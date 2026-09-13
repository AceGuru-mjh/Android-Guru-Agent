package com.apex.agent.ui.screen.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator

/**
 * 环境中心（终端页顶栏「下载」图标入口）。
 *
 * 三个层次的真实环境能力：
 *  1. **Ubuntu 24.04 LTS rootfs** —— 完整安装链（下载 SHA256 校验 → 解压 → 引导），
 *     状态驱动按钮（安装/取消/修复/删除），能力（bash/apt/python…）快览，磁盘占用展示；
 *  2. **Android 本地 Shell** —— 内置环境（mksh/toybox），零下载即用；
 *  3. **环境依赖包**（Ubuntu 内）—— JDK/Git/Gradle/SDK/NDK…，apt 命令真实路由
 *     到 Ubuntu 会话执行（镜像源可切换）。
 *
 * 全部状态来自 [TerminalViewModel]（UbuntuLifecycleCoordinator / EnvironmentProvisioner），
 * 无写死数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnvironmentCenterSheet(
    onDismiss: () -> Unit,
    ubuntu: UbuntuLifecycleCoordinator.LifecycleState,
    progress: UbuntuLifecycleCoordinator.LifecycleProgress?,
    rootfsSize: Long?,
    onInstallUbuntu: () -> Unit,
    onCancelUbuntuInstall: () -> Unit,
    onRepairUbuntu: () -> Unit,
    onRemoveUbuntu: () -> Unit,
    onCreateUbuntuSession: () -> Unit,
    useMirror: Boolean,
    onToggleMirror: (Boolean) -> Unit,
    depItems: List<TerminalViewModel.DepItem>,
    install: TerminalViewModel.InstallState,
    onInstallDep: (TerminalViewModel.DepItem) -> Unit,
    onInstallAll: () -> Unit,
    onInstallAndroid: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showRemoveConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 标题 ──
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SurfaceBadge(Icons.Default.Download, MaterialTheme.colorScheme.primary)
                Column {
                    Text("环境中心", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "按需下载运行环境 —— 安装与删除均可逆，用户数据（/root、workspace）保留",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ═══ 1. Ubuntu rootfs 环境 ═══
            UbuntuEnvironmentCard(
                ubuntu = ubuntu,
                progress = progress,
                rootfsSize = rootfsSize,
                onInstall = onInstallUbuntu,
                onCancel = onCancelUbuntuInstall,
                onRepair = onRepairUbuntu,
                onRemove = { showRemoveConfirm = true },
                onCreateSession = onCreateUbuntuSession
            )

            // ═══ 2. Android 本地 Shell（内置）═══
            SettingsCard(Icons.Default.Android, "Android Shell") {
                Text(
                    "设备自带命令行环境（mksh / toybox：ls、grep、am、pm 等）。无需下载，随时可用；不含 apt / bash 完整工具链。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                StatusBadge(text = "内置", color = Color(0xFF3E9C51))
            }

            // ═══ 3. 环境依赖（Ubuntu 内工具链）═══
            SettingsCard(Icons.Default.Terminal, "环境依赖（Ubuntu 内）") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("使用镜像源", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("国内镜像加速（清华源）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = useMirror,
                        onCheckedChange = onToggleMirror,
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("一键安装全部", install.runningId != null, Modifier.weight(1f)) { onInstallAll() }
                    ActionButton("Android 依赖", install.runningId != null, Modifier.weight(1f)) { onInstallAndroid() }
                }
                Spacer(Modifier.height(10.dp))
                Text("可独立安装：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                depItems.forEach { item ->
                    DepRow(
                        item = item,
                        // 任一安装（含批量）进行中时全部禁用，避免单行覆写批量状态
                        busy = install.runningId != null,
                        installing = install.runningId == item.id,
                        onInstall = { onInstallDep(item) }
                    )
                }
                if (install.log.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            install.log.takeLast(4000),
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("删除 Ubuntu 环境？") },
            text = {
                Text(
                    "将删除 rootfs（数百 MB）与下载缓存。用户数据（guest /root 与 workspace）保留；" +
                        "再次安装可随时恢复。正在运行的 Ubuntu 会话需先全部关闭。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveConfirm = false
                    onRemoveUbuntu()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("取消") }
            }
        )
    }
}

// ═══ Ubuntu 环境卡 ═══

@Composable
private fun UbuntuEnvironmentCard(
    ubuntu: UbuntuLifecycleCoordinator.LifecycleState,
    progress: UbuntuLifecycleCoordinator.LifecycleProgress?,
    rootfsSize: Long?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRepair: () -> Unit,
    onRemove: () -> Unit,
    onCreateSession: () -> Unit
) {
    val phase = ubuntu.phase
    val busy = phase == UbuntuLifecycleCoordinator.Phase.INSTALLING ||
        phase == UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING ||
        phase == UbuntuLifecycleCoordinator.Phase.RECOVERING

    SettingsCard(Icons.Default.Terminal, "Ubuntu 24.04 LTS") {
        Text(
            "完整 Linux 开发环境（apt / bash / python / 构建工具链），PRoot 免 root 运行。官方 rootfs SHA256 校验、断点续传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        // 状态徽标 + 占用
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(
                text = phaseLabel(phase),
                color = phaseColor(phase)
            )
            rootfsSize?.let { bytes ->
                Text(
                    "占用 ${formatBytes(bytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 安装/引导进度（percent 来自 install 下载链）
        if (busy && progress != null) {
            Spacer(Modifier.height(8.dp))
            val percent = progress.percent.coerceIn(0, 100)
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            val bytesTotal = progress.bytesTotal
            Text(
                buildString {
                    append(progress.message)
                    if (bytesTotal != null && bytesTotal > 0) {
                        append("  （")
                        append(formatBytes(progress.bytesTransferred))
                        append(" / ")
                        append(formatBytes(bytesTotal))
                        append("）")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }

        // READY 时能力快览（跨模块属性不可 smart cast —— 先拷局部变量）
        val caps = ubuntu.capabilities
        if (phase == UbuntuLifecycleCoordinator.Phase.READY && !caps.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                caps.joinToString("  ·  ") { c ->
                    c.name + (c.version?.let { " $it" } ?: "")
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2
            )
        }

        // 失败详情
        val lastError = ubuntu.lastError
        if (phase == UbuntuLifecycleCoordinator.Phase.FAILED && lastError != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "上次错误：${lastError.take(120)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3
            )
        }

        // 操作按钮（状态驱动）
        Spacer(Modifier.height(10.dp))
        when (phase) {
            UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED -> {
                ActionButton("下载并安装（约数百 MB）", loading = false) { onInstall() }
            }
            UbuntuLifecycleCoordinator.Phase.INSTALLING -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("rootfs 下载/解压中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onCancel) { Text("取消", color = MaterialTheme.colorScheme.error) }
                }
            }
            UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING, UbuntuLifecycleCoordinator.Phase.RECOVERING -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        if (phase == UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING) "初始化：apt 源 / 网络 / 基础包…" else "状态收敛中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            UbuntuLifecycleCoordinator.Phase.ROOTFS_READY -> {
                ActionButton("完成初始化（apt 引导）", loading = false) { onInstall() }
            }
            UbuntuLifecycleCoordinator.Phase.READY -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("新建 Ubuntu 会话", loading = false, modifier = Modifier.weight(1f)) { onCreateSession() }
                    OutlinedButton(onClick = onRepair) { Text("修复") }
                    OutlinedButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            UbuntuLifecycleCoordinator.Phase.FAILED -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("重试安装", loading = false, modifier = Modifier.weight(1f)) { onInstall() }
                    OutlinedButton(onClick = onRepair) { Text("修复") }
                    OutlinedButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

private fun phaseLabel(phase: UbuntuLifecycleCoordinator.Phase): String = when (phase) {
    UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED -> "未安装"
    UbuntuLifecycleCoordinator.Phase.INSTALLING -> "下载/解压中"
    UbuntuLifecycleCoordinator.Phase.ROOTFS_READY -> "待初始化"
    UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING -> "初始化中"
    UbuntuLifecycleCoordinator.Phase.READY -> "已就绪"
    UbuntuLifecycleCoordinator.Phase.RECOVERING -> "恢复中"
    UbuntuLifecycleCoordinator.Phase.FAILED -> "异常"
}

private fun phaseColor(phase: UbuntuLifecycleCoordinator.Phase): Color = when (phase) {
    UbuntuLifecycleCoordinator.Phase.READY -> Color(0xFF3E9C51)
    UbuntuLifecycleCoordinator.Phase.FAILED -> Color(0xFFB06055)
    UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED -> Color(0xFF8A93A3)
    else -> Color(0xFFE0A63C)
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format("%.1f GB", gb)
        mb >= 1 -> String.format("%.0f MB", mb)
        kb >= 1 -> String.format("%.0f KB", kb)
        else -> "$bytes B"
    }
}

// ═══ 共享小组件（TerminalScreen 抽屉同款 —— 本包内复用）═══

@Composable
internal fun SettingsCard(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SurfaceBadge(icon, MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
internal fun SurfaceBadge(icon: ImageVector, tint: Color) {
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.15f), modifier = Modifier.size(34.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = tint) }
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}

@Composable
internal fun DepRow(item: TerminalViewModel.DepItem, busy: Boolean, installing: Boolean, onInstall: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (item.group == TerminalViewModel.DepGroup.ANDROID) Icons.Default.Android else Icons.Default.CheckCircle,
                null, Modifier.size(16.dp),
                tint = if (item.group == TerminalViewModel.DepGroup.ANDROID) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            )
            Text(item.name, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(enabled = !busy, onClick = onInstall) {
            Text(if (installing) "安装中…" else "安装")
        }
    }
}

@Composable
internal fun ActionButton(label: String, loading: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (loading) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).then(
            Modifier.clickableSafe(enabled = !loading, onClick = onClick)
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                if (loading) "$label…" else label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (loading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun Modifier.clickableSafe(enabled: Boolean, onClick: () -> Unit): Modifier =
    this.then(
        this.clickable(enabled = enabled, onClick = onClick)
    )
