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

    // 小米 MiMo API 地址
    private const val API_URL_MIMO = "https://api.xiaomimimo.com/v1/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
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
                    put("model", AiFilterSettings.getCurrentModel(ctx))
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", """你是手机通知过滤规则生成器。根据用户的通知记录和纠错反馈，生成拦截/放行规则。

输出格式：只输出JSON数组，每个元素：
{"packageName":"包名","titleFilter":"标题关键词","textFilter":"正文关键词","action":"block或allow","reason":"原因"}
不确定时输出 [] 。不要输出JSON以外的任何内容。

规则要求：
- 营销广告识别要激进：凡包含「优惠」「促销」「限时」「活动」「推荐」「热门」「直播」「上新」「福利」「红包」「会员」「低价」「大促」「秒杀」「折扣」「抢购」「免费领」「抽奖」「降价」「秒杀」等营销词，就生成block规则
- ★ 同一App高频推送（一天多次、内容多变的商品/降价/推荐/优惠信息）本身就是营销信号，即使每条标题都不同，也应为该App生成block规则
- 对标题多变的营销App（如京东这类每天发不同商品降价），建议生成2-3条覆盖不同关键词的block规则，提高拦截覆盖
- 一个App最多3条规则
- 微信/QQ/WhatsApp/Telegram不生成规则

绝不能生成block规则的通知（务必放行）：
- 银行交易、支付、验证码、账单
- 快递物流、订单状态（真正的物流更新）
- 日历/闹钟/会议提醒
- 即时通讯私聊

正例（应该生成）：
- 某App反复推送「限时优惠」「全场5折」「大促」-> block，关键词「优惠」「大促」
- 某电商App每天推不同「您看过的XX降价了」「XX商品正在等你」-> block，关键词「降价」「看过的」「等你」等，生成多条
- 用户纠错说某App应放行 -> allow规则

反例（不要生成）：
- 银行App标题「交易提醒」：不拦截
- 关键词太泛如「通知」「消息」「提醒」：不生成""")
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
                    .url(API_URL_MIMO)
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

        // 日志格式：[MM-dd HH:mm:ss] [拦截/放行] [耗时] 包名: 标题 | 正文 | AI: 原因
        // 提取包名（冒号前）
        fun parsePkg(line: String): String {
            val idx = line.indexOf(": ")
            return if (idx > 0) line.substring(0, idx) else line
        }

        // 按 App 统计放行记录频率（高频放行 = 最可能漏网的营销）
        val passed = logs.filter { it.contains("[放行]") && !it.contains("⚠冲突") }
        val passedByApp = passed.groupBy { parsePkg(it) }
            .map { (pkg, list) -> pkg to list }
            .sortedByDescending { it.second.size }

        // 放行的前几名 App（候选营销源）
        sb.appendLine("== 高频被放行的 App（重点！同一App高频推送本身就是营销信号，即使标题各不相同） ==")
        val topPassed = passedByApp.take(6)
        if (topPassed.isEmpty()) sb.appendLine("（无）")
        else for ((pkg, list) in topPassed) {
            sb.appendLine("--- $pkg (共 ${list.size} 条放行，高频推送！) ---")
            // 保留多条让AI看到"高频"信号，但每条截短控制大小
            for (log in list.take(8)) {
                val idx = log.indexOf(": ")
                val body = if (idx > 0) log.substring(idx + 2) else log
                sb.appendLine("  " + body.take(70))
            }
        }
        sb.appendLine()

        // 被拦截的（已有规则确认的营销模式）
        sb.appendLine("== 已被拦截的通知（确认是广告的样本） ==")
        val blocked = logs.filter { it.contains("[拦截]") }.take(15)
        if (blocked.isEmpty()) sb.appendLine("（无）")
        else for (log in blocked) sb.appendLine(log.take(70))
        sb.appendLine()

        sb.appendLine("== 我的手动纠错（我最在意的判断） ==")
        if (feedback.isEmpty()) sb.appendLine("（暂无纠错）")
        else for (fb in feedback.take(20)) sb.appendLine(fb.take(200))
        sb.appendLine()
        sb.appendLine("请严格按照上述要求生成规则。判断标准：")
        sb.appendLine("- 同一App反复推送「优惠/促销/限时/活动/推荐/热门/直播/上新/福利/红包/会员/低价」等营销内容，即使之前被放行，也应生成block规则")
        sb.appendLine("- 宁可多拦几条广告，也不要让营销通知打扰用户（用户明确表示广告太多了）")
        sb.appendLine("- 但银行交易/验证码/快递/聊天消息/日程提醒绝不能拦截")
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
