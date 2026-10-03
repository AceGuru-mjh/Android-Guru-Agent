package com.apex.agent.ui.screen.market

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.apex.agent.R
import com.apex.agent.core.tools.marketplace.OperitPluginSource
import com.apex.agent.core.tools.marketplace.RegistryServer
import com.apex.agent.ui.screen.market.MarketRegistryController.RepoSource

/**
 * ═══ MCP Registry 目录 · 市场 UI 区块 ═══
 *
 * MCP 页签的 Registry 家族区块（官方 Registry + PulseMCP 源切换）：
 * - [RegistrySourceToggleRow]：源切换 + 服务端搜索框 + Pulse 凭据入口；
 * - [RegistryServerCard]：目录行卡（名称 + 安装形态徽标 + 一键安装）；
 * - [PulseCredentialsDialog]：PulseMCP 合作凭据输入（X-API-Key /
 *   X-Tenant-ID，EncryptedSharedPreferences 落盘）；
 * - [OperitPluginCard]：Operit 社区插件行卡（浏览器打开 + MCP 形态探测安装）。
 *
 * 安装链路见 [MarketRegistryController.installServer]（决策树：远端直装 /
 * npm 沙箱真实预装+回滚 / 不支持报错）。
 */

/** Registry 区块头部：源切换 Chip + 搜索框 + 凭据入口。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun RegistrySourceToggleRow(
    state: MarketRegistryController.RegistryUiState,
    onRetry: () -> Unit,
    onSelectSource: (RepoSource) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBackToBrowse: () -> Unit,
    onOpenCredentials: () -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = state.source == RepoSource.OFFICIAL,
                onClick = { onSelectSource(RepoSource.OFFICIAL) },
                label = { Text(stringResource(R.string.market_registry_source_official)) }
            )
            FilterChip(
                selected = state.source == RepoSource.PULSE,
                onClick = { onSelectSource(RepoSource.PULSE) },
                label = {
                    Text(
                        stringResource(
                            R.string.market_registry_source_pulse,
                            if (state.pulseCredentialsSet) "✓" else "…"
                        )
                    )
                },
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            if (state.source == RepoSource.PULSE) {
                TextButton(onClick = onOpenCredentials) {
                    Text(
                        stringResource(R.string.market_registry_pulse_credentials),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            TextButton(onClick = onRetry, enabled = !state.loading) {
                Text(
                    if (state.loading) stringResource(R.string.market_loading)
                    else stringResource(R.string.market_action_refresh),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.market_registry_search_hint)) },
                textStyle = MaterialTheme.typography.bodySmall
            )
            if (state.searchActive) {
                TextButton(
                    onClick = onBackToBrowse,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.market_registry_back_to_browse),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            } else {
                TextButton(
                    onClick = onSearch,
                    modifier = Modifier.padding(start = 4.dp),
                    enabled = state.query.isNotBlank() && !state.loading
                ) {
                    Text(
                        stringResource(R.string.market_registry_search_action),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Registry 目录行卡（安装形态徽标 + 安装按钮 + npm 预装进度提示）。 */
@Composable
internal fun RegistryServerCard(
    entry: RegistryServer,
    installed: Boolean,
    installing: Boolean,
    installBusy: Boolean,
    onInstall: () -> Unit
) {
    Column {
        MarketCard(
            title = entry.displayName,
            subtitle = buildString {
                append(entry.name)
                if (entry.version.isNotBlank()) append(" · v").append(entry.version)
            },
            description = entry.description,
            descriptionMaxLines = 2,
            trailing = {
                if (installed) {
                    MarketStatusChip(
                        text = stringResource(R.string.market_status_installed),
                        positive = true
                    )
                } else {
                    TextButton(
                        onClick = onInstall,
                        enabled = !installBusy && entry.installKind != RegistryServer.InstallKind.UNSUPPORTED
                    ) {
                        Text(
                            if (installing) stringResource(R.string.market_installing)
                            else stringResource(R.string.market_action_install)
                        )
                    }
                }
            }
        )
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MarketStatusChip(
                text = installKindLabel(entry.installKind),
                positive = entry.installKind != RegistryServer.InstallKind.UNSUPPORTED
            )
            if (installing && entry.installKind == RegistryServer.InstallKind.NPM) {
                Text(
                    text = stringResource(R.string.market_registry_npm_installing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun installKindLabel(kind: RegistryServer.InstallKind): String = when (kind) {
    RegistryServer.InstallKind.REMOTE -> stringResource(R.string.market_registry_kind_remote)
    RegistryServer.InstallKind.NPM -> stringResource(R.string.market_registry_kind_npm)
    RegistryServer.InstallKind.UNSUPPORTED -> stringResource(R.string.market_registry_kind_unsupported)
}

/** PulseMCP 凭据对话框（X-API-Key / X-Tenant-ID，保存后立即切源加载）。 */
@Composable
internal fun PulseCredentialsDialog(
    apiKeyDraft: String,
    tenantIdDraft: String,
    configured: Boolean,
    onApiKeyChange: (String) -> Unit,
    onTenantIdChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.market_registry_pulse_credentials_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.market_registry_pulse_credentials_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = onApiKeyChange,
                    singleLine = true,
                    label = { Text("X-API-Key") }
                )
                OutlinedTextField(
                    value = tenantIdDraft,
                    onValueChange = onTenantIdChange,
                    singleLine = true,
                    label = { Text("X-Tenant-ID") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.market_action_save))
            }
        },
        dismissButton = {
            Row {
                if (configured) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.market_registry_credentials_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.market_action_cancel))
                }
            }
        }
    )
}

/** Operit 社区插件行卡（MCP 形态探测安装 + 浏览器打开仓库）。 */
@Composable
internal fun OperitPluginCard(
    entry: OperitPluginSource.OperitPlugin,
    installed: Boolean,
    installing: Boolean,
    installBusy: Boolean,
    onInstall: () -> Unit
) {
    val context = LocalContext.current
    Column {
        MarketCard(
            title = entry.fullName + if (entry.isMcp) " · MCP" else "",
            subtitle = "★ ${entry.stars}" + if (entry.topics.isEmpty()) "" else
                " · " + entry.topics.take(3).joinToString(" / "),
            description = entry.description,
            descriptionMaxLines = 2,
            trailing = {
                if (installed) {
                    MarketStatusChip(
                        text = stringResource(R.string.market_status_installed),
                        positive = true
                    )
                } else {
                    TextButton(
                        onClick = onInstall,
                        enabled = !installBusy
                    ) {
                        Text(
                            if (installing) stringResource(R.string.market_installing)
                            else stringResource(R.string.market_registry_operit_detect_install)
                        )
                    }
                }
            }
        )
        if (entry.htmlUrl.isNotBlank()) {
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, entry.htmlUrl.toUri())
                        )
                    }
                },
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text(
                    stringResource(R.string.market_registry_open_repo),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
