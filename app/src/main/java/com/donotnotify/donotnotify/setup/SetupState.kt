package com.donotnotify.donotnotify.setup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object SetupState {
    const val CURRENT_SETUP_VERSION = 1

    private const val PREFS = "settings"
    private const val KEY_SEEN_OEM_AUTOSTART = "seen_oem_autostart"
    private const val KEY_LAST_SEEN_SETUP_VERSION = "last_seen_setup_version"
    const val KEY_LAST_LISTENER_CONNECTED_MS = "last_listener_connected_ms"
    const val KEY_LAST_UNHEALTHY_NOTIF_MS = "last_unhealthy_notif_ms"

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabled.contains(context.packageName)
    }

    /** API < 33 grants POST_NOTIFICATIONS implicitly; 33+ requires a runtime grant. */
    fun isPostNotificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** True only when the runtime permission step is relevant (33+, not yet granted). */
    fun needsPostNotificationsStep(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isPostNotificationsGranted(context)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasSeenOemAutostart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEEN_OEM_AUTOSTART, false)

    fun markOemAutostartSeen(context: Context) {
        prefs(context).edit().putBoolean(KEY_SEEN_OEM_AUTOSTART, true).apply()
    }

    fun lastSeenSetupVersion(context: Context): Int =
        prefs(context).getInt(KEY_LAST_SEEN_SETUP_VERSION, 0)

    fun setLastSeenSetupVersion(context: Context, version: Int) {
        prefs(context).edit().putInt(KEY_LAST_SEEN_SETUP_VERSION, version).apply()
    }

    fun shouldShowSetupWizard(context: Context): Boolean {
        if (!isNotificationListenerEnabled(context)) return true
        return lastSeenSetupVersion(context) < CURRENT_SETUP_VERSION
    }

    fun lastListenerConnectedMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_LISTENER_CONNECTED_MS, 0L)

    fun recordListenerConnected(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_LISTENER_CONNECTED_MS, System.currentTimeMillis())
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
