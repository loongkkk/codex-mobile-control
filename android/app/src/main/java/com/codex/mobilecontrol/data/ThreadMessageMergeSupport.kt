package com.codex.mobilecontrol.data

import com.codex.mobilecontrol.diagnostics.DebugTraceLogger
import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import com.codex.mobilecontrol.model.ThreadMessageSendState
import java.time.Instant
import kotlin.math.abs

object ThreadMessageMergeSupport {
    fun sanitizeOptimisticEchoes(detail: ThreadDetail): ThreadDetail {
        val sanitizedMessages = mergeMessagesById(
            existingMessages = emptyList(),
            incomingMessages = detail.recentMessages
        )
        val sanitizedDetail = if (sanitizedMessages == detail.recentMessages) {
            detail
        } else {
            detail.copy(recentMessages = sanitizedMessages)
        }
        return markBackgroundSendFailures(
            detail = sanitizedDetail,
            clientMessageIds = backgroundSendFailureClientMessageIds(sanitizedDetail.recentEvents)
        )
    }

    fun mergeMessagesById(
        existingMessages: List<ThreadMessage>,
        incomingMessages: List<ThreadMessage>
    ): List<ThreadMessage> {
        DebugTraceLogger.log(
            "message-merge",
            "mergeMessagesById existing=${existingMessages.size} incoming=${incomingMessages.size} " +
                "existingIds=${existingMessages.joinToString(",") { it.messageId.takeLast(18) }} " +
                "incomingIds=${incomingMessages.joinToString(",") { it.messageId.takeLast(18) }}"
        )
        val merged = LinkedHashMap<String, ThreadMessage>()
        (existingMessages + incomingMessages).forEach { message ->
            val matchingOptimisticKey = merged.entries
                .filter { entry ->
                    isMatchingOptimisticMessage(entry.value, message) ||
                        isMatchingConfirmedTextDuplicate(entry.value, message)
                }
                .minByOrNull { entry ->
                    abs(messageTimestampMillis(entry.value) - messageTimestampMillis(message))
                }
                ?.key
            if (matchingOptimisticKey != null) {
                val previous = requireNotNull(merged.remove(matchingOptimisticKey))
                val preferred = preferredMergedMessage(previous, message)
                DebugTraceLogger.log(
                    "message-merge",
                    "matched previous=${previous.messageId} incoming=${message.messageId} preferred=${preferred.messageId} " +
                        "text=${preferred.text.orEmpty().take(80).replace('\n', ' ')}"
                )
                merged[preferred.messageId] = preferred
            } else {
                merged[message.messageId] = message
            }
        }
        DebugTraceLogger.log(
            "message-merge",
            "mergeResult size=${merged.size} ids=${merged.values.joinToString(",") { it.messageId.takeLast(18) }}"
        )
        return merged.values.sortedBy { messageTimestampMillis(it) }
    }

    fun mergeOptimisticMessages(
        existingMessages: List<ThreadMessage>,
        optimisticMessages: List<ThreadMessage>,
        sendState: ThreadMessageSendState?
    ): List<ThreadMessage> {
        if (sendState == null) {
            val filteredOptimisticMessages = optimisticMessages.filterNot { optimisticMessage ->
                existingMessages.any { existingMessage ->
                    isConfirmedReplacementForOptimistic(
                        optimisticMessage = optimisticMessage,
                        confirmedMessage = existingMessage
                    )
                }
            }
            return mergeMessagesById(
                existingMessages = existingMessages,
                incomingMessages = filteredOptimisticMessages
            )
        }

        val optimisticMessageById = optimisticMessages.associateBy { it.messageId }
        val existingIds = existingMessages.map { it.messageId }.toSet()
        return existingMessages.map { message ->
            optimisticMessageById[message.messageId] ?: message
        } + optimisticMessages.filter { it.messageId !in existingIds }
    }

    fun mergeDetailMessages(
        incomingDetail: ThreadDetail,
        existingDetail: ThreadDetail?
    ): ThreadDetail {
        if (existingDetail?.thread?.threadId != incomingDetail.thread.threadId) {
            return sanitizeOptimisticEchoes(incomingDetail)
        }
        val incomingWithLocalOutgoing = mergeIncomingDetailWithLocalOutgoing(
            incomingDetail = incomingDetail,
            existingDetail = existingDetail
        )

        val incomingIds = incomingWithLocalOutgoing.recentMessages.map { it.messageId }.toSet()
        if (incomingIds.isEmpty()) {
            return sanitizeOptimisticEchoes(incomingWithLocalOutgoing)
        }

        val snapshotMessages = incomingDetail.recentMessages.ifEmpty {
            incomingWithLocalOutgoing.recentMessages
        }
        val firstIncomingTimestamp = snapshotMessages
            .minOf { messageTimestampMillis(it) }
        val olderLoadedMessages = existingDetail.recentMessages.filter { message ->
            message.messageId !in incomingIds &&
                message.sendState == null &&
                !isClientOptimisticMessage(message) &&
                messageTimestampMillis(message) < firstIncomingTimestamp
        }
        if (olderLoadedMessages.isEmpty()) {
            return sanitizeOptimisticEchoes(incomingWithLocalOutgoing)
        }

        return sanitizeOptimisticEchoes(
            incomingWithLocalOutgoing.copy(
                recentMessages = mergeMessagesById(
                    existingMessages = olderLoadedMessages,
                    incomingMessages = incomingWithLocalOutgoing.recentMessages
                )
            )
        )
    }

