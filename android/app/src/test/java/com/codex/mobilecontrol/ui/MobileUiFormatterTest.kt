package com.codex.mobilecontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codex.mobilecontrol.model.MobileThreadStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MobileUiFormatterTest {

    @Test
    fun `compact detail preview collapses whitespace and newlines`() {
        val value = "第一行  \n\n  第二行\t\t第三行"

        val result = MobileUiFormatter.compactDetailPreview(value, maxLength = 40)

        assertEquals("第一行 第二行 第三行", result)
    }

    @Test
    fun `compact detail preview truncates overly long content`() {
        val value = buildString {
            append("事件：")
            repeat(60) { append("很长的调试输出") }
        }

        val result = MobileUiFormatter.compactDetailPreview(value, maxLength = 48)

        assertTrue(result.length <= 49)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `format message timestamp uses compact local date time with seconds`() {
        val timestamp = "2026-04-24T17:20:00Z"
        val expected = Instant.parse(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))

        val result = MobileUiFormatter.formatMessageTimestamp(timestamp)

        assertEquals(expected, result)
    }

    @Test
    fun `format queued message time uses local clock time with seconds`() {
        val queuedAtMillis = Instant.parse("2026-05-09T02:03:04Z").toEpochMilli()
        val expected = Instant.ofEpochMilli(queuedAtMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        val result = MobileUiFormatter.formatQueuedMessageTime(queuedAtMillis)

        assertEquals(expected, result)
    }

    @Test
    fun `format repo chip strips date prefixes and truncates long folder names`() {
        val result = MobileUiFormatter.formatRepoChip(
            cwd = "C:\\Users\\devuser\\Documents\\Codex\\2026-04-23-files-mentioned-by-the-user-pdf",
            fallback = "codex"
        )

        assertEquals("files-mentioned…", result)
    }

    @Test
    fun `format detail status names running threads explicitly`() {
        val timestamp = Instant.now().minusSeconds(46).toString()

        val result = MobileUiFormatter.formatDetailStatus(MobileThreadStatus.RUNNING, timestamp)

        assertTrue(result.startsWith("正在处理 "))
    }

    @Test
    fun `format whole minute duration omits seconds`() {
        assertEquals("0m", MobileUiFormatter.formatWholeMinuteDurationMillis(0L))
        assertEquals("2m", MobileUiFormatter.formatWholeMinuteDurationMillis(120_000L))
        assertEquals("1h 30m", MobileUiFormatter.formatWholeMinuteDurationMillis(5_400_000L))
    }

    @Test
    fun `format thread status prefers live progress summary`() {
        val result = MobileUiFormatter.formatThreadStatus(
            status = MobileThreadStatus.RUNNING,
            progressSummary = "正在处理新的请求"
        )

        assertEquals("正在处理新的请求", result)
    }
}
