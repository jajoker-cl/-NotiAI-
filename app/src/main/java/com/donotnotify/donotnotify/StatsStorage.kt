package com.donotnotify.donotnotify

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatsStorage(private val context: Context) {

    private val prefs = context.getSharedPreferences("stats", Context.MODE_PRIVATE)
    private val blockedCountKey = "blocked_count"
    private val todayDateKey = "today_date"

    private fun today(): String {
        return SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    }

    /** 今日拦截数（跨天自动归零） */
    fun getTodayBlockedCount(): Int {
        val savedDate = prefs.getString(todayDateKey, "")
        val today = today()
        if (savedDate != today) return 0
        return prefs.getInt(blockedCountKey, 0)
    }

    fun incrementBlockedNotificationsCount() {
        val today = today()
        val savedDate = prefs.getString(todayDateKey, "")
        val current = if (savedDate == today) getTotalBlockedCount() else 0
        prefs.edit()
            .putString(todayDateKey, today)
            .putInt(blockedCountKey, current + 1)
            .apply()
    }

    /** 总数（不跨天重置，用于旧代码兼容） */
    fun getTotalBlockedCount(): Int {
        return prefs.getInt(blockedCountKey, 0)
    }

    companion object {
        /** 格式化大数：1234 -> "1234", 12345 -> "1.2万", 123456 -> "12.3万" */
        fun formatCount(count: Int): String {
            if (count < 10000) return count.toString()
            val wan = count / 10000.0
            return String.format(Locale.getDefault(), "%.1f万", wan)
        }
    }
}
