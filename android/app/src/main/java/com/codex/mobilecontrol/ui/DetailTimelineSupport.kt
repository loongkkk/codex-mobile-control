package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import java.time.Duration
import java.time.Instant

object DetailTimelineSupport {
    const val MAX_VISIBLE_EVENTS = 4

    fun visibleMessages(detail: ThreadDetail?): List<ThreadMessage> {
        return detail?.recentMessages
            ?.filterNot(::isInternalControlMessage)
            .orEmpty()
    }

    private fun isInternalControlMessage(message: ThreadMessage): Boolean {
        val text = message.text?.trim() ?: return false
        return text.startsWith("<turn_aborted>") ||
            text.startsWith("<environment_context>")
    }

    fun visibleEvents(detail: ThreadDetail?): List<ThreadEvent> {
        return detail?.recentEvents
            ?.takeLast(MAX_VISIBLE_EVENTS)
            .orEmpty()
    }

    fun conversationItems(
        detail: ThreadDetail?,
        expandHistory: Boolean = false,
        expandedHistoryKeys: Set<String> = emptySet(),
        collapseSettings: DetailTimelineCollapseSettings = DetailTimelineCollapseSettings()
    ): List<DetailConversationItem> {
        val messages = visibleMessages(detail)
        return conversationItems(
            messages = messages,
            collapseHistory = true,
            expandHistory = expandHistory,
            expandedHistoryKeys = expandedHistoryKeys,
            keepLatestResponseVisible = detail?.thread?.status == MobileThreadStatus.RUNNING,
            collapseSettings = collapseSettings
        )
    }

    fun conversationItems(messages: List<ThreadMessage>): List<DetailConversationItem> {
        return conversationItems(
            messages = messages,
            collapseHistory = false,
            expandHistory = false,
            expandedHistoryKeys = emptySet(),
            keepLatestResponseVisible = false,
            collapseSettings = DetailTimelineCollapseSettings()
        )
    }

    private fun conversationItems(
        messages: List<ThreadMessage>,
        collapseHistory: Boolean,
        expandHistory: Boolean,
        expandedHistoryKeys: Set<String>,
        keepLatestResponseVisible: Boolean,
        collapseSettings: DetailTimelineCollapseSettings
    ): List<DetailConversationItem> {
        if (!collapseHistory) {
            return groupedConversationItems(messages)
        }

        return collapsedConversationItems(
            messages,
            expandHistory,
            expandedHistoryKeys,
            keepLatestResponseVisible,
            collapseSettings
        )
    }

    private fun collapsedConversationItems(
        messages: List<ThreadMessage>,
        expandHistory: Boolean,
        expandedHistoryKeys: Set<String>,
        keepLatestResponseVisible: Boolean,
        collapseSettings: DetailTimelineCollapseSettings
    ): List<DetailConversationItem> {
        val items = mutableListOf<DetailConversationItem>()
        var index = 0
        while (index < messages.size) {
            val userStartIndex = index
            while (index < messages.size && messages[index].role == ThreadMessageRole.USER) {
                index += 1
            }
            if (userStartIndex < index) {
                items += groupedConversationItems(messages.subList(userStartIndex, index))
            }

            val responseStartIndex = index
            while (index < messages.size && messages[index].role != ThreadMessageRole.USER) {
                index += 1
            }
            if (responseStartIndex < index) {
                val responseMessages = messages.subList(responseStartIndex, index)
                items += if (keepLatestResponseVisible && index == messages.size) {
                    groupedConversationItems(responseMessages)
                } else {
                    collapsedResponseItems(
                        messages = responseMessages,
                        expandHistory = expandHistory,
                        expandedHistoryKeys = expandedHistoryKeys,
                        collapseSettings = collapseSettings
                    )
                }
            }
        }
        return items
    }

    private fun collapsedResponseItems(
        messages: List<ThreadMessage>,
        expandHistory: Boolean,
        expandedHistoryKeys: Set<String>,
        collapseSettings: DetailTimelineCollapseSettings
    ): List<DetailConversationItem> {
        val summaryIndex = latestAssistantSummaryIndex(messages)
        if (summaryIndex <= 0) {
            return groupedConversationItems(messages)
        }

        val historyMessages = messages.take(summaryIndex)
        if (historyMessages.size < collapseSettings.minProcessMessageCount) {
            return groupedConversationItems(messages)
        }
        val durationMillis = processDurationMillis(
            historyMessages.firstOrNull()?.timestamp,
            messages.getOrNull(summaryIndex)?.timestamp
        )
        if (durationMillis == null || durationMillis < collapseSettings.minProcessDurationMillis) {
            return groupedConversationItems(messages)
        }

        val summaryMessages = messages.drop(summaryIndex)
        val historyKey = historyGroupKey(historyMessages, summaryMessages)
        val isExpanded = expandHistory || historyKey in expandedHistoryKeys
        return buildList {
            add(
                DetailConversationItem.HistoryGroup(
                    key = historyKey,
                    messages = historyMessages,
                    expanded = isExpanded,
                    processedDurationLabel = MobileUiFormatter.formatDurationBetween(
                        historyMessages.firstOrNull()?.timestamp,
                        summaryMessages.firstOrNull()?.timestamp
                    )
                )
            )
            if (isExpanded) {
                addAll(groupedConversationItems(historyMessages))
            }
            addAll(groupedConversationItems(summaryMessages))
        }
    }

