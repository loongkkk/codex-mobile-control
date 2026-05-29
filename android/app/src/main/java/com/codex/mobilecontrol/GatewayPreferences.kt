package com.codex.mobilecontrol

import android.content.Context
import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.GatewayConnectionMode
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.QueuedTextMessageStatus
import com.codex.mobilecontrol.model.ThreadComposerState
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadFileChangeItem
import com.codex.mobilecontrol.model.ThreadFileChanges
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import com.codex.mobilecontrol.model.ThreadMessageSendState
import com.codex.mobilecontrol.ui.DetailTimelineCollapseSettings
import org.json.JSONArray
import org.json.JSONObject

data class GatewayConfig(
    val url: String,
    val token: String
)

object GatewayPreferences {
    private const val PREFS_NAME = "codex_mobile_control"
    private const val KEY_TOKEN = "token"
    private const val KEY_GATEWAY_URL = "gateway_url"
    private const val KEY_ALERT_CURSOR = "alert_cursor"
    private const val KEY_ALERT_ENABLED_THREAD_IDS = "alert_enabled_thread_ids"
    private const val KEY_ALERT_MESSAGE_ENABLED_THREAD_IDS = "alert_message_enabled_thread_ids"
    private const val KEY_ALERT_COMPLETION_ENABLED_THREAD_IDS = "alert_completion_enabled_thread_ids"
    private const val KEY_ALERT_MESSAGE_KEYS = "alert_message_keys"
    private const val KEY_ALERT_NOTIFIED_MESSAGE_KEYS = "alert_notified_message_keys"
    private const val KEY_ALERT_COMPLETION_KEYS = "alert_completion_keys"
    private const val KEY_LAST_OPENED_THREAD_ID = "last_opened_thread_id"
    private const val KEY_CACHED_THREADS = "cached_threads_v1"
    private const val KEY_CACHED_THREAD_DETAILS = "cached_thread_details_v2"
    private const val KEY_QUEUED_TEXT_MESSAGES = "queued_text_messages_v1"
    private const val KEY_THREAD_CACHE_PERSISTENCE_ENABLED = "thread_cache_persistence_enabled"
    private const val KEY_CONNECTION_MODE = "connection_mode"
    private const val KEY_BACKGROUND_CONNECTION_ENABLED = "background_connection_enabled"
    private const val KEY_LAST_REALTIME_EVENT_ID = "last_realtime_event_id"
    private const val KEY_KNOWN_NOTIFICATION_IDS = "known_notification_ids"
    private const val KEY_READ_THREAD_STATUS_INDICATOR_KEYS = "read_thread_status_indicator_keys_v1"
    private const val KEY_DETAIL_TYPING_ANIMATION_ENABLED = "detail_typing_animation_enabled"
    private const val KEY_DETAIL_TYPING_CHARS_PER_SECOND = "detail_typing_chars_per_second"
    private const val KEY_DETAIL_TIMELINE_COLLAPSE_SETTINGS = "detail_timeline_collapse_settings_v1"
    private const val DEFAULT_DETAIL_TYPING_ANIMATION_ENABLED = true
    private const val DEFAULT_DETAIL_TYPING_CHARS_PER_SECOND = 8

