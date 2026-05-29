package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import com.codex.mobilecontrol.model.MobileThreadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailTimelineSupportTest {

    @Test
    fun `visible messages keep loaded paged history entries`() {
        val detail = sampleDetail(
            messages = (1..24).map { index ->
                ThreadMessage(
                    messageId = "m$index",
                    threadId = "thread-1",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "message-$index",
                    timestamp = "2026-04-23T00:00:${index.toString().padStart(2, '0')}Z"
                )
            }
        )

        val visibleMessages = DetailTimelineSupport.visibleMessages(detail)

        assertEquals(24, visibleMessages.size)
        assertEquals("m1", visibleMessages.first().messageId)
        assertEquals("m24", visibleMessages.last().messageId)
    }

    @Test
    fun `visible messages preserve user and assistant conversation order`() {
        val detail = sampleDetail(
            messages = listOf(
                ThreadMessage(
                    messageId = "u1",
                    threadId = "thread-1",
                    role = ThreadMessageRole.USER,
                    kind = "text",
                    text = "我自己发的消息",
                    timestamp = "2026-04-23T00:00:01Z"
                ),
                ThreadMessage(
                    messageId = "a1",
                    threadId = "thread-1",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "Codex 回复",
                    timestamp = "2026-04-23T00:00:02Z"
                )
            )
        )

        val visibleMessages = DetailTimelineSupport.visibleMessages(detail)

        assertEquals(listOf("u1", "a1"), visibleMessages.map { it.messageId })
    }

    @Test
    fun `visible messages hide internal control context entries`() {
        val detail = sampleDetail(
            messages = listOf(
                ThreadMessage(
                    messageId = "internal-aborted",
                    threadId = "thread-1",
                    role = ThreadMessageRole.USER,
                    kind = "text",
                    text = "<turn_aborted>The user interrupted the previous turn on purpose.</turn_aborted>",
                    timestamp = "2026-04-23T00:00:01Z"
                ),
                ThreadMessage(
                    messageId = "internal-env",
                    threadId = "thread-1",
                    role = ThreadMessageRole.USER,
                    kind = "text",
                    text = "<environment_context><shell>powershell</shell><current_date>2026-04-27</current_date></environment_context>",
                    timestamp = "2026-04-23T00:00:02Z"
                ),
                ThreadMessage(
                    messageId = "real-user",
                    threadId = "thread-1",
                    role = ThreadMessageRole.USER,
                    kind = "text",
                    text = "开启网关",
                    timestamp = "2026-04-23T00:00:03Z"
                )
            )
        )

        val visibleMessages = DetailTimelineSupport.visibleMessages(detail)

        assertEquals(listOf("real-user"), visibleMessages.map { it.messageId })
    }

    @Test
    fun `conversation items group consecutive image messages before separate text`() {
        val detail = sampleDetail(
            messages = listOf(
                ThreadMessage(
                    messageId = "img-1",
                    threadId = "thread-1",
                    role = ThreadMessageRole.USER,
                    kind = "image",
                    imageUrl = "https://example.test/first.jpg",
                    thumbnailUrl = "https://example.test/first-thumb.jpg",
                    fileName = "first.jpg",
                    timestamp = "2026-04-29T06:57:30Z"
                ),
                ThreadMessage(
                    messageId = "img-2",
                    threadId = "thread-1",
                    role = ThreadMessageRole.USER,
                    kind = "image",
                    imageUrl = "https://example.test/second.jpg",
                    thumbnailUrl = "https://example.test/second-thumb.jpg",
                    fileName = "second.jpg",
                    timestamp = "2026-04-29T06:57:30Z"
                ),
                ThreadMessage(
                    messageId = "text-1",
                    threadId = "thread-1",
                    role = ThreadMessageRole.USER,
                    kind = "text",
                    text = "测试，回复图片内容",
                    timestamp = "2026-04-29T06:57:30Z"
                )
            )
        )

        val items = DetailTimelineSupport.conversationItems(detail)

        assertEquals(2, items.size)
        val imageGroup = items[0] as DetailConversationItem.ImageGroup
        assertEquals(listOf("img-1", "img-2"), imageGroup.messages.map { it.messageId })
        val textMessage = items[1] as DetailConversationItem.MessageRow
        assertEquals("测试，回复图片内容", textMessage.message.text)
    }

    @Test
    fun `conversation items split file attachment text below file`() {
        val detail = sampleDetail(
            messages = listOf(
                ThreadMessage(
                    messageId = "file-1",
                    threadId = "thread-1",
                    role = ThreadMessageRole.USER,
                    kind = "file",
                    text = "这是诊断日志",
                    fileUrl = "https://example.test/diagnostics.zip",
                    fileName = "diagnostics.zip",
                    mimeType = "application/zip",
                    timestamp = "2026-04-29T06:57:30Z"
                )
            )
        )

        val items = DetailTimelineSupport.conversationItems(detail)

        assertEquals(2, items.size)
        val fileMessage = items[0] as DetailConversationItem.MessageRow
        assertEquals("file", fileMessage.message.kind)
        assertEquals("diagnostics.zip", fileMessage.message.fileName)
        assertEquals(null, fileMessage.message.text)
        val textMessage = items[1] as DetailConversationItem.MessageRow
        assertEquals("text", textMessage.message.kind)
        assertEquals("这是诊断日志", textMessage.message.text)
        assertEquals("2026-04-29T06:57:30Z", textMessage.message.timestamp)
    }

    @Test
    fun `completed detail collapses execution process behind latest assistant summary`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            messages = listOf(
                messageAt("u1", ThreadMessageRole.USER, "修复返回问题", "2026-04-23T00:00:01Z"),
                messageAt("a1", ThreadMessageRole.ASSISTANT, "我先复现。", "2026-04-23T00:02:00Z"),
                messageAt("a2", ThreadMessageRole.ASSISTANT, "红灯已经有了。", "2026-04-23T00:05:00Z"),
                messageAt("a3", ThreadMessageRole.ASSISTANT, "实现已经落下。", "2026-04-23T00:08:00Z"),
                messageAt("a4", ThreadMessageRole.ASSISTANT, "窄测已转绿。", "2026-04-23T00:11:00Z"),
                messageAt("final", ThreadMessageRole.ASSISTANT, "修好了，并已出新版。", "2026-04-23T00:14:44Z")
            )
        )

        val items = DetailTimelineSupport.conversationItems(detail)

        assertEquals(3, items.size)
        val userMessage = items[0] as DetailConversationItem.MessageRow
        assertEquals("u1", userMessage.message.messageId)
        val history = items[1] as DetailConversationItem.HistoryGroup
        assertFalse(history.expanded)
        assertEquals(4, history.messages.size)
        assertEquals("12m 44s", history.processedDurationLabel)
        assertEquals(listOf("a1", "a2", "a3", "a4"), history.messages.map { it.messageId })
        val finalMessage = items[2] as DetailConversationItem.MessageRow
        assertEquals("final", finalMessage.message.messageId)
    }

    @Test
    fun `expanded completed detail shows collapsed execution process before final summary`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            messages = listOf(
                messageAt("u1", ThreadMessageRole.USER, "修复返回问题", "2026-04-23T00:00:01Z"),
                messageAt("a1", ThreadMessageRole.ASSISTANT, "我先复现。", "2026-04-23T00:02:00Z"),
                messageAt("a2", ThreadMessageRole.ASSISTANT, "红灯已经有了。", "2026-04-23T00:05:00Z"),
                messageAt("a3", ThreadMessageRole.ASSISTANT, "实现已经落下。", "2026-04-23T00:08:00Z"),
                messageAt("a4", ThreadMessageRole.ASSISTANT, "窄测已转绿。", "2026-04-23T00:11:00Z"),
                messageAt("final", ThreadMessageRole.ASSISTANT, "修好了，并已出新版。", "2026-04-23T00:14:44Z")
            )
        )

        val items = DetailTimelineSupport.conversationItems(detail, expandHistory = true)

        assertEquals(7, items.size)
        val history = items[1] as DetailConversationItem.HistoryGroup
        assertTrue(history.expanded)
        assertEquals(4, history.messages.size)
        assertEquals(listOf("a1", "a2", "a3", "a4"), history.messages.map { it.messageId })
        assertEquals(
            listOf("u1", "a1", "a2", "a3", "a4", "final"),
            items.filterIsInstance<DetailConversationItem.MessageRow>()
                .map { it.message.messageId }
        )
    }

    @Test
    fun `completed detail collapses only the latest request round`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            messages = listOf(
                messageAt("older-user", ThreadMessageRole.USER, "先研究一下项目", "2026-04-23T00:00:01Z"),
                messageAt("older-final", ThreadMessageRole.ASSISTANT, "这是上一轮总结。", "2026-04-23T00:00:40Z"),
                messageAt("latest-user", ThreadMessageRole.USER, "再修一下折叠入口", "2026-04-23T00:01:00Z"),
                messageAt("latest-progress-1", ThreadMessageRole.ASSISTANT, "我先复现边界。", "2026-04-23T00:03:00Z"),
                messageAt("latest-progress-2", ThreadMessageRole.ASSISTANT, "红灯已经加上。", "2026-04-23T00:05:00Z"),
                messageAt("latest-progress-3", ThreadMessageRole.ASSISTANT, "实现正在验证。", "2026-04-23T00:07:00Z"),
                messageAt("latest-final", ThreadMessageRole.ASSISTANT, "这轮已修好。", "2026-04-23T00:09:30Z")
            )
        )

        val items = DetailTimelineSupport.conversationItems(detail)

        assertEquals(5, items.size)
        assertEquals(
            listOf("older-user", "older-final"),
            items.take(2)
                .filterIsInstance<DetailConversationItem.MessageRow>()
                .map { it.message.messageId }
        )
        val latestUser = items[2] as DetailConversationItem.MessageRow
        assertEquals("latest-user", latestUser.message.messageId)
        val history = items[3] as DetailConversationItem.HistoryGroup
        assertEquals(
            listOf("latest-progress-1", "latest-progress-2", "latest-progress-3"),
            history.messages.map { it.messageId }
        )
        val finalMessage = items[4] as DetailConversationItem.MessageRow
        assertEquals("latest-final", finalMessage.message.messageId)
    }

    @Test
    fun `completed detail keeps short latest request round visible while folding earlier rounds`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            messages = listOf(
                messageAt("older-user", ThreadMessageRole.USER, "先研究一下项目", "2026-04-23T00:00:01Z"),
                messageAt("older-progress-1", ThreadMessageRole.ASSISTANT, "我先看源码。", "2026-04-23T00:02:00Z"),
                messageAt("older-progress-2", ThreadMessageRole.ASSISTANT, "我再看日志。", "2026-04-23T00:04:00Z"),
                messageAt("older-progress-3", ThreadMessageRole.ASSISTANT, "我整理证据。", "2026-04-23T00:06:00Z"),
                messageAt("older-final", ThreadMessageRole.ASSISTANT, "这是上一轮总结。", "2026-04-23T00:08:30Z"),
                messageAt("latest-user", ThreadMessageRole.USER, "测试，回复66999799", "2026-04-23T00:09:00Z"),
                messageAt("latest-final", ThreadMessageRole.ASSISTANT, "66999799", "2026-04-23T00:09:20Z")
            )
        )

        val items = DetailTimelineSupport.conversationItems(detail)

        assertEquals(5, items.size)
        val olderUser = items[0] as DetailConversationItem.MessageRow
        assertEquals("older-user", olderUser.message.messageId)
        val olderHistory = items[1] as DetailConversationItem.HistoryGroup
        assertEquals(
            listOf("older-progress-1", "older-progress-2", "older-progress-3"),
            olderHistory.messages.map { it.messageId }
        )
        assertEquals(
            listOf("older-user", "older-final", "latest-user", "latest-final"),
            items.filterIsInstance<DetailConversationItem.MessageRow>().map { it.message.messageId }
        )
    }

    @Test
    fun `completed detail keeps short processing rounds visible even with multiple progress messages`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            messages = listOf(
                messageAt("user", ThreadMessageRole.USER, "每天都应该有消息。", "2026-05-05T15:23:50Z"),
                messageAt("progress-1", ThreadMessageRole.ASSISTANT, "先拉一下时间线。", "2026-05-05T15:24:00Z"),
                messageAt("progress-2", ThreadMessageRole.ASSISTANT, "再核对 recentMessages。", "2026-05-05T15:24:30Z"),
                messageAt("progress-3", ThreadMessageRole.ASSISTANT, "继续看折叠边界。", "2026-05-05T15:24:50Z"),
                messageAt("final", ThreadMessageRole.ASSISTANT, "对，你这个判断是对的。", "2026-05-05T15:25:22Z")
            )
        )

        val items = DetailTimelineSupport.conversationItems(detail)

        assertFalse(items.any { it is DetailConversationItem.HistoryGroup })
        assertEquals(
            listOf("user", "progress-1", "progress-2", "progress-3", "final"),
            items.filterIsInstance<DetailConversationItem.MessageRow>().map { it.message.messageId }
        )
    }

    @Test
    fun `completed detail folds each long request round independently`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            messages = listOf(
                messageAt("round1-user", ThreadMessageRole.USER, "第一轮需求", "2026-04-23T00:00:01Z"),
                messageAt("round1-progress-1", ThreadMessageRole.ASSISTANT, "第一轮排查。", "2026-04-23T00:02:00Z"),
                messageAt("round1-progress-2", ThreadMessageRole.ASSISTANT, "第一轮实现。", "2026-04-23T00:04:00Z"),
                messageAt("round1-progress-3", ThreadMessageRole.ASSISTANT, "第一轮验证。", "2026-04-23T00:06:00Z"),
                messageAt("round1-final", ThreadMessageRole.ASSISTANT, "第一轮总结。", "2026-04-23T00:08:20Z"),
                messageAt("round2-user", ThreadMessageRole.USER, "第二轮需求", "2026-04-23T00:09:00Z"),
                messageAt("round2-progress-1", ThreadMessageRole.ASSISTANT, "第二轮排查。", "2026-04-23T00:11:00Z"),
                messageAt("round2-progress-2", ThreadMessageRole.ASSISTANT, "第二轮实现。", "2026-04-23T00:13:00Z"),
                messageAt("round2-progress-3", ThreadMessageRole.ASSISTANT, "第二轮验证。", "2026-04-23T00:15:00Z"),
                messageAt("round2-final", ThreadMessageRole.ASSISTANT, "第二轮总结。", "2026-04-23T00:17:20Z")
            )
        )

        val items = DetailTimelineSupport.conversationItems(detail)

        assertEquals(6, items.size)
        assertEquals(
            listOf("round1-user", "round1-final", "round2-user", "round2-final"),
            items.filterIsInstance<DetailConversationItem.MessageRow>().map { it.message.messageId }
        )
        assertEquals(
            listOf(
                listOf("round1-progress-1", "round1-progress-2", "round1-progress-3"),
                listOf("round2-progress-1", "round2-progress-2", "round2-progress-3")
            ),
            items.filterIsInstance<DetailConversationItem.HistoryGroup>()
                .map { history -> history.messages.map { it.messageId } }
        )
    }

    @Test
    fun `completed detail expands only the selected long request round`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            messages = listOf(
                messageAt("round1-user", ThreadMessageRole.USER, "第一轮需求", "2026-04-23T00:00:01Z"),
                messageAt("round1-progress-1", ThreadMessageRole.ASSISTANT, "第一轮排查。", "2026-04-23T00:02:00Z"),
                messageAt("round1-progress-2", ThreadMessageRole.ASSISTANT, "第一轮实现。", "2026-04-23T00:04:00Z"),
                messageAt("round1-progress-3", ThreadMessageRole.ASSISTANT, "第一轮验证。", "2026-04-23T00:06:00Z"),
                messageAt("round1-final", ThreadMessageRole.ASSISTANT, "第一轮总结。", "2026-04-23T00:08:20Z"),
                messageAt("round2-user", ThreadMessageRole.USER, "第二轮需求", "2026-04-23T00:09:00Z"),
                messageAt("round2-progress-1", ThreadMessageRole.ASSISTANT, "第二轮排查。", "2026-04-23T00:11:00Z"),
                messageAt("round2-progress-2", ThreadMessageRole.ASSISTANT, "第二轮实现。", "2026-04-23T00:13:00Z"),
                messageAt("round2-progress-3", ThreadMessageRole.ASSISTANT, "第二轮验证。", "2026-04-23T00:15:00Z"),
                messageAt("round2-final", ThreadMessageRole.ASSISTANT, "第二轮总结。", "2026-04-23T00:17:20Z")
            )
        )
        val collapsedItems = DetailTimelineSupport.conversationItems(detail)
        val historyGroups = collapsedItems.filterIsInstance<DetailConversationItem.HistoryGroup>()

        val items = DetailTimelineSupport.conversationItems(
            detail,
            expandedHistoryKeys = setOf(historyGroups[1].key)
        )

        val expandedGroups = items.filterIsInstance<DetailConversationItem.HistoryGroup>()
        assertEquals(listOf(false, true), expandedGroups.map { it.expanded })
        assertEquals(
            listOf(
                "round1-user",
                "round1-final",
                "round2-user",
                "round2-progress-1",
                "round2-progress-2",
                "round2-progress-3",
                "round2-final"
            ),
            items.filterIsInstance<DetailConversationItem.MessageRow>().map { it.message.messageId }
        )
    }

    @Test
    fun `running detail keeps execution process visible while active`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.RUNNING,
            messages = listOf(
                message("u1", ThreadMessageRole.USER, "排查问题", "01"),
                message("a1", ThreadMessageRole.ASSISTANT, "我先看证据。", "02"),
                message("a2", ThreadMessageRole.ASSISTANT, "继续查。", "03"),
                message("a3", ThreadMessageRole.ASSISTANT, "还在验证。", "04"),
                message("a4", ThreadMessageRole.ASSISTANT, "准备改。", "05"),
                message("a5", ThreadMessageRole.ASSISTANT, "当前进度。", "06")
            )
        )

        val items = DetailTimelineSupport.conversationItems(detail)

        assertFalse(items.any { it is DetailConversationItem.HistoryGroup })
        assertEquals(
            listOf("u1", "a1", "a2", "a3", "a4", "a5"),
            items.filterIsInstance<DetailConversationItem.MessageRow>()
                .map { it.message.messageId }
        )
    }

    @Test
    fun `running detail still folds completed earlier rounds`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.RUNNING,
            messages = listOf(
                messageAt("older-user", ThreadMessageRole.USER, "先修导出日志", "2026-04-23T00:00:01Z"),
                messageAt("older-progress-1", ThreadMessageRole.ASSISTANT, "我先查实现。", "2026-04-23T00:02:00Z"),
                messageAt("older-progress-2", ThreadMessageRole.ASSISTANT, "红灯已经补上。", "2026-04-23T00:04:00Z"),
                messageAt("older-progress-3", ThreadMessageRole.ASSISTANT, "实现正在验证。", "2026-04-23T00:06:00Z"),
                messageAt("older-final", ThreadMessageRole.ASSISTANT, "导出日志修好了。", "2026-04-23T00:08:30Z"),
                messageAt("latest-user", ThreadMessageRole.USER, "再看折叠问题", "2026-04-23T00:09:00Z"),
                messageAt("latest-progress-1", ThreadMessageRole.ASSISTANT, "我先读日志。", "2026-04-23T00:09:30Z"),
                messageAt("latest-progress-2", ThreadMessageRole.ASSISTANT, "继续定位。", "2026-04-23T00:10:00Z"),
                messageAt("latest-progress-3", ThreadMessageRole.ASSISTANT, "还在处理。", "2026-04-23T00:10:30Z")
            )
        )

        val items = DetailTimelineSupport.conversationItems(detail)

        val historyGroups = items.filterIsInstance<DetailConversationItem.HistoryGroup>()
        assertEquals(1, historyGroups.size)
        assertEquals(
            listOf("older-progress-1", "older-progress-2", "older-progress-3"),
            historyGroups.single().messages.map { it.messageId }
        )
        assertEquals(
            listOf(
                "older-user",
                "older-final",
                "latest-user",
                "latest-progress-1",
                "latest-progress-2",
                "latest-progress-3"
            ),
            items.filterIsInstance<DetailConversationItem.MessageRow>()
                .map { it.message.messageId }
        )
    }

    @Test
    fun `visible events keep only the latest timeline items`() {
        val detail = sampleDetail(
            events = (1..7).map { index ->
                ThreadEvent(
                    eventId = "e$index",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "event-$index",
                    timestamp = "2026-04-23T00:00:0${index}Z"
                )
            }
        )

        val visibleEvents = DetailTimelineSupport.visibleEvents(detail)

        assertEquals(DetailTimelineSupport.MAX_VISIBLE_EVENTS, visibleEvents.size)
        assertEquals("e4", visibleEvents.first().eventId)
        assertEquals("e7", visibleEvents.last().eventId)
    }

    @Test
    fun `timeline items keep chat messages and hide recent events section`() {
        val detail = sampleDetail(
            messages = listOf(
                ThreadMessage(
                    messageId = "m1",
                    threadId = "thread-1",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "latest reply",
                    timestamp = "2026-04-23T00:00:05Z"
                )
            ),
            events = listOf(
                ThreadEvent(
                    eventId = "e1",
                    threadId = "thread-1",
                    kind = ThreadEventKind.MESSAGE,
                    status = MobileThreadStatus.RUNNING,
                    text = "event-1",
                    timestamp = "2026-04-23T00:00:04Z"
                )
            )
        )

        val items = DetailTimelineSupport.timelineItems(detail)

        assertTrue(items.first() is DetailTimelineItem.Section)
        assertEquals(
            "聊天记录",
            (items.first() as DetailTimelineItem.Section).title
        )
        assertTrue(items[1] is DetailTimelineItem.MessageRow)
        assertEquals(2, items.size)
        assertFalse(items.any { it is DetailTimelineItem.EventRow })
        assertFalse(
            items.filterIsInstance<DetailTimelineItem.Section>().any { it.title == "最近事件" }
        )
    }

    private fun sampleDetail(
        messages: List<ThreadMessage> = emptyList(),
        events: List<ThreadEvent> = emptyList(),
        status: MobileThreadStatus = MobileThreadStatus.WAITING_INPUT
    ): ThreadDetail {
        return ThreadDetail(
            thread = ThreadListItem(
                threadId = "thread-1",
                title = "First",
                cwd = "D:/repo-a",
                status = status,
                updatedAt = "2026-04-23T00:00:00Z",
                progressSummary = "waiting input",
                needsAttention = true
            ),
            recentMessages = messages,
            recentEvents = events,
            sendAvailable = true,
            sendDisabledReason = null
        )
    }

    private fun message(
        messageId: String,
        role: ThreadMessageRole,
        text: String,
        second: String
    ): ThreadMessage {
        return messageAt(
            messageId = messageId,
            role = role,
            text = text,
            timestamp = "2026-04-23T00:00:${second}Z"
        )
    }

    private fun messageAt(
        messageId: String,
        role: ThreadMessageRole,
        text: String,
        timestamp: String
    ): ThreadMessage {
        return ThreadMessage(
            messageId = messageId,
            threadId = "thread-1",
            role = role,
            kind = "text",
            text = text,
            timestamp = timestamp
        )
    }
}
