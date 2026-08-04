package com.apex.agent.ui.screen.permissions

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.apex.agent.platform.privilege.PrivilegeDetector
import com.apex.agent.platform.privilege.shizuku.ShizukuCommandExecutor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen() {
    var hasRoot by remember { mutableStateOf(false) }
    var hasShizuku by remember { mutableStateOf(false) }

    // 在后台检测权限
    LaunchedEffect(Unit) {
        hasRoot = PrivilegeDetector.detectRoot()
        hasShizuku = PrivilegeDetector.detectShizuku()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("权限管理") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Root
            PermissionCard(
                icon = Icons.Default.AdminPanelSettings,
                title = "Root",
                description = "最高权限，可执行所有系统操作（/system、/data、mount、SELinux）",
                status = if (hasRoot) "✅ 已获得" else "❌ 未获得",
                actionLabel = "检测"
            )

            // Shizuku — 专用卡片，带安装/授权引导
            ShizukuPermissionCard(
                shizukuAvailable = hasShizuku,
                onStatusChanged = { hasShizuku = PrivilegeDetector.detectShizuku() }
            )

            // 无障碍
            PermissionCard(
                icon = Icons.Default.Accessibility,
                title = "无障碍服务",
                description = "读取UI树、模拟点击、截图（Agent的眼睛和手）",
                status = "未开启",
                actionLabel = "开启"
            )
            PermissionCard(
                icon = Icons.Default.Layers,
                title = "悬浮窗",
                description = "在其他应用上方显示内容",
                status = "未授权",
                actionLabel = "授权"
            )
            PermissionCard(
                icon = Icons.Default.Notifications,
                title = "通知权限",
                description = "发送前台服务通知、读取通知",
                status = "未授权",
                actionLabel = "授权"
            )
            PermissionCard(
                icon = Icons.Default.Folder,
                title = "存储权限",
                description = "读写文件（工作区、下载）",
                status = "未授权",
                actionLabel = "授权"
            )
        }
    }
}

/**
 * Shizuku 专用权限卡片
 *
 * 三态显示 + 引导按钮：
 * - 未运行 → "安装/启动" 按钮（打开 Shizuku app 或下载页）
 * - 运行中但未授权 → "授权" 按钮（调用 Shizuku.requestPermission）
 * - 已授权 → "已就绪" 标记
 */
@Composable
private fun ShizukuPermissionCard(
    shizukuAvailable: Boolean,
    onStatusChanged: () -> Unit
) {
    val context = LocalContext.current
    val shizukuRunning = remember { mutableStateOf(false) }
    val shizukuPermission = remember { mutableStateOf(false) }

    // 检测细分状态：服务运行中？已授权？
    LaunchedEffect(shizukuAvailable) {
        shizukuRunning.value = ShizukuCommandExecutor.isAvailable()
        shizukuPermission.value = ShizukuCommandExecutor.hasPermission()
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Shield, null,
                tint = if (shizukuPermission.value) MaterialTheme.colorScheme.tertiary
                       else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text("Shizuku", style = MaterialTheme.typography.titleSmall)
                Text(
                    "ADB级权限，无需Root即可执行pm/am/settings等系统命令",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val statusText = when {
                    shizukuPermission.value -> "✅ 已授权"
                    shizukuRunning.value -> "⚠️ 运行中，需要授权"
                    else -> "❌ 未运行"
                }
                val statusColor = when {
                    shizukuPermission.value -> MaterialTheme.colorScheme.tertiary
                    shizukuRunning.value -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.error
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }

            Button(
                onClick = {
                    when {
                        !shizukuRunning.value -> {
                            // Shizuku 未运行 — 尝试打开 Shizuku app
                            try {
                                val intent = Intent().apply {
                                    setClassName(
                                        "moe.shizuku.privileged.api",
                                        "moe.shizuku.manager.MainActivity"
                                    )
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Shizuku app 未安装 — 打开下载页
                                val uri = Uri.parse("https://shizuku.rikka.app/")
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                context.startActivity(intent)
                            }
                        }
                        !shizukuPermission.value -> {
                            // 请求权限
                            ShizukuCommandExecutor.requestPermission(1001)
                        }
                    }
                    // 刷新状态
                    onStatusChanged()
                    shizukuRunning.value = ShizukuCommandExecutor.isAvailable()
                    shizukuPermission.value = ShizukuCommandExecutor.hasPermission()
                }
            ) {
                Text(
                    when {
                        shizukuPermission.value -> "已就绪"
                        shizukuRunning.value -> "授权"
                        else -> "安装/启动"
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    status: String,
    actionLabel: String
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = { /* TODO: 跳转对应权限页 */ }) {
                Text(actionLabel)
            }
        }
    }
}
