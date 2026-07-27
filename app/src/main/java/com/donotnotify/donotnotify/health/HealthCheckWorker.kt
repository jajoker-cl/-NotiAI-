package com.donotnotify.donotnotify.health

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.donotnotify.donotnotify.MainActivity
import com.donotnotify.donotnotify.R
import com.donotnotify.donotnotify.setup.SetupState
import java.util.concurrent.TimeUnit

class HealthCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val listenerEnabled = SetupState.isNotificationListenerEnabled(ctx)
        val lastConnected = SetupState.lastListenerConnectedMs(ctx)
        val now = System.currentTimeMillis()
        val staleConnection = lastConnected > 0L && (now - lastConnected) > STALE_THRESHOLD_MS

        if (listenerEnabled && !staleConnection) return Result.success()

        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lastAlert = prefs.getLong(SetupState.KEY_LAST_UNHEALTHY_NOTIF_MS, 0L)
        if (now - lastAlert < ALERT_THROTTLE_MS) return Result.success()

        postUnhealthyNotification(ctx)
        prefs.edit().putLong(SetupState.KEY_LAST_UNHEALTHY_NOTIF_MS, now).apply()
        return Result.success()
    }

    private fun postUnhealthyNotification(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_WIZARD, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(ctx.getString(R.string.health_notification_title))
            .setContentText(ctx.getString(R.string.health_notification_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.health_notification_body)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "health"
        const val EXTRA_OPEN_WIZARD = "open_wizard"
        private const val NOTIF_ID = 1001
        private const val UNIQUE_WORK_NAME = "health-check"
        private val STALE_THRESHOLD_MS = TimeUnit.HOURS.toMillis(24)
        private val ALERT_THROTTLE_MS = TimeUnit.HOURS.toMillis(24)
        private val PERIOD_HOURS = 6L

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(
                PERIOD_HOURS, TimeUnit.HOURS,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
