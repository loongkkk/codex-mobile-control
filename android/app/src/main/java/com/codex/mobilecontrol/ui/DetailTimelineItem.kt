package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadMessage

sealed interface DetailTimelineItem {
    data class Section(
        val key: String,
        val title: String
    ) : DetailTimelineItem

    data class EventRow(
        val event: ThreadEvent
    ) : DetailTimelineItem

    data class MessageRow(
        val message: ThreadMessage
    ) : DetailTimelineItem
}

sealed interface DetailConversationItem {
    data class HistoryGroup(
        val key: String,
        val messages: List<ThreadMessage>,
        val expanded: Boolean,
        val processedDurationLabel: String
    ) : DetailConversationItem

    data class MessageRow(
        val message: ThreadMessage
    ) : DetailConversationItem

    data class ImageGroup(
        val messages: List<ThreadMessage>
    ) : DetailConversationItem
}
