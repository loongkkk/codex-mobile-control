package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.PendingFileDraft
import com.codex.mobilecontrol.model.PendingImageDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerAttachmentSupportTest {
    @Test
    fun `summarizes image and file drafts`() {
        val presentation = ComposerAttachmentSupport.presentation(
            pendingImages = listOf(imageDraft("a.png"), imageDraft("b.png")),
            pendingFiles = listOf(fileDraft("job.json"))
        )

        assertEquals(2, presentation.imageCount)
        assertEquals(1, presentation.fileCount)
        assertEquals(3, presentation.totalCount)
        assertTrue(presentation.hasAttachments)
    }

    @Test
    fun `empty drafts have no attachments`() {
        val presentation = ComposerAttachmentSupport.presentation(
            pendingImages = emptyList(),
            pendingFiles = emptyList()
        )

        assertEquals(0, presentation.totalCount)
        assertFalse(presentation.hasAttachments)
    }

    @Test
    fun `attachment card is visible only when composer is visible and drafts exist`() {
        val image = imageDraft("a.png")

        assertTrue(
            ComposerAttachmentSupport.isCardVisible(
                showComposer = true,
                pendingImages = listOf(image),
                pendingFiles = emptyList()
            )
        )
        assertFalse(
            ComposerAttachmentSupport.isCardVisible(
                showComposer = false,
                pendingImages = listOf(image),
                pendingFiles = emptyList()
            )
        )
        assertFalse(
            ComposerAttachmentSupport.isCardVisible(
                showComposer = true,
                pendingImages = emptyList(),
                pendingFiles = emptyList()
            )
        )
    }

    private fun imageDraft(fileName: String) = PendingImageDraft(
        previewUri = "content://images/$fileName",
        fileName = fileName,
        mimeType = "image/png"
    )

    private fun fileDraft(fileName: String) = PendingFileDraft(
        previewUri = "content://files/$fileName",
        fileName = fileName,
        mimeType = "application/json"
    )
}
