package com.apex.agent.ui.screen.market

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.tools.mcp.McpServerCatalog

/**
 * ═══ 市场 · MCP 精选目录组件（#205）═══
 *
 * [MarketBrowseTabs.kt] 的 BrowseMcpTab 里「精选目录」区的渲染组件拆出
 * （God-file 预算：MarketBrowseTabs 1400→约 1125 行，恢复 1200 预算；
 * 拆分惯例同 CodeLongTaskCenterOps.kt —— 组件函数体逐字节一致迁移，
 * 仅可见性 private→internal 供同包 BrowseMcpTab 调用）：
 * - [McpCatalogHeader]：标题 + 可见条数 + 分类 chips（CATEGORY_ORDER 排序）；
 * - [McpCatalogEntryCard]：条目卡片（风险徽标 + 双语简介 + 安装按钮）；
 * - [McpCatalogEnvDialog]：必填环境变量引导表单（高风险警示 + 缺键内联点名）。
 */

/**
 * #205 精选目录头部卡片：标题 + 条数 + 分类 chips（横向滚动）。
 *
 * 条目本身由调用方 LazyColumn 的 items 逐条渲染（懒加载友好）。
 * #206：条数显示**过滤后**的可见数（旧实现显示总数，分级/分类过滤后误导）；
 * chips 按目录声明的 [McpServerCatalog.CATEGORY_ORDER] 排序。
 */
@Composable
internal fun McpCatalogHeader(state: MarketUiState, viewModel: MarketViewModel) {
    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.market_mcp_catalog_header),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    // #206 修复：可见数（分级 + 分类过滤后），不是资产总数。
                    text = stringResource(
                        R.string.market_mcp_catalog_count,
                        viewModel.visibleCatalog(state).size
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(R.string.market_mcp_catalog_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                FilterChip(
                    selected = state.mcpCatalogCategory == null,
                    onClick = { viewModel.selectCatalogCategory(null) },
                    label = { Text(stringResource(R.string.market_mcp_catalog_cat_all)) }
                )
                // 只显示当前分级下有条目的分类（避免死 chip），按目录声明的
                // 分类顺序渲染（旧实现按文件名字母序）。
                val tier = state.tier.name.lowercase()
                state.mcpCatalog
                    .filter { it.visibleToTier(tier) }
                    .groupBy { it.category }
                    .toSortedMap(compareBy { McpServerCatalog.CATEGORY_ORDER.indexOf(it) })
                    .forEach { (category, _) ->
                        FilterChip(
                            selected = state.mcpCatalogCategory == category,
                            onClick = { viewModel.selectCatalogCategory(category) },
                            label = { Text(catalogCategoryLabel(category)) }
                        )
                    }
            }
        }
    }
}

/** 当前 UI 语言是否中文（条目双语简介的取词依据）。 */
@Composable
private fun isZhLanguage(): Boolean =
    androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "zh"

/** 目录分类 → 本地化标签。 */
@Composable
private fun catalogCategoryLabel(category: String): String {
    val res = when (category) {
        "official" -> R.string.market_mcp_catalog_cat_official
        "web-search" -> R.string.market_mcp_catalog_cat_search
        "browser" -> R.string.market_mcp_catalog_cat_browser
        "database" -> R.string.market_mcp_catalog_cat_database
        "git" -> R.string.market_mcp_catalog_cat_git
        "cloud" -> R.string.market_mcp_catalog_cat_cloud
        "observability" -> R.string.market_mcp_catalog_cat_observability
        "docs" -> R.string.market_mcp_catalog_cat_docs
        "productivity" -> R.string.market_mcp_catalog_cat_productivity
        "desktop" -> R.string.market_mcp_catalog_cat_desktop
        "finance" -> R.string.market_mcp_catalog_cat_finance
        "design" -> R.string.market_mcp_catalog_cat_design
        "communication" -> R.string.market_mcp_catalog_cat_communication
        "location" -> R.string.market_mcp_catalog_cat_location
        "data" -> R.string.market_mcp_catalog_cat_data
        "remote" -> R.string.market_mcp_catalog_cat_remote
        else -> return category
    }
    return stringResource(res)
}

