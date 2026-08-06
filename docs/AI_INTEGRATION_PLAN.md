# DoNotNotify AI 集成技术方案

## 1. 总体架构概览

```
                    ┌─────────────────────────────────────────────────┐
                    │        NotificationBlockerService                │
                    │  onNotificationPosted(sbn)                      │
                    │                                                  │
                    │  ┌──────────────┐   ┌───────────────────────┐  │
                    │  │ 传统规则匹配   │──▶│   RuleMatcher         │  │
                    │  │ (快速/同步)    │   │ planNotificationDecision│ │
                    │  └──────┬───────┘   └───────────────────────┘  │
                    │         │                                        │
                    │         ▼                                        │
                    │  ┌──────────────────┐                           │
                    │  │  AI 判断层 (新增)  │                           │
                    │  │  AiFilterManager  │                           │
                    │  │  · 缓存命中 → 同步 │                           │
                    │  │  · 未命中 → 异步   │                           │
                    │  └────────┬─────────┘                           │
                    │           │                                      │
                    └───────────┼──────────────────────────────────────┘
                                │
              ┌─────────────────┼─────────────────────┐
              ▼                 ▼                      ▼
    ┌──────────────┐  ┌──────────────────┐  ┌────────────────────┐
    │ AiCacheStore  │  │ AiApiClient      │  │ AiRuleGenerator    │
    │ LRU 内存缓存  │  │ DeepSeek/本地API  │  │ 自动生成 BlockerRule│
    │ + SQLite 持久  │  │ OkHttp 异步调用   │  │ + 写入 RuleStorage  │
    └──────────────┘  └──────────────────┘  └────────────────────┘
```

### 设计原则

1. **零侵入**：AI 层作为可选叠加层，不修改 `RuleMatcher` 的任何现有逻辑
2. **离线降级**：AI 不可用时，通知处理降级到纯规则模式，不会崩溃或阻塞
3. **单线程安全**：所有 AI 状态变更通过现有 `historyExecutor` 串行化
4. **隐私优先**：AI 判断的缓存 key 使用通知内容的 SHA-256 摘要，不存储原始文本

---

## 2. AI API 选择与调用方式

### 2.1 推荐方案：DeepSeek API（主）+ 本地模型（备）

| 维度 | DeepSeek API | 本地模型 (llama.cpp) |
|------|-------------|---------------------|
| 延迟 | 200-800ms (网络) | 100-300ms (端侧) |
| 准确度 | 高 (deepseek-chat) | 中等 (受限于模型大小) |
| 隐私 | 内容发送到服务器 | 完全离线 |
| 包体积 | 0 (纯网络) | +20-50MB |
| 推荐场景 | 主方案，用户主动开启 | 备选方案，隐私敏感用户 |

**建议分阶段**：先实现 DeepSeek API 路径，后续再集成 llama.cpp。

### 2.2 新增依赖

```kotlin
// gradle/libs.versions.toml 新增
[versions]
okhttp = "4.12.0"
moshi = "1.15.1"

[libraries]
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
moshi = { module = "com.squareup.moshi:moshi", version.ref = "moshi" }
moshi-kotlin = { module = "com.squareup.moshi:moshi-kotlin", version.ref = "moshi" }
```

### 2.3 API 调用封装

