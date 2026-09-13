package com.apex.agent.ui.screen.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp

/**
 * ═══ 市场 · 发现视图（BROWSE）═══
 *
 * 顶栏「市场」视图下的五个子页签，只放「发现 / 安装」语义的内容：
 * - 插件：设备上发现的可加载插件 APK（本地发现即市场）；
 * - Skills：GitHub / URL / JSON 安装入口 + 内置模板展示；
 * - MCP：添加工具源 / 导入社区配置两个安装入口；
 * - 连接器：添加连接器入口；
 * - 集成：魔搭 ModelScope + GitHub 仓库搜索。
 *
 * 管理动作（启停 / 卸载 / 断开）一律收敛到「已安装管理」视图
 * （见 [MarketInstalledTabs.kt]）—— 同一分类下两视图互补，不再混排。
 */

// ═══ 市场 · 插件 ═══

@Composable
internal fun BrowsePluginsTab(state: MarketUiState, viewModel: MarketViewModel) {
    MarketList(
        items = state.plugins,
        key = { it.packageName },
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
                if (plugin.loaded) {
                    MarketStatusChip(text = "已加载", positive = true)
                } else {
                    TextButton(onClick = { viewModel.loadPlugin(plugin) }) {
                        Text("加载")
                    }
                }
            }
        )
    }
}

// ═══ 市场 · Skills ═══

@Composable
internal fun BrowseSkillsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var showImportDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showRepoDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = { showRepoDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("GitHub 获取", style = MaterialTheme.typography.labelMedium)
            }
            ExtendedFloatingActionButton(
                onClick = { showUrlDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("URL 安装", style = MaterialTheme.typography.labelMedium)
            }
        }
        OutlinedButton(
            onClick = { showImportDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text("粘贴 manifest JSON", style = MaterialTheme.typography.labelMedium)
        }

        val templates = state.skillTemplates
        if (templates.isEmpty()) {
            MarketEmptyState(hint = "暂无可展示的技能模板")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    MarketHeader(
                        "内置 = App 自带能力（工具已原生注册，无安装步骤，用 /skill:<id> 直接调用）；" +
                            "其余技能需从 GitHub 或 URL 联网拉取，安装后到「已安装管理」启停。"
                    )
                }
                items(templates, key = { it.id }) { skill ->
                    MarketCard(
                        title = skill.name,
                        subtitle = skill.id,
                        description = skill.description,
                        trailing = {
                            MarketStatusChip(text = "内置", positive = false)
                        }
                    )
                }
            }
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
            title = "从 URL 安装 Skill",
            hint = "manifest JSON 直链，如 https://raw.githubusercontent.com/o/r/main/manifest.json",
            onDismiss = { showUrlDialog = false },
            onConfirm = { url ->
                viewModel.importSkillFromUrl(url)
                showUrlDialog = false
            }
        )
    }
    if (showRepoDialog) {
        GitHubRepoInstallDialog(
            busy = state.busy,
            onDismiss = { showRepoDialog = false },
            onConfirm = { input ->
                viewModel.installFromRepoInput(input)
                showRepoDialog = false
            }
        )
    }
}

// ═══ 市场 · MCP ═══

@Composable
internal fun BrowseMcpTab(state: MarketUiState, viewModel: MarketViewModel) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            MarketHeader(
                "MCP 不一定是服务器：STDIO 型就是一条本地命令（如 npx -y @modelcontextprotocol/server-memory），" +
                    "由 App 拉起子进程通过 stdin/stdout 通信。添加的工具源启用后出现在 / 菜单（/mcp:<名称>）。"
            )
        }
        item {
            MarketInstallActionCard(
                title = "添加工具源",
                description = "远端 HTTP / SSE 或本地命令 STDIO，含 apiKey 支持；添加后自动尝试连接。"
            ) {
                TextButton(onClick = { showAddDialog = true }) { Text("添加") }
            }
        }
        item {
            MarketInstallActionCard(
                title = "导入配置",
                description = "粘贴社区通用 MCP 配置 JSON（{\"mcpServers\": {...}}），支持 command/args/env 与 url 两种形态。"
            ) {
                TextButton(onClick = { showImportDialog = true }) { Text("导入") }
            }
        }
        item {
            MarketHint("已添加的 MCP 工具源在「已安装管理 · MCP」中连接 / 断开 / 启停 / 删除。")
        }
    }

    if (showAddDialog) {
        AddMcpDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { config ->
                viewModel.addMcpServer(config)
                showAddDialog = false
            }
        )
    }
    if (showImportDialog) {
        ImportMcpConfigDialog(
            onDismiss = { showImportDialog = false },
            onImport = { json ->
                viewModel.importMcpConfig(json)
                showImportDialog = false
            }
        )
    }
}

// ═══ 市场 · 连接器 ═══

@Composable
internal fun BrowseConnectorsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var showAddDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            MarketHeader("连接器 = 对外部服务（API/SSH/数据库/网盘）的访问配置。启用的连接器出现在 / 菜单（/connector:<id>）。")
        }
        item {
            MarketInstallActionCard(
                title = "添加连接器",
                description = "登记 id / 名称 / 类型 / 端点，保存后即可在对话中以 /connector:<id> 调用。"
            ) {
                TextButton(onClick = { showAddDialog = true }) { Text("添加") }
            }
        }
        item {
            MarketHint("已有连接器的启停与删除在「已安装管理 · 连接器」。")
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
}

// ═══ 市场 · 集成（魔搭 + GitHub）═══

@Composable
internal fun BrowseIntegrationsTab(state: MarketUiState, viewModel: MarketViewModel) {
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
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

/** 安装入口卡 —— 市场·发现视图的统一动作卡形态（右侧为触发按钮）。 */
@Composable
private fun MarketInstallActionCard(
    title: String,
    description: String,
    action: @Composable RowScope.() -> Unit
) {
    MarketCard(
        title = title,
        subtitle = null,
        description = description,
        trailing = action
    )
}
