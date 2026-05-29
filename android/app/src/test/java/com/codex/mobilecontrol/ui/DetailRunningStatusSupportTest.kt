package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadFileChanges
import com.codex.mobilecontrol.model.ThreadListItem
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailRunningStatusSupportTest {

    @Test
    fun `running subtitle appends elapsed duration from latest running event`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.RUNNING,
            progressSummary = "线程正在运行",
            events = listOf(
                sampleEvent(
                    id = "old-completed",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    timestamp = "2026-05-13T09:24:19Z"
                ),
                sampleEvent(
                    id = "running",
                    kind = ThreadEventKind.TURN_STARTED,
                    status = MobileThreadStatus.RUNNING,
                    timestamp = "2026-05-13T11:29:21Z"
                )
            )
        )

        val result = DetailRunningStatusSupport.subtitle(
            detailThread = detail.thread,
            detail = detail,
            now = Instant.parse("2026-05-13T12:31:45Z")
        )

        assertEquals("线程正在运行 · 已运行 1h 2m", result)
    }

    @Test
    fun `running subtitle keeps the first real turn start when later heartbeats arrive`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.RUNNING,
            progressSummary = "线程正在运行",
            events = listOf(
                sampleEvent(
                    id = "old-completed",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    timestamp = "2026-05-13T12:29:01.322Z"
                ),
                sampleEvent(
                    id = "client-start",
                    kind = ThreadEventKind.TURN_STARTED,
                    status = MobileThreadStatus.RUNNING,
                    text = "已提交到桌面发送",
                    timestamp = "2026-05-13T12:37:32.954Z"
                ),
                sampleEvent(
                    id = "turn-started",
                    kind = ThreadEventKind.TURN_STARTED,
                    status = MobileThreadStatus.RUNNING,
                    text = "开始处理新的输入",
                    timestamp = "2026-05-13T12:37:41.687Z"
                ),
                sampleEvent(
                    id = "turn-heartbeat-1",
                    kind = ThreadEventKind.TURN_STARTED,
                    status = MobileThreadStatus.RUNNING,
                    text = "线程正在运行",
                    timestamp = "2026-05-13T12:40:53.448Z"
                ),
                sampleEvent(
                    id = "turn-heartbeat-2",
                    kind = ThreadEventKind.TURN_STARTED,
                    status = MobileThreadStatus.RUNNING,
                    text = "线程正在运行",
                    timestamp = "2026-05-13T12:53:22.088Z"
                )
            )
        )

        val result = DetailRunningStatusSupport.subtitle(
            detailThread = detail.thread,
            detail = detail,
            now = Instant.parse("2026-05-13T12:53:39.693Z")
        )

        assertEquals("线程正在运行 · 已运行 15m 58s", result)
    }

    @Test
    fun `running subtitle prefers backend running start over later heartbeat events`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.RUNNING,
            progressSummary = "线程正在运行",
            runningStartedAt = "2026-05-13T09:26:41Z",
            events = listOf(
                sampleEvent(
                    id = "turn-heartbeat",
                    kind = ThreadEventKind.TURN_STARTED,
                    status = MobileThreadStatus.RUNNING,
                    text = "线程正在运行",
                    timestamp = "2026-05-13T11:29:21Z"
                )
            )
        )

        val result = DetailRunningStatusSupport.subtitle(
            detailThread = detail.thread,
            detail = detail,
            now = Instant.parse("2026-05-13T12:31:45Z")
        )

        assertEquals("线程正在运行 · 已运行 3h 5m", result)
    }

    @Test
    fun `running subtitle keeps original text without a running start event`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.RUNNING,
            progressSummary = "线程正在运行"
        )

        val result = DetailRunningStatusSupport.subtitle(
            detailThread = detail.thread,
            detail = detail,
            now = Instant.parse("2026-05-13T12:31:45Z")
        )

        assertEquals("线程正在运行", result)
    }

    @Test
    fun `non running subtitle keeps original status text`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            progressSummary = "本轮已完成",
            events = listOf(
                sampleEvent(
                    id = "running",
                    kind = ThreadEventKind.TURN_STARTED,
                    status = MobileThreadStatus.RUNNING,
                    timestamp = "2026-05-13T11:29:21Z"
                )
            )
        )

        val result = DetailRunningStatusSupport.subtitle(
            detailThread = detail.thread,
            detail = detail,
            now = Instant.parse("2026-05-13T12:31:45Z")
        )

        assertEquals("本轮已完成", result)
    }

    private fun sampleDetail(
        status: MobileThreadStatus,
        progressSummary: String,
        runningStartedAt: String? = null,
        events: List<ThreadEvent> = emptyList()
    ): ThreadDetail {
        val thread = ThreadListItem(
            threadId = "thread-1",
            title = "测试线程",
            cwd = "D:\\projects\\codex-mobile-control",
            status = status,
            updatedAt = "2026-05-13T12:00:00Z",
            progressSummary = progressSummary,
            needsAttention = false,
            runningStartedAt = runningStartedAt
        )
        return ThreadDetail(
            thread = thread,
            recentMessages = emptyList(),
            recentEvents = events,
            sendAvailable = true,
            sendDisabledReason = null,
            fileChanges = ThreadFileChanges(
                summary = "",
                changedFiles = 0,
                added = 0,
                removed = 0,
                items = emptyList()
            ),
            composerState = null
        )
    }

    private fun sampleEvent(
        id: String,
        kind: ThreadEventKind,
        status: MobileThreadStatus,
        text: String = "",
        timestamp: String
    ): ThreadEvent {
        return ThreadEvent(
            eventId = id,
            threadId = "thread-1",
            kind = kind,
            status = status,
            text = text,
            timestamp = timestamp
        )
    }
}
