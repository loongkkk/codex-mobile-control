package com.codex.mobilecontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedShellStateTest {

    @Test
    fun `profile screen activates profile tab and hides composer`() {
        val state = ConnectedShellState.forScreen(ConnectedScreen.PROFILE)

        assertFalse(state.showTaskPage)
        assertFalse(state.showDetailPage)
        assertTrue(state.showDetailContainer)
        assertTrue(state.showProfilePage)
        assertFalse(state.showComposer)
        assertTrue(state.clearComposerFocus)
        assertEquals(ConnectedScreen.PROFILE, state.activeNav)
    }

    @Test
    fun `detail screen keeps detail page and composer visible`() {
        val state = ConnectedShellState.forScreen(ConnectedScreen.DETAIL)

        assertFalse(state.showTaskPage)
        assertTrue(state.showDetailPage)
        assertTrue(state.showDetailContainer)
        assertFalse(state.showProfilePage)
        assertTrue(state.showComposer)
        assertFalse(state.clearComposerFocus)
        assertEquals(ConnectedScreen.DETAIL, state.activeNav)
    }

    @Test
    fun `task screen returns to task list without composer`() {
        val state = ConnectedShellState.forScreen(ConnectedScreen.TASKS)

        assertTrue(state.showTaskPage)
        assertFalse(state.showDetailPage)
        assertFalse(state.showDetailContainer)
        assertFalse(state.showProfilePage)
        assertFalse(state.showComposer)
        assertTrue(state.clearComposerFocus)
        assertEquals(ConnectedScreen.TASKS, state.activeNav)
    }
}
