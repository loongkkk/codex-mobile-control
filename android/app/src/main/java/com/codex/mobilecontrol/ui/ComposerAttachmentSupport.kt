package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.PendingFileDraft
import com.codex.mobilecontrol.model.PendingImageDraft

internal data class ComposerAttachmentPresentation(
    val imageCount: Int,
    val fileCount: Int
) {
    val totalCount: Int
        get() = imageCount + fileCount

    val hasAttachments: Boolean
        get() = totalCount > 0
}

internal object ComposerAttachmentSupport {
    fun presentation(
        pendingImages: List<PendingImageDraft>,
        pendingFiles: List<PendingFileDraft>
    ): ComposerAttachmentPresentation {
        return ComposerAttachmentPresentation(
            imageCount = pendingImages.size,
            fileCount = pendingFiles.size
        )
    }

    fun isCardVisible(
        showComposer: Boolean,
        pendingImages: List<PendingImageDraft>,
        pendingFiles: List<PendingFileDraft>
    ): Boolean {
        return showComposer && presentation(pendingImages, pendingFiles).hasAttachments
    }
}
