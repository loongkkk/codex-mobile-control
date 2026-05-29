package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailAlertSupportTest {

    @Test
    fun `completion key uses turn completed event even when thread status is waiting input`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.WAITING_INPUT,
            events = listOf(
                ThreadEvent(
                    eventId = "turn-1:completed",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "本轮已完成",
                    timestamp = "2026-04-28T08:10:30.000Z"
                )
            )
        )

        val key = DetailAlertSupport.completionAlertKey(detail)

        assertEquals("thread-1|completed|2026-04-28T08:10:30.000Z", key)
    }

    @Test
    fun `completion signal suppresses normal new message alert for final summary`() {
        val latestMessage = ThreadMessage(
            messageId = "assistant-final",
            threadId = "thread-1",
            role = ThreadMessageRole.ASSISTANT,
            kind = "text",
            text = "已处理 20m 22s，提交：4568b13",
            timestamp = "2026-04-28T08:10:30.000Z"
        )
        val detail = sampleDetail(
            status = MobileThreadStatus.WAITING_INPUT,
            messages = listOf(latestMessage),
            events = listOf(
                ThreadEvent(
                    eventId = "turn-1:completed",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "本轮已完成",
                    timestamp = "2026-04-28T08:10:30.000Z"
                )
            )
        )

        assertTrue(
            DetailAlertSupport.shouldSuppressNewMessageAlertForCompletion(detail, latestMessage)
        )
    }

    @Test
    fun `completion alert body includes processed duration from turn events`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            events = listOf(
                ThreadEvent(
                    eventId = "turn-1:started",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_STARTED,
                    status = MobileThreadStatus.RUNNING,
                    text = "开始处理新的输入",
                    timestamp = "2026-04-28T08:10:00.000Z"
                ),
                ThreadEvent(
                    eventId = "turn-1:completed",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "本轮已完成",
                    timestamp = "2026-04-28T08:12:13.000Z"
                )
            )
        )

        val body = DetailAlertSupport.completionAlertBody(detail, fallback = "本轮已完成")

        assertEquals("本轮已完成，用时: 2m 13s", body)
    }

    @Test
    fun `completion alert body extracts processed duration from final summary`() {
        val latestMessage = ThreadMessage(
            messageId = "assistant-final",
            threadId = "thread-1",
            role = ThreadMessageRole.ASSISTANT,
            kind = "text",
            text = "已处理 20m 22s，提交：4568b13",
            timestamp = "2026-04-28T08:10:30.000Z"
        )
        val detail = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            messages = listOf(latestMessage)
        )

        val body = DetailAlertSupport.completionAlertBody(detail, fallback = "本轮已完成")

        assertEquals("本轮已完成，用时: 20m 22s", body)
    }

    @Test
    fun `alert title prefers thread title`() {
        val detail = sampleDetail(status = MobileThreadStatus.COMPLETED)

        val title = DetailAlertSupport.notificationThreadTitle(
            threadTitle = detail.thread.title,
            fallback = "有新消息"
        )

        assertEquals("模拟", title)
    }

    @Test
    fun `completion alert body extracts processed duration from notification text`() {
        val body = DetailAlertSupport.completionAlertBodyFromText(
            text = "已处理 37s",
            fallback = "当前线程运行已结束"
        )

        assertEquals("本轮已完成，用时: 37s", body)
    }

    @Test
    fun `older completion signal does not suppress a newer assistant message`() {
        val latestMessage = ThreadMessage(
            messageId = "assistant-new",
            threadId = "thread-1",
            role = ThreadMessageRole.ASSISTANT,
            kind = "text",
            text = "新的运行中回复",
            timestamp = "2026-04-28T08:11:30.000Z"
        )
        val detail = sampleDetail(
            status = MobileThreadStatus.RUNNING,
            messages = listOf(latestMessage),
            events = listOf(
                ThreadEvent(
                    eventId = "turn-1:completed",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "本轮已完成",
                    timestamp = "2026-04-28T08:10:30.000Z"
                )
            )
        )

        assertFalse(
            DetailAlertSupport.shouldSuppressNewMessageAlertForCompletion(detail, latestMessage)
        )
    }

    @Test
    fun `completed thread status remains a fallback completion key`() {
        val detail = sampleDetail(status = MobileThreadStatus.COMPLETED)

        val key = DetailAlertSupport.completionAlertKey(detail)

        assertEquals("thread-1|completed|2026-04-28T08:10:00.000Z", key)
    }

    @Test
    fun `completion key stays stable when completion event leaves recent event window`() {
        val detailWithCompletionEvent = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2026-04-28T08:10:30.000Z",
            events = listOf(
                ThreadEvent(
                    eventId = "turn-1:completed",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "本轮已完成",
                    timestamp = "2026-04-28T08:10:30.000Z"
                )
            )
        )
        val detailAfterEventWindowMoved = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2026-04-28T08:10:30.000Z",
            events = emptyList()
        )

        assertEquals(
            DetailAlertSupport.completionAlertKey(detailWithCompletionEvent),
            DetailAlertSupport.completionAlertKey(detailAfterEventWindowMoved)
        )
    }

    @Test
    fun `completion key ignores event id churn for the same completion timestamp`() {
        val firstSnapshot = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            events = listOf(
                ThreadEvent(
                    eventId = "turn-a:completed",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "本轮已完成",
                    timestamp = "2026-04-28T08:10:30.000Z"
                )
            )
        )
        val laterSnapshot = sampleDetail(
            status = MobileThreadStatus.COMPLETED,
            events = listOf(
                ThreadEvent(
                    eventId = "turn-b:completed",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "本轮已完成",
                    timestamp = "2026-04-28T08:10:30.000Z"
                )
            )
        )

        assertEquals(
            DetailAlertSupport.completionAlertKey(firstSnapshot),
            DetailAlertSupport.completionAlertKey(laterSnapshot)
        )
    }

    @Test
    fun `running thread status ignores completion event`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.RUNNING,
            events = listOf(
                ThreadEvent(
                    eventId = "turn-1:completed",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "本轮已完成",
                    timestamp = "2026-04-28T08:10:30.000Z"
                )
            )
        )

        val key = DetailAlertSupport.completionAlertKey(detail)

        assertEquals(null, key)
    }

    @Test
    fun `completion event older than newer running event is ignored`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.WAITING_INPUT,
            events = listOf(
                ThreadEvent(
                    eventId = "turn-1:completed",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "本轮已完成",
                    timestamp = "2026-04-28T08:10:30.000Z"
                ),
                ThreadEvent(
                    eventId = "log-running",
                    threadId = "thread-1",
                    kind = ThreadEventKind.STATUS_CHANGED,
                    status = MobileThreadStatus.RUNNING,
                    text = "正在处理新的请求",
                    timestamp = "2026-04-28T08:11:30.000Z"
                )
            )
        )

        val key = DetailAlertSupport.completionAlertKey(detail)

        assertEquals(null, key)
    }

    @Test
    fun `completion notification should clear when previous completion is invalidated by a new run`() {
        val completedDetail = sampleDetail(
            status = MobileThreadStatus.WAITING_INPUT,
            events = listOf(
                ThreadEvent(
                    eventId = "turn-1:completed",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "本轮已完成",
                    timestamp = "2026-04-28T08:10:30.000Z"
                )
            )
        )
        val runningAgainDetail = sampleDetail(
            status = MobileThreadStatus.RUNNING,
            events = completedDetail.recentEvents + ThreadEvent(
                eventId = "turn-2:started",
                threadId = "thread-1",
                kind = ThreadEventKind.TURN_STARTED,
                status = MobileThreadStatus.RUNNING,
                text = "开始处理新的输入",
                timestamp = "2026-04-28T08:11:30.000Z"
            )
        )

        assertTrue(
            DetailAlertSupport.shouldClearCompletionNotification(
                previousCompletionKey = DetailAlertSupport.completionAlertKey(completedDetail),
                currentCompletionKey = DetailAlertSupport.completionAlertKey(runningAgainDetail)
            )
        )
    }

    @Test
    fun `completion notification does not clear before a completion baseline exists`() {
        assertFalse(
            DetailAlertSupport.shouldClearCompletionNotification(
                previousCompletionKey = null,
                currentCompletionKey = null
            )
        )
    }

    @Test
    fun `waiting input after a running turn is not treated as a completion key`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.WAITING_INPUT,
            events = listOf(
                ThreadEvent(
                    eventId = "log-running",
                    threadId = "thread-1",
                    kind = ThreadEventKind.STATUS_CHANGED,
                    status = MobileThreadStatus.RUNNING,
                    text = "正在处理新的请求",
                    timestamp = "2026-04-28T08:09:30.000Z"
                ),
                ThreadEvent(
                    eventId = "log-waiting",
                    threadId = "thread-1",
                    kind = ThreadEventKind.STATUS_CHANGED,
                    status = MobileThreadStatus.WAITING_INPUT,
                    text = "线程正在等待新的输入",
                    timestamp = "2026-04-28T08:10:30.000Z"
                )
            )
        )

        val key = DetailAlertSupport.completionAlertKey(detail)

        assertEquals(null, key)
    }

    @Test
    fun `waiting input status does not suppress normal new message alert`() {
        val latestMessage = ThreadMessage(
            messageId = "assistant-final",
            threadId = "thread-1",
            role = ThreadMessageRole.ASSISTANT,
            kind = "text",
            text = "已处理 2m 13s",
            timestamp = "2026-04-28T08:10:20.000Z"
        )
        val detail = sampleDetail(
            status = MobileThreadStatus.WAITING_INPUT,
            messages = listOf(latestMessage),
            events = listOf(
                ThreadEvent(
                    eventId = "log-waiting",
                    threadId = "thread-1",
                    kind = ThreadEventKind.STATUS_CHANGED,
                    status = MobileThreadStatus.WAITING_INPUT,
                    text = "线程正在等待新的输入",
                    timestamp = "2026-04-28T08:10:30.000Z"
                )
            )
        )

        assertFalse(
            DetailAlertSupport.shouldSuppressNewMessageAlertForCompletion(detail, latestMessage)
        )
    }

    @Test
    fun `completion key uses newest completion event when event order is unstable`() {
        val detail = sampleDetail(
            status = MobileThreadStatus.WAITING_INPUT,
            events = listOf(
                ThreadEvent(
                    eventId = "turn-newer:completed",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "本轮已完成",
                    timestamp = "2026-04-28T08:12:30.000Z"
                ),
                ThreadEvent(
                    eventId = "turn-older:completed",
                    threadId = "thread-1",
                    kind = ThreadEventKind.TURN_COMPLETED,
                    status = MobileThreadStatus.COMPLETED,
                    text = "本轮已完成",
                    timestamp = "2026-04-28T08:10:30.000Z"
                )
            )
        )

        val key = DetailAlertSupport.completionAlertKey(detail)

        assertEquals("thread-1|completed|2026-04-28T08:12:30.000Z", key)
    }

    @Test
    fun `message alert key uses stable message identity`() {
        val message = ThreadMessage(
            messageId = "assistant-1",
            threadId = "thread-1",
            role = ThreadMessageRole.ASSISTANT,
            kind = "image",
            text = "看这张图",
            imageUrl = "https://gateway.example/images/1.jpg",
            thumbnailUrl = "https://gateway.example/thumbs/1.jpg",
            fileName = "1.jpg",
            timestamp = "2026-04-28T08:12:00.000Z",
            mimeType = "image/jpeg"
        )

        val key = DetailAlertSupport.messageAlertKey(message)

        assertEquals("thread-1|assistant|image|assistant-1", key)
    }

    @Test
    fun `streaming text changes keep the same message alert key when timestamp is stable`() {
        val firstChunk = ThreadMessage(
            messageId = "preview:792:2026-04-28T13:15:21.183Z",
            threadId = "thread-1",
            role = ThreadMessageRole.ASSISTANT,
            kind = "text",
            text = "正在处理",
            timestamp = "2026-04-28T13:15:21.183Z"
        )
        val laterChunk = firstChunk.copy(
            text = "正在处理，已经查到关键日志",
            timestamp = "2026-04-28T13:15:21.183Z"
        )

        assertEquals(
            DetailAlertSupport.messageAlertKey(firstChunk),
            DetailAlertSupport.messageAlertKey(laterChunk)
        )
    }

    @Test
    fun `preview line index changes keep the same message alert key`() {
        val firstTailWindow = ThreadMessage(
            messageId = "preview:416:2026-04-28T14:42:42.479Z",
            threadId = "thread-1",
            role = ThreadMessageRole.ASSISTANT,
            kind = "text",
            text = "我已经用红测复现",
            timestamp = "2026-04-28T14:42:42.479Z"
        )
        val laterTailWindow = firstTailWindow.copy(
            messageId = "preview:392:2026-04-28T14:42:42.479Z"
        )

        assertEquals(
            DetailAlertSupport.messageAlertKey(firstTailWindow),
            DetailAlertSupport.messageAlertKey(laterTailWindow)
        )
    }

    @Test
    fun `latest alert message uses newest timestamp when detail message order is unstable`() {
        val newerMessage = ThreadMessage(
            messageId = "preview:15:2026-04-28T13:33:25.559Z",
            threadId = "thread-1",
            role = ThreadMessageRole.ASSISTANT,
            kind = "text",
            text = "真正最新的消息",
            timestamp = "2026-04-28T13:33:25.559Z"
        )
        val olderMessageAtEnd = ThreadMessage(
            messageId = "preview:57:2026-04-28T13:32:18.427Z",
            threadId = "thread-1",
            role = ThreadMessageRole.ASSISTANT,
            kind = "text",
            text = "列表最后但不是最新",
            timestamp = "2026-04-28T13:32:18.427Z"
        )
        val detail = sampleDetail(
            status = MobileThreadStatus.RUNNING,
            messages = listOf(newerMessage, olderMessageAtEnd)
        )

        assertEquals(
            newerMessage,
            DetailAlertSupport.latestAlertMessage(detail)
        )
    }

    @Test
    fun `latest alert message falls back to list order when timestamps are missing`() {
        val first = ThreadMessage(
            messageId = "assistant-1",
            threadId = "thread-1",
            role = ThreadMessageRole.ASSISTANT,
            kind = "text",
            text = "第一条",
            timestamp = ""
        )
        val last = first.copy(
            messageId = "assistant-2",
            text = "最后一条"
        )
        val detail = sampleDetail(
            status = MobileThreadStatus.RUNNING,
            messages = listOf(first, last)
        )

        assertEquals(
            last,
            DetailAlertSupport.latestAlertMessage(detail)
        )
    }

    @Test
    fun `message alert history suppresses a key that was notified before baseline changed away`() {
        val seenKeys = setOf("thread-1|assistant|text|preview:126")
        val latestKey = "thread-1|assistant|text|preview:126"

        assertFalse(
            DetailAlertSupport.shouldNotifyMessageAlert(
                notifiedKeys = seenKeys,
                latestMessageKey = latestKey
            )
        )
    }

    @Test
    fun `message alert history is bounded and keeps newest keys`() {
        val remembered = DetailAlertSupport.rememberMessageAlertKey(
            notifiedKeys = linkedSetOf("k1", "k2"),
            latestMessageKey = "k3",
            maxSize = 2
        )

        assertEquals(linkedSetOf("k2", "k3"), remembered)
    }

    private fun sampleDetail(
        status: MobileThreadStatus,
        messages: List<ThreadMessage> = emptyList(),
        events: List<ThreadEvent> = emptyList(),
        updatedAt: String = "2026-04-28T08:10:00.000Z"
    ): ThreadDetail {
        return ThreadDetail(
            thread = ThreadListItem(
                threadId = "thread-1",
                title = "模拟",
                cwd = "D:/projects/codex-mobile-control",
                status = status,
                updatedAt = updatedAt,
                progressSummary = "线程正在等待输入",
                needsAttention = status == MobileThreadStatus.WAITING_INPUT
            ),
            recentMessages = messages,
            recentEvents = events,
            sendAvailable = status != MobileThreadStatus.RUNNING
        )
    }
}
