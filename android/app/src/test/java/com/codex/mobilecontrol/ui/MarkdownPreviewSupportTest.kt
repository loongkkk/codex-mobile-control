package com.codex.mobilecontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownPreviewSupportTest {
    @Test
    fun `preview path supports decoded markdown and json file links`() {
        assertEquals(
            "docs/next steps.md",
            MarkdownPreviewSupport.previewPathFor("docs/next%20steps.md", "ignored.md")
        )
        assertEquals(
            "D:/projects/codex-mobile-control/job.json",
            MarkdownPreviewSupport.previewPathFor("file:///D:/projects/codex-mobile-control/job.json", "job.json")
        )
        assertEquals(
            "D:/projects/codex-mobile-control/run-logs/frida_gopay_sso_consumer_180640.log",
            MarkdownPreviewSupport.previewPathFor(
                "file:///D:/projects/codex-mobile-control/run-logs/frida_gopay_sso_consumer_180640.log",
                "frida_gopay_sso_consumer_180640.log"
            )
        )
    }

    @Test
    fun `preview path strips editor line suffix from local document links`() {
        assertEquals(
            "D:/codex/phone_zhipin/docs/superpowers/specs/2026-05-08-boss-production-scheduler-design.md",
            MarkdownPreviewSupport.previewPathFor(
                "D:/codex/phone_zhipin/docs/superpowers/specs/2026-05-08-boss-production-scheduler-design.md:1",
                "生产调度器设计 (line 1)"
            )
        )
        assertEquals(
            "docs/boss-production-scheduler-test-plan.md",
            MarkdownPreviewSupport.previewPathFor(
                "docs/boss-production-scheduler-test-plan.md:42",
                "测试与验收计划 (line 42)"
            )
        )
        assertEquals(
            "D:/projects/codex-mobile-control/logs/dde_fetch_23af2e4_1800.txt",
            MarkdownPreviewSupport.previewPathFor(
                "D:/projects/codex-mobile-control/logs/dde_fetch_23af2e4_1800.txt:141",
                "dde_fetch_23af2e4_1800.txt (line 141)"
            )
        )
        assertEquals(
            "frida_gopay_sso_consumer_180640.log",
            MarkdownPreviewSupport.previewPathFor(
                "frida_gopay_sso_consumer_180640.log:321",
                "frida_gopay_sso_consumer_180640.log (line 321)"
            )
        )
    }

    @Test
    fun `preview path rejects unsupported schemes before falling back to label`() {
        assertNull(
            MarkdownPreviewSupport.previewPathFor("https://example.test/job.json", "job.json")
        )
    }

    @Test
    fun `preview path falls back to markdown label when url is empty`() {
        assertEquals(
            "NEXT_WINDOW_HANDOFF.md",
            MarkdownPreviewSupport.previewPathFor("", "`NEXT_WINDOW_HANDOFF.md`")
        )
    }

    @Test
    fun `json path detection ignores query and fragment`() {
        assertEquals(true, MarkdownPreviewSupport.isJsonPreviewPath("job.json?raw=1#L2"))
        assertEquals(false, MarkdownPreviewSupport.isJsonPreviewPath("notes.md"))
    }

    @Test
    fun `json preview formatter handles valid json and preserves invalid content`() {
        assertEquals(
            "{\"ok\": true}",
            MarkdownPreviewSupport.formatJsonPreviewContent("""{"ok":true}""")
        )
        assertEquals(
            "{not-json",
            MarkdownPreviewSupport.formatJsonPreviewContent("{not-json")
        )
    }
}