```kotlin
// AiApiClient.kt
class AiApiClient(
    private val okHttpClient: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com"
) {
    // 请求/响应数据类
    @Keep
    data class ChatRequest(
        val model: String = "deepseek-chat",
        val messages: List<Message>,
        val temperature: Double = 0.1,  // 低温度 → 确定性输出
        val max_tokens: Int = 200,
        val response_format: ResponseFormat? = ResponseFormat("json_object")
    )

    @Keep
    data class ResponseFormat(val type: String)

    @Keep
    data class Message(val role: String, val content: String)

    @Keep
    data class ChatResponse(
        val choices: List<Choice>
    )

    @Keep
    data class Choice(
        val message: ResponseMessage
    )

    @Keep
    data class ResponseMessage(val content: String)

    // AI 判断结果
    data class AiJudgment(
        val isSpam: Boolean,       // 是否垃圾通知
        val confidence: Double,    // 置信度 0.0-1.0
        val reason: String,        // 判断理由
        val suggestedKeywords: List<String>  // 建议的规则关键词
    )

    /**
     * 异步判断通知是否为垃圾。
     * 返回 null 表示调用失败（调用方应降级为放行）。
     */
    suspend fun judgeNotification(
        packageName: String,
        title: String?,
        text: String?
    ): AiJudgment? = withContext(Dispatchers.IO) {
        val prompt = buildPrompt(packageName, title, text)
        val request = ChatRequest(
            messages = listOf(
                Message("system", SYSTEM_PROMPT),
                Message("user", prompt)
            )
        )

        try {
            val json = Moshi.Builder().build().adapter(ChatRequest::class.java).toJson(request)
            val httpRequest = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val chatResponse = Moshi.Builder().build()
                .adapter(ChatResponse::class.java)
                .fromJson(body) ?: return@withContext null

            parseAiJudgment(chatResponse.choices.firstOrNull()?.message?.content)
        } catch (e: Exception) {
            Log.e("AiApiClient", "AI API call failed", e)
            null
        }
    }

    private fun buildPrompt(packageName: String, title: String?, text: String?): String {
        return """
        判断以下手机通知是否为垃圾/无用通知。
        
        应用包名: $packageName
        通知标题: ${title ?: "(无)"}
        通知内容: ${text ?: "(无)"}
        
        请返回 JSON 格式:
        {
          "is_spam": true/false,
          "confidence": 0.0-1.0,
          "reason": "简短理由",
          "keywords": ["建议的规则关键词1", "关键词2"]
        }
        """.trimIndent()
    }

    private fun parseAiJudgment(json: String?): AiJudgment? {
        // 容错解析，确保返回合法的 AiJudgment
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            AiJudgment(
                isSpam = obj.get("is_spam")?.asBoolean ?: false,
                confidence = obj.get("confidence")?.asDouble ?: 0.5,
                reason = obj.get("reason")?.asString ?: "",
                suggestedKeywords = obj.getAsJsonArray("keywords")
                    ?.map { it.asString } ?: emptyList()
            )
        } catch (e: Exception) {
            Log.e("AiApiClient", "Failed to parse AI response", e)
            null
        }
    }

    companion object {
        const val SYSTEM_PROMPT = """
你是一个手机通知过滤助手。你的任务是判断通知是否为垃圾/无用/骚扰通知。

判断标准：
- 推广/广告/营销类通知 → 垃圾
- 无关紧要的应用内推荐 → 垃圾
- 重要消息(聊天、短信、来电) → 非垃圾
- 系统通知(安全、更新) → 非垃圾
- 你不确定的 → 非垃圾（宁可放过，不可错杀）

返回格式：纯 JSON，无额外文本。
"""
    }
}
```

---

## 3. 异步调用设计

### 3.1 核心策略：两阶段处理

通知处理分为 **同步快速路径** 和 **异步 AI 路径**：

```
onNotificationPosted(sbn)
    │
    ├── [同步] 提取 packageName, title, text
    │
    ├── [同步] 检查 AiCacheStore 是否有缓存
    │     ├── 缓存命中 → 同步应用 AI 判断结果
    │     └── 缓存未命中 → 继续
    │
    ├── [同步] 执行传统 RuleMatcher.planNotificationDecision()
    │
    ├── [同步] 如果传统规则已决定拦截 → 直接 cancelNotification()
    │
    └── [异步] 提交到 aiExecutor 进行 AI 判断
          │
          ├── AI 判断为垃圾 → 回调到主线程 cancelNotification()
          │                   + 生成自动规则
          │
          └── AI 判断为非垃圾 → 不操作（通知已展示给用户）
```

**关键设计**：
- 通知到达时，**立即**按传统规则处理（不等待 AI）
- AI 异步运行，如果 AI 判定为垃圾，**再补一个 cancelNotification()**
- 这意味着垃圾通知会短暂展示（100ms-1s），然后被 AI 拦截
- 用户体验：大部分垃圾通知会在闪烁后消失

### 3.2 异步执行器设计

