package com.codex.mobilecontrol.data

import android.content.Context
import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.GatewayPreferences
import com.codex.mobilecontrol.model.GatewayConnectionMode
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadListItem

class AndroidGatewaySessionStore(
    private val context: Context
) : GatewaySessionStore {
    override fun loadConfig(): GatewayConfig? = GatewayPreferences.loadConfig(context)

    override fun saveConfig(config: GatewayConfig) {
        GatewayPreferences.saveConfig(context, config)
    }

    override fun clearSession() {
        GatewayPreferences.clearSession(context)
    }

    override fun loadConnectionMode(): GatewayConnectionMode {
        return GatewayPreferences.loadConnectionMode(context)
    }

    override fun saveConnectionMode(mode: GatewayConnectionMode) {
        GatewayPreferences.saveConnectionMode(context, mode)
    }

    override fun loadLastOpenedThreadId(): String? = GatewayPreferences.loadLastOpenedThreadId(context)

    override fun saveLastOpenedThreadId(threadId: String?) {
        GatewayPreferences.saveLastOpenedThreadId(context, threadId)
    }

    override fun loadCachedThreads(): List<ThreadListItem> {
        return GatewayPreferences.loadCachedThreads(context)
    }

    override fun saveCachedThreads(threads: List<ThreadListItem>) {
        GatewayPreferences.saveCachedThreads(context, threads)
    }

    override fun loadCachedThreadDetail(threadId: String): ThreadDetail? {
        return GatewayPreferences.loadCachedThreadDetail(context, threadId)
    }

    override fun saveCachedThreadDetail(detail: ThreadDetail) {
        GatewayPreferences.saveCachedThreadDetail(context, detail)
    }

    override fun loadQueuedTextMessages() = GatewayPreferences.loadQueuedTextMessages(context)

    override fun saveQueuedTextMessages(messages: List<QueuedTextMessage>) {
        GatewayPreferences.saveQueuedTextMessages(context, messages)
    }

    override fun setThreadCachePersistenceEnabled(enabled: Boolean) {
        GatewayPreferences.setThreadCachePersistenceEnabled(context, enabled)
    }

    override fun loadLastRealtimeEventId(): String? {
        return GatewayPreferences.loadLastRealtimeEventId(context)
    }

    override fun saveLastRealtimeEventId(eventId: String?) {
        GatewayPreferences.saveLastRealtimeEventId(context, eventId)
    }

    override fun loadKnownNotificationIds(): Set<String> {
        return GatewayPreferences.loadKnownNotificationIds(context)
    }

    override fun saveKnownNotificationIds(ids: Set<String>) {
        GatewayPreferences.saveKnownNotificationIds(context, ids)
    }

    override fun loadAlertEnabledThreadIds(): Set<String> {
        return GatewayPreferences.loadAlertEnabledThreadIds(context)
    }

    override fun clearThreadCache() {
        GatewayPreferences.clearThreadCache(context)
    }
}