    fun messageTimestampMillis(message: ThreadMessage): Long {
        return timestampMillis(message.timestamp)
    }

    fun timestampMillis(timestamp: String): Long {
        return runCatching { Instant.parse(timestamp).toEpochMilli() }
            .getOrDefault(0L)
    }

    fun isBackfillableOptimisticBoundary(message: ThreadMessage): Boolean {
        return isClientOptimisticMessage(message) &&
            (message.kind == "file" ||
                message.kind == "image" ||
                message.fileName != null ||
                message.imageUrl != null ||
                message.thumbnailUrl != null)
    }

    private fun mergeIncomingDetailWithLocalOutgoing(
        incomingDetail: ThreadDetail,
        existingDetail: ThreadDetail?
    ): ThreadDetail {
        if (existingDetail?.thread?.threadId != incomingDetail.thread.threadId) {
            return incomingDetail
        }
        val localClientMessages = existingDetail.recentMessages.filter { message ->
            message.sendState != null || isClientOptimisticMessage(message)
        }
        if (localClientMessages.isEmpty()) {
            return incomingDetail
        }
        return incomingDetail.copy(
            recentMessages = mergeMessagesById(
                existingMessages = localClientMessages,
                incomingMessages = incomingDetail.recentMessages
            )
        )
    }

    private fun backgroundSendFailureClientMessageIds(events: List<ThreadEvent>): Set<String> {
        val failedClientMessageIds = mutableSetOf<String>()
        events.forEach { event ->
            desktopSendSuccessClientMessageId(event.eventId)?.let { clientMessageId ->
                failedClientMessageIds.remove(clientMessageId)
            }
            desktopSendFailureClientMessageId(event.eventId)?.let { clientMessageId ->
                failedClientMessageIds.add(clientMessageId)
            }
        }
        return failedClientMessageIds
    }

    fun desktopSendFailureClientMessageId(eventId: String): String? {
        val suffix = ":desktop-error"
        if (!eventId.endsWith(suffix)) {
            return null
        }
        return eventId
            .removeSuffix(suffix)
            .takeIf { it.startsWith("client-") }
    }

    private fun desktopSendSuccessClientMessageId(eventId: String): String? {
        val suffixes = listOf(":desktop", ":image-desktop", ":file-desktop")
        val suffix = suffixes.firstOrNull(eventId::endsWith) ?: return null
        return eventId
            .removeSuffix(suffix)
            .takeIf { it.startsWith("client-") }
    }

    fun markBackgroundSendFailures(
        detail: ThreadDetail,
        clientMessageIds: Set<String>
    ): ThreadDetail {
        if (clientMessageIds.isEmpty()) {
            return detail
        }
        var changed = false
        val markedMessages = detail.recentMessages.map { message ->
            if (
                message.role == ThreadMessageRole.USER &&
                clientMessageIds.any { clientMessageId ->
                    message.messageId == clientMessageId ||
                        message.messageId.startsWith("$clientMessageId:image-") ||
                        message.messageId.startsWith("$clientMessageId:file-")
                }
            ) {
                changed = true
                message.copy(sendState = ThreadMessageSendState.FAILED)
            } else {
                message
            }
        }
        return if (changed) {
            detail.copy(recentMessages = markedMessages)
        } else {
            detail
        }
    }

    private fun isClientOptimisticMessage(message: ThreadMessage): Boolean {
        return message.messageId.startsWith("client-")
    }

    private fun isMatchingOptimisticMessage(
        left: ThreadMessage,
        right: ThreadMessage
    ): Boolean {
        if (left.threadId != right.threadId || left.role != right.role) {
            return false
        }
        if (left.role != ThreadMessageRole.USER) {
            return false
        }
        if (isClientOptimisticMessage(left) == isClientOptimisticMessage(right)) {
            return false
        }
        if (!hasSameMessagePayload(left, right)) {
            return false
        }
        val optimisticMessage = when {
            isClientOptimisticMessage(left) -> left
            isClientOptimisticMessage(right) -> right
            else -> null
        }
        val confirmedMessage = when (optimisticMessage) {
            left -> right
            right -> left
            else -> null
        }
        val leftTimestamp = messageTimestampMillis(left)
        val rightTimestamp = messageTimestampMillis(right)
        if (leftTimestamp == 0L || rightTimestamp == 0L) {
            return left.timestamp == right.timestamp
        }
        if (optimisticMessage != null &&
            confirmedMessage != null &&
            isConfirmedReplacementForOptimistic(
                optimisticMessage = optimisticMessage,
                confirmedMessage = confirmedMessage
            )
        ) {
            return true
        }
        if (left.sendState == ThreadMessageSendState.FAILED ||
            right.sendState == ThreadMessageSendState.FAILED
        ) {
            return false
        }
        return abs(leftTimestamp - rightTimestamp) <= optimisticDedupeWindowMs(left, right)
    }

