package com.codex.mobilecontrol.data

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.model.GatewayConnectionMode
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadListItem

interface GatewaySessionStore {
    fun loadConfig(): GatewayConfig?

    fun saveConfig(config: GatewayConfig)

    fun clearSession()

    fun loadConnectionMode(): GatewayConnectionMode

    fun saveConnectionMode(mode: GatewayConnectionMode)

    fun loadLastOpenedThreadId(): String?

    fun saveLastOpenedThreadId(threadId: String?)

    fun loadCachedThreads(): List<ThreadListItem>

    fun saveCachedThreads(threads: List<ThreadListItem>)

    fun loadCachedThreadDetail(threadId: String): ThreadDetail?

    fun saveCachedThreadDetail(detail: ThreadDetail)

    fun loadQueuedTextMessages(): List<QueuedTextMessage>

    fun saveQueuedTextMessages(messages: List<QueuedTextMessage>)

    fun setThreadCachePersistenceEnabled(enabled: Boolean)

    fun loadLastRealtimeEventId(): String?

    fun saveLastRealtimeEventId(eventId: String?)

    fun loadKnownNotificationIds(): Set<String>

    fun saveKnownNotificationIds(ids: Set<String>)

    fun loadAlertEnabledThreadIds(): Set<String>

    fun clearThreadCache()
}
