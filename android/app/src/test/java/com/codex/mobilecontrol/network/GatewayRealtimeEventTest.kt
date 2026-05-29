package com.codex.mobilecontrol.network

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadMessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayRealtimeEventTest {
    @Test
    fun parsesThreadStatusChangedEvent() {
        val event = GatewayRealtimeEventParser.parse(
            """
            {
              "type": "thread_status_changed",
              "eventId": "event-1",
              "threadId": "thread-1",
              "status": "completed",
              "progressSummary": "本轮已完成",
              "needsAttention": false,
              "runningStartedAt": "2026-04-30T00:59:30.000Z",
              "timestamp": "2026-04-30T01:00:00.000Z"
            }
            """.trimIndent()
        )

        val statusEvent = event as GatewayRealtimeEvent.ThreadStatusChanged
        assertEquals("event-1", statusEvent.eventId)
        assertEquals("thread-1", statusEvent.threadId)
        assertEquals(MobileThreadStatus.COMPLETED, statusEvent.status)
        assertEquals("本轮已完成", statusEvent.progressSummary)
        assertEquals("2026-04-30T00:59:30.000Z", statusEvent.runningStartedAt)
    }

    @Test
    fun parsesMessagesAppendedEvent() {
        val event = GatewayRealtimeEventParser.parse(
            """
            {
              "type": "thread_messages_appended",
              "eventId": "event-message-1",
              "threadId": "thread-1",
              "timestamp": "2026-04-30T01:00:02.000Z",
              "messages": [
                {
                  "messageId": "assistant-message-1",
                  "threadId": "thread-1",
                  "role": "assistant",
                  "kind": "text",
                  "text": "新的回复",
                  "timestamp": "2026-04-30T01:00:02.000Z"
                }
              ]
            }
            """.trimIndent()
        )

        val messagesAppended = event as GatewayRealtimeEvent.MessagesAppended
        assertEquals("event-message-1", messagesAppended.eventId)
        assertEquals("assistant-message-1", messagesAppended.messages.single().messageId)
        assertEquals(ThreadMessageRole.ASSISTANT, messagesAppended.messages.single().role)
    }

    @Test
    fun parsesNotificationEvent() {
        val event = GatewayRealtimeEventParser.parse(
            """
            {
              "type": "notification",
              "eventId": "notification:thread-1:completed:turn-9",
              "notificationId": "thread-1:completed:turn-9",
              "threadId": "thread-1",
              "trigger": "completed",
              "title": "本轮已完成",
              "body": "本轮已完成",
              "timestamp": "2026-04-30T01:00:01.000Z"
            }
            """.trimIndent()
        )

        val notification = event as GatewayRealtimeEvent.Notification
        assertEquals("thread-1:completed:turn-9", notification.notificationId)
        assertEquals("completed", notification.trigger)
    }

    @Test
    fun parsesLatencyProbeResultEvent() {
        val event = GatewayRealtimeEventParser.parse(
            """
            {
              "type": "latency_probe_result",
              "probeId": "probe-1",
              "sentAt": 1777635927000,
              "timestamp": "2026-05-01T11:45:27.020Z"
            }
            """.trimIndent()
        )

        val result = event as GatewayRealtimeEvent.LatencyProbeResult
        assertEquals("probe-1", result.probeId)
        assertEquals(1_777_635_927_000L, result.sentAt)
    }
}
