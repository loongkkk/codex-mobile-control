package com.codex.mobilecontrol

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class MainActivityWindowInsetsTest {

    @Test
    fun `main activity applies status bar insets to task page content`() {
        val source = readSource()

        assertTrue(source.contains("WindowInsetsCompat.Type.statusBars()"))
        assertTrue(source.contains("taskPageContent"))
        assertTrue(source.contains("updatePadding("))
    }

    @Test
    fun `main activity keeps detail composer above ime and restores bottom navigation when ime closes`() {
        val source = readSource()
        val bottomNavBlock = source.substringAfter("private fun syncBottomNavForIme()")
            .substringBefore("private fun setupLists()")

        assertTrue(source.contains("WindowInsetsCompat.Type.ime()"))
        assertTrue(source.contains("windowInsets.isVisible(WindowInsetsCompat.Type.ime())"))
        assertTrue(source.contains("val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())"))
        assertTrue(source.contains("imeInsets.bottom > navigationBarInsets.bottom"))
        assertTrue(source.contains("val imeBottomInset = if (isImeVisibleFromInsets)"))
        assertTrue(source.contains("maxOf(0, imeInsets.bottom - navigationBarInsets.bottom)"))
        assertTrue(source.contains("private var isImeVisible = false"))
        assertTrue(source.contains("private var isImeVisibleFromInsets = false"))
        assertTrue(source.contains("private var isImeVisibleFromLayout = false"))
        assertTrue(source.contains("private fun updateImeVisibility("))
        assertTrue(source.contains("isImeVisibleFromInsets || isImeVisibleFromLayout"))
        assertTrue(source.contains("getWindowVisibleDisplayFrame"))
        assertTrue(source.contains("KEYBOARD_VISIBILITY_THRESHOLD_DP"))
        assertTrue(source.contains("private fun syncBottomNavForIme()"))
        assertTrue(source.contains("binding.messageInput.setOnFocusChangeListener"))
        assertTrue(source.contains("initialDetailBottomPadding"))
        assertTrue(source.contains("binding.detailPage.updatePadding("))
        assertTrue(source.contains("bottom = initialDetailBottomPadding + imeBottomInset"))
        assertTrue(source.contains("syncBottomNavForIme()"))
        assertTrue(bottomNavBlock.contains("currentScreen == ConnectedScreen.DETAIL && isImeVisible"))
        assertFalse(bottomNavBlock.contains("binding.messageInput.hasFocus()"))
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
