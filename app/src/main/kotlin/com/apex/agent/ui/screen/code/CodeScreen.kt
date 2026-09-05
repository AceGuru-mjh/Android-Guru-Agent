package com.apex.agent.ui.screen.code

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Code Screen（Spec §48）。
 *
 * 布局：
 * ```
 * ┌─────────────────────────────────┐
 * │ Code        Project ▼      ⋮  │  顶部栏：workspace 切换 + 操作
 * ├─────────────────────────────────┤
 * │                                 │
 * │         Code Agent 消息流       │  Agent 思考/工具/回复
 * │                                 │
 * ├─────────────────────────────────┤
 * │ Files │ Changes │ Problems │   │  底部三栏 Tab
 * ├─────────────────────────────────┤
 * │ Ask Code...                ➤   │  输入栏
 * └─────────────────────────────────┘
 * ```
 *
 * 手机：单列 + Tab 切换底栏。平板：Explorer 与 Agent 可分屏（v2）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeScreen(
    viewModel: CodeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // ═══ 顶部：workspace 刏 ═══
        CodeTopBar(
            state = state,
            onOpenWorkspace = viewModel::openWorkspace,
            onCreateWorkspace = { name -> viewModel.createWorkspace(name) },
            onCloseWorkspace = viewModel::closeWorkspace
        )

        // ═══ 中部：Agent 消息流 ═══
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.activeWorkspace == null) {
                CodeWorkspacePicker(state = state, onOpen = viewModel::openWorkspace, viewModel = viewModel)
            } else {
                CodeAgentStream(state = state)
            }
        }

        // ═══ 底部：Files / Changes / Problems Tab ═══
        CodeBottomTabs(state = state, onSelect = viewModel::selectBottomTab)

        // ═══ 输入栏 ═══
        CodeInputBar(state = state, viewModel = viewModel)
    }
}

@Composable
private fun CodeTopBar(
    state: CodeUiState,
    onOpenWorkspace: (String) -> Unit,
    onCreateWorkspace: (String) -> Unit,
    onCloseWorkspace: () -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text("Code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                state.activeWorkspace?.let { ws ->
                    Text(
                        "📁 ${ws.name} · ${ws.detectedEnvironment ?: "—"} · ${ws.buildSystem ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } ?: Text("未打开项目", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.activeWorkspace != null) {
                TextButton(onClick = onCloseWorkspace) { Text("关闭") }
            } else {
                TextButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建")
                }
            }
        }
    }
    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建 Code Workspace") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("项目名") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) { onCreateWorkspace(name.trim()); showCreate = false }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun CodeWorkspacePicker(state: CodeUiState, onOpen: (String) -> Unit, viewModel: CodeViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text("打开一个 Code Workspace 开始", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Code Agent 在 Android 上理解、修改、构建、测试你的代码仓库", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.recentWorkspaces.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("最近项目", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            state.recentWorkspaces.forEach { ws ->
                ListItem(
                    headlineContent = { Text(ws.name) },
                    supportingContent = { Text("${ws.detectedEnvironment ?: "—"} · ${ws.workspaceId}") },
                    modifier = Modifier.clickable { onOpen(ws.workspaceId) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CodeAgentStream(state: CodeUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        items(state.messages, key = { it.id }) { msg ->
            CodeMessageBubble(msg)
        }
        if (state.isLoading) {
            item {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    if (state.currentToolCall.isNotEmpty()) {
                        Text("🔧 ${state.currentToolCall}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    } else if (state.currentThinking.isNotEmpty()) {
                        Text("💭 ${state.currentThinking.take(120)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (state.currentResponse.isNotEmpty()) {
                        Text(state.currentResponse, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("工作中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeMessageBubble(msg: CodeUiMessage) {
    when (msg) {
        is CodeUiMessage.User -> Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
            Text("你: ${msg.text}", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        is CodeUiMessage.Assistant -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Text("Code: ${msg.text}", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        is CodeUiMessage.Tool -> Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("🔧 ${msg.name} ${if (msg.success) "✅" else "❌"}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                if (msg.output.isNotBlank()) Text(msg.output, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 8)
            }
        }
        is CodeUiMessage.Thinking -> Text("💭 ${msg.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        is CodeUiMessage.System -> Text("ℹ ${msg.text}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
private fun CodeBottomTabs(state: CodeUiState, onSelect: (CodeBottomTab) -> Unit) {
    Surface(tonalElevation = 1.dp) {
        TabRow(selectedTabIndex = state.activeBottomTab.ordinal) {
            CodeBottomTab.entries.forEach { tab ->
                Tab(
                    selected = state.activeBottomTab == tab,
                    onClick = { onSelect(tab) },
                    text = {
                        val label = when (tab) {
                            CodeBottomTab.FILES -> "Files"
                            CodeBottomTab.CHANGES -> "Changes"
                            CodeBottomTab.PROBLEMS -> "Problems (${state.problemsSummary})"
                            CodeBottomTab.TERMINAL -> "Terminal"
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                )
            }
        }
    }
}

@Composable
private fun CodeInputBar(state: CodeUiState, viewModel: CodeViewModel) {
    if (state.activeWorkspace == null) return
    var text by remember { mutableStateOf("") }
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; viewModel.updateInput(it) },
                placeholder = { Text("Ask Code Agent…  (e.g. 修改所有 UserManager 的引用)") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            if (state.isLoading) {
                FilledTonalIconButton(onClick = viewModel::abort) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
            } else {
                FilledIconButton(onClick = { viewModel.sendTask(text); text = "" }, enabled = text.isNotBlank()) {
                    Icon(Icons.Default.Send, contentDescription = "发送")
                }
            }
        }
    }
}
