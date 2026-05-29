package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.R
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.QueuedTextMessageStatus

internal sealed interface ComposerStatusContent {
    data class Queued(
        val preview: String,
        val count: Int,
        val status: QueuedTextMessageStatus
    ) : ComposerStatusContent
    data object Loading : ComposerStatusContent
    data class Disabled(val reason: String) : ComposerStatusContent
    data class Error(val message: String) : ComposerStatusContent
    data object Ready : ComposerStatusContent
}

internal data class ComposerStatusPresentation(
    val content: ComposerStatusContent,
    val colorResId: Int,
    val isActionable: Boolean
)

internal object ComposerStatusSupport {
    fun presentation(
        queuedTextMessages: List<QueuedTextMessage>,
        isPendingDetail: Boolean,
        sendDisabledReason: String?,
        errorMessage: String?
    ): ComposerStatusPresentation {
        val visibleQueue = visibleQueuedTexts(queuedTextMessages)
        val firstQueuedMessage = visibleQueue.firstOrNull()
        return when {
            firstQueuedMessage != null -> ComposerStatusPresentation(
                content = ComposerStatusContent.Queued(
                    preview = queuedTextPreview(firstQueuedMessage.text),
                    count = visibleQueue.size,
                    status = firstQueuedMessage.status
                ),
                colorResId = if (firstQueuedMessage.status == QueuedTextMessageStatus.FAILED) {
                    R.color.login_error_text
                } else {
                    R.color.dashboard_nav_active
                },
                isActionable = true
            )
            isPendingDetail -> ComposerStatusPresentation(
                content = ComposerStatusContent.Loading,
                colorResId = R.color.dashboard_nav_active,
                isActionable = false
            )
            sendDisabledReason != null -> ComposerStatusPresentation(
                content = ComposerStatusContent.Disabled(sendDisabledReason),
                colorResId = R.color.login_error_text,
                isActionable = false
            )
            errorMessage != null -> ComposerStatusPresentation(
                content = ComposerStatusContent.Error(errorMessage),
                colorResId = R.color.login_error_text,
                isActionable = false
            )
            else -> ComposerStatusPresentation(
                content = ComposerStatusContent.Ready,
                colorResId = R.color.text_secondary,
                isActionable = false
            )
        }
    }

    fun isStatusVisible(
        showComposer: Boolean,
        queuedTextMessages: List<QueuedTextMessage>,
        selectedThreadId: String?
    ): Boolean {
        return showComposer &&
            visibleQueuedTexts(queuedTextMessages).any { message -> message.threadId == selectedThreadId }
    }

    fun queuedTextPreview(text: String): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= 36) normalized else "${normalized.take(35)}..."
    }

    private fun visibleQueuedTexts(messages: List<QueuedTextMessage>): List<QueuedTextMessage> {
        return messages.filter { message ->
            message.status == QueuedTextMessageStatus.PENDING ||
                message.status == QueuedTextMessageStatus.DISPATCHING ||
                message.status == QueuedTextMessageStatus.FAILED
        }
    }
}
