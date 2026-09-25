package com.apex.agent.ui.screen.market

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.mcphost.McpHostManager
import com.apex.agent.platform.mcphost.McpHostConfig
import com.apex.agent.platform.mcphost.HostAuditEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import android.widget.Toast

/**
 * # 逆向 MCP Host 设置区（#173）
 *
 * 插入位置：市场 → MCP 页签顶部（[BrowseMcpTab] 列表首项之前）。
 * 选择理由：MCP 页签本就是"连接 MCP 世界"的入口 —— 该页其余卡片是
 * 手机作为客户端**接出**（添加/导入工具源），本卡片是手机作为服务端**接入**
 * （外部 AI 连进来），同一页签内方向互补、认知聚合；且 MarketBrowseTabs
 * 的 LazyColumn 结构允许整卡插入无需改脚手架。
 */
@HiltViewModel
class McpHostViewModel @Inject constructor(
    private val manager: McpHostManager
) : ViewModel() {

    val config = manager.config
    val isRunning = manager.isRunning
    val sessions = manager.sessions
    val auditLog = manager.auditLog
    val lastError = manager.lastError

    fun setEnabled(enabled: Boolean) =
        manager.updateConfig(manager.config.value.copy(enabled = enabled))

    fun setPort(port: Int) =
        manager.updateConfig(manager.config.value.copy(port = port))

    fun setRateLimit(rate: Int) =
        manager.updateConfig(manager.config.value.copy(rateLimitPerMinute = rate))

    fun toggleCategory(name: String) = manager.toggleCategory(name)

    fun regenerateToken() = manager.regenerateToken()
}

/** 高风险分类（开启需显著提示）。 */
private val RISKY_CATEGORIES =
    setOf("SHELL", "TERMINAL", "APP", "UI", "GITHUB", "SECURITY")

