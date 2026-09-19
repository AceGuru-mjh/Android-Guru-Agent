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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
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
                            "正在从 cs-mem 投影认知数据…",
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
            SectionTitle("认知健康", Icons.Default.Bolt)
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
            SectionTitle("调用统计", Icons.Default.History)
            UsageSummaryPanel(state)
        }

        // 4. 每个工具的详细统计
        if (state.toolStats.isNotEmpty()) {
            item { SectionTitle("工具级明细", Icons.Default.CheckCircle) }
            items(state.toolStats) { row -> ToolStatRow(row) }
        }

        // 5. 最近轨迹（参数脱敏）
        if (state.traces.isNotEmpty()) {
            item { SectionTitle("最近调用轨迹（参数已脱敏）", Icons.Default.Schedule) }
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
                    "该技能尚未被蒸馏为 cs-mem 宏（未被 Agent 实际执行过）",
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
                        "能量",
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
                    StatChip("成功", macro.successCount.toString(), MaterialTheme.colorScheme.primary)
                    StatChip("失败", macro.failureCount.toString(), MaterialTheme.colorScheme.error)
                    StatChip("成功率", String.format("%.0f%%", macro.successRate * 100), MaterialTheme.colorScheme.tertiary)
                    StatChip("转移", macro.transitions.toString(), MaterialTheme.colorScheme.onSurfaceVariant)
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
                            "最近执行：${formatRelative(macro.lastExecutedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (macro.isCrystallized) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "✦ 已结晶 —— 命中初始状态时可跳过 LLM 直接回放（毫秒级响应）",
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
                "已结晶",
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
                    "熔断器开启（${openTools.size} 个工具）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    openTools.joinToString(", ") { "${it.toolId}(${it.failureCount} 失败)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "冷却期后自动进入半开状态，成功探测一次后恢复",
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
            "暂无调用记录",
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
            StatChip("总调用", state.totalInvocations.toString(), MaterialTheme.colorScheme.onSurface)
            StatChip("成功", state.totalSuccesses.toString(), MaterialTheme.colorScheme.primary)
            StatChip("失败", state.totalFailures.toString(), MaterialTheme.colorScheme.error)
            StatChip("成功率", String.format("%.0f%%", state.successRate * 100), MaterialTheme.colorScheme.tertiary)
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
                "${row.invocations}次",
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

private fun formatRelative(timestampMs: Long): String {
    val delta = System.currentTimeMillis() - timestampMs
    return when {
        delta < 60_000 -> "${delta / 1000} 秒前"
        delta < 3_600_000 -> "${delta / 60_000} 分钟前"
        delta < 86_400_000 -> "${delta / 3_600_000} 小时前"
        delta < 30L * 86_400_000 -> "${delta / 86_400_000} 天前"
        else -> "${delta / (30L * 86_400_000)} 个月前"
    }
}
