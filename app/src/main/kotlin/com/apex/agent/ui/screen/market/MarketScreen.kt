package com.apex.agent.ui.screen.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.mcp.McpTransport

/**
 * 市场（v2 全面重构）
 *
 * 五页签：插件 / Skills / MCP / 连接器 / 集成
 * - 插件：系统 Apex 插件 APK 发现 + 加载/卸载（旧版只展示"已加载"角标，无操作能力）
 * - Skills：已安装（开关/卸载）+ 5 个内置模板一键安装 + JSON 导入 + URL 导入
 * - MCP：添加表单（HTTP/SSE/STDIO + apiKey）/启用开关/连接/断开/删除
 *   （旧版无任何添加入口，MCP enabled 字段空转）
 * - 连接器：ConnectorRegistry 持久化管理（增/开关/删），替代旧版硬编码三个示例
 * - 集成：魔搭 modelscope-skills 官方仓库浏览+搜索+安装 + GitHub 仓库搜索+安装
 *
 * 数据层见 [MarketViewModel]（全部 IO 线程化）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(viewModel: MarketViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.lastMessage) {
        state.lastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
                MarketTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            when (state.selectedTab) {
                MarketTab.PLUGINS -> PluginsTab(state, viewModel)
                MarketTab.SKILLS -> SkillsTab(state, viewModel)
                MarketTab.MCP -> McpTab(state, viewModel)
                MarketTab.CONNECTORS -> ConnectorsTab(state, viewModel)
                MarketTab.INTEGRATIONS -> IntegrationsTab(state, viewModel)
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ═══ 插件页 ═══

@Composable
private fun PluginsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingUnload by remember { mutableStateOf<MarketPluginRow?>(null) }

    MarketList(
        items = state.plugins,
        emptyHint = "未发现已安装的 Apex 插件（安装包含 PLUGIN intent 服务的插件 APK 后自动出现）",
        header = {
            item {
                MarketHeader("通过 PLUGIN intent 服务发现的 Apex 插件，加载后可用 /plugin:<包名> 调用。")
            }
        }
    ) { plugin ->
        MarketCard(
            title = plugin.label,
            subtitle = plugin.packageName,
            description = null,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MarketStatusChip(
                        text = if (plugin.loaded) "已加载" else "未加载",
                        positive = plugin.loaded
                    )
                    TextButton(
                        onClick = {
                            if (plugin.loaded) pendingUnload = plugin
                            else viewModel.loadPlugin(plugin)
                        }
                    ) {
                        Text(if (plugin.loaded) "卸载" else "加载")
                    }
                }
            }
        )
    }

    pendingUnload?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingUnload = null },
            title = { Text("卸载插件") },
            text = { Text("解除与 ${plugin.label} 的绑定？可随时重新加载。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unloadPlugin(plugin.packageName)
                    pendingUnload = null
                }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnload = null }) { Text("取消") }
            }
        )
    }
}

// ═══ Skills 页 ═══

@Composable
private fun SkillsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var showImportDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var pendingUninstall by remember { mutableStateOf<MarketSkillRow?>(null) }

    val rows = state.skills + state.skillTemplates

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = { showImportDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("导入 JSON", style = MaterialTheme.typography.labelMedium)
            }
            ExtendedFloatingActionButton(
                onClick = { showUrlDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("URL 导入", style = MaterialTheme.typography.labelMedium)
            }
        }

        MarketList(
            items = rows,
            emptyHint = "暂无技能（上方可导入，或从「集成」页安装魔搭技能）",
            header = {
                item {
                    MarketHeader("启用后技能指令注入对话；安装的模板可用 /skill:<id> 调用。运行期安装的 composite 工具需重启 App 注册。")
                }
            },
            key = { it.id }
        ) { skill ->
            MarketCard(
                title = skill.name,
                subtitle = skill.id,
                description = skill.description,
                trailing = {
                    if (skill.installed) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Switch(
                                checked = skill.enabled,
                                onCheckedChange = { viewModel.toggleSkill(skill.id, it) }
                            )
                            IconButton(onClick = { pendingUninstall = skill }) {
                                Text("🗑", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else {
                        TextButton(onClick = { viewModel.installSkillTemplate(skill.id) }) {
                            Text("安装")
                        }
                    }
                }
            )
        }
    }

    if (showImportDialog) {
        ImportSkillJsonDialog(
            onDismiss = { showImportDialog = false },
            onImport = { json ->
                viewModel.importSkillJson(json)
                showImportDialog = false
            }
        )
    }
    if (showUrlDialog) {
        ImportFromUrlDialog(
            title = "从 URL 导入 Skill",
            hint = "https://example.com/skill.json",
            onDismiss = { showUrlDialog = false },
            onConfirm = { url ->
                viewModel.importSkillFromUrl(url)
                showUrlDialog = false
            }
        )
    }
    pendingUninstall?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("卸载技能") },
            text = { Text("卸载「${skill.name}」？其 manifest 与资源目录将一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstallSkill(skill.id)
                    pendingUninstall = null
                }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("取消") }
            }
        )
    }
}

// ═══ MCP 页 ═══

@Composable
private fun McpTab(state: MarketUiState, viewModel: MarketViewModel) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<MarketMcpRow?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        ExtendedFloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("添加 MCP 服务器")
        }

        MarketList(
            items = state.mcps,
            emptyHint = "未配置 MCP 服务器（点上方按钮添加，HTTP/SSE/STDIO 均可）",
            header = {
                item {
                    MarketHeader("已启用的服务器出现在 / 菜单（/mcp:<名称>）；连接后其工具注入 Agent 对话。禁用 = 从菜单隐藏并断开。")
                }
            },
            key = { it.name }
        ) { server ->
            MarketCard(
                title = server.name,
                subtitle = server.url,
                description = "传输：" + server.transport.name,
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MarketStatusChip(
                            text = when {
                                server.connected -> "已连接"
                                server.enabled -> "离线"
                                else -> "已禁用"
                            },
                            positive = server.connected
                        )
                        Switch(
                            checked = server.enabled,
                            onCheckedChange = { viewModel.toggleMcp(server.name, it) }
                        )
                        TextButton(
                            onClick = {
                                if (server.connected) viewModel.disconnectMcp(server.name)
                                else viewModel.connectMcp(server.name)
                            }
                        ) {
                            Text(if (server.connected) "断开" else "连接")
                        }
                        TextButton(
                            onClick = { pendingDelete = server }
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    }

    if (showAddDialog) {
        AddMcpDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, transport, apiKey ->
                viewModel.addMcpServer(name, url, transport, apiKey)
                showAddDialog = false
            }
        )
    }
    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 MCP 服务器") },
            text = { Text("删除「${server.name}」的配置？活跃连接将被断开。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMcp(server.name)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

// ═══ 连接器页 ═══

@Composable
private fun ConnectorsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ConnectorDef?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        ExtendedFloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("添加连接器")
        }

        MarketList(
            items = state.connectors,
            emptyHint = "无连接器",
            header = {
                item {
                    MarketHeader("连接器 = 对外部服务（API/SSH/数据库/网盘）的访问配置。启用的连接器出现在 / 菜单（/connector:<id>）。内置示例删除后重启恢复。")
                }
            },
            key = { it.id }
        ) { connector ->
            MarketCard(
                title = connector.name,
                subtitle = connector.id,
                description = connector.type + if (connector.endpoint.isNotBlank()) " · ${connector.endpoint}" else "",
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (connector.builtin) {
                            MarketStatusChip(text = "内置", positive = false)
                        }
                        Switch(
                            checked = connector.enabled,
                            onCheckedChange = { viewModel.toggleConnector(connector.id, it) }
                        )
                        TextButton(
                            onClick = { pendingDelete = connector }
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    }

    if (showAddDialog) {
        AddConnectorDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { id, name, type, endpoint ->
                viewModel.addConnector(id, name, type, endpoint)
                showAddDialog = false
            }
        )
    }
    pendingDelete?.let { connector ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除连接器") },
            text = { Text("删除「${connector.name}」？" + if (connector.builtin) "（内置示例，重启后恢复）" else "") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeConnector(connector.id)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

// ═══ 集成页（魔搭 + GitHub）═══

@Composable
private fun IntegrationsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var msLoadedOnce by rememberSaveable { mutableStateOf(false) }

    // 首次进入集成页自动加载魔搭技能列表
    LaunchedEffect(Unit) {
        if (!msLoadedOnce && state.modelScopeSkills.isEmpty() && !state.modelScopeLoading) {
            msLoadedOnce = true
            viewModel.loadModelScopeSkills()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── 魔搭源 ──
        item {
            MarketSectionTitle("魔搭 ModelScope Skills（官方 modelscope-skills 仓库）")
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.modelScopeQuery,
                    onValueChange = viewModel::filterModelScope,
                    label = { Text("过滤技能") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                TextButton(onClick = viewModel::loadModelScopeSkills) {
                    Text(if (state.modelScopeLoading) "加载中…" else "刷新")
                }
            }
        }
        state.modelScopeError?.let { error ->
            item {
                MarketCard(title = "魔搭加载失败", subtitle = null, description = error, trailing = null)
            }
        }
        if (state.modelScopeLoading && state.modelScopeSkills.isEmpty()) {
            item { MarketHint("正在拉取仓库技能目录（GitHub API）…") }
        }
        items(
            state.modelScopeSkills,
            key = { "ms-" + it.id }
        ) { skill ->
            val installed = "ms-${skill.id}" in state.installedModelScopeIds
            MarketCard(
                title = skill.name,
                subtitle = "ms-${skill.id}",
                description = skill.description,
                trailing = {
                    if (installed) {
                        MarketStatusChip(text = "已安装", positive = true)
                    } else {
                        TextButton(
                            onClick = { viewModel.installModelScopeSkill(skill) },
                            enabled = !state.busy
                        ) { Text("安装") }
                    }
                }
            )
        }

        // ── GitHub 源 ──
        item {
            MarketSectionTitle("GitHub 仓库搜索（含 apex-skill-v1 manifest 的仓库可一键安装）")
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.githubQuery,
                    onValueChange = viewModel::updateGithubQuery,
                    label = { Text("仓库名 / 关键词") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                TextButton(
                    onClick = viewModel::searchGithub,
                    enabled = !state.githubSearching && state.githubQuery.isNotBlank()
                ) {
                    Text(if (state.githubSearching) "搜索中…" else "搜索")
                }
            }
        }
        state.githubError?.let { error ->
            item {
                MarketCard(title = "GitHub 搜索失败", subtitle = null, description = error, trailing = null)
            }
        }
        items(
            state.githubHits,
            key = { "gh-" + it.fullName }
        ) { hit ->
            MarketCard(
                title = hit.fullName,
                subtitle = "★ ${hit.stars}",
                description = hit.description,
                trailing = {
                    TextButton(
                        onClick = { viewModel.installGithubRepo(hit.fullName) },
                        enabled = !state.busy
                    ) { Text("安装") }
                }
            )
        }
    }
}

// ═══ 通用件 ═══

@Composable
private fun MarketSectionTitle(text: String) {
    Column {
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MarketHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun MarketHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun <T> MarketList(
    items: List<T>,
    emptyHint: String,
    header: (LazyListScope.() -> Unit)? = null,
    key: ((T) -> Any)? = null,
    itemContent: @Composable LazyItemScope.(T) -> Unit
) {
    if (items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        header?.invoke(this)
        if (key != null) {
            items(items, key = key, itemContent = itemContent)
        } else {
            items(items, itemContent = itemContent)
        }
    }
}

@Composable
private fun MarketCard(
    title: String,
    subtitle: String?,
    description: String?,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                trailing?.invoke(this@Row)
            }
            if (!description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MarketStatusChip(text: String, positive: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (positive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (positive) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
