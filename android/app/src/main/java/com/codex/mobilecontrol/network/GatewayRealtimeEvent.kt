package com.codex.mobilecontrol.network

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadMessage
import org.json.JSONArray
import org.json.JSONObject

sealed interface GatewayRealtimeEvent {
    data class Hello(
        val protocolVersion: Int,
        val timestamp: String
    ) : GatewayRealtimeEvent

    data class ThreadStatusChanged(
        val eventId: String,
        val threadId: String,
        val status: MobileThreadStatus,
        val progressSummary: String,
        val needsAttention: Boolean,
        val timestamp: String,
        val runningStartedAt: String? = null
    ) : GatewayRealtimeEvent

    data class MessagesAppended(
        val eventId: String,
        val threadId: String,
        val messages: List<ThreadMessage>,
        val timestamp: String
    ) : GatewayRealtimeEvent

    data class Notification(
        val eventId: String,
        val notificationId: String,
        val threadId: String,
        val trigger: String,
        val title: String,
        val body: String,
        val timestamp: String
    ) : GatewayRealtimeEvent

    data class LatencyProbeResult(
        val probeId: String,
        val sentAt: Long,
        val timestamp: String
    ) : GatewayRealtimeEvent
}

object GatewayRealtimeEventParser {
    fun parse(raw: String): GatewayRealtimeEvent {
        val json = JSONObject(raw)
        return when (val type = json.optString("type")) {
            "hello" -> GatewayRealtimeEvent.Hello(
                protocolVersion = json.optInt("protocolVersion", 1),
                timestamp = json.optString("timestamp")
            )
            "thread_status_changed" -> GatewayRealtimeEvent.ThreadStatusChanged(
                eventId = json.getString("eventId"),
                threadId = json.getString("threadId"),
                status = MobileThreadStatus.fromWireValue(json.getString("status")),
                progressSummary = json.optString("progressSummary"),
                needsAttention = json.optBoolean("needsAttention", false),
                timestamp = json.getString("timestamp"),
                runningStartedAt = json.optString("runningStartedAt").takeIf { it.isNotBlank() }
            )
            "thread_messages_appended" -> GatewayRealtimeEvent.MessagesAppended(
                eventId = json.getString("eventId"),
                threadId = json.getString("threadId"),
                messages = parseMessages(json.optJSONArray("messages") ?: JSONArray()),
                timestamp = json.getString("timestamp")
            )
            "notification" -> GatewayRealtimeEvent.Notification(
                eventId = json.getString("eventId"),
                notificationId = json.getString("notificationId"),
                threadId = json.getString("threadId"),
                trigger = json.getString("trigger"),
                title = json.optString("title"),
                body = json.optString("body"),
                timestamp = json.getString("timestamp")
            )
            "latency_probe_result" -> GatewayRealtimeEvent.LatencyProbeResult(
                probeId = json.getString("probeId"),
                sentAt = json.getLong("sentAt"),
                timestamp = json.getString("timestamp")
            )
            else -> throw IllegalArgumentException("unknown realtime event type: $type")
        }
    }

    private fun parseMessages(array: JSONArray): List<ThreadMessage> {
        val parser = GatewayJsonParser()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                parser.parseThreadMessageForRealtime(item)
            }
        }
    }
}