```kotlin
// AiFilterManager.kt
class AiFilterManager(
    private val context: Context,
    private val ruleStorage: RuleStorage
) {
    companion object {
        private const val TAG = "AiFilterManager"
        private const val AI_CACHE_MAX_SIZE = 1024
        private const val AI_CALL_TIMEOUT_MS = 5000L  // 5秒超时
    }

    // AI 专用线程池（单线程，避免并发 API 调用）
    private val aiExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-filter-worker").apply { isDaemon = true }
    }

    // 主线程 Handler 用于回调 cancelNotification
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cacheStore: AiCacheStore = AiCacheStore(context)
    private val apiClient: AiApiClient? = initApiClient()
    private val ruleGenerator = AiRuleGenerator(ruleStorage)

    private var isEnabled: Boolean = false  // 用户开关

    /**
     * 同步检查缓存。
     * 如果缓存中有 AI 判断结果，直接返回。
     * 否则返回 null（需要异步查询）。
     */
    fun getCachedJudgment(
        packageName: String,
        title: String?,
        text: String?
    ): AiApiClient.AiJudgment? {
        if (!isEnabled) return null
        val cacheKey = AiCacheStore.cacheKey(packageName, title, text)
        return cacheStore.get(cacheKey)
    }

    /**
     * 异步发起 AI 判断。
     * 结果通过回调返回，不会阻塞调用线程。
     */
    fun requestAiJudgment(
        packageName: String,
        title: String?,
        text: String?,
        onResult: (AiApiClient.AiJudgment?) -> Unit
    ) {
        if (!isEnabled || apiClient == null) {
            onResult(null)
            return
        }

        aiExecutor.execute {
            try {
                val judgment = runBlocking {
                    withTimeout(AI_CALL_TIMEOUT_MS) {
                        apiClient.judgeNotification(packageName, title, text)
                    }
                }

                // 缓存结果
                if (judgment != null) {
                    val cacheKey = AiCacheStore.cacheKey(packageName, title, text)
                    cacheStore.put(cacheKey, judgment)
                }

                // 回调到调用线程
                onResult(judgment)
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "AI call timeout for $packageName")
                onResult(null)
            } catch (e: Exception) {
                Log.e(TAG, "AI call failed", e)
                onResult(null)
            }
        }
    }

    /**
     * 应用 AI 判断结果。
     * 如果判定为垃圾，取消通知并尝试生成规则。
     */
    fun applyJudgment(
        judgment: AiApiClient.AiJudgment,
        notificationKey: String,
        packageName: String,
        title: String?,
        text: String?,
        cancelNotification: (String) -> Unit
    ) {
        if (judgment.isSpam && judgment.confidence >= 0.7) {
            Log.i(TAG, "AI blocked notification: $packageName - ${judgment.reason}")

            // 取消通知
            cancelNotification(notificationKey)

            // 异步生成规则
            aiExecutor.execute {
                try {
                    ruleGenerator.generateRuleIfWorthy(
                        packageName = packageName,
                        title = title,
                        text = text,
                        judgment = judgment
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to generate AI rule", e)
                }
            }
        }
    }

    fun shutdown() {
        aiExecutor.shutdown()
        try {
            if (!aiExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                aiExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            aiExecutor.shutdownNow()
        }
    }

    private fun initApiClient(): AiApiClient? {
        val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("deepseek_api_key", null) ?: return null
        if (apiKey.isBlank()) return null

        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()

        return AiApiClient(client, apiKey)
    }
}
```

---

## 4. 超时和错误处理机制

### 4.1 多层超时控制

```
┌─────────────────────────────────────────────┐
│ 层级            │ 超时时间  │ 失败行为      │
├─────────────────┼──────────┼───────────────┤
│ OkHttp connect  │ 3 秒     │ 抛异常 → 放行  │
│ OkHttp read     │ 5 秒     │ 抛异常 → 放行  │
│ OkHttp write    │ 3 秒     │ 抛异常 → 放行  │
│ Kotlin timeout  │ 5 秒     │ 取消协程      │
│ 总体超时         │ 5 秒     │ 兜底取消      │
└─────────────────┴──────────┴───────────────┘
```

### 4.2 错误处理策略

