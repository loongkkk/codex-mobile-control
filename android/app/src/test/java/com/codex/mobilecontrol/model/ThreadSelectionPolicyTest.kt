package com.codex.mobilecontrol.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadSelectionPolicyTest {

    @Test
    fun prefersThreadIdFromIntentWhenItExists() {
        val threads = listOf(
            ThreadListItem(
                threadId = "thread-1",
                title = "First",
                cwd = "/tmp/a",
                status = MobileThreadStatus.IDLE,
                updatedAt = "2026-01-01T00:00:00Z",
                progressSummary = "idle",
                needsAttention = false
            ),
            ThreadListItem(
                threadId = "thread-2",
                title = "Second",
                cwd = "/tmp/b",
                status = MobileThreadStatus.RUNNING,
                updatedAt = "2026-01-01T00:00:01Z",
                progressSummary = "running",
                needsAttention = false
            )
        )

        val selected = ThreadSelectionPolicy.selectThreadId(
            requestedThreadId = "thread-2",
            lastOpenedThreadId = "thread-1",
            threads = threads
        )

        assertEquals("thread-2", selected)
    }

    @Test
    fun fallsBackToLastOpenedThreadWhenRequestIsMissing() {
        val threads = listOf(
            ThreadListItem(
                threadId = "thread-1",
                title = "First",
                cwd = "/tmp/a",
                status = MobileThreadStatus.IDLE,
                updatedAt = "2026-01-01T00:00:00Z",
                progressSummary = "idle",
                needsAttention = false
            ),
            ThreadListItem(
                threadId = "thread-2",
                title = "Second",
                cwd = "/tmp/b",
                status = MobileThreadStatus.RUNNING,
                updatedAt = "2026-01-01T00:00:01Z",
                progressSummary = "running",
                needsAttention = false
            )
        )

        val selected = ThreadSelectionPolicy.selectThreadId(
            requestedThreadId = null,
            lastOpenedThreadId = "thread-2",
            threads = threads
        )

        assertEquals("thread-2", selected)
    }

    @Test
    fun fallsBackToLastOpenedThreadWhenRequestedThreadIsMissing() {
        val threads = listOf(
            ThreadListItem(
                threadId = "thread-1",
                title = "First",
                cwd = "/tmp/a",
                status = MobileThreadStatus.IDLE,
                updatedAt = "2026-01-01T00:00:00Z",
                progressSummary = "idle",
                needsAttention = false
            ),
            ThreadListItem(
                threadId = "thread-2",
                title = "Second",
                cwd = "/tmp/b",
                status = MobileThreadStatus.RUNNING,
                updatedAt = "2026-01-01T00:00:01Z",
                progressSummary = "running",
                needsAttention = false
            )
        )

        val selected = ThreadSelectionPolicy.selectThreadId(
            requestedThreadId = "thread-9",
            lastOpenedThreadId = "thread-2",
            threads = threads
        )

        assertEquals("thread-2", selected)
    }

    @Test
    fun fallsBackToFirstThreadWhenSavedThreadIsMissing() {
        val threads = listOf(
            ThreadListItem(
                threadId = "thread-1",
                title = "First",
                cwd = "/tmp/a",
                status = MobileThreadStatus.IDLE,
                updatedAt = "2026-01-01T00:00:00Z",
                progressSummary = "idle",
                needsAttention = false
            ),
            ThreadListItem(
                threadId = "thread-2",
                title = "Second",
                cwd = "/tmp/b",
                status = MobileThreadStatus.RUNNING,
                updatedAt = "2026-01-01T00:00:01Z",
                progressSummary = "running",
                needsAttention = false
            )
        )

        val selected = ThreadSelectionPolicy.selectThreadId(
            requestedThreadId = null,
            lastOpenedThreadId = "thread-9",
            threads = threads
        )

        assertEquals("thread-1", selected)
    }

    @Test
    fun returnsNullWhenThereAreNoThreads() {
        val selected = ThreadSelectionPolicy.selectThreadId(
            requestedThreadId = "thread-1",
            lastOpenedThreadId = "thread-2",
            threads = emptyList()
        )

        assertNull(selected)
    }
}
