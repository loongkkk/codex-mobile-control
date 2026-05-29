package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole

object DetailTypingAnimatorSupport {
    fun stableMessageKey(message: ThreadMessage, displayedText: String): String {
        return buildString {
            append(message.threadId)
            append('|')
            append(message.role.name)
            append('|')
            append(message.kind)
            append('|')
            append(message.timestamp)
            append('|')
            append(displayedText.trim())
        }
    }

    fun latestAnimatableMessage(items: List<DetailConversationItem>): ThreadMessage? {
        val trailingMessage = (items.lastOrNull() as? DetailConversationItem.MessageRow)?.message
            ?: return null
        return trailingMessage.takeIf(::isAnimatableAssistantText)
    }

    fun animationStartIndex(
        previousLatestMessageKey: String?,
        previousLatestText: String?,
        currentMessageKey: String,
        currentLatestText: String,
        previousVisibleChars: Int,
        isAnimationActive: Boolean
    ): Int? {
        if (currentLatestText.isBlank()) {
            return null
        }
        if (previousLatestMessageKey == null || previousLatestText == null) {
            return null
        }

        if (previousLatestMessageKey != currentMessageKey) {
            return 0
        }

        if (previousLatestText == currentLatestText) {
            if (previousVisibleChars <= 0) {
                return if (isAnimationActive) 0 else null
            }
            return previousVisibleChars
                .coerceIn(0, currentLatestText.length)
                .takeIf { it < currentLatestText.length }
        }

        val commonPrefixLength = commonPrefixLength(previousLatestText, currentLatestText)
        val resumedIndex = maxOf(previousVisibleChars, commonPrefixLength)
            .coerceIn(0, currentLatestText.length)
        return resumedIndex.takeIf { it < currentLatestText.length }
    }

    private fun isAnimatableAssistantText(message: ThreadMessage): Boolean {
        return message.role == ThreadMessageRole.ASSISTANT &&
            message.kind == "text" &&
            !message.text.isNullOrBlank()
    }

    private fun commonPrefixLength(left: String, right: String): Int {
        val sharedLength = minOf(left.length, right.length)
        for (index in 0 until sharedLength) {
            if (left[index] != right[index]) {
                return index
            }
        }
        return sharedLength
    }
}
