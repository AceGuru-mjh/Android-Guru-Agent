package com.apex.agent.ui.component

import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.plugin.host.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 动态斜杠菜单数据提供者。
 *
 * 从 [SkillRegistry] / [McpManager] / [PluginManager] 动态加载已安装的
 * Skill / MCP server / Plugin，生成 [SlashMenuCategoryData] 列表。
 *
 * 取代 [SlashCommandButton] 中原硬编码的 `buildSlashMenuData()` 函数。
 *
 * 推理依据：
 * - 当前 `buildSlashMenuData()` 返回 5 个固定 Skills + 3 个固定 MCP + 3 个固定 Plugin；
 * - 用户实际安装的 Skill/MCP/Plugin 不会出现在菜单中；
 * - 需要类似 IDE 的动态菜单，根据实际安装状态生成。
 */
class SlashMenuProvider(
    private val skillRegistry: SkillRegistry,
    private val mcpManager: McpManager,
    private val pluginManager: PluginManager
) {

    /**
     * 异步构建斜杠菜单数据。
     *
     * Skill / MCP / Plugin 三类的查询都是潜在 I/O 操作（读取磁盘配置、查询已连接 server），
     * 必须在 IO 调度器中执行。
     *
     * @return 4 个分类：Skills / MCP / 连接器 / 插件
     */
    suspend fun buildMenu(): List<SlashMenuCategoryData> = withContext(Dispatchers.IO) {
        val skills = buildSkillsCategory()
        val mcp = buildMcpCategory()
        val connectors = buildConnectorsCategory()
        val plugins = buildPluginsCategory()

        listOf(skills, mcp, connectors, plugins).filter { it.items.isNotEmpty() }
    }

    /**
     * 同步构建（仅在已知无 I/O 时使用）。
     *
     * 注意：[SkillRegistry.getInstalled] / [McpManager.getConnectedServers]
     * 仍是同步的内存查询，但 [PluginManager.discoverPlugins] 会扫描已安装 APK，
     * 可能阻塞 100-500ms。建议优先使用 [buildMenu]。
     */
    fun buildMenuSync(): List<SlashMenuCategoryData> {
        val skills = buildSkillsCategory()
        val mcp = buildMcpCategory()
        val connectors = buildConnectorsCategory()
        val plugins = buildPluginsCategory()
        return listOf(skills, mcp, connectors, plugins).filter { it.items.isNotEmpty() }
    }

    /**
     * Skills 分类：动态从 [SkillRegistry] 加载已安装的 Skill。
     */
    private fun buildSkillsCategory(): SlashMenuCategoryData {
        val installed = skillRegistry.getInstalled()
        val items = if (installed.isEmpty()) {
            // 没有已安装 Skill 时，显示几个内置示例（引导用户安装）
            listOf(
                SlashMenuItemData("代码解释器", "/skill:code_interpreter "),
                SlashMenuItemData("网页搜索", "/skill:web_search "),
                SlashMenuItemData("图表生成", "/skill:chart_generator "),
                SlashMenuItemData("文件整理", "/skill:file_organizer "),
                SlashMenuItemData("数据爬取", "/skill:web_scraper ")
            )
        } else {
            installed.map { skill ->
                SlashMenuItemData(
                    label = skill.manifest.name,
                    command = "/skill:${skill.manifest.id} "
                )
            }
        }

        return SlashMenuCategoryData(
            id = "skills",
            title = "Skills (${installed.size})",
            icon = androidx.compose.material.icons.Icons.Default.Extension,
            items = items
        )
    }

    /**
     * MCP 分类：从 [McpManager] 加载已连接的 MCP server。
     */
    private fun buildMcpCategory(): SlashMenuCategoryData {
        val connectedServers = mcpManager.getConnectedServers()
        val configs = mcpManager.getConfigs()

        val items = if (connectedServers.isEmpty()) {
            // 没有已连接 server 时，显示已配置但未连接的
            if (configs.isEmpty()) {
                listOf(
                    SlashMenuItemData("GitHub MCP", "/mcp:github "),
                    SlashMenuItemData("PostgreSQL MCP", "/mcp:postgres "),
                    SlashMenuItemData("Filesystem MCP", "/mcp:filesystem ")
                )
            } else {
                configs.map { cfg ->
                    SlashMenuItemData(
                        label = "${cfg.name} (${cfg.transport.name.lowercase()})",
                        command = "/mcp:${cfg.name} "
                    )
                }
            }
        } else {
            connectedServers.map { name ->
                SlashMenuItemData(
                    label = name,
                    command = "/mcp:$name "
                )
            }
        }

        return SlashMenuCategoryData(
            id = "mcp",
            title = "MCP (${connectedServers.size} connected)",
            icon = androidx.compose.material.icons.Icons.Default.Api,
            items = items
        )
    }

    /**
     * 连接器分类：当前未实现连接器系统，保留硬编码示例。
     *
     * TODO: 未来可从 [com.apex.agent.connector.ConnectorRegistry] 加载。
     */
    private fun buildConnectorsCategory(): SlashMenuCategoryData {
        return SlashMenuCategoryData(
            id = "connectors",
            title = "连接器",
            icon = androidx.compose.material.icons.Icons.Default.Link,
            items = listOf(
                SlashMenuItemData("Google Drive", "/connector:google_drive "),
                SlashMenuItemData("Notion", "/connector:notion "),
                SlashMenuItemData("SSH", "/connector:ssh ")
            )
        )
    }

    /**
     * 插件分类：从 [PluginManager] 扫描已安装的插件 APK。
     */
    private fun buildPluginsCategory(): SlashMenuCategoryData {
        val plugins = pluginManager.discoverPlugins()
        val items = if (plugins.isEmpty()) {
            listOf(
                SlashMenuItemData("PDF 阅读器", "/plugin:pdf_reader "),
                SlashMenuItemData("实时翻译", "/plugin:translator "),
                SlashMenuItemData("工作流引擎", "/plugin:workflow ")
            )
        } else {
            plugins.map { info ->
                SlashMenuItemData(
                    label = info.label,
                    command = "/plugin:${info.packageName} "
                )
            }
        }

        return SlashMenuCategoryData(
            id = "plugins",
            title = "插件 (${plugins.size})",
            icon = androidx.compose.material.icons.Icons.Default.Puzzle,
            items = items
        )
    }
}

/**
 * 斜杠菜单分类数据。
 *
 * 与 [SlashCommandButton] 中的 [DynamicMenuCategory] 分离，
 * 供 [SlashMenuProvider] 内部使用（扩展版：含 MCP / Plugin 动态加载）。
 */
data class SlashMenuCategoryData(
    val id: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val items: List<SlashMenuItemData>
)

/**
 * 斜杠菜单项数据。
 */
data class SlashMenuItemData(
    val label: String,
    val command: String
)
