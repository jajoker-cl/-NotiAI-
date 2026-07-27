package com.donotnotify.donotnotify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.donotnotify.donotnotify.setup.SetupState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NotificationBlockerService : NotificationListenerService() {

    private val TAG = "NotificationBlockerService"
    private lateinit var ruleStorage: RuleStorage
    private lateinit var notificationHistoryStorage: NotificationHistoryStorage
    private lateinit var blockedNotificationHistoryStorage: BlockedNotificationHistoryStorage
    private lateinit var statsStorage: StatsStorage
    private lateinit var unmonitoredAppsStorage: UnmonitoredAppsStorage
    private lateinit var appInfoStorage: AppInfoStorage

    companion object {
        const val ACTION_HISTORY_UPDATED = "com.donotnotify.donotnotify.HISTORY_UPDATED"
        private const val DEBOUNCE_PERIOD_MS = 5000L
        private val HEARTBEAT_INTERVAL_MS = TimeUnit.HOURS.toMillis(1)
    }

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable: Runnable = object : Runnable {
        override fun run() {
            SetupState.recordListenerConnected(this@NotificationBlockerService)
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private val recentlyBlocked = mutableMapOf<String, Long>()
    private val historyExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "history-writer").apply { isDaemon = true }
    }

    private val stackPoster: StackedNotificationManager.StackPoster by lazy {
        StackedNotificationManager.AndroidStackPoster(
            context = this,
            activeProvider = {
                try {
                    (activeNotifications ?: emptyArray()).asList()
                        .filter { it.packageName == BuildConfig.APPLICATION_ID }
                        .map {
                            StackedNotificationManager.ActiveStackNote(
                                listenerKey = it.key,
                                groupKey = it.notification.group ?: ""
                            )
                        }
                } catch (e: Exception) {
                    Log.w(TAG, "activeNotifications unavailable", e)
                    emptyList()
                }
            },
            keyCanceller = { key -> cancelNotification(key) }
        )
    }

    override fun onCreate() {
        super.onCreate()
        ruleStorage = RuleStorage(this)
        notificationHistoryStorage = NotificationHistoryStorage(this)
        blockedNotificationHistoryStorage = BlockedNotificationHistoryStorage(this)
        statsStorage = StatsStorage(this)
        unmonitoredAppsStorage = UnmonitoredAppsStorage(this)
        appInfoStorage = AppInfoStorage(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName

        // Reentrancy guard (FIRST): our own re-posted stack notifications must never
        // re-enter rule/history/stack processing or we recurse infinitely.
        if (packageName == BuildConfig.APPLICATION_ID) return

        val notification = sbn.notification
        val title = notification.extras.getCharSequence("android.title")?.toString()
        val text = notification.extras.getCharSequence("android.text")?.toString()
        val currentTime = System.currentTimeMillis()

        if (title.isNullOrBlank() && text.isNullOrBlank()) {
            Log.i(TAG, "Ignoring notification with no title and text from ${sbn.packageName}")
            return
        }

        var appLabel = resolveAppName(this, sbn).toString()
        val savedAppName = appInfoStorage.isAppInfoSaved(packageName)

        // Save App Info if not exists
        if (savedAppName == null || savedAppName == packageName) {
            try {
                // Extract app name from notification extras or fallback to package name
                val appName = appLabel

                // Extract app icon from notification
                val iconDrawable = notification.smallIcon?.loadDrawable(this)

                if (iconDrawable != null) {
                    appInfoStorage.saveAppInfo(packageName, appName, iconDrawable)
                } else {
                    Log.w(TAG, "Could not load icon for $packageName")
                }

                if (savedAppName == packageName) {
                    historyExecutor.execute {
                        notificationHistoryStorage.updateAppLabelForPackage(packageName, appLabel)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save app info for $packageName", e)
            }
        } else {
            appLabel = savedAppName
        }

        Log.i(TAG, "Notification Received: App='${appLabel}', Title='${title}', Text='${text}'")

        // Authoritative guard: the listener process can survive a device-language change while
        // staying connected, so reconcile stale fail-closed prebuilt allowlists here, before
        // rules are read, regardless of whether the UI ever ran. Cheap + idempotent (no-op when
        // the locale is unchanged).
        PrebuiltRuleReconciler.reconcileIfLocaleChanged(this)

        // ★ AI模式：同步判断（和原规则引擎一样，拦截在声音播放之前）
        if (AiFilterSettings.isAiModeEnabled(this)) {
            val apiKey = AiFilterSettings.getApiKey(this)
            if (apiKey.isNotBlank()) {
                // 没有标题也没有内容的通知（纯图标），不浪费API，直接放行
                if (title.isNullOrBlank() && text.isNullOrBlank()) {
                    Log.d(TAG, "Skip AI: empty notification from $packageName")
                } else {
                val startTime = System.currentTimeMillis()
                // 后台线程调API，主线程等结果（最多5秒）
                var blocked = false
                var reason = "异常-默认放行"
                try {
                    val future = java.util.concurrent.CompletableFuture.supplyAsync {
                        AiFilter.decide(this@NotificationBlockerService, packageName, title, text)
                    }
                    val result = future.get(3, TimeUnit.SECONDS)
                    blocked = result?.shouldBlock ?: false
                    reason = result?.reason ?: reason
                } catch (e: Exception) {
                    Log.w(TAG, "AI timeout/error: ${e.message}")
                    reason = "超时/异常-放行"
                }
                val duration = System.currentTimeMillis() - startTime
                AiLogStorage.addLog(this, packageName, title, text, reason, blocked, duration)

                if (blocked) {
                    cancelNotification(sbn.key)
                    historyExecutor.execute {
                        val sn = SimpleNotification(appLabel, packageName, title, text, currentTime, wasOngoing = false)
                        blockedNotificationHistoryStorage.saveNotification(sn)
                        statsStorage.incrementBlockedNotificationsCount()
                        sendBroadcast(Intent(ACTION_HISTORY_UPDATED))
                    }
                    return
                }
                } // end if-else empty check
                // AI说放行，继续往下走正常流程
            }
        }

        // Pure precedence resolution (DENYLIST/allowlist-gating wins over STACK;
        // first enabled match wins; STACK never gates like allowlist).
        val rules = ruleStorage.getRules()
        val wasOngoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val decision = RuleMatcher.planNotificationDecision(rules, packageName, title, text, wasOngoing)
        val isBlocked = decision.isBlocked
        val matchedRule: BlockerRule? = decision.matchedDenylistRule
        val matchedRuleIndices = decision.matchedRuleIndices

        if (isBlocked && matchedRule == null) {
            Log.i(TAG, "Blocking notification from $packageName because it did not match any allowlist rule.")
        }

        if (isBlocked) {
            // Cancel immediately on binder thread
            if (wasOngoing) {
                Log.w(TAG, "Attempting to block an ongoing notification. Cancellation may not be possible. Key: ${sbn.key}")
            }
            Log.i(TAG, "Blocking notification from $packageName. Matched rule: $matchedRule")
            cancelNotification(sbn.key)
            // ★ AI校验层：规则拦截后，AI后台复查
            // 非AI模式（纯规则模式）下也运行，因为规则可能是AI生成的
            if (AiFilterSettings.isAiModeEnabled(this) || AiFilterSettings.getApiKey(this).isNotBlank()) {
                val ruleBlockedPkg = packageName
                val ruleBlockedTitle = title
                val ruleBlockedText = text
                val ruleBlockedLabel = appLabel.toString()
                val ruleBlockedKey = sbn.key
                historyExecutor.execute {
                    try {
                        val aiCheck = AiFilter.decide(this@NotificationBlockerService, ruleBlockedPkg, ruleBlockedTitle, ruleBlockedText)
                        // AI说应该放行，但规则拦截了 → 重新放出来
                        if (aiCheck != null && !aiCheck.shouldBlock) {
                            Log.w(TAG, "AI disagrees with rule: re-posting $ruleBlockedPkg")
                            // 重新发通知
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                            val chId = "ai_conflict"
                            val ch = android.app.NotificationChannel(chId, "拦截存疑", android.app.NotificationManager.IMPORTANCE_HIGH)
                            nm.createNotificationChannel(ch)
                            val restored = android.app.Notification.Builder(this@NotificationBlockerService, chId)
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentTitle(ruleBlockedTitle ?: ruleBlockedLabel)
                                .setContentText(ruleBlockedText ?: "")
                                .setAutoCancel(true)
                                .build()
                            nm.notify(ruleBlockedKey.hashCode(), restored)
                            // 发系统通知提醒用户
                            val alertMsg = "规则拦截了[" + ruleBlockedLabel + "]的通知，AI认为应该放行。请打开App查看详情。"
                            val alertN = android.app.Notification.Builder(this@NotificationBlockerService, chId)
                                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                                .setContentTitle("NotiAI - 拦截存疑")
                                .setContentText(alertMsg)
                                .setAutoCancel(true)
                                .setPriority(android.app.Notification.PRIORITY_HIGH)
                                .build()
                            nm.notify(("ai_conflict_" + ruleBlockedKey).hashCode(), alertN)
                            // 记录冲突
                            AiLogStorage.addLog(this@NotificationBlockerService, ruleBlockedPkg, "⚠冲突", "${ruleBlockedTitle} | ${ruleBlockedText}", "AI认为应放行-已恢复", false, 0)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "AI validation error", e)
                    }
                }
            }
        } else if (decision.shouldStack) {
            // STACK: post the replacement FIRST; only cancel the source if the
            // re-post succeeded (post-then-cancel — never lose a notification).
            val stackRule = decision.matchedStackRule!!
            val groupKey = StackedNotificationManager.groupKeyFor(packageName, stackRule)
            // Defence in depth: if a channel sync was missed (or the rule arrived from a path we
            // don't hook), create it now rather than silently dropping the stack.
            StackChannelsAndroid.ensure(this, stackRule)
            val channelId = StackChannelsAndroid.channelIdFor(stackRule)
            // Resolve the large-icon bitmap from cached storage before the lock
            // (no PackageManager call on the binder thread).
            val largeIcon = appInfoStorage.getAppIcon(packageName)
            val entry = StackedNotificationManager.Entry(
                sbnKey = sbn.key,
                title = title,
                text = text,
                timestamp = currentTime,
                contentIntent = sbn.notification.contentIntent,
                sourceVisibility = sbn.notification.visibility,
                childId = 0
            )
            val posted = StackedNotificationManager.absorbAndPost(
                stackPoster, groupKey, channelId, appLabel, entry, largeIcon
            )
            if (posted) {
                cancelNotification(sbn.key)
            } else {
                Log.w(TAG, "Stack post failed/blocked; leaving source intact: $packageName")
            }
            // Stacked notifications are NOT "blocked": they fall through to the
            // normal-history branch below (blocked count is not incremented).
        }

        // Carry the matched rule *ids*, not a whole-list snapshot: storage re-reads current
        // state and bumps only these, so a hit-count write can never resurrect a rule the UI
        // deleted (or clobber an edit) in the window before the executor runs.
        val hitRuleIds: List<String> = matchedRuleIndices.map { rules[it].id }

        // Debounce check on binder thread
        val notificationKey = sbn.key
        val isDuplicate = recentlyBlocked.containsKey(notificationKey) && currentTime - (recentlyBlocked[notificationKey] ?: 0) < DEBOUNCE_PERIOD_MS

        if (isDuplicate) {
            Log.i(TAG, "Ignoring duplicate for history/stats: $notificationKey")
            // Still persist hitCount updates asynchronously
            if (hitRuleIds.isNotEmpty()) {
                historyExecutor.execute {
                    try {
                        ruleStorage.incrementHitCounts(hitRuleIds)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save rules", e)
                    }
                }
            }
        } else {
            val simpleNotification = SimpleNotification(appLabel, packageName, title, text, currentTime, wasOngoing = wasOngoing)

            sbn.notification.contentIntent?.let { intent ->
                simpleNotification.id?.let { id ->
                    NotificationActionRepository.saveAction(id, intent)
                }
            }

            if (isBlocked) {
                recentlyBlocked[notificationKey] = currentTime
            }

            // Move all I/O to background executor
            historyExecutor.execute {
                try {
                    ruleStorage.incrementHitCounts(hitRuleIds)
                    if (isBlocked) {
                        val isNew = blockedNotificationHistoryStorage.saveNotification(simpleNotification)
                        if (isNew) {
                            statsStorage.incrementBlockedNotificationsCount()
                        }
                    } else {
                        if (!unmonitoredAppsStorage.isAppUnmonitored(packageName)) {
                            notificationHistoryStorage.saveNotification(simpleNotification)
                        }
                    }
                    sendBroadcast(Intent(ACTION_HISTORY_UPDATED))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save notification data", e)
                }
            }
        }

        // Clean up old entries from the debounce map
        recentlyBlocked.entries.removeIf { (_, timestamp) -> currentTime - timestamp > DEBOUNCE_PERIOD_MS }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // A live device-language change: reconcile stale fail-closed prebuilt allowlists promptly.
        PrebuiltRuleReconciler.reconcileIfLocaleChanged(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        SetupState.recordListenerConnected(this)
        PrebuiltRuleReconciler.reconcileIfLocaleChanged(this)
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        Log.i(TAG, "Listener connected")
        // Restart-safety: cancel any of our own stacks that survived a process
        // restart and clear the in-memory registry (no orphans / no id reuse).
        try {
            StackedNotificationManager.reconcileOnConnect(stackPoster)
        } catch (e: Exception) {
            Log.w(TAG, "reconcileOnConnect failed", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null || sbn.packageName != BuildConfig.APPLICATION_ID) return
        // One of our own stack notifications was dismissed — keep the registry in sync.
        try {
            StackedNotificationManager.onOurNotificationRemoved(
                stackPoster, sbn.id, getString(R.string.app_name)
            )
        } catch (e: Exception) {
            Log.w(TAG, "onOurNotificationRemoved failed", e)
        }
    }

    override fun onListenerDisconnected() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        super.onListenerDisconnected()
        Log.w(TAG, "Listener disconnected — requesting rebind")
        try {
            requestRebind(ComponentName(this, NotificationBlockerService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "requestRebind failed", e)
        }
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        super.onDestroy()
        historyExecutor.shutdown()
        try {
            if (!historyExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                historyExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            historyExecutor.shutdownNow()
        }
    }

    private fun saveAppInfoIfNeeded(packageName: String, appLabel: CharSequence) {
        try {
            if (appInfoStorage.isAppInfoSaved(packageName) == null || appInfoStorage.isAppInfoSaved(packageName) == packageName) {
                // Skipping icon save for now - we already have the icon from earlier notification
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save app info", e)
        }
    }

    fun resolveAppName(context: Context, sbn: StatusBarNotification): CharSequence {
        val extras = sbn.notification.extras

        // 1. System-resolved app label (best)
        extras.getCharSequence("android.substituteAppName")?.let { return it }

        // 2. Same-profile PackageManager fallback
        val pkg = sbn.opPkg
        return try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(ai)
        } catch (_: Exception) {
            // 3. Honest last resort
            pkg
        }
    }

}