@Composable
internal fun McpHostSection(viewModel: McpHostViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val auditLog by viewModel.auditLog.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ip = remember { localSiteLocalAddress() }

    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var showRegenDialog by remember { mutableStateOf(false) }
    var connectExpanded by rememberSaveable { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── 标题行：名称 + 状态灯 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.mcphost_title),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.mcphost_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusLight(running = isRunning)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isRunning) {
                        stringResource(R.string.mcphost_status_running, sessions.size)
                    } else {
                        stringResource(R.string.mcphost_status_stopped)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = config.enabled,
                    onCheckedChange = viewModel::setEnabled
                )
            }

            // ── 启动失败错误 ──
            val error = lastError
            if (config.enabled && !isRunning && error != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.mcphost_error_prefix, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // ── 端口 + 限速 ──
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PortField(
                    modifier = Modifier.weight(1f),
                    initialValue = config.effectivePort,
                    label = stringResource(R.string.mcphost_port_label),
                    minIn = McpHostConfig.MIN_PORT,
                    maxIn = McpHostConfig.MAX_PORT,
                    onCommit = viewModel::setPort
                )
                PortField(
                    modifier = Modifier.weight(1f),
                    initialValue = config.effectiveRateLimitPerMinute,
                    label = stringResource(R.string.mcphost_rate_label),
                    minIn = 1,
                    maxIn = 10_000,
                    onCommit = viewModel::setRateLimit
                )
            }

            // ── Token 行 ──
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.mcphost_token_label),
                style = MaterialTheme.typography.labelMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (tokenVisible) config.token
                    else "•".repeat(config.token.length.coerceAtMost(32)),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(
                        if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            if (tokenVisible) R.string.mcphost_token_hide else R.string.mcphost_token_show
                        )
                    )
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(config.token))
                    Toast.makeText(
                        context, context.getString(R.string.mcphost_token_copied), Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.mcphost_token_copy)
                    )
                }
                IconButton(onClick = { showRegenDialog = true }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.mcphost_token_regenerate)
                    )
                }
            }

            // ── 分类白名单 ──
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.mcphost_categories_title),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                stringResource(R.string.mcphost_categories_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CategoryChips(
                allowed = config.allowedCategories,
                onToggle = viewModel::toggleCategory
            )
            if (config.allowedCategories.any { it.uppercase() in RISKY_CATEGORIES }) {
                Text(
                    stringResource(R.string.mcphost_categories_risky),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // ── 黑名单只读说明 ──
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.mcphost_blacklist_title),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                stringResource(
                    R.string.mcphost_blacklist_hint,
                    config.blockedToolIds.joinToString(", ")
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── 审计日志（最近 20 条）──
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.mcphost_audit_title),
                style = MaterialTheme.typography.labelMedium
            )
            if (auditLog.isEmpty()) {
                Text(
                    stringResource(R.string.mcphost_audit_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                AuditLogList(entries = auditLog.take(20))
            }

            // ── 如何连接（折叠）──
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.mcphost_connect_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium
                )
                IconButton(onClick = { connectExpanded = !connectExpanded }) {
                    Icon(
                        if (connectExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(R.string.mcphost_connect_title)
                    )
                }
            }
            if (connectExpanded) {
                Text(
                    stringResource(R.string.mcphost_connect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        stringResource(
                            R.string.mcphost_connect_json,
                            ip ?: stringResource(R.string.mcphost_connect_ip_placeholder),
                            config.effectivePort,
                            config.token
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    if (showRegenDialog) {
        AlertDialog(
            onDismissRequest = { showRegenDialog = false },
            title = { Text(stringResource(R.string.mcphost_token_regen_title)) },
            text = { Text(stringResource(R.string.mcphost_token_regen_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.regenerateToken()
                    showRegenDialog = false
                }) { Text(stringResource(R.string.mcphost_token_regenerate)) }
            },
            dismissButton = {
                TextButton(onClick = { showRegenDialog = false }) {
                    Text(stringResource(R.string.mcphost_regen_cancel))
                }
            }
        )
    }
}

/** 状态灯（绿=运行 / 灰=停止）。 */
@Composable
private fun StatusLight(running: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = if (running) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                shape = CircleShape
            )
    )
}

/**
 * 数字输入字段：本地编辑态 + 仅当落在 [minIn, maxIn] 才提交
 * （避免 sanitize 回退与输入中问态打架）。
 */
@Composable
private fun PortField(
    modifier: Modifier,
    initialValue: Int,
    label: String,
    minIn: Int,
    maxIn: Int,
    onCommit: (Int) -> Unit
) {
    var text by rememberSaveable(initialValue) { mutableStateOf(initialValue.toString()) }
    val parsed = text.trim().toIntOrNull()
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter { it.isDigit() }.take(6)
            val candidate = text.toIntOrNull()
            if (candidate != null && candidate in minIn..maxIn) onCommit(candidate)
        },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = parsed == null || parsed !in minIn..maxIn,
        supportingText = if (parsed != null && parsed !in minIn..maxIn) {
            { Text(stringResource(R.string.mcphost_field_range, minIn, maxIn)) }
        } else {
            null
        }
    )
}

/** 分类 FilterChip 组（17 分类，风险分类开启时整组上方已有关注文案）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(
    allowed: List<String>,
    onToggle: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ToolCategory.entries.forEach { category ->
            val selected = allowed.any { it.equals(category.name, ignoreCase = true) }
            FilterChip(
                selected = selected,
                onClick = { onToggle(category.name) },
                label = { Text(category.name, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

/** 审计日志列表（固定高度内滚动）。 */
@Composable
private fun AuditLogList(entries: List<HostAuditEntry>) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
    ) {
        items(entries.size) { index ->
            val entry = entries[index]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    timeFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.method ?: "-",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    entry.toolName ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${entry.status}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (entry.status in 200..299) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.mcphost_audit_duration, entry.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 站点本地 IPv4（192.168.x / 10.x / 172.16-31.x），取不到返回 null。 */
private fun localSiteLocalAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()
