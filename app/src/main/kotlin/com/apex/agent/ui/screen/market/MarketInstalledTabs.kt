package com.apex.agent.ui.screen.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.mcp.McpTransport

/**
 * ═══ 市场 · 已安装管理视图（INSTALLED）═══
 *
 * 顶栏「已安装管理」视图下的五个子页签，只放「管理」语义的内容：
 * - 插件：已加载插件的卸载（解除服务绑定）；
 * - Skills：已安装技能的启停 / 卸载；
 * - MCP：已配置工具源的连接 / 断开 / 启停 / 删除；
 * - 连接器：已有连接器的启停 / 删除；
 * - 集成：从魔搭（ms-*）/ GitHub（gh-*）安装的技能管理。
 *
 * 所有空态提供「去市场安装」一键跳回发现视图 —— 两个视图互为闭环。
 */

/** 空态统一动作 —— 跳回「市场」发现视图（保留当前子页签）。 */
private val goToBrowse: (MarketViewModel) -> Unit = { it.selectScope(MarketScope.BROWSE) }

// ═══ 已安装管理 · 插件 ═══

@Composable
internal fun InstalledPluginsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingUnload by remember { mutableStateOf<MarketPluginRow?>(null) }
    val loaded = state.plugins.filter { it.loaded }

    if (loaded.isEmpty()) {
        MarketEmptyState(
            hint = "尚未加载任何插件 —— 到「市场」加载设备上发现的插件 APK",
            actionLabel = "去市场加载",
            onAction = { goToBrowse(viewModel) }
        )
    } else {
        MarketList(
            items = loaded,
            key = { it.packageName },
            header = {
                item {
                    MarketHeader("已加载 ${loaded.size} 个插件；卸载仅解除服务绑定，APK 仍在设备上，可随时重新加载。")
                }
            }
        ) { plugin ->
            MarketCard(
                title = plugin.label,
                subtitle = plugin.packageName,
                description = null,
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MarketStatusChip(text = "已加载", positive = true)
                        TextButton(onClick = { pendingUnload = plugin }) {
                            Text("卸载")
                        }
                    }
                }
            )
        }
    }

    pendingUnload?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingUnload = null },
            title = { Text("卸载插件") },
            text = { Text("解除与 ${plugin.label} 的绑定？可随时重新加载。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unloadPlugin(plugin.packageName)
                    pendingUnload = null
                }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnload = null }) { Text("取消") }
            }
        )
    }
}

// ═══ 已安装管理 · Skills ═══

@Composable
internal fun InstalledSkillsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingUninstall by remember { mutableStateOf<MarketSkillRow?>(null) }

    if (state.skills.isEmpty()) {
        MarketEmptyState(
            hint = "暂无已安装技能 —— 到「市场」从 GitHub / URL / 魔搭安装",
            actionLabel = "去市场安装",
            onAction = { goToBrowse(viewModel) }
        )
        return
    }

    MarketList(
        items = state.skills,
        emptyHint = "暂无已安装技能",
        key = { it.id },
        header = {
            item {
                MarketHeader(
                    "已安装 ${state.skills.size} 个技能；开关控制是否注入对话，卸载将删除 manifest 与资源目录。"
                )
            }
        }
    ) { skill ->
        MarketCard(
            title = skill.name,
            subtitle = skill.id,
            description = skill.description,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { viewModel.toggleSkill(skill.id, it) }
                    )
                    IconButton(onClick = { pendingUninstall = skill }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "卸载技能",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }

    pendingUninstall?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("卸载技能") },
            text = { Text("卸载「${skill.name}」？其 manifest 与资源目录将一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstallSkill(skill.id)
                    pendingUninstall = null
                }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("取消") }
            }
        )
    }
}

// ═══ 已安装管理 · MCP ═══

