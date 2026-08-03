package com.apex.agent.ui.screen.permissions

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
import androidx.compose.ui.unit.dp
import com.apex.agent.platform.privilege.PrivilegeDetector

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
            PermissionCard(
                icon = Icons.Default.AdminPanelSettings,
                title = "Root",
                description = "最高权限，可执行所有系统操作",
                status = if (hasRoot) "✅ 已获得" else "❌ 未获得",
                actionLabel = "检测"
            )
            PermissionCard(
                icon = Icons.Default.Shield,
                title = "Shizuku",
                description = "ADB级权限，无需Root即可执行系统命令",
                status = if (hasShizuku) "✅ 已连接" else "❌ 未连接",
                actionLabel = "连接"
            )
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