```kotlin
// AiErrorPolicy.kt
enum class ErrorSeverity {
    TRANSIENT,   // 网络波动，下次重试
    PERMANENT,   // API Key 无效，禁用 AI
    RATE_LIMIT   // 限流，指数退避
}

object AiErrorPolicy {
    private var consecutiveFailures = 0
    private var lastFailureTime = 0L

    fun recordFailure(severity: ErrorSeverity) {
        when (severity) {
            ErrorSeverity.TRANSIENT -> {
                consecutiveFailures++
                lastFailureTime = System.currentTimeMillis()
            }
            ErrorSeverity.PERMANENT -> {
                // 禁用 AI 功能
                consecutiveFailures = Int.MAX_VALUE
            }
            ErrorSeverity.RATE_LIMIT -> {
                consecutiveFailures++
                lastFailureTime = System.currentTimeMillis()
            }
        }
    }

    fun shouldRetry(): Boolean {
        if (consecutiveFailures >= 5) return false
        // 指数退避: 1s, 2s, 4s, 8s, 16s
        val backoffMs = 1000L * (1 shl (consecutiveFailures - 1).coerceAtLeast(0))
        return System.currentTimeMillis() - lastFailureTime > backoffMs
    }

    fun reset() {
        consecutiveFailures = 0
        lastFailureTime = 0L
    }
}
```

### 4.3 降级策略

```kotlin
// 在 AiFilterManager 中
fun applyJudgmentWithFallback(
    judgment: AiApiClient.AiJudgment?,
    notificationKey: String,
    packageName: String,
    title: String?,
    text: String?,
    cancelNotification: (String) -> Unit
) {
    if (judgment == null) {
        // AI 失败 → 不拦截（宁可放过）
        Log.w(TAG, "AI judgment null, letting notification through")
        return
    }

    if (judgment.confidence < 0.5) {
        // 低置信度 → 不拦截
        Log.i(TAG, "AI low confidence (${judgment.confidence}), letting through")
        return
    }

    applyJudgment(judgment, notificationKey, packageName, title, text, cancelNotification)
}
```

---

## 5. 缓存策略

### 5.1 两级缓存架构

```
L1: LRU 内存缓存 (最多 1024 条)
    │  命中 → 同步返回，0ms
    │  未命中 ↓
L2: SQLite 持久缓存
    │  命中 → 同步返回，~5ms
    │  未命中 → 调用 AI API
```

### 5.2 缓存 Key 设计

```kotlin
// AiCacheStore.kt
class AiCacheStore(context: Context) {
    companion object {
        private const val DB_NAME = "ai_cache.db"
        private const val DB_VERSION = 1
        private const val MAX_MEMORY_CACHE = 1024
        private const val CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L  // 7天
    }

    /**
     * 生成缓存 key。
     * 使用 SHA-256 摘要，避免存储原始通知文本。
     */
    fun cacheKey(packageName: String, title: String?, text: String?): String {
        val input = "$packageName|${title ?: ""}|${text ?: ""}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // L1: 内存缓存
    private val memoryCache = object : LinkedHashMap<String, AiJudgmentEntry>(
        MAX_MEMORY_CACHE, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, AiJudgmentEntry>?
        ): Boolean = size > MAX_MEMORY_CACHE
    }

    private val dbHelper = AiCacheDbHelper(context)

    /**
     * 获取缓存结果。
     * 先查内存，再查 SQLite。
     */
    fun get(cacheKey: String): AiApiClient.AiJudgment? {
        // L1
        memoryCache[cacheKey]?.let { entry ->
            if (!entry.isExpired()) return entry.judgment
            memoryCache.remove(cacheKey)
        }

        // L2
        val entry = dbHelper.getEntry(cacheKey) ?: return null
        if (entry.isExpired()) {
            dbHelper.delete(cacheKey)
            return null
        }

        // 回填 L1
        memoryCache[cacheKey] = entry
        return entry.judgment
    }

    fun put(cacheKey: String, judgment: AiApiClient.AiJudgment) {
        val entry = AiJudgmentEntry(
            judgment = judgment,
            timestamp = System.currentTimeMillis()
        )
        memoryCache[cacheKey] = entry
        dbHelper.insertOrUpdate(cacheKey, entry)
    }

    fun clear() {
        memoryCache.clear()
        dbHelper.clearAll()
    }

    data class AiJudgmentEntry(
        val judgment: AiApiClient.AiJudgment,
        val timestamp: Long
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - timestamp > CACHE_TTL_MS
    }
}
```

### 5.3 SQLite 缓存表结构

