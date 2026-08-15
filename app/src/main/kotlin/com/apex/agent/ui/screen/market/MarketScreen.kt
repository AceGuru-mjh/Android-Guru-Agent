package com.apex.agent.ui.screen.market

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.ui.component.GlassTab
import com.apex.agent.ui.component.LiquidGlassNavBar

/** 市场主屏：内容区 + 底部液态玻璃导航栏（插件 / Skills / MCP / 连接器 / 集成） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(
    viewModel: MarketViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showToast by remember { mutableStateOf(false) }

    val tabs = listOf(
        GlassTab(MarketTab.PLUGINS.id, MarketTab.PLUGINS.label, Icons.Default.Extension),
        GlassTab(MarketTab.SKILLS.id, MarketTab.SKILLS.label, Icons.Default.AutoAwesome),
        GlassTab(MarketTab.MCP.id, MarketTab.MCP.label, Icons.Default.Api),
        GlassTab(MarketTab.CONNECTORS.id, MarketTab.CONNECTORS.label, Icons.Default.Link),
        GlassTab(MarketTab.INTEGRATIONS.id, MarketTab.INTEGRATIONS.label, Icons.Default.Cloud)
    )

    LaunchedEffect(state.lastMessage) {
        if (state.lastMessage != null) showToast = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ═══ 当前页签内容 ═══
        Box(modifier = Modifier.weight(1f)) {
            when (state.selectedTab) {
                MarketTab.PLUGINS -> PluginsTab(state, viewModel)
                MarketTab.SKILLS -> SkillsTab(state, viewModel)
                MarketTab.MCP -> McpTab(state, viewModel)
                MarketTab.CONNECTORS -> ConnectorsTab(state, viewModel)
                MarketTab.INTEGRATIONS -> IntegrationsTab(state, viewModel)
            }

            // 忙碌遮罩
            if (state.busy) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.25f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            // 结果提示条
            val msg = state.lastMessage
            if (showToast && msg != null) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { showToast = false; viewModel.clearMessage() }) {
                            Text("知道了")
                        }
                    }
                ) { Text(msg) }
            }
        }

        // ═══ 液态玻璃底部导航栏 ═══
        LiquidGlassNavBar(
            tabs = tabs,
            selectedId = state.selectedTab.id,
            onSelect = { id ->
                viewModel.selectTab(MarketTab.entries.first { it.id == id })
            },
            modifier = Modifier.padding(bottom = 10.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 页签 1：插件
// ═══════════════════════════════════════════════════════════
@Composable
private fun PluginsTab(state: MarketUiState, viewModel: MarketViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SectionHeader(
                icon = Icons.Default.Build,
                title = "已发现的插件",
                subtitle = "安装 Apex 插件 APK 后会自动出现在这里；开启后可在输入框输入 /plugin:<包名> 调用"
            )
        }
        if (state.plugins.isEmpty()) {
            item {
                EmptyHint("未发现任何插件 APK\n\n安装包含 com.apex.agent.plugin.PLUGIN 服务的 APK 后刷新")
            }
        } else {
            items(state.plugins, key = { it.packageName }) { info ->
                val loaded = info.packageName in state.loadedPlugins
                MarketCard(
                    icon = Icons.Default.Extension,
                    title = info.label,
                    description = info.packageName,
                    trailing = {
                        Switch(
                            checked = loaded,
                            onCheckedChange = { viewModel.togglePlugin(info.packageName, it) }
                        )
                    }
                )
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 页签 2：Skills
// ═══════════════════════════════════════════════════════════
@Composable
private fun SkillsTab(state: MarketUiState, viewModel: MarketViewModel) {
    val context = LocalContext.current
    var showImportJson by remember { mutableStateOf(false) }
    var pendingUninstall by remember { mutableStateOf<String?>(null) }

    // 自定义导入：选择 apex-skill-v1 JSON 文件
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val json = readUriText(context, uri)
        if (json != null) viewModel.installSkillJson(json)
        else viewModel.selectTab(MarketTab.SKILLS) // noop 防误触
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(
                    icon = Icons.Default.AutoAwesome,
                    title = "已安装 Skill（${state.skills.size}）",
                    subtitle = "开启的 Skill 出现在输入框 / 菜单，可用 /skill:<id> 调用"
                )
                IconButton(onClick = { importLauncher.launch("application/json") }) {
                    Icon(Icons.Default.FileUpload, contentDescription = "导入 Skill JSON")
                }
            }
        }
        if (state.skills.isEmpty()) {
            item { EmptyHint("还没有安装任何 Skill\n\n可从下方内置模板安装、从文件导入，或在集成页从魔搭/GitHub 获取") }
        } else {
            items(state.skills, key = { it.manifest.id }) { skill ->
                MarketCard(
                    icon = Icons.Default.AutoAwesome,
                    title = skill.manifest.name,
                    description = skill.manifest.description,
                    subtitle = "v${skill.manifest.version} · ${skill.manifest.author}",
                    enabled = skill.enabled,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = skill.enabled,
                                onCheckedChange = { viewModel.toggleSkill(skill.manifest.id, it) }
                            )
                            IconButton(onClick = { pendingUninstall = skill.manifest.id }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "卸载",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                )
            }
        }

        item {
            SectionHeader(
                icon = Icons.Default.Download,
                title = "内置模板（${state.skillTemplates.count { !it.installed }} 个可安装）",
                subtitle = "一键安装官方内置 Skill 模板"
            )
        }
        items(state.skillTemplates, key = { it.id }) { template ->
            MarketCard(
                icon = if (template.installed) Icons.Default.Check else Icons.Default.Download,
                title = template.name,
                description = template.description,
                subtitle = template.id,
                trailing = {
                    if (template.installed) {
                        Text(
                            "已安装",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        TextButton(onClick = { viewModel.installTemplate(template.id) }) {
                            Text("安装")
                        }
                    }
                }
            )
        }

        item { Spacer(Modifier.height(8.dp)) }
    }

    // 卸载确认
    pendingUninstall?.let { skillId ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("卸载 Skill") },
            text = { Text("确定要卸载 $skillId 吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstallSkill(skillId)
                    pendingUninstall = null
                }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("取消") }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 页签 3：MCP
// ═══════════════════════════════════════════════════════════
@Composable
private fun McpTab(state: MarketUiState, viewModel: MarketViewModel) {
    var showAdd by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(
                    icon = Icons.Default.Api,
                    title = "MCP 服务器（${state.mcps.size}）",
                    subtitle = "开启的 MCP 出现在输入框 / 菜单，可用 /mcp:<名称> 调用；连接状态单独控制"
                )
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加 MCP 服务器")
                }
            }
        }
        if (state.mcps.isEmpty()) {
            item { EmptyHint("还没有配置 MCP 服务器\n\n点击右上角 + 添加（HTTP / SSE / STDIO），或在集成页从魔搭获取") }
        } else {
            items(state.mcps, key = { it.name }) { cfg ->
                val connected = cfg.name in state.connectedMcps
                MarketCard(
                    icon = Icons.Default.Api,
                    title = cfg.name,
                    description = cfg.url,
                    subtitle = "${cfg.transport.name}" + if (connected) " · 已连接" else " · 离线",
                    enabled = cfg.enabled,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!connected) {
                                TextButton(onClick = { viewModel.connectMcp(cfg.name) }) { Text("连接") }
                            }
                            Switch(
                                checked = cfg.enabled,
                                onCheckedChange = { viewModel.toggleMcp(cfg.name, it) }
                            )
                            IconButton(onClick = { pendingRemove = cfg.name }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (showAdd) {
        AddMcpDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, url, transport, apiKey ->
                viewModel.addMcpServer(name, url, transport, apiKey)
                showAdd = false
            }
        )
    }

    pendingRemove?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("删除 MCP 服务器") },
            text = { Text("确定删除 $name 吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMcp(name)
                    pendingRemove = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 页签 4：连接器
// ═══════════════════════════════════════════════════════════
@Composable
private fun ConnectorsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var showAdd by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(
                    icon = Icons.Default.Link,
                    title = "连接器（${state.connectors.size}）",
                    subtitle = "开启的连接器出现在输入框 / 菜单，可用 /connector:<id> 调用"
                )
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加连接器")
                }
            }
        }
        items(state.connectors, key = { it.id }) { def ->
            MarketCard(
                icon = Icons.Default.Link,
                title = def.name,
                description = def.endpoint.ifBlank { def.type },
                subtitle = if (def.builtin) "内置示例" else "自定义",
                enabled = def.enabled,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = def.enabled,
                            onCheckedChange = { viewModel.toggleConnector(def.id, it) }
                        )
                        IconButton(onClick = { pendingRemove = def.id }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (showAdd) {
        AddConnectorDialog(
            onDismiss = { showAdd = false },
            onAdd = { def ->
                viewModel.addConnector(def)
                showAdd = false
            }
        )
    }

    pendingRemove?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("删除连接器") },
            text = { Text("确定删除 $id 吗？（内置示例删除后重启会恢复）") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeConnector(id)
                    pendingRemove = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 页签 5：集成（魔搭 + GitHub + 自定义导入）
// ═══════════════════════════════════════════════════════════
@Composable
private fun IntegrationsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var showUrlImport by remember { mutableStateOf(false) }
    val q = state.modelScopeQuery
    val modelScopeVisible = if (q.isBlank()) state.modelScopeSkills else state.modelScopeSkills.filter {
        it.name.contains(q, ignoreCase = true) ||
            it.description.contains(q, ignoreCase = true) ||
            it.id.contains(q, ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── 魔搭源 ──
        item {
            SectionHeader(
                icon = Icons.Default.Cloud,
                title = "魔搭 ModelScope",
                subtitle = "来自官方 modelscope/modelscope-skills 仓库，安装后出现在 Skills 页",
                action = {
                    IconButton(onClick = { viewModel.loadModelScope() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新魔搭")
                    }
                }
            )
        }
        item {
            OutlinedTextField(
                value = state.modelScopeQuery,
                onValueChange = { viewModel.setModelScopeQuery(it) },
                label = { Text("搜索魔搭技能") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            )
        }
        if (state.modelScopeLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        } else if (modelScopeVisible.isEmpty()) {
            item { EmptyHint("暂无魔搭技能（或搜索无结果）") }
        } else {
            items(modelScopeVisible, key = { it.id }) { skill ->
                MarketCard(
                    icon = Icons.Default.Cloud,
                    title = skill.name,
                    description = skill.description,
                    subtitle = skill.id,
                    trailing = {
                        if (viewModel.isSkillInstalled(skill.id)) {
                            Text(
                                "已安装",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            TextButton(onClick = { viewModel.installModelScopeSkill(skill) }) {
                                Text("安装")
                            }
                        }
                    }
                )
            }
        }

        // ── GitHub 源 ──
        item {
            SectionHeader(
                icon = Icons.Default.Build,
                title = "GitHub 搜索",
                subtitle = "搜索包含 apex-skill manifest 的仓库；安装时自动探测仓库内的 manifest 文件"
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.githubQuery,
                    onValueChange = { viewModel.setGithubQuery(it) },
                    label = { Text("搜索 GitHub（如 apex-skill agent）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { viewModel.searchGithub() },
                    enabled = !state.githubSearching
                ) {
                    if (state.githubSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                }
            }
        }
        if (state.githubHits.isNotEmpty()) {
            items(state.githubHits, key = { it.fullName }) { hit ->
                MarketCard(
                    icon = Icons.Default.Build,
                    title = hit.fullName,
                    description = hit.description,
                    subtitle = "⭐ ${hit.stars}",
                    trailing = {
                        TextButton(onClick = { viewModel.installFromGitHubRepo(hit.fullName) }) {
                            Text("安装")
                        }
                    }
                )
            }
        }

        // ── 自定义导入 ──
        item {
            SectionHeader(
                icon = Icons.Default.FileUpload,
                title = "自定义导入",
                subtitle = "粘贴 Skill manifest URL（apex-skill-v1 JSON）一键安装"
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                TextButton(onClick = { showUrlImport = true }) {
                    Text("从 URL 导入 Skill")
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (showUrlImport) {
        UrlImportDialog(
            onDismiss = { showUrlImport = false },
            onImport = { url ->
                viewModel.installSkillUrl(url)
                showUrlImport = false
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 通用组件
// ═══════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        action?.invoke()
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** 市场通用卡片：图标 + 标题/描述 + 开关/操作区；启用时霓虹描边 */
@Composable
private fun MarketCard(
    icon: ImageVector,
    title: String,
    description: String,
    subtitle: String = "",
    enabled: Boolean = true,
    trailing: @Composable () -> Unit
) {
    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .then(
                if (enabled)
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = borderColor,
                            style = Stroke(1.dp.toPx()),
                            cornerRadius = CornerRadius(12.dp.toPx())
                        )
                    }
                else Modifier
            ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.14f else 0.05f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            trailing()
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 对话框
// ═══════════════════════════════════════════════════════════

