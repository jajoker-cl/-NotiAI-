package com.donotnotify.donotnotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

/**
 * 拦截收到的短信，在高优先级通知播放声音前由AI判断
 * 需要 RECEIVE_SMS 权限（AndroidManifest已添加）
 * 在AndroidManifest中优先级设为最高(999)，确保先于其他App收到
 */
class SmsInterceptor : BroadcastReceiver() {
    private val TAG = "SmsInterceptor"

    override fun onReceive(context: Context, intent: Intent) {
        if (!AiFilterSettings.isAiModeEnabled(context)) return
        val apiKey = AiFilterSettings.getApiKey(context)
        if (apiKey.isBlank()) return

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            // 解析短信
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isEmpty()) return

            val sender = messages[0].originatingAddress ?: "未知号码"
            val body = StringBuilder()
            for (msg in messages) body.append(msg.messageBody)

            val text = body.toString()
            Log.d(TAG, "SMS from $sender: ${text.take(50)}")

            // 后台预热AI判断（不阻塞广播）
            AiFilter.preWarm(context, "com.android.mms", "短信: $sender", text)
            Log.d(TAG, "SMS pre-warming AI cache for $sender")
        } catch (e: Exception) {
            Log.e(TAG, "SMS intercept error", e)
        }
    }
}
