package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import java.time.Instant

object DetailAlertSupport {
    private val processedDurationPattern = Regex("""(?:本轮)?已处理\s+((?:\d+h\s+\d+m)|(?:\d+m\s+\d+s)|(?:\d+s))""")

    fun notificationThreadTitle(threadTitle: String, fallback: String): String {
        return threadTitle.trim().ifBlank { fallback }
    }

    fun latestAlertMessage(detail: ThreadDetail?): ThreadMessage? {
        val messages = detail?.recentMessages.orEmpty()
        if (messages.isEmpty()) {
            return null
        }

        val indexedMessages = messages.mapIndexed { index, message ->
            IndexedMessage(
                index = index,
                message = message,
                timestampMillis = parseIsoMillis(message.timestamp)
            )
        }
        if (indexedMessages.none { it.timestampMillis != null }) {
            return messages.last()
        }

        return indexedMessages
            .maxWithOrNull(
                compareBy<IndexedMessage> { it.timestampMillis ?: Long.MIN_VALUE }
                    .thenBy { it.index }
            )
            ?.message
    }

    fun messageAlertKey(message: ThreadMessage): String {
        return listOf(
            message.threadId,
            message.role.wireValue,
            message.kind,
            stableMessageIdentity(message)
        ).joinToString("|")
    }

    fun shouldNotifyMessageAlert(
        notifiedKeys: Set<String>,
        latestMessageKey: String?
    ): Boolean {
        return latestMessageKey != null && latestMessageKey !in notifiedKeys
    }

    fun rememberMessageAlertKey(
        notifiedKeys: Set<String>,
        latestMessageKey: String?,
        maxSize: Int = MAX_NOTIFIED_MESSAGE_KEYS_PER_THREAD
    ): LinkedHashSet<String> {
        val remembered = LinkedHashSet<String>()
        notifiedKeys.filter { it.isNotBlank() }.forEach(remembered::add)
        latestMessageKey?.takeIf { it.isNotBlank() }?.let { key ->
            remembered.remove(key)
            remembered.add(key)
        }
        while (remembered.size > maxSize) {
            val oldest = remembered.firstOrNull() ?: break
            remembered.remove(oldest)
        }
        return remembered
    }

    fun completionAlertKey(detail: ThreadDetail): String? {
        if (detail.thread.status == MobileThreadStatus.RUNNING) {
            return null
        }

        val completionEvent = latestRelevantCompletionEvent(detail)
        if (completionEvent != null) {
            return listOf(
                detail.thread.threadId,
                "completed",
                completionEvent.timestamp
            ).joinToString("|")
        }

        if (!isCompletionStatus(detail.thread.status)) {
            return null
        }

        return listOf(
            detail.thread.threadId,
            "completed",
            detail.thread.updatedAt
        ).joinToString("|")
    }

    fun shouldClearCompletionNotification(
        previousCompletionKey: String?,
        currentCompletionKey: String?
    ): Boolean {
        return previousCompletionKey != null && currentCompletionKey == null
    }

    fun shouldSuppressNewMessageAlertForCompletion(
        detail: ThreadDetail,
        latestMessage: ThreadMessage?
    ): Boolean {
        if (isCompletionStatus(detail.thread.status)) {
            return true
        }

        val completionEvent = latestRelevantCompletionEvent(detail) ?: return false
        val latestMessageMillis = latestMessage?.timestamp?.let(::parseIsoMillis) ?: return true
        val completionMillis = parseIsoMillis(completionEvent.timestamp) ?: return true
        return completionMillis >= latestMessageMillis
    }

    fun completionAlertBody(detail: ThreadDetail, fallback: String): String {
        completionProcessedDurationLabel(detail)?.let { duration ->
            return "本轮已完成，用时: $duration"
        }
        return fallback
    }

    fun completionAlertBodyFromText(text: String?, fallback: String): String {
        val normalized = text.orEmpty().trim()
        val duration = processedDurationPattern.find(normalized)?.groupValues?.getOrNull(1)
        return duration?.let { "本轮已完成，用时: $it" }.orEmpty().ifBlank { fallback }
    }

