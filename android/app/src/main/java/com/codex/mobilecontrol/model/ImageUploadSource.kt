package com.codex.mobilecontrol.model

import java.io.InputStream

data class ImageUploadSource(
    val fileName: String,
    val mimeType: String,
    val previewUri: String,
    val openStream: () -> InputStream
)

data class FileUploadSource(
    val fileName: String,
    val mimeType: String,
    val previewUri: String,
    val openStream: () -> InputStream
)
