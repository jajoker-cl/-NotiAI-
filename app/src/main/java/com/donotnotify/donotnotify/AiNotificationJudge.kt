package com.donotnotify.donotnotify

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AI-powered notification judge that calls the DeepSeek Chat Completions API
 * (OpenAI-compatible endpoint) to decide whether a notification is spam / junk.
 *
 * Design principles:
 * - **Fail-open**: any error or timeout lets the notification through.
 * - **Non-blocking**: all network I/O runs on [aiExecutor].
 * - **Cached**: identical notifications hit [AiNotificationCache] first.
 * - **Configurable**: API key and endpoint can be changed at runtime (e.g. from
 *   a settings screen).
 *
 * Usage:
 * ```
 * val judge = AiNotificationJudge(key = "sk-...")
 * val future = judge.judgeAsync("com.spam.app", "Sale!", "Buy now 50% off")
 * val result = future.get(6, TimeUnit.SECONDS)  // 5 s API + 1 s margin
 * ```
 */
class AiNotificationJudge(
    key: String,
    private val apiUrl: String = DEFAULT_API_URL,
    private val model: String = DEFAULT_MODEL,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val cache: AiNotificationCache = AiNotificationCache()
) {

    // Single-threaded executor keeps ordering predictable and avoids overwhelming the API.
    private val aiExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-judge").apply { isDaemon = true }
    }

    private val gson = Gson()

    @Volatile
    private var deepSeekKey: String = key

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Asynchronously judge whether a notification is spam.
     *
     * @return a [java.util.concurrent.Future] whose value is an [AiJudgment].
     *         On error or timeout the future still resolves to [AiJudgment.FAIL_OPEN].
     */
    fun judgeAsync(
        packageName: String,
        title: String?,
        text: String?
    ): java.util.concurrent.Future<AiJudgment> {
        // Check cache first — no need to submit to the executor.
        val cached = cache.get(packageName, title, text)
        if (cached != null) {
            Log.d(TAG, "Cache hit for $packageName — isSpam=${cached.isSpam}")
            return CompletedFuture(cached)
        }

        return aiExecutor.submit(Callable {
            try {
                val judgment = callDeepSeekApi(packageName, title, text)
                cache.put(packageName, title, text, judgment)
                judgment
            } catch (e: Exception) {
                Log.e(TAG, "AI judgment failed for $packageName — failing open", e)
                AiJudgment.FAIL_OPEN
            }
        })
    }

    /**
     * Synchronous convenience wrapper — blocks the calling thread.
     * Prefer [judgeAsync] from UI / service threads.
     */
    fun judge(
        packageName: String,
        title: String?,
        text: String?
    ): AiJudgment = judgeAsync(packageName, title, text).get(
        (timeoutMs + 1000).toLong(), TimeUnit.MILLISECONDS
    )

    /**
     * Update the API key at runtime (e.g. from a settings screen).
     */
    fun updateApiKey(newKey: String) {
        this.deepSeekKey = newKey
    }

    fun shutdown() {
        aiExecutor.shutdown()
        try {
            if (!aiExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                aiExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            aiExecutor.shutdownNow()
        }
    }

    fun cacheSize(): Int = cache.size()
    fun evictExpiredCache() = cache.evictExpired()

    // ---------------------------------------------------------------------------
    // DeepSeek API call
    // ---------------------------------------------------------------------------

    /**
     * Constructs the prompt, POSTs to DeepSeek, and parses the JSON response
     * into an [AiJudgment].
     *
     * @throws Exception on network error, bad response, or timeout.
     */
    private fun callDeepSeekApi(
        packageName: String,
        title: String?,
        text: String?
    ): AiJudgment {
        val requestBody = buildRequestBody(packageName, title, text)

        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $deepSeekKey")
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
        }

        try {
            // Write request body
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode

            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = readStream(connection.errorStream)
                Log.e(TAG, "DeepSeek API error $responseCode: $errorBody")
                throw RuntimeException("DeepSeek API returned $responseCode")
            }

            val responseBody = readStream(connection.inputStream)
            return parseApiResponse(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Builds the OpenAI-compatible chat completions request body with a
     * structured system prompt that forces JSON output.
     */
    private fun buildRequestBody(
        packageName: String,
        title: String?,
        text: String?
    ): String {
        val userMessage = buildString {
            append("Analyze this Android notification:\n")
            append("App package: $packageName\n")
            if (!title.isNullOrBlank()) append("Title: $title\n")
            if (!text.isNullOrBlank()) append("Text: $text\n")
        }

        val messages = listOf(
            mapOf(
                "role" to "system",
                "content" to SYSTEM_PROMPT
            ),
            mapOf(
                "role" to "user",
                "content" to userMessage
            )
        )

        val payload = mapOf(
            "model" to model,
            "messages" to messages,
            "temperature" to 0.1,
            "max_tokens" to 200
        )

        return gson.toJson(payload)
    }

    /**
     * Parses the DeepSeek / OpenAI-compatible response and extracts the
     * structured [AiJudgment] from the assistant message content.
     *
     * Expected assistant content format (JSON):
     * ```json
     * {"isSpam": true, "confidence": 0.95, "reason": "Promotional spam"}
     * ```
     */
    private fun parseApiResponse(responseBody: String): AiJudgment {
        val root = JsonParser.parseString(responseBody).asJsonObject

        // Navigate OpenAI response structure: choices[0].message.content
        val choices = root.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) {
            throw RuntimeException("No choices in DeepSeek response")
        }

        val messageContent = choices[0]
            .asJsonObject
            .getAsJsonObject("message")
            .get("content")
            .asString
            .trim()

        // The model may wrap its JSON in markdown code fences — strip them.
        val jsonContent = messageContent
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val result = JsonParser.parseString(jsonContent).asJsonObject

        return AiJudgment(
            isSpam = result.get("isSpam")?.asBoolean ?: false,
            confidence = result.get("confidence")?.asFloat?.coerceIn(0f, 1f) ?: 0f,
            reason = result.get("reason")?.asString ?: "No reason provided"
        )
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    /**
     * Trivial [java.util.concurrent.Future] that wraps an already-computed value.
     * Used when a cache hit avoids the executor entirely.
     */
    private class CompletedFuture<T>(private val value: T) : java.util.concurrent.Future<T> {
        override fun get(): T = value
        override fun get(timeout: Long, unit: TimeUnit?): T = value
        override fun isDone(): Boolean = true
        override fun isCancelled(): Boolean = false
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
    }

    companion object {
        private const val TAG = "AiNotificationJudge"
        private const val DEFAULT_API_URL = "https://api.deepseek.com/chat/completions"
        private const val DEFAULT_MODEL = "deepseek-chat"
        private const val DEFAULT_TIMEOUT_MS = 5000   // 5 seconds

        private const val SYSTEM_PROMPT = """You are a notification spam classifier for Android.
Analyze the given notification and determine if it is spam, promotional junk, or an unwanted notification.

Respond with ONLY a JSON object (no markdown, no explanation outside the JSON):
{"isSpam": <true/false>, "confidence": <0.0-1.0>, "reason": "<brief explanation>"}

Guidelines:
- isSpam = true for: ads, promotions, marketing, fake alerts, phishing attempts, engagement bait, useless system messages, "someone viewed your profile" type social engineering, fake battery/storage warnings from apps, repeated daily promotional notifications.
- isSpam = false for: personal messages, important alerts (banking, 2FA, delivery), calendar reminders, security notifications, genuine system updates, work-related messages, weather alerts.
- confidence: 0.9+ = very certain, 0.7-0.9 = fairly certain, 0.5-0.7 = uncertain, less than 0.5 = likely not spam.
- Keep the reason under 100 characters."""
    }
}
