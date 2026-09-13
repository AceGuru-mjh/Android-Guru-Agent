package com.apex.agent.ui.screen.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.ui.screen.terminal.formatBytes
import kotlinx.coroutines.delay

/**
 * 存储与数据管理页（抽屉「存储」目的地）。
 *
 * 四个区块（全部真实数据源）：
 *  1. 附件沙箱 —— AttachmentCleanupManager 用量/清理；
 *  2. Ubuntu rootfs —— 环境占用/删除（用户数据保留说明）；
 *  3. 会话历史 —— 全局对话条数/导出（文本分享）/清空；
 *  4. 运行日志 —— 条数/内存占用，跳转日志页由用户自行导出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    viewModel: StorageViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmAction by remember { mutableStateOf<String?>(null) }

    // 结果反馈自动消隐
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4000)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("存储") },
                actions = {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            if (state.loading) {
                Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            // ═══ 1. 附件沙箱 ═══
            StorageCard(
                icon = Icons.Default.AttachFile,
                title = "附件",
                tint = MaterialTheme.colorScheme.primary
            ) {
                StorageMetricRow("占用空间", formatBytes(state.attachmentsSize))
                StorageMetricRow("文件数", "${state.attachmentsCount} 个")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { confirmAction = "attachments" },
                        enabled = !state.busy && state.attachmentsCount > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清空附件", color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    "聊天附件的本地缓存；清除后历史消息中的附件预览将不可用（Agent 侧引用路径失效）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ═══ 2. Ubuntu 环境 ═══
            StorageCard(
                icon = Icons.Default.Terminal,
                title = "Ubuntu 环境（rootfs）",
                tint = MaterialTheme.colorScheme.tertiary
            ) {
                val installed = state.rootfsPhase != UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED
                StorageMetricRow(
                    "状态",
                    when (state.rootfsPhase) {
                        UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED -> "未安装"
                        UbuntuLifecycleCoordinator.Phase.READY -> "已就绪"
                        UbuntuLifecycleCoordinator.Phase.FAILED -> "异常"
                        else -> state.rootfsPhase.name
                    }
                )
                StorageMetricRow(
                    "占用空间",
                    state.rootfsSize?.let { formatBytes(it) } ?: if (installed) "统计中…" else "—"
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmAction = "rootfs" },
                    enabled = !state.busy && installed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除 Ubuntu 环境（保留用户数据）", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "删除 rootfs 与下载缓存；guest /root 用户数据与 workspace 保留，重装即恢复。完整管理（安装/修复）在终端页环境中心。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ═══ 3. 会话历史 ═══
            StorageCard(
                icon = Icons.Default.Chat,
                title = "会话历史",
                tint = MaterialTheme.colorScheme.secondary
            ) {
                StorageMetricRow("消息数", "${state.conversationCount} 条")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::exportConversation,
                        enabled = !state.busy && state.conversationCount > 0,
                        modifier = Modifier.weight(1f)
                    ) { Text("导出为文本") }
                    OutlinedButton(
                        onClick = { confirmAction = "conversation" },
                        enabled = !state.busy && state.conversationCount > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    "当前全局会话（跨重启持久化）。清空前建议先导出备份；清空后 Agent 对话上下文从零开始。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ═══ 4. 运行日志 ═══
            StorageCard(
                icon = Icons.Default.Info,
                title = "运行日志（内存环形缓冲）",
                tint = MaterialTheme.colorScheme.primary
            ) {
                StorageMetricRow("记录数", "${state.logCount} 条")
                StorageMetricRow("占用", formatBytes(state.logBytes))
                val ratio = (state.logBytes.toFloat() / 8_388_608f).coerceIn(0f, 1f) // 8MB 上限（LogViewer 同源）
                if (ratio > 0f) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (ratio > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "日志位于内存环形缓冲（App 重启即释放）；完整查看与导出在「运行日志」页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 底部反馈条（Box 层级，不随内容滚动）
        state.message?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Text(
                    msg,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        }
    }

    // ═══ 破坏性操作二次确认 ═══
    if (confirmAction != null) {
        val (title, text) = when (confirmAction) {
            "attachments" -> "清空附件？" to "将删除全部 ${state.attachmentsCount} 个附件文件（${formatBytes(state.attachmentsSize)}）。此操作不可撤销。"
            "rootfs" -> "删除 Ubuntu 环境？" to "将删除 rootfs（${state.rootfsSize?.let { formatBytes(it) } ?: "数百 MB"}）与下载缓存；用户数据（/root、workspace）保留。"
            else -> "清空会话历史？" to "将删除全部 ${state.conversationCount} 条消息，Agent 上下文从零开始。建议先导出备份。"
        }
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    when (confirmAction) {
                        "attachments" -> viewModel.clearAttachments()
                        "rootfs" -> viewModel.removeRootfs()
                        "conversation" -> viewModel.clearConversation()
                    }
                    confirmAction = null
                }) { Text("确认", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun StorageCard(
    icon: ImageVector,
    title: String,
    tint: Color,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = tint.copy(alpha = 0.15f), modifier = Modifier.size(34.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = tint) }
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StorageMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}
