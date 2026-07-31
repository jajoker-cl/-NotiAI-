package com.donotnotify.donotnotify

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 调DeepSeek API判断通知是否重要
 * 返回true=拦截（不重要），false=放行（重要）
 */
object AiFilter {
    private const val TAG = "AiFilter"

    // 各提供商 API 地址
    private const val API_URL_DEEPSEEK = "https://api.deepseek.com/chat/completions"
    private const val API_URL_MIMO = "https://api.xiaomimimo.com/v1/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var lastResult: AiResult? = null

    // SMS预判断缓存：SmsInterceptor提前调API，NotificationBlockerService直接用
    private val smsCache = LinkedHashMap<String, AiResult>(10, 0.75f, true)
    private const val CACHE_TTL_MS = 30_000L // 30秒有效

    data class AiResult(val shouldBlock: Boolean, val reason: String)

    /**
     * 同步调用（在后台线程执行）
     * @return null=API调用失败，非null=判断结果
     */
    /**
     * 非阻塞预判断（SmsInterceptor调用）：后台线程调API，结果缓存
     */
    fun preWarm(ctx: Context, packageName: String, title: String?, text: String?) {
        Thread {
            decide(ctx, packageName, title, text)
        }.start()
    }

    fun decide(ctx: Context, packageName: String, title: String?, text: String?): AiResult? {
        // 检查SMS缓存
        val cacheKey = "$packageName|${title}|${text}"
        val cached = smsCache[cacheKey]
        if (cached != null) {
            smsCache.remove(cacheKey)
            return cached
        }
        val provider = AiFilterSettings.getProvider(ctx)
        val apiKey = AiFilterSettings.getApiKey(ctx)
        if (apiKey.isBlank()) return AiResult(shouldBlock = false, reason = "未配置API Key")

        val customRule = AiFilterSettings.getCustomRule(ctx)
        val feedback = AiFilterSettings.getFeedback(ctx)

        val prompt = buildPrompt(packageName, title, text, customRule, feedback)

        try {
            val body = JSONObject().apply {
                put("model", AiFilterSettings.getCurrentModel(ctx))
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", """你是手机通知过滤器。判断每条通知是否垃圾/推广，应拦截。

只回答JSON: {"block":true,"reason":"简短原因"} 或 {"block":false,"reason":"简短原因"}

应拦截(block=true)的通知类型：
- 营销推广：优惠券、促销、打折、大促、限时抢购、直播带货
- 垃圾广告：贷款、刷单、兼职、加微信、赌博、色情
- 无意义系统推送：天气预警以外的「猜你喜欢」「热门推荐」
- 重复骚扰：同一App短时间内大量相似通知

必须放行(block=false)的通知类型：
- 验证码、登录确认、安全提醒
- 银行交易、支付、账单提醒
- 快递物流、订单状态更新
- 日历提醒、闹钟、会议通知
- 即时通讯私聊消息（微信/QQ等个人对话）
- 系统功能通知（截图、更新完成等）

不确定的边界情况 -> 放行
宁可漏拦也不误拦。拿不准就放行(block=false)。

用户反馈中有 [IMPORTANT] 标记的，下次遇到同类通知必须放行。
用户反馈中有 [NOT_IMPORTANT] 标记的，下次遇到同类通知必须拦截。
不要输出JSON以外的任何内容。""")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.1)
                put("max_tokens", 150)
            }

            val apiUrl = if (provider == AiFilterSettings.PROVIDER_MIMO) API_URL_MIMO else API_URL_DEEPSEEK

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return AiResult(shouldBlock = false, reason = "响应体为空")

            if (!response.isSuccessful) {
                val errMsg = "HTTP ${response.code}: ${responseBody.take(100)}"
                Log.w(TAG, "API error: $errMsg")
                return AiResult(shouldBlock = false, reason = errMsg)
            }

            // 解析DeepSeek返回的JSON
            val result = parseResponse(responseBody)
            val finalResult = result ?: AiResult(shouldBlock = false, reason = "JSON解析失败: ${responseBody.take(80)}")
            // 缓存结果（SMS预判断用）
            smsCache[cacheKey] = finalResult
            return finalResult

        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.javaClass.simpleName} - ${e.message}")
            return AiResult(shouldBlock = false, reason = "网络错误: ${e.javaClass.simpleName} - ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.javaClass.simpleName} - ${e.message}")
            return AiResult(shouldBlock = false, reason = "异常: ${e.javaClass.simpleName} - ${e.message}")
        }
    }

    fun getLastResult(): AiResult? = lastResult

    private fun buildPrompt(
        pkg: String,
        title: String?,
        text: String?,
        customRule: String,
        feedback: List<String>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("判断这条手机通知是否重要，需要我放行（响铃提醒用户）：")
        sb.appendLine("应用: $pkg")
        sb.appendLine("标题: ${title ?: "(无)"}")
        sb.appendLine("内容: ${text ?: "(无)"}")

        if (customRule.isNotBlank()) {
            sb.appendLine("\n用户的额外要求:")
            sb.appendLine(customRule)
        }

        if (feedback.isNotEmpty()) {
            sb.appendLine("\n用户的历史反馈（请严格参考）：")
            sb.appendLine("[IMPORTANT]=用户认为重要应放行 / [NOT_IMPORTANT]=用户认为应拦截")
            sb.appendLine("包含用户纠错（你判错了）和用户确认（你判对了），都要遵守。")
            for (fb in feedback.take(10)) {
                sb.appendLine("  $fb")
            }
            sb.appendLine("不要再犯用户纠正过的错误。用户确认正确的判断要保持一致。")
        }

        sb.appendLine("\n哪些通知应该放行？")
        sb.appendLine("- 银行交易、快递物流、验证码、家人消息、重要日程提醒")
        sb.appendLine("- 用户自定义的重要类型")
        sb.appendLine("\n哪些应该拦截？")
        sb.appendLine("- 广告推送、系统不重要提示、社交媒体无关通知、促销信息")

        return sb.toString()
    }

    private fun parseResponse(json: String): AiResult? {
        return try {
            val obj = JSONObject(json)
            val choices = obj.getJSONArray("choices")
            val content = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            // 尝试解析DeepSeek返回的JSON
            val cleanContent = content
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val resultObj = JSONObject(cleanContent)
            val block = resultObj.optBoolean("block", false)
            val reason = resultObj.optString("reason", "")

            val aiResult = AiResult(block, reason)
            lastResult = aiResult
            aiResult
        } catch (e: Exception) {
            // JSON解析失败，尝试从文本中判断
            if (json.contains("\"block\":true") || json.contains("\"block\": true")) {
                AiResult(shouldBlock = true, reason = "解析判断")
            } else if (json.contains("\"block\":false") || json.contains("\"block\": false")) {
                AiResult(shouldBlock = false, reason = "解析判断")
            } else {
                null
            }
        }
    }
}