    fun loadConfig(context: Context): GatewayConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_TOKEN, null)
        if (token.isNullOrBlank()) {
            return null
        }
        val url = prefs.getString(KEY_GATEWAY_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: GatewayDefaults.BUILT_IN_URL
        return GatewayDefaults.configFor(url, token)
    }

    fun saveConfig(context: Context, config: GatewayConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_GATEWAY_URL, config.url)
            .apply()
    }

    fun loadConnectionMode(context: Context): GatewayConnectionMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONNECTION_MODE, null)
        return GatewayConnectionMode.fromWireValue(raw)
    }

    fun saveConnectionMode(context: Context, mode: GatewayConnectionMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONNECTION_MODE, mode.wireValue)
            .apply()
    }

    fun loadBackgroundConnectionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKGROUND_CONNECTION_ENABLED, false)
    }

    fun saveBackgroundConnectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND_CONNECTION_ENABLED, enabled)
            .apply()
    }

    fun loadDetailTypingAnimationEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(
                KEY_DETAIL_TYPING_ANIMATION_ENABLED,
                DEFAULT_DETAIL_TYPING_ANIMATION_ENABLED
            )
    }

    fun saveDetailTypingAnimationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DETAIL_TYPING_ANIMATION_ENABLED, enabled)
            .apply()
    }

    fun loadDetailTypingCharsPerSecond(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(
                KEY_DETAIL_TYPING_CHARS_PER_SECOND,
                DEFAULT_DETAIL_TYPING_CHARS_PER_SECOND
            )
            .coerceAtLeast(1)
    }

    fun saveDetailTypingCharsPerSecond(context: Context, charsPerSecond: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(
                KEY_DETAIL_TYPING_CHARS_PER_SECOND,
                charsPerSecond.coerceAtLeast(1)
            )
            .apply()
    }

    fun loadDetailTimelineCollapseSettings(context: Context): DetailTimelineCollapseSettings {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DETAIL_TIMELINE_COLLAPSE_SETTINGS, null)
            ?: return DetailTimelineCollapseSettings()
        return runCatching {
            val json = JSONObject(raw)
            DetailTimelineCollapseSettings(
                minProcessMessageCount = json.optInt(
                    "minProcessMessageCount",
                    DetailTimelineCollapseSettings.DEFAULT_MIN_PROCESS_MESSAGE_COUNT
                ),
                minProcessDurationMillis = json.optLong(
                    "minProcessDurationMillis",
                    DetailTimelineCollapseSettings.DEFAULT_MIN_PROCESS_DURATION_MILLIS
                )
            )
        }.getOrDefault(DetailTimelineCollapseSettings())
    }

    fun saveDetailTimelineCollapseSettings(
        context: Context,
        settings: DetailTimelineCollapseSettings
    ) {
        val raw = JSONObject()
            .put("minProcessMessageCount", settings.minProcessMessageCount)
            .put("minProcessDurationMillis", settings.minProcessDurationMillis)
            .toString()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DETAIL_TIMELINE_COLLAPSE_SETTINGS, raw)
            .apply()
    }

    fun loadLastRealtimeEventId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_REALTIME_EVENT_ID, null)
    }

    fun saveLastRealtimeEventId(context: Context, eventId: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_REALTIME_EVENT_ID, eventId)
            .apply()
    }

    fun loadKnownNotificationIds(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_KNOWN_NOTIFICATION_IDS, null)
            ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length())
                .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
                .toSet()
        }.getOrDefault(emptySet())
    }

    fun saveKnownNotificationIds(context: Context, ids: Set<String>) {
        val array = JSONArray()
        ids.sorted().takeLast(500).forEach(array::put)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_KNOWN_NOTIFICATION_IDS, array.toString())
            .apply()
    }

    fun loadQueuedTextMessages(context: Context): List<QueuedTextMessage> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QUEUED_TEXT_MESSAGES, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::queuedTextMessageFromJson)
            }
        }.getOrDefault(emptyList())
    }

    fun saveQueuedTextMessages(context: Context, messages: List<QueuedTextMessage>) {
        val visibleMessages = messages.filter { message ->
            message.threadId.isNotBlank() &&
                message.text.isNotBlank() &&
                (
                    message.status == QueuedTextMessageStatus.PENDING ||
                        message.status == QueuedTextMessageStatus.DISPATCHING ||
                        message.status == QueuedTextMessageStatus.FAILED
                    )
        }.takeLast(100)
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (visibleMessages.isEmpty()) {
            editor.remove(KEY_QUEUED_TEXT_MESSAGES)
        } else {
            val array = JSONArray()
            visibleMessages.forEach { message ->
                array.put(queuedTextMessageToJson(message))
            }
            editor.putString(KEY_QUEUED_TEXT_MESSAGES, array.toString())
        }
        editor.apply()
    }

    fun loadAlertCursor(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ALERT_CURSOR, null)
    }

    fun saveAlertCursor(context: Context, cursor: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALERT_CURSOR, cursor)
            .apply()
    }

    fun loadAlertEnabledThreadIds(context: Context): Set<String> {
        return loadStringSet(context, KEY_ALERT_ENABLED_THREAD_IDS) ?: emptySet()
    }

    fun saveAlertEnabledThreadIds(context: Context, threadIds: Set<String>) {
        saveStringSet(context, KEY_ALERT_ENABLED_THREAD_IDS, threadIds)
    }

    fun loadAlertMessageEnabledThreadIds(context: Context): Set<String> {
        return loadStringSet(context, KEY_ALERT_MESSAGE_ENABLED_THREAD_IDS)
            ?: loadAlertEnabledThreadIds(context)
    }

    fun saveAlertMessageEnabledThreadIds(context: Context, threadIds: Set<String>) {
        saveStringSet(context, KEY_ALERT_MESSAGE_ENABLED_THREAD_IDS, threadIds)
    }

    fun loadAlertCompletionEnabledThreadIds(context: Context): Set<String> {
        return loadStringSet(context, KEY_ALERT_COMPLETION_ENABLED_THREAD_IDS)
            ?: loadAlertEnabledThreadIds(context)
    }

    fun saveAlertCompletionEnabledThreadIds(context: Context, threadIds: Set<String>) {
        saveStringSet(context, KEY_ALERT_COMPLETION_ENABLED_THREAD_IDS, threadIds)
    }

    private fun saveStringSet(context: Context, key: String, values: Set<String>) {
        val array = JSONArray()
        values.sorted().forEach(array::put)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, array.toString())
            .apply()
    }

    fun loadAlertMessageKeys(context: Context): Map<String, String?> {
        return loadStringMap(context, KEY_ALERT_MESSAGE_KEYS)
    }

    fun saveAlertMessageKeys(context: Context, keys: Map<String, String?>) {
        saveStringMap(context, KEY_ALERT_MESSAGE_KEYS, keys)
    }

    fun loadAlertNotifiedMessageKeys(context: Context): Map<String, Set<String>> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ALERT_NOTIFIED_MESSAGE_KEYS, null)
            ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { threadId ->
                val array = json.optJSONArray(threadId) ?: JSONArray()
                (0 until array.length())
                    .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
                    .toCollection(LinkedHashSet())
                    .toSet()
            }
        }.getOrDefault(emptyMap())
    }

    fun saveAlertNotifiedMessageKeys(context: Context, keys: Map<String, Set<String>>) {
        val json = JSONObject()
        keys.forEach { (threadId, threadKeys) ->
            json.put(
                threadId,
                JSONArray().also { array ->
                    threadKeys.filter { it.isNotBlank() }.forEach(array::put)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALERT_NOTIFIED_MESSAGE_KEYS, json.toString())
            .apply()
    }

    fun loadAlertCompletionKeys(context: Context): Map<String, String?> {
        return loadStringMap(context, KEY_ALERT_COMPLETION_KEYS)
    }

    fun saveAlertCompletionKeys(context: Context, keys: Map<String, String?>) {
        saveStringMap(context, KEY_ALERT_COMPLETION_KEYS, keys)
    }

    fun loadReadThreadStatusIndicatorKeys(context: Context): Map<String, String?> {
        return loadStringMap(context, KEY_READ_THREAD_STATUS_INDICATOR_KEYS)
    }

    fun saveReadThreadStatusIndicatorKeys(
        context: Context,
        keys: Map<String, String?>
    ) {
        saveStringMap(context, KEY_READ_THREAD_STATUS_INDICATOR_KEYS, keys)
    }

    fun loadLastOpenedThreadId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_OPENED_THREAD_ID, null)
    }

    fun saveLastOpenedThreadId(context: Context, threadId: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_OPENED_THREAD_ID, threadId)
            .apply()
    }

    fun loadCachedThreads(context: Context): List<ThreadListItem> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_THREADS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                ThreadListItem(
                    threadId = item.optString("threadId").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null,
                    title = item.optString("title"),
                    cwd = item.optString("cwd"),
                    status = MobileThreadStatus.fromWireValue(item.optString("status")),
                    updatedAt = item.optString("updatedAt").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null,
                    progressSummary = item.optString("progressSummary"),
                    needsAttention = item.optBoolean("needsAttention", false),
                    isPinned = item.optBoolean("isPinned", false),
                    automationActive = item.optBoolean("automationActive", false),
                    automationSummary = item.optString("automationSummary").takeIf { it.isNotBlank() },
                    runningStartedAt = item.optString("runningStartedAt").takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveCachedThreads(context: Context, threads: List<ThreadListItem>) {
        if (!isThreadCachePersistenceEnabled(context)) {
            return
        }
        val array = JSONArray()
        threads.forEach { thread ->
            array.put(threadToJson(thread))
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CACHED_THREADS, array.toString())
            .apply()
    }

    fun loadCachedThreadDetail(context: Context, threadId: String): ThreadDetail? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_THREAD_DETAILS, null)
            ?: return null
        return runCatching {
            val details = JSONObject(raw)
            details.optJSONObject(threadId)?.let { detailFromJson(it) }
        }.getOrNull()
    }

    fun saveCachedThreadDetail(context: Context, detail: ThreadDetail) {
        if (!isThreadCachePersistenceEnabled(context)) {
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val details = runCatching {
            JSONObject(prefs.getString(KEY_CACHED_THREAD_DETAILS, null) ?: "{}")
        }.getOrDefault(JSONObject())

        details.put(detail.thread.threadId, detailToJson(detail))
        prefs.edit()
            .putString(KEY_CACHED_THREAD_DETAILS, details.toString())
            .apply()
    }

    fun setThreadCachePersistenceEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_THREAD_CACHE_PERSISTENCE_ENABLED, enabled)
            .apply()
    }

    fun clearThreadCache(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_OPENED_THREAD_ID)
            .remove(KEY_CACHED_THREADS)
            .remove(KEY_CACHED_THREAD_DETAILS)
            .putBoolean(KEY_THREAD_CACHE_PERSISTENCE_ENABLED, false)
            .apply()
    }

    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TOKEN)
            .remove(KEY_GATEWAY_URL)
            .remove(KEY_ALERT_CURSOR)
            .remove(KEY_ALERT_ENABLED_THREAD_IDS)
            .remove(KEY_ALERT_MESSAGE_ENABLED_THREAD_IDS)
            .remove(KEY_ALERT_COMPLETION_ENABLED_THREAD_IDS)
            .remove(KEY_ALERT_MESSAGE_KEYS)
            .remove(KEY_ALERT_NOTIFIED_MESSAGE_KEYS)
            .remove(KEY_ALERT_COMPLETION_KEYS)
            .remove(KEY_LAST_OPENED_THREAD_ID)
            .remove(KEY_CACHED_THREADS)
            .remove(KEY_CACHED_THREAD_DETAILS)
            .remove(KEY_QUEUED_TEXT_MESSAGES)
            .remove(KEY_THREAD_CACHE_PERSISTENCE_ENABLED)
            .remove(KEY_CONNECTION_MODE)
            .remove(KEY_BACKGROUND_CONNECTION_ENABLED)
            .remove(KEY_LAST_REALTIME_EVENT_ID)
            .remove(KEY_KNOWN_NOTIFICATION_IDS)
            .remove(KEY_READ_THREAD_STATUS_INDICATOR_KEYS)
            .apply()
    }

    private fun isThreadCachePersistenceEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_THREAD_CACHE_PERSISTENCE_ENABLED, true)
    }

    private fun loadStringMap(context: Context, key: String): Map<String, String?> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
            ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { itemKey ->
                if (json.isNull(itemKey)) {
                    null
                } else {
                    json.optString(itemKey).takeIf { it.isNotBlank() }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveStringMap(context: Context, key: String, values: Map<String, String?>) {
        val json = JSONObject()
        values.forEach { (itemKey, value) ->
            json.put(itemKey, value ?: JSONObject.NULL)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, json.toString())
            .apply()
    }

    private fun threadToJson(thread: ThreadListItem): JSONObject {
        return JSONObject()
            .put("threadId", thread.threadId)
            .put("title", thread.title)
            .put("cwd", thread.cwd)
            .put("status", thread.status.wireValue)
            .put("updatedAt", thread.updatedAt)
            .put("progressSummary", thread.progressSummary)
            .put("needsAttention", thread.needsAttention)
            .put("isPinned", thread.isPinned)
            .put("automationActive", thread.automationActive)
            .put("automationSummary", thread.automationSummary)
            .put("runningStartedAt", thread.runningStartedAt)
    }

    private fun queuedTextMessageToJson(message: QueuedTextMessage): JSONObject {
        return JSONObject()
            .put("threadId", message.threadId)
            .put("text", message.text)
            .put("queuedAtMillis", message.queuedAtMillis)
            .put("blockedThreadUpdatedAt", message.blockedThreadUpdatedAt)
            .put("queueId", message.queueId)
            .put("status", message.status.name)
            .put("dispatchStartedAtMillis", message.dispatchStartedAtMillis)
            .put("errorMessage", message.errorMessage)
    }

    private fun queuedTextMessageFromJson(json: JSONObject): QueuedTextMessage? {
        val threadId = json.optString("threadId").takeIf { it.isNotBlank() } ?: return null
        val text = json.optString("text").takeIf { it.isNotBlank() } ?: return null
        val queuedAtMillis = json.optLong("queuedAtMillis", 0L).takeIf { it > 0L } ?: return null
        val queueId = json.optString("queueId").takeIf { it.isNotBlank() }
            ?: "queued-$queuedAtMillis"
        val storedStatus = queuedTextMessageStatusFromJson(json.optString("status"))
        val status = if (storedStatus == QueuedTextMessageStatus.DISPATCHING) {
            QueuedTextMessageStatus.FAILED
        } else {
            storedStatus
        }
        return QueuedTextMessage(
            threadId = threadId,
            text = text,
            queuedAtMillis = queuedAtMillis,
            blockedThreadUpdatedAt = json.optString("blockedThreadUpdatedAt").takeIf { it.isNotBlank() },
            queueId = queueId,
            status = status,
            dispatchStartedAtMillis = if (status == QueuedTextMessageStatus.DISPATCHING) {
                json.optLong("dispatchStartedAtMillis", 0L).takeIf { it > 0L }
            } else {
                null
            },
            errorMessage = if (storedStatus == QueuedTextMessageStatus.DISPATCHING) {
                "queue_dispatch_interrupted"
            } else {
                json.optString("errorMessage").takeIf { it.isNotBlank() }
            }
        )
    }

    private fun queuedTextMessageStatusFromJson(raw: String?): QueuedTextMessageStatus {
        return runCatching {
            QueuedTextMessageStatus.valueOf(raw.orEmpty())
        }.getOrDefault(QueuedTextMessageStatus.PENDING)
    }

    private fun detailToJson(detail: ThreadDetail): JSONObject {
        return JSONObject()
            .put("thread", threadToJson(detail.thread))
            .put("recentMessages", JSONArray().also { array ->
                detail.recentMessages.takeLast(80).forEach { message ->
                    array.put(messageToJson(message))
                }
            })
            .put("recentEvents", JSONArray().also { array ->
                detail.recentEvents.takeLast(40).forEach { event ->
                    array.put(eventToJson(event))
                }
            })
            .put("sendAvailable", detail.sendAvailable)
            .put("sendDisabledReason", detail.sendDisabledReason)
            .put("fileChanges", detail.fileChanges?.let { fileChangesToJson(it) })
            .put("composerState", detail.composerState?.let { composerStateToJson(it) })
    }

    private fun detailFromJson(json: JSONObject): ThreadDetail {
        val messages = json.optJSONArray("recentMessages") ?: JSONArray()
        val events = json.optJSONArray("recentEvents") ?: JSONArray()
        return ThreadDetail(
            thread = threadFromJson(json.getJSONObject("thread")),
            recentMessages = (0 until messages.length()).mapNotNull { index ->
                messages.optJSONObject(index)?.let { messageFromJson(it) }
            },
            recentEvents = (0 until events.length()).mapNotNull { index ->
                events.optJSONObject(index)?.let { eventFromJson(it) }
            },
            sendAvailable = json.optBoolean("sendAvailable", true),
            sendDisabledReason = json.optString("sendDisabledReason").takeIf { it.isNotBlank() },
            fileChanges = json.optJSONObject("fileChanges")?.let { fileChangesFromJson(it) },
            composerState = json.optJSONObject("composerState")?.let { composerStateFromJson(it) }
        )
    }

    private fun threadFromJson(json: JSONObject): ThreadListItem {
        return ThreadListItem(
            threadId = json.optString("threadId"),
            title = json.optString("title"),
            cwd = json.optString("cwd"),
            status = MobileThreadStatus.fromWireValue(json.optString("status")),
            updatedAt = json.optString("updatedAt"),
            progressSummary = json.optString("progressSummary"),
            needsAttention = json.optBoolean("needsAttention", false),
            isPinned = json.optBoolean("isPinned", false),
            automationActive = json.optBoolean("automationActive", false),
            automationSummary = json.optString("automationSummary").takeIf { it.isNotBlank() },
            runningStartedAt = json.optString("runningStartedAt").takeIf { it.isNotBlank() }
        )
    }

    private fun loadStringSet(context: Context, key: String): Set<String>? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
            ?: return null
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length())
                .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun messageToJson(message: ThreadMessage): JSONObject {
        return JSONObject()
            .put("messageId", message.messageId)
            .put("threadId", message.threadId)
            .put("role", message.role.wireValue)
            .put("kind", message.kind)
            .put("text", message.text)
            .put("imageUrl", message.imageUrl)
            .put("thumbnailUrl", message.thumbnailUrl)
            .put("fileUrl", message.fileUrl)
            .put("fileName", message.fileName)
            .put("timestamp", message.timestamp)
            .put("sendState", message.sendState?.wireValue)
            .put("mimeType", message.mimeType)
    }

    private fun messageFromJson(json: JSONObject): ThreadMessage {
        return ThreadMessage(
            messageId = json.optString("messageId"),
            threadId = json.optString("threadId"),
            role = ThreadMessageRole.fromWireValue(json.optString("role")),
            kind = json.optString("kind").takeIf { it.isNotBlank() } ?: "text",
            text = json.optString("text").takeIf { it.isNotBlank() },
            imageUrl = json.optString("imageUrl").takeIf { it.isNotBlank() },
            thumbnailUrl = json.optString("thumbnailUrl").takeIf { it.isNotBlank() },
            fileUrl = json.optString("fileUrl").takeIf { it.isNotBlank() },
            fileName = json.optString("fileName").takeIf { it.isNotBlank() },
            timestamp = json.optString("timestamp"),
            sendState = ThreadMessageSendState.fromWireValue(json.optString("sendState")),
            mimeType = json.optString("mimeType").takeIf { it.isNotBlank() }
        )
    }

    private fun eventToJson(event: ThreadEvent): JSONObject {
        return JSONObject()
            .put("eventId", event.eventId)
            .put("threadId", event.threadId)
            .put("kind", event.kind.wireValue)
            .put("status", event.status?.wireValue)
            .put("text", event.text)
            .put("timestamp", event.timestamp)
    }

    private fun eventFromJson(json: JSONObject): ThreadEvent {
        return ThreadEvent(
            eventId = json.optString("eventId"),
            threadId = json.optString("threadId"),
            kind = ThreadEventKind.fromWireValue(json.optString("kind")),
            status = json.optString("status").takeIf { it.isNotBlank() }
                ?.let { MobileThreadStatus.fromWireValue(it) },
            text = json.optString("text"),
            timestamp = json.optString("timestamp")
        )
    }

    private fun fileChangesToJson(fileChanges: ThreadFileChanges): JSONObject {
        return JSONObject()
            .put("summary", fileChanges.summary)
            .put("changedFiles", fileChanges.changedFiles)
            .put("added", fileChanges.added)
            .put("removed", fileChanges.removed)
            .put("items", JSONArray().also { array ->
                fileChanges.items.forEach { item ->
                    array.put(
                        JSONObject()
                            .put("path", item.path)
                            .put("added", item.added)
                            .put("removed", item.removed)
                    )
                }
            })
    }

    private fun fileChangesFromJson(json: JSONObject): ThreadFileChanges {
        val items = json.optJSONArray("items") ?: JSONArray()
        return ThreadFileChanges(
            summary = json.optString("summary"),
            changedFiles = json.optInt("changedFiles"),
            added = json.optInt("added"),
            removed = json.optInt("removed"),
            items = (0 until items.length()).mapNotNull { index ->
                items.optJSONObject(index)?.let { item ->
                    ThreadFileChangeItem(
                        path = item.optString("path"),
                        added = item.optInt("added"),
                        removed = item.optInt("removed")
                    )
                }
            }
        )
    }

    private fun composerStateToJson(composerState: ThreadComposerState): JSONObject {
        return JSONObject()
            .put("permissionLabel", composerState.permissionLabel)
            .put("modelLabel", composerState.modelLabel)
            .put("effortLabel", composerState.effortLabel)
    }

    private fun composerStateFromJson(json: JSONObject): ThreadComposerState {
        return ThreadComposerState(
            permissionLabel = json.optString("permissionLabel"),
            modelLabel = json.optString("modelLabel"),
            effortLabel = json.optString("effortLabel").takeIf { it.isNotBlank() }
        )
    }
}
