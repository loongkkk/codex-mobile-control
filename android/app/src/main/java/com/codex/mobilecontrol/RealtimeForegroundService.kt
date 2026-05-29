package com.codex.mobilecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.codex.mobilecontrol.diagnostics.DebugTraceLogger
import com.codex.mobilecontrol.ui.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RealtimeForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DebugTraceLogger.initialize(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                DebugTraceLogger.log("foreground-service", "stop requested startId=$startId")
                GatewayPreferences.saveBackgroundConnectionEnabled(applicationContext, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START, null -> Unit
            else -> Unit
        }

        if (!GatewayPreferences.loadBackgroundConnectionEnabled(applicationContext)) {
            DebugTraceLogger.log("foreground-service", "ignored start because preference is disabled")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        serviceScope.launch {
            val config = GatewayPreferences.loadConfig(applicationContext)
            if (config == null) {
                DebugTraceLogger.log("foreground-service", "stop because gateway config is missing")
                GatewayPreferences.saveBackgroundConnectionEnabled(applicationContext, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return@launch
            }

            val requestedThreadId = GatewayPreferences.loadLastOpenedThreadId(applicationContext)
            DebugTraceLogger.log(
                "foreground-service",
                "connect requested thread=${requestedThreadId ?: "none"} url=${config.url}"
            )
            runCatching {
                AppGraph.repositoryFactory(applicationContext)
                    .connect(config, requestedThreadId)
            }.onFailure { error ->
                DebugTraceLogger.log(
                    "foreground-service",
                    "connect failed ${error.message.orEmpty().take(160)}"
                )
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        DebugTraceLogger.log("foreground-service", "destroyed")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RealtimeForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(getString(R.string.foreground_service_notification_title))
            .setContentText(getString(R.string.foreground_service_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.foreground_service_stop),
                stopIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.foreground_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.foreground_service_notification_text)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.codex.mobilecontrol.realtime.START"
        const val ACTION_STOP = "com.codex.mobilecontrol.realtime.STOP"
        private const val CHANNEL_ID = "codex_realtime_foreground"
        private const val NOTIFICATION_ID = 3001

        fun setEnabled(context: Context, enabled: Boolean) {
            GatewayPreferences.saveBackgroundConnectionEnabled(context, enabled)
            val intent = Intent(context, RealtimeForegroundService::class.java).setAction(
                if (enabled) ACTION_START else ACTION_STOP
            )
            if (enabled) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                runCatching {
                    context.startService(intent)
                }.onFailure {
                    context.stopService(intent)
                }
            }
        }
    }
}
