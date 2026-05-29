package com.codex.mobilecontrol.network

import com.codex.mobilecontrol.GatewayConfig

data class SocketLatencyTestResult(
    val averageMillis: Long,
    val samplesMillis: List<Long>
)

interface GatewayRealtimeConnection : AutoCloseable {
    fun updateNotificationPreferences(
        enabledThreadIds: Set<String>,
        knownNotificationIds: Set<String>
    )

    suspend fun measureLatency(
        sampleCount: Int,
        timeoutMillis: Long
    ): SocketLatencyTestResult
}

interface GatewayRealtimeClient {
    fun connect(
        config: GatewayConfig,
        lastEventId: String?,
        enabledThreadIds: Set<String>,
        knownNotificationIds: Set<String>,
        onEvent: (GatewayRealtimeEvent) -> Unit,
        onStateChanged: (StreamConnectionState) -> Unit
    ): GatewayRealtimeConnection
}
