package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadListItem

internal object ThreadStatusIndicatorSupport {
    enum class IndicatorState {
        HIDDEN,
        RUNNING,
        WAITING_INPUT,
        ERROR,
        SETTLED
    }

    fun stateFor(status: MobileThreadStatus): IndicatorState {
        return when (status) {
            MobileThreadStatus.RUNNING -> IndicatorState.RUNNING
            MobileThreadStatus.WAITING_INPUT -> IndicatorState.WAITING_INPUT
            MobileThreadStatus.ERROR -> IndicatorState.ERROR
            MobileThreadStatus.COMPLETED -> IndicatorState.SETTLED
            MobileThreadStatus.IDLE,
            MobileThreadStatus.OFFLINE -> IndicatorState.HIDDEN
        }
    }

    fun stateFor(thread: ThreadListItem): IndicatorState {
        val statusState = stateFor(thread.status)
        if (statusState == IndicatorState.HIDDEN || statusState == IndicatorState.SETTLED) {
            return if (thread.automationActive) IndicatorState.RUNNING else statusState
        }
        return statusState
    }

    fun settledReadKey(thread: ThreadListItem): String? {
        if (stateFor(thread) != IndicatorState.SETTLED) {
            return null
        }
        return "${thread.status.wireValue}:${thread.updatedAt}"
    }

    fun visibleStateFor(
        thread: ThreadListItem,
        readSettledKey: String?
    ): IndicatorState {
        val state = stateFor(thread)
        if (state != IndicatorState.SETTLED) {
            return state
        }
        return if (settledReadKey(thread) == readSettledKey) {
            IndicatorState.HIDDEN
        } else {
            IndicatorState.SETTLED
        }
    }
}
