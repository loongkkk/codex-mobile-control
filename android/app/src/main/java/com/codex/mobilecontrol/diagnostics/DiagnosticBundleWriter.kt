package com.codex.mobilecontrol.diagnostics

import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadFileChanges
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.network.GatewayRealtimeEvent
import com.codex.mobilecontrol.ui.MainUiState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DiagnosticBundleInput(
    val appVersionName: String,
    val appVersionCode: Int,
    val currentScreen: String,
    val state: MainUiState,
    val debugTraceLines: List<String>,
    val gatewayDiagnosticsJson: String,
    val gatewayDiagnosticsError: String? = null,
    val alertMessageEnabledThreadIds: Set<String> = emptySet(),
    val alertCompletionEnabledThreadIds: Set<String> = emptySet(),
    val generatedAtMillis: Long
)

object DiagnosticBundleWriter {
    fun create(
        outputDir: File,
        input: DiagnosticBundleInput
    ): File {
        outputDir.mkdirs()
        val zip = File(outputDir, "codex-mobile-diagnostics-${fileTimestamp(input.generatedAtMillis)}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { output ->
            output.writeTextEntry("app-state.json", appStateJson(input).toString(2))
            output.writeTextEntry("gateway-diagnostics.json", sanitizeText(input.gatewayDiagnosticsJson))
            output.writeTextEntry(
                "debug-trace.log",
                input.debugTraceLines.joinToString(separator = "\n", transform = ::sanitizeText)
            )
        }
        return zip
    }

    private fun appStateJson(input: DiagnosticBundleInput): JSONObject {
        val state = input.state
        return JSONObject()
            .put("diagnosticsVersion", 2)
            .put(
                "app",
                JSONObject()
                    .put("versionName", input.appVersionName)
                    .put("versionCode", input.appVersionCode)
                    .put("generatedAtMillis", input.generatedAtMillis)
            )
            .put("currentScreen", input.currentScreen)
            .put(
                "gateway",
                JSONObject()
                    .putNullable("url", sanitizeNullable(state.config?.url))
                    .put("isConnected", state.isConnected)
                    .put("connectionState", state.connectionState.name)
                    .putNullable("syncWarningMessage", sanitizeNullable(state.syncWarningMessage))
                    .putNullable("diagnosticsError", sanitizeNullable(input.gatewayDiagnosticsError))
            )
            .put("selectedThreadId", state.selectedThreadId ?: JSONObject.NULL)
            .put("threads", JSONArray(state.threads.map(::threadJson)))
            .put("detail", state.detail?.let(::detailJson) ?: JSONObject.NULL)
            .put("queue", queueJson(state.queuedTextMessages))
            .put(
                "notifications",
                notificationsJson(
                    pendingRealtimeNotifications = state.pendingRealtimeNotifications,
                    alertMessageEnabledThreadIds = input.alertMessageEnabledThreadIds,
                    alertCompletionEnabledThreadIds = input.alertCompletionEnabledThreadIds
                )
            )
            .put("gatewayDiagnosticsSummary", gatewayDiagnosticsSummaryJson(input.gatewayDiagnosticsJson))
            .put(
                "pendingImageDrafts",
                JSONArray(
                    state.pendingImageDrafts.map { draft ->
                        JSONObject()
                            .put("previewUri", draft.previewUri)
                            .put("fileName", draft.fileName)
                            .put("mimeType", draft.mimeType)
                    }
                )
            )
            .put(
                "pendingFileDrafts",
                JSONArray(
                    state.pendingFileDrafts.map { draft ->
                        JSONObject()
                            .put("previewUri", draft.previewUri)
                            .put("fileName", draft.fileName)
                            .put("mimeType", draft.mimeType)
                    }
                )
            )
            .put("debugTraceLineCount", input.debugTraceLines.size)
    }

    private fun queueJson(messages: List<QueuedTextMessage>): JSONObject {
        val byStatus = JSONObject()
        messages
            .groupingBy { it.status.name }
            .eachCount()
            .toSortedMap()
            .forEach { (status, count) -> byStatus.put(status, count) }
        return JSONObject()
            .put("total", messages.size)
            .put("byStatus", byStatus)
            .put("items", JSONArray(messages.takeLast(MAX_DIAGNOSTIC_ITEMS).map(::queuedTextJson)))
    }

