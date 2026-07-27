package com.donotnotify.donotnotify

import android.content.Context

/**
 * AI模式设置存储（SharedPreferences）
 * 用DeepSeek API代替规则引擎判断通知是否重要
 */
object AiFilterSettings {
    private const val PREFS_NAME = "ai_filter"
    private const val KEY_AI_ENABLED = "ai_mode_enabled"
    private const val KEY_API_KEY = "deepseek_api_key"
    private const val KEY_CUSTOM_RULE = "custom_rule"
    private const val KEY_FEEDBACK_PREFIX = "feedback_"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAiModeEnabled(ctx: Context) =
        prefs(ctx).getBoolean(KEY_AI_ENABLED, false)

    fun setAiModeEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_ENABLED, enabled).apply()

    fun getApiKey(ctx: Context) =
        prefs(ctx).getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(ctx: Context, key: String) =
        prefs(ctx).edit().putString(KEY_API_KEY, key).apply()

    fun getCustomRule(ctx: Context) =
        prefs(ctx).getString(KEY_CUSTOM_RULE, "") ?: ""

    fun setCustomRule(ctx: Context, rule: String) =
        prefs(ctx).edit().putString(KEY_CUSTOM_RULE, rule).apply()

    // 反馈记录：标记某条通知"其实很重要"或"其实不重要"
    fun addFeedback(ctx: Context, title: String, text: String, wasImportant: Boolean) {
        val fb = prefs(ctx).getStringSet(KEY_FEEDBACK_PREFIX + "data", mutableSetOf()) ?: mutableSetOf()
        val label = if (wasImportant) "IMPORTANT" else "NOT_IMPORTANT"
        fb.add("[$label] $title | $text")
        prefs(ctx).edit().putStringSet(KEY_FEEDBACK_PREFIX + "data", fb).apply()
    }

    fun getFeedback(ctx: Context): List<String> =
        (prefs(ctx).getStringSet(KEY_FEEDBACK_PREFIX + "data", mutableSetOf()) ?: mutableSetOf()).toList()
}
