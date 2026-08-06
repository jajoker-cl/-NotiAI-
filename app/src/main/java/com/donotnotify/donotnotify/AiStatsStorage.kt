package com.donotnotify.donotnotify

import android.content.Context

/**
 * Persistent storage for AI-related statistics.
 *
 * Tracks:
 * - Total number of AI judgments performed
 * - Number of notifications blocked by AI
 * - Number of auto-generated rules created
 *
 * Uses SharedPreferences with key prefix "ai_" to avoid collisions with other stats.
 */
class AiStatsStorage(private val context: Context) {

    private val prefs = context.getSharedPreferences("ai_stats", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_AI_JUDGMENTS = "ai_judgments_count"
        private const val KEY_AI_BLOCKS = "ai_blocks_count"
        private const val KEY_AI_RULES_CREATED = "ai_rules_created_count"
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_AI_API_KEY = "ai_api_key"
    }

    // ------------------------------------------------------------------
    // AI enabled state
    // ------------------------------------------------------------------

    fun isAiEnabled(): Boolean {
        return prefs.getBoolean(KEY_AI_ENABLED, false)
    }

    fun setAiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_ENABLED, enabled).apply()
    }

    // ------------------------------------------------------------------
    // API Key management
    // ------------------------------------------------------------------

    fun getApiKey(): String {
        return prefs.getString(KEY_AI_API_KEY, "") ?: ""
    }

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_AI_API_KEY, key).apply()
    }

    // ------------------------------------------------------------------
    // Statistics counters
    // ------------------------------------------------------------------

    fun getJudgmentCount(): Int {
        return prefs.getInt(KEY_AI_JUDGMENTS, 0)
    }

    fun incrementJudgmentCount() {
        val current = getJudgmentCount()
        prefs.edit().putInt(KEY_AI_JUDGMENTS, current + 1).apply()
    }

    fun getBlockCount(): Int {
        return prefs.getInt(KEY_AI_BLOCKS, 0)
    }

    fun incrementBlockCount() {
        val current = getBlockCount()
        prefs.edit().putInt(KEY_AI_BLOCKS, current + 1).apply()
    }

    fun getRulesCreatedCount(): Int {
        return prefs.getInt(KEY_AI_RULES_CREATED, 0)
    }

    fun incrementRulesCreatedCount() {
        val current = getRulesCreatedCount()
        prefs.edit().putInt(KEY_AI_RULES_CREATED, current + 1).apply()
    }

    fun incrementRulesCreatedCountBy(amount: Int) {
        val current = getRulesCreatedCount()
        prefs.edit().putInt(KEY_AI_RULES_CREATED, current + amount).apply()
    }

    /**
     * Reset all AI statistics to zero.
     */
    fun resetStats() {
        prefs.edit()
            .putInt(KEY_AI_JUDGMENTS, 0)
            .putInt(KEY_AI_BLOCKS, 0)
            .putInt(KEY_AI_RULES_CREATED, 0)
            .apply()
    }

    /**
     * Get a snapshot of current AI statistics.
     */
    fun getStatsSnapshot(): AiStats {
        return AiStats(
            judgmentCount = getJudgmentCount(),
            blockCount = getBlockCount(),
            rulesCreatedCount = getRulesCreatedCount()
        )
    }
}

/**
 * Immutable snapshot of AI statistics for display.
 */
data class AiStats(
    val judgmentCount: Int = 0,
    val blockCount: Int = 0,
    val rulesCreatedCount: Int = 0
)
