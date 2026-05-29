package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.ThreadListItem

sealed interface ThreadListRow {
    data class Section(
        val title: String
    ) : ThreadListRow

    data class Project(
        val cwd: String,
        val folderName: String,
        val parentName: String
    ) : ThreadListRow

    data class Thread(
        val thread: ThreadListItem,
        val isProjectChild: Boolean,
        val readSettledIndicatorKey: String? = null
    ) : ThreadListRow
}

object ThreadListDisplaySupport {
    fun buildRows(
        threads: List<ThreadListItem>,
        readSettledIndicatorKeys: Map<String, String?> = emptyMap()
    ): List<ThreadListRow> {
        val projectGroups = linkedMapOf<String, MutableList<ThreadListItem>>()
        val conversations = mutableListOf<ThreadListItem>()
        val pinnedThreads = threads.filter { it.isPinned }

        threads.filterNot { it.isPinned }.forEach { thread ->
            if (isConversationThread(thread.cwd)) {
                conversations += thread
            } else {
                projectGroups.getOrPut(normalizePath(thread.cwd)) { mutableListOf() } += thread
            }
        }

        val rows = mutableListOf<ThreadListRow>()
        if (pinnedThreads.isNotEmpty()) {
            rows += ThreadListRow.Section("置顶")
            rows += pinnedThreads.map { thread ->
                threadRow(thread = thread, isProjectChild = false, readSettledIndicatorKeys)
            }
        }

        if (projectGroups.isNotEmpty()) {
            rows += ThreadListRow.Section("项目")
            projectGroups.forEach { (cwd, groupThreads) ->
                rows += ThreadListRow.Project(
                    cwd = cwd,
                    folderName = folderName(cwd),
                    parentName = parentName(cwd)
                )
                rows += groupThreads.map { thread ->
                    threadRow(thread = thread, isProjectChild = true, readSettledIndicatorKeys)
                }
            }
        }

        if (conversations.isNotEmpty()) {
            rows += ThreadListRow.Section("对话")
            rows += conversations.map { thread ->
                threadRow(thread = thread, isProjectChild = false, readSettledIndicatorKeys)
            }
        }

        return rows
    }

    private fun threadRow(
        thread: ThreadListItem,
        isProjectChild: Boolean,
        readSettledIndicatorKeys: Map<String, String?>
    ) = ThreadListRow.Thread(
        thread = thread,
        isProjectChild = isProjectChild,
        readSettledIndicatorKey = readSettledIndicatorKeys[thread.threadId]
    )

    private fun isConversationThread(cwd: String): Boolean {
        val normalized = normalizePath(cwd).lowercase()
        return normalized.contains("/documents/codex/")
    }

    private fun normalizePath(value: String): String {
        return value
            .removePrefix("\\\\?\\")
            .replace('\\', '/')
            .trimEnd('/')
    }

    private fun folderName(cwd: String): String {
        return normalizePath(cwd).substringAfterLast('/').ifBlank { cwd }
    }

    private fun parentName(cwd: String): String {
        val normalized = normalizePath(cwd)
        return normalized.substringBeforeLast('/', "")
            .substringAfterLast('/')
            .ifBlank { "codex" }
    }
}
