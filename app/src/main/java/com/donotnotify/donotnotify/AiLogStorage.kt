package com.donotnotify.donotnotify

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI拦截日志 - 每条通知的完整处理过程
 * 存储在SharedPreferences中，最多保留最近100条
 */
object AiLogStorage {
    private const val PREFS = "ai_logs"
    private const val KEY_LOG = "log_entries"
    private const val MAX_LOGS = 100
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    data class LogEntry(
        val time: String,
        val app: String,
        val title: String,
        val text: String,
        val aiResponse: String,
        val action: String,    // "拦截" 或 "放行"
        val duration: String   // API耗时
    )

    fun addLog(ctx: Context, app: String, title: String?, text: String?,
               aiResponse: String, blocked: Boolean, durationMs: Long) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = getLogs(ctx).toMutableList()

        val line = "[${dateFmt.format(Date())}] [${if (blocked) "拦截" else "放行"}] [${durationMs}ms] $app: ${title ?: "(无)"} | ${(text ?: "(无)").take(80)} | AI: ${aiResponse.take(60)}"

        existing.add(0, line) // 最新的放前面
        if (existing.size > MAX_LOGS) existing.subList(MAX_LOGS, existing.size).clear()
        prefs.edit().putString(KEY_LOG, existing.joinToString("\n")).apply()
    }

    fun getLogs(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LOG, "") ?: ""
        return if (saved.isBlank()) emptyList() else saved.split("\n")
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LOG).remove("reviewed").apply()
    }

    fun isReviewed(ctx: Context, idx: Int): Boolean {
        val set = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("reviewed", emptySet()) ?: emptySet()
        return set.contains(idx.toString())
    }

    fun markReviewed(ctx: Context, idx: Int) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet("reviewed", emptySet()) ?: emptySet()).toMutableSet()
        set.add(idx.toString())
        prefs.edit().putStringSet("reviewed", set).apply()
    }
}
