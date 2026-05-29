package com.codex.mobilecontrol

object DetailNotificationIds {
    const val SUMMARY_NOTIFICATION_ID = 31030
    const val SUMMARY_CONTENT_INTENT_REQUEST_CODE = 31031
    const val THREAD_NOTIFICATION_ID = 31032
    const val GROUP_KEY = "codex_mobile_threads"
    const val ALERT_CHANNEL_ID = "codex_mobile_detail_alerts"
    const val COMPLETION_ALERT_CHANNEL_ID = "codex_mobile_thread_completion_alerts"

    fun notificationTagForThread(threadId: String): String {
        return "thread:$threadId"
    }

    fun threadContentIntentRequestCode(threadId: String): Int {
        return 32000 + (threadId.hashCode() and 0x3FFF)
    }
}
