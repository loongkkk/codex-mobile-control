package com.codex.mobilecontrol

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityTaskListDiagnosticsTest {

    @Test
    fun `task list diagnostics capture navigation list refresh scroll and layout evidence`() {
        val source = readSource()

        assertTrue(source.contains("private data class TaskListDiagnosticSignature"))
        assertTrue(source.contains("private fun submitThreadsWithTaskDiagnostics("))
        assertTrue(source.contains("private fun logTaskListSnapshotAfterLayout("))
        assertTrue(source.contains("private fun logTaskListState("))
        assertTrue(source.contains("DebugTraceLogger.log(\"task-list\""))
        assertTrue(source.contains("binding.taskPage.setOnScrollChangeListener"))
        assertTrue(source.contains("binding.threadDrawerRecycler.addOnScrollListener"))
        assertTrue(source.contains("binding.threadDrawerRecycler.addOnLayoutChangeListener"))
        assertTrue(source.contains("\"nav.tasks.tap\""))
        assertTrue(source.contains("\"nav.profile.tap\""))
        assertTrue(source.contains("\"thread.tap\""))
        assertTrue(source.contains("\"task.refresh.tap\""))
    }

    private fun readSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/MainActivity.kt"),
            File("src/main/java/com/codex/mobilecontrol/MainActivity.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("MainActivity.kt not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }
}
