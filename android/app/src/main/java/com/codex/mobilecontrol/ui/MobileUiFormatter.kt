package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.MobileThreadStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object MobileUiFormatter {
    private const val MAX_REPO_CHIP_LENGTH = 16

    fun formatRelativeTime(timestamp: String?): String {
        val instant = parseInstant(timestamp) ?: return ""
        val duration = Duration.between(instant, Instant.now())
        val minutes = abs(duration.toMinutes())
        val hours = abs(duration.toHours())
        val days = abs(duration.toDays())

        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes} 分钟"
            hours < 24 -> "${hours} 小时"
            else -> "${days} 天"
        }
    }

    fun formatElapsed(timestamp: String?): String {
        val instant = parseInstant(timestamp) ?: return "--"
        val duration = Duration.between(instant, Instant.now()).abs()
        return formatDuration(duration)
    }

    fun formatDurationBetween(startTimestamp: String?, endTimestamp: String?): String {
        val start = parseInstant(startTimestamp) ?: return "--"
        val end = parseInstant(endTimestamp) ?: return "--"
        val duration = Duration.between(start, end).abs()
        return formatDuration(duration)
    }

    fun formatDurationMillis(durationMillis: Long): String {
        return formatDuration(Duration.ofMillis(durationMillis).abs())
    }

    fun formatWholeMinuteDurationMillis(durationMillis: Long): String {
        val duration = Duration.ofMillis(durationMillis).abs()
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60

        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${duration.toMinutes()}m"
        }
    }

    private fun formatDuration(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60
        val seconds = duration.seconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun formatDetailStatus(status: MobileThreadStatus?, timestamp: String?): String {
        val elapsed = formatElapsed(timestamp)
        val prefix = when (status) {
            MobileThreadStatus.RUNNING -> "正在处理"
            MobileThreadStatus.WAITING_INPUT -> "等待输入"
            MobileThreadStatus.ERROR -> "需要处理"
            MobileThreadStatus.IDLE -> "空闲"
            MobileThreadStatus.OFFLINE -> "离线"
            MobileThreadStatus.COMPLETED, null -> "已处理"
        }
        return "$prefix $elapsed"
    }

    fun formatThreadStatus(
        status: MobileThreadStatus?,
        progressSummary: String?
    ): String {
        val summary = progressSummary?.trim().orEmpty()
        if (summary.isNotBlank()) {
            return summary
        }

        return when (status) {
            MobileThreadStatus.RUNNING -> "正在处理新的请求"
            MobileThreadStatus.WAITING_INPUT -> "等待输入"
            MobileThreadStatus.ERROR -> "需要处理"
            MobileThreadStatus.IDLE -> "空闲"
            MobileThreadStatus.OFFLINE -> "离线"
            MobileThreadStatus.COMPLETED -> "本轮已完成"
            null -> "状态未知"
        }
    }

    fun formatRepoChip(cwd: String?, fallback: String): String {
        val rawValue = cwd
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.trim()
            .orEmpty()
        val normalizedValue = rawValue
            .replace(Regex("""^\d{4}-\d{2}-\d{2}-"""), "")
            .ifBlank { rawValue }
        val displayValue = if (normalizedValue.isBlank()) fallback else normalizedValue

        if (displayValue.length <= MAX_REPO_CHIP_LENGTH) {
            return displayValue
        }

        return displayValue
            .take(MAX_REPO_CHIP_LENGTH - 1)
            .trimEnd('-', '_', ' ')
            .ifBlank { displayValue.take(MAX_REPO_CHIP_LENGTH - 1) } + "…"
    }

    fun compactDetailPreview(value: String?, maxLength: Int = 120): String {
        val normalized = value
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

        if (normalized.isBlank()) {
            return ""
        }

        if (normalized.length <= maxLength) {
            return normalized
        }

        return normalized.take(maxLength).trimEnd() + "…"
    }

    fun formatMessageTimestamp(timestamp: String?): String {
        val instant = parseInstant(timestamp) ?: return timestamp.orEmpty()
        val zone = ZoneId.systemDefault()
        val now = Instant.now().atZone(zone)
        val messageTime = instant.atZone(zone)
        val formatter = if (messageTime.year == now.year) {
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        } else {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        }

        return messageTime.format(formatter)
    }

    fun formatQueuedMessageTime(queuedAtMillis: Long): String {
        return Instant.ofEpochMilli(queuedAtMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }

    private fun parseInstant(timestamp: String?): Instant? {
        return runCatching {
            timestamp?.takeIf { it.isNotBlank() }?.let(Instant::parse)
        }.getOrNull()
    }
}
