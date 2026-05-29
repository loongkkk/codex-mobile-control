package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.model.LatestApkInfo
import com.codex.mobilecontrol.model.MobileAutomationItem
import com.codex.mobilecontrol.model.PendingFileDraft
import com.codex.mobilecontrol.model.PendingImageDraft
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.network.GatewayRealtimeEvent
import com.codex.mobilecontrol.network.StreamConnectionState

data class MainUiState(
    val config: GatewayConfig? = null,
    val isConnected: Boolean = false,
    val connectionState: StreamConnectionState = StreamConnectionState.CLOSED,
    val threads: List<ThreadListItem> = emptyList(),
    val selectedThreadId: String? = null,
    val detail: ThreadDetail? = null,
    val isSending: Boolean = false,
    val isRefreshingThreads: Boolean = false,
    val isLoadingAutomations: Boolean = false,
    val isCheckingLatestApk: Boolean = false,
    val isTestingSocketLatency: Boolean = false,
    val socketLatencyAverageMillis: Long? = null,
    val socketLatencySampleCount: Int = 0,
    val socketLatencyError: String? = null,
    val latestApkInfo: LatestApkInfo? = null,
    val automations: List<MobileAutomationItem> = emptyList(),
    val latestApkCheckedAtMillis: Long? = null,
    val isLoadingOlderMessages: Boolean = false,
    val canLoadOlderMessages: Boolean = false,
    val pendingImageDrafts: List<PendingImageDraft> = emptyList(),
    val pendingFileDrafts: List<PendingFileDraft> = emptyList(),
    val queuedTextMessages: List<QueuedTextMessage> = emptyList(),
    val pendingRealtimeNotifications: List<GatewayRealtimeEvent.Notification> = emptyList(),
    val pendingDetailSyncThreadIds: Set<String> = emptySet(),
    val syncWarningMessage: String? = null,
    val errorMessage: String? = null
) {
    val pendingImageDraft: PendingImageDraft?
        get() = pendingImageDrafts.firstOrNull()

    val pendingFileDraft: PendingFileDraft?
        get() = pendingFileDrafts.firstOrNull()

    val queuedTextMessage: QueuedTextMessage?
        get() = queuedTextMessages.firstOrNull()
}
