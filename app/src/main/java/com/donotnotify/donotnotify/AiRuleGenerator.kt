package com.donotnotify.donotnotify

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI分析用户纠错记录，自动生成规则
 * 由用户手动触发（AI评判页面顶部按钮："生成规则"）
 */
object AiRuleGenerator {
    private const val TAG = "AiRuleGenerator"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class GeneratedRule(
        val packageName: String,
        val titleFilter: String,
        val textFilter: String,
        val action: String,       // "block" or "allow"
        val reason: String
    )

    fun generate(ctx: Context, onResult: (List<GeneratedRule>) -> Unit) {
        val apiKey = AiFilterSettings.getApiKey(ctx)
        if (apiKey.isBlank()) {
            onResult(emptyList())
            return
        }

        val logs = AiLogStorage.getLogs(ctx)
        val feedback = AiFilterSettings.getFeedback(ctx)
        if (logs.size < 10 && feedback.size < 3) {
            Log.d(TAG, "Not enough data: ${logs.size} logs, ${feedback.size} feedback")
            onResult(emptyList())
            return
        }

        val prompt = buildRuleGenPrompt(logs, feedback)

        Thread {
            try {
                val body = JSONObject().apply {
                    put("model", "deepseek-chat")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "你是通知过滤规则生成器。根据用户的拦截日志和纠错记录，自动生成拦截/放行规则。\n\n" +
                                "只输出JSON数组，每个元素格式：\n" +
                                "{\"packageName\":\"app包名\",\"titleFilter\":\"标题关键词\",\"textFilter\":\"正文关键词\",\"action\":\"block或allow\",\"reason\":\"生成原因\"}\n\n" +
                                "规则要求：\n" +
                                "- 关键词简短精确（2-4个字），不要太长\n" +
                                "- 一个App最多生成2条规则\n" +
                                "- 只对明确重复出现的模式生成规则\n" +
                                "- 不要输出任何JSON以外的内容")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                    put("temperature", 0.2)
                    put("max_tokens", 2000)
                }

                val request = Request.Builder()
                    .url("https://api.deepseek.com/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val json = response.body?.string() ?: ""
                val rules = parseRules(json)
                onResult(rules)
            } catch (e: Exception) {
                Log.e(TAG, "Rule generation failed", e)
                onResult(emptyList())
            }
        }.start()
    }

    private fun buildRuleGenPrompt(logs: List<String>, feedback: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine("以下是我过去几天的通知过滤记录和纠错反馈，请帮我自动生成拦截/放行规则：")
        sb.appendLine()
        sb.appendLine("== 拦截记录（最近50条） ==")
        for (log in logs.take(50).filter { it.contains("[拦截]") }) {
            sb.appendLine(log.take(200))
        }
        sb.appendLine()
        sb.appendLine("== 放行记录（最近20条） ==")
        for (log in logs.take(50).filter { it.contains("[放行]") }.take(20)) {
            sb.appendLine(log.take(200))
        }
        sb.appendLine()
        sb.appendLine("== 用户纠错记录 ==")
        for (fb in feedback.take(20)) {
            sb.appendLine(fb.take(200))
        }
        sb.appendLine()
        sb.appendLine("请生成规则。重点：经常被拦截的相似通知生成block规则，纠错要求放行的生成allow规则。")
        return sb.toString()
    }

    private fun parseRules(json: String): List<GeneratedRule> {
        return try {
            val obj = JSONObject(json)
            val content = obj.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .replace("```json", "")
                .replace("```", "")

            val arr = JSONArray(content)
            val rules = mutableListOf<GeneratedRule>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                rules.add(GeneratedRule(
                    packageName = item.optString("packageName", ""),
                    titleFilter = item.optString("titleFilter", ""),
                    textFilter = item.optString("textFilter", ""),
                    action = item.optString("action", "block"),
                    reason = item.optString("reason", "")
                ))
            }
            rules
        } catch (e: Exception) {
            Log.e(TAG, "Parse rules failed: ${e.message}")
            emptyList()
        }
    }
}