    private fun completionProcessedDurationLabel(detail: ThreadDetail): String? {
        val completionEvent = latestRelevantCompletionEvent(detail)
        val runningEvent = latestIndexedEvent(detail, ::isRunningEvent)?.event
        if (completionEvent != null && runningEvent != null) {
            val runningMillis = parseIsoMillis(runningEvent.timestamp)
            val completionMillis = parseIsoMillis(completionEvent.timestamp)
            if (runningMillis != null && completionMillis != null && completionMillis >= runningMillis) {
                return MobileUiFormatter.formatDurationBetween(
                    runningEvent.timestamp,
                    completionEvent.timestamp
                ).takeUnless { it == "--" }
            }
        }

        return detail.recentMessages
            .asReversed()
            .asSequence()
            .filter { it.role == ThreadMessageRole.ASSISTANT }
            .mapNotNull { message -> processedDurationPattern.find(message.text.orEmpty())?.groupValues?.get(1) }
            .firstOrNull()
    }

    private fun latestRelevantCompletionEvent(detail: ThreadDetail): ThreadEvent? {
        val completionEvent = latestIndexedEvent(detail, ::isCompletionEvent) ?: return null
        val runningEvent = latestIndexedEvent(detail, ::isRunningEvent)
        if (runningEvent != null && eventOccursAfter(runningEvent, completionEvent)) {
            return null
        }

        return completionEvent.event
    }

    private fun latestIndexedEvent(
        detail: ThreadDetail,
        predicate: (ThreadEvent) -> Boolean
    ): IndexedEvent? {
        val matchedEvents = detail.recentEvents.mapIndexedNotNull { index, event ->
            if (!predicate(event)) {
                return@mapIndexedNotNull null
            }
            IndexedEvent(
                index = index,
                event = event,
                timestampMillis = parseIsoMillis(event.timestamp)
            )
        }
        if (matchedEvents.isEmpty()) {
            return null
        }
        if (matchedEvents.none { it.timestampMillis != null }) {
            return matchedEvents.last()
        }

        return matchedEvents
            .maxWithOrNull(
                compareBy<IndexedEvent> { it.timestampMillis ?: Long.MIN_VALUE }
                    .thenBy { it.index }
            )
    }

    private fun isCompletionEvent(event: ThreadEvent): Boolean {
        return event.kind == ThreadEventKind.TURN_COMPLETED ||
            event.status == MobileThreadStatus.COMPLETED
    }

    private fun isRunningEvent(event: ThreadEvent): Boolean {
        return event.kind == ThreadEventKind.TURN_STARTED ||
            event.status == MobileThreadStatus.RUNNING
    }

    private fun eventOccursAfter(left: IndexedEvent, right: IndexedEvent): Boolean {
        val leftMillis = left.timestampMillis
        val rightMillis = right.timestampMillis
        if (leftMillis != null && rightMillis != null) {
            if (leftMillis != rightMillis) {
                return leftMillis > rightMillis
            }
            return left.index > right.index
        }
        if (leftMillis != null) {
            return true
        }
        if (rightMillis != null) {
            return false
        }
        return left.index > right.index
    }

    private fun isCompletionStatus(status: MobileThreadStatus): Boolean {
        return status == MobileThreadStatus.COMPLETED
    }

    private fun parseIsoMillis(value: String): Long? {
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private fun stableMessageIdentity(message: ThreadMessage): String {
        previewMessageIdPattern.matchEntire(message.messageId)?.let { match ->
            return "preview:${match.groupValues[1]}"
        }
        return message.messageId
    }

    private data class IndexedMessage(
        val index: Int,
        val message: ThreadMessage,
        val timestampMillis: Long?
    )

    private data class IndexedEvent(
        val index: Int,
        val event: ThreadEvent,
        val timestampMillis: Long?
    )

    private val previewMessageIdPattern =
        Regex("""^preview:\d+:(\d{4}-\d{2}-\d{2}T.*)$""")

    private const val MAX_NOTIFIED_MESSAGE_KEYS_PER_THREAD = 64
}
