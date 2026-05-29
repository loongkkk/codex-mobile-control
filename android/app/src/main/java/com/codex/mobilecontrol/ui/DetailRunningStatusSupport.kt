package com.codex.mobilecontrol.ui

import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadListItem
import java.time.Instant

object DetailRunningStatusSupport {
    fun shouldRefreshSubtitle(detailThread: ThreadListItem?, detail: ThreadDetail?): Boolean {
        return detailThread?.status == MobileThreadStatus.RUNNING &&
            (detailThread.runningStartedAt?.isNotBlank() == true || runningStartEvent(detail) != null)
    }

    fun subtitle(
        detailThread: ThreadListItem?,
        detail: ThreadDetail?,
        now: Instant = Instant.now()
    ): String {
        val base = MobileUiFormatter.formatThreadStatus(
            detailThread?.status,
            detailThread?.progressSummary
        )
        if (detailThread?.status != MobileThreadStatus.RUNNING || base.contains("已运行")) {
            return base
        }

        detailThread.runningStartedAt?.takeIf { it.isNotBlank() }?.let { runningStartedAt ->
            val duration = MobileUiFormatter
                .formatDurationBetween(runningStartedAt, now.toString())
                .takeUnless { it == "--" }
                ?: return base
            return "$base · 已运行 $duration"
        }

        val runningEvent = runningStartEvent(detail) ?: return base
        val completionEvent = latestIndexedEvent(detail, ::isCompletionEvent)
        if (completionEvent != null && eventOccursAfter(completionEvent, runningEvent)) {
            return base
        }

        val duration = MobileUiFormatter
            .formatDurationBetween(runningEvent.event.timestamp, now.toString())
            .takeUnless { it == "--" }
            ?: return base
        return "$base · 已运行 $duration"
    }

    private fun runningStartEvent(detail: ThreadDetail?): IndexedEvent? {
        val runningEvents = indexedEvents(detail, ::isRunningEvent)
        if (runningEvents.isEmpty()) {
            return null
        }
        val latestCompletion = latestIndexedEvent(detail, ::isCompletionEvent)
        val activeRunningEvents = runningEvents.filter { runningEvent ->
            latestCompletion == null || eventOccursAfter(runningEvent, latestCompletion)
        }
        if (activeRunningEvents.isEmpty()) {
            return null
        }

        return activeRunningEvents.firstOrNull { isRealTurnStartEvent(it.event) }
            ?: activeRunningEvents.firstOrNull { !isOptimisticRunningEvent(it.event) }
            ?: activeRunningEvents.first()
    }

    private fun latestIndexedEvent(
        detail: ThreadDetail?,
        predicate: (ThreadEvent) -> Boolean
    ): IndexedEvent? {
        return indexedEvents(detail, predicate).lastOrNull()
    }

    private fun indexedEvents(
        detail: ThreadDetail?,
        predicate: (ThreadEvent) -> Boolean
    ): List<IndexedEvent> {
        val matchedEvents = detail?.recentEvents.orEmpty().mapIndexedNotNull { index, event ->
            if (!predicate(event)) {
                return@mapIndexedNotNull null
            }
            IndexedEvent(
                index = index,
                event = event,
                timestampMillis = parseIsoMillis(event.timestamp)
            )
        }
        if (matchedEvents.isEmpty()) {
            return emptyList()
        }
        if (matchedEvents.none { it.timestampMillis != null }) {
            return matchedEvents
        }

        return matchedEvents
            .sortedWith(
                compareBy<IndexedEvent> { it.timestampMillis ?: Long.MIN_VALUE }
                    .thenBy { it.index }
            )
    }

    private fun isRunningEvent(event: ThreadEvent): Boolean {
        return event.kind == ThreadEventKind.TURN_STARTED ||
            event.status == MobileThreadStatus.RUNNING
    }

    private fun isCompletionEvent(event: ThreadEvent): Boolean {
        return event.kind == ThreadEventKind.TURN_COMPLETED ||
            event.status == MobileThreadStatus.COMPLETED
    }

    private fun isRealTurnStartEvent(event: ThreadEvent): Boolean {
        return isRunningEvent(event) &&
            !isOptimisticRunningEvent(event) &&
            !isRunningHeartbeatEvent(event)
    }

    private fun isOptimisticRunningEvent(event: ThreadEvent): Boolean {
        return event.eventId.startsWith("client-") ||
            event.text == "已提交到桌面发送"
    }

    private fun isRunningHeartbeatEvent(event: ThreadEvent): Boolean {
        return event.text == "线程正在运行"
    }

    private fun eventOccursAfter(left: IndexedEvent, right: IndexedEvent): Boolean {
        val leftMillis = left.timestampMillis
        val rightMillis = right.timestampMillis
        if (leftMillis != null && rightMillis != null) {
            if (leftMillis != rightMillis) {
                return leftMillis > rightMillis
            }
            return left.index > right.index
        }
        if (leftMillis != null) {
            return true
        }
        if (rightMillis != null) {
            return false
        }
        return left.index > right.index
    }

    private fun parseIsoMillis(value: String): Long? {
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private data class IndexedEvent(
        val index: Int,
        val event: ThreadEvent,
        val timestampMillis: Long?
    )
}