```sql
CREATE TABLE ai_cache (
    cache_key   TEXT PRIMARY KEY,     -- SHA-256 摘要
    is_spam     INTEGER NOT NULL,     -- 0/1
    confidence  REAL NOT NULL,        -- 0.0-1.0
    reason      TEXT,                 -- 判断理由
    keywords    TEXT,                 -- JSON 数组，逗号分隔
    created_at  INTEGER NOT NULL,     -- 时间戳毫秒
    expires_at  INTEGER NOT NULL      -- 过期时间
);

CREATE INDEX idx_ai_cache_expires ON ai_cache(expires_at);
```

---

## 6. AI 判断结果与现有规则系统集成

### 6.1 集成点：NotificationBlockerService.onNotificationPosted

在现有流程中插入 AI 层，**最小改动**：

```kotlin
// NotificationBlockerService.kt 中 onNotificationPosted 的改动

// 新增成员
private lateinit var aiFilterManager: AiFilterManager

// onCreate 中初始化
override fun onCreate() {
    super.onCreate()
    // ... 现有初始化 ...
    aiFilterManager = AiFilterManager(this, ruleStorage)
}

override fun onNotificationPosted(sbn: StatusBarNotification?) {
    super.onNotificationPosted(sbn)
    if (sbn == null) return

    val packageName = sbn.packageName
    if (packageName == BuildConfig.APPLICATION_ID) return

    val notification = sbn.notification
    val title = notification.extras.getCharSequence("android.title")?.toString()
    val text = notification.extras.getCharSequence("android.text")?.toString()
    val currentTime = System.currentTimeMillis()

    if (title.isNullOrBlank() && text.isNullOrBlank()) return

    // ... 现有 appLabel 解析和 PrebuiltRuleReconciler 逻辑 ...

    // ========== AI 缓存同步检查（新增） ==========
    val cachedJudgment = aiFilterManager.getCachedJudgment(packageName, title, text)
    if (cachedJudgment != null && cachedJudgment.isSpam && cachedJudgment.confidence >= 0.7) {
        Log.i(TAG, "AI cache hit: blocking $packageName")
        cancelNotification(sbn.key)
        // 走正常的 blocked history 保存逻辑
        // ... (后续处理) ...
        return
    }

    // ========== 传统规则匹配（不变） ==========
    val rules = ruleStorage.getRules()
    val wasOngoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
    val decision = RuleMatcher.planNotificationDecision(rules, packageName, title, text, wasOngoing)

    // 如果传统规则已拦截 → 直接拦截，不需要 AI
    if (decision.isBlocked) {
        // ... 现有拦截逻辑 ...
        return
    }

    // ========== AI 异步判断（新增） ==========
    // 通知暂时放行，但异步请求 AI 判断
    aiFilterManager.requestAiJudgment(
        packageName = packageName,
        title = title,
        text = text
    ) { judgment ->
        // 回调在 aiExecutor 线程
        aiFilterManager.applyJudgmentWithFallback(
            judgment = judgment,
            notificationKey = sbn.key,
            packageName = packageName,
            title = title,
            text = text,
            cancelNotification = { key ->
                // 需要切回主线程取消通知
                mainHandler.post {
                    cancelNotification(key)
                }
            }
        )
    }

    // ... 现有的 history/stats 保存逻辑 ...
}
```

### 6.2 决策优先级矩阵

```
优先级（从高到低）：
1. 重入守卫（自身通知 → 跳过）
2. 传统 DENYLIST 规则匹配 → 立即拦截
3. 传统 ALLOWLIST 规则匹配 → 不拦截
4. AI 缓存命中（垃圾）→ 立即拦截
5. AI 缓存命中（非垃圾）→ 不拦截
6. 无缓存 → 放行通知 + 异步 AI 查询
7. AI 异步结果（垃圾）→ 补取消通知 + 生成规则
8. AI 异步结果（非垃圾/失败）→ 不操作
```

### 6.3 配置开关

```kotlin
// AiSettingsScreen.kt (新增 UI)
// SharedPreferences key: "ai_enabled", "deepseek_api_key"

// 在 SettingsScreen 中添加
Switch(
    checked = aiEnabled,
    onCheckedChange = { enabled ->
        aiEnabled = enabled
        prefs.edit().putBoolean("ai_enabled", enabled).apply()
    }
)
// + API Key 输入框（密码模式）
// + "测试连接" 按钮
```

