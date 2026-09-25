package com.apex.agent.ui.screen.market

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.tools.ToolCircuitBreaker
import com.apex.agent.core.tools.ToolTraceRecorder
import com.apex.agent.core.tools.ToolUsageTracker
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.marketplace.ClawHubSource
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.mcp.McpConfigImport
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.skill.SkillMenuProvider
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.marketplace.MarketInstallManager
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import com.apex.agent.plugin.host.PluginManager
import com.apex.agent.ui.language.LanguageManager
import com.apex.agent.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 市场五个页签（两个顶栏视图共享同一套子导航）—— label 为资源 id，composable 侧 stringResource 取词。 */
enum class MarketTab(@StringRes val labelRes: Int) {
    PLUGINS(R.string.market_tab_plugins),
    SKILLS(R.string.market_tab_skills),
    MCP(R.string.market_tab_mcp),
    CONNECTORS(R.string.market_tab_connectors),
    INTEGRATIONS(R.string.market_tab_integrations)
}

/**
 * 市场顶栏视图：
 * - [BROWSE] 市场 —— 发现与安装入口（模板 / GitHub / URL / 魔搭 / 添加表单）；
 * - [INSTALLED] 已安装管理 —— 管理已装/已加载内容（启停 / 卸载 / 连接）。
 *
 * 两个视图下均保留同一套 [MarketTab] 子导航（插件 / Skills / MCP / 连接器 / 集成），
 * 切换视图不重置子页签 —— 用户在「Skills · 市场」看完安装源，切到
 * 「已安装管理」还在 Skills 分类下，上下文不断裂。
 */
enum class MarketScope(@StringRes val labelRes: Int) {
    BROWSE(R.string.market_scope_browse),
    INSTALLED(R.string.market_scope_installed)
}

/**
 * 市场 · Skills 页签的仓库源切换：
 * - [LOCAL] —— 本地技能（已安装 + 内置模板 + 四个本地安装入口）；
 * - [CLAWHUB] —— ClawHub 技能仓库（clawhub.ai：浏览热门 / 搜索 / 真实下载安装）。
 */
enum class SkillRepoSource(@StringRes val labelRes: Int) {
    LOCAL(R.string.market_skill_source_local),
    CLAWHUB(R.string.market_skill_source_clawhub)
}

// ═══ UI 行数据（避免界面直接依赖各注册表内部类型）═══

data class MarketSkillRow(
    val id: String,
    val name: String,
    val description: String,
    val installed: Boolean,
    val enabled: Boolean,
    val source: String = "",   // local / modelscope / github
    /**
     * true = App 内置模板：能力已在二进制里（工具原生注册），不存在"下载/安装"这一步。
     * UI 显示为「内置」标记而不是「安装」按钮 —— 旧实现给它一个安装按钮，
     * 点了只是往本地写一份 JSON，观感是装了，实际什么外部内容都没拉取。
     */
    val builtin: Boolean = false,
    // ── cs-mem 认知健康（v2 增强：让市场"活"起来）──
    /** 能量 [0.01, 10.0]；衰减到 0.05 以下会被梦境折叠。1.0 = 默认/新装。 */
    val energy: Float = 1.0f,
    /** 已结晶为可跳过 LLM 的确定性宏（高频成功技能）。 */
    val isCrystallized: Boolean = false,
    /** 最近一次执行时间戳（ms）；0 = 从未执行。 */
    val lastUsedAt: Long = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    // ── 市场元数据（来自 manifest，用于分类过滤与详情页）──
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val author: String = "",
    val version: String = "",
    val trustLevel: String = "community",
    /**
     * Issue #166：manifest.bundled——APK assets（assets/skills/）首启幂等释放的内置优质技能。
     * 市场卡 / 详情显示「内置」徽标；卸载入口降级（内置可禁用不可卸载，
     * SkillRegistry.uninstall 对 bundled 恒 false）。
     */
    val bundled: Boolean = false
) {
    /** 成功率 0..1。 */
    val successRate: Float get() =
        if (successCount + failureCount == 0) 0f
        else successCount.toFloat() / (successCount + failureCount)

    /** 是否低能量（需要被使用否则会衰减）。 */
    val isLowEnergy: Boolean get() = installed && energy < 0.5f && !isCrystallized
}

