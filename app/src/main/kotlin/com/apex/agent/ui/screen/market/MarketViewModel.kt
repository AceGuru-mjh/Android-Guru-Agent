package com.apex.agent.ui.screen.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.tools.builtin.SkillInstallTool
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.marketplace.GitHubRepoHit
import com.apex.agent.marketplace.MarketInstallManager
import com.apex.agent.plugin.host.PluginInfo
import com.apex.agent.plugin.host.PluginManager
import com.apex.agent.ui.component.SlashMenuProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 市场导航五个页签 */
enum class MarketTab(val id: String, val label: String) {
    PLUGINS("plugins", "插件"),
    SKILLS("skills", "Skills"),
    MCP("mcp", "MCP"),
    CONNECTORS("connectors", "连接器"),
    INTEGRATIONS("integrations", "集成")
}

data class MarketUiState(
    val selectedTab: MarketTab = MarketTab.PLUGINS,
    // Skills
    val skills: List<SkillRegistry.InstalledSkill> = emptyList(),
    val skillTemplates: List<SkillTemplateItem> = emptyList(),
    // MCP
    val mcps: List<McpServerConfig> = emptyList(),
    val connectedMcps: Set<String> = emptySet(),
    // 连接器
    val connectors: List<ConnectorDef> = emptyList(),
    // 插件
    val plugins: List<PluginInfo> = emptyList(),
    val loadedPlugins: Set<String> = emptySet(),
    // 集成：魔搭
    val modelScopeSkills: List<ModelScopeSource.ModelScopeSkill> = emptyList(),
    val modelScopeLoading: Boolean = false,
    val modelScopeQuery: String = "",
    // 集成：GitHub
    val githubQuery: String = "",
    val githubHits: List<GitHubRepoHit> = emptyList(),
    val githubSearching: Boolean = false,
    // 全局
    val busy: Boolean = false,
    val lastMessage: String? = null
)

data class SkillTemplateItem(
    val id: String,
    val name: String,
    val description: String,
    val installed: Boolean
)

