package com.codex.mobilecontrol

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.codex.mobilecontrol.model.GatewayConnectionMode
import java.util.concurrent.TimeUnit

object AlertPollingScheduler {
    private const val PERIODIC_WORK_NAME = "codex_mobile_alerts_periodic"
    private const val ONE_TIME_WORK_NAME = "codex_mobile_alerts_now"
    private const val ALERT_POLL_INTERVAL_MS = 10_000L

    fun schedule(context: Context) {
        if (GatewayPreferences.loadConnectionMode(context) == GatewayConnectionMode.SOCKET) {
            cancel(context)
            return
        }
        enqueueNext(context, ExistingWorkPolicy.REPLACE)
    }

    fun scheduleNext(context: Context) {
        if (GatewayPreferences.loadConnectionMode(context) == GatewayConnectionMode.SOCKET ||
            GatewayPreferences.loadAlertEnabledThreadIds(context).isEmpty()
        ) {
            cancel(context)
            return
        }
        enqueueNext(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK_NAME)
    }

    private fun enqueueNext(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<AlertsWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInitialDelay(ALERT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            policy,
            request
        )
    }
}
