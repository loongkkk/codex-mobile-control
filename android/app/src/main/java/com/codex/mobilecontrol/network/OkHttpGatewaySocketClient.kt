package com.codex.mobilecontrol.network

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.diagnostics.DebugTraceLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class OkHttpGatewaySocketClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val reconnectDelayMillis: Long = 1_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val traceLogger: (String) -> Unit = { message ->
        DebugTraceLogger.log("socket", message)
    }
) : GatewayRealtimeClient {
    override fun connect(
        config: GatewayConfig,
        lastEventId: String?,
        enabledThreadIds: Set<String>,
        knownNotificationIds: Set<String>,
        onEvent: (GatewayRealtimeEvent) -> Unit,
        onStateChanged: (StreamConnectionState) -> Unit
    ): GatewayRealtimeConnection {
        return SocketConnection(
            config = config,
            lastEventId = lastEventId,
            enabledThreadIds = enabledThreadIds,
            knownNotificationIds = knownNotificationIds,
            onEvent = onEvent,
            onStateChanged = onStateChanged
        ).also(SocketConnection::start)
    }

    private fun buildSocketUrl(config: GatewayConfig, lastEventId: String?): String {
        val httpUrl = config.url.trimEnd('/').toHttpUrl()
        val url = httpUrl.newBuilder()
            .addPathSegment("ws")
            .addPathSegment("mobile")
            .apply {
                if (!lastEventId.isNullOrBlank()) {
                    addQueryParameter("lastEventId", lastEventId)
                }
            }
            .build()
            .toString()
        return if (httpUrl.scheme == "https") {
            url.replaceFirst("https://", "wss://")
        } else {
            url.replaceFirst("http://", "ws://")
        }
    }

    private inner class SocketConnection(
        private val config: GatewayConfig,
        lastEventId: String?,
        enabledThreadIds: Set<String>,
        knownNotificationIds: Set<String>,
        private val onEvent: (GatewayRealtimeEvent) -> Unit,
        private val onStateChanged: (StreamConnectionState) -> Unit
    ) : GatewayRealtimeConnection {
        @Volatile
        private var socket: WebSocket? = null

        @Volatile
        private var closed = false

        @Volatile
        private var reconnectScheduled = false

        @Volatile
        private var currentLastEventId = lastEventId

        @Volatile
        private var currentEnabledThreadIds = enabledThreadIds

        @Volatile
        private var currentKnownNotificationIds = knownNotificationIds

        private val realtimeCursorLock = Any()
        private val pendingNotificationEvents = LinkedHashMap<String, String>()
        private var deferredLastEventIdAfterPendingNotification: String? = null

        private val pendingLatencyProbes =
            ConcurrentHashMap<String, CompletableDeferred<GatewayRealtimeEvent.LatencyProbeResult>>()

        private val reconnectExecutor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "codex-mobile-socket-reconnect").apply {
                    isDaemon = true
                }
            }

        private fun trace(message: String) {
            runCatching {
                traceLogger(message)
            }
        }

        fun start() {
            if (closed) {
                return
            }

            onStateChanged(StreamConnectionState.CONNECTING)
            val socketUrl = buildSocketUrl(config, lastEventIdForConnection())
            trace("connect url=$socketUrl")
            val request = Request.Builder()
                .url(socketUrl)
                .header("Authorization", "Bearer ${config.token}")
                .build()
            val nextSocket = httpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (closed || socket !== webSocket) {
                            return
                        }
                        trace("open code=${response.code}")
                        onStateChanged(StreamConnectionState.OPEN)
                        sendNotificationPreferences(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (closed || socket !== webSocket) {
                            return
                        }
                        runCatching { GatewayRealtimeEventParser.parse(text) }
                            .onSuccess { event ->
                                if (event is GatewayRealtimeEvent.LatencyProbeResult) {
                                    pendingLatencyProbes.remove(event.probeId)?.complete(event)
                                    return@onSuccess
                                }
                                rememberRealtimeEvent(event)
                                onEvent(event)
                            }.onFailure { error ->
                                trace("messageParseFailed error=${error.toSocketErrorSummary()}")
                            }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        if (closed) {
                            return
                        }
                        trace("closing code=$code reason=${reason.take(120)}")
                        webSocket.close(1000, "reconnecting")
                        scheduleReconnect()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (closed) {
                            trace("closed code=$code reason=${reason.take(120)} closedByClient=true")
                            onStateChanged(StreamConnectionState.CLOSED)
                            return
                        }
                        trace("closed code=$code reason=${reason.take(120)}")
                        scheduleReconnect()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (closed) {
                            return
                        }
                        trace(
                            "failure responseCode=${response?.code ?: "none"} " +
                                "error=${t.toSocketErrorSummary()}"
                        )
                        if (response == null) {
                            scheduleReconnect()
                        } else {
                            failPendingLatencyProbes(IOException(t))
                            onStateChanged(StreamConnectionState.FAILED)
                        }
                    }
                }
            )
            socket = nextSocket
        }

        override fun updateNotificationPreferences(
            enabledThreadIds: Set<String>,
            knownNotificationIds: Set<String>
        ) {
            currentEnabledThreadIds = enabledThreadIds
            acknowledgeKnownNotifications(knownNotificationIds)
            socket?.send(notificationPreferencesPayload(enabledThreadIds, knownNotificationIds))
        }

        override fun close() {
            closed = true
            reconnectExecutor.shutdownNow()
            val currentSocket = socket
            socket = null
            failPendingLatencyProbes(IOException("socket closed"))
            trace("close requested")
            currentSocket?.close(1000, "closed")
            currentSocket?.cancel()
            onStateChanged(StreamConnectionState.CLOSED)
        }

        override suspend fun measureLatency(
            sampleCount: Int,
            timeoutMillis: Long
        ): SocketLatencyTestResult {
            require(sampleCount > 0) { "sampleCount must be positive" }
            require(timeoutMillis > 0L) { "timeoutMillis must be positive" }

            val samples = mutableListOf<Long>()
            repeat(sampleCount) {
                val result = measureSingleLatency(timeoutMillis)
                samples += nowMillis() - result.sentAt
            }
            return SocketLatencyTestResult(
                averageMillis = samples.sum() / samples.size,
                samplesMillis = samples
            )
        }

        private fun scheduleReconnect() {
            if (closed || reconnectScheduled) {
                return
            }

            failPendingLatencyProbes(IOException("socket reconnecting"))
            reconnectScheduled = true
            trace("reconnectScheduled delayMs=$reconnectDelayMillis lastEventId=${lastEventIdForConnection() ?: "none"}")
            onStateChanged(StreamConnectionState.RECONNECTING)
            reconnectExecutor.schedule(
                {
                    reconnectScheduled = false
                    start()
                },
                reconnectDelayMillis,
                TimeUnit.MILLISECONDS
            )
        }

        private suspend fun measureSingleLatency(
            timeoutMillis: Long
        ): GatewayRealtimeEvent.LatencyProbeResult {
            val currentSocket = socket ?: throw IOException("socket unavailable")
            if (closed) {
                throw IOException("socket closed")
            }
            val probeId = UUID.randomUUID().toString()
            val sentAt = nowMillis()
            val result = CompletableDeferred<GatewayRealtimeEvent.LatencyProbeResult>()
            pendingLatencyProbes[probeId] = result
            val sent = currentSocket.send(
                JSONObject()
                    .put("type", "latency_probe")
                    .put("probeId", probeId)
                    .put("sentAt", sentAt)
                    .toString()
            )
            if (!sent) {
                pendingLatencyProbes.remove(probeId)
                throw IOException("socket send failed")
            }

            return try {
                withTimeout(timeoutMillis) {
                    result.await()
                }
            } finally {
                pendingLatencyProbes.remove(probeId)
            }
        }

        private fun failPendingLatencyProbes(error: Throwable) {
            pendingLatencyProbes.values.forEach { pending ->
                pending.completeExceptionally(error)
            }
            pendingLatencyProbes.clear()
        }

        private fun sendNotificationPreferences(webSocket: WebSocket) {
            webSocket.send(
                notificationPreferencesPayload(
                    enabledThreadIds = currentEnabledThreadIds,
                    knownNotificationIds = currentKnownNotificationIds
                )
            )
        }

        private fun notificationPreferencesPayload(
            enabledThreadIds: Set<String>,
            knownNotificationIds: Set<String>
        ): String {
            return JSONObject()
                .put("type", "notification_preferences")
                .put("enabledThreadIds", JSONArray().also { array ->
                    enabledThreadIds.sorted().forEach(array::put)
                })
                .put("knownNotificationIds", JSONArray().also { array ->
                    knownNotificationIds.sorted().forEach(array::put)
                })
                .toString()
        }

        private fun lastEventIdForConnection(): String? {
            return synchronized(realtimeCursorLock) {
                currentLastEventId
            }
        }

        private fun rememberRealtimeEvent(event: GatewayRealtimeEvent) {
            val eventId = event.realtimeEventId() ?: return
            synchronized(realtimeCursorLock) {
                if (
                    event is GatewayRealtimeEvent.Notification &&
                    event.notificationId !in currentKnownNotificationIds
                ) {
                    pendingNotificationEvents[event.eventId] = event.notificationId
                    return
                }
                if (pendingNotificationEvents.isEmpty()) {
                    currentLastEventId = eventId
                } else {
                    deferredLastEventIdAfterPendingNotification = eventId
                }
            }
        }

        private fun acknowledgeKnownNotifications(knownNotificationIds: Set<String>) {
            synchronized(realtimeCursorLock) {
                currentKnownNotificationIds = knownNotificationIds
                var lastAcknowledgedEventId: String? = null
                val iterator = pendingNotificationEvents.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.value in knownNotificationIds) {
                        lastAcknowledgedEventId = entry.key
                        iterator.remove()
                    }
                }
                if (pendingNotificationEvents.isEmpty()) {
                    val eventIdToCommit =
                        deferredLastEventIdAfterPendingNotification ?: lastAcknowledgedEventId
                    if (eventIdToCommit != null) {
                        currentLastEventId = eventIdToCommit
                    }
                    deferredLastEventIdAfterPendingNotification = null
                }
            }
        }
    }
}

private fun GatewayRealtimeEvent.realtimeEventId(): String? {
    return when (this) {
        is GatewayRealtimeEvent.Hello -> null
        is GatewayRealtimeEvent.ThreadStatusChanged -> eventId
        is GatewayRealtimeEvent.MessagesAppended -> eventId
        is GatewayRealtimeEvent.Notification -> eventId
        is GatewayRealtimeEvent.LatencyProbeResult -> null
    }
}

private fun Throwable.toSocketErrorSummary(): String {
    val type = this::class.java.simpleName.ifBlank { "Throwable" }
    val message = this.message
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotBlank() }
        ?: "no message"
    return "$type: ${message.take(240)}"
}
