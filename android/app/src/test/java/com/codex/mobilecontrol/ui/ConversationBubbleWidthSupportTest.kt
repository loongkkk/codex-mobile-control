package com.codex.mobilecontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationBubbleWidthSupportTest {

    @Test
    fun `user compact message can shrink below legacy minimum width`() {
        val width = ConversationBubbleWidthSupport.resolvedTextWidth(
            desiredWidth = 54,
            minimumWidth = 358,
            maximumWidth = 778,
            bufferWidth = 6,
            keepMinimumWidth = false
        )

        assertEquals(60, width)
    }

    @Test
    fun `medium cjk user message keeps measured width to avoid unwanted wrapping`() {
        val width = ConversationBubbleWidthSupport.resolvedTextWidth(
            desiredWidth = 430,
            minimumWidth = 358,
            maximumWidth = 778,
            bufferWidth = 6,
            keepMinimumWidth = false
        )

        assertEquals(436, width)
    }

    @Test
    fun `assistant short message can keep the legacy minimum width`() {
        val width = ConversationBubbleWidthSupport.resolvedTextWidth(
            desiredWidth = 120,
            minimumWidth = 358,
            maximumWidth = 778,
            bufferWidth = 6,
            keepMinimumWidth = true
        )

        assertEquals(358, width)
    }

    @Test
    fun `long received message leaves width unspecified so parent constraints can wrap it`() {
        val width = ConversationBubbleWidthSupport.resolvedTextWidth(
            desiredWidth = 1800,
            minimumWidth = 358,
            maximumWidth = 778,
            bufferWidth = 6,
            keepMinimumWidth = true
        )

        assertNull(width)
    }
}
