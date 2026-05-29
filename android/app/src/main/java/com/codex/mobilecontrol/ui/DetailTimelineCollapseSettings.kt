package com.codex.mobilecontrol.ui

data class DetailTimelineCollapseSettings(
    val minProcessMessageCount: Int = DEFAULT_MIN_PROCESS_MESSAGE_COUNT,
    val minProcessDurationMillis: Long = DEFAULT_MIN_PROCESS_DURATION_MILLIS
) {
    companion object {
        const val DEFAULT_MIN_PROCESS_MESSAGE_COUNT = 3
        const val DEFAULT_MIN_PROCESS_DURATION_MILLIS = 2 * 60 * 1000L
    }
}
