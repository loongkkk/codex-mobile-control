package com.codex.mobilecontrol

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityConversationTextWidthTest {

    @Test
    fun `conversation text width keeps enough edge buffer for cjk glyphs`() {
        val source = readSource()
        val match = Regex("""CONVERSATION_TEXT_WIDTH_BUFFER_DP\s*=\s*(\d+)""").find(source)
            ?: error("CONVERSATION_TEXT_WIDTH_BUFFER_DP was not found")
        val bufferDp = match.groupValues[1].toInt()

        assertTrue(
            "Expected conversation text width buffer to be at least 6dp, got ${bufferDp}dp",
            bufferDp >= 6
        )
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