    private fun isMatchingConfirmedTextDuplicate(
        left: ThreadMessage,
        right: ThreadMessage
    ): Boolean {
        if (left.threadId != right.threadId || left.role != right.role) {
            return false
        }
        if (isClientOptimisticMessage(left) ||
            isClientOptimisticMessage(right) ||
            left.sendState == ThreadMessageSendState.FAILED ||
            right.sendState == ThreadMessageSendState.FAILED
        ) {
            return false
        }
        if (left.role != ThreadMessageRole.USER &&
            left.role != ThreadMessageRole.ASSISTANT
        ) {
            return false
        }
        if (left.kind != "text" || right.kind != "text") {
            return false
        }
        if (left.timestamp != right.timestamp) {
            return false
        }
        return left.text.orEmpty().trim().isNotBlank() &&
            left.text.orEmpty().trim() == right.text.orEmpty().trim()
    }

    private fun isConfirmedReplacementForOptimistic(
        optimisticMessage: ThreadMessage,
        confirmedMessage: ThreadMessage
    ): Boolean {
        if (!isClientOptimisticMessage(optimisticMessage) ||
            isClientOptimisticMessage(confirmedMessage) ||
            optimisticMessage.role != ThreadMessageRole.USER ||
            confirmedMessage.role != ThreadMessageRole.USER ||
            confirmedMessage.sendState == ThreadMessageSendState.FAILED ||
            !hasSameMessagePayload(optimisticMessage, confirmedMessage)
        ) {
            return false
        }
        val optimisticTimestamp = messageTimestampMillis(optimisticMessage)
        val confirmedTimestamp = messageTimestampMillis(confirmedMessage)
        if (optimisticTimestamp == 0L || confirmedTimestamp == 0L) {
            return optimisticMessage.timestamp == confirmedMessage.timestamp
        }
        if (confirmedTimestamp < optimisticTimestamp) {
            return false
        }
        return confirmedTimestamp - optimisticTimestamp <= PENDING_OPTIMISTIC_DEDUPE_WINDOW_MS
    }

    private fun hasSameMessagePayload(left: ThreadMessage, right: ThreadMessage): Boolean {
        val leftText = left.text.orEmpty().trim()
        val rightText = right.text.orEmpty().trim()
        if (leftText != rightText) {
            return false
        }

        if (left.kind == right.kind && left.kind == "image") {
            return left.fileName == right.fileName
        }

        if (left.kind == right.kind && left.kind == "file") {
            return left.fileName == right.fileName
        }

        if (left.kind == right.kind) {
            return true
        }

        return leftText.isNotBlank() &&
            isAttachmentTextPair(left, right)
    }

    private fun optimisticDedupeWindowMs(left: ThreadMessage, right: ThreadMessage): Long {
        return if (isAttachmentTextPair(left, right)) {
            ATTACHMENT_TEXT_DEDUPE_WINDOW_MS
        } else {
            OPTIMISTIC_DEDUPE_WINDOW_MS
        }
    }

    private fun isAttachmentTextPair(left: ThreadMessage, right: ThreadMessage): Boolean {
        return (left.kind == "text" && isAttachmentMessage(right)) ||
            (right.kind == "text" && isAttachmentMessage(left))
    }

    private fun isAttachmentMessage(message: ThreadMessage): Boolean {
        return when (message.kind) {
            "image" -> !message.imageUrl.isNullOrBlank() || !message.fileName.isNullOrBlank()
            "file" -> !message.fileUrl.isNullOrBlank() || !message.fileName.isNullOrBlank()
            else -> false
        }
    }

    private fun preferredMergedMessage(left: ThreadMessage, right: ThreadMessage): ThreadMessage {
        val confirmed = if (isClientOptimisticMessage(left) && !isClientOptimisticMessage(right)) {
            right
        } else if (!isClientOptimisticMessage(left) && isClientOptimisticMessage(right)) {
            left
        } else {
            right
        }
        val fallback = if (confirmed === left) right else left
        val base = if (confirmed.kind == "text" && isAttachmentMessage(fallback)) {
            confirmed.copy(kind = fallback.kind)
        } else {
            confirmed
        }
        return base.copy(
            text = confirmed.text ?: fallback.text,
            imageUrl = base.imageUrl ?: fallback.imageUrl,
            thumbnailUrl = base.thumbnailUrl ?: fallback.thumbnailUrl,
            fileUrl = base.fileUrl ?: fallback.fileUrl,
            fileName = base.fileName ?: fallback.fileName,
            sendState = null,
            mimeType = base.mimeType ?: fallback.mimeType
        )
    }

    private const val OPTIMISTIC_DEDUPE_WINDOW_MS = 2 * 60_000L
    private const val PENDING_OPTIMISTIC_DEDUPE_WINDOW_MS = 15 * 60_000L
    private const val ATTACHMENT_TEXT_DEDUPE_WINDOW_MS = 15 * 60_000L
}