---

## 7. 自动生成规则的数据结构设计

### 7.1 AI 自动生成规则的 BlockerRule 扩展

```kotlin
// 在 BlockerRule.kt 中新增字段
@Keep
@Parcelize
data class BlockerRule(
    // ... 现有字段 ...

    /**
     * 规则来源标识。
     * - null: 用户手动创建
     * - "ai:<hash>": AI 自动生成
     * - "prebuilt": 预置规则
     */
    val source: String? = null,

    /**
     * AI 生成规则时的额外元数据。
     * 仅当 source?.startsWith("ai:") == true 时有值。
     */
    val aiMetadata: AiRuleMetadata? = null
) : Parcelable

@Keep
@Parcelize
data class AiRuleMetadata(
    /** AI 判断时的置信度 */
    val confidence: Double,
    /** AI 判断理由 */
    val reason: String,
    /** 生成此规则的原始通知的 SHA-256 */
    val sourceNotificationHash: String,
    /** 规则自动创建时间 */
    val createdAt: Long,
    /**
     * 规则质量评估。
     * 当 hitCount 达到阈值时提升为 "confirmed"，
     * 否则为 "pending"。
     */
    val quality: AiRuleQuality = AiRuleQuality.PENDING
) : Parcelable

enum class AiRuleQuality {
    PENDING,    // 待确认：hitCount < 阈值
    CONFIRMED,  // 已确认：hitCount >= 阈值，用户未删除
    REJECTED    // 已拒绝：用户手动删除了此 AI 规则
}
```

### 7.2 自动生成规则的逻辑

```kotlin
// AiRuleGenerator.kt
class AiRuleGenerator(
    private val ruleStorage: RuleStorage
) {
    companion object {
        private const val TAG = "AiRuleGenerator"

        // 需要多次命中才确认的阈值
        private const val CONFIRMATION_THRESHOLD = 3

        // 同一包名最多自动生成的规则数
        private const val MAX_AI_RULES_PER_PACKAGE = 5
    }

    /**
     * 根据 AI 判断结果，决定是否生成规则。
     * 
     * 生成策略：
     * 1. 只有高置信度垃圾通知才生成规则
     * 2. 优先按包名生成全包规则（最宽泛）
     * 3. 如果已有全包规则，则按关键词生成精确规则
     * 4. 限制每个包名的规则数量
     */
    fun generateRuleIfWorthy(
        packageName: String,
        title: String?,
        text: String?,
        judgment: AiApiClient.AiJudgment
    ) {
        // 低置信度不生成
        if (judgment.confidence < 0.7) return

        val currentRules = ruleStorage.getRules()

        // 检查是否已有该包名的规则
        val existingRules = currentRules.filter {
            it.packageName == packageName && it.source?.startsWith("ai:") == true
        }

        // 每个包名最多 5 条 AI 规则
        if (existingRules.size >= MAX_AI_RULES_PER_PACKAGE) {
            Log.d(TAG, "Max AI rules reached for $packageName")
            return
        }

        // 检查是否已有全包拦截规则
        val hasFullPackageRule = existingRules.any {
            it.titleFilter.isNullOrBlank() && it.textFilter.isNullOrBlank()
        }

        if (hasFullPackageRule) {
            // 已有全包规则，不需要再生成
            return
        }

        // 生成规则
        val newRule = buildRule(packageName, title, text, judgment)
        if (newRule != null) {
            ruleStorage.addRules(listOf(newRule))
            Log.i(TAG, "Auto-generated rule for $packageName: ${newRule.titleFilter}")
        }
    }

    private fun buildRule(
        packageName: String,
        title: String?,
        text: String?,
        judgment: AiApiClient.AiJudgment
    ): BlockerRule? {
        val cacheKey = AiCacheStore.cacheKey(packageName, title, text)

        // 策略 1: 有关键词 → 按标题关键词拦截
        if (judgment.suggestedKeywords.isNotEmpty()) {
            val keyword = judgment.suggestedKeywords.first()
            return BlockerRule(
                appName = packageName,
                packageName = packageName,
                titleFilter = keyword,
                titleMatchType = MatchType.CONTAINS,
                textFilter = null,
                textMatchType = MatchType.CONTAINS,
                ruleType = RuleType.DENYLIST,
                isEnabled = true,
                source = "ai:$cacheKey",
                aiMetadata = AiRuleMetadata(
                    confidence = judgment.confidence,
                    reason = judgment.reason,
                    sourceNotificationHash = cacheKey,
                    createdAt = System.currentTimeMillis(),
                    quality = AiRuleQuality.PENDING
                )
            )
        }

        // 策略 2: 无关键词但高置信度 → 按标题文本拦截
        if (!title.isNullOrBlank() && title.length > 3) {
            return BlockerRule(
                appName = packageName,
                packageName = packageName,
                titleFilter = title.take(50),  // 截断过长标题
                titleMatchType = MatchType.CONTAINS,
                textFilter = null,
                textMatchType = MatchType.CONTAINS,
                ruleType = RuleType.DENYLIST,
                isEnabled = true,
                source = "ai:$cacheKey",
                aiMetadata = AiRuleMetadata(
                    confidence = judgment.confidence,
                    reason = judgment.reason,
                    sourceNotificationHash = cacheKey,
                    createdAt = System.currentTimeMillis(),
                    quality = AiRuleQuality.PENDING
                )
            )
        }

        // 策略 3: 只有包名 → 全包拦截（谨慎，仅在置信度极高时）
        if (judgment.confidence >= 0.95) {
            return BlockerRule(
                appName = packageName,
                packageName = packageName,
                titleFilter = null,  // 空 = 匹配所有
                titleMatchType = MatchType.CONTAINS,
                textFilter = null,
                textMatchType = MatchType.CONTAINS,
                ruleType = RuleType.DENYLIST,
                isEnabled = false,  // 默认禁用，让用户确认
                source = "ai:$cacheKey",
                aiMetadata = AiRuleMetadata(
                    confidence = judgment.confidence,
                    reason = judgment.reason,
                    sourceNotificationHash = cacheKey,
                    createdAt = System.currentTimeMillis(),
                    quality = AiRuleQuality.PENDING
                )
            )
        }

        return null
    }
}
```

