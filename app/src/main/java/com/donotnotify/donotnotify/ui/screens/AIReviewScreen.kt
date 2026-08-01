package com.donotnotify.donotnotify.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.donotnotify.donotnotify.AiFilterSettings
import com.donotnotify.donotnotify.AiLogStorage
import com.donotnotify.donotnotify.AiRuleGenerator
import com.donotnotify.donotnotify.BlockerRule
import com.donotnotify.donotnotify.MatchType
import com.donotnotify.donotnotify.RuleStorage
import com.donotnotify.donotnotify.RuleType
import com.donotnotify.donotnotify.StackChannelsAndroid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIReviewScreen() {
    val ctx = LocalContext.current
    val logs = AiLogStorage.getLogs(ctx)
    var showClearConfirm by remember { mutableStateOf(false) }
    var correctingLog by remember { mutableStateOf("") }
    var correctReason by remember { mutableStateOf("") }

    var generating by remember { mutableStateOf(false) }
    var generatedResult by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 评判记录") },
                actions = {
                    if (logs.size >= 5) {
                        TextButton(onClick = {
                            generating = true
                            generatedResult = ""
                            Toast.makeText(ctx, "正在生成规则，可能需要10~60秒，请稍候...", Toast.LENGTH_LONG).show()
                            AiRuleGenerator.generate(ctx) { rules ->
                                generating = false
                                if (rules.isEmpty()) {
                                    // 区分：日志够但没生成 → 大概率是API失败/超时
                                    val logs = AiLogStorage.getLogs(ctx)
                                    val feedback = AiFilterSettings.getFeedback(ctx)
                                    if (logs.size >= 10 || feedback.size >= 3) {
                                        generatedResult = "生成失败，可能是API超时或没有识别到明确营销模式。\n请检查：\n1. API Key是否有效\n2. 网络是否正常\n3. 稍后再试"
                                    } else {
                                        generatedResult = "数据不够，至少需要5条以上记录和纠错反馈才能生成规则"
                                    }
                                } else {
                                    // 去重后写入RuleStorage
                                    val storage = RuleStorage(ctx)
                                    val existingRules = storage.getRules()
                                    var addedCount = 0
                                    var skippedCount = 0
                                    for (r in rules) {
                                        // 检查是否已存在相同规则
                                        val isDuplicate = existingRules.any { er ->
                                            er.packageName == r.packageName &&
                                            er.titleFilter == r.titleFilter &&
                                            er.textFilter == r.textFilter &&
                                            er.ruleType == (if (r.action == "block") RuleType.DENYLIST else RuleType.ALLOWLIST)
                                        }
                                        if (!isDuplicate) {
                                            val newRule = BlockerRule(
                                                id = java.util.UUID.randomUUID().toString(),
                                                packageName = r.packageName,
                                                titleFilter = r.titleFilter,
                                                textFilter = r.textFilter,
                                                titleMatchType = MatchType.CONTAINS,
                                                textMatchType = MatchType.CONTAINS,
                                                ruleType = if (r.action == "block") RuleType.DENYLIST else RuleType.ALLOWLIST,
                                                isEnabled = true
                                            )
                                            storage.addRules(listOf(newRule))
                                            addedCount++
                                        } else {
                                            skippedCount++
                                        }
                                    }
                                    StackChannelsAndroid.sync(ctx)
                                    val summary = rules.joinToString("\n") { "${it.action}: ${it.packageName} | ${it.titleFilter} | ${it.reason}" }
                                    val skipMsg = if (skippedCount > 0) "\n（跳过${skippedCount}条重复规则）" else ""
                                    generatedResult = "已新增${addedCount}条规则${skipMsg}：\n$summary\n\n现在可以关闭AI模式，用规则引擎运行（设置→关闭AI开关）"
                                }
                            }
                        }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                            if (generating) Text("生成中...", fontSize = 12.sp) else Text("生成规则", fontSize = 12.sp)
                        }
                    }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空")
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("暂无AI评判记录\n收到通知后自动出现在这里", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text("每条记录可打✅（判断正确）或❌（判断错误，下次改进）",
                        modifier = Modifier.padding(16.dp, 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(logs.take(100)) { idx, log ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (log.contains("[拦截]"))
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        )) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(log, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            val reviewed = AiLogStorage.isReviewed(ctx, log)
                            if (!reviewed) {
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = {
                                        AiLogStorage.markReviewed(ctx, log)
                                        val wasBlock = log.contains("[拦截]")
                                        AiFilterSettings.addFeedback(ctx,
                                            if (wasBlock) "确认拦截正确" else "确认放行正确",
                                            "用户确认AI判断无误 | $log".take(200),
                                            wasImportant = !wasBlock
                                        )
                                        Toast.makeText(ctx, "✅ 判断正确已记录", Toast.LENGTH_SHORT).show()
                                    }) { Text("✅ 正确", fontSize = 12.sp) }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = {
                                        AiLogStorage.markReviewed(ctx, log)
                                        correctingLog = log
                                        correctReason = ""
                                    }) { Text("❌ 纠错", fontSize = 12.sp) }
                                }
                            } else {
                                Text("已评判", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    // ★ 生成结果弹窗（不遮挡内容）
    if (generatedResult.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { generatedResult = "" },
            title = { Text("规则生成结果") },
            text = { Text(generatedResult) },
            confirmButton = { TextButton(onClick = { generatedResult = "" }) { Text("知道了") } }
        )
    }

    if (correctingLog.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { correctingLog = "" },
            title = { Text("纠错原因") },
            text = {
                Column {
                    Text("请告诉AI为什么这条判断错了：", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = correctReason,
                        onValueChange = { correctReason = it },
                        placeholder = { Text("例：这是银行验证码，应该放行") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val reason = correctReason.ifBlank { "判断错误" }
                    AiFilterSettings.addFeedback(ctx, "用户纠错", "$reason | 原记录: ${correctingLog.take(150)}",
                        wasImportant = !correctingLog.contains("[拦截]"))
                    Toast.makeText(ctx, "❌ 已纠错，AI下次会改进", Toast.LENGTH_SHORT).show()
                    correctingLog = ""
                    correctReason = ""
                }) { Text("提交纠错") }
            },
            dismissButton = {
                TextButton(onClick = { correctingLog = "" }) { Text("取消") }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空所有记录？") },
            confirmButton = {
                TextButton(onClick = {
                    AiLogStorage.clear(ctx)
                    showClearConfirm = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}