@Composable
internal fun InstalledMcpTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingDelete by remember { mutableStateOf<MarketMcpRow?>(null) }

    if (state.mcps.isEmpty()) {
        MarketEmptyState(
            hint = "未配置 MCP 工具源 —— 到「市场」添加远端 HTTP/SSE 或本地命令 STDIO",
            actionLabel = "去市场添加",
            onAction = { goToBrowse(viewModel) }
        )
        return
    }

    MarketList(
        items = state.mcps,
        emptyHint = "未配置 MCP 工具源",
        key = { it.name },
        header = {
            item {
                MarketHeader(
                    "已配置 ${state.mcps.size} 个 MCP 工具源；连接后其工具注入对话，/ 菜单出现 /mcp:<名称>。"
                )
            }
        }
    ) { server ->
        MarketCard(
            title = server.name,
            subtitle = server.endpoint,
            description = "传输：" + server.transport.name + when (server.transport) {
                McpTransport.STDIO -> "（本地命令）"
                McpTransport.SSE -> "（远端 SSE）"
                McpTransport.HTTP -> "（远端 HTTP）"
            },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MarketStatusChip(
                        text = when {
                            server.connected -> "已连接"
                            server.enabled -> "离线"
                            else -> "已禁用"
                        },
                        positive = server.connected
                    )
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = { viewModel.toggleMcp(server.name, it) }
                    )
                    TextButton(
                        // 连接中禁用该行按钮防双击并发重连（断开不受影响）
                        enabled = state.mcpConnecting != server.name,
                        onClick = {
                            if (server.connected) viewModel.disconnectMcp(server.name)
                            else viewModel.connectMcp(server.name)
                        }
                    ) {
                        Text(if (server.connected) "断开" else if (state.mcpConnecting == server.name) "连接中…" else "连接")
                    }
                    TextButton(
                        onClick = { pendingDelete = server }
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 MCP 服务器") },
            text = { Text("删除「${server.name}」的配置？活跃连接将被断开。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMcp(server.name)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

// ═══ 已安装管理 · 连接器 ═══

@Composable
internal fun InstalledConnectorsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingDelete by remember { mutableStateOf<ConnectorDef?>(null) }

    if (state.connectors.isEmpty()) {
        MarketEmptyState(
            hint = "无连接器 —— 到「市场」添加外部服务访问配置",
            actionLabel = "去市场添加",
            onAction = { goToBrowse(viewModel) }
        )
        return
    }

    MarketList(
        items = state.connectors,
        emptyHint = "无连接器",
        key = { it.id },
        header = {
            item {
                MarketHeader("启用的连接器出现在 / 菜单（/connector:<id>）。内置示例删除后重启恢复。")
            }
        }
    ) { connector ->
        MarketCard(
            title = connector.name,
            subtitle = connector.id,
            description = connector.type + if (connector.endpoint.isNotBlank()) " · ${connector.endpoint}" else "",
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (connector.builtin) {
                        MarketStatusChip(text = "内置", positive = false)
                    }
                    Switch(
                        checked = connector.enabled,
                        onCheckedChange = { viewModel.toggleConnector(connector.id, it) }
                    )
                    TextButton(
                        onClick = { pendingDelete = connector }
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    pendingDelete?.let { connector ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除连接器") },
            text = { Text("删除「${connector.name}」？" + if (connector.builtin) "（内置示例，重启后恢复）" else "") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeConnector(connector.id)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

// ═══ 已安装管理 · 集成（魔搭 / GitHub 来源技能）═══

@Composable
internal fun InstalledIntegrationsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingUninstall by remember { mutableStateOf<MarketSkillRow?>(null) }

    // 远程来源技能：魔搭安装固定 ms- 前缀，GitHub SKILL.md 转换安装固定 gh- 前缀；
    // 携带自定义 manifest 的 GitHub 仓库 id 不定，归入「Skills」页统一管理。
    val remoteSkills = state.skills.filter { it.id.startsWith("ms-") || it.id.startsWith("gh-") }

    if (remoteSkills.isEmpty()) {
        MarketEmptyState(
            hint = "尚未从魔搭 / GitHub 安装技能 —— 到「市场 · 集成」浏览与一键安装",
            actionLabel = "去市场逛逛",
            onAction = { goToBrowse(viewModel) }
        )
        return
    }

    MarketList(
        items = remoteSkills,
        emptyHint = "暂无远程来源技能",
        key = { it.id },
        header = {
            item {
                MarketHeader(
                    "ms- 前缀 = 魔搭 ModelScope 安装；gh- 前缀 = GitHub 仓库转换安装。" +
                        "自定义 manifest 的 GitHub 技能在「Skills」页管理。"
                )
            }
        }
    ) { skill ->
        MarketCard(
            title = skill.name,
            subtitle = skill.id,
            description = skill.description,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MarketStatusChip(
                        text = if (skill.id.startsWith("ms-")) "魔搭" else "GitHub",
                        positive = false
                    )
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { viewModel.toggleSkill(skill.id, it) }
                    )
                    IconButton(onClick = { pendingUninstall = skill }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "卸载技能",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }

    pendingUninstall?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("卸载技能") },
            text = { Text("卸载「${skill.name}」？其 manifest 与资源目录将一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstallSkill(skill.id)
                    pendingUninstall = null
                }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("取消") }
            }
        )
    }
}
