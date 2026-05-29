package com.codex.mobilecontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadMessageRenderSupportTest {

    @Test
    fun `gateway upload image requests carry bearer auth`() {
        val header = ThreadMessageRenderSupport.authorizationHeader(
            imageUrl = "https://gateway.example.test/api/uploads/thread-1/demo.png",
            token = "abc123"
        )

        assertEquals("Bearer abc123", header)
    }

    @Test
    fun `non gateway images skip bearer auth`() {
        val header = ThreadMessageRenderSupport.authorizationHeader(
            imageUrl = "https://example.com/demo.png",
            token = "abc123"
        )

        assertNull(header)
    }
}
