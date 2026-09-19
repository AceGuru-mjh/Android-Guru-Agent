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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.tools.marketplace.ClawHubSource

/**
 * ═══ 市场 · 发现视图（BROWSE）═══
 *
 * 顶栏「市场」视图下的五个子页签，只放「发现 / 安装」语义的内容：
 * - 插件：设备上发现的可加载插件 APK（本地发现即市场）；
 * - Skills：仓库源切换（本地：GitHub / URL / JSON / 本地导入入口 + 内置模板；
 *   ClawHub：clawhub.ai 技能仓库的浏览 / 搜索 / 真实下载安装）；
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
        emptyHint = stringResource(R.string.market_plugins_empty_hint),
        header = {
            item {
                MarketHeader(stringResource(R.string.market_plugins_header))
            }
        }
    ) { plugin ->
        MarketCard(
            title = plugin.label,
            subtitle = plugin.packageName,
            description = null,
            trailing = {
                if (plugin.loaded) {
                    MarketStatusChip(text = stringResource(R.string.market_status_loaded), positive = true)
                } else {
                    TextButton(onClick = { viewModel.loadPlugin(plugin) }) {
                        Text(stringResource(R.string.market_action_load))
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
        // ── 仓库源切换：本地技能 ⇄ ClawHub 技能仓库 ──
        SkillSourceChips(state, viewModel)

        if (state.skillSource == SkillRepoSource.CLAWHUB) {
            ClawHubSection(state, viewModel)
        } else {
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
                    Text(stringResource(R.string.market_skills_action_github), style = MaterialTheme.typography.labelMedium)
                }
                ExtendedFloatingActionButton(
                    onClick = { showUrlDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.market_skills_action_url), style = MaterialTheme.typography.labelMedium)
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
                    Text(stringResource(R.string.market_skills_action_local_import), style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.market_skills_action_paste_json), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                text = stringResource(R.string.market_skills_import_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            // ── v2 认知市场：搜索框 + 分类过滤 + 模糊建议 ──
            SkillSearchAndFilter(state, viewModel)

            // 过滤后的技能列表（已安装 + 内置模板，按分类与查询过滤）
            val filtered = rememberFilteredSkills(state, viewModel)

            if (filtered.isEmpty()) {
                MarketEmptyState(hint = stringResource(R.string.market_skills_no_match))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        MarketHeader(stringResource(R.string.market_skills_builtin_header))
                    }
                    items(filtered, key = { it.id }) { skill ->
                        SkillListCard(skill, viewModel)
                    }
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
            title = stringResource(R.string.market_skills_url_dialog_title),
            hint = stringResource(R.string.market_skills_url_dialog_hint),
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

/** 仓库源切换 chips（本地 ⇄ ClawHub）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillSourceChips(state: MarketUiState, viewModel: MarketViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SkillRepoSource.entries.forEach { source ->
            FilterChip(
                selected = state.skillSource == source,
                onClick = { viewModel.selectSkillSource(source) },
                label = {
                    Text(
                        stringResource(source.labelRes),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
    }
}

// ═══ 市场 · Skills：ClawHub 技能仓库 ═══

/**
 * ClawHub（clawhub.ai）仓库区块：搜索 + 热门/搜索结果列表 + 加载更多 + 安装。
 *
 * 空态 / 错误态全中文文案与市场现有风格一致；加载失败可重试；
 * 安装中行级 busy（安装期间禁用其它行的安装按钮防并发下载）。
 */
