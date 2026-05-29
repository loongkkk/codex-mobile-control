package com.codex.mobilecontrol.data

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.diagnostics.DebugTraceLogger
import com.codex.mobilecontrol.model.FileUploadSource
import com.codex.mobilecontrol.model.GatewayConnectionMode
import com.codex.mobilecontrol.model.GatewayStreamEvent
import com.codex.mobilecontrol.model.ImageUploadSource
import com.codex.mobilecontrol.model.MarkdownFilePreview
import com.codex.mobilecontrol.model.DetailRefreshResult
import com.codex.mobilecontrol.model.DetailRefreshStatus
import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.MobileAutomationItem
import com.codex.mobilecontrol.model.PendingFileDraft
import com.codex.mobilecontrol.model.PendingImageDraft
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.QueuedTextMessageStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadSelectionPolicy
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import com.codex.mobilecontrol.model.ThreadMessageSendState
import com.codex.mobilecontrol.network.GatewayApi
import com.codex.mobilecontrol.network.GatewayRealtimeClient
import com.codex.mobilecontrol.network.GatewayRealtimeConnection
import com.codex.mobilecontrol.network.GatewayRealtimeEvent
import com.codex.mobilecontrol.network.GatewaySseClient
import com.codex.mobilecontrol.network.SocketLatencyTestResult
import com.codex.mobilecontrol.network.StreamConnectionState
import com.codex.mobilecontrol.ui.MainUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID

