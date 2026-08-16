package com.apex.agent.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.graphics.vector.ImageVector
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillMenuProvider
import com.apex.agent.plugin.host.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 斜杠指令菜单的统一数据源（成品版）
 *
 * 与 PR #30 中被传参却从不消费的 SlashMenuProvider 不同，本实现：
 *  - 从 SkillRegistry / McpManager / PluginManager / ConnectorRegistry 实时聚合菜单数据；
 *  - 通过 [menu] StateFlow 在插件加载/卸载时自动刷新；
 *  - 为每个条目附带状态（已连接 / 离线 / 未安装 / 外部），由 UI 渲染角标；
 *  - [refresh] 可在 MCP 连接状态变化后手动触发全量刷新。
 *
 * 市场开关接线：Skill（SkillRegistry.setEnabled）、MCP（McpManager.setEnabled）、
 * 连接器（ConnectorRegistry.setEnabled）关闭后，对应条目从 "/" 菜单消失。
 *
 * 所有依赖均已在 main 的 DI 图中提供，因此本类无需引入任何新的脚手架代码。
 */
@Singleton
class SlashMenuProvider @Inject constructor(
    private val skills: SkillMenuProvider,
    private val mcpManager: McpManager,
    private val pluginManager: PluginManager,
    private val connectorRegistry: ConnectorRegistry
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _menu = MutableStateFlow(buildMenu())
    val menu: StateFlow<SlashMenuData> = _menu.asStateFlow()

    init {
        // 插件加载/卸载时自动刷新菜单（PluginManager.loadedPlugins 为 StateFlow）
        pluginManager.loadedPlugins
            .onEach { _menu.value = buildMenu() }
            .launchIn(scope)
    }

    /** 在 MCP 连接状态变化后手动触发全量刷新 */
    fun refresh() {
        _menu.value = buildMenu()
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
                status = if (skill.label.contains("未安装")) {
                    SlashItemStatus.NOT_INSTALLED
                } else {
                    SlashItemStatus.READY
                }
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

    // ── MCP：展示已配置服务器（仅启用的），区分「已连接」与「离线」 ──
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
            hint = if (items.isEmpty()) "未配置已启用的 MCP 服务器" else null
        )
    }

    // ── 插件：从系统已安装的 Apex 插件中实时发现，区分「已加载」与「未加载」 ──
    private fun buildPluginsCategory(): SlashMenuCategory {
        val discovered = pluginManager.discoverPlugins()
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

    // ── 连接器：从 ConnectorRegistry 实时读取（仅启用的），替代原硬编码示例 ──
    private fun buildConnectorsCategory(): SlashMenuCategory {
        val items = connectorRegistry.getEnabled().map { def ->
            SlashMenuItem(
                label = def.name,
                command = "/connector:${def.id} ",
                description = if (def.endpoint.isNotBlank()) def.endpoint else def.type,
                status = SlashItemStatus.READY
            )
        }
        return SlashMenuCategory(
            id = "connectors",
            title = "连接器",
            icon = Icons.Default.Link,
            items = items,
            hint = if (items.isEmpty()) "未启用任何连接器" else null
        )
    }
}

// ═══ 数据模型 ═══

enum class SlashItemStatus {
    READY,        // 可用
    CONNECTED,    // 已连接 / 已加载
    OFFLINE,      // 已配置但当前离线
    NOT_INSTALLED,// 未安装 / 未加载
    EXTERNAL      // 外部示例（连接器）
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
