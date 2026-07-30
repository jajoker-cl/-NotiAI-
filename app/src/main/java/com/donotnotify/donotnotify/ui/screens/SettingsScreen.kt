package com.donotnotify.donotnotify.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.donotnotify.donotnotify.ImportError
import com.donotnotify.donotnotify.ImportResult
import com.donotnotify.donotnotify.R
import com.donotnotify.donotnotify.RuleExport
import com.donotnotify.donotnotify.RuleExportSerializer
import com.donotnotify.donotnotify.RuleImport
import com.donotnotify.donotnotify.RuleStorage
import com.donotnotify.donotnotify.AiFilterSettings
import com.donotnotify.donotnotify.AiLogStorage
import com.donotnotify.donotnotify.AppInfoStorage
import com.donotnotify.donotnotify.BlockedNotificationHistoryStorage
import com.donotnotify.donotnotify.NotificationHistoryStorage
import com.donotnotify.donotnotify.UnmonitoredAppsStorage
import com.donotnotify.donotnotify.StackChannelsAndroid
import com.donotnotify.donotnotify.ui.components.AboutDialog
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var historyDays by remember {
        mutableStateOf(sharedPreferences.getInt("historyDays", 5).toString())
    }
    var showAboutDialog by remember { mutableStateOf(false) }

    // ★ AI模式状态
    var aiMode by remember { mutableStateOf(AiFilterSettings.getAiMode(context)) }
    var aiApiKey by remember { mutableStateOf(AiFilterSettings.getApiKey(context)) }
    var aiCustomRule by remember { mutableStateOf(AiFilterSettings.getCustomRule(context)) }
    var showAiLogs by remember { mutableStateOf(false) }

    val ruleStorage = remember { RuleStorage(context) }

    var showExportImportDialog by remember { mutableStateOf(false) }
    var exportImportMessage by remember { mutableStateOf<String?>(null) }
    var showResetHitsDialog by remember { mutableStateOf(false) }
    var showUnmonitoredDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                val localeTag = context.resources.configuration.locales[0].toLanguageTag()
                val export = RuleExport(locale = localeTag, rules = ruleStorage.getRules())
                val json = RuleExportSerializer.toJson(export)
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(json.toByteArray())
                }
                exportImportMessage = context.getString(R.string.rules_exported_successfully)
            } catch (e: Exception) {
                exportImportMessage = context.getString(R.string.failed_to_export_rules, e.message ?: "")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val json = readCappedText(context, it, MAX_IMPORT_BYTES)
                if (json == null) {
                    exportImportMessage = context.getString(R.string.rules_file_too_large)
                    return@let
                }
                when (val result = RuleImport.parse(json)) {
                    is ImportResult.Error -> {
                        exportImportMessage = when (result.reason) {
                            ImportError.TooLarge -> context.getString(R.string.rules_file_too_large)
                            ImportError.Malformed -> context.getString(R.string.invalid_rules_file)
                            ImportError.SchemaMismatch -> context.getString(R.string.invalid_rules_file_schema)
                            ImportError.Empty -> context.getString(R.string.import_no_rules)
                        }
                    }
                    is ImportResult.Success -> {
                        val currentRules = ruleStorage.getRules()
                        // Dedup by rule *signature* (not identity): imported ids are always freshly
                        // minted, so they can never match an existing rule.
                        val newRules = result.rules.filter { imported ->
                            currentRules.none { current ->
                                current.packageName == imported.packageName &&
                                        current.titleFilter == imported.titleFilter &&
                                        current.titleMatchType == imported.titleMatchType &&
                                        current.textFilter == imported.textFilter &&
                                        current.textMatchType == imported.textMatchType &&
                                        current.ruleType == imported.ruleType
                            }
                        }
                        if (newRules.isNotEmpty()) {
                            ruleStorage.addRules(newRules)
                            // Imported STACK rules need channels now — otherwise they'd have none
                            // until the next app start, and the settings deep-link would point at
                            // a channel that doesn't exist.
                            StackChannelsAndroid.sync(context)
                        }
                        exportImportMessage = if (result.droppedCount > 0) {
                            context.getString(
                                R.string.imported_rules_some_skipped,
                                newRules.size,
                                result.droppedCount
                            )
                        } else {
                            context.getString(R.string.successfully_imported_rules, newRules.size)
                        }
                    }
                }
            } catch (e: Exception) {
                exportImportMessage = context.getString(R.string.failed_to_import_rules, e.message ?: "")
            }
        }
    }

    if (showAboutDialog) {
        AboutDialog {
            showAboutDialog = false
        }
    }

    if (showExportImportDialog) {
        AlertDialog(
            onDismissRequest = { showExportImportDialog = false },
            title = { Text(stringResource(R.string.export_import_rules)) },
            text = { Text(stringResource(R.string.choose_action)) },
            confirmButton = {
                TextButton(onClick = {
                    showExportImportDialog = false
                    exportLauncher.launch("donotnotify_rules.json")
                }) {
                    Text(stringResource(R.string.export))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportImportDialog = false
                    importLauncher.launch(arrayOf("application/json"))
                }) {
                    Text(stringResource(R.string.import_rules))
                }
            }
        )
    }

    if (showResetHitsDialog) {
        AlertDialog(
            onDismissRequest = { showResetHitsDialog = false },
            title = { Text(stringResource(R.string.reset_hit_counters_title)) },
            text = { Text(stringResource(R.string.reset_hit_counters_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    ruleStorage.resetHitCounts()
                    showResetHitsDialog = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_hit_counters_reset),
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text(stringResource(R.string.reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetHitsDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (exportImportMessage != null) {
        AlertDialog(
            onDismissRequest = { exportImportMessage = null },
            title = { Text(stringResource(R.string.status)) },
            text = { Text(exportImportMessage!!) },
            confirmButton = {
                TextButton(onClick = { exportImportMessage = null }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_general)) {
                SettingsRow(
                    icon = Icons.Filled.History,
                    title = stringResource(R.string.history_retention),
                    subtitle = stringResource(R.string.history_retention_desc),
                    trailing = {
                        OutlinedTextField(
                            value = historyDays,
                            onValueChange = { newText ->
                                historyDays = newText
                                newText.toIntOrNull()?.let { newDays ->
                                    with(sharedPreferences.edit()) {
                                        putInt("historyDays", newDays)
                                        apply()
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            suffix = { Text(stringResource(R.string.days_unit)) },
                            modifier = Modifier.width(112.dp)
                        )
                    }
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_general)) {
                SettingsRow(
                    icon = Icons.Filled.VisibilityOff,
                    title = "管理不监控应用",
                    subtitle = "添加后该App所有通知直接放行",
                    onClick = { showUnmonitoredDialog = true },
                    trailing = { NavChevron() }
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_rules)) {
                SettingsRow(
                    icon = Icons.Filled.ImportExport,
                    title = stringResource(R.string.export_import_rules),
                    subtitle = stringResource(R.string.export_import_rules_desc),
                    onClick = { showExportImportDialog = true },
                    trailing = { NavChevron() }
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Filled.RestartAlt,
                    title = stringResource(R.string.reset_hit_counters),
                    subtitle = stringResource(R.string.reset_hit_counters_desc),
                    onClick = { showResetHitsDialog = true },
                    trailing = { NavChevron() }
                )
            }

            // ★ AI模式设置 - 三档选择
            SettingsSection(title = "🤖 AI智能过滤 (DeepSeek)") {
                // 档位说明卡片
                androidx.compose.material3.OutlinedCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("💡", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "建议前3天使用「AI直接判断」学习，之后切「规则优先+AI复查」日常使用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 三档单选
                val modes = listOf(
                    Triple(0, "关闭AI", "纯规则引擎，AI完全不参与，仅凭自定义规则拦截"),
                    Triple(1, "规则优先 + AI复查", "规则引擎先判断，AI后台双向复查拦截和放行是否都有误判"),
                    Triple(2, "AI直接判断", "AI先判断每条通知（学习阶段），不经过规则引擎")
                )
                modes.forEach { (mode, title, subtitle) ->
                    SettingsRow(
                        icon = Icons.Filled.BugReport,
                        title = title,
                        subtitle = subtitle,
                        trailing = {
                            androidx.compose.material3.RadioButton(
                                selected = aiMode == mode,
                                onClick = {
                                    aiMode = mode
                                    AiFilterSettings.setAiMode(context, mode)
                                    if (mode == 2) AiFilterSettings.markAiStartTime(context)
                                    val msg = when (mode) {
                                        0 -> "AI已关闭，纯规则引擎运行"
                                        1 -> "规则优先+AI复查：AI会双向校验规则引擎的拦截和放行"
                                        2 -> "AI直接判断模式已开启，前3天建议多去AI评判页面纠错"
                                        else -> ""
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    )
                    if (mode < 2) RowDivider()
                }
                RowDivider()
                // API Key输入（填完显示●●●●）
                var apiKeyVisible by remember { mutableStateOf(false) }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("DeepSeek API Key", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("填完自动隐藏，点击右侧眼睛查看", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = aiApiKey,
                        onValueChange = { key ->
                            aiApiKey = key
                            AiFilterSettings.setApiKey(context, key)
                        },
                        singleLine = true,
                        placeholder = { Text("sk-xxxxxxxx") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector = if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (apiKeyVisible) "隐藏" else "显示"
                                )
                            }
                        }
                    )
                }
                RowDivider()
                // 调教语
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("对AI说句话", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("告诉AI哪些通知应该放行", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = aiCustomRule,
                        onValueChange = { rule ->
                            aiCustomRule = rule
                            AiFilterSettings.setCustomRule(context, rule)
                        },
                        placeholder = { Text("例如：银行扣款、快递到了、家人消息这些都算重要") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
            }

            var showReportChoice by remember { mutableStateOf(false) }

            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                SettingsRow(
                    icon = Icons.Filled.BugReport,
                    title = "报告问题",
                    subtitle = "选择提交到哪个仓库",
                    onClick = { showReportChoice = true },
                    trailing = { NavChevron() }
                )
                if (showReportChoice) {
                    AlertDialog(
                        onDismissRequest = { showReportChoice = false },
                        title = { Text("选择仓库") },
                        text = { Text("将问题提交到哪个GitHub仓库？") },
                        confirmButton = {
                            Column {
                                TextButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jajoker-cl/-NotiAI-/issues")))
                                    showReportChoice = false
                                }) { Text("AI版仓库 (jajoker-cl)") }
                                TextButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/anujja/DoNotNotify/issues")))
                                    showReportChoice = false
                                }) { Text("原作者仓库 (anujja)") }
                            }
                        },
                        dismissButton = { TextButton(onClick = { showReportChoice = false }) { Text("取消") } }
                    )
                }
                RowDivider()
                SettingsRow(
                    icon = Icons.Filled.Favorite,
                    title = "支持开发者",
                    subtitle = "如果觉得好用，欢迎请开发者喝杯咖啡 ☕",
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jajoker-cl/-NotiAI-"))
                        )
                    },
                    trailing = { NavChevron() }
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.about),
                    subtitle = stringResource(R.string.about_desc),
                    onClick = { showAboutDialog = true },
                    trailing = { NavChevron() }
                )
            }

            // ★ AI日志弹窗 + 纠错
            if (showAiLogs) {
                val logs = AiLogStorage.getLogs(context)
                AlertDialog(
                    onDismissRequest = { showAiLogs = false },
                    title = { Text("AI拦截日志 (${logs.size}条) - 点一条纠错") },
                    text = {
                        if (logs.isEmpty()) {
                            Text("暂无日志，等收到通知后再查看")
                        } else {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState()).height(400.dp)) {
                                for ((idx, log) in logs.take(50).withIndex()) {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 4.dp)) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(log, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        TextButton(onClick = {
                                            // 标记"判断正确"
                                            Toast.makeText(context, "已记录：判断正确 ✅", Toast.LENGTH_SHORT).show()
                                        }, modifier = Modifier.padding(0.dp)) {
                                            Text("✅", fontSize = 14.sp)
                                        }
                                        TextButton(onClick = {
                                            // 标记"判断错误" + 存反馈
                                            AiFilterSettings.addFeedback(context,
                                                title = "用户纠错",
                                                text = "这条判断错了: $log".take(200),
                                                wasImportant = !log.contains("[拦截]") // 点纠错=反着来：拦截了的其实该放行，放行了的其实该拦截
                                            )
                                            Toast.makeText(context, "已纠错并记录 ✅", Toast.LENGTH_SHORT).show()
                                        }, modifier = Modifier.padding(0.dp)) {
                                            Text("❌", fontSize = 14.sp)
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Row {
                            TextButton(onClick = {
                                AiLogStorage.clear(context)
                                showAiLogs = false
                            }) { Text("清除日志") }
                            TextButton(onClick = { showAiLogs = false }) { Text("关闭") }
                        }
                    }
                )
            }

            val packageInfo = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (e: Exception) {
                null
            }
            val versionName = packageInfo?.versionName ?: stringResource(R.string.unknown)

            Text(
                text = stringResource(R.string.app_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }

    // 管理不监控应用弹窗
    if (showUnmonitoredDialog) {
        val storage = remember { UnmonitoredAppsStorage(context) }
        val appInfo = remember { AppInfoStorage(context) }
        val histStorage = remember { NotificationHistoryStorage(context) }
        val blockedStorage = remember { BlockedNotificationHistoryStorage(context) }
        val unmonitored = remember { storage.getUnmonitoredApps() }
        // 从历史和拦截记录中收集所有见过的App，排除已在白名单的
        val seenApps = remember {
            val pkgs = mutableSetOf<String>()
            for (n in histStorage.getHistory()) n.packageName?.let { pkgs.add(it) }
            for (n in blockedStorage.getHistory()) n.packageName?.let { pkgs.add(it) }
            pkgs.filter { it !in unmonitored }
        }
        AlertDialog(
            onDismissRequest = { showUnmonitoredDialog = false },
            title = { Text("不监控应用管理") },
            text = {
                LazyColumn {
                    // 已添加的白名单
                    item { Text("已添加 (${unmonitored.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp)) }
                    if (unmonitored.isEmpty()) {
                        item { Text("暂无", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    } else {
                        itemsIndexed(unmonitored.toList()) { _, pkg ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = appInfo.getAppName(pkg) ?: pkg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { storage.removeApp(pkg) }) {
                                    Text("恢复", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    // 可添加的应用
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text("可添加 (${seenApps.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    if (seenApps.isEmpty()) {
                        item { Text("没有新应用可添加", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    } else {
                        itemsIndexed(seenApps) { _, pkg ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = appInfo.getAppName(pkg) ?: pkg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { storage.addApp(pkg) }) {
                                    Text("添加", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUnmonitoredDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
    )
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun NavChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Cap on the size of an imported rules document (defends against OOM from a corrupt file). */
private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

/**
 * Reads the document as UTF-8 text, but never buffers more than [maxBytes]. Returns null if the
 * document exceeds the cap (so the caller can report "file too large" instead of risking OOM).
 */
private fun readCappedText(context: Context, uri: Uri, maxBytes: Int): String? {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }
    return null
}
