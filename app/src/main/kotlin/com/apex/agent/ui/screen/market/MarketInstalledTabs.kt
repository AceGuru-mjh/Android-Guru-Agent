package com.apex.agent.ui.screen.market

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R
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
 * - 集成：从魔搭（ms-*）/ GitHub（gh-*）/ ClawHub（ch-*）安装的技能管理。
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
            hint = stringResource(R.string.market_installed_plugins_empty),
            actionLabel = stringResource(R.string.market_installed_plugins_go),
            onAction = { goToBrowse(viewModel) }
        )
    } else {
        MarketList(
            items = loaded,
            key = { it.packageName },
            header = {
                item {
                    MarketHeader(
                        stringResource(R.string.market_installed_plugins_header, loaded.size)
                    )
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
                        MarketStatusChip(
                            text = stringResource(R.string.market_status_loaded),
                            positive = true
                        )
                        TextButton(onClick = { pendingUnload = plugin }) {
                            Text(stringResource(R.string.market_action_uninstall))
                        }
                    }
                }
            )
        }
    }

    pendingUnload?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingUnload = null },
            title = { Text(stringResource(R.string.market_plugins_uninstall_title)) },
            text = {
                Text(stringResource(R.string.market_plugins_uninstall_text, plugin.label))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unloadPlugin(plugin.packageName)
                    pendingUnload = null
                }) { Text(stringResource(R.string.market_action_uninstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnload = null }) {
                    Text(stringResource(R.string.market_action_cancel))
                }
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
            hint = stringResource(R.string.market_installed_skills_empty),
            actionLabel = stringResource(R.string.market_installed_skills_go),
            onAction = { goToBrowse(viewModel) }
        )
        return
    }

    // ── v2 认知排序：按最近使用时间倒序（从未使用的沉底）──
    val sortedSkills = state.skills.sortedByDescending { it.lastUsedAt }

    MarketList(
        items = sortedSkills,
        emptyHint = stringResource(R.string.market_installed_skills_empty_short),
        key = { it.id },
        header = {
            item {
                MarketHeader(
                    stringResource(R.string.market_installed_skills_header, state.skills.size)
                )
            }
        }
    ) { skill ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.loadSkillDetail(skill.id) }
        ) {
            MarketCard(
                title = skill.name,
                subtitle = if (skill.lastUsedAt > 0) {
                    "${skill.id} · " + stringResource(
                        R.string.market_skill_last_used,
                        formatRelativeTime(skill.lastUsedAt)
                    )
                } else {
                    "${skill.id} · " + stringResource(R.string.market_skill_never_used)
                },
                description = skill.description,
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (skill.isCrystallized) {
                            MarketCrystallizedBadge()
                        } else if (skill.isLowEnergy) {
                            MarketLowEnergyBadge()
                        }
                        Switch(
                            checked = skill.enabled,
                            onCheckedChange = { viewModel.toggleSkill(skill.id, it) }
                        )
                        IconButton(onClick = { pendingUninstall = skill }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.market_cd_uninstall_skill),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
            // 能量条
            Spacer(modifier = Modifier.height(4.dp))
            MarketEnergyBar(energy = skill.energy)
            // 成功率 + 调用计数
            if (skill.successCount + skill.failureCount > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${skill.successCount}✓ / ${skill.failureCount}✗",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    MarketSuccessRateChip(successRate = skill.successRate)
                }
            }
        }
    }

    pendingUninstall?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(stringResource(R.string.market_uninstall_skill_title)) },
            text = { Text(stringResource(R.string.market_uninstall_skill_text, skill.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstallSkill(skill.id)
                    pendingUninstall = null
                }) { Text(stringResource(R.string.market_action_uninstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.market_action_cancel))
                }
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
            hint = stringResource(R.string.market_installed_mcp_empty),
            actionLabel = stringResource(R.string.market_installed_go_add),
            onAction = { goToBrowse(viewModel) }
        )
        return
    }

    MarketList(
        items = state.mcps,
        emptyHint = stringResource(R.string.market_installed_mcp_empty_short),
        key = { it.name },
        header = {
            item {
                MarketHeader(
                    stringResource(R.string.market_installed_mcp_header, state.mcps.size)
                )
            }
        }
    ) { server ->
        MarketCard(
            // 内置服务器（进程内 transport，App 预置）加「内置」标记与用户自建区分。
            title = if (server.builtin) {
                stringResource(R.string.market_builtin_name, server.name)
            } else {
                server.name
            },
            subtitle = server.endpoint,
            description = when (server.transport) {
                McpTransport.STDIO -> stringResource(R.string.market_transport_stdio)
                McpTransport.SSE -> stringResource(R.string.market_transport_sse)
                McpTransport.HTTP -> stringResource(R.string.market_transport_http)
                McpTransport.BUILTIN -> stringResource(R.string.market_transport_builtin)
            },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MarketStatusChip(
                        text = when {
                            server.connected -> stringResource(R.string.market_status_connected)
                            server.enabled -> stringResource(R.string.market_status_offline)
                            else -> stringResource(R.string.market_status_disabled)
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
                        Text(
                            when {
                                server.connected -> stringResource(R.string.market_action_disconnect)
                                state.mcpConnecting == server.name -> stringResource(R.string.market_connecting)
                                else -> stringResource(R.string.market_action_connect)
                            }
                        )
                    }
                    TextButton(
                        // 内置服务器随 App 预置/自愈，不提供删除入口
                        enabled = !server.builtin,
                        onClick = { if (!server.builtin) pendingDelete = server }
                    ) {
                        Text(
                            stringResource(R.string.market_action_delete),
                            color = if (server.builtin) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.market_delete_mcp_title)) },
            text = { Text(stringResource(R.string.market_delete_mcp_text, server.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMcp(server.name)
                    pendingDelete = null
                }) { Text(stringResource(R.string.market_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.market_action_cancel))
                }
            }
        )
    }
}

