package com.apex.agent.ui.component

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.graphics.vector.ImageVector
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillMenuProvider
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.plugin.host.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 斜杠指令菜单的统一数据源（成品版 v2）
 *
 * 从 SkillRegistry / McpManager / PluginManager / ConnectorRegistry 实时聚合菜单数据。
 *
 * ## v2 修复
 * - **异步构建**：旧实现在字段初始化器里同步执行 `buildMenu()`（SkillRegistry 懒加载
 *   全目录扫盘 + McpManager 读盘 + discoverPlugins 跨进程 PackageManager 查询），
 *   而本类在首屏进入聊天页时于主线程被 Hilt 构造 → 首帧卡顿/ANR。现在初始值是
 *   空菜单，首次构建放到后台协程。
 * - **变更自动刷新**：旧实现只在插件加载/手动 refresh 时重建——市场页安装技能、
 *   开关 MCP、增删连接器后回到聊天页，菜单仍是旧快照。现在聚合各注册表的
 *   changes 流 + 插件 loadedPlugins，任一变化自动重建（300ms 去抖合并洪峰）。
 * - **插件发现缓存**：discoverPlugins 是跨进程 IPC + loadLabel 资源加载，
 *   旧实现每次 buildMenu 全量执行。现在 10 秒 TTL 缓存。
 * - **MCP enabled 过滤**：禁用的 MCP 服务器不再出现在菜单（旧实现全量展示）。
 * - **连接器真数据源**：从 [ConnectorRegistry] 读取启用的连接器，替代硬编码示例。
 * - **状态结构化**：技能安装状态由 [com.apex.agent.core.tools.skill.SkillMenuItem.installed]
 *   显式携带，替代旧 `label.contains("未安装")` 字符串协议。
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Singleton
class SlashMenuProvider @Inject constructor(
    private val skills: SkillMenuProvider,
    skillRegistry: SkillRegistry,
    private val mcpManager: McpManager,
    private val pluginManager: PluginManager,
    private val connectorRegistry: ConnectorRegistry
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // v2：初始为空菜单，首建移到后台协程（构造在主线程发生，绝不能扫盘/IPC）
    private val _menu = MutableStateFlow(SlashMenuData(emptyList()))
    val menu: StateFlow<SlashMenuData> = _menu.asStateFlow()

    // 插件发现缓存（跨进程 PackageManager IPC + loadLabel 资源加载都很贵）
    @Volatile private var pluginCache: List<com.apex.agent.plugin.host.PluginInfo> = emptyList()
    @Volatile private var pluginCacheAt = 0L

    init {
        // 首次构建：后台执行（旧实现在主线程构造期同步跑）
        scope.launch { rebuild() }

        // 聚合各注册表的变更流：技能安装/开关、MCP 配置/连接状态、连接器增删开关。
        // 任一变化 → 重建菜单；300ms 去抖把洪峰（如批量安装）合并为一次重建。
        combine(
            skillRegistry.changes,
            mcpManager.changes,
            connectorRegistry.changes
        ) { _, _, _ -> Unit }
            .debounce(300)
            .onEach { rebuild() }
            .launchIn(scope)

        // 插件加载/卸载时自动刷新菜单（PluginManager.loadedPlugins 为 StateFlow）
        pluginManager.loadedPlugins
            .onEach {
                pluginCacheAt = 0L // 插件加载状态变化，强制下次重建时重新发现
                rebuild()
            }
            .launchIn(scope)
    }

    /** 在 MCP 连接状态变化后手动触发全量刷新。 */
    fun refresh() {
        scope.launch { rebuild() }
    }

    private suspend fun rebuild() {
        val data = withContext(Dispatchers.Default) { buildMenu() }
        _menu.value = data
    }

    private fun buildMenu(): SlashMenuData {
        return SlashMenuData(
            categories = listOf(
                buildSkillsCategory(),
                buildMcpCategory(),
                buildPluginsCategory(),
                buildConnectorsCategory()
            )
        )
    }

    // ── Skills：复用已有的 SkillMenuProvider，保持「已启用优先、未安装后置」的排序 ──
    private fun buildSkillsCategory(): SlashMenuCategory {
        val active = skills.getActiveSkills()
        val templates = skills.getBuiltinTemplates()
        val items = (active + templates).map { skill ->
            SlashMenuItem(
                label = skill.label,
                command = skill.command,
                description = skill.description,
                status = if (skill.installed) SlashItemStatus.READY else SlashItemStatus.NOT_INSTALLED
            )
        }
        return SlashMenuCategory(
            id = "skills",
            title = "Skills",
            icon = Icons.Default.Extension,
            items = items,
            badge = if (active.isNotEmpty()) "${active.size}" else null,
            hint = if (active.isEmpty()) "暂无已启用 Skill" else null
        )
    }

    // ── MCP：只展示「已启用」的配置，区分「已连接」与「离线」 ──
    private fun buildMcpCategory(): SlashMenuCategory {
        val connected = mcpManager.getConnectedServers().toSet()
        val configs = mcpManager.getEnabledConfigs()
        val items = configs.map { cfg ->
            val isConnected = cfg.name in connected
            SlashMenuItem(
                label = cfg.name,
                command = "/mcp:${cfg.name} ",
                description = cfg.url,
                status = if (isConnected) SlashItemStatus.CONNECTED else SlashItemStatus.OFFLINE
            )
        }
        val connectedCount = items.count { it.status == SlashItemStatus.CONNECTED }
        return SlashMenuCategory(
            id = "mcp",
            title = "MCP 服务器",
            icon = Icons.Default.Api,
            items = items,
            badge = if (connectedCount > 0) "已连接 $connectedCount" else null,
            hint = if (items.isEmpty()) "未配置 MCP 服务器（市场页可添加）" else null
        )
    }

    // ── 插件：从系统已安装的 Apex 插件中实时发现（10s TTL 缓存），区分「已加载」与「未加载」 ──
    private fun buildPluginsCategory(): SlashMenuCategory {
        val discovered = discoverPluginsCached()
        val loaded = pluginManager.loadedPlugins.value.keys
        val items = discovered.map { info ->
            val isLoaded = info.packageName in loaded
            SlashMenuItem(
                label = info.label,
                command = "/plugin:${info.packageName} ",
                description = info.packageName,
                status = if (isLoaded) SlashItemStatus.CONNECTED else SlashItemStatus.NOT_INSTALLED
            )
        }
        return SlashMenuCategory(
            id = "plugins",
            title = "插件",
            icon = Icons.Default.Build,
            items = items,
            hint = if (items.isEmpty()) "未发现已安装的插件" else null
        )
    }

    /** 插件发现缓存：discoverPlugins 是跨进程 IPC，旧实现每次重建菜单都全量查询。 */
    private fun discoverPluginsCached(): List<com.apex.agent.plugin.host.PluginInfo> {
        val now = System.currentTimeMillis()
        val cached = pluginCache
        if (cached.isNotEmpty() && (now - pluginCacheAt) < PLUGIN_CACHE_TTL_MS) {
            return cached
        }
        return try {
            val fresh = pluginManager.discoverPlugins()
            pluginCache = fresh
            pluginCacheAt = now
            fresh
        } catch (e: Exception) {
            Log.w("SlashMenu", "discoverPlugins failed: ${e.message}")
            cached
        }
    }

    // ── 连接器：v2 从 ConnectorRegistry 读取真实数据（含自定义添加），仅展示启用的 ──
    private fun buildConnectorsCategory(): SlashMenuCategory {
        val enabled = connectorRegistry.getEnabled()
        val items = enabled.map { def ->
            SlashMenuItem(
                label = def.name,
                command = "/connector:${def.id} ",
                description = def.type + if (def.endpoint.isNotBlank()) " · ${def.endpoint}" else "",
                status = SlashItemStatus.EXTERNAL
            )
        }
        return SlashMenuCategory(
            id = "connectors",
            title = "连接器",
            icon = Icons.Default.Link,
            items = items,
            hint = if (items.isEmpty()) "无启用连接器（市场页可添加）" else null
        )
    }

    companion object {
        private const val PLUGIN_CACHE_TTL_MS = 10_000L
    }
}

// ═══ 数据模型 ═══

enum class SlashItemStatus {
    READY,        // 可用
    CONNECTED,    // 已连接 / 已加载
    OFFLINE,      // 已配置但当前离线
    NOT_INSTALLED,// 未安装 / 未加载
    EXTERNAL      // 外部服务（连接器）
}

data class SlashMenuItem(
    val label: String,
    val command: String,
    val description: String = "",
    val status: SlashItemStatus = SlashItemStatus.READY
)

data class SlashMenuCategory(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val items: List<SlashMenuItem>,
    val badge: String? = null,
    val hint: String? = null
)

data class SlashMenuData(
    val categories: List<SlashMenuCategory>
)
