package com.codex.mobilecontrol.data

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import com.codex.mobilecontrol.model.ThreadMessageSendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadMessageMergeSupportTest {
    @Test
    fun `merge messages replaces pending optimistic text with confirmed user message`() {
        val optimistic = message(
            id = "client-1",
            text = "继续",
            timestamp = "2026-05-08T00:00:00Z",
            sendState = ThreadMessageSendState.SENDING
        )
        val confirmed = message(
            id = "confirmed-1",
            text = "继续",
            timestamp = "2026-05-08T00:00:03Z"
        )

        val merged = ThreadMessageMergeSupport.mergeMessagesById(
            existingMessages = listOf(optimistic),
            incomingMessages = listOf(confirmed)
        )

        assertEquals(listOf("confirmed-1"), merged.map { it.messageId })
        assertNull(merged.single().sendState)
    }

    @Test
    fun `merge messages confirms the closest pending text when duplicate sends share content`() {
        val firstPending = message(
            id = "client-first",
            text = "继续查资料",
            timestamp = "2026-05-08T00:00:00Z",
            sendState = ThreadMessageSendState.SENDING
        )
        val secondPending = message(
            id = "client-second",
            text = "继续查资料",
            timestamp = "2026-05-08T00:02:00Z",
            sendState = ThreadMessageSendState.SENDING
        )
        val confirmed = message(
            id = "confirmed-second",
            text = "继续查资料",
            timestamp = "2026-05-08T00:02:05Z"
        )

        val merged = ThreadMessageMergeSupport.mergeMessagesById(
            existingMessages = listOf(firstPending, secondPending),
            incomingMessages = listOf(confirmed)
        )

        assertEquals(listOf("client-first", "confirmed-second"), merged.map { it.messageId })
        assertEquals(ThreadMessageSendState.SENDING, merged.first().sendState)
        assertNull(merged.last().sendState)
    }

    @Test
    fun `merge messages replaces failed optimistic text when confirmed user message arrives`() {
        val failed = message(
            id = "client-failed",
            text = "那里面所有付款都可以抵用吗",
            timestamp = "2026-05-16T02:22:44Z",
            sendState = ThreadMessageSendState.FAILED
        )
        val confirmed = message(
            id = "event-confirmed",
            text = "那里面所有付款都可以抵用吗",
            timestamp = "2026-05-16T02:22:52Z"
        )

        val merged = ThreadMessageMergeSupport.mergeMessagesById(
            existingMessages = listOf(failed),
            incomingMessages = listOf(confirmed)
        )

        assertEquals(listOf("event-confirmed"), merged.map { it.messageId })
        assertNull(merged.single().sendState)
    }

    @Test
    fun `merge detail messages keeps older loaded history before incoming snapshot`() {
        val existing = detail(
            listOf(
                message(id = "older", text = "旧消息", timestamp = "2026-05-08T00:00:00Z"),
                message(id = "current", text = "当前", timestamp = "2026-05-08T00:01:00Z")
            )
        )
        val incoming = detail(
            listOf(
                message(id = "current", text = "当前", timestamp = "2026-05-08T00:01:00Z"),
                message(id = "latest", text = "最新", timestamp = "2026-05-08T00:02:00Z")
            )
        )

        val merged = ThreadMessageMergeSupport.mergeDetailMessages(
            incomingDetail = incoming,
            existingDetail = existing
        )

        assertEquals(listOf("older", "current", "latest"), merged.recentMessages.map { it.messageId })
    }

    private fun detail(messages: List<ThreadMessage>): ThreadDetail {
        val thread = ThreadListItem(
            threadId = "thread-1",
            title = "线程",
            cwd = "/tmp",
            status = MobileThreadStatus.IDLE,
            updatedAt = messages.lastOrNull()?.timestamp ?: "2026-05-08T00:00:00Z",
            progressSummary = "空闲",
            needsAttention = false
        )
        return ThreadDetail(
            thread = thread,
            recentMessages = messages,
            recentEvents = emptyList(),
            sendAvailable = true
        )
    }

    private fun message(
        id: String,
        text: String,
        timestamp: String,
        sendState: ThreadMessageSendState? = null
    ): ThreadMessage {
        return ThreadMessage(
            messageId = id,
            threadId = "thread-1",
            role = ThreadMessageRole.USER,
            kind = "text",
            text = text,
            timestamp = timestamp,
            sendState = sendState
        )
    }
}
