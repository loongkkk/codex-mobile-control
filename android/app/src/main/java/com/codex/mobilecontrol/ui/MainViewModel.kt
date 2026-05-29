package com.codex.mobilecontrol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.model.DetailRefreshResult
import com.codex.mobilecontrol.model.DetailRefreshStatus
import com.codex.mobilecontrol.data.ThreadRepository
import com.codex.mobilecontrol.model.LatestApkInfo
import com.codex.mobilecontrol.model.MarkdownFilePreview
import com.codex.mobilecontrol.model.MobileAutomationItem
import com.codex.mobilecontrol.model.PendingFileDraft
import com.codex.mobilecontrol.model.PendingImageDraft
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.ThreadDetail
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

class MainViewModel(
    private val repository: ThreadRepository
) : ViewModel() {
    val state: StateFlow<MainUiState> = repository.state

    fun connect(config: GatewayConfig, requestedThreadId: String?) {
        viewModelScope.launch {
            runCatching { repository.connect(config, requestedThreadId) }
        }
    }

    fun selectThread(threadId: String) {
        viewModelScope.launch {
            runCatching { repository.selectThread(threadId) }
        }
    }

    fun refreshThreadForAlert(threadId: String, onFinished: (ThreadDetail?) -> Unit = {}) {
        viewModelScope.launch {
            val currentState = state.value
            val detail = if (currentState.selectedThreadId == threadId) {
                runCatching { repository.refreshThreadDetail(threadId) }
                state.value.detail?.takeIf { it.thread.threadId == threadId }
            } else {
                runCatching { repository.fetchThreadDetailForAlert(threadId) }.getOrNull()
            }
            onFinished(detail)
        }
    }

    fun refreshThreadDetail(threadId: String, onFinished: (DetailRefreshResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { repository.refreshThreadDetail(threadId) }
                .getOrElse {
                    DetailRefreshResult(
                        status = DetailRefreshStatus.FAILED,
                        changed = false,
                        messageCount = state.value.detail?.recentMessages?.size ?: 0,
                        lastMessageId = state.value.detail?.recentMessages?.lastOrNull()?.messageId,
                        errorMessage = it.message
                    )
                }
            onFinished(result)
        }
    }

    fun refreshThreads() {
        viewModelScope.launch {
            runCatching { repository.refreshThreads() }
        }
    }

    fun refreshAutomations(onResult: (List<MobileAutomationItem>) -> Unit = {}) {
        viewModelScope.launch {
            val automations = runCatching { repository.refreshAutomations() }
                .getOrElse { state.value.automations }
            onResult(automations)
        }
    }

    fun clearThreadCache() {
        repository.clearThreadCache()
    }

    fun logout() {
        repository.logout()
    }

    fun checkLatestApk(
        force: Boolean = false,
        onResult: (LatestApkInfo?) -> Unit = {}
    ) {
        viewModelScope.launch {
            val latestApkInfo = runCatching { repository.checkLatestApk(force) }
                .getOrNull()
            onResult(latestApkInfo)
        }
    }

    fun fetchGatewayDiagnosticsJson(onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { repository.fetchGatewayDiagnosticsJson() })
        }
    }

    suspend fun fetchGatewayDiagnosticsJson(): String {
        return repository.fetchGatewayDiagnosticsJson()
    }

    fun fetchMarkdownFilePreview(
        threadId: String,
        path: String,
        onResult: (Result<MarkdownFilePreview>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(runCatching { repository.fetchMarkdownFilePreview(threadId, path) })
        }
    }

    fun testSocketLatency() {
        viewModelScope.launch {
            runCatching { repository.testSocketLatency() }
        }
    }

    fun updateRealtimeNotificationPreferences(enabledThreadIds: Set<String>) {
        repository.updateRealtimeNotificationPreferences(enabledThreadIds)
    }

    fun markRealtimeNotificationDisplayed(notificationId: String) {
        repository.markRealtimeNotificationDisplayed(notificationId)
    }

    fun loadOlderMessages() {
        viewModelScope.launch {
            runCatching { repository.loadOlderMessages() }
        }
    }

    fun sendMessage(
        text: String,
        guide: Boolean = false,
        queue: Boolean = false,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching { repository.sendMessage(text, guide = guide, queue = queue) }
                .onSuccess { confirmed ->
                    if (confirmed) {
                        onSuccess()
                    }
                }
        }
    }

    fun queueMessageAfterCurrentTurn(text: String, onQueued: (QueuedTextMessage) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.queueMessageAfterCurrentTurn(text) }
                .onSuccess { message ->
                    if (message != null) {
                        onQueued(message)
                    }
                }
        }
    }

    fun cancelQueuedMessage(queueId: String? = null): QueuedTextMessage? {
        return repository.cancelQueuedMessage(queueId)
    }

    fun retryQueuedMessage(queueId: String) {
        repository.retryQueuedMessage(queueId)
    }

    fun retryFailedMessage(
        messageId: String,
        openStream: ((PendingImageDraft) -> InputStream)? = null,
        openFileStream: ((PendingFileDraft) -> InputStream)? = null
    ) {
        viewModelScope.launch {
            runCatching {
                repository.retryFailedMessage(
                    messageId = messageId,
                    openFileStream = openFileStream,
                    openStream = openStream
                )
            }
        }
    }

    fun setPendingImage(draft: PendingImageDraft) {
        repository.setPendingImage(draft)
    }

    fun addPendingImages(drafts: List<PendingImageDraft>) {
        repository.addPendingImages(drafts)
    }

    fun clearPendingImage() {
        repository.clearPendingImage()
    }

    fun removePendingImage(draft: PendingImageDraft) {
        repository.removePendingImage(draft)
    }

    fun setPendingFiles(drafts: List<PendingFileDraft>) {
        repository.setPendingFiles(drafts)
    }

    fun addPendingFiles(drafts: List<PendingFileDraft>) {
        repository.addPendingFiles(drafts)
    }

    fun clearPendingFile() {
        repository.clearPendingFile()
    }

    fun removePendingFile(draft: PendingFileDraft) {
        repository.removePendingFile(draft)
    }

    fun sendImageMessage(
        text: String,
        openStream: (PendingImageDraft) -> InputStream,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching { repository.sendImageMessage(text, openStream) }
                .onSuccess { confirmed ->
                    if (confirmed) {
                        onSuccess()
                    }
                }
        }
    }

    fun sendFileMessage(
        text: String,
        openStream: (PendingFileDraft) -> InputStream,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching { repository.sendFileMessage(text, openStream) }
                .onSuccess { confirmed ->
                    if (confirmed) {
                        onSuccess()
                    }
                }
        }
    }

    fun sendAttachmentMessage(
        text: String,
        openImageStream: (PendingImageDraft) -> InputStream,
        openFileStream: (PendingFileDraft) -> InputStream,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                repository.sendAttachmentMessage(
                    text = text,
                    openImageStream = openImageStream,
                    openFileStream = openFileStream
                )
            }.onSuccess { confirmed ->
                if (confirmed) {
                    onSuccess()
                }
            }
        }
    }

    class Factory(
        private val repository: ThreadRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository) as T
        }
    }
}