    private fun historyGroupKey(
        historyMessages: List<ThreadMessage>,
        summaryMessages: List<ThreadMessage>
    ): String {
        return listOf(
            "history",
            historyMessages.firstOrNull()?.messageId.orEmpty(),
            historyMessages.lastOrNull()?.messageId.orEmpty(),
            summaryMessages.firstOrNull()?.messageId.orEmpty()
        ).joinToString(":")
    }

    private fun latestAssistantSummaryIndex(messages: List<ThreadMessage>): Int {
        return messages.indexOfLast { message ->
            message.role == ThreadMessageRole.ASSISTANT &&
                message.kind == "text" &&
                !message.text.isNullOrBlank()
        }
    }

    private fun processDurationMillis(startTimestamp: String?, endTimestamp: String?): Long? {
        val start = parseInstant(startTimestamp) ?: return null
        val end = parseInstant(endTimestamp) ?: return null
        return Duration.between(start, end).abs().toMillis()
    }

    private fun groupedConversationItems(messages: List<ThreadMessage>): List<DetailConversationItem> {
        val items = mutableListOf<DetailConversationItem>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when {
                isImageMessage(message) -> {
                    val imageMessages = mutableListOf<ThreadMessage>()
                    val textMessages = mutableListOf<ThreadMessage>()
                    val groupRole = message.role
                    val groupTimestamp = message.timestamp
                    while (
                        index < messages.size &&
                        isImageMessage(messages[index]) &&
                        messages[index].role == groupRole &&
                        messages[index].timestamp == groupTimestamp
                    ) {
                        val imageMessage = messages[index]
                        imageMessages += imageMessage.copy(text = null)
                        attachmentTextMessage(imageMessage)?.let { textMessages += it }
                        index += 1
                    }

                    items += DetailConversationItem.ImageGroup(imageMessages)
                    items += textMessages.map(DetailConversationItem::MessageRow)
                }

                isFileMessage(message) -> {
                    val fileMessages = mutableListOf<ThreadMessage>()
                    val textMessages = mutableListOf<ThreadMessage>()
                    val groupRole = message.role
                    val groupTimestamp = message.timestamp
                    while (
                        index < messages.size &&
                        isFileMessage(messages[index]) &&
                        messages[index].role == groupRole &&
                        messages[index].timestamp == groupTimestamp
                    ) {
                        val fileMessage = messages[index]
                        fileMessages += fileMessage.copy(text = null)
                        attachmentTextMessage(fileMessage)?.let { textMessages += it }
                        index += 1
                    }

                    items += fileMessages.map(DetailConversationItem::MessageRow)
                    items += textMessages.map(DetailConversationItem::MessageRow)
                }

                else -> {
                    items += DetailConversationItem.MessageRow(message)
                    index += 1
                }
            }
        }
        return items
    }

    private fun isImageMessage(message: ThreadMessage): Boolean {
        return message.kind == "image" && !message.imageUrl.isNullOrBlank()
    }

    private fun isFileMessage(message: ThreadMessage): Boolean {
        return message.kind == "file" &&
            (!message.fileUrl.isNullOrBlank() || !message.fileName.isNullOrBlank())
    }

    private fun attachmentTextMessage(message: ThreadMessage): ThreadMessage? {
        val text = message.text?.trim()
        if (text.isNullOrBlank()) {
            return null
        }
        return message.copy(
            messageId = "${message.messageId}:text",
            kind = "text",
            text = text,
            imageUrl = null,
            thumbnailUrl = null,
            fileUrl = null,
            fileName = null,
            mimeType = null
        )
    }

    private fun parseInstant(timestamp: String?): Instant? {
        return runCatching {
            timestamp?.takeIf { it.isNotBlank() }?.let(Instant::parse)
        }.getOrNull()
    }

    fun timelineItems(detail: ThreadDetail?): List<DetailTimelineItem> {
        if (detail == null) {
            return emptyList()
        }

        val items = mutableListOf<DetailTimelineItem>()
        val messages = visibleMessages(detail)

        if (messages.isNotEmpty()) {
            items += DetailTimelineItem.Section(
                key = "messages",
                title = "聊天记录"
            )
            items += messages.map(DetailTimelineItem::MessageRow)
        }

        return items
    }
}
