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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * ═══ 市场共享 UI 件 ═══
 *
 * 从旧 MarketScreen.kt 抽出（拆分动机：MarketScreen 拆为
 * 脚手架 + Browse 页签集 + Installed 页签集后，共享卡片/列表件需要
 * internal 可见性供三个文件复用，同时守住单文件 1200 行预算）。
 */

/**
 * 下载/星标等计数短格式：中文 万/亿，英文 K/M/B，其余语言按英文习惯。
 * 数字部分先在此算好，再作为 %1$s 填入资源文案（如 “%1$s 次下载”）。
 */
internal fun formatDownloads(count: Long, language: String): String {
    val zh = language.equals("zh", ignoreCase = true)
    return when {
        zh && count >= 100_000_000L -> String.format("%.1f亿", count / 100_000_000.0)
        zh && count >= 10_000L -> String.format("%.1f万", count / 10_000.0)
        count >= 1_000_000_000L -> String.format("%.1fB", count / 1_000_000_000.0)
        count >= 1_000_000L -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000L -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}

/** 相对时间短格式（“3分钟前” / “2天前” / “从未”）—— 供已安装列表卡片副标题用。 */
@Composable
internal fun formatRelativeTime(timestampMs: Long): String {
    if (timestampMs <= 0) return stringResource(R.string.market_time_never)
    val delta = System.currentTimeMillis() - timestampMs
    return when {
        delta < 60_000 -> stringResource(R.string.market_time_just_now)
        delta < 3_600_000 -> stringResource(R.string.market_time_minutes_ago, delta / 60_000)
        delta < 86_400_000 -> stringResource(R.string.market_time_hours_ago, delta / 3_600_000)
        delta < 30L * 86_400_000 -> stringResource(R.string.market_time_days_ago, delta / 86_400_000)
        else -> stringResource(R.string.market_time_months_ago, delta / (30L * 86_400_000))
    }
}

/** 顶栏视图切换行 —— 市场 / 已安装管理 两个一级导航。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MarketScopeTabs(
    scope: MarketScope,
    installedCount: Int,
    onSelect: (MarketScope) -> Unit
) {
    PrimaryTabRow(selectedTabIndex = scope.ordinal) {
        MarketScope.entries.forEach { item ->
            val labelText = stringResource(item.labelRes)
            val label = if (item == MarketScope.INSTALLED && installedCount > 0) {
                "$labelText · $installedCount"
            } else {
                labelText
            }
            Tab(
                selected = scope == item,
                onClick = { onSelect(item) },
                text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = {
                    Icon(
                        imageVector = if (item == MarketScope.BROWSE) {
                            Icons.Default.Storefront
                        } else {
                            Icons.Default.Inventory2
                        },
                        contentDescription = null
                    )
                }
            )
        }
    }
}

/** 空态提示 —— 可选「去市场」动作按钮引导跳回安装视图。 */
@Composable
internal fun MarketEmptyState(
    hint: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
internal fun MarketSectionTitle(text: String) {
    Column {
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
internal fun MarketHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun MarketHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun <T> MarketList(
    items: List<T>,
    emptyHint: String = "",
    header: (LazyListScope.() -> Unit)? = null,
    key: ((T) -> Any)? = null,
    itemContent: @Composable LazyItemScope.(T) -> Unit
) {
    if (items.isEmpty()) {
        MarketEmptyState(hint = emptyHint)
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        header?.invoke(this)
        if (key != null) {
            items(items, key = key, itemContent = itemContent)
        } else {
            items(items, itemContent = itemContent)
        }
    }
}

@Composable
internal fun MarketCard(
    title: String,
    subtitle: String?,
    description: String?,
    descriptionMaxLines: Int = 3,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, // 修复：尾部控件（开关+双按钮 ~220dp）挤压时标题无限换行撑高卡片
                        overflow = TextOverflow.Ellipsis
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
                    maxLines = descriptionMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun MarketStatusChip(text: String, positive: Boolean) {
    androidx.compose.material3.Surface(
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

// ═══ 认知市场增强组件（cs-mem 能量 + 结晶徽章 + 熔断横幅）═══

/**
 * 能量条 —— 显示技能的 cs-mem 能量值 [0.01, 10.0]。
 * 颜色分级：≥8 橙（接近结晶）/ ≥4 绿（健康）/ ≥1 蓝（正常）/ ≥0.5 橙黄（低能）/ <0.5 红（危险）。
 */
@Composable
internal fun MarketEnergyBar(
    energy: Float,
    modifier: Modifier = Modifier
) {
    val color = when {
        energy >= 8.0f -> Color(0xFFFF6B35)
        energy >= 4.0f -> Color(0xFF4CAF50)
        energy >= 1.0f -> Color(0xFF2196F3)
        energy >= 0.5f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.market_energy),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Text(
                String.format("%.2f", energy),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { (energy / 10.0f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

/**
 * 结晶徽章 —— 标记已结晶为可跳过 LLM 的确定性 FSM 宏的高频技能。
 */
@Composable
internal fun MarketCrystallizedBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocalFireDepartment,
                contentDescription = stringResource(R.string.market_crystallized),
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

/**
 * 低能量警告徽章 —— 提示技能若不使用将被梦境折叠。
 */
@Composable
internal fun MarketLowEnergyBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = stringResource(R.string.market_low_energy),
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(2.dp))
            Text(
                stringResource(R.string.market_low_energy),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 成功率微型显示 —— 用于卡片角落。
 */
@Composable
internal fun MarketSuccessRateChip(successRate: Float) {
    val text = if (successRate > 0f) String.format("%.0f%%", successRate * 100) else "—"
    val color = when {
        successRate >= 0.9f -> MaterialTheme.colorScheme.primary
        successRate >= 0.5f -> MaterialTheme.colorScheme.tertiary
        successRate > 0f -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = color
    )
}
