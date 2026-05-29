package com.codex.mobilecontrol.network

import com.codex.mobilecontrol.model.LoginResponse
import com.codex.mobilecontrol.model.LatestApkInfo
import com.codex.mobilecontrol.model.MarkdownFilePreview
import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.MobileAutomationItem
import com.codex.mobilecontrol.model.SendMessageResponse
import com.codex.mobilecontrol.model.ThreadComposerState
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadFileChangeItem
import com.codex.mobilecontrol.model.ThreadFileChanges
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import com.codex.mobilecontrol.model.ThreadMessagesPage
import org.json.JSONArray
import org.json.JSONObject

class GatewayJsonParser {

    fun parseLoginResponse(body: String): LoginResponse {
        val root = JSONObject(body)
        return LoginResponse(
            authenticated = root.requireBoolean("authenticated")
        )
    }

    fun parseLatestApkInfo(body: String): LatestApkInfo {
        val root = JSONObject(body)
        return LatestApkInfo(
            available = root.requireBoolean("available"),
            fileName = root.optNullableString("fileName")?.takeIf { it.isNotBlank() },
            versionCode = root.optNullableInt("versionCode"),
            versionName = root.optNullableString("versionName")?.takeIf { it.isNotBlank() },
            downloadUrl = root.optNullableString("downloadUrl")?.takeIf { it.isNotBlank() }
        )
    }

    fun parseThreadsResponse(body: String): List<ThreadListItem> {
        val root = JSONObject(body)
        val threads = root.requireArray("threads")
        return (0 until threads.length()).map { index ->
            parseThreadListItem(threads.getJSONObject(index))
        }
    }

    fun parseAutomationsResponse(body: String): List<MobileAutomationItem> {
        val root = JSONObject(body)
        val automations = root.requireArray("automations")
        return (0 until automations.length()).map { index ->
            parseAutomationItem(automations.getJSONObject(index))
        }
    }

    fun parseThreadDetail(body: String): ThreadDetail {
        val root = JSONObject(body)
        val threadJson = root.optJSONObject("thread")
            ?: throw IllegalArgumentException("Missing required field: thread")
        val thread = parseThreadListItem(threadJson)

        val recentMessagesJson = root.requireArray("recentMessages")
        val recentMessages = (0 until recentMessagesJson.length()).map { index ->
            parseThreadMessage(recentMessagesJson.getJSONObject(index))
        }

        val recentEventsJson = root.requireArray("recentEvents")
        val recentEvents = (0 until recentEventsJson.length()).map { index ->
            parseThreadEvent(recentEventsJson.getJSONObject(index))
        }

        val sendAvailable = root.requireBoolean("sendAvailable")
        val sendDisabledReason = root
            .optNullableString("sendDisabledReason")
            ?.takeIf { it.isNotBlank() }
        val fileChanges = root.optJSONObject("fileChanges")?.let { parseThreadFileChanges(it) }
        val composerState = root.optJSONObject("composerState")?.let { parseThreadComposerState(it) }

        return ThreadDetail(
            thread = thread,
            recentMessages = recentMessages,
            recentEvents = recentEvents,
            sendAvailable = sendAvailable,
            sendDisabledReason = sendDisabledReason,
            fileChanges = fileChanges,
            composerState = composerState
        )
    }

    fun parseThreadMessagesResponse(body: String): ThreadMessagesPage {
        val root = JSONObject(body)
        val messagesJson = root.requireArray("messages")
        val messages = (0 until messagesJson.length()).map { index ->
            parseThreadMessage(messagesJson.getJSONObject(index))
        }
        return ThreadMessagesPage(
            messages = messages,
            nextCursor = root.optNullableString("nextCursor")?.takeIf { it.isNotBlank() }
        )
    }

    fun parseMarkdownFilePreview(body: String): MarkdownFilePreview {
        val root = JSONObject(body)
        return MarkdownFilePreview(
            fileName = root.requireString("fileName"),
            path = root.requireString("path"),
            content = root.requireString("content", allowBlank = true),
            sizeBytes = root.optNullableLong("sizeBytes")
        )
    }

    fun parseThreadMessageForRealtime(json: JSONObject): ThreadMessage {
        return parseThreadMessage(json)
    }

    fun parseSendMessageResponse(body: String): SendMessageResponse {
        val root = JSONObject(body)
        return SendMessageResponse(
            accepted = root.requireBoolean("accepted"),
            threadId = root.requireString("threadId"),
            clientMessageId = root.requireString("clientMessageId"),
            sendPath = root.requireString("sendPath"),
            confirmation = root.requireString("confirmation"),
            warning = root.optNullableString("warning")?.takeIf { it.isNotBlank() }
        )
    }

