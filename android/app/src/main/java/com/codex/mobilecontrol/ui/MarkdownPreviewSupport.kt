package com.codex.mobilecontrol.ui

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

object MarkdownPreviewSupport {
    private val unsupportedSchemePattern = Regex("^[a-z][a-z\\d+.-]*:", RegexOption.IGNORE_CASE)
    private val windowsDrivePattern = Regex("^[A-Za-z]:[\\\\/]")
    private val leadingWindowsFileSlashPattern = Regex("^/[A-Za-z]:[\\\\/]")
    private val editorLineSuffixPattern =
        Regex("(\\.(?:md|markdown|json|txt|log))(?::\\d+){1,2}$", RegexOption.IGNORE_CASE)

    fun previewPathFor(url: String?, label: String): String? {
        val rawUrl = url.orEmpty()
        normalizeCandidate(rawUrl)?.let { return it }
        if (rawUrl.isNotBlank() && hasUnsupportedScheme(rawUrl)) {
            return null
        }
        return normalizeCandidate(label)
    }

    fun isJsonPreviewPath(path: String): Boolean {
        val normalizedPath = previewKindPath(path)
        return normalizedPath.lowercase().endsWith(".json")
    }

    fun isPlainTextPreviewPath(path: String): Boolean {
        val normalizedPath = previewKindPath(path).lowercase()
        return normalizedPath.endsWith(".txt") || normalizedPath.endsWith(".log")
    }

    fun formatJsonPreviewContent(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            return content
        }
        return runCatching {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> content
            }
        }.getOrDefault(content)
    }

    private fun normalizeCandidate(value: String): String? {
        var candidate = decode(value).trim().trim('`')
        if (candidate.isBlank() || candidate.startsWith("#")) {
            return null
        }
        if (candidate.startsWith("file://", ignoreCase = true)) {
            candidate = normalizeFileUriPath(candidate)
        }
        val withoutDecorations = stripEditorLineSuffix(
            candidate.substringBefore('#')
                .substringBefore('?')
                .trim()
        )
        if (withoutDecorations.isBlank() || hasUnsupportedScheme(withoutDecorations)) {
            return null
        }
        val lower = withoutDecorations.lowercase()
        if (
            !lower.endsWith(".md") &&
            !lower.endsWith(".markdown") &&
            !lower.endsWith(".json") &&
            !lower.endsWith(".txt") &&
            !lower.endsWith(".log")
        ) {
            return null
        }
        return withoutDecorations
    }

    private fun previewKindPath(path: String): String {
        return stripEditorLineSuffix(
            path.substringBefore('#')
                .substringBefore('?')
                .trim()
        )
    }

    private fun normalizeFileUriPath(value: String): String {
        var path = value.replace(Regex("^file:/+", RegexOption.IGNORE_CASE), "")
        if (leadingWindowsFileSlashPattern.containsMatchIn(path)) {
            path = path.drop(1)
        }
        return path
    }

    private fun stripEditorLineSuffix(value: String): String {
        return editorLineSuffixPattern.replace(value) { matchResult ->
            matchResult.groupValues[1]
        }
    }

    private fun hasUnsupportedScheme(value: String): Boolean {
        val candidate = decode(value).trim()
        if (candidate.startsWith("file://", ignoreCase = true)) {
            return false
        }
        val withoutDecorations = candidate
            .substringBefore('#')
            .substringBefore('?')
            .trim()
        return unsupportedSchemePattern.containsMatchIn(withoutDecorations) &&
            !windowsDrivePattern.containsMatchIn(withoutDecorations)
    }

    private fun decode(value: String): String {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }
}
