package com.codex.mobilecontrol.ui

enum class ConnectedScreen {
    TASKS,
    DETAIL,
    PROFILE
}

data class ConnectedShellState(
    val showTaskPage: Boolean,
    val showDetailPage: Boolean,
    val showProfilePage: Boolean,
    val showComposer: Boolean,
    val clearComposerFocus: Boolean,
    val activeNav: ConnectedScreen
) {
    val showDetailContainer: Boolean
        get() = showDetailPage || showProfilePage

    companion object {
        fun forScreen(screen: ConnectedScreen): ConnectedShellState {
            return when (screen) {
                ConnectedScreen.TASKS -> ConnectedShellState(
                    showTaskPage = true,
                    showDetailPage = false,
                    showProfilePage = false,
                    showComposer = false,
                    clearComposerFocus = true,
                    activeNav = ConnectedScreen.TASKS
                )

                ConnectedScreen.DETAIL -> ConnectedShellState(
                    showTaskPage = false,
                    showDetailPage = true,
                    showProfilePage = false,
                    showComposer = true,
                    clearComposerFocus = false,
                    activeNav = ConnectedScreen.DETAIL
                )

                ConnectedScreen.PROFILE -> ConnectedShellState(
                    showTaskPage = false,
                    showDetailPage = false,
                    showProfilePage = true,
                    showComposer = false,
                    clearComposerFocus = true,
                    activeNav = ConnectedScreen.PROFILE
                )
            }
        }
    }
}
