package com.apex.agent.ui.screen.market

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * ═══ 市场 · 发现视图 · MCP 子页签 ═══
 *
 * 从 [MarketBrowseTabs.kt] 按职责缝拆出（God-file 预算纪律）：MCP 页签
 * 是市场里信息密度最高的一页，聚合六个区块——
 * - 官方 MCP 仓库（apex-mcp-hub 目录 + 分级过滤）；
 * - MCP Registry 目录（官方 Registry / PulseMCP 源切换 + 服务端搜索，
 *   见 [MarketRegistrySections.kt]）；
 * - mcp.so 社区长尾目录；
 * - Operit 社区插件（GitHub 聚合，MCP 形态探测安装）；
 * - 当前工位已配置服务器（配置 / 启动 / 停止）+ 逆向 MCP Host + #205
 *   精选目录（[McpCatalogEntryCard]）；
 * - 添加 / 导入 / 本地文件导入三个安装入口。
 *
 * 管理动作（启停 / 卸载）仍收敛在「已安装管理」视图（MarketInstalledTabs）。
 */
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
