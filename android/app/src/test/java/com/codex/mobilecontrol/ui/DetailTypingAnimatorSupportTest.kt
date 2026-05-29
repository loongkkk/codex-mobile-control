package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailTypingAnimatorSupportTest {

    @Test
    fun `latest animatable message returns newest assistant text row`() {
        val items = listOf(
            DetailConversationItem.MessageRow(message("user-1", ThreadMessageRole.USER, "需求")),
            DetailConversationItem.MessageRow(message("assistant-1", ThreadMessageRole.ASSISTANT, "第一段")),
            DetailConversationItem.MessageRow(message("assistant-2", ThreadMessageRole.ASSISTANT, "第二段"))
        )

        val latest = DetailTypingAnimatorSupport.latestAnimatableMessage(items)

        assertEquals("assistant-2", latest?.messageId)
    }

    @Test
    fun `latest animatable message stays idle when trailing row is user text`() {
        val items = listOf(
            DetailConversationItem.MessageRow(message("assistant-1", ThreadMessageRole.ASSISTANT, "上一条回复")),
            DetailConversationItem.MessageRow(message("user-1", ThreadMessageRole.USER, "我刚发送"))
        )

        val latest = DetailTypingAnimatorSupport.latestAnimatableMessage(items)

        assertNull(latest)
    }

    @Test
    fun `animation start index stays idle for first detail render`() {
        val startIndex = DetailTypingAnimatorSupport.animationStartIndex(
            previousLatestMessageKey = null,
            previousLatestText = null,
            currentMessageKey = "assistant|text|2026-05-02T10:00:00Z|first",
            currentLatestText = "当前最新回复",
            previousVisibleChars = 0,
            isAnimationActive = false
        )

        assertNull(startIndex)
    }

    @Test
    fun `animation start index resumes from previous visible chars when latest message grows`() {
        val startIndex = DetailTypingAnimatorSupport.animationStartIndex(
            previousLatestMessageKey = "assistant|text|2026-05-02T10:00:00Z|growing",
            previousLatestText = "当前最新",
            currentMessageKey = "assistant|text|2026-05-02T10:00:00Z|growing",
            currentLatestText = "当前最新回复内容",
            previousVisibleChars = 4,
            isAnimationActive = true
        )

        assertEquals(4, startIndex)
    }

    @Test
    fun `animation start index restarts from zero for a different latest reply`() {
        val startIndex = DetailTypingAnimatorSupport.animationStartIndex(
            previousLatestMessageKey = "assistant|text|2026-05-02T10:00:00Z|previous",
            previousLatestText = "上一条回复",
            currentMessageKey = "assistant|text|2026-05-02T10:00:01Z|next",
            currentLatestText = "全新的回复",
            previousVisibleChars = 5,
            isAnimationActive = false
        )

        assertEquals(0, startIndex)
    }

    @Test
    fun `animation start index stays idle when same latest reply already has no visible progress`() {
        val startIndex = DetailTypingAnimatorSupport.animationStartIndex(
            previousLatestMessageKey = "assistant|text|2026-05-02T10:00:00Z|already-rendered",
            previousLatestText = "已经完整展示的回复",
            currentMessageKey = "assistant|text|2026-05-02T10:00:00Z|already-rendered",
            currentLatestText = "已经完整展示的回复",
            previousVisibleChars = 0,
            isAnimationActive = false
        )

        assertNull(startIndex)
    }

    @Test
    fun `animation start index keeps zero when same latest reply is already animating`() {
        val startIndex = DetailTypingAnimatorSupport.animationStartIndex(
            previousLatestMessageKey = "assistant|text|2026-05-02T10:00:00Z|animating",
            previousLatestText = "正在输出的回复",
            currentMessageKey = "assistant|text|2026-05-02T10:00:00Z|animating",
            currentLatestText = "正在输出的回复",
            previousVisibleChars = 0,
            isAnimationActive = true
        )

        assertEquals(0, startIndex)
    }

    @Test
    fun `animation start index resumes when same logical reply gets a new message id`() {
        val startIndex = DetailTypingAnimatorSupport.animationStartIndex(
            previousLatestMessageKey = "assistant|text|2026-05-02T10:00:00Z|same-payload",
            previousLatestText = "同一条回复内容",
            currentMessageKey = "assistant|text|2026-05-02T10:00:00Z|same-payload",
            currentLatestText = "同一条回复内容",
            previousVisibleChars = 3,
            isAnimationActive = true
        )

        assertEquals(3, startIndex)
    }

    @Test
    fun `stable message key ignores message id for same assistant text payload`() {
        val left = message("preview:1", ThreadMessageRole.ASSISTANT, "同一条回复").copy(
            timestamp = "2026-05-02T10:00:00Z"
        )
        val right = message("preview:469", ThreadMessageRole.ASSISTANT, "同一条回复").copy(
            timestamp = "2026-05-02T10:00:00Z"
        )

        val leftKey = DetailTypingAnimatorSupport.stableMessageKey(left, left.text.orEmpty())
        val rightKey = DetailTypingAnimatorSupport.stableMessageKey(right, right.text.orEmpty())

        assertEquals(leftKey, rightKey)
    }

    private fun message(
        messageId: String,
        role: ThreadMessageRole,
        text: String
    ): ThreadMessage {
        return ThreadMessage(
            messageId = messageId,
            threadId = "thread-1",
            role = role,
            kind = "text",
            text = text,
            timestamp = "2026-05-02T10:00:00Z"
        )
    }
}