    private fun queuedTextJson(message: QueuedTextMessage): JSONObject {
        return JSONObject()
            .put("queueId", sanitizeText(message.queueId))
            .put("threadId", sanitizeText(message.threadId))
            .put("textPreview", sanitizeText(message.text).take(160))
            .put("queuedAtMillis", message.queuedAtMillis)
            .putNullable("blockedThreadUpdatedAt", message.blockedThreadUpdatedAt)
            .put("status", message.status.name)
            .put("dispatchStartedAtMillis", message.dispatchStartedAtMillis ?: JSONObject.NULL)
            .putNullable("errorMessage", sanitizeNullable(message.errorMessage))
    }

    private fun notificationsJson(
        pendingRealtimeNotifications: List<GatewayRealtimeEvent.Notification>,
        alertMessageEnabledThreadIds: Set<String>,
        alertCompletionEnabledThreadIds: Set<String>
    ): JSONObject {
        return JSONObject()
            .put("messageEnabledThreadCount", alertMessageEnabledThreadIds.size)
            .put("completionEnabledThreadCount", alertCompletionEnabledThreadIds.size)
            .put("messageEnabledThreadIds", JSONArray(alertMessageEnabledThreadIds.sorted()))
            .put("completionEnabledThreadIds", JSONArray(alertCompletionEnabledThreadIds.sorted()))
            .put("pendingRealtimeCount", pendingRealtimeNotifications.size)
            .put(
                "pendingRealtime",
                JSONArray(pendingRealtimeNotifications.takeLast(MAX_DIAGNOSTIC_ITEMS).map(::notificationJson))
            )
    }

    private fun notificationJson(notification: GatewayRealtimeEvent.Notification): JSONObject {
        return JSONObject()
            .put("eventId", sanitizeText(notification.eventId))
            .put("notificationId", sanitizeText(notification.notificationId))
            .put("threadId", sanitizeText(notification.threadId))
            .put("trigger", notification.trigger)
            .put("title", sanitizeText(notification.title))
            .put("body", sanitizeText(notification.body))
            .put("timestamp", notification.timestamp)
    }

    private fun gatewayDiagnosticsSummaryJson(gatewayDiagnosticsJson: String): JSONObject {
        val parsed = runCatching { JSONObject(sanitizeText(gatewayDiagnosticsJson)) }.getOrNull()
            ?: return JSONObject().put("available", false)
        if (!parsed.optBoolean("available", true)) {
            return JSONObject()
                .put("available", false)
                .put("diagnosticsVersion", parsed.optInt("diagnosticsVersion", 0))
                .putNullable("error", sanitizeNullable(parsed.optString("error").takeIf { it.isNotBlank() }))
        }
        val service = parsed.optJSONObject("service")
        val statusDecisions = service?.optJSONObject("statusDecisions")
            ?: parsed.optJSONObject("statusDecisions")
        val recent = statusDecisions?.optJSONArray("recent")
        val recentItems = JSONArray()
        if (recent != null) {
            val start = maxOf(0, recent.length() - MAX_DIAGNOSTIC_ITEMS)
            for (index in start until recent.length()) {
                val item = recent.optJSONObject(index) ?: continue
                recentItems.put(
                    JSONObject()
                        .put("threadId", sanitizeText(item.optString("threadId")))
                        .put("context", item.optString("context"))
                        .put("status", item.optString("status"))
                        .put("source", item.optString("source"))
                        .put("text", sanitizeText(item.optString("text")))
                        .put("timestamp", item.optString("timestamp"))
                        .put("observedAt", item.optString("observedAt"))
                )
            }
        }
        return JSONObject()
            .put("available", true)
            .put("diagnosticsVersion", parsed.optInt("diagnosticsVersion", 0))
            .put(
                "statusDecisions",
                JSONObject()
                    .put("recentCount", recent?.length() ?: 0)
                    .put("recent", recentItems)
            )
    }

    private fun detailJson(detail: ThreadDetail): JSONObject {
        return JSONObject()
            .put("thread", threadJson(detail.thread))
            .put("recentMessages", JSONArray(detail.recentMessages.map(::messageJson)))
            .put("recentEvents", JSONArray(detail.recentEvents.map(::eventJson)))
            .put("sendAvailable", detail.sendAvailable)
            .putNullable("sendDisabledReason", detail.sendDisabledReason)
            .put("fileChanges", detail.fileChanges?.let(::fileChangesJson) ?: JSONObject.NULL)
            .put(
                "composerState",
                detail.composerState?.let { composer ->
                    JSONObject()
                        .put("permissionLabel", composer.permissionLabel)
                        .put("modelLabel", composer.modelLabel)
                        .putNullable("effortLabel", composer.effortLabel)
                } ?: JSONObject.NULL
            )
    }

