package com.codex.mobilecontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityThreadListBindingTest {

    @Test
    fun `main activity submits raw threads through grouped desktop list support`() {
        val source = readMainActivity()

        assertTrue(source.contains("threadAdapter.submitThreads(state.threads, readThreadStatusIndicatorKeys)"))
        assertFalse(source.contains("threadAdapter.submitList(state.threads)"))
    }

    @Test
    fun `task thread list disables default item change animations`() {
        val source = readMainActivity()
        val setupListsBlock = source.substringAfter("private fun setupLists()")
            .substringBefore("private fun setupActions()")

        assertTrue(setupListsBlock.contains("binding.threadDrawerRecycler.itemAnimator = null"))
    }

    private fun readMainActivity(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/MainActivity.kt"),
            File("src/main/java/com/codex/mobilecontrol/MainActivity.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("MainActivity.kt not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }
}
