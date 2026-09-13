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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * ═══ 市场共享 UI 件 ═══
 *
 * 从旧 MarketScreen.kt 抽出（拆分动机：MarketScreen 拆为
 * 脚手架 + Browse 页签集 + Installed 页签集后，共享卡片/列表件需要
 * internal 可见性供三个文件复用，同时守住单文件 1200 行预算）。
 */

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
            val label = if (item == MarketScope.INSTALLED && installedCount > 0) {
                "${item.label} · $installedCount"
            } else {
                item.label
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
                    maxLines = 3,
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
