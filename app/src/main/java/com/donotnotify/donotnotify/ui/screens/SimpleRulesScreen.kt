package com.donotnotify.donotnotify.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.donotnotify.donotnotify.BlockerRule
import com.donotnotify.donotnotify.RuleType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleRulesScreen(rules: List<BlockerRule>, onToggleAll: (Boolean) -> Unit, onDeleteRule: ((BlockerRule) -> Unit)? = null) {
    val enabledCount = rules.count { it.isEnabled }
    val allEnabled = rules.isNotEmpty() && enabledCount == rules.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("当前规则") },
                actions = {
                    if (rules.isNotEmpty()) {
                        TextButton(onClick = { onToggleAll(!allEnabled) }) {
                            Text(if (allEnabled) "全部停用" else "全部启用", fontSize = 12.sp)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (rules.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("还没有规则\nAI生成规则后自动出现在这里", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text("共${rules.size}条规则，${enabledCount}条启用中",
                        modifier = Modifier.padding(16.dp, 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(rules) { rule ->
                    val icon = when (rule.ruleType) {
                        RuleType.DENYLIST -> Icons.Filled.Block
                        RuleType.ALLOWLIST -> Icons.Filled.CheckCircle
                        RuleType.STACK -> Icons.Filled.Layers
                    }
                    val actionText = when (rule.ruleType) {
                        RuleType.DENYLIST -> "拦截"
                        RuleType.ALLOWLIST -> "放行"
                        RuleType.STACK -> "堆叠"
                    }
                    val desc = buildRuleDescription(rule, actionText)

                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (rule.isEnabled)
                                MaterialTheme.colorScheme.surface
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null,
                                tint = if (rule.isEnabled) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(desc, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (rule.isEnabled) FontWeight.Normal else FontWeight.Light,
                                    color = if (rule.isEnabled) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                if (!rule.isEnabled) {
                                    Text("已停用", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (onDeleteRule != null) {
                                IconButton(onClick = { onDeleteRule(rule) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

private fun buildRuleDescription(rule: BlockerRule, action: String): String {
    val pkg = rule.packageName ?: ""
    val dotIdx = pkg.lastIndexOf('.')
    val app = if (dotIdx >= 0) pkg.substring(dotIdx + 1) else pkg
    val parts = mutableListOf<String>()
    parts.add(action)
    parts.add(app.ifEmpty { pkg })
    val tf = rule.titleFilter ?: ""
    val txf = rule.textFilter ?: ""
    if (tf.isNotBlank()) parts.add("\"$tf\"")
    if (txf.isNotBlank()) parts.add("\"$txf\"")
    return parts.joinToString(" · ")
}
