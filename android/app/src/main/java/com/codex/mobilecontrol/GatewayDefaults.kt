package com.codex.mobilecontrol

object GatewayDefaults {
    const val BUILT_IN_DISPLAY_URL = ""
    const val BUILT_IN_URL = ""

    fun configForToken(token: String): GatewayConfig {
        return configFor(BUILT_IN_URL, token)
    }

    fun configFor(url: String, token: String): GatewayConfig {
        return GatewayConfig(
            url = normalizeGatewayUrl(url),
            token = token.trim()
        )
    }

    private fun normalizeGatewayUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return ""
        }
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        return "https://$trimmed"
    }
}