data class MarketMcpRow(
    val name: String,
    /** 端点摘要：远端显示 URL，STDIO 显示完整命令行。 */
    val endpoint: String,
    val transport: McpTransport,
    val enabled: Boolean,
    val connected: Boolean,
    /** 内置服务器（BUILTIN 进程内 transport，如内置 GitHub）：不可删除。 */
    val builtin: Boolean = false
)

data class MarketPluginRow(
    val packageName: String,
    val label: String,
    val loaded: Boolean
)

data class MarketUiState(
    val scope: MarketScope = MarketScope.BROWSE,
    val selectedTab: MarketTab = MarketTab.PLUGINS,
    // Skills
    val skills: List<MarketSkillRow> = emptyList(),
    val skillTemplates: List<MarketSkillRow> = emptyList(),
    // MCP
    val mcps: List<MarketMcpRow> = emptyList(),
    // 连接器
    val connectors: List<ConnectorDef> = emptyList(),
    // 插件
    val plugins: List<MarketPluginRow> = emptyList(),
    // 集成：魔搭
    val modelScopeSkills: List<ModelScopeSource.ModelScopeSkill> = emptyList(),
    val modelScopeLoading: Boolean = false,
    val modelScopeQuery: String = "",
    val modelScopeError: String? = null,
    val installedModelScopeIds: Set<String> = emptySet(),
    // 集成：GitHub
    val githubQuery: String = "",
    val githubHits: List<MarketInstallManager.GitHubRepoHit> = emptyList(),
    val githubSearching: Boolean = false,
    val githubError: String? = null,
    // Skills：ClawHub 技能仓库（源切换 + 列表/搜索/安装状态）
    val skillSource: SkillRepoSource = SkillRepoSource.LOCAL,
    val clawHubSkills: List<ClawHubSource.ClawHubSkillEntry> = emptyList(),
    /** 首次/刷新加载中（trending 或翻页）。 */
    val clawHubLoading: Boolean = false,
    val clawHubError: String? = null,
    val clawHubQuery: String = "",
    /** 关键词搜索请求中（与列表加载区分，避免双重 loading）。 */
    val clawHubQueryLoading: Boolean = false,
    /** 正在安装的技能 slug（行级 busy；null = 空闲，非 null 时禁用其它安装按钮防并发）。 */
    val clawHubInstallingSlug: String? = null,
    /** 是否还有下一页（「加载更多」按钮可见性）。 */
    val clawHubHasMore: Boolean = false,
    /** 当前列表是否为搜索结果（控制「返回热门」提示）。 */
    val clawHubSearchActive: Boolean = false,
    // ── v2 认知市场增强 ──
    /** 当前选中分类过滤（null = 全部）。镜像 ToolCategory 枚举名。 */
    val categoryFilter: String? = null,
    /** 本地搜索查询（已安装技能 + 内置模板的模糊匹配，ToolSuggester 驱动）。 */
    val skillQuery: String = "",
    /** 模糊搜索建议（"你是不是要找…"）。 */
    val skillSuggestions: List<String> = emptyList(),
    /** 当前打开的技能详情对话框对应的 skillId。null = 关闭。 */
    val detailSkillId: String? = null,
    /** 详情对话框加载的分析数据。null = 未加载/加载中。 */
    val detailState: SkillDetailUiState? = null,
    /** 详情加载中。 */
    val detailLoading: Boolean = false,
    // 全局
    val busy: Boolean = false,
    /** 正在连接的 MCP 服务器名（null = 无）：连接中禁用对应行按钮，防双击并发重连 */
    val mcpConnecting: String? = null,
    val lastMessage: String? = null
)

/**
 * 市场页 ViewModel（v2 全面重构）
 *
 * 相比旧 MarketViewModel：
 * - **所有注册表操作移出主线程**：旧实现在 Main 线程直接调 install/setEnabled/
 *   discoverPlugins（扫盘/写文件/跨进程 IPC），点一下开关就掉帧。现在全部
 *   `Dispatchers.IO`。
 * - **功能补全**：MCP 添加/删除/启用开关（HTTP/SSE/STDIO）、连接器增删开关
 *   （ConnectorRegistry 持久化）、插件加载/卸载、Skill JSON 导入、
 *   魔搭/GitHub 两个集成源 + URL 导入——对齐并超越 PR45 的市场设计。
 * - **变更推送**：斜杠菜单由各注册表的 changes 流自动刷新（见 SlashMenuProvider v2），
 *   市场操作完成后无需手动通知菜单。
 */
