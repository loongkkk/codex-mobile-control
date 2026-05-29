package com.codex.mobilecontrol.ui

import android.content.Context
import com.codex.mobilecontrol.R
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessageRole
import kotlin.math.roundToInt

data class DetailChecklistItem(
    val text: String,
    val completed: Boolean
)

data class DetailFileChangeItem(
    val path: String,
    val added: String? = null,
    val removed: String? = null
)

data class DetailErrorContent(
    val title: String,
    val summary: String,
    val evidence: String? = null,
    val showCard: Boolean = true
)

data class DetailVisualContent(
    val bodyMarkdown: String,
    val errorContent: DetailErrorContent?,
    val todoTitle: String,
    val todoProgress: String,
    val todoItems: List<DetailChecklistItem>,
    val showTodoMarker: Boolean,
    val todoMarkerText: String,
    val fileSummary: String,
    val fileItems: List<DetailFileChangeItem>,
    val showFileChanges: Boolean,
    val showFilesMarker: Boolean,
    val filesMarkerText: String
)

object DetailVisualSupport {
    private const val MESSAGE_PREVIEW_LIMIT = 3
    private val checklistLinePattern = Regex("""^\s*(?:\d+\.\s+|-\s+\[(?:x|X| )]\s+)(.+?)\s*$""")
    private val explicitChecklistPattern = Regex("""^\s*-\s+\[(x|X| )]\s+(.+?)\s*$""")
    private val completedCountPattern = Regex("""已完成\s*(\d+)\s*/\s*(\d+)""")
    private val percentPattern = Regex("""(\d{1,3})%""")
    private val filePathPattern = Regex(
        """([A-Za-z0-9_./-]+\.(?:kt|kts|xml|png|jpg|jpeg|md|txt|json|java|ts|tsx|js))"""
    )
    private val explicitFileSummaryPattern = Regex("""(\d+)\s*个文件已更改(?:\s*(\+\d+))?(?:\s*(-\d+))?""")
    private val addCountPattern = Regex("""(?<![A-Za-z0-9])\+\d+(?![A-Za-z0-9])""")
    private val removeCountPattern = Regex("""(?<![A-Za-z0-9])-\d+(?![A-Za-z0-9])""")

    fun build(
        context: Context,
        detail: ThreadDetail?,
        detailThread: ThreadListItem?
    ): DetailVisualContent {
        val sourceText = previewBodySource(detail, detailThread)
        val todoItems = extractChecklistItems(sourceText)
        val completedMatch = completedCountPattern.find(sourceText)
        val percentMatch = percentPattern.find(sourceText)
        val todoTitle = completedMatch?.let {
            "下一步建议（已完成 ${it.groupValues[1]}/${it.groupValues[2]}）"
        } ?: context.getString(R.string.detail_todo_title_default)
        val todoProgress = percentMatch?.groupValues?.getOrNull(1)?.plus("%") ?: "待更新"
        val fileItems = resolveFileItems(detail, sourceText)
        val bodySource = cleanupBodySource(sourceText, todoItems, fileItems)
        val hasExactTodoMeta = completedMatch != null && percentMatch != null
        val fileSummary = resolveFileSummary(detail, sourceText)
        val showFileChanges = fileSummary != null && fileItems.isNotEmpty()
        val filesHaveExplicitMeta = showFileChanges && fileItems.all {
            !it.added.isNullOrBlank() || !it.removed.isNullOrBlank()
        }
        val errorContent = resolveErrorContent(detail, detailThread)

        return DetailVisualContent(
            bodyMarkdown = bodySource.ifBlank {
                context.getString(R.string.detail_body_todo)
            },
            errorContent = errorContent,
            todoTitle = todoTitle,
            todoProgress = todoProgress,
            todoItems = if (todoItems.isEmpty()) {
                listOf(DetailChecklistItem("暂无可提取的下一步建议", completed = false))
            } else {
                todoItems
            },
            showTodoMarker = !hasExactTodoMeta,
            todoMarkerText = context.getString(R.string.detail_todo_visual_todo),
            fileSummary = fileSummary.orEmpty(),
            fileItems = fileItems,
            showFileChanges = showFileChanges,
            showFilesMarker = showFileChanges && !filesHaveExplicitMeta,
            filesMarkerText = context.getString(R.string.detail_files_visual_todo)
        )
    }

    internal fun resolveErrorContent(
        detail: ThreadDetail?,
        detailThread: ThreadListItem?
    ): DetailErrorContent? {
        val activeThread = detail?.thread ?: detailThread
        val latestStatusEvent = detail?.recentEvents.orEmpty()
            .filter { event ->
                event.status != null || event.kind == ThreadEventKind.ERROR
            }
            .maxByOrNull { event -> event.timestamp }
        val latestErrorEvent = latestStatusEvent?.takeIf { event ->
            event.kind == ThreadEventKind.ERROR || event.status == MobileThreadStatus.ERROR
        }
        val hasErrorStatus = activeThread?.status == MobileThreadStatus.ERROR ||
            detail?.thread?.status == MobileThreadStatus.ERROR ||
            latestErrorEvent != null
        if (!hasErrorStatus) {
            return null
        }

        val summary = firstNonBlank(
            latestErrorEvent?.text,
            detail?.sendDisabledReason,
            activeThread?.progressSummary,
            detail?.thread?.progressSummary
        ) ?: return null
        val evidence = firstNonBlank(
            activeThread?.progressSummary?.takeIf { it != summary },
            detail?.sendDisabledReason?.takeIf { it != summary },
            detail?.thread?.progressSummary?.takeIf { it != summary }
        )
        return DetailErrorContent(
            title = "异常详情",
            summary = summary,
            evidence = evidence
        )
    }