// ═══ 已安装管理 · 连接器 ═══

@Composable
internal fun InstalledConnectorsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingDelete by remember { mutableStateOf<ConnectorDef?>(null) }
    // 凭据配置：消息通道（微信/飞书/QQ/Telegram）只有填了凭据才能真正发消息
    var pendingConfig by remember { mutableStateOf<ConnectorDef?>(null) }
    val testBusy by viewModel.connectorTestBusy.collectAsState()
    val testResult by viewModel.connectorTestResult.collectAsState()

    if (state.connectors.isEmpty()) {
        MarketEmptyState(
            hint = stringResource(R.string.market_installed_connectors_empty),
            actionLabel = stringResource(R.string.market_installed_go_add),
            onAction = { goToBrowse(viewModel) }
        )
        return
    }

    MarketList(
        items = state.connectors,
        emptyHint = stringResource(R.string.market_installed_connectors_empty_short),
        key = { it.id },
        header = {
            item {
                MarketHeader(stringResource(R.string.market_installed_connectors_header))
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
                        MarketStatusChip(
                            text = stringResource(R.string.market_builtin),
                            positive = false
                        )
                    }
                    Switch(
                        checked = connector.enabled,
                        onCheckedChange = { viewModel.toggleConnector(connector.id, it) }
                    )
                    TextButton(
                        onClick = { pendingConfig = connector }
                    ) {
                        Text(stringResource(R.string.market_connector_action_config))
                    }
                    TextButton(
                        onClick = { pendingDelete = connector }
                    ) {
                        Text(stringResource(R.string.market_action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    pendingConfig?.let { connector ->
        ConnectorCredentialsDialog(
            connector = connector,
            busy = testBusy,
            testResult = testResult,
            onDismiss = { pendingConfig = null },
            onSave = { endpoint, apiKey, extra ->
                viewModel.saveConnectorCredentials(connector.id, endpoint, apiKey, extra)
                pendingConfig = null
            },
            onTest = { viewModel.testConnector(connector.id) }
        )
    }

    pendingDelete?.let { connector ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.market_delete_connector_title)) },
            text = {
                Text(
                    stringResource(R.string.market_delete_connector_text, connector.name) +
                        if (connector.builtin) {
                            stringResource(R.string.market_delete_connector_builtin_suffix)
                        } else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeConnector(connector.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.market_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.market_action_cancel))
                }
            }
        )
    }
}

// ═══ 已安装管理 · 集成（魔搭 / GitHub / ClawHub 来源技能）═══

@Composable
internal fun InstalledIntegrationsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingUninstall by remember { mutableStateOf<MarketSkillRow?>(null) }

    // 远程来源技能：魔搭安装固定 ms- 前缀，GitHub SKILL.md 转换安装固定 gh- 前缀，
    // ClawHub 仓库安装固定 ch- 前缀；携带自定义 manifest 的 GitHub 仓库 id 不定，
    // 归入「Skills」页统一管理。
    val remoteSkills = state.skills.filter {
        it.id.startsWith("ms-") || it.id.startsWith("gh-") || it.id.startsWith("ch-")
    }

    if (remoteSkills.isEmpty()) {
        MarketEmptyState(
            hint = stringResource(R.string.market_installed_integrations_empty),
            actionLabel = stringResource(R.string.market_installed_integrations_go),
            onAction = { goToBrowse(viewModel) }
        )
        return
    }

    MarketList(
        items = remoteSkills,
        emptyHint = stringResource(R.string.market_installed_integrations_empty_short),
        key = { it.id },
        header = {
            item {
                MarketHeader(
                    stringResource(R.string.market_installed_integrations_header)
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
                        text = when {
                            skill.id.startsWith("ms-") -> stringResource(R.string.market_source_modelscope)
                            skill.id.startsWith("ch-") -> "ClawHub"
                            else -> "GitHub"
                        },
                        positive = false
                    )
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { viewModel.toggleSkill(skill.id, it) }
                    )
                    IconButton(onClick = { pendingUninstall = skill }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.market_cd_uninstall_skill),
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
            title = { Text(stringResource(R.string.market_uninstall_skill_title)) },
            text = { Text(stringResource(R.string.market_uninstall_skill_text, skill.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstallSkill(skill.id)
                    pendingUninstall = null
                }) { Text(stringResource(R.string.market_action_uninstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.market_action_cancel))
                }
            }
        )
    }
}
