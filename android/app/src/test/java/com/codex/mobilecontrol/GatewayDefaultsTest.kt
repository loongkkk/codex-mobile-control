package com.codex.mobilecontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayDefaultsTest {
    @Test
    fun builtInDisplayUrlIsBlankForOpenSourceBuilds() {
        assertEquals("", GatewayDefaults.BUILT_IN_DISPLAY_URL)
    }

    @Test
    fun configForTokenLeavesGatewayUrlBlankWhenNoDefaultIsConfigured() {
        val config = GatewayDefaults.configForToken("token")

        assertEquals("", config.url)
        assertEquals("token", config.token)
    }

    @Test
    fun configForTrimsCustomGatewayUrlAndToken() {
        val config = GatewayDefaults.configFor(" https://gateway.example.test/ ", " token ")

        assertEquals("https://gateway.example.test", config.url)
        assertEquals("token", config.token)
    }

    @Test
    fun configForAddsHttpsToBareGatewayHost() {
        val config = GatewayDefaults.configFor(" gateway.example.test/ ", " token ")

        assertEquals("https://gateway.example.test", config.url)
        assertEquals("token", config.token)
    }

    @Test
    fun configForPreservesExplicitHttpGatewayUrl() {
        val config = GatewayDefaults.configFor(" http://192.168.1.8:43124/ ", " token ")

        assertEquals("http://192.168.1.8:43124", config.url)
        assertEquals("token", config.token)
    }
}
