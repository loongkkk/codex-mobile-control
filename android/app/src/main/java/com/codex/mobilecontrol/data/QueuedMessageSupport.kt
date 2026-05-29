package com.codex.mobilecontrol.data

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.QueuedTextMessageStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.ui.MainUiState
import java.time.Instant

sealed interface QueuedTextPlan {
    data object Blank : QueuedTextPlan
    data object SendDisabled : QueuedTextPlan
    data class SendNow(val text: String) : QueuedTextPlan
    data class Queue(val message: QueuedTextMessage) : QueuedTextPlan
}

object QueuedMessageSupport {
    fun planQueueText(
        state: MainUiState,
        text: String,
        queuedAtMillis: Long,
        queueId: String
    ): QueuedTextPlan {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) {
            return QueuedTextPlan.Blank
        }

        val threadId = state.selectedThreadId
        val detail = state.detail
        if (
            state.config == null ||
            threadId == null ||
            detail == null ||
            detail.thread.threadId != threadId
        ) {
            return QueuedTextPlan.SendDisabled
        }

        return if (canSendQueuedTextNow(detail)) {
            QueuedTextPlan.SendNow(trimmedText)
        } else {
            QueuedTextPlan.Queue(
                QueuedTextMessage(
                    threadId = threadId,
                    text = trimmedText,
                    queuedAtMillis = queuedAtMillis,
                    blockedThreadUpdatedAt = detail.thread.updatedAt,
                    queueId = queueId,
                    status = QueuedTextMessageStatus.PENDING
                )
            )
        }
    }

    fun dispatchableQueuedText(state: MainUiState): QueuedTextMessage? {
        val queuedMessage = firstVisibleQueuedText(state) ?: return null
        val detail = state.detail ?: return null
        if (
            state.selectedThreadId != queuedMessage.threadId ||
            detail.thread.threadId != queuedMessage.threadId ||
            queuedMessage.status != QueuedTextMessageStatus.PENDING ||
            !canSendQueuedTextNow(detail) ||
            !isNewerThanBlockedTurn(queuedMessage, detail)
        ) {
            return null
        }
        return queuedMessage
    }

    fun visibleQueuedTextsForThread(
        queuedMessages: List<QueuedTextMessage>,
        threadId: String?
    ): List<QueuedTextMessage> {
        if (threadId == null) {
            return emptyList()
        }
        return queuedMessages.filter { message ->
            message.threadId == threadId && isVisibleStatus(message.status)
        }
    }

    private fun firstVisibleQueuedText(state: MainUiState): QueuedTextMessage? {
        return visibleQueuedTextsForThread(
            queuedMessages = state.queuedTextMessages,
            threadId = state.selectedThreadId
        ).firstOrNull()
    }

    private fun isVisibleStatus(status: QueuedTextMessageStatus): Boolean {
        return status == QueuedTextMessageStatus.PENDING ||
            status == QueuedTextMessageStatus.DISPATCHING ||
            status == QueuedTextMessageStatus.FAILED
    }

    private fun canSendQueuedTextNow(detail: ThreadDetail): Boolean {
        return detail.sendAvailable &&
            detail.thread.status != MobileThreadStatus.RUNNING &&
            detail.thread.status != MobileThreadStatus.OFFLINE
    }

    private fun isNewerThanBlockedTurn(
        queuedMessage: QueuedTextMessage,
        detail: ThreadDetail
    ): Boolean {
        val blockedUpdatedAt = queuedMessage.blockedThreadUpdatedAt ?: return true
        val candidateUpdatedAt = detail.thread.updatedAt
        val candidate = runCatching { Instant.parse(candidateUpdatedAt) }.getOrNull()
        val blocked = runCatching { Instant.parse(blockedUpdatedAt) }.getOrNull()
        return when {
            candidate != null && blocked != null -> candidate.isAfter(blocked)
            else -> candidateUpdatedAt > blockedUpdatedAt
        }
    }
}
