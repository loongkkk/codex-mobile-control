package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadListItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSendModeSupportTest {
    @Test
    fun `running text without attachments asks for send mode`() {
        assertTrue(
            ComposerSendModeSupport.shouldConfirmRunningTextSend(
                text = "继续优化",
                hasPendingAttachments = false,
                detail = detail(status = MobileThreadStatus.RUNNING, sendAvailable = false)
            )
        )
    }

    @Test
    fun `blank text attachments and missing detail do not ask for send mode`() {
        val runningDetail = detail(status = MobileThreadStatus.RUNNING, sendAvailable = false)

        assertFalse(
            ComposerSendModeSupport.shouldConfirmRunningTextSend(
                text = "   ",
                hasPendingAttachments = false,
                detail = runningDetail
            )
        )
        assertFalse(
            ComposerSendModeSupport.shouldConfirmRunningTextSend(
                text = "继续优化",
                hasPendingAttachments = true,
                detail = runningDetail
            )
        )
        assertFalse(
            ComposerSendModeSupport.shouldConfirmRunningTextSend(
                text = "继续优化",
                hasPendingAttachments = false,
                detail = null
            )
        )
    }

    @Test
    fun `offline and sendable details do not ask for send mode`() {
        assertFalse(
            ComposerSendModeSupport.shouldConfirmRunningTextSend(
                text = "继续优化",
                hasPendingAttachments = false,
                detail = detail(status = MobileThreadStatus.OFFLINE, sendAvailable = false)
            )
        )
        assertFalse(
            ComposerSendModeSupport.shouldConfirmRunningTextSend(
                text = "继续优化",
                hasPendingAttachments = false,
                detail = detail(status = MobileThreadStatus.COMPLETED, sendAvailable = true)
            )
        )
    }

    @Test
    fun `blocked non-running detail asks only for running reasons`() {
        assertTrue(
            ComposerSendModeSupport.shouldConfirmRunningTextSend(
                text = "继续优化",
                hasPendingAttachments = false,
                detail = detail(
                    status = MobileThreadStatus.COMPLETED,
                    sendAvailable = false,
                    sendDisabledReason = "消息已发到桌面，正在等待线程确认"
                )
            )
        )
        assertFalse(
            ComposerSendModeSupport.shouldConfirmRunningTextSend(
                text = "继续优化",
                hasPendingAttachments = false,
                detail = detail(
                    status = MobileThreadStatus.COMPLETED,
                    sendAvailable = false,
                    sendDisabledReason = "token_invalid"
                )
            )
        )
    }

    private fun detail(
        status: MobileThreadStatus,
        sendAvailable: Boolean,
        sendDisabledReason: String? = null
    ): ThreadDetail {
        return ThreadDetail(
            thread = ThreadListItem(
                threadId = "thread-1",
                title = "线程",
                cwd = "/tmp",
                status = status,
                updatedAt = "2026-05-08T00:00:00Z",
                progressSummary = "状态",
                needsAttention = false
            ),
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = sendAvailable,
            sendDisabledReason = sendDisabledReason
        )
    }
}
