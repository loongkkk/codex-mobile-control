package com.codex.mobilecontrol.network

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.model.GatewayStreamEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

enum class StreamConnectionState {
    CONNECTING,
    OPEN,
    RECONNECTING,
    CLOSED,
    FAILED
}

interface GatewaySseClient {
    fun connect(
        config: GatewayConfig,
        onEvent: (GatewayStreamEvent) -> Unit,
        onStateChanged: (StreamConnectionState) -> Unit
    ): AutoCloseable
}

class OkHttpGatewaySseClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val eventSourceFactory: EventSource.Factory = EventSources.createFactory(httpClient)
) : GatewaySseClient {
    override fun connect(
        config: GatewayConfig,
        onEvent: (GatewayStreamEvent) -> Unit,
        onStateChanged: (StreamConnectionState) -> Unit
    ): AutoCloseable {
        onStateChanged(StreamConnectionState.CONNECTING)

        val url = buildString {
            append(config.url.trimEnd('/'))
            append("/api/stream")
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.token}")
            .build()
        val closed = AtomicBoolean(false)

        val eventSource = eventSourceFactory.newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    onStateChanged(StreamConnectionState.OPEN)
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    val json = JSONObject(data)
                    onEvent(
                        GatewayStreamEvent(
                            type = type ?: "message",
                            threadId = json.optString("threadId").takeIf { it.isNotBlank() },
                            timestamp = json.optString("timestamp").takeIf { it.isNotBlank() },
                            probeId = json.optString("probeId").takeIf { it.isNotBlank() },
                            sentAt = json.optLong("sentAt").takeIf { it > 0L }
                        )
                    )
                }

                override fun onClosed(eventSource: EventSource) {
                    onStateChanged(
                        if (closed.get()) {
                            StreamConnectionState.CLOSED
                        } else {
                            StreamConnectionState.RECONNECTING
                        }
                    )
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    if (closed.get()) {
                        return
                    }
                    onStateChanged(
                        if (response == null || response.isSuccessful) {
                            StreamConnectionState.RECONNECTING
                        } else {
                            StreamConnectionState.FAILED
                        }
                    )
                }
            }
        )

        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                eventSource.cancel()
                onStateChanged(StreamConnectionState.CLOSED)
            }
        }
    }
}