class ThreadRepository(
    private val api: GatewayApi,
    private val sseClient: GatewaySseClient,
    private val sessionStore: GatewaySessionStore,
    private val realtimeClient: GatewayRealtimeClient? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(
        initialState()
    )
    private var streamHandle: AutoCloseable? = null
    private var realtimeHandle: GatewayRealtimeConnection? = null
    private var activeRealtimeMode: GatewayConnectionMode? = null
    private var realtimeCompensationJob: Job? = null
    private var queuedDispatchRefreshJob: Job? = null
    private var sseReconnectJob: Job? = null
    private var streamGeneration = 0
    private val pendingSseLatencyProbes = mutableMapOf<String, PendingSseLatencyProbe>()
    private var cacheGeneration = 0
    private var userSelectionGeneration = 0
    private var deferredRealtimeEventIdAfterPendingNotification: String? = null
    private val optimisticGapBackfillKeys = mutableSetOf<String>()
    private val socketFreshDetailThreadIds = mutableSetOf<String>()
    private val manualDetailRefreshLock = Any()
    private val manualDetailRefreshes = mutableMapOf<String, CompletableDeferred<DetailRefreshResult>>()

    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    private fun initialState(): MainUiState {
        val config = sessionStore.loadConfig()
        val queuedTextMessages = if (config != null) {
            restoredQueuedTextMessages()
        } else {
            emptyList()
        }
        val cachedThreads = sessionStore.loadCachedThreads()
        val selectedThreadId = if (config != null && cachedThreads.isNotEmpty()) {
            ThreadSelectionPolicy.selectThreadId(
                requestedThreadId = null,
                lastOpenedThreadId = sessionStore.loadLastOpenedThreadId(),
                threads = cachedThreads
            )
        } else {
            null
        }
        val cachedDetail = selectedThreadId
            ?.let { sessionStore.loadCachedThreadDetail(it) }
            ?.let(::sanitizeOptimisticEchoes)
        return MainUiState(
            config = config,
            isConnected = config != null && cachedThreads.isNotEmpty(),
            threads = cachedThreads,
            selectedThreadId = selectedThreadId,
            detail = cachedDetail,
            queuedTextMessages = queuedTextMessages,
            canLoadOlderMessages = cachedDetail?.recentMessages?.isNotEmpty() == true
        )
    }

    private fun restoredQueuedTextMessages(): List<QueuedTextMessage> {
        val storedMessages = sessionStore.loadQueuedTextMessages()
        val restoredMessages = storedMessages.mapNotNull { message ->
            val status = when (message.status) {
                QueuedTextMessageStatus.PENDING,
                QueuedTextMessageStatus.FAILED -> message.status
                QueuedTextMessageStatus.DISPATCHING -> QueuedTextMessageStatus.FAILED
                QueuedTextMessageStatus.SENT,
                QueuedTextMessageStatus.CANCELLED -> return@mapNotNull null
            }
            message.copy(
                status = status,
                dispatchStartedAtMillis = null,
                errorMessage = if (message.status == QueuedTextMessageStatus.DISPATCHING) {
                    message.errorMessage ?: "queue_dispatch_interrupted"
                } else {
                    message.errorMessage
                }
            )
        }
        if (restoredMessages != storedMessages) {
            persistQueuedTextMessages(restoredMessages)
        }
        return restoredMessages
    }

    private fun persistQueuedTextMessages(messages: List<QueuedTextMessage>) {
        sessionStore.saveQueuedTextMessages(messages)
    }

    private fun cacheThreads(threads: List<ThreadListItem>) {
        if (threads.isNotEmpty()) {
            sessionStore.saveCachedThreads(threads)
        }
    }

    private fun cacheDetail(detail: ThreadDetail?) {
        if (detail != null) {
            sessionStore.saveCachedThreadDetail(sanitizeOptimisticEchoes(detail))
        }
    }

    private fun cachedThreadDetail(threadId: String): ThreadDetail? {
        return sessionStore.loadCachedThreadDetail(threadId)
            ?.let { normalizeDetail(state.value.threads, it) ?: it }
            ?.let(::sanitizeOptimisticEchoes)
    }

    private fun sanitizeOptimisticEchoes(detail: ThreadDetail): ThreadDetail {
        return ThreadMessageMergeSupport.sanitizeOptimisticEchoes(detail)
    }

    private fun detailMessageCount(detail: ThreadDetail?): Int {
        return detail?.recentMessages?.size ?: 0
    }

    private fun detailLastMessageId(detail: ThreadDetail?): String? {
        return detail?.recentMessages?.lastOrNull()?.messageId
    }

    private fun detailRefreshResult(
        before: ThreadDetail?,
        after: ThreadDetail,
        errorMessage: String? = null
    ): DetailRefreshResult {
        val changed = before == null ||
            detailMessageCount(before) != detailMessageCount(after) ||
            detailLastMessageId(before) != detailLastMessageId(after) ||
            before.thread.updatedAt != after.thread.updatedAt
        val status = if (changed) {
            DetailRefreshStatus.UPDATED
        } else {
            DetailRefreshStatus.UNCHANGED
        }
        return DetailRefreshResult(
            status = status,
            changed = changed,
            messageCount = detailMessageCount(after),
            lastMessageId = detailLastMessageId(after),
            errorMessage = errorMessage
        )
    }

    private fun failedDetailRefreshResult(
        before: ThreadDetail?,
        errorMessage: String?
    ): DetailRefreshResult {
        return DetailRefreshResult(
            status = DetailRefreshStatus.FAILED,
            changed = false,
            messageCount = detailMessageCount(before),
            lastMessageId = detailLastMessageId(before),
            errorMessage = errorMessage
        )
    }

    private fun isCacheGenerationCurrent(generation: Int): Boolean {
        return generation == cacheGeneration
    }

    private fun enableThreadCachePersistence() {
        sessionStore.setThreadCachePersistenceEnabled(true)
    }

    private fun sendDisabledReasonForStatus(status: MobileThreadStatus): String? {
        return when (status) {
            MobileThreadStatus.RUNNING -> "线程仍在运行，暂不支持并发发送"
            MobileThreadStatus.OFFLINE -> "当前无法连接到 Codex sidecar"
            else -> null
        }
    }

    private fun mergeThreadsWithDetail(
        threads: List<ThreadListItem>,
        detail: ThreadDetail?
    ): List<ThreadListItem> {
        if (detail == null) {
            return threads
        }

        return threads.map { thread ->
            if (thread.threadId == detail.thread.threadId) {
                detail.thread
            } else {
                thread
            }
        }
    }

    private fun mergeMessagesById(
        existingMessages: List<ThreadMessage>,
        incomingMessages: List<ThreadMessage>
    ): List<ThreadMessage> {
        return ThreadMessageMergeSupport.mergeMessagesById(existingMessages, incomingMessages)
    }

    private fun pendingDetailSyncAfterUncertainEvent(
        latest: MainUiState,
        threadId: String
    ): Set<String> {
        return if (threadId in socketFreshDetailThreadIds) {
            latest.pendingDetailSyncThreadIds - threadId
        } else {
            latest.pendingDetailSyncThreadIds + threadId
        }
    }

    private fun pendingDetailSyncAfterFreshMessages(
        latest: MainUiState,
        threadId: String
    ): Set<String> {
        socketFreshDetailThreadIds += threadId
        return latest.pendingDetailSyncThreadIds - threadId
    }

    private fun mergeOptimisticMessages(
        existingMessages: List<ThreadMessage>,
        optimisticMessages: List<ThreadMessage>,
        sendState: ThreadMessageSendState?
    ): List<ThreadMessage> {
        return ThreadMessageMergeSupport.mergeOptimisticMessages(
            existingMessages = existingMessages,
            optimisticMessages = optimisticMessages,
            sendState = sendState
        )
    }

    private fun mergeDetailMessages(
        incomingDetail: ThreadDetail,
        existingDetail: ThreadDetail?
    ): ThreadDetail {
        return ThreadMessageMergeSupport.mergeDetailMessages(incomingDetail, existingDetail)
    }

    private fun messageTimestampMillis(message: ThreadMessage): Long {
        return ThreadMessageMergeSupport.messageTimestampMillis(message)
    }

    private suspend fun mergeDetailMessagesWithBackfill(
        config: GatewayConfig,
        incomingDetail: ThreadDetail,
        existingDetail: ThreadDetail?
    ): ThreadDetail {
        val mergedDetail = mergeDetailMessages(
            incomingDetail = incomingDetail,
            existingDetail = existingDetail
        )
        return runCatching {
            backfillOptimisticGap(
                config = config,
                incomingDetail = incomingDetail,
                existingDetail = existingDetail,
                mergedDetail = mergedDetail
            )
        }.getOrElse { error ->
            DebugTraceLogger.log(
                "message-backfill",
                "optimistic gap backfill skipped error=${error.message.orEmpty().take(120)}"
            )
            mergedDetail
        }
    }

    private suspend fun backfillOptimisticGap(
        config: GatewayConfig,
        incomingDetail: ThreadDetail,
        existingDetail: ThreadDetail?,
        mergedDetail: ThreadDetail
    ): ThreadDetail {
        if (existingDetail?.thread?.threadId != incomingDetail.thread.threadId ||
            incomingDetail.recentMessages.isEmpty()
        ) {
            return mergedDetail
        }

        val firstSnapshotMessage = incomingDetail.recentMessages
            .minByOrNull { messageTimestampMillis(it) }
            ?: return mergedDetail
        val firstSnapshotTimestamp = messageTimestampMillis(firstSnapshotMessage)
        val optimisticBoundary = existingDetail.recentMessages
            .filter { message ->
                isBackfillableOptimisticBoundary(message) &&
                    messageTimestampMillis(message) < firstSnapshotTimestamp
            }
            .maxByOrNull { messageTimestampMillis(it) }
            ?: return mergedDetail
        val backfillKey = listOf(
            incomingDetail.thread.threadId,
            optimisticBoundary.messageId
        ).joinToString("|")
        if (backfillKey in optimisticGapBackfillKeys) {
            return mergedDetail
        }

        val backfilledMessages = mutableListOf<ThreadMessage>()
        var afterMessageId = optimisticBoundary.messageId
        var afterTimestamp = optimisticBoundary.timestamp
        var reachedSnapshot = false
        var pageCount = 0
        while (pageCount < OPTIMISTIC_GAP_BACKFILL_MAX_PAGES && !reachedSnapshot) {
            val page = api.getNewThreadMessages(
                config = config,
                threadId = incomingDetail.thread.threadId,
                afterMessageId = afterMessageId,
                afterTimestamp = afterTimestamp,
                limit = MESSAGE_PAGE_LIMIT
            )
            val messages = page.messages.sortedBy { messageTimestampMillis(it) }
            if (messages.isEmpty()) {
                reachedSnapshot = true
                break
            }

            backfilledMessages += messages.filter { messageTimestampMillis(it) < firstSnapshotTimestamp }
            val lastMessage = messages.last()
            val lastTimestamp = messageTimestampMillis(lastMessage)
            reachedSnapshot = lastTimestamp >= firstSnapshotTimestamp
            if (lastTimestamp <= messageTimestampMillisByText(afterTimestamp)) {
                break
            }
            afterMessageId = lastMessage.messageId
            afterTimestamp = lastMessage.timestamp
            pageCount += 1
        }

        if (reachedSnapshot) {
            optimisticGapBackfillKeys += backfillKey
        }
        if (backfilledMessages.isEmpty()) {
            return mergedDetail
        }

        DebugTraceLogger.log(
            "message-backfill",
            "optimistic gap thread=${incomingDetail.thread.threadId} " +
                "from=${optimisticBoundary.messageId.takeLast(18)} " +
                "to=${firstSnapshotMessage.messageId.takeLast(18)} " +
                "count=${backfilledMessages.size} reached=$reachedSnapshot"
        )
        return mergedDetail.copy(
            recentMessages = mergeMessagesById(
                existingMessages = mergedDetail.recentMessages,
                incomingMessages = backfilledMessages
            )
        )
    }

    private fun messageTimestampMillisByText(timestamp: String): Long {
        return ThreadMessageMergeSupport.timestampMillis(timestamp)
    }

    private fun isBackfillableOptimisticBoundary(message: ThreadMessage): Boolean {
        return ThreadMessageMergeSupport.isBackfillableOptimisticBoundary(message)
    }

    private fun refreshedCanLoadOlderMessages(
        latest: MainUiState,
        threadId: String,
        detail: ThreadDetail
    ): Boolean {
        val latestDetail = latest.detail
        val alreadyTrackingThisThread =
            latest.selectedThreadId == threadId &&
                latestDetail?.thread?.threadId == threadId &&
                latestDetail.recentMessages.isNotEmpty()
        return if (alreadyTrackingThisThread) {
            latest.canLoadOlderMessages
        } else {
            detail.recentMessages.isNotEmpty()
        }
    }

    private fun normalizeDetail(
        threads: List<ThreadListItem>,
        detail: ThreadDetail?
    ): ThreadDetail? {
        val currentDetail = detail ?: return null
        val listThread = threads.firstOrNull { it.threadId == currentDetail.thread.threadId }
            ?: return currentDetail
        val resolvedThread = preferredThreadSnapshot(
            listThread = listThread,
            detailThread = currentDetail.thread
        )
        return if (resolvedThread == currentDetail.thread) {
            currentDetail
        } else {
            currentDetail.copy(thread = resolvedThread)
        }
    }

    private fun preferredThreadSnapshot(
        listThread: ThreadListItem,
        detailThread: ThreadListItem
    ): ThreadListItem {
        return when {
            isNewerThreadSnapshot(detailThread.updatedAt, listThread.updatedAt) -> detailThread
            isNewerThreadSnapshot(listThread.updatedAt, detailThread.updatedAt) -> listThread
            listThread.needsAttention && !detailThread.needsAttention -> listThread
            threadStatusPriority(listThread.status) > threadStatusPriority(detailThread.status) -> {
                listThread
            }
            else -> detailThread
        }
    }

    private fun isNewerThreadSnapshot(candidateUpdatedAt: String, baselineUpdatedAt: String): Boolean {
        val candidate = runCatching { Instant.parse(candidateUpdatedAt) }.getOrNull()
        val baseline = runCatching { Instant.parse(baselineUpdatedAt) }.getOrNull()
        return when {
            candidate != null && baseline != null -> candidate.isAfter(baseline)
            else -> candidateUpdatedAt > baselineUpdatedAt
        }
    }

    private fun threadStatusPriority(status: MobileThreadStatus): Int {
        return when (status) {
            MobileThreadStatus.ERROR -> 5
            MobileThreadStatus.WAITING_INPUT -> 4
            MobileThreadStatus.COMPLETED -> 3
            MobileThreadStatus.RUNNING -> 2
            MobileThreadStatus.IDLE -> 1
            MobileThreadStatus.OFFLINE -> 0
        }
    }

    private fun optimisticTextDetail(
        detail: ThreadDetail,
        text: String,
        summary: String,
        messageId: String,
        sendState: ThreadMessageSendState?,
        markThreadRunning: Boolean
    ): ThreadDetail {
        val now = Instant.ofEpochMilli(nowMillis()).toString()
        val existingMessage = detail.recentMessages.firstOrNull { it.messageId == messageId }
        val optimisticMessage = ThreadMessage(
            messageId = messageId,
            threadId = detail.thread.threadId,
            role = ThreadMessageRole.USER,
            kind = "text",
            text = text,
            timestamp = existingMessage?.timestamp ?: now,
            sendState = sendState
        )
        val optimisticMessages = mergeOptimisticMessages(
            existingMessages = detail.recentMessages,
            optimisticMessages = listOf(optimisticMessage),
            sendState = sendState
        )
        val optimisticThread = if (markThreadRunning) {
            detail.thread.copy(
                status = MobileThreadStatus.RUNNING,
                updatedAt = now,
                progressSummary = summary,
                needsAttention = false
            )
        } else {
            detail.thread
        }
        return detail.copy(
            thread = optimisticThread,
            recentMessages = optimisticMessages,
            sendAvailable = if (markThreadRunning) false else detail.sendAvailable,
            sendDisabledReason = if (markThreadRunning) summary else detail.sendDisabledReason
        )
    }

    private fun failedTextDetail(
        detail: ThreadDetail,
        messageId: String,
        reason: String
    ): ThreadDetail {
        return detail.copy(
            recentMessages = detail.recentMessages.map { message ->
                if (message.messageId == messageId) {
                    message.copy(sendState = ThreadMessageSendState.FAILED)
                } else {
                    message
                }
            },
            sendAvailable = true,
            sendDisabledReason = reason
        )
    }

    private fun optimisticImageDetail(
        detail: ThreadDetail,
        drafts: List<PendingImageDraft>,
        text: String,
        summary: String,
        clientMessageId: String,
        sendState: ThreadMessageSendState?,
        markThreadRunning: Boolean
    ): ThreadDetail {
        val now = Instant.ofEpochMilli(nowMillis()).toString()
        val optimisticMessages = drafts.mapIndexed { index, draft ->
            val messageId = imageMessageId(clientMessageId, index)
            val existingMessage = detail.recentMessages.firstOrNull { it.messageId == messageId }
            ThreadMessage(
                messageId = messageId,
                threadId = detail.thread.threadId,
                role = ThreadMessageRole.USER,
                kind = "image",
                text = if (index == 0) text.ifBlank { null } else null,
                imageUrl = draft.previewUri,
                thumbnailUrl = draft.previewUri,
                fileName = draft.fileName,
                timestamp = existingMessage?.timestamp ?: now,
                sendState = sendState,
                mimeType = draft.mimeType
            )
        }
        val mergedMessages = mergeOptimisticMessages(
            existingMessages = detail.recentMessages,
            optimisticMessages = optimisticMessages,
            sendState = sendState
        )
        val optimisticThread = if (markThreadRunning) {
            detail.thread.copy(
                status = MobileThreadStatus.RUNNING,
                updatedAt = now,
                progressSummary = summary,
                needsAttention = false
            )
        } else {
            detail.thread
        }
        return detail.copy(
            thread = optimisticThread,
            recentMessages = mergedMessages,
            sendAvailable = if (markThreadRunning) false else detail.sendAvailable,
            sendDisabledReason = if (markThreadRunning) summary else detail.sendDisabledReason
        )
    }

    private fun failedImageDetail(
        detail: ThreadDetail,
        clientMessageId: String,
        drafts: List<PendingImageDraft>,
        reason: String
    ): ThreadDetail {
        val failedMessageIds = drafts.indices
            .map { index -> imageMessageId(clientMessageId, index) }
            .toSet()
        return detail.copy(
            recentMessages = detail.recentMessages.map { message ->
                if (message.messageId in failedMessageIds) {
                    message.copy(sendState = ThreadMessageSendState.FAILED)
                } else {
                    message
                }
            },
            sendAvailable = true,
            sendDisabledReason = reason
        )
    }

    private fun optimisticFileDetail(
        detail: ThreadDetail,
        drafts: List<PendingFileDraft>,
        text: String,
        summary: String,
        clientMessageId: String,
        sendState: ThreadMessageSendState?,
        markThreadRunning: Boolean
    ): ThreadDetail {
        val now = Instant.ofEpochMilli(nowMillis()).toString()
        val optimisticMessages = drafts.mapIndexed { index, draft ->
            val messageId = fileMessageId(clientMessageId, index)
            val existingMessage = detail.recentMessages.firstOrNull { it.messageId == messageId }
            ThreadMessage(
                messageId = messageId,
                threadId = detail.thread.threadId,
                role = ThreadMessageRole.USER,
                kind = "file",
                text = if (index == 0) text.ifBlank { null } else null,
                fileUrl = draft.previewUri,
                fileName = draft.fileName,
                timestamp = existingMessage?.timestamp ?: now,
                sendState = sendState,
                mimeType = draft.mimeType
            )
        }
        val mergedMessages = mergeOptimisticMessages(
            existingMessages = detail.recentMessages,
            optimisticMessages = optimisticMessages,
            sendState = sendState
        )
        val optimisticThread = if (markThreadRunning) {
            detail.thread.copy(
                status = MobileThreadStatus.RUNNING,
                updatedAt = now,
                progressSummary = summary,
                needsAttention = false
            )
        } else {
            detail.thread
        }
        return detail.copy(
            thread = optimisticThread,
            recentMessages = mergedMessages,
            sendAvailable = if (markThreadRunning) false else detail.sendAvailable,
            sendDisabledReason = if (markThreadRunning) summary else detail.sendDisabledReason
        )
    }

    private fun failedFileDetail(
        detail: ThreadDetail,
        clientMessageId: String,
        drafts: List<PendingFileDraft>,
        reason: String
    ): ThreadDetail {
        val failedMessageIds = drafts.indices
            .map { index -> fileMessageId(clientMessageId, index) }
            .toSet()
        return detail.copy(
            recentMessages = detail.recentMessages.map { message ->
                if (message.messageId in failedMessageIds) {
                    message.copy(sendState = ThreadMessageSendState.FAILED)
                } else {
                    message
                }
            },
            sendAvailable = true,
            sendDisabledReason = reason
        )
    }

    private fun optimisticAttachmentDetail(
        detail: ThreadDetail,
        imageDrafts: List<PendingImageDraft>,
        fileDrafts: List<PendingFileDraft>,
        text: String,
        summary: String,
        clientMessageId: String,
        sendState: ThreadMessageSendState?,
        markThreadRunning: Boolean
    ): ThreadDetail {
        val now = Instant.ofEpochMilli(nowMillis()).toString()
        val imageMessages = imageDrafts.mapIndexed { index, draft ->
            val messageId = attachmentImageMessageId(clientMessageId, index)
            val existingMessage = detail.recentMessages.firstOrNull { it.messageId == messageId }
            ThreadMessage(
                messageId = messageId,
                threadId = detail.thread.threadId,
                role = ThreadMessageRole.USER,
                kind = "image",
                text = if (index == 0) text.ifBlank { null } else null,
                imageUrl = draft.previewUri,
                thumbnailUrl = draft.previewUri,
                fileName = draft.fileName,
                timestamp = existingMessage?.timestamp ?: now,
                sendState = sendState,
                mimeType = draft.mimeType
            )
        }
        val fileMessages = fileDrafts.mapIndexed { index, draft ->
            val messageId = attachmentFileMessageId(clientMessageId, index)
            val existingMessage = detail.recentMessages.firstOrNull { it.messageId == messageId }
            ThreadMessage(
                messageId = messageId,
                threadId = detail.thread.threadId,
                role = ThreadMessageRole.USER,
                kind = "file",
                text = if (imageDrafts.isEmpty() && index == 0) text.ifBlank { null } else null,
                fileUrl = draft.previewUri,
                fileName = draft.fileName,
                timestamp = existingMessage?.timestamp ?: now,
                sendState = sendState,
                mimeType = draft.mimeType
            )
        }
        val mergedMessages = mergeOptimisticMessages(
            existingMessages = detail.recentMessages,
            optimisticMessages = imageMessages + fileMessages,
            sendState = sendState
        )
        val optimisticThread = if (markThreadRunning) {
            detail.thread.copy(
                status = MobileThreadStatus.RUNNING,
                updatedAt = now,
                progressSummary = summary,
                needsAttention = false
            )
        } else {
            detail.thread
        }
        return detail.copy(
            thread = optimisticThread,
            recentMessages = mergedMessages,
            sendAvailable = if (markThreadRunning) false else detail.sendAvailable,
            sendDisabledReason = if (markThreadRunning) summary else detail.sendDisabledReason
        )
    }

    private fun failedAttachmentDetail(
        detail: ThreadDetail,
        clientMessageId: String,
        imageDrafts: List<PendingImageDraft>,
        fileDrafts: List<PendingFileDraft>,
        reason: String
    ): ThreadDetail {
        val failedMessageIds = imageDrafts.indices
            .map { index -> attachmentImageMessageId(clientMessageId, index) }
            .toSet() + fileDrafts.indices
            .map { index -> attachmentFileMessageId(clientMessageId, index) }
            .toSet()
        return detail.copy(
            recentMessages = detail.recentMessages.map { message ->
                if (message.messageId in failedMessageIds) {
                    message.copy(sendState = ThreadMessageSendState.FAILED)
                } else {
                    message
                }
            },
            sendAvailable = true,
            sendDisabledReason = reason
        )
    }

    private fun imageMessageId(clientMessageId: String, index: Int): String {
        return if (index == 0) clientMessageId else "$clientMessageId:image-$index"
    }

    private fun fileMessageId(clientMessageId: String, index: Int): String {
        return if (index == 0) clientMessageId else "$clientMessageId:file-$index"
    }

    private fun attachmentImageMessageId(clientMessageId: String, index: Int): String {
        return "$clientMessageId:image-$index"
    }

    private fun attachmentFileMessageId(clientMessageId: String, index: Int): String {
        return "$clientMessageId:file-$index"
    }

    private fun keepCurrentShellOnReconnectFailure(state: MainUiState): Boolean {
        return state.isConnected && (state.detail != null || state.threads.isNotEmpty())
    }

    private fun sendSummary(warning: String?): String {
        return if (warning == "desktop_send_confirmation_timeout") {
            "消息已发到桌面，正在等待线程确认"
        } else {
            "消息已发送，线程正在处理中"
        }
    }

    private fun sendImageSummary(warning: String?): String {
        return if (warning == "desktop_send_confirmation_timeout") {
            "图片已发到桌面，正在等待线程确认"
        } else {
            "图片已发送，线程正在处理中"
        }
    }

    private fun sendFileSummary(warning: String?): String {
        return if (warning == "desktop_send_confirmation_timeout") {
            "文件已发到桌面，正在等待线程确认"
        } else {
            "文件已发送，线程正在处理中"
        }
    }

    private fun sendAttachmentSummary(warning: String?): String {
        return if (warning == "desktop_send_confirmation_timeout") {
            "附件已发到桌面，正在等待线程确认"
        } else {
            "附件已发送，线程正在处理中"
        }
    }

    private fun displayableSendWarning(warning: String?): String? {
        return when (warning) {
            null -> null
            "desktop_send_confirmation_timeout" -> null
            else -> warning
        }
    }

    private suspend fun refreshSelectedThread(
        config: GatewayConfig,
        threadId: String
    ): Pair<List<ThreadListItem>, ThreadDetail> {
        val detail = ThreadDetailSnapshotSource.fetch(api, config, threadId)
        val threads = state.value.threads
        val normalizedDetail = requireNotNull(normalizeDetail(threads, detail))
        return mergeThreadsWithDetail(threads, normalizedDetail) to normalizedDetail
    }

    private suspend fun refreshDetailWithBestAvailablePath(
        config: GatewayConfig,
        threadId: String
    ): Pair<List<ThreadListItem>, ThreadDetail> {
        return refreshSelectedThread(config, threadId)
    }

    private suspend fun loadThreadDetailFromCurrentThreads(
        config: GatewayConfig,
        threadId: String
    ): Pair<List<ThreadListItem>, ThreadDetail> {
        val detail = requireNotNull(
            normalizeDetail(
                threads = state.value.threads,
                detail = ThreadDetailSnapshotSource.fetch(api, config, threadId)
            )
        )
        return mergeThreadsWithDetail(state.value.threads, detail) to detail
    }

    private fun threadShellDetail(threadId: String): ThreadDetail? {
        val thread = state.value.threads.firstOrNull { it.threadId == threadId } ?: return null
        val sendAvailable = thread.status != MobileThreadStatus.RUNNING &&
            thread.status != MobileThreadStatus.OFFLINE
        return ThreadDetail(
            thread = thread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = sendAvailable,
            sendDisabledReason = sendDisabledReasonForStatus(thread.status)
        )
    }

    private fun schedulePostSendDetailRefresh(config: GatewayConfig, threadId: String) {
        POST_SEND_REFRESH_DELAYS_MS.forEach { delayMs ->
            scope.launch {
                delay(delayMs)
                refreshSelectedThreadIfStillSelected(config, threadId)
            }
        }
    }

    private fun schedulePendingTextSendWatchdog(
        config: GatewayConfig,
        threadId: String,
        clientMessageId: String
    ) {
        scope.launch {
            delay(SEND_CONFIRMATION_WATCHDOG_MS)
            markPendingTextSendUnconfirmed(config, threadId, clientMessageId)
        }
    }

    private fun markPendingTextSendUnconfirmed(
        config: GatewayConfig,
        threadId: String,
        clientMessageId: String
    ) {
        val latest = mutableState.value
        if (latest.config != config) {
            return
        }
        val currentDetail = latest.detail?.takeIf { it.thread.threadId == threadId }
        val sourceDetail = currentDetail ?: cachedThreadDetail(threadId) ?: return
        val pendingMessage = sourceDetail.recentMessages.firstOrNull { message ->
            message.messageId == clientMessageId &&
                message.role == ThreadMessageRole.USER &&
                message.sendState == ThreadMessageSendState.SENDING
        } ?: return
        DebugTraceLogger.log(
            "send-text",
            "sendWatchdogTimeout thread=$threadId client=$clientMessageId " +
                "text=${pendingMessage.text.orEmpty().take(80).replace('\n', ' ')}"
        )

        val failedDetail = failedTextDetail(
            detail = sourceDetail,
            messageId = clientMessageId,
            reason = "发送未确认，点红色感叹号重发"
        )
        cacheDetail(failedDetail)
        if (currentDetail == null) {
            return
        }
        val failedThreads = mergeThreadsWithDetail(latest.threads, failedDetail)
        mutableState.value = latest.copy(
            threads = failedThreads,
            detail = failedDetail,
            isSending = false
        )
        cacheThreads(failedThreads)
    }

    private fun scheduleQueuedDispatchRefresh(config: GatewayConfig, threadId: String) {
        val current = mutableState.value
        if (!shouldPollQueuedDispatch(current, config, threadId)) {
            return
        }
        queuedDispatchRefreshJob?.cancel()
        queuedDispatchRefreshJob = scope.launch {
            repeat(QUEUED_DISPATCH_REFRESH_MAX_ATTEMPTS) { attempt ->
                delay(QUEUED_DISPATCH_REFRESH_INTERVAL_MS)
                val beforeRefresh = mutableState.value
                if (!shouldPollQueuedDispatch(beforeRefresh, config, threadId)) {
                    return@launch
                }
                DebugTraceLogger.log(
                    "queue-dispatch",
                    "poll thread=$threadId attempt=${attempt + 1} pending=${pendingQueuedTextCount(beforeRefresh, threadId)}"
                )
                refreshSelectedThreadIfStillSelected(config, threadId)
                val afterRefresh = mutableState.value
                if (!shouldPollQueuedDispatch(afterRefresh, config, threadId)) {
                    return@launch
                }
            }
        }
    }

    private fun shouldPollQueuedDispatch(
        state: MainUiState,
        config: GatewayConfig,
        threadId: String
    ): Boolean {
        return state.config == config &&
            state.selectedThreadId == threadId &&
            pendingQueuedTextCount(state, threadId) > 0 &&
            state.queuedTextMessages.none { message ->
                message.threadId == threadId &&
                    (
                        message.status == QueuedTextMessageStatus.DISPATCHING ||
                            message.status == QueuedTextMessageStatus.FAILED
                        )
            }
    }

    private fun pendingQueuedTextCount(state: MainUiState, threadId: String): Int {
        return state.queuedTextMessages.count { message ->
            message.threadId == threadId && message.status == QueuedTextMessageStatus.PENDING
        }
    }

    private fun scheduleRealtimeCompensationRefresh(config: GatewayConfig) {
        realtimeCompensationJob?.cancel()
        realtimeCompensationJob = scope.launch {
            delay(REALTIME_COMPENSATION_REFRESH_DELAY_MS)
            val threadId = mutableState.value.selectedThreadId
            if (threadId != null) {
                refreshSelectedThreadIfStillSelected(config, threadId)
            } else {
                refreshThreads()
            }
        }
    }

    private suspend fun refreshSelectedThreadIfStillSelected(
        config: GatewayConfig,
        threadId: String
    ) {
        runCatching {
            if (mutableState.value.selectedThreadId != threadId) {
                return
            }

            val (threads, detail) = refreshDetailWithBestAvailablePath(config, threadId)
            DebugTraceLogger.log(
                "detail-refresh",
                "refreshSelectedThread thread=$threadId fetched=${detail.recentMessages.size} " +
                    "ids=${detail.recentMessages.joinToString(",") { it.messageId.takeLast(18) }}"
            )
            val latest = mutableState.value
            if (latest.selectedThreadId != threadId) {
                return
            }

            val mergedThreads = mergeThreadsWithDetail(threads, detail)
            val visualDetail = mergeDetailMessagesWithBackfill(config, detail, latest.detail)
            socketFreshDetailThreadIds -= threadId
            mutableState.value = latest.copy(
                threads = mergedThreads,
                detail = visualDetail,
                canLoadOlderMessages = refreshedCanLoadOlderMessages(
                    latest = latest,
                    threadId = threadId,
                    detail = visualDetail
                ),
                isLoadingOlderMessages = latest.isLoadingOlderMessages,
                pendingDetailSyncThreadIds = latest.pendingDetailSyncThreadIds - threadId,
                syncWarningMessage = null,
                errorMessage = null
            )
            cacheThreads(mergedThreads)
            cacheDetail(visualDetail)
            maybeDispatchQueuedTextMessage()
        }.onFailure { error ->
            val syncWarning = error.toSyncWarningMessage()
                ?: error.toBackgroundRefreshErrorMessage()
                ?: error.toConnectionErrorMessage()
            mutableState.value = mutableState.value.copy(syncWarningMessage = syncWarning)
            DebugTraceLogger.log(
                "detail-refresh",
                "refreshSelectedThread.failed thread=$threadId error=${syncWarning.take(120)}"
            )
        }
    }

    companion object {
        private const val MESSAGE_PAGE_LIMIT = 20
        private const val LATEST_APK_CHECK_INTERVAL_MS = 60 * 60 * 1_000L
        private const val REALTIME_COMPENSATION_REFRESH_DELAY_MS = 1_500L
        private const val QUEUED_DISPATCH_REFRESH_INTERVAL_MS = 5_000L
        private const val QUEUED_DISPATCH_REFRESH_MAX_ATTEMPTS = 360
        private const val OPTIMISTIC_GAP_BACKFILL_MAX_PAGES = 10
        private const val SSE_RECONNECT_DELAY_MS = 1_000L
        private const val SOCKET_LATENCY_SAMPLE_COUNT = 3
        private const val SOCKET_LATENCY_TIMEOUT_MS = 5_000L
        private const val DIAGNOSTICS_GATEWAY_FETCH_TIMEOUT_MS = 8_000L
        private const val SEND_CONFIRMATION_WATCHDOG_MS = 60_000L
        private val POST_SEND_REFRESH_DELAYS_MS = longArrayOf(1_500L, 5_000L, 15_000L)
    }

    suspend fun connect(config: GatewayConfig, requestedThreadId: String?) = withContext(dispatcher) {
        enableThreadCachePersistence()
        val cacheGenerationSnapshot = cacheGeneration
        val userSelectionGenerationSnapshot = userSelectionGeneration
        val previousState = mutableState.value
        val reuseRealtimeConnection = canReuseRealtimeConnection(config, previousState)
        var gatewayReached = false
        val requestedCachedDetail = requestedThreadId?.let { cachedThreadDetail(it) }
        val warmThreads = if (requestedCachedDetail != null) {
            mergeThreadsWithDetail(
                previousState.threads.ifEmpty { sessionStore.loadCachedThreads() },
                requestedCachedDetail
            )
        } else {
            previousState.threads
        }
        if (requestedCachedDetail != null) {
            mutableState.value = previousState.copy(
                config = config,
                isConnected = true,
                connectionState = if (reuseRealtimeConnection) {
                    previousState.connectionState
                } else {
                    StreamConnectionState.CONNECTING
                },
                threads = warmThreads,
                selectedThreadId = requestedCachedDetail.thread.threadId,
                detail = requestedCachedDetail,
                canLoadOlderMessages = requestedCachedDetail.recentMessages.isNotEmpty(),
                isLoadingOlderMessages = false,
                errorMessage = null
            )
        }
        runCatching {
            sessionStore.saveConfig(config)

            val login = api.login(config)
            check(login.authenticated) { "token_invalid" }
            gatewayReached = true

            val latestAfterLogin = mutableState.value
            val canApplyConnectSelection =
                userSelectionGeneration == userSelectionGenerationSnapshot
            mutableState.value = latestAfterLogin.copy(
                config = config,
                isConnected = true,
                connectionState = if (reuseRealtimeConnection) {
                    latestAfterLogin.connectionState
                } else {
                    StreamConnectionState.CONNECTING
                },
                threads = if (canApplyConnectSelection) {
                    warmThreads
                } else {
                    latestAfterLogin.threads.ifEmpty { warmThreads }
                },
                selectedThreadId = if (canApplyConnectSelection) {
                    requestedCachedDetail?.thread?.threadId ?: previousState.selectedThreadId
                } else {
                    latestAfterLogin.selectedThreadId
                },
                detail = if (canApplyConnectSelection) {
                    requestedCachedDetail ?: previousState.detail
                } else {
                    latestAfterLogin.detail
                },
                canLoadOlderMessages = if (canApplyConnectSelection) {
                    requestedCachedDetail?.recentMessages?.isNotEmpty()
                        ?: previousState.canLoadOlderMessages
                } else {
                    latestAfterLogin.canLoadOlderMessages
                },
                isLoadingOlderMessages = false,
                syncWarningMessage = null,
                errorMessage = null
            )

            if (reuseRealtimeConnection) {
                DebugTraceLogger.log(
                    "realtime",
                    "reuseExistingConnection mode=${activeRealtimeMode?.wireValue ?: "none"} state=${mutableState.value.connectionState}"
                )
            } else {
                connectRealtimeStream(config)
            }

            val threads = api.getThreads(config)
            val selectedThreadId = ThreadSelectionPolicy.selectThreadId(
                requestedThreadId = requestedThreadId,
                lastOpenedThreadId = sessionStore.loadLastOpenedThreadId(),
                threads = threads
            )
            val detail = normalizeDetail(
                threads = threads,
                detail = selectedThreadId?.let { api.getThreadDetail(config, it) }
            )
            val visualDetail = detail?.let {
                mergeDetailMessagesWithBackfill(
                    config = config,
                    incomingDetail = it,
                    existingDetail = mutableState.value.detail
                )
            }
            val mergedThreads = mergeThreadsWithDetail(threads, visualDetail)
            if (!isCacheGenerationCurrent(cacheGenerationSnapshot)) {
                val latest = mutableState.value
                mutableState.value = latest.copy(
                    config = config,
                    isConnected = true,
                    isLoadingOlderMessages = false,
                    syncWarningMessage = null,
                    errorMessage = null
                )
                return@runCatching
            }
            if (userSelectionGeneration != userSelectionGenerationSnapshot) {
                val latest = mutableState.value
                val preservedDetail = normalizeDetail(threads, latest.detail)
                    ?.let { mergeDetailMessages(it, latest.detail) }
                val preservedThreads = mergeThreadsWithDetail(threads, preservedDetail)
                cacheThreads(preservedThreads)
                cacheDetail(preservedDetail)
                mutableState.value = latest.copy(
                    config = config,
                    isConnected = true,
                    threads = preservedThreads,
                    detail = preservedDetail,
                    canLoadOlderMessages = preservedDetail
                        ?.let {
                            refreshedCanLoadOlderMessages(
                                latest = latest,
                                threadId = it.thread.threadId,
                                detail = it
                            )
                        }
                        ?: false,
                    isLoadingOlderMessages = false,
                    syncWarningMessage = null,
                    errorMessage = null
                )
                return@runCatching
            }
            cacheThreads(mergedThreads)
            cacheDetail(visualDetail)
            if (selectedThreadId != null) {
                sessionStore.saveLastOpenedThreadId(selectedThreadId)
            }

            mutableState.value = mutableState.value.copy(
                config = config,
                isConnected = true,
                threads = mergedThreads,
                selectedThreadId = selectedThreadId,
                detail = visualDetail,
                canLoadOlderMessages = visualDetail?.recentMessages?.isNotEmpty() == true,
                isLoadingOlderMessages = false,
                syncWarningMessage = null,
                errorMessage = null
            )
            resumeQueuedDispatch(config)
        }.onFailure { error ->
            val errorMessage = error.toConnectionErrorMessage()
            mutableState.value = if (gatewayReached) {
                mutableState.value.copy(
                    config = config,
                    isConnected = true,
                    syncWarningMessage = error.toSyncWarningMessage(),
                    errorMessage = error.toBackgroundRefreshErrorMessage()
                )
            } else if (keepCurrentShellOnReconnectFailure(previousState)) {
                previousState.copy(
                    config = config,
                    connectionState = StreamConnectionState.FAILED,
                    syncWarningMessage = error.toSyncWarningMessage(),
                    errorMessage = errorMessage
                )
            } else {
                previousState.copy(
                    config = config,
                    isConnected = false,
                    connectionState = StreamConnectionState.FAILED,
                    errorMessage = errorMessage
                )
            }
        }
    }

    private fun connectRealtimeStream(config: GatewayConfig) {
        closeSseStream()
        realtimeHandle?.close()
        realtimeHandle = null

        if (sessionStore.loadConnectionMode() == GatewayConnectionMode.SOCKET && realtimeClient != null) {
            activeRealtimeMode = GatewayConnectionMode.SOCKET
            var socketOpenedOnce = false
            realtimeHandle = realtimeClient.connect(
                config = config,
                lastEventId = sessionStore.loadLastRealtimeEventId(),
                enabledThreadIds = sessionStore.loadAlertEnabledThreadIds(),
                knownNotificationIds = sessionStore.loadKnownNotificationIds(),
                onEvent = { event ->
                    scope.launch {
                        handleRealtimeEvent(event)
                    }
                },
                onStateChanged = { connectionState ->
                    mutableState.value = mutableState.value.copy(
                        connectionState = connectionState
                    )
                    when (connectionState) {
                        StreamConnectionState.RECONNECTING -> scheduleRealtimeCompensationRefresh(config)
                        StreamConnectionState.OPEN -> {
                            if (socketOpenedOnce) {
                                scheduleRealtimeCompensationRefresh(config)
                            }
                            socketOpenedOnce = true
                        }
                        StreamConnectionState.FAILED -> startSseFallback(config)
                        else -> Unit
                    }
                }
            )
            return
        }

        startSseStream(config)
    }

    private fun canReuseRealtimeConnection(
        config: GatewayConfig,
        currentState: MainUiState
    ): Boolean {
        if (currentState.config != config) {
            return false
        }
        if (activeRealtimeMode != sessionStore.loadConnectionMode()) {
            return false
        }
        val hasActiveHandle = when (activeRealtimeMode) {
            GatewayConnectionMode.SOCKET -> realtimeHandle != null
            GatewayConnectionMode.SSE -> streamHandle != null
            null -> false
        }
        if (!hasActiveHandle) {
            return false
        }
        return currentState.connectionState == StreamConnectionState.OPEN ||
            currentState.connectionState == StreamConnectionState.CONNECTING ||
            currentState.connectionState == StreamConnectionState.RECONNECTING
    }

    private fun startSseFallback(config: GatewayConfig) {
        realtimeCompensationJob?.cancel()
        realtimeHandle?.close()
        realtimeHandle = null
        startSseStream(config)
    }

    private fun closeSseStream() {
        streamGeneration += 1
        sseReconnectJob?.cancel()
        sseReconnectJob = null
        streamHandle?.close()
        streamHandle = null
        if (activeRealtimeMode == GatewayConnectionMode.SSE) {
            activeRealtimeMode = null
        }
    }

    fun logout() {
        cacheGeneration += 1
        userSelectionGeneration += 1
        realtimeCompensationJob?.cancel()
        realtimeCompensationJob = null
        queuedDispatchRefreshJob?.cancel()
        queuedDispatchRefreshJob = null
        closeSseStream()
        realtimeHandle?.close()
        realtimeHandle = null
        activeRealtimeMode = null
        pendingSseLatencyProbes.clear()
        socketFreshDetailThreadIds.clear()
        sessionStore.clearSession()
        mutableState.value = MainUiState(connectionState = StreamConnectionState.CLOSED)
    }

    private fun startSseStream(config: GatewayConfig) {
        closeSseStream()
        val generation = streamGeneration
        activeRealtimeMode = GatewayConnectionMode.SSE
        streamHandle = sseClient.connect(
            config = config,
            onEvent = { event ->
                scope.launch {
                    handleStreamEvent(event)
                }
            },
            onStateChanged = streamState@ { connectionState ->
                if (generation != streamGeneration) {
                    return@streamState
                }
                mutableState.value = mutableState.value.copy(
                    connectionState = connectionState
                )
                when (connectionState) {
                    StreamConnectionState.RECONNECTING -> scheduleSseReconnect(config, generation)
                    StreamConnectionState.OPEN -> {
                        sseReconnectJob?.cancel()
                        sseReconnectJob = null
                    }
                    else -> Unit
                }
            }
        )
    }

    private fun scheduleSseReconnect(config: GatewayConfig, generation: Int) {
        if (sseReconnectJob?.isActive == true) {
            return
        }
        sseReconnectJob = scope.launch {
            delay(SSE_RECONNECT_DELAY_MS)
            if (generation == streamGeneration && state.value.config == config) {
                startSseStream(config)
            }
        }
    }

    suspend fun selectThread(threadId: String) = withContext(dispatcher) {
        enableThreadCachePersistence()
        userSelectionGeneration += 1
        var partialDetailDisplayed = false
        runCatching {
            val config = requireNotNull(state.value.config)
            val cachedDetail = cachedThreadDetail(threadId)
            if (cachedDetail != null) {
                sessionStore.saveLastOpenedThreadId(threadId)
                mutableState.value = mutableState.value.copy(
                    threads = mergeThreadsWithDetail(state.value.threads, cachedDetail),
                    selectedThreadId = threadId,
                    detail = cachedDetail,
                    canLoadOlderMessages = cachedDetail.recentMessages.isNotEmpty(),
                    isLoadingOlderMessages = false,
                    errorMessage = null
                )
                partialDetailDisplayed = true
            } else {
                val shellDetail = threadShellDetail(threadId)
                if (shellDetail != null) {
                    sessionStore.saveLastOpenedThreadId(threadId)
                    mutableState.value = mutableState.value.copy(
                        threads = mergeThreadsWithDetail(state.value.threads, shellDetail),
                        selectedThreadId = threadId,
                        detail = shellDetail,
                        canLoadOlderMessages = false,
                        isLoadingOlderMessages = false,
                        errorMessage = null
                    )
                    partialDetailDisplayed = true
                }
            }

            if (cachedDetail == null) {
                val preview = runCatching {
                    normalizeDetail(
                        threads = state.value.threads,
                        detail = api.getThreadPreview(config, threadId)
                    )
                }.getOrElse {
                    null
                }
                if (preview != null) {
                    sessionStore.saveLastOpenedThreadId(threadId)
                    val previewThreads = mergeThreadsWithDetail(state.value.threads, preview)
                    mutableState.value = mutableState.value.copy(
                        threads = previewThreads,
                        selectedThreadId = threadId,
                        detail = preview,
                        canLoadOlderMessages = preview.recentMessages.isNotEmpty(),
                        isLoadingOlderMessages = false,
                        errorMessage = null
                    )
                    partialDetailDisplayed = true
                }
            }

            val latestBeforeRefresh = mutableState.value
            val hasPendingDetailSync = threadId in latestBeforeRefresh.pendingDetailSyncThreadIds
            if (cachedDetail != null &&
                threadId in socketFreshDetailThreadIds &&
                !hasPendingDetailSync
            ) {
                socketFreshDetailThreadIds -= threadId
                maybeDispatchQueuedTextMessage()
                return@runCatching
            }

            val (detailThreads, detail) = if (cachedDetail != null) {
                refreshDetailWithBestAvailablePath(
                    config = config,
                    threadId = threadId
                )
            } else {
                loadThreadDetailFromCurrentThreads(config, threadId)
            }
            sessionStore.saveLastOpenedThreadId(threadId)
            val latest = mutableState.value
            if (latest.selectedThreadId != threadId) {
                return@runCatching
            }
            val visualDetail = mergeDetailMessagesWithBackfill(config, detail, latest.detail)
            socketFreshDetailThreadIds -= threadId
            mutableState.value = latest.copy(
                threads = detailThreads,
                selectedThreadId = threadId,
                detail = visualDetail,
                canLoadOlderMessages = refreshedCanLoadOlderMessages(
                    latest = latest,
                    threadId = threadId,
                    detail = visualDetail
                ),
                isLoadingOlderMessages = false,
                pendingDetailSyncThreadIds = latest.pendingDetailSyncThreadIds - threadId,
                syncWarningMessage = null,
                errorMessage = null
            )
            cacheThreads(detailThreads)
            cacheDetail(visualDetail)
            maybeDispatchQueuedTextMessage()
        }.onFailure { error ->
            val syncWarning = error.toSyncWarningMessage()
            if (partialDetailDisplayed) {
                val backgroundRefreshError = error.toBackgroundRefreshErrorMessage()
                if (backgroundRefreshError != null || syncWarning != null) {
                    mutableState.value = mutableState.value.copy(
                        syncWarningMessage = syncWarning,
                        errorMessage = backgroundRefreshError
                    )
                }
                return@onFailure
            }
            mutableState.value = mutableState.value.copy(
                syncWarningMessage = syncWarning,
                errorMessage = error.message ?: "thread_load_failed"
            )
        }
    }

    suspend fun refreshThreadDetail(threadId: String): DetailRefreshResult = withContext(dispatcher) {
        val existingRefresh = synchronized(manualDetailRefreshLock) {
            manualDetailRefreshes[threadId]
        }
        if (existingRefresh != null) {
            DebugTraceLogger.log("detail-refresh", "manual.join thread=$threadId")
            return@withContext existingRefresh.await()
        }
        val currentRefresh = CompletableDeferred<DetailRefreshResult>()
        val racedRefresh = synchronized(manualDetailRefreshLock) {
            val existing = manualDetailRefreshes[threadId]
            if (existing == null) {
                manualDetailRefreshes[threadId] = currentRefresh
            }
            existing
        }
        if (racedRefresh != null) {
            DebugTraceLogger.log("detail-refresh", "manual.join thread=$threadId")
            return@withContext racedRefresh.await()
        }

        try {
            val result = performRefreshThreadDetail(threadId)
            currentRefresh.complete(result)
            return@withContext result
        } catch (error: Throwable) {
            currentRefresh.completeExceptionally(error)
            throw error
        } finally {
            synchronized(manualDetailRefreshLock) {
                if (manualDetailRefreshes[threadId] === currentRefresh) {
                    manualDetailRefreshes.remove(threadId)
                }
            }
        }
    }

    private suspend fun performRefreshThreadDetail(threadId: String): DetailRefreshResult {
        enableThreadCachePersistence()
        val cacheGenerationSnapshot = cacheGeneration
        val beforeDetail = state.value.detail?.takeIf { it.thread.threadId == threadId }
        DebugTraceLogger.log(
            "detail-refresh",
            "manual.start thread=$threadId beforeCount=${detailMessageCount(beforeDetail)} " +
                "beforeLast=${detailLastMessageId(beforeDetail) ?: "none"}"
        )
        return runCatching {
            val config = requireNotNull(state.value.config)
            val (threads, detail) = refreshDetailWithBestAvailablePath(
                config = config,
                threadId = threadId
            )
            val latest = mutableState.value
            if (latest.selectedThreadId != threadId ||
                !isCacheGenerationCurrent(cacheGenerationSnapshot)
            ) {
                val skipped = failedDetailRefreshResult(
                    before = latest.detail?.takeIf { it.thread.threadId == threadId } ?: beforeDetail,
                    errorMessage = "detail_refresh_skipped"
                )
                DebugTraceLogger.log(
                    "detail-refresh",
                    "manual.skipped thread=$threadId selected=${latest.selectedThreadId} " +
                        "cacheCurrent=${isCacheGenerationCurrent(cacheGenerationSnapshot)}"
                )
                return@runCatching skipped
            }
            val currentDetail = latest.detail?.takeIf { it.thread.threadId == threadId }
            val visualDetail = mergeDetailMessagesWithBackfill(config, detail, latest.detail)
            val result = detailRefreshResult(
                before = currentDetail,
                after = visualDetail
            )
            socketFreshDetailThreadIds -= threadId
            mutableState.value = latest.copy(
                threads = threads,
                selectedThreadId = threadId,
                detail = visualDetail,
                canLoadOlderMessages = refreshedCanLoadOlderMessages(
                    latest = latest,
                    threadId = threadId,
                    detail = visualDetail
                ),
                isLoadingOlderMessages = latest.isLoadingOlderMessages,
                pendingDetailSyncThreadIds = latest.pendingDetailSyncThreadIds - threadId,
                syncWarningMessage = null,
                errorMessage = null
            )
            cacheThreads(threads)
            cacheDetail(visualDetail)
            maybeDispatchQueuedTextMessage()
            DebugTraceLogger.log(
                "detail-refresh",
                "manual.done thread=$threadId status=${result.status} changed=${result.changed} " +
                    "fetched=${detail.recentMessages.size} merged=${result.messageCount} " +
                    "last=${result.lastMessageId ?: "none"}"
            )
            result
        }.getOrElse { error ->
            val backgroundRefreshError = error.toBackgroundRefreshErrorMessage()
                ?: error.toConnectionErrorMessage()
            mutableState.value = mutableState.value.copy(
                syncWarningMessage = error.toSyncWarningMessage(),
                errorMessage = backgroundRefreshError
            )
            val result = failedDetailRefreshResult(
                before = beforeDetail,
                errorMessage = backgroundRefreshError
            )
            DebugTraceLogger.log(
                "detail-refresh",
                "manual.failed thread=$threadId error=${backgroundRefreshError.orEmpty()} " +
                    "beforeCount=${result.messageCount} beforeLast=${result.lastMessageId ?: "none"}"
            )
            result
        }
    }

    suspend fun fetchThreadDetailForAlert(threadId: String): ThreadDetail? = withContext(dispatcher) {
        runCatching {
            val config = requireNotNull(state.value.config)
            val detail = requireNotNull(
                normalizeDetail(
                    threads = state.value.threads,
                    detail = ThreadDetailSnapshotSource.fetch(api, config, threadId)
                )
            )
            val visualDetail = mergeDetailMessages(detail, cachedThreadDetail(threadId))
            visualDetail
        }.getOrNull()
    }

    suspend fun loadOlderMessages() = withContext(dispatcher) {
        val current = state.value
        val config = current.config ?: return@withContext
        val detail = current.detail ?: return@withContext
        val threadId = current.selectedThreadId ?: return@withContext
        if (detail.thread.threadId != threadId ||
            current.isLoadingOlderMessages ||
            !current.canLoadOlderMessages
        ) {
            return@withContext
        }

        val oldestMessage = detail.recentMessages.firstOrNull() ?: run {
            mutableState.value = current.copy(canLoadOlderMessages = false)
            return@withContext
        }

        mutableState.value = current.copy(
            isLoadingOlderMessages = true,
            errorMessage = null
        )

        runCatching {
            val page = api.getThreadMessages(
                config = config,
                threadId = threadId,
                beforeMessageId = oldestMessage.messageId,
                beforeTimestamp = oldestMessage.timestamp,
                limit = MESSAGE_PAGE_LIMIT
            )
            val latest = mutableState.value
            val latestDetail = latest.detail
            if (latest.selectedThreadId != threadId ||
                latestDetail == null ||
                latestDetail.thread.threadId != threadId
            ) {
                mutableState.value = latest.copy(isLoadingOlderMessages = false)
                return@runCatching
            }

            val mergedDetail = latestDetail.copy(
                recentMessages = mergeMessagesById(
                    existingMessages = page.messages,
                    incomingMessages = latestDetail.recentMessages
                )
            )
            mutableState.value = latest.copy(
                detail = mergedDetail,
                isLoadingOlderMessages = false,
                canLoadOlderMessages = page.nextCursor != null,
                errorMessage = null
            )
            cacheDetail(mergedDetail)
        }.onFailure { error ->
            mutableState.value = mutableState.value.copy(
                isLoadingOlderMessages = false,
                errorMessage = error.toBackgroundRefreshErrorMessage()
                    ?: error.toConnectionErrorMessage()
            )
        }
    }

    suspend fun refreshThreads() = withContext(dispatcher) {
        enableThreadCachePersistence()
        val cacheGenerationSnapshot = cacheGeneration
        val current = state.value
        val config = current.config ?: return@withContext
        mutableState.value = current.copy(
            isRefreshingThreads = true,
            errorMessage = null
        )
        runCatching {
            val threads = api.getThreads(config)
            val latest = state.value
            if (latest.config != config) {
                mutableState.value = latest.copy(isRefreshingThreads = false)
                return@runCatching
            }
            if (!isCacheGenerationCurrent(cacheGenerationSnapshot)) {
                mutableState.value = latest.copy(isRefreshingThreads = false)
                return@runCatching
            }
            val detail = normalizeDetail(
                threads = threads,
                detail = latest.detail
            )
            val visualDetail = detail?.let { mergeDetailMessages(it, latest.detail) }
            val mergedThreads = mergeThreadsWithDetail(threads, detail)
            mutableState.value = latest.copy(
                threads = mergedThreads,
                detail = visualDetail,
                canLoadOlderMessages = visualDetail
                    ?.let { refreshedCanLoadOlderMessages(latest, it.thread.threadId, it) }
                    ?: false,
                isRefreshingThreads = false,
                syncWarningMessage = null,
                errorMessage = null
            )
            cacheThreads(mergedThreads)
            cacheDetail(visualDetail)
            maybeDispatchQueuedTextMessage()
        }.onFailure { error ->
            mutableState.value = mutableState.value.copy(
                isRefreshingThreads = false,
                syncWarningMessage = error.toSyncWarningMessage(),
                errorMessage = error.toConnectionErrorMessage()
            )
        }
    }

    suspend fun refreshAutomations(): List<MobileAutomationItem> = withContext(dispatcher) {
        val current = state.value
        val config = current.config ?: return@withContext current.automations
        if (current.isLoadingAutomations) {
            return@withContext current.automations
        }

        mutableState.value = current.copy(isLoadingAutomations = true, errorMessage = null)
        return@withContext runCatching {
            api.getAutomations(config)
        }.fold(
            onSuccess = { automations ->
                val latest = state.value
                if (latest.config == config) {
                    mutableState.value = latest.copy(
                        automations = automations,
                        isLoadingAutomations = false,
                        errorMessage = null
                    )
                }
                automations
            },
            onFailure = { error ->
                val latest = state.value
                if (latest.config == config) {
                    mutableState.value = latest.copy(
                        isLoadingAutomations = false,
                        errorMessage = error.toBackgroundRefreshErrorMessage()
                    )
                }
                throw error
            }
        )
    }

    fun clearThreadCache() {
        cacheGeneration += 1
        userSelectionGeneration += 1
        socketFreshDetailThreadIds.clear()
        sessionStore.clearThreadCache()
        val latest = mutableState.value
        mutableState.value = latest.copy(
            threads = emptyList(),
            selectedThreadId = null,
            detail = null,
            canLoadOlderMessages = false,
            isLoadingOlderMessages = false,
            pendingDetailSyncThreadIds = emptySet(),
            errorMessage = null
        )
    }

    suspend fun checkLatestApk(force: Boolean = false) = withContext(dispatcher) {
        val current = state.value
        val config = current.config ?: return@withContext current.latestApkInfo
        if (current.isCheckingLatestApk) {
            return@withContext current.latestApkInfo
        }

        val checkedAt = current.latestApkCheckedAtMillis
        val now = nowMillis()
        if (!force &&
            checkedAt != null &&
            now >= checkedAt &&
            now - checkedAt < LATEST_APK_CHECK_INTERVAL_MS
        ) {
            return@withContext current.latestApkInfo
        }

        mutableState.value = current.copy(isCheckingLatestApk = true)
        return@withContext runCatching {
            api.getLatestApkInfo(config)
        }.fold(
            onSuccess = { latestApkInfo ->
                val latest = state.value
                if (latest.config == config) {
                    mutableState.value = latest.copy(
                        latestApkInfo = latestApkInfo,
                        isCheckingLatestApk = false,
                        latestApkCheckedAtMillis = nowMillis()
                    )
                }
                latestApkInfo
            },
            onFailure = {
                val latest = state.value
                if (latest.config == config) {
                    mutableState.value = latest.copy(
                        isCheckingLatestApk = false,
                        latestApkCheckedAtMillis = nowMillis()
                    )
                }
                null
            }
        )
    }

    suspend fun fetchGatewayDiagnosticsJson(): String = withContext(dispatcher) {
        val config = state.value.config ?: throw IOException("Gateway is not configured")
        withTimeout(DIAGNOSTICS_GATEWAY_FETCH_TIMEOUT_MS) {
            api.getDiagnosticsJson(config)
        }
    }

    suspend fun fetchMarkdownFilePreview(threadId: String, path: String): MarkdownFilePreview =
        withContext(dispatcher) {
            val config = state.value.config ?: throw IOException("Gateway is not configured")
            api.getMarkdownFilePreview(config, threadId, path)
        }

    suspend fun testSocketLatency(): SocketLatencyTestResult? = withContext(dispatcher) {
        val current = state.value
        if (current.isTestingSocketLatency) {
            return@withContext null
        }
        val handle = realtimeHandle
        val config = current.config
        val canMeasureSse =
            sessionStore.loadConnectionMode() == GatewayConnectionMode.SSE &&
                config != null &&
                current.connectionState == StreamConnectionState.OPEN
        if ((handle == null || current.connectionState != StreamConnectionState.OPEN) && !canMeasureSse) {
            mutableState.value = current.copy(
                isTestingSocketLatency = false,
                socketLatencyAverageMillis = null,
                socketLatencySampleCount = 0,
                socketLatencyError = "socket_latency_unavailable"
            )
            return@withContext null
        }

        mutableState.value = current.copy(
            isTestingSocketLatency = true,
            socketLatencyAverageMillis = null,
            socketLatencySampleCount = 0,
            socketLatencyError = null
        )

        return@withContext runCatching {
            if (handle != null && current.connectionState == StreamConnectionState.OPEN) {
                handle.measureLatency(
                    sampleCount = SOCKET_LATENCY_SAMPLE_COUNT,
                    timeoutMillis = SOCKET_LATENCY_TIMEOUT_MS
                )
            } else {
                measureSseLatency(requireNotNull(config))
            }
        }.fold(
            onSuccess = { result ->
                val latest = mutableState.value
                mutableState.value = latest.copy(
                    isTestingSocketLatency = false,
                    socketLatencyAverageMillis = result.averageMillis,
                    socketLatencySampleCount = result.samplesMillis.size,
                    socketLatencyError = null
                )
                result
            },
            onFailure = {
                val latest = mutableState.value
                mutableState.value = latest.copy(
                    isTestingSocketLatency = false,
                    socketLatencyAverageMillis = null,
                    socketLatencySampleCount = 0,
                    socketLatencyError = "socket_latency_failed"
                )
                null
            }
        )
    }

    suspend fun queueMessageAfterCurrentTurn(text: String): QueuedTextMessage? = withContext(dispatcher) {
        val current = state.value
        val queuedAt = nowMillis()
        val queueId = "queue-${queuedAt}-${UUID.randomUUID()}"
        when (val plan = QueuedMessageSupport.planQueueText(current, text, queuedAt, queueId)) {
            QueuedTextPlan.Blank -> return@withContext null
            QueuedTextPlan.SendDisabled -> {
                mutableState.value = current.copy(errorMessage = "send_disabled")
                return@withContext null
            }
            is QueuedTextPlan.SendNow -> {
                sendMessage(plan.text)
                return@withContext null
            }
            is QueuedTextPlan.Queue -> {
                val updatedMessages = current.queuedTextMessages + plan.message
                mutableState.value = current.copy(
                    queuedTextMessages = updatedMessages,
                    errorMessage = null
                )
                persistQueuedTextMessages(updatedMessages)
                current.config?.let { config ->
                    scheduleQueuedDispatchRefresh(config, plan.message.threadId)
                }
                return@withContext plan.message
            }
        }
    }

    fun cancelQueuedMessage(queueId: String? = null): QueuedTextMessage? {
        val current = mutableState.value
        val message = current.queuedTextMessages.firstOrNull { queued ->
            (queueId == null || queued.queueId == queueId) &&
                queued.status != QueuedTextMessageStatus.DISPATCHING
        } ?: return null
        val updatedMessages = current.queuedTextMessages.filterNot { it.queueId == message.queueId }
        mutableState.value = current.copy(
            queuedTextMessages = updatedMessages,
            errorMessage = null
        )
        persistQueuedTextMessages(updatedMessages)
        return message
    }

    fun retryQueuedMessage(queueId: String) {
        val current = mutableState.value
        val updated = current.queuedTextMessages.map { queued ->
            if (queued.queueId == queueId && queued.status == QueuedTextMessageStatus.FAILED) {
                queued.copy(
                    status = QueuedTextMessageStatus.PENDING,
                    dispatchStartedAtMillis = null,
                    errorMessage = null
                )
            } else {
                queued
            }
        }
        if (updated != current.queuedTextMessages) {
            mutableState.value = current.copy(queuedTextMessages = updated, errorMessage = null)
            persistQueuedTextMessages(updated)
            maybeDispatchQueuedTextMessage()
            val latest = mutableState.value
            val threadId = latest.selectedThreadId
            val config = latest.config
            if (config != null && threadId != null) {
                scheduleQueuedDispatchRefresh(config, threadId)
            }
        }
    }

    private fun markQueuedMessageDispatching(
        message: QueuedTextMessage,
        blockedThreadUpdatedAt: String?
    ): Boolean {
        val latest = mutableState.value
        val updated = latest.queuedTextMessages.map { queued ->
            if (queued.queueId == message.queueId && queued.status == QueuedTextMessageStatus.PENDING) {
                queued.copy(
                    status = QueuedTextMessageStatus.DISPATCHING,
                    dispatchStartedAtMillis = nowMillis(),
                    errorMessage = null
                )
            } else if (
                queued.threadId == message.threadId &&
                queued.status == QueuedTextMessageStatus.PENDING &&
                blockedThreadUpdatedAt != null
            ) {
                queued.copy(blockedThreadUpdatedAt = blockedThreadUpdatedAt)
            } else {
                queued
            }
        }
        if (updated == latest.queuedTextMessages) {
            return false
        }
        mutableState.value = latest.copy(queuedTextMessages = updated, errorMessage = null)
        persistQueuedTextMessages(updated)
        return true
    }

    private fun removeQueuedMessage(queueId: String) {
        val latest = mutableState.value
        val updatedMessages = latest.queuedTextMessages.filterNot { it.queueId == queueId }
        mutableState.value = latest.copy(
            queuedTextMessages = updatedMessages,
            errorMessage = null
        )
        persistQueuedTextMessages(updatedMessages)
    }

    private fun markQueuedMessageFailed(queueId: String, errorMessage: String) {
        val latest = mutableState.value
        val updatedMessages = latest.queuedTextMessages.map { queued ->
            if (queued.queueId == queueId) {
                queued.copy(
                    status = QueuedTextMessageStatus.FAILED,
                    dispatchStartedAtMillis = null,
                    errorMessage = errorMessage
                )
            } else {
                queued
            }
        }
        mutableState.value = latest.copy(
            queuedTextMessages = updatedMessages,
            errorMessage = errorMessage
        )
        persistQueuedTextMessages(updatedMessages)
    }

    private fun maybeDispatchQueuedTextMessage() {
        val latest = mutableState.value
        val queuedMessage = QueuedMessageSupport.dispatchableQueuedText(latest) ?: return
        if (!markQueuedMessageDispatching(queuedMessage, latest.detail?.thread?.updatedAt)) {
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sendMessage(
                text = queuedMessage.text,
                queue = true,
                queuedMessageId = queuedMessage.queueId
            )
        }
    }

    private fun resumeQueuedDispatch(config: GatewayConfig) {
        val threadId = mutableState.value.selectedThreadId ?: return
        maybeDispatchQueuedTextMessage()
        scheduleQueuedDispatchRefresh(config, threadId)
    }

    suspend fun sendMessage(
        text: String,
        guide: Boolean = false,
        queue: Boolean = false,
        queuedMessageId: String? = null
    ): Boolean = withContext(dispatcher) {
        enableThreadCachePersistence()
        val initial = state.value
        val config = initial.config
        val threadId = initial.selectedThreadId
        if (config == null || threadId == null) {
            mutableState.value = initial.copy(errorMessage = "send_disabled")
            return@withContext false
        }

        val currentDetail = initial.detail
        if (currentDetail == null || currentDetail.thread.threadId != threadId) {
            mutableState.value = initial.copy(errorMessage = "send_disabled")
            return@withContext false
        }

        val clientMessageId = "client-${UUID.randomUUID()}"
        val pendingDetail = optimisticTextDetail(
            detail = currentDetail,
            text = text,
            summary = "消息正在发送到桌面",
            messageId = clientMessageId,
            sendState = ThreadMessageSendState.SENDING,
            markThreadRunning = false
        )
        val pendingThreads = mergeThreadsWithDetail(initial.threads, pendingDetail)
        mutableState.value = initial.copy(
            threads = pendingThreads,
            detail = pendingDetail,
            isSending = false,
            errorMessage = null
        )
        cacheThreads(pendingThreads)
        cacheDetail(pendingDetail)

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sendPendingTextMessage(
                config = config,
                threadId = threadId,
                text = text,
                clientMessageId = clientMessageId,
                guide = guide,
                queue = queue,
                queuedMessageId = queuedMessageId
            )
        }
        true
    }

    suspend fun retryFailedMessage(
        messageId: String,
        openFileStream: ((PendingFileDraft) -> InputStream)? = null,
        openStream: ((PendingImageDraft) -> InputStream)? = null
    ): Boolean = withContext(dispatcher) {
        enableThreadCachePersistence()
        val initial = state.value
        val config = initial.config
        val threadId = initial.selectedThreadId
        val detail = initial.detail
        if (config == null || threadId == null || detail == null || detail.thread.threadId != threadId) {
            mutableState.value = initial.copy(errorMessage = "send_disabled")
            return@withContext false
        }

        val failedMessage = detail.recentMessages.firstOrNull { message ->
            message.messageId == messageId &&
                message.role == ThreadMessageRole.USER &&
                message.sendState == ThreadMessageSendState.FAILED
        } ?: run {
            mutableState.value = initial.copy(errorMessage = "send_retry_unavailable")
            return@withContext false
        }

        when (failedMessage.kind) {
            "text" -> {
                val text = failedMessage.text?.takeIf { it.isNotBlank() } ?: run {
                    mutableState.value = initial.copy(errorMessage = "send_retry_unavailable")
                    return@withContext false
                }
                val retryingDetail = optimisticTextDetail(
                    detail = detail,
                    text = text,
                    summary = "消息正在重新发送到桌面",
                    messageId = messageId,
                    sendState = ThreadMessageSendState.SENDING,
                    markThreadRunning = false
                )
                val retryingThreads = mergeThreadsWithDetail(initial.threads, retryingDetail)
                mutableState.value = initial.copy(
                    threads = retryingThreads,
                    detail = retryingDetail,
                    isSending = false,
                    errorMessage = null
                )
                cacheThreads(retryingThreads)
                cacheDetail(retryingDetail)

                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    sendPendingTextMessage(
                        config = config,
                        threadId = threadId,
                        text = text,
                        clientMessageId = messageId
                    )
                }
                true
            }
            "image" -> {
                val retryOpenStream = openStream ?: run {
                    mutableState.value = initial.copy(errorMessage = "send_retry_unavailable")
                    return@withContext false
                }
                val previewUri = failedMessage.imageUrl
                    ?: failedMessage.thumbnailUrl
                    ?: run {
                        mutableState.value = initial.copy(errorMessage = "send_retry_unavailable")
                        return@withContext false
                    }
                val draft = PendingImageDraft(
                    previewUri = previewUri,
                    fileName = failedMessage.fileName ?: "image",
                    mimeType = failedMessage.mimeType ?: "application/octet-stream"
                )
                val retryingDetail = optimisticImageDetail(
                    detail = detail,
                    drafts = listOf(draft),
                    text = failedMessage.text.orEmpty(),
                    summary = "图片正在重新发送到桌面",
                    clientMessageId = messageId,
                    sendState = ThreadMessageSendState.SENDING,
                    markThreadRunning = false
                )
                val retryingThreads = mergeThreadsWithDetail(initial.threads, retryingDetail)
                mutableState.value = initial.copy(
                    threads = retryingThreads,
                    detail = retryingDetail,
                    isSending = false,
                    errorMessage = null
                )
                cacheThreads(retryingThreads)
                cacheDetail(retryingDetail)

                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    sendPendingImageMessage(
                        config = config,
                        threadId = threadId,
                        text = failedMessage.text.orEmpty(),
                        clientMessageId = messageId,
                        drafts = listOf(draft),
                        openStream = retryOpenStream
                    )
                }
                true
            }
            "file" -> {
                val retryOpenStream = openFileStream ?: run {
                    mutableState.value = initial.copy(errorMessage = "send_retry_unavailable")
                    return@withContext false
                }
                val previewUri = failedMessage.fileUrl ?: run {
                    mutableState.value = initial.copy(errorMessage = "send_retry_unavailable")
                    return@withContext false
                }
                val draft = PendingFileDraft(
                    previewUri = previewUri,
                    fileName = failedMessage.fileName ?: "file",
                    mimeType = failedMessage.mimeType ?: "application/octet-stream"
                )
                val retryingDetail = optimisticFileDetail(
                    detail = detail,
                    drafts = listOf(draft),
                    text = failedMessage.text.orEmpty(),
                    summary = "文件正在重新发送到桌面",
                    clientMessageId = messageId,
                    sendState = ThreadMessageSendState.SENDING,
                    markThreadRunning = false
                )
                val retryingThreads = mergeThreadsWithDetail(initial.threads, retryingDetail)
                mutableState.value = initial.copy(
                    threads = retryingThreads,
                    detail = retryingDetail,
                    isSending = false,
                    errorMessage = null
                )
                cacheThreads(retryingThreads)
                cacheDetail(retryingDetail)

                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    sendPendingFileMessage(
                        config = config,
                        threadId = threadId,
                        text = failedMessage.text.orEmpty(),
                        clientMessageId = messageId,
                        drafts = listOf(draft),
                        openStream = retryOpenStream
                    )
                }
                true
            }
            else -> {
                mutableState.value = initial.copy(errorMessage = "send_retry_unavailable")
                false
            }
        }
    }

    private suspend fun sendPendingTextMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String,
        guide: Boolean = false,
        queue: Boolean = false,
        queuedMessageId: String? = null
    ) {
        DebugTraceLogger.log(
            "send-text",
            "sendPendingTextMessage thread=$threadId client=$clientMessageId guide=$guide queue=$queue " +
                "text=${text.take(80).replace('\n', ' ')}"
        )
        schedulePendingTextSendWatchdog(config, threadId, clientMessageId)
        runCatching {
            if (guide) {
                api.sendGuideMessage(
                    config = config,
                    threadId = threadId,
                    text = text,
                    clientMessageId = clientMessageId
                )
            } else if (queue) {
                api.sendQueuedMessage(
                    config = config,
                    threadId = threadId,
                    text = text,
                    clientMessageId = clientMessageId
                )
            } else {
                api.sendMessage(
                    config = config,
                    threadId = threadId,
                    text = text,
                    clientMessageId = clientMessageId
                )
            }
        }.fold(
            onSuccess = { sendResponse ->
                DebugTraceLogger.log(
                    "send-text",
                    "sendSuccess thread=$threadId client=$clientMessageId warning=${sendResponse.warning ?: "none"} " +
                        "confirmation=${sendResponse.confirmation}"
                )
                val latest = mutableState.value
                if (latest.config != config) {
                    return@fold
                }
                if (queuedMessageId != null) {
                    removeQueuedMessage(queuedMessageId)
                }
                val latestAfterQueue = mutableState.value
                if (queuedMessageId != null) {
                    scheduleQueuedDispatchRefresh(config, threadId)
                }
                val currentDetail = latestAfterQueue.detail
                    ?.takeIf { it.thread.threadId == threadId }
                if (currentDetail == null) {
                    mutableState.value = latestAfterQueue.copy(
                        isSending = false,
                        syncWarningMessage = null,
                        errorMessage = displayableSendWarning(sendResponse.warning)
                    )
                    schedulePostSendDetailRefresh(config, threadId)
                    return@fold
                }
                val optimisticDetail = optimisticTextDetail(
                    detail = currentDetail,
                    text = text,
                    summary = sendSummary(sendResponse.warning),
                    messageId = clientMessageId,
                    sendState = null,
                    markThreadRunning = true
                )
                val optimisticThreads = mergeThreadsWithDetail(latestAfterQueue.threads, optimisticDetail)
                mutableState.value = latestAfterQueue.copy(
                    threads = optimisticThreads,
                    detail = optimisticDetail,
                    isSending = false,
                    syncWarningMessage = null,
                    errorMessage = displayableSendWarning(sendResponse.warning)
                )
                cacheThreads(optimisticThreads)
                cacheDetail(optimisticDetail)
                schedulePostSendDetailRefresh(config, threadId)
            },
            onFailure = { error ->
                DebugTraceLogger.log(
                    "send-text",
                    "sendFailure thread=$threadId client=$clientMessageId error=${error.message ?: error::class.java.simpleName}"
                )
                val latest = mutableState.value
                if (latest.config != config) {
                    return@fold
                }
                val displayError = error.message ?: "send_failed"
                if (queuedMessageId != null) {
                    markQueuedMessageFailed(queuedMessageId, displayError)
                }
                val latestAfterQueue = mutableState.value
                val currentDetail = latestAfterQueue.detail
                    ?.takeIf { it.thread.threadId == threadId }
                if (currentDetail == null) {
                    mutableState.value = latestAfterQueue.copy(
                        isSending = false,
                        syncWarningMessage = error.toSyncWarningMessage(),
                        errorMessage = displayError
                    )
                    return@fold
                }
                val failedDetail = failedTextDetail(
                    detail = currentDetail,
                    messageId = clientMessageId,
                    reason = "发送失败，点红色感叹号重发"
                )
                val failedThreads = mergeThreadsWithDetail(latestAfterQueue.threads, failedDetail)
                mutableState.value = latestAfterQueue.copy(
                    threads = failedThreads,
                    detail = failedDetail,
                    isSending = false,
                    syncWarningMessage = error.toSyncWarningMessage(),
                    errorMessage = displayError
                )
                cacheThreads(failedThreads)
                cacheDetail(failedDetail)
            }
        )
    }

    fun setPendingImage(draft: PendingImageDraft) {
        setPendingImages(listOf(draft))
    }

    fun setPendingImages(drafts: List<PendingImageDraft>) {
        mutableState.value = mutableState.value.copy(
            pendingImageDrafts = drafts,
            errorMessage = null
        )
    }

    fun addPendingImages(drafts: List<PendingImageDraft>) {
        if (drafts.isEmpty()) {
            return
        }
        val current = mutableState.value
        mutableState.value = current.copy(
            pendingImageDrafts = current.pendingImageDrafts + drafts,
            errorMessage = null
        )
    }

    fun clearPendingImage() {
        mutableState.value = mutableState.value.copy(pendingImageDrafts = emptyList())
    }

    fun removePendingImage(draft: PendingImageDraft) {
        val current = mutableState.value
        mutableState.value = current.copy(
            pendingImageDrafts = current.pendingImageDrafts.filterNot { it == draft }
        )
    }

    fun setPendingFiles(drafts: List<PendingFileDraft>) {
        mutableState.value = mutableState.value.copy(
            pendingFileDrafts = drafts,
            errorMessage = null
        )
    }

    fun addPendingFiles(drafts: List<PendingFileDraft>) {
        if (drafts.isEmpty()) {
            return
        }
        val current = mutableState.value
        mutableState.value = current.copy(
            pendingFileDrafts = current.pendingFileDrafts + drafts,
            errorMessage = null
        )
    }

    fun clearPendingFile() {
        mutableState.value = mutableState.value.copy(pendingFileDrafts = emptyList())
    }

    fun removePendingFile(draft: PendingFileDraft) {
        val current = mutableState.value
        mutableState.value = current.copy(
            pendingFileDrafts = current.pendingFileDrafts.filterNot { it == draft }
        )
    }

    suspend fun sendAttachmentMessage(
        text: String,
        openImageStream: (PendingImageDraft) -> InputStream,
        openFileStream: (PendingFileDraft) -> InputStream
    ): Boolean {
        val current = state.value
        return when {
            current.pendingImageDrafts.isNotEmpty() && current.pendingFileDrafts.isNotEmpty() -> {
                sendMixedAttachmentMessage(text, openImageStream, openFileStream)
            }
            current.pendingImageDrafts.isNotEmpty() -> sendImageMessage(text, openImageStream)
            current.pendingFileDrafts.isNotEmpty() -> sendFileMessage(text, openFileStream)
            else -> withContext(dispatcher) {
                mutableState.value = current.copy(errorMessage = "attachment_missing")
                false
            }
        }
    }

    private suspend fun sendMixedAttachmentMessage(
        text: String,
        openImageStream: (PendingImageDraft) -> InputStream,
        openFileStream: (PendingFileDraft) -> InputStream
    ): Boolean = withContext(dispatcher) {
        enableThreadCachePersistence()
        val initial = state.value
        val imageDrafts = initial.pendingImageDrafts
        val fileDrafts = initial.pendingFileDrafts
        if (imageDrafts.isEmpty() || fileDrafts.isEmpty()) {
            mutableState.value = initial.copy(errorMessage = "attachment_missing")
            return@withContext false
        }

        val config = initial.config
        val threadId = initial.selectedThreadId
        if (config == null || threadId == null) {
            mutableState.value = initial.copy(errorMessage = "send_disabled")
            return@withContext false
        }

        val currentDetail = initial.detail
        if (currentDetail == null || currentDetail.thread.threadId != threadId) {
            mutableState.value = initial.copy(errorMessage = "send_disabled")
            return@withContext false
        }

        val clientMessageId = "client-${UUID.randomUUID()}"
        val pendingDetail = optimisticAttachmentDetail(
            detail = currentDetail,
            imageDrafts = imageDrafts,
            fileDrafts = fileDrafts,
            text = text,
            summary = "附件正在发送到桌面",
            clientMessageId = clientMessageId,
            sendState = ThreadMessageSendState.SENDING,
            markThreadRunning = false
        )
        val pendingThreads = mergeThreadsWithDetail(initial.threads, pendingDetail)
        mutableState.value = initial.copy(
            threads = pendingThreads,
            detail = pendingDetail,
            isSending = false,
            pendingImageDrafts = emptyList(),
            pendingFileDrafts = emptyList(),
            errorMessage = null
        )
        cacheThreads(pendingThreads)
        cacheDetail(pendingDetail)

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sendPendingAttachmentMessage(
                config = config,
                threadId = threadId,
                text = text,
                clientMessageId = clientMessageId,
                imageDrafts = imageDrafts,
                fileDrafts = fileDrafts,
                openImageStream = openImageStream,
                openFileStream = openFileStream
            )
        }
        true
    }

    private suspend fun sendPendingAttachmentMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String,
        imageDrafts: List<PendingImageDraft>,
        fileDrafts: List<PendingFileDraft>,
        openImageStream: (PendingImageDraft) -> InputStream,
        openFileStream: (PendingFileDraft) -> InputStream
    ) {
        runCatching {
            api.sendAttachmentMessages(
                config = config,
                threadId = threadId,
                text = text.takeIf { it.isNotBlank() },
                clientMessageId = clientMessageId,
                images = imageDrafts.map { draft ->
                    ImageUploadSource(
                        fileName = draft.fileName,
                        mimeType = draft.mimeType,
                        previewUri = draft.previewUri,
                        openStream = { openImageStream(draft) }
                    )
                },
                files = fileDrafts.map { draft ->
                    FileUploadSource(
                        fileName = draft.fileName,
                        mimeType = draft.mimeType,
                        previewUri = draft.previewUri,
                        openStream = { openFileStream(draft) }
                    )
                }
            )
        }.fold(
            onSuccess = { sendResponse ->
                val latest = mutableState.value
                if (latest.config != config) {
                    return@fold
                }
                val currentDetail = latest.detail
                    ?.takeIf { it.thread.threadId == threadId }
                if (currentDetail == null) {
                    mutableState.value = latest.copy(
                        isSending = false,
                        errorMessage = displayableSendWarning(sendResponse.warning)
                    )
                    schedulePostSendDetailRefresh(config, threadId)
                    return@fold
                }
                val warning = sendResponse.warning
                val optimisticDetail = optimisticAttachmentDetail(
                    detail = currentDetail,
                    imageDrafts = imageDrafts,
                    fileDrafts = fileDrafts,
                    text = text,
                    summary = sendAttachmentSummary(warning),
                    clientMessageId = clientMessageId,
                    sendState = null,
                    markThreadRunning = true
                )
                val optimisticThreads = mergeThreadsWithDetail(latest.threads, optimisticDetail)
                mutableState.value = latest.copy(
                    threads = optimisticThreads,
                    detail = optimisticDetail,
                    isSending = false,
                    pendingImageDrafts = emptyList(),
                    pendingFileDrafts = emptyList(),
                    errorMessage = displayableSendWarning(warning)
                )
                cacheThreads(optimisticThreads)
                cacheDetail(optimisticDetail)
                schedulePostSendDetailRefresh(config, threadId)
            },
            onFailure = { error ->
                val latest = mutableState.value
                if (latest.config != config) {
                    return@fold
                }
                val currentDetail = latest.detail
                    ?.takeIf { it.thread.threadId == threadId }
                if (currentDetail == null) {
                    mutableState.value = latest.copy(
                        isSending = false,
                        errorMessage = error.message ?: "send_failed"
                    )
                    return@fold
                }
                val failedDetail = failedAttachmentDetail(
                    detail = currentDetail,
                    clientMessageId = clientMessageId,
                    imageDrafts = imageDrafts,
                    fileDrafts = fileDrafts,
                    reason = "附件发送失败，请重新选择后发送"
                )
                val failedThreads = mergeThreadsWithDetail(latest.threads, failedDetail)
                mutableState.value = latest.copy(
                    threads = failedThreads,
                    detail = failedDetail,
                    isSending = false,
                    pendingImageDrafts = emptyList(),
                    pendingFileDrafts = emptyList(),
                    errorMessage = error.message ?: "send_failed"
                )
                cacheThreads(failedThreads)
                cacheDetail(failedDetail)
            }
        )
    }

    suspend fun sendImageMessage(
        text: String,
        openStream: (PendingImageDraft) -> InputStream
    ): Boolean = withContext(dispatcher) {
        enableThreadCachePersistence()
        val initial = state.value
        val drafts = initial.pendingImageDrafts
        if (drafts.isEmpty()) {
            mutableState.value = initial.copy(errorMessage = "image_missing")
            return@withContext false
        }

        val config = initial.config
        val threadId = initial.selectedThreadId
        if (config == null || threadId == null) {
            mutableState.value = initial.copy(errorMessage = "send_disabled")
            return@withContext false
        }

        val currentDetail = initial.detail
        if (currentDetail == null || currentDetail.thread.threadId != threadId) {
            mutableState.value = initial.copy(errorMessage = "send_disabled")
            return@withContext false
        }

        val clientMessageId = "client-${UUID.randomUUID()}"
        val pendingDetail = optimisticImageDetail(
            detail = currentDetail,
            drafts = drafts,
            text = text,
            summary = "图片正在发送到桌面",
            clientMessageId = clientMessageId,
            sendState = ThreadMessageSendState.SENDING,
            markThreadRunning = false
        )
        val pendingThreads = mergeThreadsWithDetail(initial.threads, pendingDetail)
        mutableState.value = initial.copy(
            threads = pendingThreads,
            detail = pendingDetail,
            isSending = false,
            pendingImageDrafts = emptyList(),
            pendingFileDrafts = emptyList(),
            errorMessage = null
        )
        cacheThreads(pendingThreads)
        cacheDetail(pendingDetail)

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sendPendingImageMessage(
                config = config,
                threadId = threadId,
                text = text,
                clientMessageId = clientMessageId,
                drafts = drafts,
                openStream = openStream
            )
        }
        true
    }

    private suspend fun sendPendingImageMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String,
        drafts: List<PendingImageDraft>,
        openStream: (PendingImageDraft) -> InputStream
    ) {
        runCatching {
            api.sendImageMessages(
                config = config,
                threadId = threadId,
                text = text.takeIf { it.isNotBlank() },
                clientMessageId = clientMessageId,
                images = drafts.map { draft ->
                    ImageUploadSource(
                        fileName = draft.fileName,
                        mimeType = draft.mimeType,
                        previewUri = draft.previewUri,
                        openStream = { openStream(draft) }
                    )
                }
            )
        }.fold(
            onSuccess = { sendResponse ->
                val latest = mutableState.value
                if (latest.config != config) {
                    return@fold
                }
                val currentDetail = latest.detail
                    ?.takeIf { it.thread.threadId == threadId }
                if (currentDetail == null) {
                    mutableState.value = latest.copy(
                        isSending = false,
                        errorMessage = displayableSendWarning(sendResponse.warning)
                    )
                    schedulePostSendDetailRefresh(config, threadId)
                    return@fold
                }
                val warning = sendResponse.warning
                val optimisticDetail = optimisticImageDetail(
                    detail = currentDetail,
                    drafts = drafts,
                    text = text,
                    summary = sendImageSummary(warning),
                    clientMessageId = clientMessageId,
                    sendState = null,
                    markThreadRunning = true
                )
                val optimisticThreads = mergeThreadsWithDetail(latest.threads, optimisticDetail)
                mutableState.value = latest.copy(
                    threads = optimisticThreads,
                    detail = optimisticDetail,
                    isSending = false,
                    pendingImageDrafts = emptyList(),
                    pendingFileDrafts = emptyList(),
                    errorMessage = displayableSendWarning(warning)
                )
                cacheThreads(optimisticThreads)
                cacheDetail(optimisticDetail)
                schedulePostSendDetailRefresh(config, threadId)
            },
            onFailure = { error ->
                val latest = mutableState.value
                if (latest.config != config) {
                    return@fold
                }
                val currentDetail = latest.detail
                    ?.takeIf { it.thread.threadId == threadId }
                if (currentDetail == null) {
                    mutableState.value = latest.copy(
                        isSending = false,
                        errorMessage = error.message ?: "send_failed"
                    )
                    return@fold
                }
                val failedDetail = failedImageDetail(
                    detail = currentDetail,
                    clientMessageId = clientMessageId,
                    drafts = drafts,
                    reason = "图片发送失败，点红色感叹号重发"
                )
                val failedThreads = mergeThreadsWithDetail(latest.threads, failedDetail)
                mutableState.value = latest.copy(
                    threads = failedThreads,
                    detail = failedDetail,
                    isSending = false,
                    pendingImageDrafts = emptyList(),
                    pendingFileDrafts = emptyList(),
                    errorMessage = error.message ?: "send_failed"
                )
                cacheThreads(failedThreads)
                cacheDetail(failedDetail)
            }
        )
    }

    suspend fun sendFileMessage(
        text: String,
        openStream: (PendingFileDraft) -> InputStream
    ): Boolean = withContext(dispatcher) {
        enableThreadCachePersistence()
        val initial = state.value
        val drafts = initial.pendingFileDrafts
        if (drafts.isEmpty()) {
            mutableState.value = initial.copy(errorMessage = "file_missing")
            return@withContext false
        }

        val config = initial.config
        val threadId = initial.selectedThreadId
        if (config == null || threadId == null) {
            mutableState.value = initial.copy(errorMessage = "send_disabled")
            return@withContext false
        }

        val currentDetail = initial.detail
        if (currentDetail == null || currentDetail.thread.threadId != threadId) {
            mutableState.value = initial.copy(errorMessage = "send_disabled")
            return@withContext false
        }

        val clientMessageId = "client-${UUID.randomUUID()}"
        val pendingDetail = optimisticFileDetail(
            detail = currentDetail,
            drafts = drafts,
            text = text,
            summary = "文件正在发送到桌面",
            clientMessageId = clientMessageId,
            sendState = ThreadMessageSendState.SENDING,
            markThreadRunning = false
        )
        val pendingThreads = mergeThreadsWithDetail(initial.threads, pendingDetail)
        mutableState.value = initial.copy(
            threads = pendingThreads,
            detail = pendingDetail,
            isSending = false,
            pendingImageDrafts = emptyList(),
            pendingFileDrafts = emptyList(),
            errorMessage = null
        )
        cacheThreads(pendingThreads)
        cacheDetail(pendingDetail)

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sendPendingFileMessage(
                config = config,
                threadId = threadId,
                text = text,
                clientMessageId = clientMessageId,
                drafts = drafts,
                openStream = openStream
            )
        }
        true
    }

    private suspend fun sendPendingFileMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String,
        drafts: List<PendingFileDraft>,
        openStream: (PendingFileDraft) -> InputStream
    ) {
        runCatching {
            api.sendFileMessages(
                config = config,
                threadId = threadId,
                text = text.takeIf { it.isNotBlank() },
                clientMessageId = clientMessageId,
                files = drafts.map { draft ->
                    FileUploadSource(
                        fileName = draft.fileName,
                        mimeType = draft.mimeType,
                        previewUri = draft.previewUri,
                        openStream = { openStream(draft) }
                    )
                }
            )
        }.fold(
            onSuccess = { sendResponse ->
                val latest = mutableState.value
                if (latest.config != config) {
                    return@fold
                }
                val currentDetail = latest.detail
                    ?.takeIf { it.thread.threadId == threadId }
                if (currentDetail == null) {
                    mutableState.value = latest.copy(
                        isSending = false,
                        errorMessage = displayableSendWarning(sendResponse.warning)
                    )
                    schedulePostSendDetailRefresh(config, threadId)
                    return@fold
                }
                val warning = sendResponse.warning
                val optimisticDetail = optimisticFileDetail(
                    detail = currentDetail,
                    drafts = drafts,
                    text = text,
                    summary = sendFileSummary(warning),
                    clientMessageId = clientMessageId,
                    sendState = null,
                    markThreadRunning = true
                )
                val optimisticThreads = mergeThreadsWithDetail(latest.threads, optimisticDetail)
                mutableState.value = latest.copy(
                    threads = optimisticThreads,
                    detail = optimisticDetail,
                    isSending = false,
                    pendingImageDrafts = emptyList(),
                    pendingFileDrafts = emptyList(),
                    errorMessage = displayableSendWarning(warning)
                )
                cacheThreads(optimisticThreads)
                cacheDetail(optimisticDetail)
                schedulePostSendDetailRefresh(config, threadId)
            },
            onFailure = { error ->
                val latest = mutableState.value
                if (latest.config != config) {
                    return@fold
                }
                val currentDetail = latest.detail
                    ?.takeIf { it.thread.threadId == threadId }
                if (currentDetail == null) {
                    mutableState.value = latest.copy(
                        isSending = false,
                        errorMessage = error.message ?: "send_failed"
                    )
                    return@fold
                }
                val failedDetail = failedFileDetail(
                    detail = currentDetail,
                    clientMessageId = clientMessageId,
                    drafts = drafts,
                    reason = "文件发送失败，请重新选择文件后发送"
                )
                val failedThreads = mergeThreadsWithDetail(latest.threads, failedDetail)
                mutableState.value = latest.copy(
                    threads = failedThreads,
                    detail = failedDetail,
                    isSending = false,
                    pendingImageDrafts = emptyList(),
                    pendingFileDrafts = emptyList(),
                    errorMessage = error.message ?: "send_failed"
                )
                cacheThreads(failedThreads)
                cacheDetail(failedDetail)
            }
        )
    }

    private suspend fun measureSseLatency(config: GatewayConfig): SocketLatencyTestResult {
        val probes = List(SOCKET_LATENCY_SAMPLE_COUNT) {
            val probeId = UUID.randomUUID().toString()
            val sentAt = nowMillis()
            val pending = PendingSseLatencyProbe(
                sentAt = sentAt,
                result = CompletableDeferred()
            )
            pendingSseLatencyProbes[probeId] = pending
            Triple(probeId, sentAt, pending)
        }

        return try {
            probes.forEach { (probeId, sentAt, _) ->
                api.sendRealtimeLatencyProbe(config, probeId, sentAt)
            }
            val samples = probes.map { (_, _, pending) ->
                withTimeout(SOCKET_LATENCY_TIMEOUT_MS) {
                    pending.result.await()
                }
            }
            SocketLatencyTestResult(
                averageMillis = samples.sum() / samples.size,
                samplesMillis = samples
            )
        } finally {
            probes.forEach { (probeId, _, _) ->
                pendingSseLatencyProbes.remove(probeId)
            }
        }
    }

    private suspend fun handleStreamEvent(event: GatewayStreamEvent) {
        if (event.type == "latency_probe_result") {
            val probeId = event.probeId ?: return
            val pending = pendingSseLatencyProbes[probeId] ?: return
            pending.result.complete(nowMillis() - pending.sentAt)
            return
        }

        val changedThreadId = event.threadId
        val current = state.value
        val config = current.config ?: return

        runCatching {
            val selectedChanged = changedThreadId != null && changedThreadId == current.selectedThreadId
            val selectedChangedThreadId = changedThreadId
            val refreshResult = if (selectedChanged) {
                requireNotNull(selectedChangedThreadId)
                refreshDetailWithBestAvailablePath(
                    config = config,
                    threadId = selectedChangedThreadId
                )
            } else {
                val threads = api.getThreads(config)
                val detail = normalizeDetail(threads, current.detail)
                mergeThreadsWithDetail(threads, detail) to detail
            }
            val latest = state.value
            if (latest.config != config) {
                return@runCatching
            }
            val threads = refreshResult.first
            val detail = if (latest.selectedThreadId == current.selectedThreadId) {
                refreshResult.second
            } else {
                normalizeDetail(threads, latest.detail)
            }
            val visualDetail = detail?.let { mergeDetailMessages(it, latest.detail) }
            val mergedThreads = mergeThreadsWithDetail(threads, detail)
            cacheThreads(mergedThreads)
            cacheDetail(visualDetail)

            mutableState.value = latest.copy(
                threads = mergedThreads,
                detail = visualDetail,
                canLoadOlderMessages = visualDetail
                    ?.let { refreshedCanLoadOlderMessages(latest, it.thread.threadId, it) }
                    ?: false,
                errorMessage = null
            )
            maybeDispatchQueuedTextMessage()
        }.onFailure { error ->
            val backgroundRefreshError = error.toBackgroundRefreshErrorMessage() ?: return@onFailure
            mutableState.value = mutableState.value.copy(
                errorMessage = backgroundRefreshError
            )
        }
    }

    private suspend fun handleRealtimeEvent(event: GatewayRealtimeEvent) {
        when (event) {
            is GatewayRealtimeEvent.Hello -> Unit
            is GatewayRealtimeEvent.ThreadStatusChanged -> handleRealtimeStatusChanged(event)
            is GatewayRealtimeEvent.MessagesAppended -> handleRealtimeMessagesAppended(event)
            is GatewayRealtimeEvent.Notification -> handleRealtimeNotification(event)
            is GatewayRealtimeEvent.LatencyProbeResult -> Unit
        }
    }

    private fun rememberProcessedRealtimeEvent(eventId: String) {
        if (mutableState.value.pendingRealtimeNotifications.isEmpty()) {
            sessionStore.saveLastRealtimeEventId(eventId)
        } else {
            deferredRealtimeEventIdAfterPendingNotification = eventId
        }
    }

    private fun handleRealtimeStatusChanged(event: GatewayRealtimeEvent.ThreadStatusChanged) {
        rememberProcessedRealtimeEvent(event.eventId)
        val latest = mutableState.value
        val runningStartedAt = realtimeRunningStartedAt(event)
        val updatedThreads = latest.threads.map { thread ->
            if (thread.threadId == event.threadId) {
                thread.copy(
                    status = event.status,
                    updatedAt = event.timestamp,
                    progressSummary = event.progressSummary,
                    needsAttention = event.needsAttention,
                    runningStartedAt = runningStartedAt
                )
            } else {
                thread
            }
        }
        val updatedDetail = latest.detail?.let { detail ->
            if (detail.thread.threadId == event.threadId) {
                detail.copy(
                    thread = detail.thread.copy(
                        status = event.status,
                        updatedAt = event.timestamp,
                        progressSummary = event.progressSummary,
                        needsAttention = event.needsAttention,
                        runningStartedAt = runningStartedAt
                    ),
                    sendAvailable = event.status != MobileThreadStatus.RUNNING &&
                        event.status != MobileThreadStatus.OFFLINE,
                    sendDisabledReason = sendDisabledReasonForStatus(event.status)
                )
            } else {
                detail
            }
        }?.let { detail ->
            val failedClientMessageId =
                ThreadMessageMergeSupport.desktopSendFailureClientMessageId(event.eventId)
            if (failedClientMessageId == null || detail.thread.threadId != event.threadId) {
                detail
            } else {
                ThreadMessageMergeSupport.markBackgroundSendFailures(
                    detail = detail,
                    clientMessageIds = setOf(failedClientMessageId)
                )
            }
        }
        mutableState.value = latest.copy(
            threads = updatedThreads,
            detail = updatedDetail,
            pendingDetailSyncThreadIds = pendingDetailSyncAfterUncertainEvent(latest, event.threadId)
        )
        cacheThreads(updatedThreads)
        cacheDetail(updatedDetail)
        maybeDispatchQueuedTextMessage()
    }

    private fun realtimeRunningStartedAt(event: GatewayRealtimeEvent.ThreadStatusChanged): String? {
        return if (event.status == MobileThreadStatus.RUNNING) {
            event.runningStartedAt ?: event.timestamp
        } else {
            null
        }
    }

    private fun handleRealtimeMessagesAppended(event: GatewayRealtimeEvent.MessagesAppended) {
        rememberProcessedRealtimeEvent(event.eventId)
        val latest = mutableState.value
        val currentDetail = latest.detail?.takeIf { it.thread.threadId == event.threadId }
        val sourceDetail = currentDetail
            ?: cachedThreadDetail(event.threadId)
            ?: threadShellDetail(event.threadId)
            ?: return
        DebugTraceLogger.log(
            "realtime-message",
            "messagesAppended event=${event.eventId} thread=${event.threadId} count=${event.messages.size} " +
                "ids=${event.messages.joinToString(",") { it.messageId }}"
        )
        val mergedDetail = sourceDetail.copy(
            recentMessages = mergeMessagesById(
                existingMessages = sourceDetail.recentMessages,
                incomingMessages = event.messages
            )
        )
        val updatedThreads = mergeThreadsWithDetail(latest.threads, mergedDetail)
        DebugTraceLogger.log(
            "realtime-message",
            "messagesAppendedResult thread=${event.threadId} size=${mergedDetail.recentMessages.size} " +
                "ids=${mergedDetail.recentMessages.joinToString(",") { it.messageId.takeLast(18) }}"
        )
        mutableState.value = latest.copy(
            threads = updatedThreads,
            detail = if (currentDetail != null) {
                mergedDetail
            } else {
                latest.detail
            },
            pendingDetailSyncThreadIds = pendingDetailSyncAfterFreshMessages(latest, event.threadId),
            syncWarningMessage = null
        )
        cacheThreads(updatedThreads)
        cacheDetail(mergedDetail)
    }

    private fun handleRealtimeNotification(event: GatewayRealtimeEvent.Notification) {
        val knownNotificationIds = sessionStore.loadKnownNotificationIds()
        if (event.notificationId in knownNotificationIds) {
            rememberProcessedRealtimeEvent(event.eventId)
            return
        }

        val latest = mutableState.value
        if (latest.pendingRealtimeNotifications.any { it.notificationId == event.notificationId }) {
            mutableState.value = latest.copy(
                pendingDetailSyncThreadIds = pendingDetailSyncAfterUncertainEvent(latest, event.threadId)
            )
            return
        }

        mutableState.value = latest.copy(
            pendingRealtimeNotifications = latest.pendingRealtimeNotifications + event,
            pendingDetailSyncThreadIds = pendingDetailSyncAfterUncertainEvent(latest, event.threadId)
        )
    }

    fun markRealtimeNotificationDisplayed(notificationId: String) {
        val latest = mutableState.value
        val displayedEventId = latest.pendingRealtimeNotifications
            .firstOrNull { it.notificationId == notificationId }
            ?.eventId
        val remainingNotifications = latest.pendingRealtimeNotifications.filterNot {
            it.notificationId == notificationId
        }
        val knownNotificationIds = sessionStore.loadKnownNotificationIds()
        val updatedKnownNotificationIds = (knownNotificationIds + notificationId)
            .toList()
            .takeLast(500)
            .toSet()
        sessionStore.saveKnownNotificationIds(updatedKnownNotificationIds)
        if (remainingNotifications.isEmpty()) {
            val eventIdToCommit = deferredRealtimeEventIdAfterPendingNotification ?: displayedEventId
            if (eventIdToCommit != null) {
                sessionStore.saveLastRealtimeEventId(eventIdToCommit)
            }
            deferredRealtimeEventIdAfterPendingNotification = null
        }
        realtimeHandle?.updateNotificationPreferences(
            enabledThreadIds = sessionStore.loadAlertEnabledThreadIds(),
            knownNotificationIds = updatedKnownNotificationIds
        )

        mutableState.value = latest.copy(
            pendingRealtimeNotifications = remainingNotifications
        )
    }

    fun updateRealtimeNotificationPreferences(enabledThreadIds: Set<String>) {
        realtimeHandle?.updateNotificationPreferences(
            enabledThreadIds = enabledThreadIds,
            knownNotificationIds = sessionStore.loadKnownNotificationIds()
        )
    }

    private fun Throwable.toConnectionErrorMessage(): String {
        return when {
            message == "token_invalid" -> "token_invalid"
            this is IOException -> "gateway_unreachable"
            else -> message ?: "gateway_unreachable"
        }
    }

    private fun Throwable.toBackgroundRefreshErrorMessage(): String? {
        return when {
            message == "token_invalid" -> "token_invalid"
            this is IOException -> null
            else -> message
        }
    }

    private fun Throwable.toSyncWarningMessage(): String? {
        return when {
            message == "token_invalid" -> null
            this is IOException -> "gateway_unreachable"
            else -> null
        }
    }
}

private data class PendingSseLatencyProbe(
    val sentAt: Long,
    val result: CompletableDeferred<Long>
)
