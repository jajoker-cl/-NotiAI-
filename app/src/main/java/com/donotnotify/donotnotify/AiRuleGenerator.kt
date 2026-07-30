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
                            put("content", """你是手机通知过滤规则生成器。根据用户的拦截记录和纠错反馈，生成拦截/放行规则。

输出格式：只输出JSON数组，每个元素：
{"packageName":"包名","titleFilter":"标题关键词","textFilter":"正文关键词","action":"block或allow","reason":"原因"}
不确定时输出 [] 。不要输出JSON以外的任何内容。

规则要求：
- 关键词精准（如「优惠券」而非「优」），避免误拦
- 宁可漏拦也不误拦，不确定就不要生成规则
- 只对出现3次以上的明确重复模式生成
- 一个App最多2条规则
- 微信/QQ/WhatsApp/Telegram不生成规则

不靠谱的规则（不要生成）：
- 关键词太短太泛：如「通知」「消息」「提醒」
- 只出现一两次就生成：样本不足
- 银行/验证码类标记拦截：用户需要收验证码

正例（应该生成）：
- 某App反复推送「限时优惠」「全场5折」「大促」-> block，关键词「促销」
- 某App标题固定但内容是广告 -> block，关键词用正文特征词
- 用户纠错说某App应放行 -> allow规则

反例（不要生成）：
- 某App只出现一次：不生成
- 银行App标题「交易提醒」：不拦截""")
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

    // 社交App不生成规则——内容太复杂，关键词区分不了"朋友群聊"和"垃圾广告"
    private val skipPackagePrefixes = listOf(
        "com.tencent.mm",      // 微信
        "com.tencent.mobileqq", // QQ
        "com.whatsapp",         // WhatsApp
        "org.telegram",         // Telegram
    )

    private fun buildRuleGenPrompt(logs: List<String>, feedback: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine("以下是我手机上的通知过滤记录和我的纠错反馈。请分析后生成拦截/放行规则。")
        sb.appendLine()
        sb.appendLine("== 被拦截的通知（最近50条） ==")
        val blocked = logs.take(50).filter { it.contains("[拦截]") }
        if (blocked.isEmpty()) sb.appendLine("（无）")
        else for (log in blocked) sb.appendLine(log.take(200))
        sb.appendLine()
        sb.appendLine("== 放行的通知（最近20条） ==")
        val passed = logs.take(50).filter { it.contains("[放行]") }.take(20)
        if (passed.isEmpty()) sb.appendLine("（无）")
        else for (log in passed) sb.appendLine(log.take(200))
        sb.appendLine()
        sb.appendLine("== 我的手动纠错（我最在意的判断） ==")
        if (feedback.isEmpty()) sb.appendLine("（暂无纠错）")
        else for (fb in feedback.take(20)) sb.appendLine(fb.take(200))
        sb.appendLine()
        sb.appendLine("请严格按照上述要求生成规则。记住：宁可漏拦也不误拦，不确定就输出 []。")
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
