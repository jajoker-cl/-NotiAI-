package com.donotnotify.donotnotify

/**
 * Thread-safe LRU cache for [AiJudgment] results keyed by a composite hash of
 * (packageName, title, text).
 *
 * - Maximum [MAX_SIZE] entries (1 000 by default).
 * - Entries expire after [TTL_MS] milliseconds (24 hours).
 * - Uses an [synchronized] [LinkedHashMap] with access-order iteration
 *   — the same pattern already established in [RuleMatcher] for its regex cache.
 */
class AiNotificationCache(
    private val maxSize: Int = MAX_SIZE,
    private val ttlMs: Long = TTL_MS
) {

    /** Single cache entry with a creation timestamp. */
    private data class Entry(
        val judgment: AiJudgment,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * access-order = true so that [removeEldestEntry] evicts the *least-recently-used*
     * entry when the map exceeds [maxSize].
     */
    private val cache = object : LinkedHashMap<String, Entry>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxSize
    }

    /**
     * Look up a cached [AiJudgment] for the given notification key.
     *
     * @return The cached judgment if present and not expired, or `null`.
     */
    @Synchronized
    fun get(packageName: String, title: String?, text: String?): AiJudgment? {
        val key = cacheKey(packageName, title, text)
        val entry = cache[key] ?: return null

        if (System.currentTimeMillis() - entry.createdAt > ttlMs) {
            cache.remove(key)          // expired — evict eagerly
            return null
        }
        return entry.judgment
    }

    /**
     * Store an [AiJudgment] in the cache.
     */
    @Synchronized
    fun put(packageName: String, title: String?, text: String?, judgment: AiJudgment) {
        val key = cacheKey(packageName, title, text)
        cache[key] = Entry(judgment)
    }

    /**
     * Remove all entries whose [Entry.createdAt] is older than [ttlMs].
     * Call periodically (e.g. once per hour) to reclaim memory.
     */
    @Synchronized
    fun evictExpired() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { (_, entry) -> now - entry.createdAt > ttlMs }
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    @Synchronized
    fun size(): Int = cache.size

    // ---------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------

    private fun cacheKey(packageName: String, title: String?, text: String?): String {
        // Simple composite key — cheap and collision-resistant for our use case.
        return "$packageName|${title ?: ""}|${text ?: ""}"
    }

    companion object {
        private const val MAX_SIZE = 1000
        private const val TTL_MS = 24 * 60 * 60 * 1000L   // 24 hours
    }
}