    private fun threadJson(thread: ThreadListItem): JSONObject {
        return JSONObject()
            .put("threadId", thread.threadId)
            .put("title", sanitizeText(thread.title))
            .put("cwd", sanitizeText(thread.cwd))
            .put("status", thread.status.wireValue)
            .put("updatedAt", thread.updatedAt)
            .put("progressSummary", sanitizeText(thread.progressSummary))
            .put("needsAttention", thread.needsAttention)
            .put("isPinned", thread.isPinned)
            .put("automationActive", thread.automationActive)
            .put("automationSummary", sanitizeText(thread.automationSummary.orEmpty()))
            .put("runningStartedAt", sanitizeText(thread.runningStartedAt.orEmpty()))
    }

    private fun messageJson(message: ThreadMessage): JSONObject {
        return JSONObject()
            .put("messageId", message.messageId)
            .put("threadId", message.threadId)
            .put("role", message.role.wireValue)
            .put("kind", message.kind)
            .putNullable("text", sanitizeNullable(message.text))
            .putNullable("imageUrl", sanitizeNullable(message.imageUrl))
            .putNullable("thumbnailUrl", sanitizeNullable(message.thumbnailUrl))
            .putNullable("fileUrl", sanitizeNullable(message.fileUrl))
            .putNullable("fileName", message.fileName)
            .put("timestamp", message.timestamp)
            .putNullable("sendState", message.sendState?.wireValue)
            .putNullable("mimeType", message.mimeType)
    }

    private fun eventJson(event: ThreadEvent): JSONObject {
        return JSONObject()
            .put("eventId", event.eventId)
            .put("threadId", event.threadId)
            .put("kind", event.kind.wireValue)
            .putNullable("status", event.status?.wireValue)
            .put("text", sanitizeText(event.text))
            .put("timestamp", event.timestamp)
    }

    private fun fileChangesJson(fileChanges: ThreadFileChanges): JSONObject {
        return JSONObject()
            .put("summary", fileChanges.summary)
            .put("changedFiles", fileChanges.changedFiles)
            .put("added", fileChanges.added)
            .put("removed", fileChanges.removed)
            .put(
                "items",
                JSONArray(
                    fileChanges.items.map { item ->
                        JSONObject()
                            .put("path", item.path)
                            .put("added", item.added)
                            .put("removed", item.removed)
                    }
                )
            )
    }

    private fun fileTimestamp(epochMillis: Long): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))
    }

    private fun sanitizeNullable(value: String?): String? = value?.let(::sanitizeText)

    private fun sanitizeText(value: String): String {
        return value
            .replace(AUTH_BEARER_REGEX, "$1[REDACTED]")
            .replace(BEARER_REGEX, "$1[REDACTED]")
            .replace(TOKEN_QUERY_REGEX, "$1[REDACTED]")
            .replace(TOKEN_ASSIGNMENT_REGEX, "$1[REDACTED]")
            .replace(JSON_SECRET_REGEX, "$1[REDACTED]$3")
            .replace(LOCAL_PATH_REGEX, "[LOCAL_PATH]")
    }

    private fun ZipOutputStream.writeTextEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun JSONObject.putNullable(name: String, value: String?): JSONObject {
        put(name, value ?: JSONObject.NULL)
        return this
    }

    private val AUTH_BEARER_REGEX = Regex(
        pattern = "(?i)(Authorization\\s*:\\s*Bearer\\s+)([^\\s\"'\\\\]+)"
    )
    private val BEARER_REGEX = Regex(
        pattern = "(?i)(Bearer\\s+)([A-Za-z0-9._~+/=-]+)"
    )
    private val TOKEN_QUERY_REGEX = Regex(
        pattern = "(?i)([?&](?:access_token|auth_token|token)=)([^\\s&\"'\\\\]+)"
    )
    private val TOKEN_ASSIGNMENT_REGEX = Regex(
        pattern = "(?i)(\\b(?:access_token|auth_token|token)=)([^\\s&\"'\\\\]+)"
    )
    private val JSON_SECRET_REGEX = Regex(
        pattern = "(?i)(\"(?:authorization|token|accessToken|refreshToken|apiKey)\"\\s*:\\s*\")([^\"]+)(\")"
    )
    private val LOCAL_PATH_REGEX = Regex(
        pattern = """(?i)(?:[A-Z]:[\\/]+Users[\\/]+[^\s"'\\/]+(?:[\\/]+[^\s"']*)?|/home/[^\s"']+)"""
    )
    private const val MAX_DIAGNOSTIC_ITEMS = 20
}
