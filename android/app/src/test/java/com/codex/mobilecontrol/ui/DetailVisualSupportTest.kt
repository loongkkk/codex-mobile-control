package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadFileChangeItem
import com.codex.mobilecontrol.model.ThreadFileChanges
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailVisualSupportTest {

    @Test
    fun `preview body source combines recent assistant and system messages`() {
        val detail = detailWithMessages(
            message("1", ThreadMessageRole.ASSISTANT, "第一条助手消息"),
            message("2", ThreadMessageRole.USER, "用户消息不应该进入详情正文"),
            message("3", ThreadMessageRole.ASSISTANT, "第二条助手消息"),
            message("4", ThreadMessageRole.SYSTEM, "系统补充消息")
        )

        val source = DetailVisualSupport.previewBodySource(detail, detail.thread)

        assertEquals("第一条助手消息\n\n第二条助手消息\n\n系统补充消息", source)
        assertFalse(source.contains("用户消息"))
    }

    @Test
    fun `preview body source limits to the newest three non user messages`() {
        val detail = detailWithMessages(
            message("1", ThreadMessageRole.ASSISTANT, "旧消息"),
            message("2", ThreadMessageRole.ASSISTANT, "消息二"),
            message("3", ThreadMessageRole.ASSISTANT, "消息三"),
            message("4", ThreadMessageRole.ASSISTANT, "消息四")
        )

        val source = DetailVisualSupport.previewBodySource(detail, detail.thread)

        assertFalse(source.contains("旧消息"))
        assertTrue(source.contains("消息二"))
        assertTrue(source.contains("消息三"))
        assertTrue(source.contains("消息四"))
    }

    @Test
    fun `visual content keeps markdown syntax for native markdown renderer`() {
        val source = readDetailVisualSource()

        assertTrue(source.contains("val bodyMarkdown: String"))
        assertTrue(source.contains("bodyMarkdown = bodySource.ifBlank"))
        assertFalse(source.contains("private fun buildBodyText("))
    }

    @Test
    fun `detail visual placeholders do not expose developer todo wording`() {
        val source = readDetailVisualSource()
        val strings = readStrings()
        val layout = readActivityMainLayout()

        assertFalse(source.contains("\"TODO\""))
        assertFalse(source.contains("TODO:"))
        assertFalse(strings.contains("TODO"))
        assertFalse(layout.contains("TODO"))
    }

    @Test
    fun `file summary keeps explicit changed added and removed counts`() {
        val source = """
            2 个文件已更改 +104 -1
            gateway/src/mobile-gateway-service.ts +26 -1
            gateway/tests/mobile-gateway-service.test.ts +78 -0
        """.trimIndent()

        assertEquals("2 个文件已更改 +104 -1", extractFileSummary(source))
        assertEquals(
            listOf(
                DetailFileChangeItem("gateway/src/mobile-gateway-service.ts", "+26", "-1"),
                DetailFileChangeItem("gateway/tests/mobile-gateway-service.test.ts", "+78", "-0")
            ),
            extractFileItems(source)
        )
    }

    @Test
    fun `file summary ignores apk artifact names and date-like hyphens`() {
        val source = """
            最新 APK：D:\projects\codex-mobile-control\codex-mobile-control-debug-20260426-011156.apk
            下载入口：/downloads/latest.apk
            本地路径：/sdcard/Download/codex-mobile-control-latest-2.apk
            gateway 测试：45 passed
        """.trimIndent()

        assertNull(extractFileSummary(source))
        assertTrue(extractFileItems(source).isEmpty())
    }

    @Test
    fun `structured file changes override apk artifact text`() {
        val source = """
            下载最新安装包，下载成功。
            - 路径：/downloads/latest.apk
            - 本地：/sdcard/Download/codex-mobile-control-latest-2.apk
        """.trimIndent()
        val detail = detailWithMessages(
            message("1", ThreadMessageRole.ASSISTANT, source),
            fileChanges = ThreadFileChanges(
                summary = "2 个文件已更改 +104 -1",
                changedFiles = 2,
                added = 104,
                removed = 1,
                items = listOf(
                    ThreadFileChangeItem("gateway/src/mobile-gateway-service.ts", 26, 1),
                    ThreadFileChangeItem("gateway/tests/mobile-gateway-service.test.ts", 78, 0)
                )
            )
        )

        assertEquals("2 个文件已更改 +104 -1", DetailVisualSupport.resolveFileSummary(detail, source))
        assertEquals(
            listOf(
                DetailFileChangeItem("gateway/src/mobile-gateway-service.ts", "+26", "-1"),
                DetailFileChangeItem("gateway/tests/mobile-gateway-service.test.ts", "+78", "-0")
            ),
            DetailVisualSupport.resolveFileItems(detail, source)
        )
    }

    @Test
    fun `structured file changes are not truncated when more than four files changed`() {
        val detail = detailWithMessages(
            message("1", ThreadMessageRole.ASSISTANT, "8 个文件已更改 +124 -4"),
            fileChanges = ThreadFileChanges(
                summary = "8 个文件已更改 +124 -4",
                changedFiles = 8,
                added = 124,
                removed = 4,
                items = (1..8).map { index ->
                    ThreadFileChangeItem(
                        path = "android/app/src/main/File$index.kt",
                        added = index,
                        removed = if (index % 2 == 0) 1 else 0
                    )
                }
            )
        )

        val items = DetailVisualSupport.resolveFileItems(detail, "8 个文件已更改 +124 -4")

        assertEquals(8, items.size)
        assertEquals("android/app/src/main/File8.kt", items.last().path)
    }

    @Test
    fun `error content prefers latest error event text`() {
        val detail = detailWithMessages(
            message("1", ThreadMessageRole.ASSISTANT, "普通消息"),
            status = MobileThreadStatus.ERROR,
            recentEvents = listOf(
                event("event-1", ThreadEventKind.MESSAGE, null, "普通事件"),
                event("event-2", ThreadEventKind.ERROR, MobileThreadStatus.ERROR, "命令执行失败：exit 1")
            )
        )

        val content = DetailVisualSupport.resolveErrorContent(detail, detail.thread)

        assertEquals("异常详情", content?.title)
        assertEquals("命令执行失败：exit 1", content?.summary)
        assertTrue(content?.showCard == true)
    }

    @Test
    fun `error content falls back to disabled reason and progress summary`() {
        val detail = detailWithMessages(
            status = MobileThreadStatus.ERROR,
            sendDisabledReason = "当前线程异常，暂不能发送",
            progressSummary = "本轮异常结束"
        )

        val content = DetailVisualSupport.resolveErrorContent(detail, detail.thread)

        assertEquals("当前线程异常，暂不能发送", content?.summary)
        assertEquals("本轮异常结束", content?.evidence)
    }

    @Test
    fun `stale desktop error does not show after a newer completed event`() {
        val detail = detailWithMessages(
            status = MobileThreadStatus.COMPLETED,
            recentEvents = listOf(
                event(
                    "client-1:desktop-error",
                    ThreadEventKind.ERROR,
                    MobileThreadStatus.ERROR,
                    "桌面发送失败"
                ).copy(timestamp = "2026-05-09T18:22:44.296Z"),
                event(
                    "turn-2:completed",
                    ThreadEventKind.TURN_COMPLETED,
                    MobileThreadStatus.COMPLETED,
                    "本轮已完成"
                ).copy(timestamp = "2026-05-09T19:43:09.000Z")
            )
        )

        val content = DetailVisualSupport.resolveErrorContent(detail, detail.thread)

        assertNull(content)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractFileItems(source: String): List<DetailFileChangeItem> {
        val method = DetailVisualSupport::class.java.getDeclaredMethod(
            "extractFileItems",
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(DetailVisualSupport, source) as List<DetailFileChangeItem>
    }

    private fun extractFileSummary(source: String): String? {
        val method = DetailVisualSupport::class.java.getDeclaredMethod(
            "extractFileSummary",
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(DetailVisualSupport, source) as String?
    }

    private fun readDetailVisualSource(): String {
        val candidates = listOf(
            java.io.File("app/src/main/java/com/codex/mobilecontrol/ui/DetailVisualSupport.kt"),
            java.io.File("src/main/java/com/codex/mobilecontrol/ui/DetailVisualSupport.kt")
        )
        val sourceFile = candidates.firstOrNull(java.io.File::isFile)
            ?: error("DetailVisualSupport.kt not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }

    private fun readStrings(): String {
        val candidates = listOf(
            java.io.File("app/src/main/res/values/strings.xml"),
            java.io.File("src/main/res/values/strings.xml")
        )
        val sourceFile = candidates.firstOrNull(java.io.File::isFile)
            ?: error("strings.xml not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }

    private fun readActivityMainLayout(): String {
        val candidates = listOf(
            java.io.File("app/src/main/res/layout/activity_main.xml"),
            java.io.File("src/main/res/layout/activity_main.xml")
        )
        val sourceFile = candidates.firstOrNull(java.io.File::isFile)
            ?: error("activity_main.xml not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }

    private fun detailWithMessages(
        vararg messages: ThreadMessage,
        fileChanges: ThreadFileChanges? = null,
        status: MobileThreadStatus = MobileThreadStatus.COMPLETED,
        recentEvents: List<ThreadEvent> = emptyList(),
        sendDisabledReason: String? = null,
        progressSummary: String = "本轮已完成"
    ): ThreadDetail {
        val thread = ThreadListItem(
            threadId = "thread-1",
            title = "开发 Codex 控制App",
            cwd = "D:/projects/codex-mobile-control",
            status = status,
            updatedAt = "2026-04-25T00:00:00Z",
            progressSummary = progressSummary,
            needsAttention = false
        )
        return ThreadDetail(
            thread = thread,
            recentMessages = messages.toList(),
            recentEvents = recentEvents,
            sendAvailable = true,
            sendDisabledReason = sendDisabledReason,
            fileChanges = fileChanges
        )
    }

    private fun event(
        id: String,
        kind: ThreadEventKind,
        status: MobileThreadStatus?,
        text: String
    ): ThreadEvent {
        return ThreadEvent(
            eventId = id,
            threadId = "thread-1",
            kind = kind,
            status = status,
            text = text,
            timestamp = "2026-04-25T00:00:00Z"
        )
    }

    private fun message(
        id: String,
        role: ThreadMessageRole,
        text: String
    ): ThreadMessage {
        return ThreadMessage(
            messageId = id,
            threadId = "thread-1",
            role = role,
            kind = "text",
            text = text,
            timestamp = "2026-04-25T00:00:0${id}Z"
        )
    }
}
