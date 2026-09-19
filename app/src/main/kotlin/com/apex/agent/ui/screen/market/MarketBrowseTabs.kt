package com.apex.agent.ui.screen.market

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
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
                MarketHeader("通过 PLUGIN intent 服务发现的 Apex 插件。加载会验证插件 APK 与服务连通；Agent 工具桥接建设中（暂不能通过 /plugin: 执行工具）。")
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BrowseSkillsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var showImportDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showRepoDialog by remember { mutableStateOf(false) }

    // 本地文件导入（.zip / .json 自动识别）—— SAF mime 对 zip/json 上报不可靠，
    // 选择器放行 */*，内容由 MarketInstallManager 按字节魔数与解析结果识别。
    val skillFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importSkillFromFile(it) }
    }

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { skillFilePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("本地导入", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = { showImportDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("粘贴 JSON", style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            text = "本地导入支持 .zip 技能包（含 manifest + 资源文件）与 .json manifest，自动识别。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )

        // ── v2 认知市场：搜索框 + 分类过滤 + 模糊建议 ──
        SkillSearchAndFilter(state, viewModel)

        // 过滤后的技能列表（已安装 + 内置模板，按分类与查询过滤）
        val filtered = rememberFilteredSkills(state, viewModel)

        if (filtered.isEmpty()) {
            MarketEmptyState(hint = "未找到匹配的技能（试试调整分类或搜索关键词）")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    MarketHeader(
                        "内置 = App 自带能力（工具已原生注册，无安装步骤，用 /skill:<id> 直接调用）；" +
                            "已安装技能点击查看认知详情（能量 / 结晶 / 调用统计 / 熔断 / 轨迹）。"
                    )
                }
                items(filtered, key = { it.id }) { skill ->
                    SkillListCard(skill, viewModel)
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

/** 搜索框 + 分类过滤 chips + 模糊建议。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SkillSearchAndFilter(state: MarketUiState, viewModel: MarketViewModel) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = state.skillQuery,
            onValueChange = { viewModel.setSkillQuery(it) },
            placeholder = { Text("搜索技能 / 标签 / 描述（支持模糊匹配）", style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium
        )
        // 模糊建议
        if (state.skillSuggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.size(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "你是不是要找：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.skillSuggestions.take(3).forEach { suggestion ->
                    TextButton(
                        onClick = { viewModel.setSkillQuery(suggestion) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(suggestion, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        // 分类过滤 chips
        Spacer(modifier = Modifier.size(4.dp))
        CategoryFilterChips(state.categoryFilter, viewModel)
    }
}

/** 分类过滤 chip 行。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CategoryFilterChips(
    selected: String?,
    viewModel: MarketViewModel
) {
    val categories = listOf(
        "SHELL" to "Shell",
        "FILE" to "文件",
        "WEB" to "网络",
        "BROWSER" to "浏览器",
        "MEMORY" to "记忆",
        "SYSTEM" to "系统",
        "UI" to "UI",
        "AGENT" to "Agent",
        "UTILITY" to "工具",
        "MCP" to "MCP"
    )
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { viewModel.setCategoryFilter(null) },
            label = { Text("全部", style = MaterialTheme.typography.labelSmall) }
        )
        categories.forEach { (name, label) ->
            FilterChip(
                selected = selected == name,
                onClick = { viewModel.setCategoryFilter(if (selected == name) null else name) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

/** 技能列表卡片 —— 已安装显示能量条/结晶徽章，未安装（内置）显示内置标记。点击已安装技能打开认知详情。 */
@Composable
private fun SkillListCard(skill: MarketSkillRow, viewModel: MarketViewModel) {
    val cardModifier = if (skill.installed) {
        Modifier
            .fillMaxWidth()
            .clickable { viewModel.loadSkillDetail(skill.id) }
    } else {
        Modifier.fillMaxWidth()
    }
    Column(modifier = cardModifier) {
        MarketCard(
            title = skill.name,
            subtitle = if (skill.installed) "${skill.id} · v${skill.version}" else skill.id,
            description = skill.description,
            trailing = {
                if (skill.builtin) {
                    MarketStatusChip(text = "内置", positive = false)
                } else if (skill.isCrystallized) {
                    MarketCrystallizedBadge()
                } else if (skill.isLowEnergy) {
                    MarketLowEnergyBadge()
                }
            }
        )
        // 已安装技能：展示能量条
        if (skill.installed) {
            Spacer(modifier = Modifier.size(4.dp))
            MarketEnergyBar(energy = skill.energy)
        }
    }
}

/** 应用分类 + 搜索过滤（纯内存计算，无 IO）。 */
@Composable
private fun rememberFilteredSkills(state: MarketUiState, viewModel: MarketViewModel): List<MarketSkillRow> {
    return viewModel.filteredSkills()
}

// ═══ 市场 · MCP ═══

@Composable
internal fun BrowseMcpTab(state: MarketUiState, viewModel: MarketViewModel) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    // 本地配置文件导入：选 .json（{"mcpServers": {...}} 社区通用格式）
    val mcpFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importMcpConfigFromFile(it) }
    }

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
            MarketInstallActionCard(
                title = "从本地文件导入",
                description = "选择设备上的 MCP 配置文件（.json，如从桌面端 Claude/Cursor 导出的 mcp.json），解析失败的条目会逐条报出原因。"
            ) {
                TextButton(onClick = { mcpFilePicker.launch(arrayOf("*/*")) }) { Text("选择文件") }
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
