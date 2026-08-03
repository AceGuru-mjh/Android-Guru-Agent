package com.apex.agent.ui.screen.status

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    viewModel: StatusViewModel = hiltViewModel()
) {
    val status by viewModel.status.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("系统状态") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 权限状态卡片
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("权限状态", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    StatusRow("Root", status.hasRoot)
                    StatusRow("Shizuku", status.hasShizuku)
                    StatusRow("无障碍", status.hasAccessibility)
                    StatusRow("前台服务", status.foregroundServiceRunning)
                }
            }

            // Agent状态
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Agent 引擎", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "LLM: ${status.llmProvider ?: "未配置"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "已注册工具: ${status.toolCount}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "已加载插件: ${status.pluginCount}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Linux环境
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Linux 环境", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "运行时: ${status.linuxRuntime ?: "未安装"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Python: ${status.pythonVersion ?: "未安装"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (active) "✅ 已激活" else "❌ 未激活",
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) MaterialTheme.colorScheme.tertiary 
                    else MaterialTheme.colorScheme.error
        )
    }
}
