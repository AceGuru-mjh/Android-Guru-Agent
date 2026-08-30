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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.builtin.SkillInstallTool
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillMenuProvider
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.plugin.host.PluginManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ═══ 市场行数据（UI 视图模型，避免界面直接依赖各注册表内部类型） ═══

data class MarketToolRow(
    val id: String,
    val name: String,
    val description: String
)

data class MarketSkillRow(
    val id: String,
    val name: String,
    val description: String,
    val installed: Boolean,
    val enabled: Boolean
)

data class MarketMcpRow(
    val name: String,
    val url: String,
    val connected: Boolean
)

data class MarketPluginRow(
    val packageName: String,
    val label: String,
    val loaded: Boolean
)

data class MarketConnectorRow(
    val label: String,
    val command: String
)

@HiltViewModel
class MarketViewModel @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val skillRegistry: SkillRegistry,
    private val mcpManager: McpManager,
    private val pluginManager: PluginManager
) : ViewModel() {

    private val _tools = MutableStateFlow<List<MarketToolRow>>(emptyList())
    val tools: StateFlow<List<MarketToolRow>> = _tools.asStateFlow()

    private val _skills = MutableStateFlow<List<MarketSkillRow>>(emptyList())
    val skills: StateFlow<List<MarketSkillRow>> = _skills.asStateFlow()

    private val _mcp = MutableStateFlow<List<MarketMcpRow>>(emptyList())
    val mcp: StateFlow<List<MarketMcpRow>> = _mcp.asStateFlow()

    private val _plugins = MutableStateFlow<List<MarketPluginRow>>(emptyList())
    val plugins: StateFlow<List<MarketPluginRow>> = _plugins.asStateFlow()

    /** 最近一次操作的结果消息，供 UI 提示。 */
    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    init { refresh() }

    fun refresh() {
        _tools.value = toolRegistry.getAllTools()
            .map { MarketToolRow(id = it.id, name = it.name, description = it.description) }
            .sortedBy { it.id }
        val installedIds = skillRegistry.getInstalled().map { it.manifest.id }.toSet()
        _skills.value =
            skillRegistry.getInstalled().map {
                MarketSkillRow(
                    id = it.manifest.id,
                    name = it.manifest.name,
                    description = it.manifest.description,
                    installed = true,
                    enabled = it.enabled
                )
            } + SkillMenuProvider.BUILTIN_TEMPLATES
                .filter { it.id !in installedIds }
                .map { MarketSkillRow(it.id, it.name, it.description, installed = false, enabled = false) }
        refreshMcp()
        val loaded = pluginManager.loadedPlugins.value.keys
        _plugins.value = pluginManager.discoverPlugins().map {
            MarketPluginRow(
                packageName = it.packageName,
                label = it.label,
                loaded = it.packageName in loaded
            )
        }
    }

    private fun refreshMcp() {
        val connected = mcpManager.getConnectedServers().toSet()
        _mcp.value = mcpManager.getConfigs().map {
            MarketMcpRow(name = it.name, url = it.url, connected = it.name in connected)
        }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        skillRegistry.setEnabled(skillId, enabled)
        refresh()
    }

    /** 安装内置技能模板（与 skill_install 的 template 来源一致）。 */
    fun installBuiltinSkill(templateId: String) {
        val json = when (templateId) {
            "coding_principles" -> SkillInstallTool.CODING_PRINCIPLES_TEMPLATE
            "web_scraper" -> SkillInstallTool.WEB_SCRAPER_TEMPLATE
            "file_organizer" -> SkillInstallTool.FILE_ORGANIZER_TEMPLATE
            "code_runner" -> SkillInstallTool.CODE_RUNNER_TEMPLATE
            "data_analyzer" -> SkillInstallTool.DATA_ANALYZER_TEMPLATE
            else -> null
        }
        if (json == null) {
            _lastMessage.value = "未知模板：$templateId"
            return
        }
        skillRegistry.install(json).fold(
            onSuccess = { m -> _lastMessage.value = "已安装：${m.name}（对话中用 /skill:${m.id} 调用）" },
            onFailure = { e -> _lastMessage.value = "安装失败：${e.message}" }
        )
        refresh()
    }

    fun connectMcp(name: String) {
        viewModelScope.launch {
            mcpManager.connect(name).fold(
                onSuccess = { _lastMessage.value = "MCP 已连接：$name" },
                onFailure = { e -> _lastMessage.value = "连接失败：${e.message}" }
            )
            refreshMcp()
        }
    }

    fun disconnectMcp(name: String) {
        viewModelScope.launch {
            mcpManager.disconnect(name)
            refreshMcp()
        }
    }

    fun clearMessage() { _lastMessage.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(viewModel: MarketViewModel = hiltViewModel()) {
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val mcp by viewModel.mcp.collectAsStateWithLifecycle()
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val lastMessage by viewModel.lastMessage.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(lastMessage) {
        lastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                val tabs = listOf(
                    "内置工具 ${tools.size}",
                    "技能 ${skills.size}",
                    "MCP ${mcp.size}",
                    "插件 ${plugins.size}",
                    "连接器"
                )
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            when (selectedTab) {
                0 -> ToolsTab(tools)
                1 -> SkillsTab(
                    skills = skills,
                    onToggle = viewModel::toggleSkill,
                    onInstall = viewModel::installBuiltinSkill
                )
                2 -> McpTab(
                    servers = mcp,
                    onConnect = viewModel::connectMcp,
                    onDisconnect = viewModel::disconnectMcp
                )
                3 -> PluginsTab(plugins)
                else -> ConnectorsTab()
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ═══ 各页签 ═══

@Composable
private fun ToolsTab(tools: List<MarketToolRow>) {
    MarketList(
        items = tools,
        emptyHint = "工具注册表为空",
        header = {
            item {
                Text(
                    "当前已注册的内置工具。部分工具随能力条件注册（如 GitHub 登录后追加）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { tool ->
        MarketCard(
            title = tool.name,
            subtitle = tool.id,
            description = tool.description
        )
    }
}

@Composable
private fun SkillsTab(
    skills: List<MarketSkillRow>,
    onToggle: (String, Boolean) -> Unit,
    onInstall: (String) -> Unit
) {
    MarketList(
        items = skills,
        emptyHint = "暂无技能",
        header = {
            item {
                Text(
                    "启用后技能指令会注入对话；安装的模板可用 /skill:<id> 调用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { skill ->
        MarketCard(
            title = skill.name,
            subtitle = skill.id,
            description = skill.description,
            trailing = {
                if (skill.installed) {
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { onToggle(skill.id, it) }
                    )
                } else {
                    TextButton(onClick = { onInstall(skill.id) }) {
                        Text("安装")
                    }
                }
            }
        )
    }
}

@Composable
private fun McpTab(
    servers: List<MarketMcpRow>,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit
) {
    MarketList(
        items = servers,
        emptyHint = "未配置 MCP 服务器",
        header = {
            item {
                Text(
                    "已配置的 MCP 服务器。连接后其工具注入对话，可用 /mcp:<名称> 调用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { server ->
        MarketCard(
            title = server.name,
            subtitle = server.url,
            description = null,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MarketStatusChip(
                        text = if (server.connected) "已连接" else "离线",
                        positive = server.connected
                    )
                    TextButton(
                        onClick = {
                            if (server.connected) onDisconnect(server.name) else onConnect(server.name)
                        }
                    ) {
                        Text(if (server.connected) "断开" else "连接")
                    }
                }
            }
        )
    }
}

@Composable
private fun PluginsTab(plugins: List<MarketPluginRow>) {
    MarketList(
        items = plugins,
        emptyHint = "未发现已安装的 Apex 插件",
        header = {
            item {
                Text(
                    "通过 PLUGIN intent 服务发现的 Apex 插件，可用 /plugin:<包名> 调用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { plugin ->
        MarketCard(
            title = plugin.label,
            subtitle = plugin.packageName,
            description = null,
            trailing = {
                MarketStatusChip(
                    text = if (plugin.loaded) "已加载" else "未加载",
                    positive = plugin.loaded
                )
            }
        )
    }
}

@Composable
private fun ConnectorsTab() {
    val connectors = listOf(
        MarketConnectorRow("Google Drive", "/connector:google_drive "),
        MarketConnectorRow("Notion", "/connector:notion "),
        MarketConnectorRow("SSH", "/connector:ssh ")
    )
    MarketList(
        items = connectors,
        emptyHint = "无连接器",
        header = {
            item {
                Text(
                    "示例连接器（与斜杠菜单一致）。正式接入外部服务需实现 Connector 类 Skill。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { connector ->
        MarketCard(
            title = connector.label,
            subtitle = null,
            description = "斜杠命令：${connector.command.trim()}",
            trailing = {
                MarketStatusChip(text = "示例", positive = false)
            }
        )
    }
}

// ═══ 通用件 ═══

@Composable
private fun <T> MarketList(
    items: List<T>,
    emptyHint: String,
    header: (LazyListScope.() -> Unit)? = null,
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
        items(items, itemContent = itemContent)
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
