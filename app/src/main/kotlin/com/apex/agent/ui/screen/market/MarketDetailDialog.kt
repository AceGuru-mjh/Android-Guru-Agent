package com.apex.agent.ui.screen.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.R

/**
 * # 技能详情对话框
 *
 * 认知市场的核心信息面板 —— 把散落在四个子系统中的技能健康数据
 * （cs-mem 宏 / 工具调用统计 / 熔断器 / 调用轨迹）一次性呈现：
 *
 * - **认知健康**：能量条 + 结晶徽章 + 成功/失败计数 + 最近执行时间
 * - **调用统计**：总调用 / 成功率 / 均耗时（聚合 manifest.tools[].id）
 * - **熔断状态**：任一工具熔断开启时红色横幅 + 失败计数
 * - **最近轨迹**：最近 10 次调用（参数已脱敏，仅长度+指纹）
 * - **版本日志**：manifest.changelog
 *
 * 数据来自 [SkillDetailUiState]（由 [SkillAnalytics.projectSkillAnalytics] 投影）。
 */
@Composable
internal fun MarketSkillDetailDialog(
    skillId: String,
    skillName: String,
    detailState: SkillDetailUiState?,
    loading: Boolean,
    onDismiss: () -> Unit,
    /** Issue #166：内置技能（assets 释放）——标题行展示「内置」徽标。 */
    bundled: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.market_action_close)) }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skillName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    skillId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
                // Issue #166：内置技能徽标（复用状态 chip 样式；样式先例见
                // MarketComponents.MarketStatusChip / 内置模板徽标渲染）
                if (bundled) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            stringResource(R.string.market_skill_bundled_badge),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        },
        text = {
            if (loading || detailState == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.market_detail_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                DetailContent(detailState)
            }
        }
    )
}

@Composable
private fun DetailContent(state: SkillDetailUiState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 认知健康（cs-mem）
        item {
            SectionTitle(stringResource(R.string.market_detail_health), Icons.Default.Bolt)
            CognitiveHealthPanel(state)
        }

        // 2. 熔断横幅（若有任一工具熔断）
        if (state.anyBreakerOpen) {
            item {
                BreakerBanner(state.toolStats.filter { it.isOpen })
            }
        }

        // 3. 调用统计聚合
        item {
            SectionTitle(stringResource(R.string.market_detail_usage), Icons.Default.History)
            UsageSummaryPanel(state)
        }

        // 4. 每个工具的详细统计
        if (state.toolStats.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.market_detail_tool_stats), Icons.Default.CheckCircle) }
            items(state.toolStats) { row -> ToolStatRow(row) }
        }

        // 5. 最近轨迹（参数脱敏）
        if (state.traces.isNotEmpty()) {
            item {
                SectionTitle(
                    stringResource(R.string.market_detail_traces),
                    Icons.Default.Schedule
                )
            }
            items(state.traces) { span -> TraceSpanRow(span) }
        }
    }
}