@Composable
private fun ClawHubSection(state: MarketUiState, viewModel: MarketViewModel) {
    var loadedOnce by rememberSaveable { mutableStateOf(false) }

    // 首次切入自动加载热门列表（已有列表则不重复拉，安装后切回也不闪列表）
    LaunchedEffect(Unit) {
        if (!loadedOnce && state.clawHubSkills.isEmpty() && !state.clawHubLoading) {
            loadedOnce = true
            viewModel.loadClawHubTrending()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索框 + 搜索按钮（复用市场搜索行样式）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.clawHubQuery,
                onValueChange = viewModel::updateClawHubQuery,
                placeholder = {
                    Text(
                        stringResource(R.string.market_clawhub_search_hint),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            TextButton(
                onClick = viewModel::searchClawHub,
                enabled = !state.clawHubQueryLoading && state.clawHubQuery.isNotBlank()
            ) {
                Text(
                    if (state.clawHubQueryLoading) {
                        stringResource(R.string.market_searching)
                    } else {
                        stringResource(R.string.market_action_search)
                    }
                )
            }
        }

        // 搜索结果模式：提示当前查询词 + 返回热门
        if (state.clawHubSearchActive) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.market_clawhub_showing_results, state.clawHubQuery.trim()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = viewModel::loadClawHubTrending,
                    enabled = !state.clawHubQueryLoading
                ) { Text(stringResource(R.string.market_clawhub_back_to_trending)) }
            }
        }

        Text(
            text = stringResource(R.string.market_clawhub_header),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )

        // 错误横幅（可重试；追加加载失败时保留已有列表，横幅叠在其上）
        state.clawHubError?.let { error ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.market_load_failed_with_reason, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = viewModel::retryClawHub,
                    enabled = !state.clawHubLoading && !state.clawHubQueryLoading
                ) { Text(stringResource(R.string.market_action_retry)) }
            }
        }

        when {
            state.clawHubLoading && state.clawHubSkills.isEmpty() -> {
                MarketEmptyState(hint = stringResource(R.string.market_clawhub_loading))
            }
            state.clawHubSkills.isEmpty() && state.clawHubError == null -> {
                MarketEmptyState(
                    hint = if (state.clawHubSearchActive) {
                        stringResource(R.string.market_clawhub_no_results, state.clawHubQuery.trim())
                    } else {
                        stringResource(R.string.market_clawhub_empty_trending)
                    }
                )
            }
            state.clawHubSkills.isNotEmpty() -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.clawHubSkills, key = { it.key }) { entry ->
                        ClawHubSkillCard(entry, state, viewModel)
                    }
                    if (state.clawHubHasMore) {
                        item(key = "clawhub-load-more") {
                            OutlinedButton(
                                onClick = viewModel::loadMoreClawHub,
                                enabled = !state.clawHubLoading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (state.clawHubLoading) {
                                        stringResource(R.string.market_loading)
                                    } else {
                                        stringResource(R.string.market_clawhub_load_more)
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
            // 空列表 + 已有错误横幅：横幅已含重试入口，不再重复空态
        }
    }
}

/** ClawHub 技能行：displayName + owner + 下载数 + summary 两行截断 + 徽章 + 安装按钮。 */
@Composable
private fun ClawHubSkillCard(
    entry: ClawHubSource.ClawHubSkillEntry,
    state: MarketUiState,
    viewModel: MarketViewModel
) {
    val installed = state.skills.any { it.id == entry.installId }
    val installing = state.clawHubInstallingSlug == entry.slug
    // 计数短格式按当前显示语言分支（中文 万/亿，英文 K/M/B）
    val lang = LocalConfiguration.current.locales[0].language
    val downloadsText = stringResource(
        R.string.market_clawhub_downloads,
        formatDownloads(entry.downloads, lang)
    )

    Column {
        MarketCard(
            title = entry.displayName,
            subtitle = buildString {
                append("@${entry.owner}")
                append(" · ").append(downloadsText)
                if (entry.stars > 0) append(" · ★${formatDownloads(entry.stars, lang)}")
            },
            description = entry.summary,
            descriptionMaxLines = 2,
            trailing = {
                if (installed) {
                    MarketStatusChip(text = stringResource(R.string.market_status_installed), positive = true)
                } else {
                    TextButton(
                        onClick = { viewModel.installClawHub(entry) },
                        // 安装期间禁用所有行的安装按钮，防并发下载
                        enabled = state.clawHubInstallingSlug == null
                    ) {
                        Text(
                            if (installing) {
                                stringResource(R.string.market_installing)
                            } else {
                                stringResource(R.string.market_action_install)
                            }
                        )
                    }
                }
            }
        )
        if (entry.featured || entry.official) {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (entry.featured) {
                    MarketStatusChip(
                        text = stringResource(R.string.market_clawhub_featured),
                        positive = false
                    )
                }
                if (entry.official) {
                    MarketStatusChip(
                        text = stringResource(R.string.market_clawhub_official),
                        positive = true
                    )
                }
            }
        }
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
            placeholder = {
                Text(
                    stringResource(R.string.market_skills_search_hint),
                    style = MaterialTheme.typography.labelMedium
                )
            },
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
                    stringResource(R.string.market_skills_suggestions),
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
        "SHELL" to null,
        "FILE" to R.string.market_cat_file,
        "WEB" to R.string.market_cat_web,
        "BROWSER" to R.string.market_cat_browser,
        "MEMORY" to R.string.market_cat_memory,
        "SYSTEM" to R.string.market_cat_system,
        "UI" to null,
        "AGENT" to null,
        "UTILITY" to R.string.market_cat_utility,
        "MCP" to null
    )
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { viewModel.setCategoryFilter(null) },
            label = { Text(stringResource(R.string.market_cat_all), style = MaterialTheme.typography.labelSmall) }
        )
        categories.forEach { (name, labelRes) ->
            FilterChip(
                selected = selected == name,
                onClick = { viewModel.setCategoryFilter(if (selected == name) null else name) },
                label = {
                    Text(
                        labelRes?.let { stringResource(it) } ?: name,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
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
                    MarketStatusChip(text = stringResource(R.string.market_builtin), positive = false)
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
            MarketHeader(stringResource(R.string.market_mcp_header))
        }
        item {
            MarketInstallActionCard(
                title = stringResource(R.string.market_mcp_add_title),
                description = stringResource(R.string.market_mcp_add_desc)
            ) {
                TextButton(onClick = { showAddDialog = true }) {
                    Text(stringResource(R.string.market_action_add))
                }
            }
        }
        item {
            MarketInstallActionCard(
                title = stringResource(R.string.market_mcp_import_card_title),
                description = stringResource(R.string.market_mcp_import_desc)
            ) {
                TextButton(onClick = { showImportDialog = true }) {
                    Text(stringResource(R.string.market_action_import))
                }
            }
        }
        item {
            MarketInstallActionCard(
                title = stringResource(R.string.market_mcp_import_file_title),
                description = stringResource(R.string.market_mcp_import_file_desc)
            ) {
                TextButton(onClick = { mcpFilePicker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.market_mcp_choose_file))
                }
            }
        }
        item {
            MarketHint(stringResource(R.string.market_mcp_manage_hint))
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
            MarketHeader(stringResource(R.string.market_connectors_header))
        }
        item {
            MarketInstallActionCard(
                title = stringResource(R.string.market_connectors_add_title),
                description = stringResource(R.string.market_connectors_add_desc)
            ) {
                TextButton(onClick = { showAddDialog = true }) {
                    Text(stringResource(R.string.market_action_add))
                }
            }
        }
        item {
            MarketHint(stringResource(R.string.market_connectors_manage_hint))
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
            MarketSectionTitle(stringResource(R.string.market_modelscope_title))
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.modelScopeQuery,
                    onValueChange = viewModel::filterModelScope,
                    label = { Text(stringResource(R.string.market_modelscope_filter)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                TextButton(onClick = viewModel::loadModelScopeSkills) {
                    Text(
                        if (state.modelScopeLoading) {
                            stringResource(R.string.market_loading)
                        } else {
                            stringResource(R.string.market_action_refresh)
                        }
                    )
                }
            }
        }
        state.modelScopeError?.let { error ->
            item {
                MarketCard(
                    title = stringResource(R.string.market_modelscope_error_title),
                    subtitle = null,
                    description = error,
                    trailing = null
                )
            }
        }
        if (state.modelScopeLoading && state.modelScopeSkills.isEmpty()) {
            item { MarketHint(stringResource(R.string.market_modelscope_loading)) }
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
                        MarketStatusChip(
                            text = stringResource(R.string.market_status_installed),
                            positive = true
                        )
                    } else {
                        TextButton(
                            onClick = { viewModel.installModelScopeSkill(skill) },
                            enabled = !state.busy
                        ) { Text(stringResource(R.string.market_action_install)) }
                    }
                }
            )
        }

        // ── GitHub 源 ──
        item {
            MarketSectionTitle(stringResource(R.string.market_github_title))
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.githubQuery,
                    onValueChange = viewModel::updateGithubQuery,
                    label = { Text(stringResource(R.string.market_github_query_label)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                TextButton(
                    onClick = viewModel::searchGithub,
                    enabled = !state.githubSearching && state.githubQuery.isNotBlank()
                ) {
                    Text(
                        if (state.githubSearching) {
                            stringResource(R.string.market_searching)
                        } else {
                            stringResource(R.string.market_action_search)
                        }
                    )
                }
            }
        }
        state.githubError?.let { error ->
            item {
                MarketCard(
                    title = stringResource(R.string.market_github_error_title),
                    subtitle = null,
                    description = error,
                    trailing = null
                )
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
                    ) { Text(stringResource(R.string.market_action_install)) }
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