@HiltViewModel
class MarketViewModel @Inject constructor(
    private val skillRegistry: SkillRegistry,
    private val mcpManager: McpManager,
    private val connectorRegistry: ConnectorRegistry,
    private val pluginManager: PluginManager,
    private val installManager: MarketInstallManager,
    private val modelScopeSource: ModelScopeSource,
    private val slashMenuProvider: SlashMenuProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarketUiState())
    val uiState: StateFlow<MarketUiState> = _uiState.asStateFlow()

    init {
        refresh()
        loadModelScope()
    }

    // ═══ 页签 ═══
    fun selectTab(tab: MarketTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    // ═══ 全量刷新 ═══
    fun refresh() {
        val installedIds = skillRegistry.getInstalled().map { it.manifest.id }.toSet()
        val templates = SkillInstallTool.BUILTIN_TEMPLATES_BY_ID
            .map { (id, entry) ->
                SkillTemplateItem(
                    id = id,
                    name = entry.name,
                    description = entry.description,
                    installed = id in installedIds
                )
            }
            .sortedBy { it.installed }
        _uiState.update {
            it.copy(
                skills = skillRegistry.getInstalled(),
                skillTemplates = templates,
                mcps = mcpManager.getConfigs(),
                connectedMcps = mcpManager.getConnectedServers().toSet(),
                connectors = connectorRegistry.getAll(),
                plugins = pluginManager.discoverPlugins(),
                loadedPlugins = pluginManager.loadedPlugins.value.keys
            )
        }
        slashMenuProvider.refresh()
    }

    fun clearMessage() { _uiState.update { it.copy(lastMessage = null) } }

    // ═══ Skills ═══
    fun toggleSkill(skillId: String, enabled: Boolean) {
        skillRegistry.setEnabled(skillId, enabled)
        refresh()
    }

    fun uninstallSkill(skillId: String) {
        skillRegistry.uninstall(skillId)
        refresh()
    }

    fun installTemplate(templateId: String) {
        val result = installManager.installSkillTemplate(templateId)
        _uiState.update { it.copy(lastMessage = result.getOrElse { e -> "❌ ${e.message}" }) }
        refresh()
    }

    fun installSkillJson(content: String) {
        val result = installManager.installSkillFromJson(content)
        _uiState.update { it.copy(lastMessage = result.getOrElse { e -> "❌ ${e.message}" }) }
        refresh()
    }

    fun installSkillUrl(url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val result = installManager.installSkillFromUrl(url)
            _uiState.update {
                it.copy(busy = false, lastMessage = result.getOrElse { e -> "❌ ${e.message}" })
            }
            refresh()
        }
    }

    // ═══ MCP ═══
    fun toggleMcp(name: String, enabled: Boolean) {
        installManager.setMcpEnabled(name, enabled)
        refresh()
    }

    fun connectMcp(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val result = installManager.connectMcpServer(name)
            _uiState.update {
                it.copy(busy = false, lastMessage = result.getOrElse { e -> "❌ ${e.message}" })
            }
            refresh()
        }
    }

    fun addMcpServer(name: String, url: String, transport: String, apiKey: String) {
        if (name.isBlank() || url.isBlank()) {
            _uiState.update { it.copy(lastMessage = "名称与地址不能为空") }
            return
        }
        val config = McpServerConfig(
            name = name.trim(),
            url = url.trim(),
            transport = when (transport) {
                "SSE" -> McpTransport.SSE
                "STDIO" -> McpTransport.STDIO
                else -> McpTransport.HTTP
            },
            apiKey = apiKey.ifBlank { null }
        )
        viewModelScope.launch {
            val result = installManager.addMcpServer(config)
            _uiState.update { it.copy(lastMessage = result.getOrElse { e -> "❌ ${e.message}" }) }
            refresh()
        }
    }

    fun removeMcp(name: String) {
        viewModelScope.launch {
            installManager.removeMcpServer(name)
            refresh()
        }
    }

    // ═══ 连接器 ═══
    fun toggleConnector(id: String, enabled: Boolean) {
        installManager.setConnectorEnabled(id, enabled)
        refresh()
    }

    fun removeConnector(id: String) {
        installManager.removeConnector(id)
        refresh()
    }

    fun addConnector(def: ConnectorDef) {
        val result = installManager.addConnector(def)
        _uiState.update { it.copy(lastMessage = result.getOrElse { e -> "❌ ${e.message}" }) }
        refresh()
    }

    // ═══ 插件 ═══
    fun togglePlugin(packageName: String, load: Boolean) {
        if (load) installManager.loadPlugin(packageName)
        else installManager.unloadPlugin(packageName)
        refresh()
    }

    // ═══ 集成：魔搭 ═══
    fun loadModelScope() {
        viewModelScope.launch {
            _uiState.update { it.copy(modelScopeLoading = true) }
            val result = modelScopeSource.listSkills()
            _uiState.update { s ->
                s.copy(
                    modelScopeLoading = false,
                    modelScopeSkills = result.getOrDefault(emptyList()),
                    lastMessage = result.exceptionOrNull()?.let { "❌ 魔搭加载失败：${it.message}" } ?: s.lastMessage
                )
            }
        }
    }

    fun setModelScopeQuery(query: String) {
        _uiState.update { it.copy(modelScopeQuery = query) }
    }

    fun installModelScopeSkill(skill: ModelScopeSource.ModelScopeSkill) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val result = installManager.installModelScopeSkill(skill)
            _uiState.update {
                it.copy(busy = false, lastMessage = result.getOrElse { e -> "❌ ${e.message}" })
            }
            refresh()
        }
    }

    // ═══ 集成：GitHub ═══
    fun setGithubQuery(query: String) {
        _uiState.update { it.copy(githubQuery = query) }
    }

    fun searchGithub() {
        val query = _uiState.value.githubQuery.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(githubSearching = true) }
            val result = installManager.searchGitHubSkills(query)
            _uiState.update { s ->
                s.copy(
                    githubSearching = false,
                    githubHits = result.getOrDefault(emptyList()),
                    lastMessage = result.exceptionOrNull()?.let { "❌ GitHub 搜索失败：${it.message}" } ?: s.lastMessage
                )
            }
        }
    }

    fun installFromGitHubRepo(fullName: String) {
        val parts = fullName.split('/')
        if (parts.size != 2) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val result = installManager.installSkillFromGitHubRepo(parts[0], parts[1])
            _uiState.update {
                it.copy(busy = false, lastMessage = result.getOrElse { e -> "❌ ${e.message}" })
            }
            refresh()
        }
    }

    // ═══ 已安装状态辅助 ═══
    fun isSkillInstalled(id: String): Boolean =
        skillRegistry.getInstalled().any { it.manifest.id == id }
}
