package com.codex.mobilecontrol

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityTaskListJumpFixTest {

    @Test
    fun `returning to task screen restores task page top after layout without resetting same tab`() {
        val source = readSource()
        val showTaskBlock = source.substringAfter("private fun showTaskScreen()")
            .substringBefore("private fun showProfileScreen()")
        val restoreBlock = source.substringAfter("private fun restoreTaskPageTopAfterLayout(")
            .substringBefore("private fun showProfileScreen()")

        assertTrue(showTaskBlock.contains("val wasTaskScreen = currentScreen == ConnectedScreen.TASKS"))
        assertTrue(showTaskBlock.contains("if (!wasTaskScreen)"))
        assertTrue(showTaskBlock.contains("restoreTaskPageTopAfterLayout("))
        assertTrue(restoreBlock.contains("binding.taskPage.requestFocus()"))
        assertTrue(restoreBlock.contains("binding.taskPage.scrollTo(0, 0)"))
        assertTrue(restoreBlock.contains("binding.taskPage.post"))
        assertTrue(restoreBlock.contains("binding.taskPage.postDelayed"))
        assertTrue(restoreBlock.contains("\"task.restoreTop\""))
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