@HiltViewModel
class MarketViewModel @Inject constructor(
    private val skillRegistry: SkillRegistry,
    private val skillMenuProvider: SkillMenuProvider,
    private val mcpManager: McpManager,
    private val connectorRegistry: ConnectorRegistry,
    private val pluginManager: PluginManager,
    private val installManager: MarketInstallManager,
    private val modelScopeSource: ModelScopeSource,
    private val clawHubSource: ClawHubSource,
    // 语言切换：VM 侧消息（snackbar）按当前语言取词
    private val languageManager: LanguageManager,
    // ── v2 认知市场：注入 cs-mem + 工具分析四件套（均为 @Singleton）──
    private val memoryGraphStore: MemoryGraphStore,
    private val usageTracker: ToolUsageTracker,
    private val circuitBreaker: ToolCircuitBreaker,
    private val traceRecorder: ToolTraceRecorder
) : ViewModel() {

    /** 技能分析投影器（聚合 cs-mem + 工具统计 + 熔断 + 轨迹 → 详情 UI 状态）。 */
    private val skillAnalytics = SkillAnalytics(
        memoryGraphStore, usageTracker, circuitBreaker, traceRecorder
    )

    private val _uiState = MutableStateFlow(MarketUiState())
    val uiState: StateFlow<MarketUiState> = _uiState.asStateFlow()

    /** 魔搭全量列表（过滤基于全量，避免在已过滤结果上二次过滤后无法还原）。 */
    private var allModelScopeSkills: List<ModelScopeSource.ModelScopeSkill> = emptyList()

    /** ClawHub 当前页偏移（加载更多时 offset += 一页）。 */
    private var clawHubOffset = 0

    /** ClawHub 当前是否处于搜索结果模式（重试时区分走搜索还是热门）。 */
    private var clawHubSearchMode = false

    /** 当前搜索结果集对应的查询词（翻页/重试用，避免用户改了输入框但未点搜索时错页）。 */
    private var clawHubSearchQuery = ""

    init { refresh() }

    /** 全量刷新（IO 线程）：技能/MCP/连接器/插件快照 + cs-mem 健康数据。保留集成源列表避免安装后列表闪失。 */
    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { snapshotState() } ?: return@launch
            _uiState.update { state -> snapshot.copy(
                selectedTab = state.selectedTab,
                modelScopeQuery = state.modelScopeQuery,
                modelScopeSkills = state.modelScopeSkills,
                modelScopeLoading = state.modelScopeLoading,
                modelScopeError = state.modelScopeError,
                githubQuery = state.githubQuery,
                githubHits = state.githubHits,
                githubSearching = state.githubSearching,
                githubError = state.githubError,
                skillSource = state.skillSource,
                clawHubSkills = state.clawHubSkills,
                clawHubLoading = state.clawHubLoading,
                clawHubError = state.clawHubError,
                clawHubQuery = state.clawHubQuery,
                clawHubQueryLoading = state.clawHubQueryLoading,
                clawHubInstallingSlug = state.clawHubInstallingSlug,
                clawHubHasMore = state.clawHubHasMore,
                clawHubSearchActive = state.clawHubSearchActive,
                categoryFilter = state.categoryFilter,
                skillQuery = state.skillQuery,
                skillSuggestions = state.skillSuggestions,
                detailSkillId = state.detailSkillId,
                detailState = state.detailState,
                detailLoading = state.detailLoading,
                busy = state.busy,
                lastMessage = state.lastMessage
            ) }
        }
    }

    private suspend fun snapshotState(): MarketUiState? {
        return runCatching {
            val installed = skillRegistry.getInstalled()
            val installedIds = installed.map { it.manifest.id }.toSet()

            // cs-mem 健康批量投影：为每个已安装技能取 energy/crystallized/lastUsedAt/success/failure
            val healthMap = skillAnalytics.batchProjectSkillHealth(installedIds.toList())

            val skills = installed.map {
                val h = healthMap[it.manifest.id]
                MarketSkillRow(
                    id = it.manifest.id,
                    name = it.manifest.name,
                    description = it.manifest.description,
                    installed = true,
                    enabled = it.enabled,
                    energy = h?.energy ?: 1.0f,
                    isCrystallized = h?.isCrystallized ?: false,
                    lastUsedAt = h?.lastUsedAt ?: 0,
                    successCount = h?.successCount ?: 0,
                    failureCount = h?.failureCount ?: 0,
                    category = it.manifest.category,
                    tags = it.manifest.tags,
                    author = it.manifest.author,
                    version = it.manifest.version,
                    trustLevel = it.manifest.trustLevel,
                    bundled = it.manifest.bundled
                )
            }
            val templates = skillMenuProvider.getBuiltinTemplates().map { t ->
                val tpl = SkillMenuProvider.BUILTIN_TEMPLATES.firstOrNull { it.id == t.id }
                MarketSkillRow(
                    id = t.id, name = t.label, description = t.description,
                    installed = false, enabled = false, source = "builtin", builtin = true,
                    category = tpl?.category,
                    tags = tpl?.tags.orEmpty()
                )
            }
            val connected = mcpManager.getConnectedServers().toSet()
            val mcps = mcpManager.getConfigs().map {
                MarketMcpRow(
                    name = it.name,
                    endpoint = it.endpointSummary(),
                    transport = it.transport,
                    enabled = it.enabled,
                    connected = it.name in connected,
                    builtin = it.transport == McpTransport.BUILTIN
                )
            }
            val connectors = connectorRegistry.getAll()
            val loaded = pluginManager.loadedPlugins.value.keys
            val plugins = pluginManager.discoverPlugins().map {
                MarketPluginRow(it.packageName, it.label, it.packageName in loaded)
            }
            MarketUiState(
                skills = skills,
                skillTemplates = templates,
                mcps = mcps,
                connectors = connectors,
                plugins = plugins,
                installedModelScopeIds = installedIds
            )
        }.getOrNull()
    }

    fun selectTab(tab: MarketTab) = _uiState.update { it.copy(selectedTab = tab) }

    /** 切换顶栏视图（市场 ⇄ 已安装管理），保留当前子页签。 */
    fun selectScope(scope: MarketScope) = _uiState.update { it.copy(scope = scope) }

    fun clearMessage() = _uiState.update { it.copy(lastMessage = null) }

    private fun message(msg: String) = _uiState.update { it.copy(lastMessage = msg) }

    // ═══ Skills ═══

    fun toggleSkill(skillId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            skillRegistry.setEnabled(skillId, enabled)
            refresh()
        }
    }

    fun uninstallSkill(skillId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Issue #166：内置技能不可卸载（SkillRegistry.uninstall 对 bundled 恒 false），
            // 提前拦下并给出正确提示——否则会落进「未找到技能」的误导文案。
            if (_uiState.value.skills.any { it.id == skillId && it.bundled }) {
                message(languageManager.getString(R.string.market_skill_bundled_uninstall_blocked_hint))
                return@launch
            }
            val ok = skillRegistry.uninstall(skillId)
            message(
                if (ok) {
                    languageManager.getString(R.string.market_skill_uninstalled).format(skillId)
                } else {
                    languageManager.getString(R.string.market_skill_not_found).format(skillId)
                }
            )
            refresh()
        }
    }

    fun installSkillTemplate(templateId: String) {
        viewModelScope.launch {
            installManager.installSkillTemplate(templateId).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(languageManager.getString(R.string.market_install_failed).format(it.message ?: ""))
                }
            )
            refresh()
        }
    }

    /** 导入自定义 Skill JSON（粘贴内容）。 */
    fun importSkillJson(content: String) {
        viewModelScope.launch {
            installManager.installSkillFromJson(content).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(languageManager.getString(R.string.market_import_failed).format(it.message ?: ""))
                }
            )
            refresh()
        }
    }

    /** 从 URL 导入 Skill manifest。 */
    fun importSkillFromUrl(url: String) {
        viewModelScope.launch {
            installManager.installSkillFromUrl(url).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(languageManager.getString(R.string.market_import_failed).format(it.message ?: ""))
                }
            )
            refresh()
        }
    }

    /**
     * 从本地文件导入 Skill（.zip / .json 自动识别）。
     *
     * 用户反馈"Skills 不能自己导入"：本地 skill 包此前没有任何入口。
     * zip 走 SafeZipExtractor（路径穿越 / zip bomb 防御），json 直接装 manifest。
     */
    fun importSkillFromFile(uri: android.net.Uri) {
        viewModelScope.launch {
            installManager.installSkillFromFile(uri).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(it.message ?: languageManager.getString(R.string.market_local_import_failed))
                }
            )
            refresh()
        }
    }

    /**
     * 从本地文件导入 MCP 配置（`{"mcpServers": {...}}` 形态的 .json）。
     * 复用 [importMcpConfig] 的解析与逐条报错管道。
     */
    fun importMcpConfigFromFile(uri: android.net.Uri) {
        viewModelScope.launch {
            installManager.readTextFile(uri).fold(
                onSuccess = { text -> importMcpConfig(text) },
                onFailure = {
                    message(
                        languageManager.getString(R.string.market_read_file_failed)
                            .format(it.message ?: "")
                    )
                }
            )
        }
    }

    // ═══ MCP ═══

    /**
     * 添加 MCP 工具源。
     *
     * 注意 STDIO 不是"服务器 URL"：它是本地命令（command + args + env），
     * 添加成功后 connect 会真正把这个进程拉起来做 JSON-RPC 握手。
     */
    fun addMcpServer(config: McpServerConfig) {
        viewModelScope.launch {
            val name = config.name.trim()
            mcpManager.addServer(config.copy(name = name)).fold(
                onSuccess = {
                    message(languageManager.getString(R.string.market_mcp_added).format(name))
                    mcpManager.connect(name)   // 添加后立即尝试连接
                    refresh()
                },
                onFailure = {
                    message(languageManager.getString(R.string.market_add_failed).format(it.message ?: ""))
                }
            )
        }
    }

    /**
     * 导入社区通用 MCP 配置 JSON（`{"mcpServers": {...}}`）。
     *
     * 支持 `command/args/env`（STDIO 本地命令）与 `type=streamable_http|sse` + `url`
     * 两种形态；解析不通过的条目会**逐条报出原因**，不会静默丢配置。
     */
    fun importMcpConfig(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = McpConfigImport.parse(text)
            // 分隔符 / “名称：原因”模板按语言取（en 用 "; " 与 ": "）
            val sep = languageManager.getString(R.string.market_list_sep)
            val nameReason = { serverName: String, reason: String ->
                languageManager.getString(R.string.market_name_reason).format(serverName, reason)
            }
            if (parsed.configs.isEmpty()) {
                val detail = parsed.errors.joinToString(sep) { nameReason(it.serverName, it.reason) }
                    .ifBlank { languageManager.getString(R.string.market_mcp_import_empty) }
                message(languageManager.getString(R.string.market_mcp_import_none).format(detail))
                return@launch
            }
            val added = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (config in parsed.configs) {
                val exists = mcpManager.getConfigs().any { it.name == config.name }
                val unique = if (exists) config.copy(name = "${config.name}-${System.currentTimeMillis()}") else config
                mcpManager.addServer(unique).fold(
                    onSuccess = { added += unique.name },
                    onFailure = { failed += nameReason(unique.name, it.message ?: "") }
                )
            }
            val summary = buildString {
                append(languageManager.getString(R.string.market_mcp_imported_count).format(added.size))
                if (failed.isNotEmpty()) {
                    append(
                        languageManager.getString(R.string.market_mcp_import_failed_part)
                            .format(failed.size, failed.joinToString(sep))
                    )
                }
                if (parsed.errors.isNotEmpty()) {
                    append(
                        languageManager.getString(R.string.market_mcp_import_skipped_part).format(
                            parsed.errors.size,
                            parsed.errors.joinToString(sep) { nameReason(it.serverName, it.reason) }
                        )
                    )
                }
            }
            message(summary)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun toggleMcp(name: String, enabled: Boolean) {
        viewModelScope.launch {
            mcpManager.setEnabled(name, enabled).fold(
                onSuccess = {
                    message(
                        if (enabled) {
                            languageManager.getString(R.string.market_mcp_enabled).format(name)
                        } else {
                            languageManager.getString(R.string.market_mcp_disabled_disconnected).format(name)
                        }
                    )
                    refresh()
                },
                onFailure = {
                    message(languageManager.getString(R.string.market_action_failed).format(it.message ?: ""))
                }
            )
        }
    }

    fun connectMcp(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(mcpConnecting = name) }
            try {
                mcpManager.connect(name).fold(
                    onSuccess = {
                        message(languageManager.getString(R.string.market_mcp_connected).format(name))
                    },
                    onFailure = {
                        message(languageManager.getString(R.string.market_connect_failed).format(it.message ?: ""))
                    }
                )
            } finally {
                _uiState.update { it.copy(mcpConnecting = null) }
            }
            refresh()
        }
    }

    fun disconnectMcp(name: String) {
        viewModelScope.launch {
            mcpManager.disconnect(name)
            message(languageManager.getString(R.string.market_mcp_disconnected).format(name))
            refresh()
        }
    }

    fun removeMcp(name: String) {
        viewModelScope.launch {
            mcpManager.removeServer(name)
            message(languageManager.getString(R.string.market_mcp_removed).format(name))
            refresh()
        }
    }

    // ═══ 连接器 ═══

    fun addConnector(id: String, name: String, type: String, endpoint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmedId = id.trim()
            connectorRegistry.add(
                ConnectorDef(id = trimmedId, name = name.trim(), type = type, endpoint = endpoint.trim())
            ).fold(
                onSuccess = {
                    message(languageManager.getString(R.string.market_connector_added).format(trimmedId))
                },
                onFailure = {
                    message(languageManager.getString(R.string.market_add_failed).format(it.message ?: ""))
                }
            )
            refresh()
        }
    }

    fun toggleConnector(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            connectorRegistry.setEnabled(id, enabled)
            refresh()
        }
    }

    fun removeConnector(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            connectorRegistry.remove(id)
            message(languageManager.getString(R.string.market_connector_removed).format(id))
            refresh()
        }
    }

    // ═══ 插件 ═══

    fun loadPlugin(row: MarketPluginRow) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                pluginManager.discoverPlugins().firstOrNull { it.packageName == row.packageName }
            } ?: return@launch
            // bindService 需在 Looper 线程调用（Context 契约），回调本身回主线程
            withContext(Dispatchers.Main) { pluginManager.loadPlugin(info) }
            message(languageManager.getString(R.string.market_plugin_load_requested).format(info.label))
            refresh()
        }
    }

    fun unloadPlugin(packageName: String) {
        viewModelScope.launch {
            // unbindService 同样需在主线程执行
            withContext(Dispatchers.Main) { pluginManager.unloadPlugin(packageName) }
            message(languageManager.getString(R.string.market_plugin_unloaded).format(packageName))
            refresh()
        }
    }

    // ═══ 集成：魔搭 ═══

    fun updateModelScopeQuery(query: String) =
        _uiState.update { it.copy(modelScopeQuery = query) }

    fun loadModelScopeSkills() {
        viewModelScope.launch {
            _uiState.update { it.copy(modelScopeLoading = true, modelScopeError = null) }
            modelScopeList()
            _uiState.update { it.copy(modelScopeLoading = false) }
        }
    }

    private suspend fun modelScopeList() {
        modelScopeSource.listSkills().fold(
            onSuccess = { skills ->
                allModelScopeSkills = skills
                val filtered = modelScopeSource.filterSkills(skills, _uiState.value.modelScopeQuery)
                _uiState.update { it.copy(modelScopeSkills = filtered) }
            },
            onFailure = { e ->
                allModelScopeSkills = emptyList()
                _uiState.update { it.copy(modelScopeSkills = emptyList(), modelScopeError = e.message) }
            }
        )
    }

    fun filterModelScope(query: String) {
        _uiState.update { it.copy(modelScopeQuery = query) }
        val filtered = modelScopeSource.filterSkills(allModelScopeSkills, query)
        _uiState.update { it.copy(modelScopeSkills = filtered) }
    }

    fun installModelScopeSkill(skill: ModelScopeSource.ModelScopeSkill) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            installManager.installModelScopeSkill(skill).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(
                        languageManager.getString(R.string.market_modelscope_install_failed)
                            .format(it.message ?: "")
                    )
                }
            )
            _uiState.update { it.copy(busy = false) }
            refresh()
        }
    }

    // ═══ 集成：GitHub ═══

    fun updateGithubQuery(query: String) = _uiState.update { it.copy(githubQuery = query) }

    fun searchGithub() {
        viewModelScope.launch {
            val query = _uiState.value.githubQuery.trim()
            if (query.isBlank()) return@launch
            _uiState.update { it.copy(githubSearching = true, githubError = null) }
            installManager.searchGitHubSkills(query).fold(
                onSuccess = { hits -> _uiState.update { it.copy(githubHits = hits) } },
                onFailure = { e -> _uiState.update { it.copy(githubHits = emptyList(), githubError = e.message) } }
            )
            _uiState.update { it.copy(githubSearching = false) }
        }
    }

    fun installGithubRepo(fullName: String) = installFromRepoInput(fullName)

    /**
     * 真实联网安装：接受 `owner/repo`、GitHub 链接、git@ 链接任一种写法。
     * 走 raw.githubusercontent.com 取 manifest / SKILL.md —— 不再"点一下就假装装好了"。
     */
    fun installFromRepoInput(input: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            installManager.installSkillFromRepoInput(input).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(languageManager.getString(R.string.market_install_failed).format(it.message ?: ""))
                }
            )
            _uiState.update { it.copy(busy = false) }
            refresh()
        }
    }

    // ═══ Skills 页签：ClawHub 技能仓库 ═══

    /** 切换 Skills 页签的仓库源（本地 ⇄ ClawHub）。 */
    fun selectSkillSource(source: SkillRepoSource) =
        _uiState.update { it.copy(skillSource = source) }

    fun updateClawHubQuery(query: String) =
        _uiState.update { it.copy(clawHubQuery = query) }

    /** 加载 ClawHub 热门列表（第一页；也用作「返回热门」）。 */
    fun loadClawHubTrending() {
        viewModelScope.launch {
            _uiState.update { it.copy(clawHubLoading = true, clawHubError = null) }
            clawHubSearchMode = false
            clawHubOffset = 0
            clawHubSource.listTrending(limit = ClawHubSource.TRENDING_PAGE_SIZE, offset = 0).fold(
                onSuccess = { page ->
                    clawHubOffset = ClawHubSource.TRENDING_PAGE_SIZE
                    _uiState.update {
                        it.copy(
                            clawHubSkills = page.entries,
                            clawHubHasMore = page.hasMore,
                            clawHubSearchActive = false
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(clawHubSkills = emptyList(), clawHubError = e.message) }
                }
            )
            _uiState.update { it.copy(clawHubLoading = false) }
        }
    }

    /** 关键词搜索（空关键词退回热门列表，与搜索端点「必须有关键词」的行为对齐）。 */
    fun searchClawHub() {
        val query = _uiState.value.clawHubQuery.trim()
        if (query.isEmpty()) {
            loadClawHubTrending()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(clawHubQueryLoading = true, clawHubError = null) }
            clawHubSearchMode = true
            clawHubSearchQuery = query
            clawHubOffset = 0
            clawHubSource.search(query, limit = ClawHubSource.DEFAULT_PAGE_SIZE, offset = 0).fold(
                onSuccess = { page ->
                    clawHubOffset = ClawHubSource.DEFAULT_PAGE_SIZE
                    _uiState.update {
                        it.copy(
                            clawHubSkills = page.entries,
                            clawHubHasMore = page.hasMore,
                            clawHubSearchActive = true
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(clawHubSkills = emptyList(), clawHubError = e.message) }
                }
            )
            _uiState.update { it.copy(clawHubQueryLoading = false) }
        }
    }

    /**
     * 加载下一页（追加并按 owner/slug 去重；热门与搜索模式共用）。
     *
     * 服务端 offset 翻页实测暂不生效（返回重复页）：去重后 0 新条目时
     * 自动收起「加载更多」，避免无限空点；若服务端将来修复 offset，
     * 本逻辑无需改动即自动恢复真分页。
     */
    fun loadMoreClawHub() {
        val current = _uiState.value
        if (current.clawHubLoading || current.clawHubQueryLoading || !current.clawHubHasMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(clawHubLoading = true) }
            val pageSize = if (clawHubSearchMode) {
                ClawHubSource.DEFAULT_PAGE_SIZE
            } else {
                ClawHubSource.TRENDING_PAGE_SIZE
            }
            val result = if (clawHubSearchMode) {
                clawHubSource.search(
                    clawHubSearchQuery,
                    limit = pageSize,
                    offset = clawHubOffset
                )
            } else {
                clawHubSource.listTrending(
                    limit = pageSize,
                    offset = clawHubOffset
                )
            }
            result.fold(
                onSuccess = { page ->
                    clawHubOffset += pageSize
                    _uiState.update { state ->
                        val seen = state.clawHubSkills.map { it.key }.toHashSet()
                        val fresh = page.entries.filter { it.key !in seen }
                        state.copy(
                            clawHubSkills = state.clawHubSkills + fresh,
                            // 全重复页（服务端 offset 未生效）→ 收起加载更多
                            clawHubHasMore = page.hasMore && fresh.isNotEmpty()
                        )
                    }
                },
                onFailure = { e ->
                    // 追加失败：保留已有列表，仅提示错误，不动页码
                    _uiState.update { it.copy(clawHubError = e.message) }
                }
            )
            _uiState.update { it.copy(clawHubLoading = false) }
        }
    }

    /** 失败重试（按当前模式走热门或搜索）。 */
    fun retryClawHub() {
        if (clawHubSearchMode) searchClawHub() else loadClawHubTrending()
    }

    /** 从 ClawHub 安装（下载 ZIP → 转 prompt 型技能 → SkillRegistry）。 */
    fun installClawHub(entry: ClawHubSource.ClawHubSkillEntry) {
        viewModelScope.launch {
            _uiState.update { it.copy(clawHubInstallingSlug = entry.slug) }
            try {
                installManager.installClawHubSkill(entry).fold(
                    onSuccess = { message(it) },
                    onFailure = {
                        message(
                            languageManager.getString(R.string.market_clawhub_install_failed)
                                .format(it.message ?: "")
                        )
                    }
                )
            } finally {
                // 无论成败都复位行级 busy 并刷新已安装列表（「已安装」Chip 依据 skills 快照）
                _uiState.update { it.copy(clawHubInstallingSlug = null) }
                refresh()
            }
        }
    }

    // ═══ 认知市场：详情 + 分类过滤 + 模糊搜索 ═══

    /**
     * 打开技能详情对话框：异步从 cs-mem + ToolUsageTracker + CircuitBreaker + TraceRecorder
     * 投影出 [SkillDetailUiState]（能源/结晶/调用统计/熔断/轨迹）。
     */
    fun loadSkillDetail(skillId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(detailSkillId = skillId, detailLoading = true, detailState = null) }
            val manifest = withContext(Dispatchers.IO) {
                skillRegistry.getInstalled().firstOrNull { it.manifest.id == skillId }?.manifest
            }
            val detail = skillAnalytics.projectSkillAnalytics(skillId, manifest)
            _uiState.update { it.copy(detailState = detail, detailLoading = false) }
        }
    }

    /** 关闭技能详情对话框。 */
    fun closeSkillDetail() {
        _uiState.update { it.copy(detailSkillId = null, detailState = null, detailLoading = false) }
    }

    /** 设置分类过滤（null = 全部分类）。 */
    fun setCategoryFilter(category: String?) {
        _uiState.update { it.copy(categoryFilter = category) }
    }

    /**
     * 本地技能搜索：对已安装 + 内置模板做 ToolSuggester 模糊匹配。
     * 输入空时清空建议并显示全部。
     */
    fun setSkillQuery(query: String) {
        val trimmed = query.trim()
        _uiState.update { it.copy(skillQuery = trimmed) }
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(skillSuggestions = emptyList()) }
            return
        }
        viewModelScope.launch {
            // 候选 id = 已安装 + 内置模板
            val candidates = withContext(Dispatchers.IO) {
                val installed = skillRegistry.getInstalled().map { it.manifest.id }
                val templates = SkillMenuProvider.BUILTIN_TEMPLATES.map { it.id }
                (installed + templates).distinct()
            }
            val suggestions = com.apex.agent.core.tools.ToolSuggester.suggest(
                trimmed, candidates, maxSuggestions = 5
            )
            _uiState.update { it.copy(skillSuggestions = suggestions) }
        }
    }

    /**
     * 对当前已安装技能 + 内置模板应用分类过滤 + 搜索查询。
     * 返回过滤后的技能列表（UI 渲染用）。
     */
    fun filteredSkills(): List<MarketSkillRow> {
        val state = _uiState.value
        val all = state.skills + state.skillTemplates
        return all.filter { row ->
            // 分类过滤
            (state.categoryFilter == null || row.category == state.categoryFilter) &&
            // 搜索查询：精确匹配 id/name/tags/description
            (state.skillQuery.isBlank() ||
                row.id.contains(state.skillQuery, ignoreCase = true) ||
                row.name.contains(state.skillQuery, ignoreCase = true) ||
                row.description.contains(state.skillQuery, ignoreCase = true) ||
                row.tags.any { it.contains(state.skillQuery, ignoreCase = true) })
        }
    }
}
