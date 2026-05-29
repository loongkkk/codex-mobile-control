package com.codex.mobilecontrol.model

object ThreadSelectionPolicy {
    fun selectThreadId(
        requestedThreadId: String?,
        lastOpenedThreadId: String?,
        threads: List<ThreadListItem>
    ): String? {
        if (threads.isEmpty()) {
            return null
        }

        val threadIds = threads.map { it.threadId }.toSet()

        if (!requestedThreadId.isNullOrBlank() && threadIds.contains(requestedThreadId)) {
            return requestedThreadId
        }

        if (!lastOpenedThreadId.isNullOrBlank() && threadIds.contains(lastOpenedThreadId)) {
            return lastOpenedThreadId
        }

        return threads.firstOrNull()?.threadId
    }
}
