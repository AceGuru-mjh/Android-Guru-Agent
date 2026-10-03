package com.apex.agent.ui.screen.market

import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.tools.mcp.McpServerCatalog
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
        // ── 仓库源切换：本地技能 ⇄ 官方仓库 ⇄ ClawHub 技能仓库 ──
        SkillSourceChips(state, viewModel)

        if (state.skillSource == SkillRepoSource.CLAWHUB) {
            ClawHubSection(state, viewModel)
        } else if (state.skillSource == SkillRepoSource.HUB) {
            // 官方技能仓库（apex-skill-hub）：内置瘦身后迁出的技能目录，一键直装
            HubSkillSection(state, viewModel)
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

/**
 * #206 分类过滤 chip 行 —— 从 [SkillCategory] 24 域派生（不再硬编码
 * ToolCategory 名字表：旧列表 10 个 chip 里 6 个对技能永远筛不出任何
 * 结果，属死过滤器）。
 *
 * 只渲染当前列表里**真实有技能**的域（计数 > 0，含旧值残留的「未分类」
 * 兜底 chip）；计数从行数据实时统计，安装/卸载后自动更新。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CategoryFilterChips(
    selected: String?,
    viewModel: MarketViewModel
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    // 行数据里真实出现的分类（已安装 + 内置模板），保留 SkillCategory 顺序，
    // 未知旧值（AGENT/UTILITY 等历史残留）归「未分类」。chip 同时携带
    // 过滤键（字符串，与 categoryFilter 同域）与域对象（标签渲染）。
    val counted = remember(state.skills, state.skillTemplates) {
        buildList {
            val rows = state.skills + state.skillTemplates
            val known = LinkedHashMap<com.apex.agent.core.tools.skill.SkillCategory, Int>()
            var uncategorized = 0
            rows.forEach { row ->
                val cat = com.apex.agent.core.tools.skill.SkillCategory.of(row.category)
                if (cat != null) known[cat] = (known[cat] ?: 0) + 1 else uncategorized++
            }
            com.apex.agent.core.tools.skill.SkillCategory.inDisplayOrder()
                .filter { known.containsKey(it) }
                .forEach { add(it to (known[it] ?: 0)) }
            if (uncategorized > 0) add(null to uncategorized)
        }
    }
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
        counted.forEach { (category, count) ->
            // 过滤键：域 key；未分类 = 空串之外的哨兵（用旧值不可达的 "__uncategorized__"
            // 避免与真实域 key 撞车；categoryFilter 存的也是它）
            val filterKey = category?.key ?: MarketViewModel.UNCATEGORIZED_FILTER
            FilterChip(
                selected = selected == filterKey,
                onClick = { viewModel.setCategoryFilter(if (selected == filterKey) null else filterKey) },
                label = {
                    Text(
                        text = (category?.let { stringResource(skillCategoryLabel(it)) }
                            ?: stringResource(R.string.market_cat_uncategorized)) + " $count",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
    }
}

/** #206 技能域 → 本地化标签资源。 */
@StringRes
private fun skillCategoryLabel(category: com.apex.agent.core.tools.skill.SkillCategory): Int =
    when (category) {
        com.apex.agent.core.tools.skill.SkillCategory.CAREER -> R.string.market_skill_cat_career
        com.apex.agent.core.tools.skill.SkillCategory.KNOWLEDGE -> R.string.market_skill_cat_knowledge
        com.apex.agent.core.tools.skill.SkillCategory.LIFESTYLE -> R.string.market_skill_cat_lifestyle
        com.apex.agent.core.tools.skill.SkillCategory.EMOTIONAL -> R.string.market_skill_cat_emotional
        com.apex.agent.core.tools.skill.SkillCategory.SOCIAL -> R.string.market_skill_cat_social
        com.apex.agent.core.tools.skill.SkillCategory.DATA -> R.string.market_skill_cat_data
        com.apex.agent.core.tools.skill.SkillCategory.BUSINESS -> R.string.market_skill_cat_business
        com.apex.agent.core.tools.skill.SkillCategory.FINANCE -> R.string.market_skill_cat_finance
        com.apex.agent.core.tools.skill.SkillCategory.TECH -> R.string.market_skill_cat_tech
        com.apex.agent.core.tools.skill.SkillCategory.LANGUAGE -> R.string.market_skill_cat_language
        com.apex.agent.core.tools.skill.SkillCategory.HEALTH -> R.string.market_skill_cat_health
        com.apex.agent.core.tools.skill.SkillCategory.FITNESS -> R.string.market_skill_cat_fitness
        com.apex.agent.core.tools.skill.SkillCategory.EDUCATION -> R.string.market_skill_cat_education
        com.apex.agent.core.tools.skill.SkillCategory.PARENTING -> R.string.market_skill_cat_parenting
        com.apex.agent.core.tools.skill.SkillCategory.HOME -> R.string.market_skill_cat_home
        com.apex.agent.core.tools.skill.SkillCategory.TRAVEL -> R.string.market_skill_cat_travel
        com.apex.agent.core.tools.skill.SkillCategory.CREATIVE -> R.string.market_skill_cat_creative
        com.apex.agent.core.tools.skill.SkillCategory.WRITING -> R.string.market_skill_cat_writing
        com.apex.agent.core.tools.skill.SkillCategory.ENTERTAINMENT -> R.string.market_skill_cat_entertainment
        com.apex.agent.core.tools.skill.SkillCategory.PRODUCTIVITY -> R.string.market_skill_cat_productivity
        com.apex.agent.core.tools.skill.SkillCategory.COMMUNICATION -> R.string.market_skill_cat_communication
        com.apex.agent.core.tools.skill.SkillCategory.SAFETY -> R.string.market_skill_cat_safety
        com.apex.agent.core.tools.skill.SkillCategory.DIGITAL -> R.string.market_skill_cat_digital
        com.apex.agent.core.tools.skill.SkillCategory.CODING -> R.string.market_skill_cat_coding
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
                } else if (skill.bundled) {
                    // Issue #166：assets 释放的内置技能——「内置」徽标（复用状态 chip 样式）
                    MarketStatusChip(
                        text = stringResource(R.string.market_skill_bundled_badge),
                        positive = true
                    )
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

    // 官方 MCP 仓库（apex-mcp-hub）：目录 + 当前分级过滤
    val hubState by viewModel.hub.uiState.collectAsStateWithLifecycle()
    val tierScope = state.tier.name.lowercase()
    val hubMcps = hubState.mcps.filter { it.visibleToScope(tierScope) }

    LaunchedEffect(Unit) { viewModel.hub.loadMcpServers() }

    // mcp.so 社区目录（首屏自动加载；分页「加载更多」）
    val mcpSoState by viewModel.mcpSo.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.mcpSo.loadFirst() }

    // MCP Registry 目录（官方 Registry / PulseMCP 源切换 + 服务端搜索）
    val registryState by viewModel.registry.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.registry.loadFirst() }
    var showPulseCredentials by remember { mutableStateOf(false) }

    // Operit 社区插件（GitHub 聚合，MCP 形态探测安装）
    val operitState by viewModel.operit.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.operit.loadFirst() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            MarketHeader(stringResource(R.string.market_mcp_header))
        }
        // ═══ 官方 MCP 仓库（安装 → 配置 → 启动的市场闭环）═══
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                MarketSectionTitle(stringResource(R.string.market_hub_mcp_header))
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { viewModel.hub.loadMcpServers(force = true) },
                    enabled = !hubState.mcpsLoading
                ) {
                    Text(
                        if (hubState.mcpsLoading) stringResource(R.string.market_loading)
                        else stringResource(R.string.market_action_refresh),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        hubState.mcpsError?.let { error ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        onClick = { viewModel.hub.loadMcpServers(force = true) },
                        enabled = !hubState.mcpsLoading
                    ) { Text(stringResource(R.string.market_action_retry)) }
                }
            }
        }
        if (hubState.mcpsLoading && hubMcps.isEmpty()) {
            item { MarketHint(stringResource(R.string.market_hub_mcp_loading)) }
        }
        items(hubMcps, key = { "hub-" + it.name }) { entry ->
            HubMcpCatalogCard(entry, state, hubState, viewModel)
        }
        // ═══ MCP Registry 目录（官方 9000+ / PulseMCP —— 远端直装 + npm 沙箱预装）═══
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                MarketSectionTitle(stringResource(R.string.market_registry_header))
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        item {
            RegistrySourceToggleRow(
                state = registryState,
                onRetry = { viewModel.registry.retry() },
                onSelectSource = { viewModel.registry.selectSource(it) },
                onQueryChange = { viewModel.registry.updateQuery(it) },
                onSearch = { viewModel.registry.search() },
                onBackToBrowse = { viewModel.registry.backToBrowse() },
                onOpenCredentials = { showPulseCredentials = true }
            )
        }
        registryState.error?.let { error ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.market_load_failed_with_reason, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(
                        onClick = { viewModel.registry.retry() },
                        enabled = !registryState.loading
                    ) { Text(stringResource(R.string.market_action_retry)) }
                }
            }
        }
        if (registryState.loading && registryState.servers.isEmpty()) {
            item { MarketHint(stringResource(R.string.market_registry_loading)) }
        }
        items(registryState.servers, key = { it.key }) { entry ->
            RegistryServerCard(
                entry = entry,
                installed = state.mcps.any { it.name == entry.configName },
                installing = registryState.installingKey == entry.key,
                installBusy = registryState.installingKey != null,
                onInstall = { viewModel.registry.installServer(entry) }
            )
        }
        item {
            McpSoLoadMoreRow(
                loadingMore = registryState.loadingMore,
                hasMore = registryState.nextCursor != null,
                enabled = registryState.servers.isNotEmpty(),
                onLoadMore = { viewModel.registry.loadMore() }
            )
        }
        // ═══ mcp.so 社区目录（安装 → 配置 → 启动同一闭环，社区长尾源）═══
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                MarketSectionTitle(stringResource(R.string.market_mcpso_header))
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { viewModel.mcpSo.loadFirst(force = true) },
                    enabled = !mcpSoState.loading
                ) {
                    Text(
                        if (mcpSoState.loading) stringResource(R.string.market_loading)
                        else stringResource(R.string.market_action_refresh),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        mcpSoState.error?.let { error ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        onClick = { viewModel.mcpSo.loadFirst(force = true) },
                        enabled = !mcpSoState.loading
                    ) { Text(stringResource(R.string.market_action_retry)) }
                }
            }
        }
        if (mcpSoState.loading && mcpSoState.servers.isEmpty()) {
            item { MarketHint(stringResource(R.string.market_mcpso_loading)) }
        }
        items(mcpSoState.servers, key = { it.key }) { entry ->
            McpSoCard(
                entry = entry,
                installed = state.mcps.any { it.name == entry.name },
                installing = mcpSoState.installingSlug == entry.slug,
                installBusy = mcpSoState.installingSlug != null,
                onInstall = { viewModel.mcpSo.installServer(entry) }
            )
        }
        item {
            McpSoLoadMoreRow(
                loadingMore = mcpSoState.loadingMore,
                hasMore = mcpSoState.hasMore,
                enabled = mcpSoState.servers.isNotEmpty(),
                onLoadMore = { viewModel.mcpSo.loadMore() }
            )
        }
        // ═══ Operit 社区插件（GitHub 聚合 —— MCP 形态探测安装 / 浏览器打开）═══
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                MarketSectionTitle(stringResource(R.string.market_registry_operit_header))
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { viewModel.operit.loadFirst(force = true) },
                    enabled = !operitState.loading
                ) {
                    Text(
                        if (operitState.loading) stringResource(R.string.market_loading)
                        else stringResource(R.string.market_action_refresh),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        operitState.error?.let { error ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.market_load_failed_with_reason, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(
                        onClick = { viewModel.operit.loadFirst(force = true) },
                        enabled = !operitState.loading
                    ) { Text(stringResource(R.string.market_action_retry)) }
                }
            }
        }
        if (operitState.loading && operitState.plugins.isEmpty()) {
            item { MarketHint(stringResource(R.string.market_registry_operit_loading)) }
        }
        items(operitState.plugins, key = { it.key }) { entry ->
            OperitPluginCard(
                entry = entry,
                installed = state.mcps.any { it.name == entry.configName },
                installing = operitState.installingKey == entry.key,
                installBusy = operitState.installingKey != null,
                onInstall = { viewModel.operit.installPlugin(entry) }
            )
        }
        // ═══ 当前工位已配置服务器（配置 / 启动 / 停止 —— 市场内完成）═══
        if (state.mcps.isNotEmpty()) {
            item {
                MarketSectionTitle(
                    stringResource(R.string.market_mcp_configured_header, state.mcps.size)
                )
            }
            items(state.mcps, key = { "cfg-" + it.name }) { server ->
                ConfiguredMcpCard(server, state, viewModel)
            }
        }
        // #173 逆向 MCP Host：手机作为 MCP Server（外部 AI 接入）——与下方
        //「添加工具源」卡片方向互补（接出 ↔ 接入），同页认知聚合。
        item {
            McpHostSection()
        }
        // #205 精选目录：头部（分类 chips）+ 每条目一个 item（懒加载友好）。
        item {
            McpCatalogHeader(state, viewModel)
        }
        // 注意：LazyListScope 作用域不是 @Composable —— 这里不能用 remember；
        // visibleCatalog 是廉价内存过滤，直接调用即可。
        val catalogEntries = viewModel.visibleCatalog(state)
        items(
            catalogEntries,
            key = { "catalog-${it.id}" }
        ) { entry ->
            McpCatalogEntryCard(
                entry = entry,
                installed = viewModel.isCatalogEntryInstalled(entry),
                onInstall = {
                    if (entry.envSchema.isNotEmpty()) {
                        viewModel.openCatalogEnvDialog(entry)
                    } else {
                        viewModel.installCatalogEntry(entry, emptyMap())
                    }
                }
            )
        }
        if (state.mcpCatalogError != null) {
            item {
                MarketHint(
                    stringResource(R.string.market_mcp_catalog_error, state.mcpCatalogError ?: "")
                )
            }
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
        // Issue #163：沙箱可用性在对话框打开时重查（单次 exists() 系统调用，
        // 开销可忽略）——rootfs 装好后无需重启即生效。判定与
        // ProotMcpProcessLauncher 的门禁同源：`<filesDir>/rootfs/ubuntu/current`。
        val context = LocalContext.current
        val sandboxAvailable = androidx.compose.runtime.remember(showAddDialog) {
            java.io.File(context.filesDir, "rootfs/ubuntu/current").exists()
        }
        AddMcpDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { config ->
                viewModel.addMcpServer(config)
                showAddDialog = false
            },
            sandboxAvailable = sandboxAvailable,
            // #206 实时预检：重名/URL 协议/裸命令/沙箱未装等在表单内当场点名。
            validate = viewModel::validateMcpConfig
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
    // MCP Registry 目录：PulseMCP 合作凭据对话框（保存后立即切源加载）。
    if (showPulseCredentials) {
        PulseCredentialsDialog(
            apiKeyDraft = registryState.pulseApiKeyDraft,
            tenantIdDraft = registryState.pulseTenantIdDraft,
            configured = registryState.pulseCredentialsSet,
            onApiKeyChange = { viewModel.registry.updatePulseApiKeyDraft(it) },
            onTenantIdChange = { viewModel.registry.updatePulseTenantIdDraft(it) },
            onSave = {
                showPulseCredentials = false
                viewModel.registry.savePulseCredentials()
            },
            onClear = { viewModel.registry.clearPulseCredentials() },
            onDismiss = { showPulseCredentials = false }
        )
    }
    // #205 目录条目的环境变量弹窗（密钥引导表单）。
    state.catalogEnvEntry?.let { entry ->
        McpCatalogEnvDialog(
            entry = entry,
            onDismiss = viewModel::closeCatalogEnvDialog,
            onInstall = { values -> viewModel.installCatalogEntry(entry, values) }
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
