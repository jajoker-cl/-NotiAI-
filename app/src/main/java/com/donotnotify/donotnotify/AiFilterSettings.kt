package com.donotnotify.donotnotify

import android.content.Context

/**
 * AI模式设置存储（SharedPreferences）
 * 用DeepSeek API代替规则引擎判断通知是否重要
 */
object AiFilterSettings {
    private const val PREFS_NAME = "ai_filter"
    private const val KEY_AI_MODE = "ai_mode" // 0=关闭 1=规则优先+AI复查 2=AI直接判断
    private const val KEY_PROVIDER = "ai_provider" // "deepseek" 或 "mimo"
    private const val KEY_API_KEY_DEEPSEEK = "deepseek_api_key"
    private const val KEY_API_KEY_MIMO = "mimo_api_key"
    private const val KEY_CUSTOM_RULE = "custom_rule"
    private const val KEY_FEEDBACK_PREFIX = "feedback_"

    // 支持的提供商
    const val PROVIDER_DEEPSEEK = "deepseek"
    const val PROVIDER_MIMO = "mimo"

    // 各提供商默认模型
    const val MODEL_DEEPSEEK = "deepseek-v4-flash"
    const val MODEL_MIMO = "mimo-v2.5"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** AI档位：0=关闭 1=规则优先+AI复查 2=AI直接判断 */
    fun getAiMode(ctx: Context): Int =
        prefs(ctx).getInt(KEY_AI_MODE, 0)

    fun setAiMode(ctx: Context, mode: Int) =
        prefs(ctx).edit().putInt(KEY_AI_MODE, mode).apply()

    /** 兼容旧代码：AI是否启用（档位>=1） */
    fun isAiModeEnabled(ctx: Context) =
        getAiMode(ctx) >= 1

    /** 当前选中的提供商 */
    fun getProvider(ctx: Context): String =
        prefs(ctx).getString(KEY_PROVIDER, PROVIDER_DEEPSEEK) ?: PROVIDER_DEEPSEEK

    fun setProvider(ctx: Context, provider: String) =
        prefs(ctx).edit().putString(KEY_PROVIDER, provider).apply()

    /** 取当前提供商对应的模型名 */
    fun getCurrentModel(ctx: Context): String =
        if (getProvider(ctx) == PROVIDER_MIMO) MODEL_MIMO else MODEL_DEEPSEEK

    /** 当前提供商的 API Key */
    fun getApiKey(ctx: Context): String {
        val provider = getProvider(ctx)
        val key = if (provider == PROVIDER_MIMO) KEY_API_KEY_MIMO else KEY_API_KEY_DEEPSEEK
        return prefs(ctx).getString(key, "") ?: ""
    }

    /** 保存某提供商的 API Key（不动另一个） */
    fun setApiKey(ctx: Context, key: String) {
        val provider = getProvider(ctx)
        val prefKey = if (provider == PROVIDER_MIMO) KEY_API_KEY_MIMO else KEY_API_KEY_DEEPSEEK
        prefs(ctx).edit().putString(prefKey, key).apply()
    }

    /** 按提供商读 key（用于设置界面展示当前选中的那个） */
    fun getApiKeyFor(ctx: Context, provider: String): String {
        val prefKey = if (provider == PROVIDER_MIMO) KEY_API_KEY_MIMO else KEY_API_KEY_DEEPSEEK
        return prefs(ctx).getString(prefKey, "") ?: ""
    }

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

    fun getObservationDays(ctx: Context): Int = prefs(ctx).getInt("obs_days", 3)
    fun setObservationDays(ctx: Context, d: Int) = prefs(ctx).edit().putInt("obs_days", d).apply()
    fun markAiStartTime(ctx: Context) {
        if (prefs(ctx).getLong("ai_start", 0L) == 0L)
            prefs(ctx).edit().putLong("ai_start", System.currentTimeMillis()).apply()
    }
    fun isObservationPeriod(ctx: Context): Boolean {
        val s = getAiStartTime(ctx)
        val d = getObservationDays(ctx)
        return s == 0L || System.currentTimeMillis() - s < d * 24L * 60 * 60 * 1000
    }
    private fun getAiStartTime(ctx: Context): Long = prefs(ctx).getLong("ai_start", 0L)
}