/**
 * #205 目录条目卡片：名称 + 风险/运行时/传输徽标 + 双语简介 + 安装按钮。
 */
@Composable
internal fun McpCatalogEntryCard(
    entry: McpServerCatalog.McpCatalogEntry,
    installed: Boolean,
    onInstall: () -> Unit
) {
    val zh = isZhLanguage()
    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.weight(1f))
                // 风险徽标（high 红 / medium 橙 / low 绿）
                val (riskLabel, riskColor) = when (entry.risk) {
                    "high" -> R.string.market_mcp_catalog_risk_high to MaterialTheme.colorScheme.error
                    "medium" -> R.string.market_mcp_catalog_risk_medium to MaterialTheme.colorScheme.tertiary
                    else -> R.string.market_mcp_catalog_risk_low to MaterialTheme.colorScheme.primary
                }
                Text(
                    text = stringResource(riskLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = riskColor,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                if (installed) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.market_mcp_catalog_installed),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text = entry.descriptionFor(zh),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Text(
                    text = listOfNotNull(
                        entry.transport.name,
                        entry.runtime,
                        if (entry.envSchema.any { it.required })
                            stringResource(R.string.market_mcp_catalog_needs_key) else null
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onInstall, enabled = !installed) {
                    Text(
                        if (installed) stringResource(R.string.market_mcp_catalog_installed)
                        else stringResource(R.string.market_mcp_catalog_install)
                    )
                }
            }
            entry.notes?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * #205 目录安装的环境变量引导表单：逐个渲染 envSchema（必填* + 描述），
 * 高风险条目加警示行。确认时校验必填项，缺的键逐个点名。
 */
@Composable
internal fun McpCatalogEnvDialog(
    entry: McpServerCatalog.McpCatalogEntry,
    onDismiss: () -> Unit,
    onInstall: (Map<String, String>) -> Unit
) {
    var values by remember(entry.id) { mutableStateOf(mapOf<String, String>()) }
    var missingKeys by remember(entry.id) { mutableStateOf<List<String>>(emptyList()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.market_mcp_catalog_env_title, entry.name),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column {
                Text(
                    text = entry.descriptionFor(isZhLanguage()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.risk == "high") {
                    Text(
                        text = stringResource(R.string.market_mcp_catalog_env_high_risk),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                if (missingKeys.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.market_mcp_catalog_missing_env, missingKeys.joinToString("、")),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    // #264：envSchema 内 key 唯一（McpCatalogEnvVar.key），列表形式 + key
                    // 替代下标形式 —— 条目增删时复用既有组合而非整段重建。
                    items(entry.envSchema, key = { it.key }) { envVar ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            OutlinedTextField(
                                value = values[envVar.key].orEmpty(),
                                onValueChange = { values = values + (envVar.key to it) },
                                isError = envVar.required && missingKeys.contains(envVar.key),
                                label = {
                                    Text(
                                        if (envVar.required) "${envVar.key} *"
                                        else envVar.key
                                    )
                                },
                                supportingText = {
                                    if (envVar.description.isNotBlank()) {
                                        Text(envVar.description, style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val missing = entry.requiredEnv()
                        .filter { values[it.key].isNullOrBlank() }
                        .map { it.key }
                    if (missing.isEmpty()) {
                        onInstall(values.filterValues { it.isNotBlank() })
                    } else {
                        // 必填缺失：弹窗内联点名（不关弹窗、保留已填内容）。
                        missingKeys = missing
                    }
                }
            ) {
                Text(stringResource(R.string.market_mcp_catalog_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.market_action_cancel))
            }
        }
    )
}
