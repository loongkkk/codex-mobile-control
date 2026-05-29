package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadDetail

internal object ComposerSendModeSupport {
    fun shouldConfirmRunningTextSend(
        text: String,
        hasPendingAttachments: Boolean,
        detail: ThreadDetail?
    ): Boolean {
        if (text.isBlank() || hasPendingAttachments || detail == null) {
            return false
        }
        return isRunningSendBlocked(detail)
    }

    fun isRunningSendBlocked(detail: ThreadDetail): Boolean {
        if (detail.thread.status == MobileThreadStatus.RUNNING) {
            return true
        }
        if (detail.thread.status == MobileThreadStatus.OFFLINE) {
            return false
        }
        if (!detail.sendAvailable) {
            val reason = detail.sendDisabledReason.orEmpty()
            return reason.contains("线程仍在运行") ||
                reason.contains("线程正在处理") ||
                reason.contains("正在处理中") ||
                reason.contains("正在等待线程确认")
        }
        return false
    }
}