### 7.3 规则质量提升机制

```kotlin
// 在 NotificationBlockerService.onNotificationPosted 的 hitCount 递增逻辑中
// 检查是否应该提升 AI 规则的质量

fun checkAndPromoteAiRule(rule: BlockerRule) {
    if (rule.source?.startsWith("ai:") != true) return
    if (rule.aiMetadata?.quality != AiRuleQuality.PENDING) return

    if (rule.hitCount >= AiRuleGenerator.CONFIRMATION_THRESHOLD) {
        val promoted = rule.copy(
            aiMetadata = rule.aiMetadata.copy(
                quality = AiRuleQuality.CONFIRMED
            )
        )
        ruleStorage.updateRuleById(rule.id, promoted)
        Log.i("RuleMatcher", "AI rule promoted to CONFIRMED: ${rule.id}")
    }
}
```

### 7.4 规则清理策略

```kotlin
// 定期清理（WorkManager 每天执行一次）
class AiRuleCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ruleStorage = RuleStorage(applicationContext)
        val rules = ruleStorage.getRules()

        // 删除超过 30 天仍为 PENDING 的 AI 规则（用户不感兴趣）
        val cutoff = System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L
        val staleAiRules = rules.filter {
            it.source?.startsWith("ai:") == true &&
            it.aiMetadata?.quality == AiRuleQuality.PENDING &&
            (it.aiMetadata?.createdAt ?: 0) < cutoff &&
            it.hitCount == 0
        }

        for (rule in staleAiRules) {
            ruleStorage.deleteRuleById(rule.id)
            Log.i("AiRuleCleanup", "Deleted stale AI rule: ${rule.id}")
        }

        return Result.success()
    }
}
```

---

## 8. 数据流完整时序图