@Composable
private fun SectionTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CognitiveHealthPanel(state: SkillDetailUiState) {
    val macro = state.macro
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (macro == null) {
                Text(
                    stringResource(R.string.market_detail_no_macro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (macro.isCrystallized) {
                        CrystallizedBadge()
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(R.string.market_energy),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        String.format("%.2f", macro.energy),
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = energyColor(macro.energy)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "[0.01, 10.0]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 能量条
                LinearProgressIndicator(
                    progress = { (macro.energy / 10.0f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = energyColor(macro.energy),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatChip(
                        stringResource(R.string.market_stat_success),
                        macro.successCount.toString(),
                        MaterialTheme.colorScheme.primary
                    )
                    StatChip(
                        stringResource(R.string.market_stat_failure),
                        macro.failureCount.toString(),
                        MaterialTheme.colorScheme.error
                    )
                    StatChip(
                        stringResource(R.string.market_stat_success_rate),
                        String.format("%.0f%%", macro.successRate * 100),
                        MaterialTheme.colorScheme.tertiary
                    )
                    StatChip(
                        stringResource(R.string.market_stat_transitions),
                        macro.transitions.toString(),
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (macro.lastExecutedAt > 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(
                                R.string.market_detail_last_executed,
                                formatRelative(macro.lastExecutedAt)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (macro.isCrystallized) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.market_detail_crystallized),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun CrystallizedBadge() {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocalFireDepartment,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.width(2.dp))
            Text(
                stringResource(R.string.market_crystallized),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .padding(end = 16.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun BreakerBanner(openTools: List<ToolUsageRow>) {
    // 单工具熔断条目模板（%1$s 工具 id / %2$d 失败次数）—— Composable 层预解析。
    val breakerToolTemplate = stringResource(R.string.market_breaker_tool)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    stringResource(R.string.market_breaker_open, openTools.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    // joinToString 的 lambda 非 @Composable 上下文 —— 模板先在
                    // Composable 层预解析（lambda 内仅做格式化）。
                    openTools.joinToString(", ") {
                        breakerToolTemplate.format(it.toolId, it.failureCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    stringResource(R.string.market_breaker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun UsageSummaryPanel(state: SkillDetailUiState) {
    if (!state.hasUsage) {
        Text(
            stringResource(R.string.market_detail_no_usage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatChip(
                stringResource(R.string.market_stat_total),
                state.totalInvocations.toString(),
                MaterialTheme.colorScheme.onSurface
            )
            StatChip(
                stringResource(R.string.market_stat_success),
                state.totalSuccesses.toString(),
                MaterialTheme.colorScheme.primary
            )
            StatChip(
                stringResource(R.string.market_stat_failure),
                state.totalFailures.toString(),
                MaterialTheme.colorScheme.error
            )
            StatChip(
                stringResource(R.string.market_stat_success_rate),
                String.format("%.0f%%", state.successRate * 100),
                MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun ToolStatRow(row: ToolUsageRow) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (row.isOpen) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (row.isOpen) MaterialTheme.colorScheme.error
                else if (row.invocations == 0) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                row.toolId,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            Text(
                stringResource(R.string.market_detail_invocations_count, row.invocations),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                String.format("%.0f%%", row.successRate * 100),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (row.successRate >= 0.9) MaterialTheme.colorScheme.primary
                else if (row.successRate >= 0.5) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(8.dp))
            Text(
                String.format("%.0fms", row.meanDurationMs),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TraceSpanRow(span: TraceRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when (span.outcome) {
                "SUCCESS" -> Icons.Default.CheckCircle
                "FAILED" -> Icons.Default.Error
                else -> Icons.Default.Warning
            },
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = when (span.outcome) {
                "SUCCESS" -> MaterialTheme.colorScheme.primary
                "FAILED" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.tertiary
            }
        )
        Spacer(Modifier.width(6.dp))
        Text(
            span.toolId,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Text(
            "${span.durationMs}ms",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            span.argsDigest,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun energyColor(energy: Float): Color {
    return when {
        energy >= 8.0f -> Color(0xFFFF6B35)  // 橙色 = 高能/接近结晶
        energy >= 4.0f -> Color(0xFF4CAF50)  // 绿色 = 健康
        energy >= 1.0f -> Color(0xFF2196F3)  // 蓝色 = 正常
        energy >= 0.5f -> Color(0xFFFF9800)  // 橙黄 = 低能警告
        else -> Color(0xFFF44336)            // 红色 = 危险（即将折叠）
    }
}

/** 相对时间（秒级）—— 详情对话框专用，文案随语言切换。 */
@Composable
private fun formatRelative(timestampMs: Long): String {
    val delta = System.currentTimeMillis() - timestampMs
    return when {
        delta < 60_000 -> stringResource(R.string.market_time_seconds_ago, delta / 1000)
        delta < 3_600_000 -> stringResource(R.string.market_time_minutes_ago, delta / 60_000)
        delta < 86_400_000 -> stringResource(R.string.market_time_hours_ago, delta / 3_600_000)
        delta < 30L * 86_400_000 -> stringResource(R.string.market_time_days_ago, delta / 86_400_000)
        else -> stringResource(R.string.market_time_months_ago, delta / (30L * 86_400_000))
    }
}
