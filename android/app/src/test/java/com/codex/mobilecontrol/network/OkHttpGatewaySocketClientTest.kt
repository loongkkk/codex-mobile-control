package com.codex.mobilecontrol.network

import com.codex.mobilecontrol.GatewayConfig
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class OkHttpGatewaySocketClientTest {
    @Test
    fun connectsToMobileSocketAndParsesEvents() {
        val server = MockWebServer()
        val serverMessages = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            """
                            {
                              "type": "thread_status_changed",
                              "eventId": "event-1",
                              "threadId": "thread-1",
                              "status": "completed",
                              "progressSummary": "本轮已完成",
                              "needsAttention": false,
                              "timestamp": "2026-04-30T01:00:00.000Z"
                            }
                            """.trimIndent()
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        serverMessages.offer(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                }
            )
        )
        server.start()
        try {
            val events = LinkedBlockingQueue<GatewayRealtimeEvent>()
            val states = LinkedBlockingQueue<StreamConnectionState>()
            val traces = LinkedBlockingQueue<String>()
            val client = OkHttpGatewaySocketClient(traceLogger = traces::offer)
            val connection = client.connect(
                config = GatewayConfig(server.url("/").toString(), "secret-token"),
                lastEventId = "last-1",
                enabledThreadIds = setOf("thread-1"),
                knownNotificationIds = setOf("known-1"),
                onEvent = events::offer,
                onStateChanged = states::offer
            )

            val event = events.poll(2, TimeUnit.SECONDS) as GatewayRealtimeEvent.ThreadStatusChanged
            assertEquals("event-1", event.eventId)
            assertEquals("thread-1", event.threadId)
            val request = server.takeRequest()
            assertEquals("/ws/mobile?lastEventId=last-1", request.path)
            assertEquals("Bearer secret-token", request.getHeader("Authorization"))
            assertEquals(StreamConnectionState.CONNECTING, states.poll(2, TimeUnit.SECONDS))
            assertEquals(StreamConnectionState.OPEN, states.poll(2, TimeUnit.SECONDS))
            assertTrue(serverMessages.poll(2, TimeUnit.SECONDS).contains("notification_preferences"))
            connection.close()
            assertEquals(StreamConnectionState.CLOSED, states.poll(2, TimeUnit.SECONDS))
            val traceText = drainStrings(traces).joinToString("\n")
            assertTrue(traceText.contains("connect url="))
            assertTrue(traceText.contains("/ws/mobile?lastEventId=last-1"))
            assertTrue(traceText.contains("open code="))
            assertTrue(traceText.contains("close requested"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun reconnectsAfterSocketClosesAndResendsNotificationPreferences() {
        val server = MockWebServer()
        val serverMessages = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1012, "gateway_restart")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        serverMessages.offer(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                }
            )
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            """
                            {
                              "type": "notification",
                              "eventId": "notification:alert-1",
                              "notificationId": "alert-1",
                              "threadId": "thread-1",
                              "trigger": "completed",
                              "title": "本轮已完成",
                              "body": "本轮已完成",
                              "timestamp": "2026-04-30T01:00:00.000Z"
                            }
                            """.trimIndent()
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        serverMessages.offer(text)
                    }
                }
            )
        )
        server.start()
        try {
            val events = LinkedBlockingQueue<GatewayRealtimeEvent>()
            val states = LinkedBlockingQueue<StreamConnectionState>()
            val client = OkHttpGatewaySocketClient()
            val connection = client.connect(
                config = GatewayConfig(server.url("/").toString(), "secret-token"),
                lastEventId = "last-1",
                enabledThreadIds = setOf("thread-1"),
                knownNotificationIds = setOf("known-1"),
                onEvent = events::offer,
                onStateChanged = states::offer
            )

            val firstRequest = server.takeRequest()
            assertEquals("/ws/mobile?lastEventId=last-1", firstRequest.path)
            assertEquals("Bearer secret-token", firstRequest.getHeader("Authorization"))
            val secondRequest = server.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("/ws/mobile?lastEventId=last-1", secondRequest?.path)
            assertEquals("Bearer secret-token", secondRequest?.getHeader("Authorization"))
            val event = events.poll(3, TimeUnit.SECONDS) as GatewayRealtimeEvent.Notification
            assertEquals("alert-1", event.notificationId)
            assertTrue(serverMessages.poll(2, TimeUnit.SECONDS).contains("notification_preferences"))
            assertTrue(serverMessages.poll(2, TimeUnit.SECONDS).contains("notification_preferences"))
            assertTrue(drainStates(states).contains(StreamConnectionState.RECONNECTING))
            connection.close()
            assertEquals(StreamConnectionState.CLOSED, states.poll(2, TimeUnit.SECONDS))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun reconnectUsesLatestReceivedRealtimeEventId() {
        val server = MockWebServer()
        val firstServerSocket = AtomicReference<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        firstServerSocket.set(webSocket)
                        webSocket.send(
                            """
                            {
                              "type": "thread_status_changed",
                              "eventId": "event-latest",
                              "threadId": "thread-1",
                              "status": "running",
                              "progressSummary": "开始处理新的输入",
                              "needsAttention": false,
                              "timestamp": "2026-04-30T01:00:00.000Z"
                            }
                            """.trimIndent()
                        )
                    }
                }
            )
        )
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()
        try {
            val events = LinkedBlockingQueue<GatewayRealtimeEvent>()
            val client = OkHttpGatewaySocketClient()
            val connection = client.connect(
                config = GatewayConfig(server.url("/").toString(), "secret-token"),
                lastEventId = "last-1",
                enabledThreadIds = emptySet(),
                knownNotificationIds = emptySet(),
                onEvent = events::offer,
                onStateChanged = {}
            )

            val firstRequest = server.takeRequest()
            assertEquals("/ws/mobile?lastEventId=last-1", firstRequest.path)
            assertEquals("Bearer secret-token", firstRequest.getHeader("Authorization"))
            val event = events.poll(2, TimeUnit.SECONDS) as GatewayRealtimeEvent.ThreadStatusChanged
            assertEquals("event-latest", event.eventId)
            firstServerSocket.get().close(1012, "gateway_restart")
            val secondRequest = server.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("/ws/mobile?lastEventId=event-latest", secondRequest?.path)
            assertEquals("Bearer secret-token", secondRequest?.getHeader("Authorization"))
            connection.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun reconnectKeepsCursorBeforeUnacknowledgedNotification() {
        val server = MockWebServer()
        val firstServerSocket = AtomicReference<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        firstServerSocket.set(webSocket)
                        webSocket.send(
                            """
                            {
                              "type": "notification",
                              "eventId": "notification:alert-1",
                              "notificationId": "alert-1",
                              "threadId": "thread-1",
                              "trigger": "completed",
                              "title": "本轮已完成",
                              "body": "本轮已完成",
                              "timestamp": "2026-04-30T01:00:00.000Z"
                            }
                            """.trimIndent()
                        )
                        webSocket.send(
                            """
                            {
                              "type": "thread_status_changed",
                              "eventId": "event-after-alert",
                              "threadId": "thread-1",
                              "status": "completed",
                              "progressSummary": "本轮已完成",
                              "needsAttention": false,
                              "timestamp": "2026-04-30T01:00:01.000Z"
                            }
                            """.trimIndent()
                        )
                    }
                }
            )
        )
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()
        try {
            val events = LinkedBlockingQueue<GatewayRealtimeEvent>()
            val client = OkHttpGatewaySocketClient()
            val connection = client.connect(
                config = GatewayConfig(server.url("/").toString(), "secret-token"),
                lastEventId = "event-before-alert",
                enabledThreadIds = setOf("thread-1"),
                knownNotificationIds = emptySet(),
                onEvent = events::offer,
                onStateChanged = {}
            )

            val firstRequest = server.takeRequest()
            assertEquals("/ws/mobile?lastEventId=event-before-alert", firstRequest.path)
            assertEquals("Bearer secret-token", firstRequest.getHeader("Authorization"))
            assertEquals("alert-1", (events.poll(2, TimeUnit.SECONDS) as GatewayRealtimeEvent.Notification).notificationId)
            assertEquals(
                "event-after-alert",
                (events.poll(2, TimeUnit.SECONDS) as GatewayRealtimeEvent.ThreadStatusChanged).eventId
            )
            firstServerSocket.get().close(1012, "gateway_restart")
            val secondRequest = server.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("/ws/mobile?lastEventId=event-before-alert", secondRequest?.path)
            assertEquals("Bearer secret-token", secondRequest?.getHeader("Authorization"))
            connection.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun measuresAverageLatencyWithThreeSocketProbes() = runBlocking {
        val server = MockWebServer()
        val clock = AtomicLong(1_000L)
        val latencies = LinkedBlockingQueue<Long>().apply {
            offer(10L)
            offer(20L)
            offer(30L)
        }
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val json = JSONObject(text)
                        if (json.optString("type") != "latency_probe") {
                            return
                        }
                        val latency = latencies.poll(2, TimeUnit.SECONDS) ?: 0L
                        clock.set(json.getLong("sentAt") + latency)
                        webSocket.send(
                            JSONObject()
                                .put("type", "latency_probe_result")
                                .put("probeId", json.getString("probeId"))
                                .put("sentAt", json.getLong("sentAt"))
                                .put("timestamp", "2026-05-01T11:45:27.000Z")
                                .toString()
                        )
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                }
            )
        )
        server.start()
        try {
            val states = LinkedBlockingQueue<StreamConnectionState>()
            val client = OkHttpGatewaySocketClient(nowMillis = { clock.get() })
            val connection = client.connect(
                config = GatewayConfig(server.url("/").toString(), "secret-token"),
                lastEventId = null,
                enabledThreadIds = emptySet(),
                knownNotificationIds = emptySet(),
                onEvent = {},
                onStateChanged = states::offer
            )

            assertEquals(StreamConnectionState.CONNECTING, states.poll(2, TimeUnit.SECONDS))
            assertEquals(StreamConnectionState.OPEN, states.poll(2, TimeUnit.SECONDS))

            val result = connection.measureLatency(sampleCount = 3, timeoutMillis = 1_000L)

            assertEquals(listOf(10L, 20L, 30L), result.samplesMillis)
            assertEquals(20L, result.averageMillis)
            connection.close()
        } finally {
            server.shutdown()
        }
    }

    private fun drainStates(states: LinkedBlockingQueue<StreamConnectionState>): List<StreamConnectionState> {
        val drained = mutableListOf<StreamConnectionState>()
        states.drainTo(drained)
        return drained
    }

    private fun drainStrings(values: LinkedBlockingQueue<String>): List<String> {
        val drained = mutableListOf<String>()
        values.drainTo(drained)
        return drained
    }
}