@Composable
private fun AddMcpDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, transport: String, apiKey: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("HTTP") }
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 MCP 服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（用于 /mcp:<名称> 调用）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("传输协议", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("HTTP", "SSE", "STDIO").forEach { t ->
                        val sel = t == transport
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { transport = t }
                        ) {
                            Text(
                                t,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && url.isNotBlank(),
                onClick = { onAdd(name, url, transport, apiKey) }
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun AddConnectorDialog(
    onDismiss: () -> Unit,
    onAdd: (ConnectorDef) -> Unit
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("api") }
    var endpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加连接器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("ID（用于 /connector:<id> 调用）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("类型", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("api" to "API", "ssh" to "SSH", "database" to "数据库", "storage" to "存储").forEach { (v, label) ->
                        val sel = v == type
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { type = v }
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("端点地址（URL / host）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("密钥（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = id.isNotBlank() && name.isNotBlank(),
                onClick = {
                    onAdd(
                        ConnectorDef(
                            id = id.trim(),
                            name = name.trim(),
                            type = type,
                            endpoint = endpoint.trim(),
                            apiKey = apiKey.ifBlank { null }
                        )
                    )
                }
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun UrlImportDialog(
    onDismiss: () -> Unit,
    onImport: (url: String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从 URL 导入 Skill") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("https://.../skill.json（apex-skill-v1）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = url.startsWith("http"),
                onClick = { onImport(url.trim()) }
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 读取 content URI 的文本内容（Skill JSON 导入） */
private fun readUriText(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()
}