    internal fun previewBodySource(detail: ThreadDetail?, detailThread: ThreadListItem?): String {
        val messages = detail?.recentMessages.orEmpty()
        val nonUserMessages = messages
            .filter { it.role != ThreadMessageRole.USER && !it.text.isNullOrBlank() }
            .takeLast(MESSAGE_PREVIEW_LIMIT)
        val fallbackMessages = messages
            .filter { !it.text.isNullOrBlank() }
            .takeLast(1)
        val selectedMessages = nonUserMessages.ifEmpty { fallbackMessages }
        val text = selectedMessages
            .mapNotNull { message ->
                message.text?.trim()?.takeIf { it.isNotBlank() }
            }
            .joinToString("\n\n")
            .ifBlank {
                detailThread?.progressSummary
                    ?: detail?.thread?.progressSummary
                    ?: ""
            }
        return text.trim()
    }

    internal fun resolveFileItems(
        detail: ThreadDetail?,
        sourceText: String
    ): List<DetailFileChangeItem> {
        val structuredItems = detail?.fileChanges?.items.orEmpty()
        if (structuredItems.isNotEmpty()) {
            return structuredItems.map { item ->
                DetailFileChangeItem(
                    path = item.path,
                    added = "+${item.added}",
                    removed = "-${item.removed}"
                )
            }
        }

        return extractFileItems(sourceText)
    }

    internal fun resolveFileSummary(detail: ThreadDetail?, sourceText: String): String? {
        val structuredChanges = detail?.fileChanges
        if (structuredChanges != null && structuredChanges.items.isNotEmpty()) {
            return structuredChanges.summary.ifBlank {
                "${structuredChanges.changedFiles} 个文件已更改 +${structuredChanges.added} -${structuredChanges.removed}"
            }
        }

        return extractFileSummary(sourceText)
    }

    private fun extractChecklistItems(sourceText: String): List<DetailChecklistItem> {
        return sourceText.lines()
            .mapNotNull { line ->
                val explicitMatch = explicitChecklistPattern.find(line)
                if (explicitMatch != null) {
                    return@mapNotNull DetailChecklistItem(
                        text = explicitMatch.groupValues[2].trim(),
                        completed = explicitMatch.groupValues[1].equals("x", ignoreCase = true)
                    )
                }

                checklistLinePattern.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf {
                    it.isNotBlank()
                }?.let { text ->
                    DetailChecklistItem(
                        text = text,
                        // 无显式状态时保守按完成态渲染，避免误显示失败或等待。
                        completed = true
                    )
                }
            }
            .take(4)
    }

    private fun extractFileItems(sourceText: String): List<DetailFileChangeItem> {
        val items = mutableListOf<DetailFileChangeItem>()
        val seen = linkedSetOf<String>()
        sourceText.lines().forEach { line ->
            val path = filePathPattern.find(line)?.value ?: return@forEach
            if (!seen.add(path)) {
                return@forEach
            }
            items += DetailFileChangeItem(
                path = path,
                added = addCountPattern.find(line)?.value,
                removed = removeCountPattern.find(line)?.value
            )
        }
        return items
            .filter { !it.added.isNullOrBlank() || !it.removed.isNullOrBlank() }
    }

    private fun cleanupBodySource(
        sourceText: String,
        todoItems: List<DetailChecklistItem>,
        fileItems: List<DetailFileChangeItem>
    ): String {
        val todoTexts = todoItems.map { it.text }.toSet()
        val filePaths = fileItems.map { it.path }.toSet()
        val filtered = sourceText.lines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.isBlank() && false
            }
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("下一步建议") ||
                    trimmed.startsWith("代码提交与文件变更") ||
                    trimmed in todoTexts ||
                    filePaths.any { trimmed.contains(it) }
            }
            .joinToString("\n")
            .trim()

        return filtered
    }

    private fun extractFileSummary(sourceText: String): String? {
        val explicitSummary = explicitFileSummaryPattern.find(sourceText)
        if (explicitSummary != null) {
            return listOf(
                "${explicitSummary.groupValues[1]} 个文件已更改",
                explicitSummary.groupValues.getOrNull(2).orEmpty(),
                explicitSummary.groupValues.getOrNull(3).orEmpty()
            )
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }

        val fileItems = extractFileItems(sourceText)
        if (fileItems.isEmpty()) {
            return null
        }

        val addTotal = fileItems.sumOf { it.added?.drop(1)?.toIntOrNull() ?: 0 }
        val removeTotal = fileItems.sumOf { it.removed?.drop(1)?.toIntOrNull() ?: 0 }
        return "${fileItems.size} 个文件已更改 +$addTotal -$removeTotal"
    }

    fun percentFromText(value: String): Int? {
        return percentPattern.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
    }

    fun completionFractionFromTitle(value: String): Pair<Int, Int>? {
        val match = completedCountPattern.find(value) ?: return null
        return (match.groupValues[1].toIntOrNull() ?: return null) to
            (match.groupValues[2].toIntOrNull() ?: return null)
    }

    fun fallbackPercent(items: List<DetailChecklistItem>): String {
        if (items.isEmpty()) {
            return "待更新"
        }
        val completed = items.count { it.completed }
        val percent = ((completed.toFloat() / items.size.toFloat()) * 100f).roundToInt()
        return "$percent%"
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstNotNullOfOrNull { value ->
            value?.trim()?.takeIf { it.isNotBlank() }
        }
    }
}
