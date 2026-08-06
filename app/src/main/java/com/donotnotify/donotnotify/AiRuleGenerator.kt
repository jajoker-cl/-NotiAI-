package com.donotnotify.donotnotify

import android.util.Log

/**
 * Automatically generates [BlockerRule]s from AI judgment results.
 *
 * When the AI judge determines a notification is spam with high confidence, this
 * class extracts filter keywords and creates a new rule so future notifications
 * from the same app with similar content are blocked immediately — without
 * waiting for another AI call.
 *
 * ### Quality lifecycle
 * New rules start as **PENDING**. Each time the rule matches a notification,
 * its `hitCount` is bumped (by [RuleStorage.incrementHitCounts]). When
 * `hitCount ≥ [AiMetadata.PROMOTION_THRESHOLD]`, the next check
 * ([upgradePENDING]) promotes the rule to **CONFIRMED**, signalling that the
 * rule is validated by real-world hits and should be treated with the same
 * trust as a manually created rule.
 *
 * ### Deduplication
 * Before creating a new rule, [tryGenerate] checks for an existing rule with
 * the same `packageName` + `titleFilter` + `textFilter`. If one already exists,
 * it returns `null` to avoid duplicates.
 *
 * Thread safety: all public methods are safe to call from any thread. The
 * heavy lifting (persistence) happens on the caller's thread via
 * [RuleStorage]'s internal lock.
 */
class AiRuleGenerator(private val ruleStorage: RuleStorage) {

    companion object {
        private const val TAG = "AiRuleGenerator"

        /** Minimum confidence to consider generating a rule. */
        const val MIN_CONFIDENCE = 0.85f

        /** Minimum keyword length after trimming to be usable as a filter. */
        private const val MIN_KEYWORD_LENGTH = 3

        /** Maximum keywords to extract per notification. */
        private const val MAX_KEYWORDS = 3
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Attempt to generate a rule from an AI judgment. Returns the new rule
     * if one was created, or `null` if:
     * - Confidence is below [MIN_CONFIDENCE]
     * - A matching rule already exists
     * - No useful keywords could be extracted
     */
    fun tryGenerate(
        packageName: String,
        title: String?,
        text: String?,
        judgment: AiJudgment
    ): BlockerRule? {
        if (!judgment.isSpam || judgment.confidence < MIN_CONFIDENCE) {
            Log.d(TAG, "Skipped rule generation — isSpam=${judgment.isSpam}, confidence=${judgment.confidence}")
            return null
        }

        val keywords = extractKeywords(title, text)
        if (keywords.isEmpty()) {
            Log.d(TAG, "Skipped rule generation — no extractable keywords for $packageName")
            return null
        }

        // Pick the best single keyword for the title filter; remaining keywords go to text filter.
        val titleKeyword = keywords.firstOrNull()
        val textKeyword = keywords.getOrNull(1)

        // Check for duplicates against existing rules.
        val existing = ruleStorage.getRules()
        val isDuplicate = existing.any { rule ->
            rule.packageName == packageName &&
                rule.titleFilter == titleKeyword &&
                rule.textFilter == textKeyword
        }
        if (isDuplicate) {
            Log.d(TAG, "Skipped rule generation — duplicate rule exists for $packageName, keyword='$titleKeyword'")
            return null
        }

        val newRule = BlockerRule(
            packageName = packageName,
            titleFilter = titleKeyword,
            titleMatchType = MatchType.CONTAINS,
            textFilter = textKeyword,
            textMatchType = MatchType.CONTAINS,
            ruleType = RuleType.DENYLIST,
            isEnabled = true,
            hitCount = 0,
            source = "ai_auto",
            aiMetadata = AiMetadata(
                source = "ai_auto",
                confidence = judgment.confidence,
                reason = judgment.reason
            )
        )

        // Persist the rule.
        try {
            ruleStorage.addRules(listOf(newRule))
            Log.i(TAG, "Generated AI rule for $packageName: title='${titleKeyword}', text='${textKeyword}', confidence=${judgment.confidence}")
            return newRule
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save AI-generated rule for $packageName", e)
            return null
        }
    }

    /**
     * Promotes PENDING rules that have accumulated enough hits to CONFIRMED.
     *
     * Intended to be called periodically (e.g. on listener connect or after
     * hit-count updates). Scans all rules, and for each PENDING rule whose
     * `hitCount ≥ [AiMetadata.PROMOTION_THRESHOLD]`, updates its
     * `aiMetadata.status` to [AiMetadata.CONFIRMED].
     *
     * @return the number of rules promoted.
     */
    fun upgradePENDING(): Int {
        val rules = ruleStorage.getRules()
        var promoted = 0

        for (rule in rules) {
            val meta = rule.aiMetadata ?: continue
            if (meta.status != AiMetadata.PENDING) continue
            if (rule.hitCount < AiMetadata.PROMOTION_THRESHOLD) continue

            val updated = rule.copy(
                aiMetadata = meta.copy(status = AiMetadata.CONFIRMED)
            )
            val result = ruleStorage.updateRuleById(rule.id, updated)
            if (result != null) {
                promoted++
                Log.i(TAG, "Promoted rule ${rule.id} to CONFIRMED (hitCount=${rule.hitCount})")
            }
        }

        return promoted
    }

    // ------------------------------------------------------------------
    // Keyword extraction
    // ------------------------------------------------------------------

    /**
     * Extracts the most representative keywords from a notification's title
     * and text content. Uses a simple frequency + relevance heuristic:
     * 1. Tokenise both fields (split on whitespace/punctuation).
     * 2. Remove common stop words and very short tokens.
     * 3. Return the top [MAX_KEYWORDS] tokens by length (longer tokens are
     *    usually more distinctive).
     *
     * Returns up to [MAX_KEYWORDS] keywords. The first keyword is intended
     * for `titleFilter`; subsequent keywords for `textFilter`.
     */
    internal fun extractKeywords(title: String?, text: String?): List<String> {
        val combined = buildString {
            if (!title.isNullOrBlank()) append(title)
            if (!title.isNullOrBlank() && !text.isNullOrBlank()) append(' ')
            if (!text.isNullOrBlank()) append(text)
        }
        if (combined.isBlank()) return emptyList()

        val tokens = combined
            .lowercase()
            .split(Regex("[^a-zA-Z0-9\\u4e00-\\u9fff]+"))  // keep CJK characters
            .filter { it.length >= MIN_KEYWORD_LENGTH && it !in STOP_WORDS }
            .distinct()
            .sortedByDescending { it.length }  // longer = more specific

        return tokens.take(MAX_KEYWORDS)
    }

    /**
     * Common English stop words that are too generic to form useful filters.
     * CJK stop words are intentionally not in this set — they're typically
     * meaningful even at 1–2 characters.
     */
    private val STOP_WORDS = setOf(
        // English
        "the", "this", "that", "with", "from", "your", "have", "been",
        "will", "would", "could", "should", "about", "into", "just",
        "also", "only", "when", "then", "than", "what", "which",
        "there", "their", "them", "they", "were", "some", "more",
        "very", "here", "like", "other", "each", "most", "such",
        "does", "made", "make", "many", "well", "back", "over",
        "after", "before", "being", "where", "while",
        // Common notification filler
        "https", "http", "www", "com", "org", "net"
    )
}