    private fun parseThreadListItem(json: JSONObject): ThreadListItem {
        return ThreadListItem(
            threadId = json.requireString("threadId"),
            title = json.requireString("title", allowBlank = true),
            cwd = json.requireString("cwd", allowBlank = true),
            status = MobileThreadStatus.fromWireValue(json.requireString("status")),
            updatedAt = json.requireString("updatedAt"),
            progressSummary = json.requireString("progressSummary", allowBlank = true),
            needsAttention = json.requireBoolean("needsAttention"),
            isPinned = json.optBoolean("isPinned", false),
            automationActive = json.optBoolean("automationActive", false),
            automationSummary = json.optNullableString("automationSummary")?.takeIf { it.isNotBlank() },
            runningStartedAt = json.optNullableString("runningStartedAt")?.takeIf { it.isNotBlank() }
        )
    }

    private fun parseAutomationItem(json: JSONObject): MobileAutomationItem {
        return MobileAutomationItem(
            id = json.requireString("id"),
            name = json.requireString("name", allowBlank = true),
            kind = json.optNullableString("kind")?.takeIf { it.isNotBlank() } ?: "unknown",
            status = json.optNullableString("status")?.takeIf { it.isNotBlank() } ?: "UNKNOWN",
            scheduleSummary = json.optNullableString("scheduleSummary")?.takeIf { it.isNotBlank() }
                ?: "未设置周期",
            targetThreadId = json.optNullableString("targetThreadId")?.takeIf { it.isNotBlank() },
            targetThreadTitle = json.optNullableString("targetThreadTitle")?.takeIf { it.isNotBlank() },
            cwd = json.optNullableString("cwd")?.takeIf { it.isNotBlank() }
        )
    }

    private fun parseThreadMessage(json: JSONObject): ThreadMessage {
        return ThreadMessage(
            messageId = json.requireString("messageId"),
            threadId = json.requireString("threadId"),
            role = ThreadMessageRole.fromWireValue(json.requireString("role")),
            kind = json.optNullableString("kind")?.takeIf { it.isNotBlank() }
                ?: if (json.optNullableString("imageUrl").isNullOrBlank()) "text" else "image",
            text = json.optNullableString("text"),
            imageUrl = json.optNullableString("imageUrl"),
            thumbnailUrl = json.optNullableString("thumbnailUrl"),
            fileUrl = json.optNullableString("fileUrl"),
            fileName = json.optNullableString("fileName"),
            timestamp = json.requireString("timestamp"),
            mimeType = json.optNullableString("mimeType")
        )
    }

    private fun parseThreadEvent(json: JSONObject): ThreadEvent {
        return ThreadEvent(
            eventId = json.requireString("eventId"),
            threadId = json.requireString("threadId"),
            kind = ThreadEventKind.fromWireValue(json.requireString("kind")),
            status = json.optNullableString("status")?.let { MobileThreadStatus.fromWireValue(it) },
            text = json.requireString("text", allowBlank = true),
            timestamp = json.requireString("timestamp")
        )
    }

    private fun parseThreadFileChanges(json: JSONObject): ThreadFileChanges {
        val itemsJson = json.requireArray("items")
        val items = (0 until itemsJson.length()).map { index ->
            val itemJson = itemsJson.getJSONObject(index)
            ThreadFileChangeItem(
                path = itemJson.requireString("path"),
                added = itemJson.requireInt("added"),
                removed = itemJson.requireInt("removed")
            )
        }

        return ThreadFileChanges(
            summary = json.requireString("summary"),
            changedFiles = json.requireInt("changedFiles"),
            added = json.requireInt("added"),
            removed = json.requireInt("removed"),
            items = items
        )
    }

    private fun parseThreadComposerState(json: JSONObject): ThreadComposerState {
        return ThreadComposerState(
            permissionLabel = json.requireString("permissionLabel"),
            modelLabel = json.requireString("modelLabel"),
            effortLabel = json.optNullableString("effortLabel")?.takeIf { it.isNotBlank() }
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) {
            return null
        }
        return optString(key)
    }

    private fun JSONObject.requireString(key: String, allowBlank: Boolean = false): String {
        val value = optNullableString(key)
        if (value == null || (!allowBlank && value.isBlank())) {
            throw IllegalArgumentException("Missing required field: $key")
        }
        return value
    }

    private fun JSONObject.requireBoolean(key: String): Boolean {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException("Missing required field: $key")
        }
        return getBoolean(key)
    }

    private fun JSONObject.requireInt(key: String): Int {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException("Missing required field: $key")
        }
        return getInt(key)
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) {
            return null
        }
        return getInt(key)
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) {
            return null
        }
        return getLong(key)
    }

    private fun JSONObject.requireArray(key: String): JSONArray {
        return optJSONArray(key) ?: throw IllegalArgumentException("Missing required field: $key")
    }
}
