package com.apex.agent.ui.screen.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.marketplace.ModelScopeSource
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
    val source: String = ""   // local / modelscope / github
)

data class MarketMcpRow(
    val name: String,
    val url: String,
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
                MarketSkillRow(it.id, it.label, it.description, installed = false, enabled = false)
            }
            val connected = mcpManager.getConnectedServers().toSet()
            val mcps = mcpManager.getConfigs().map {
                MarketMcpRow(it.name, it.url, it.transport, it.enabled, it.name in connected)
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

    fun addMcpServer(name: String, url: String, transport: McpTransport, apiKey: String?) {
        viewModelScope.launch {
            val config = McpServerConfig(
                name = name.trim(),
                url = url.trim(),
                transport = transport,
                apiKey = apiKey?.trim()?.ifBlank { null }
            )
            mcpManager.addServer(config).fold(
                onSuccess = {
                    message("已添加 MCP 服务器：${config.name}（连接后工具注入对话）")
                    mcpManager.connect(config.name)   // 添加后立即尝试连接
                    refresh()
                },
                onFailure = { message("添加失败：${it.message}") }
            )
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
            mcpManager.connect(name).fold(
                onSuccess = { message("MCP 已连接：$name") },
                onFailure = { message("连接失败：${it.message}") }
            )
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
            connectorRegistry.add(
                ConnectorDef(id = id.trim(), name = name.trim(), type = type, endpoint = endpoint.trim())
            ).fold(
                onSuccess = { message(it + "（/ 菜单与 /connector:${id.trim()} 可用）") },
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

    fun installGithubRepo(fullName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val parts = fullName.split("/")
            if (parts.size < 2) {
                message("无效仓库：$fullName")
            } else {
                installManager.installSkillFromGitHubRepo(parts[0], parts[1]).fold(
                    onSuccess = { message(it) },
                    onFailure = { message("安装失败：${it.message}") }
                )
            }
            _uiState.update { it.copy(busy = false) }
            refresh()
        }
    }
}
