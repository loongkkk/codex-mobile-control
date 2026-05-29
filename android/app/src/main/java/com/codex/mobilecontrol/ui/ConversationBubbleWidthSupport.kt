package com.codex.mobilecontrol.ui

object ConversationBubbleWidthSupport {

    fun resolvedTextWidth(
        desiredWidth: Int,
        minimumWidth: Int,
        maximumWidth: Int,
        bufferWidth: Int,
        keepMinimumWidth: Boolean
    ): Int? {
        if (desiredWidth <= 0 || maximumWidth <= 0) {
            return null
        }
        val bufferedDesiredWidth = desiredWidth + bufferWidth.coerceAtLeast(0)
        if (bufferedDesiredWidth >= maximumWidth) {
            return null
        }

        val lowerBound = if (keepMinimumWidth) {
            minimumWidth.coerceAtLeast(0).coerceAtMost(maximumWidth)
        } else {
            1
        }
        return bufferedDesiredWidth.coerceIn(lowerBound, maximumWidth)
    }
}
