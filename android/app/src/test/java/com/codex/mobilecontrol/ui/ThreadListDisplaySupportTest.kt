package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadListItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadListDisplaySupportTest {

    @Test
    fun `buildRows groups desktop threads by project folder and keeps document codex threads as conversations`() {
        val rows = ThreadListDisplaySupport.buildRows(
            listOf(
                thread(
                    id = "app-2",
                    title = "Codex 安卓 App 2.0",
                    cwd = "D:\\projects\\codex-mobile-control",
                    updatedAt = "2026-04-26T12:00:00Z"
                ),
                thread(
                    id = "chat-1",
                    title = "模拟",
                    cwd = "C:\\Users\\devuser\\Documents\\Codex\\2026-04-23-files-mentioned-by-the-user-pdf",
                    updatedAt = "2026-04-26T11:00:00Z"
                ),
                thread(
                    id = "app-1",
                    title = "Codex 安卓 App 1.0",
                    cwd = "D:\\projects\\codex-mobile-control",
                    updatedAt = "2026-04-22T12:00:00Z"
                ),
                thread(
                    id = "hook",
                    title = "研究 WeChat-Hook 项目",
                    cwd = "D:\\codex\\weixin_bot",
                    updatedAt = "2026-04-22T10:00:00Z"
                )
            )
        )

        assertEquals(
            listOf(
                "section:项目",
                "project:codex-mobile-control:projects",
                "thread:Codex 安卓 App 2.0:child",
                "thread:Codex 安卓 App 1.0:child",
                "project:weixin_bot:codex",
                "thread:研究 WeChat-Hook 项目:child",
                "section:对话",
                "thread:模拟:chat"
            ),
            rows.map { row ->
                when (row) {
                    is ThreadListRow.Section -> "section:${row.title}"
                    is ThreadListRow.Project -> "project:${row.folderName}:${row.parentName}"
                    is ThreadListRow.Thread -> {
                        val kind = if (row.isProjectChild) "child" else "chat"
                        "thread:${row.thread.title}:$kind"
                    }
                }
            }
        )
    }

    @Test
    fun `buildRows places pinned threads in their own section and removes duplicates from project and conversation groups`() {
        val rows = ThreadListDisplaySupport.buildRows(
            listOf(
                thread(
                    id = "pinned-project",
                    title = "Codex 安卓 App 5.0",
                    cwd = "D:\\projects\\codex-mobile-control",
                    updatedAt = "2026-05-07T09:00:00Z",
                    isPinned = true
                ),
                thread(
                    id = "normal-project",
                    title = "普通项目线程",
                    cwd = "D:\\projects\\codex-mobile-control",
                    updatedAt = "2026-05-07T08:00:00Z"
                ),
                thread(
                    id = "pinned-chat",
                    title = "分析项目功能和代码",
                    cwd = "C:\\Users\\devuser\\Documents\\Codex\\analysis",
                    updatedAt = "2026-05-07T07:00:00Z",
                    isPinned = true
                ),
                thread(
                    id = "normal-chat",
                    title = "普通对话",
                    cwd = "C:\\Users\\devuser\\Documents\\Codex\\chat",
                    updatedAt = "2026-05-07T06:00:00Z"
                )
            )
        )

        assertEquals(
            listOf(
                "section:置顶",
                "thread:Codex 安卓 App 5.0:pinned",
                "thread:分析项目功能和代码:pinned",
                "section:项目",
                "project:codex-mobile-control:projects",
                "thread:普通项目线程:child",
                "section:对话",
                "thread:普通对话:chat"
            ),
            rows.map { row ->
                when (row) {
                    is ThreadListRow.Section -> "section:${row.title}"
                    is ThreadListRow.Project -> "project:${row.folderName}:${row.parentName}"
                    is ThreadListRow.Thread -> {
                        val kind = when {
                            row.thread.isPinned -> "pinned"
                            row.isProjectChild -> "child"
                            else -> "chat"
                        }
                        "thread:${row.thread.title}:$kind"
                    }
                }
            }
        )
    }

    private fun thread(
        id: String,
        title: String,
        cwd: String,
        updatedAt: String,
        isPinned: Boolean = false
    ): ThreadListItem {
        return ThreadListItem(
            threadId = id,
            title = title,
            cwd = cwd,
            status = MobileThreadStatus.IDLE,
            updatedAt = updatedAt,
            progressSummary = title,
            needsAttention = false,
            isPinned = isPinned
        )
    }
}
