package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // LLM设置
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("LLM 配置", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        label = { Text("API Base URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        label = { Text("Model Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 权限管理
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("权限管理", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { /* 打开Shizuku */ }) {
                        Text("配置 Shizuku")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { /* 打开无障碍设置 */ }) {
                        Text("开启无障碍服务")
                    }
                }
            }
        }
    }
}
