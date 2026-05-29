package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.R
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.QueuedTextMessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerStatusSupportTest {
    @Test
    fun `queued status is actionable and uses normalized preview`() {
        val queued = queuedMessage(
            text = "  第一行\n第二行   第三行，继续补充一段很长很长的排队消息内容  "
        )

        val presentation = ComposerStatusSupport.presentation(
            queuedTextMessages = listOf(queued),
            isPendingDetail = false,
            sendDisabledReason = null,
            errorMessage = null
        )

        assertEquals(
            ComposerStatusContent.Queued(
                preview = "第一行 第二行 第三行，继续补充一段很长很长的排队消息内容",
                count = 1,
                status = QueuedTextMessageStatus.PENDING
            ),
            presentation.content
        )
        assertEquals(R.color.dashboard_nav_active, presentation.colorResId)
        assertTrue(presentation.isActionable)
    }

    @Test
    fun `queued preview is truncated after normalized text`() {
        val preview = ComposerStatusSupport.queuedTextPreview(
            "1234567890 1234567890 1234567890 1234567890"
        )

        assertEquals("1234567890 1234567890 1234567890 12...", preview)
    }

    @Test
    fun `pending detail and disabled states keep previous colors`() {
        assertEquals(
            ComposerStatusPresentation(
                content = ComposerStatusContent.Loading,
                colorResId = R.color.dashboard_nav_active,
                isActionable = false
            ),
            ComposerStatusSupport.presentation(
                queuedTextMessages = emptyList(),
                isPendingDetail = true,
                sendDisabledReason = null,
                errorMessage = null
            )
        )
        assertEquals(
            ComposerStatusPresentation(
                content = ComposerStatusContent.Disabled("线程仍在运行"),
                colorResId = R.color.login_error_text,
                isActionable = false
            ),
            ComposerStatusSupport.presentation(
                queuedTextMessages = emptyList(),
                isPendingDetail = false,
                sendDisabledReason = "线程仍在运行",
                errorMessage = null
            )
        )
    }

    @Test
    fun `status row remains visible only for the selected queued thread`() {
        val queued = queuedMessage()

        assertTrue(
            ComposerStatusSupport.isStatusVisible(
                showComposer = true,
                queuedTextMessages = listOf(queued),
                selectedThreadId = "thread-1"
            )
        )
        assertFalse(
            ComposerStatusSupport.isStatusVisible(
                showComposer = true,
                queuedTextMessages = listOf(queued),
                selectedThreadId = "thread-2"
            )
        )
        assertFalse(
            ComposerStatusSupport.isStatusVisible(
                showComposer = false,
                queuedTextMessages = listOf(queued),
                selectedThreadId = "thread-1"
            )
        )
    }

    @Test
    fun `queued status summarizes multiple messages and failed state`() {
        val first = queuedMessage(text = "第一条", status = QueuedTextMessageStatus.FAILED)
        val second = queuedMessage(text = "第二条", id = "queue-2")

        val presentation = ComposerStatusSupport.presentation(
            queuedTextMessages = listOf(first, second),
            isPendingDetail = false,
            sendDisabledReason = null,
            errorMessage = null
        )

        assertEquals(
            ComposerStatusContent.Queued(
                preview = "第一条",
                count = 2,
                status = QueuedTextMessageStatus.FAILED
            ),
            presentation.content
        )
        assertEquals(R.color.login_error_text, presentation.colorResId)
        assertTrue(presentation.isActionable)
    }

    private fun queuedMessage(
        text: String = "继续优化",
        id: String = "queue-1",
        status: QueuedTextMessageStatus = QueuedTextMessageStatus.PENDING
    ) = QueuedTextMessage(
        threadId = "thread-1",
        text = text,
        queuedAtMillis = 12L,
        blockedThreadUpdatedAt = "2026-05-08T00:00:00Z",
        queueId = id,
        status = status
    )
}