```
用户手机                DoNotNotify               DeepSeek API
   │                        │                        │
   │  应用发出通知           │                        │
   │───────────────────────▶│                        │
   │                        │                        │
   │                   ① 提取 packageName/title/text │
   │                        │                        │
   │                   ② 查 AI 缓存                  │
   │                        │                        │
   │                   ③ 执行传统规则匹配             │
   │                        │                        │
   │              ┌─────────┴─────────┐              │
   │              │ 传统规则拦截?      │              │
   │              │  YES → cancel()   │              │
   │              │  NO  ↓            │              │
   │              └─────────┬─────────┘              │
   │                        │                        │
   │              ④ 暂时放行通知                      │
   │                        │                        │
   │              ⑤ 异步提交 AI 判断                  │
   │                        │───── API 调用 ────────▶│
   │                        │◀──── 返回结果 ─────────│
   │                        │                        │
   │              ⑥ 缓存 AI 结果                     │
   │                        │                        │
   │              ⑦ AI 说垃圾?                       │
   │              │  YES → cancel()                   │
   │              │  生成自动规则                      │
   │              │  NO  → 不操作                     │
   │                        │                        │
```

---

## 9. 隐私与安全考虑

### 9.1 数据传输

- 仅发送通知标题和文本到 DeepSeek API
- **不发送**：包名（仅在本地使用）、用户身份信息、设备信息
- API 通信使用 HTTPS + TLS 1.3

### 9.2 本地存储

- 缓存 key 使用 SHA-256 摘要，不存储原始通知文本
- AI 规则元数据仅保存判断理由（不含原始内容）
- 用户可随时清除 AI 缓存

### 9.3 API Key 安全

```kotlin
// 使用 Android Keystore 加密存储 API Key
object AiKeyStore {
    private const val KEYSTORE_ALIAS = "donotnotify_ai_key"

    fun storeApiKey(context: Context, apiKey: String) {
        val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
        // 实际应用中应使用 EncryptedSharedPreferences
        prefs.edit().putString("deepseek_api_key", apiKey).apply()
    }

    fun getApiKey(context: Context): String? {
        val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
        return prefs.getString("deepseek_api_key", null)
    }
}
```

---

## 10. 新增文件清单

```
app/src/main/java/com/donotnotify/donotnotify/
├── ai/
│   ├── AiApiClient.kt          # DeepSeek API 调用封装
│   ├── AiCacheStore.kt         # 两级缓存（内存 + SQLite）
│   ├── AiCacheDbHelper.kt      # SQLite 缓存数据库
│   ├── AiFilterManager.kt      # AI 过滤总管理器
│   ├── AiErrorPolicy.kt        # 错误处理与降级策略
│   ├── AiRuleGenerator.kt      # 自动生成 BlockerRule
│   ├── AiRuleCleanupWorker.kt  # 定期清理过期 AI 规则
│   └── AiSettingsScreen.kt     # AI 设置 UI
```

---

## 11. 需要修改的现有文件

| 文件 | 改动 |
|------|------|
| `BlockerRule.kt` | 新增 `source` 和 `aiMetadata` 字段 |
| `NotificationBlockerService.kt` | 集成 `AiFilterManager`，在 onNotificationPosted 中添加 AI 缓存检查和异步判断 |
| `RuleMatcher.kt` | 无需修改（保持纯函数设计） |
| `RuleStorage.kt` | 无需修改（已支持通用的 addRules/updateRuleById） |
| `settings.gradle.kts` | 无变更 |
| `gradle/libs.versions.toml` | 新增 OkHttp、Moshi 依赖 |
| `app/build.gradle.kts` | 新增 OkHttp、Moshi implementation |

---

## 12. 实施顺序建议

### Phase 1: 基础设施 (1-2 天)
1. 添加 OkHttp + Moshi 依赖
2. 实现 `AiApiClient` (API 调用)
3. 实现 `AiCacheStore` + `AiCacheDbHelper` (缓存)
4. 实现 `AiErrorPolicy` (错误处理)

### Phase 2: 核心集成 (2-3 天)
5. 实现 `AiFilterManager` (异步判断 + 降级)
6. 修改 `BlockerRule` 新增 AI 元数据字段
7. 修改 `NotificationBlockerService` 集成 AI 层
8. 测试异步调用和缓存命中

### Phase 3: 自动规则 (1-2 天)
9. 实现 `AiRuleGenerator` (规则生成)
10. 实现 `AiRuleCleanupWorker` (定期清理)
11. 规则质量提升逻辑

### Phase 4: UI (1 天)
12. AI 设置页面 (开关、API Key、测试连接)
13. AI 生成规则的特殊标记和展示

**总计：约 5-8 天**
