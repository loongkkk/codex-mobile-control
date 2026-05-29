package com.codex.mobilecontrol

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.codex.mobilecontrol.data.ThreadDetailSnapshotSource
import com.codex.mobilecontrol.diagnostics.DebugTraceLogger
import com.codex.mobilecontrol.model.GatewayConnectionMode
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import com.codex.mobilecontrol.network.GatewayApiClient
import com.codex.mobilecontrol.ui.DetailAlertSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AlertsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val api = GatewayApiClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (GatewayPreferences.loadConnectionMode(applicationContext) == GatewayConnectionMode.SOCKET) {
            AlertPollingScheduler.cancel(applicationContext)
            return@withContext Result.success()
        }
        try {
            runCatching { pollEnabledThreads() }
            Result.success()
        } finally {
            AlertPollingScheduler.scheduleNext(applicationContext)
        }
    }

    private suspend fun pollEnabledThreads() {
        val enabledThreadIds = GatewayPreferences.loadAlertEnabledThreadIds(applicationContext)
        if (enabledThreadIds.isEmpty()) {
            return
        }

        val config = GatewayPreferences.loadConfig(applicationContext) ?: return
        createNotificationChannels()

        val messageEnabledThreadIds =
            GatewayPreferences.loadAlertMessageEnabledThreadIds(applicationContext)
        val completionEnabledThreadIds =
            GatewayPreferences.loadAlertCompletionEnabledThreadIds(applicationContext)
        val messageKeys = GatewayPreferences.loadAlertMessageKeys(applicationContext).toMutableMap()
        val notifiedMessageKeys = GatewayPreferences.loadAlertNotifiedMessageKeys(applicationContext)
            .mapValues { (_, keys) -> keys.toMutableSet() }
            .toMutableMap()
        val completionKeys = GatewayPreferences.loadAlertCompletionKeys(applicationContext).toMutableMap()

        enabledThreadIds.forEach { threadId ->
            val detail = runCatching { ThreadDetailSnapshotSource.fetch(api, config, threadId) }
                .getOrNull()
                ?: return@forEach
            cachePolledDetail(detail)
            if (threadId !in messageEnabledThreadIds) {
                messageKeys.remove(threadId)
                notifiedMessageKeys.remove(threadId)
            } else {
                updateMessageAlertBaseline(detail, messageKeys, notifiedMessageKeys)
            }
            if (threadId !in completionEnabledThreadIds) {
                completionKeys.remove(threadId)
            } else {
                updateCompletionAlertBaseline(detail, completionKeys)
            }
        }

        messageKeys.keys.retainAll(enabledThreadIds)
        notifiedMessageKeys.keys.retainAll(enabledThreadIds)
        completionKeys.keys.retainAll(enabledThreadIds)
        GatewayPreferences.saveAlertMessageKeys(applicationContext, messageKeys)
        GatewayPreferences.saveAlertNotifiedMessageKeys(applicationContext, notifiedMessageKeys)
        GatewayPreferences.saveAlertCompletionKeys(applicationContext, completionKeys)
    }

    private fun cachePolledDetail(detail: ThreadDetail) {
        GatewayPreferences.saveCachedThreadDetail(applicationContext, detail)
        val cachedThreads = GatewayPreferences.loadCachedThreads(applicationContext)
        val mergedThreads = if (cachedThreads.any { it.threadId == detail.thread.threadId }) {
            cachedThreads.map { thread ->
                if (thread.threadId == detail.thread.threadId) detail.thread else thread
            }
        } else {
            listOf(detail.thread) + cachedThreads
        }
        GatewayPreferences.saveCachedThreads(applicationContext, mergedThreads)
    }

    private fun updateMessageAlertBaseline(
        detail: ThreadDetail,
        messageKeys: MutableMap<String, String?>,
        notifiedMessageKeys: MutableMap<String, MutableSet<String>>
    ) {
        val threadId = detail.thread.threadId
        val latestMessage = DetailAlertSupport.latestAlertMessage(detail)
        val latestMessageKey = latestMessage?.let { DetailAlertSupport.messageAlertKey(latestMessage) }
        val rememberedKeys = notifiedMessageKeys[threadId].orEmpty()
        val hasBaseline = messageKeys.containsKey(threadId)
        if (!hasBaseline) {
            messageKeys[threadId] = latestMessageKey
            notifiedMessageKeys[threadId] =
                DetailAlertSupport.rememberMessageAlertKey(rememberedKeys, latestMessageKey)
            return
        }
        if (!DetailAlertSupport.shouldNotifyMessageAlert(rememberedKeys, latestMessageKey)) {
            messageKeys[threadId] = latestMessageKey
            notifiedMessageKeys[threadId] =
                DetailAlertSupport.rememberMessageAlertKey(rememberedKeys, latestMessageKey)
            return
        }

        messageKeys[threadId] = latestMessageKey
        notifiedMessageKeys[threadId] =
            DetailAlertSupport.rememberMessageAlertKey(rememberedKeys, latestMessageKey)
        if (latestMessage == null || latestMessage.role == ThreadMessageRole.USER) {
            return
        }
        if (DetailAlertSupport.shouldSuppressNewMessageAlertForCompletion(detail, latestMessage)) {
            return
        }
        showMessageNotification(detail, latestMessage)
    }

    private fun updateCompletionAlertBaseline(
        detail: ThreadDetail,
        completionKeys: MutableMap<String, String?>
    ) {
        val threadId = detail.thread.threadId
        val completionKey = DetailAlertSupport.completionAlertKey(detail)
        val hasBaseline = completionKeys.containsKey(threadId)
        if (!hasBaseline) {
            completionKeys[threadId] = completionKey
            return
        }
        if (completionKey == null) {
            if (
                DetailAlertSupport.shouldClearCompletionNotification(
                    previousCompletionKey = completionKeys[threadId],
                    currentCompletionKey = completionKey
                )
            ) {
                DetailNotificationSupport.cancelThreadNotificationIfChannel(
                    context = applicationContext,
                    threadId = threadId,
                    channelId = DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID
                )
            }
            completionKeys[threadId] = null
            return
        }
        if (completionKeys[threadId] == completionKey) {
            return
        }

        completionKeys[threadId] = completionKey
        showCompletionNotification(detail)
    }

    private fun showMessageNotification(
        detail: ThreadDetail,
        message: ThreadMessage
    ) {
        val body = message.text?.trim()?.take(160).orEmpty().ifBlank {
            applicationContext.getString(R.string.detail_new_message_fallback)
        }
        showNotification(
            detail = detail,
            channelId = DetailNotificationIds.ALERT_CHANNEL_ID,
            title = DetailAlertSupport.notificationThreadTitle(
                threadTitle = detail.thread.title,
                fallback = applicationContext.getString(R.string.detail_new_message_title)
            ),
            body = body,
            category = NotificationCompat.CATEGORY_MESSAGE
        )
    }

    private fun showCompletionNotification(
        detail: ThreadDetail
    ) {
        showNotification(
            detail = detail,
            channelId = DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID,
            title = DetailAlertSupport.notificationThreadTitle(
                threadTitle = detail.thread.title,
                fallback = applicationContext.getString(R.string.detail_new_message_title)
            ),
            body = DetailAlertSupport.completionAlertBody(
                detail,
                applicationContext.getString(R.string.detail_completion_alert_body)
            ),
            category = NotificationCompat.CATEGORY_STATUS,
            defaults = NotificationCompat.DEFAULT_ALL
        )
    }

    private fun showNotification(
        detail: ThreadDetail,
        channelId: String,
        title: String,
        body: String,
        category: String,
        defaults: Int = 0
    ) {
        if (!hasNotificationPermission()) {
            DebugTraceLogger.log(
                "notification.failed",
                "threadId=${detail.thread.threadId} channelId=$channelId reason=permission_denied"
            )
            return
        }
        DetailNotificationSupport.showThreadNotification(
            context = applicationContext,
            threadId = detail.thread.threadId,
            channelId = channelId,
            title = title,
            body = body,
            category = category,
            defaults = defaults
        )
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val messageChannel = NotificationChannel(
            DetailNotificationIds.ALERT_CHANNEL_ID,
            applicationContext.getString(R.string.detail_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = applicationContext.getString(R.string.detail_alert_channel_description)
            enableVibration(false)
        }
        manager.createNotificationChannel(messageChannel)

        val completionChannel = NotificationChannel(
            DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID,
            applicationContext.getString(R.string.detail_completion_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = applicationContext.getString(R.string.detail_completion_alert_channel_description)
            enableVibration(true)
        }
        manager.createNotificationChannel(completionChannel)
    }
}
