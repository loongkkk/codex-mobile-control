package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadListItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadStatusIndicatorSupportTest {

    @Test
    fun `maps thread status to unified indicator state`() {
        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.RUNNING,
            ThreadStatusIndicatorSupport.stateFor(MobileThreadStatus.RUNNING)
        )
        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.SETTLED,
            ThreadStatusIndicatorSupport.stateFor(MobileThreadStatus.COMPLETED)
        )
        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.WAITING_INPUT,
            ThreadStatusIndicatorSupport.stateFor(MobileThreadStatus.WAITING_INPUT)
        )
        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.HIDDEN,
            ThreadStatusIndicatorSupport.stateFor(MobileThreadStatus.IDLE)
        )
        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.ERROR,
            ThreadStatusIndicatorSupport.stateFor(MobileThreadStatus.ERROR)
        )
        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.HIDDEN,
            ThreadStatusIndicatorSupport.stateFor(MobileThreadStatus.OFFLINE)
        )
    }

    @Test
    fun `settled indicator behaves like unread state until current settled key is read`() {
        val completed = thread(status = MobileThreadStatus.COMPLETED, updatedAt = "2026-05-11T12:00:00Z")
        val completedKey = ThreadStatusIndicatorSupport.settledReadKey(completed)

        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.SETTLED,
            ThreadStatusIndicatorSupport.visibleStateFor(completed, readSettledKey = null)
        )
        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.HIDDEN,
            ThreadStatusIndicatorSupport.visibleStateFor(completed, readSettledKey = completedKey)
        )
        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.SETTLED,
            ThreadStatusIndicatorSupport.visibleStateFor(
                completed.copy(updatedAt = "2026-05-11T12:05:00Z"),
                readSettledKey = completedKey
            )
        )
        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.RUNNING,
            ThreadStatusIndicatorSupport.visibleStateFor(
                completed.copy(status = MobileThreadStatus.RUNNING),
                readSettledKey = completedKey
            )
        )
    }

    @Test
    fun `active automation shows blue running indicator even after completed state is read`() {
        val scheduled = thread(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2026-05-11T12:00:00Z",
            automationActive = true
        )

        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.RUNNING,
            ThreadStatusIndicatorSupport.visibleStateFor(
                scheduled,
                readSettledKey = ThreadStatusIndicatorSupport.settledReadKey(scheduled)
            )
        )
        assertEquals(null, ThreadStatusIndicatorSupport.settledReadKey(scheduled))
    }

    @Test
    fun `attention states keep visible pulsing indicators and are not marked read`() {
        val waiting = thread(
            status = MobileThreadStatus.WAITING_INPUT,
            updatedAt = "2026-05-11T12:00:00Z"
        )
        val error = waiting.copy(status = MobileThreadStatus.ERROR)

        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.WAITING_INPUT,
            ThreadStatusIndicatorSupport.visibleStateFor(waiting, readSettledKey = "ignored")
        )
        assertEquals(null, ThreadStatusIndicatorSupport.settledReadKey(waiting))
        assertEquals(
            ThreadStatusIndicatorSupport.IndicatorState.ERROR,
            ThreadStatusIndicatorSupport.visibleStateFor(error, readSettledKey = "ignored")
        )
        assertEquals(null, ThreadStatusIndicatorSupport.settledReadKey(error))
    }

    private fun thread(
        status: MobileThreadStatus,
        updatedAt: String,
        automationActive: Boolean = false
    ) = ThreadListItem(
        threadId = "thread-1",
        title = "Thread",
        cwd = "D:\\code\\sample",
        status = status,
        updatedAt = updatedAt,
        progressSummary = "",
        needsAttention = false,
        automationActive = automationActive
    )
}
