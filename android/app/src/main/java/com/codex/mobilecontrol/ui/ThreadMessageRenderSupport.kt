package com.codex.mobilecontrol.ui

object ThreadMessageRenderSupport {
    private const val GATEWAY_UPLOADS_SEGMENT = "/api/uploads/"

    fun authorizationHeader(imageUrl: String?, token: String?): String? {
        val normalizedUrl = imageUrl?.trim().orEmpty()
        val normalizedToken = token?.trim().orEmpty()
        if (normalizedUrl.isBlank() || normalizedToken.isBlank()) {
            return null
        }

        if (!normalizedUrl.contains(GATEWAY_UPLOADS_SEGMENT, ignoreCase = true)) {
            return null
        }

        return "Bearer $normalizedToken"
    }
}
