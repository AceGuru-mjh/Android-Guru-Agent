package com.apex.agent.ui.screen.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.mcp.McpConfigImport
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.skill.SkillMenuProvider
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.marketplace.MarketInstallManager
import com.apex.agent.plugin.host.PluginManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 市场五个页签 */
enum class MarketTab(val label: String) {
    PLUGINS("插件"),
    SKILLS("Skills"),
    MCP("MCP"),
    CONNECTORS("连接器"),
    INTEGRATIONS("集成")
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
    val builtin: Boolean = false
)

data class MarketMcpRow(
    val name: String,
    /** 端点摘要：远端显示 URL，STDIO 显示完整命令行。 */
    val endpoint: String,
    val transport: McpTransport,
    val enabled: Boolean,
    val connected: Boolean
)

data class MarketPluginRow(
    val packageName: String,
    val label: String,
    val loaded: Boolean
)

data class MarketUiState(
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
    private val modelScopeSource: ModelScopeSource
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarketUiState())
    val uiState: StateFlow<MarketUiState> = _uiState.asStateFlow()

    /** 魔搭全量列表（过滤基于全量，避免在已过滤结果上二次过滤后无法还原）。 */
    private var allModelScopeSkills: List<ModelScopeSource.ModelScopeSkill> = emptyList()

    init { refresh() }

    /** 全量刷新（IO 线程）：技能/MCP/连接器/插件快照。保留集成源列表避免安装后列表闪失。 */
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
                busy = state.busy,
                lastMessage = state.lastMessage
            ) }
        }
    }

    private fun snapshotState(): MarketUiState? {
        return runCatching {
            val installed = skillRegistry.getInstalled()
            val installedIds = installed.map { it.manifest.id }.toSet()
            val skills = installed.map {
                MarketSkillRow(
                    id = it.manifest.id,
                    name = it.manifest.name,
                    description = it.manifest.description,
                    installed = true,
                    enabled = it.enabled
                )
            }
            val templates = skillMenuProvider.getBuiltinTemplates().map {
                MarketSkillRow(
                    id = it.id, name = it.label, description = it.description,
                    installed = false, enabled = false, source = "builtin", builtin = true
                )
            }
            val connected = mcpManager.getConnectedServers().toSet()
            val mcps = mcpManager.getConfigs().map {
                MarketMcpRow(
                    name = it.name,
                    endpoint = it.endpointSummary(),
                    transport = it.transport,
                    enabled = it.enabled,
                    connected = it.name in connected
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
            val ok = skillRegistry.uninstall(skillId)
            message(if (ok) "已卸载技能：$skillId" else "未找到技能：$skillId")
            refresh()
        }
    }

    fun installSkillTemplate(templateId: String) {
        viewModelScope.launch {
            installManager.installSkillTemplate(templateId).fold(
                onSuccess = { message(it) },
                onFailure = { message("安装失败：${it.message}") }
            )
            refresh()
        }
    }

    /** 导入自定义 Skill JSON（粘贴内容）。 */
    fun importSkillJson(content: String) {
        viewModelScope.launch {
            installManager.installSkillFromJson(content).fold(
                onSuccess = { message(it) },
                onFailure = { message("导入失败：${it.message}") }
            )
            refresh()
        }
    }

    /** 从 URL 导入 Skill manifest。 */
    fun importSkillFromUrl(url: String) {
        viewModelScope.launch {
            installManager.installSkillFromUrl(url).fold(
                onSuccess = { message(it) },
                onFailure = { message("导入失败：${it.message}") }
            )
            refresh()
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
                    message("已添加 MCP 工具源：$name（连接后其工具注入对话）")
                    mcpManager.connect(name)   // 添加后立即尝试连接
                    refresh()
                },
                onFailure = { message("添加失败：${it.message}") }
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
            if (parsed.configs.isEmpty()) {
                message(
                    "未导入任何条目：" + parsed.errors.joinToString("；") { "${it.serverName}：${it.reason}" }
                        .ifBlank { "配置为空" }
                )
                return@launch
            }
            val added = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (config in parsed.configs) {
                val exists = mcpManager.getConfigs().any { it.name == config.name }
                val unique = if (exists) config.copy(name = "${config.name}-${System.currentTimeMillis()}") else config
                mcpManager.addServer(unique).fold(
                    onSuccess = { added += unique.name },
                    onFailure = { failed += "${unique.name}（${it.message}）" }
                )
            }
            val summary = buildString {
                append("已导入 ${added.size} 个 MCP 工具源")
                if (failed.isNotEmpty()) append("，失败 ${failed.size}：${failed.joinToString("；")}")
                if (parsed.errors.isNotEmpty()) {
                    append("；跳过 ${parsed.errors.size}：")
                    append(parsed.errors.joinToString("；") { "${it.serverName}：${it.reason}" })
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
                    message(if (enabled) "已启用 $name" else "已禁用并断开 $name")
                    refresh()
                },
                onFailure = { message("操作失败：${it.message}") }
            )
        }
    }

    fun connectMcp(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(mcpConnecting = name) }
            try {
                mcpManager.connect(name).fold(
                    onSuccess = { message("MCP 已连接：$name") },
                    onFailure = { message("连接失败：${it.message}") }
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
            message("已断开：$name")
            refresh()
        }
    }

    fun removeMcp(name: String) {
        viewModelScope.launch {
            mcpManager.removeServer(name)
            message("已删除 MCP 服务器：$name")
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
                onSuccess = { message("已添加连接器：$trimmedId（/ 菜单与 /connector:$trimmedId 可用）") },
                onFailure = { message("添加失败：${it.message}") }
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
            message("已删除连接器：$id")
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
            message("插件加载请求已发出：${info.label}")
            refresh()
        }
    }

    fun unloadPlugin(packageName: String) {
        viewModelScope.launch {
            // unbindService 同样需在主线程执行
            withContext(Dispatchers.Main) { pluginManager.unloadPlugin(packageName) }
            message("已卸载插件：$packageName")
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
                onFailure = { message("魔搭安装失败：${it.message}") }
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
                onFailure = { message("安装失败：${it.message}") }
            )
            _uiState.update { it.copy(busy = false) }
            refresh()
        }
    }
}
