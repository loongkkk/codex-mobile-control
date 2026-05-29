package com.codex.mobilecontrol.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerAttachmentRowSupportSourceTest {
    @Test
    fun `pending attachment rows are built by support class`() {
        val mainActivity = sourceFile("MainActivity.kt").readText()
        val support = sourceFile("ui/ComposerAttachmentRowSupport.kt").readText()

        assertTrue(support.contains("object ComposerAttachmentRowSupport"))
        assertTrue(support.contains("fun imagePreviewRow("))
        assertTrue(support.contains("fun filePreviewRow("))
        assertTrue(support.contains("R.string.pending_image_preview"))
        assertTrue(support.contains("R.string.pending_file_preview"))
        assertTrue(support.contains("R.string.remove_pending_image"))
        assertTrue(support.contains("R.string.remove_pending_file"))
        assertTrue(support.contains("onPreview(draft)"))
        assertTrue(support.contains("onRemove(draft)"))

        assertTrue(mainActivity.contains("ComposerAttachmentRowSupport.imagePreviewRow("))
        assertTrue(mainActivity.contains("ComposerAttachmentRowSupport.filePreviewRow("))
        assertFalse(mainActivity.contains("private fun pendingImagePreviewRow("))
        assertFalse(mainActivity.contains("private fun pendingFilePreviewRow("))
    }

    private fun sourceFile(relativePath: String): File {
        return sequenceOf(
            File("app/src/main/java/com/codex/mobilecontrol/$relativePath"),
            File("src/main/java/com/codex/mobilecontrol/$relativePath")
        ).firstOrNull { it.isFile }
            ?: error("$relativePath not found from ${System.getProperty("user.dir")}")
    }
}
