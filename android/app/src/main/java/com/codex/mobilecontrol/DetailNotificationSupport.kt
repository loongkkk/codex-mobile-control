package com.codex.mobilecontrol

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.codex.mobilecontrol.diagnostics.DebugTraceLogger

object DetailNotificationSupport {
    fun showThreadNotification(
        context: Context,
        threadId: String,
        channelId: String,
        title: String,
        body: String,
        category: String,
        defaults: Int = 0
    ): Boolean {
        val pendingIntent = PendingIntent.getActivity(
            context,
            DetailNotificationIds.threadContentIntentRequestCode(threadId),
            launchIntent(context, threadId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(category)
            .setDefaults(defaults)
            .setGroup(DetailNotificationIds.GROUP_KEY)
            .build()

        return runCatching {
            DebugTraceLogger.log(
                "notification.prepare",
                "threadId=$threadId channelId=$channelId category=$category title=${title.quoteForTrace()} body=${body.quoteForTrace()}"
            )
            val manager = NotificationManagerCompat.from(context)
            manager.notify(
                DetailNotificationIds.notificationTagForThread(threadId),
                DetailNotificationIds.THREAD_NOTIFICATION_ID,
                notification
            )
            refreshSummaryNotification(context, manager)
            DebugTraceLogger.log(
                "notification.posted",
                "threadId=$threadId channelId=$channelId title=${title.quoteForTrace()}"
            )
            true
        }.getOrElse { error ->
            DebugTraceLogger.log(
                "notification.failed",
                "threadId=$threadId channelId=$channelId reason=${error.javaClass.simpleName}:${error.message.orEmpty().quoteForTrace()}"
            )
            false
        }
    }

    fun refreshSummaryNotification(
        context: Context,
        manager: NotificationManagerCompat = NotificationManagerCompat.from(context)
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val childNotifications = notificationManager.activeNotifications
            .filter { statusBarNotification ->
                statusBarNotification.tag?.startsWith("thread:") == true &&
                    statusBarNotification.id == DetailNotificationIds.THREAD_NOTIFICATION_ID
            }
            .sortedByDescending { it.postTime }

        if (childNotifications.isEmpty()) {
            manager.cancel(DetailNotificationIds.SUMMARY_NOTIFICATION_ID)
            return
        }

        val summaryText = context.getString(
            R.string.detail_notification_summary_body,
            childNotifications.size
        )
        val style = NotificationCompat.InboxStyle().setSummaryText(summaryText)
        childNotifications.take(5).forEach { statusBarNotification ->
            val line = statusBarNotification.notification.extras
                .getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: statusBarNotification.notification.extras
                    .getCharSequence(Notification.EXTRA_TEXT)
                    ?.toString()
                    .orEmpty()
            if (line.isNotBlank()) {
                style.addLine(line)
            }
        }

        val summaryPendingIntent = PendingIntent.getActivity(
            context,
            DetailNotificationIds.SUMMARY_CONTENT_INTENT_REQUEST_CODE,
            launchIntent(context, threadId = null),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summaryNotification = NotificationCompat.Builder(
            context,
            DetailNotificationIds.ALERT_CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(summaryText)
            .setStyle(style)
            .setContentIntent(summaryPendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup(DetailNotificationIds.GROUP_KEY)
            .setGroupSummary(true)
            .build()
        manager.notify(DetailNotificationIds.SUMMARY_NOTIFICATION_ID, summaryNotification)
    }

    fun cancelThreadNotificationIfChannel(
        context: Context,
        threadId: String,
        channelId: String
    ): Boolean {
        return runCatching {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val tag = DetailNotificationIds.notificationTagForThread(threadId)
            val activeNotification = notificationManager.activeNotifications.firstOrNull { item ->
                item.tag == tag &&
                    item.id == DetailNotificationIds.THREAD_NOTIFICATION_ID &&
                    item.notification.channelId == channelId
            } ?: return false
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(activeNotification.tag, activeNotification.id)
            refreshSummaryNotification(context, manager)
            DebugTraceLogger.log(
                "notification.cancelled",
                "threadId=$threadId channelId=$channelId"
            )
            true
        }.getOrElse { error ->
            DebugTraceLogger.log(
                "notification.cancel_failed",
                "threadId=$threadId channelId=$channelId reason=${error.javaClass.simpleName}:${error.message.orEmpty().quoteForTrace()}"
            )
            false
        }
    }

    private fun launchIntent(context: Context, threadId: String?): Intent {
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            if (!threadId.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            }
        }
    }
}

private fun String.quoteForTrace(): String {
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
