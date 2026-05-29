package com.codex.mobilecontrol.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDialogSupportSourceTest {
    @Test
    fun `main activity delegates unified dialog construction to support class`() {
        val mainActivity = sourceFile("MainActivity.kt").readText()
        val support = sourceFile("ui/UnifiedDialogSupport.kt").readText()

        assertTrue(support.contains("object UnifiedDialogSupport"))
        assertTrue(support.contains("fun panel("))
        assertTrue(support.contains("fun row("))
        assertTrue(support.contains("fun pillSwitch("))
        assertTrue(support.contains("fun footerButton("))
        assertTrue(support.contains("clickable: Boolean = true"))
        assertTrue(support.contains("isClickable = clickable"))
        assertTrue(support.contains("if (clickable) {"))
        assertTrue(mainActivity.contains("UnifiedDialogSupport.panel("))
        assertTrue(mainActivity.contains("UnifiedDialogSupport.row("))
        assertTrue(mainActivity.contains("UnifiedDialogSupport.pillSwitch("))
        assertFalse(mainActivity.contains("fun trackDrawable(isChecked: Boolean)"))
    }

    private fun sourceFile(relativePath: String): File {
        return sequenceOf(
            File("app/src/main/java/com/codex/mobilecontrol/$relativePath"),
            File("src/main/java/com/codex/mobilecontrol/$relativePath")
        ).firstOrNull { it.isFile }
            ?: error("$relativePath not found from ${System.getProperty("user.dir")}")
    }
}
