package com.codex.mobilecontrol.network

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.model.GatewayStreamEvent
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GatewaySseClientTest {
    @Test
    fun `connects with bearer token without putting token in stream url`() {
        val eventSourceFactory = CapturingEventSourceFactory()

        val streamHandle = OkHttpGatewaySseClient(
            eventSourceFactory = eventSourceFactory
        ).connect(
            config = GatewayConfig("http://gateway", "secret-token"),
            onEvent = {},
            onStateChanged = {}
        )

        try {
            val request = eventSourceFactory.request
            assertEquals("Bearer secret-token", request?.header("Authorization"))
            assertNull(request?.url?.queryParameter("token"))
            assertFalse(request?.url.toString().contains("secret-token"))
        } finally {
            streamHandle.close()
        }
    }

    @Test
    fun `parses thread_event and reports reconnecting state`() {
        val events = mutableListOf<GatewayStreamEvent>()
        val connectionStates = mutableListOf<StreamConnectionState>()
        val latch = CountDownLatch(1)

        val streamHandle = OkHttpGatewaySseClient(
            eventSourceFactory = FakeEventSourceFactory()
        ).connect(
            config = GatewayConfig("http://gateway", "token"),
            onEvent = {
                events += it
                latch.countDown()
            },
            onStateChanged = { connectionStates += it }
        )

        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals("thread_event", events.first().type)
            assertEquals("latency_probe_result", events.last().type)
            assertEquals("probe-1", events.last().probeId)
            assertEquals(1_777_699_000_000L, events.last().sentAt)
            assertTrue(connectionStates.contains(StreamConnectionState.CONNECTING))
            assertTrue(connectionStates.contains(StreamConnectionState.RECONNECTING))
        } finally {
            streamHandle.close()
        }
    }

    @Test
    fun `reports reconnecting when an opened stream fails with a successful response`() {
        val connectionStates = mutableListOf<StreamConnectionState>()

        val streamHandle = OkHttpGatewaySseClient(
            eventSourceFactory = FailureEventSourceFactory(responseCode = 200)
        ).connect(
            config = GatewayConfig("http://gateway", "token"),
            onEvent = {},
            onStateChanged = { connectionStates += it }
        )

        try {
            assertEquals(
                listOf(StreamConnectionState.CONNECTING, StreamConnectionState.RECONNECTING),
                connectionStates
            )
        } finally {
            streamHandle.close()
        }
    }

    @Test
    fun `reports failed when stream handshake returns an error response`() {
        val connectionStates = mutableListOf<StreamConnectionState>()

        val streamHandle = OkHttpGatewaySseClient(
            eventSourceFactory = FailureEventSourceFactory(responseCode = 401)
        ).connect(
            config = GatewayConfig("http://gateway", "token"),
            onEvent = {},
            onStateChanged = { connectionStates += it }
        )

        try {
            assertEquals(
                listOf(StreamConnectionState.CONNECTING, StreamConnectionState.FAILED),
                connectionStates
            )
        } finally {
            streamHandle.close()
        }
    }
}

private class CapturingEventSourceFactory : EventSource.Factory {
    var request: Request? = null
        private set

    override fun newEventSource(request: Request, listener: EventSourceListener): EventSource {
        this.request = request
        return object : EventSource {
            override fun request(): Request = request

            override fun cancel() = Unit
        }
    }
}

private class FakeEventSourceFactory : EventSource.Factory {
    override fun newEventSource(request: Request, listener: EventSourceListener): EventSource {
        val eventSource = object : EventSource {
            override fun request(): Request = request

            override fun cancel() = Unit
        }

        listener.onEvent(
            eventSource = eventSource,
            id = null,
            type = "thread_event",
            data = """{"threadId":"thread-1","timestamp":"2026-04-23T01:00:00Z"}"""
        )
        listener.onEvent(
            eventSource = eventSource,
            id = null,
            type = "latency_probe_result",
            data = """{"probeId":"probe-1","sentAt":1777699000000,"timestamp":"2026-05-02T05:29:00Z"}"""
        )
        listener.onFailure(
            eventSource = eventSource,
            t = IllegalStateException("reconnect"),
            response = null
        )
        return eventSource
    }
}

private class FailureEventSourceFactory(
    private val responseCode: Int
) : EventSource.Factory {
    override fun newEventSource(request: Request, listener: EventSourceListener): EventSource {
        val eventSource = object : EventSource {
            override fun request(): Request = request

            override fun cancel() = Unit
        }

        listener.onFailure(
            eventSource = eventSource,
            t = IllegalStateException("stream closed"),
            response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message(if (responseCode in 200..299) "OK" else "Unauthorized")
                .build()
        )
        return eventSource
    }
}
