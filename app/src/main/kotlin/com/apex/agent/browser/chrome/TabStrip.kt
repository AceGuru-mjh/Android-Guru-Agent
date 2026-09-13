package com.apex.agent.browser.chrome

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * TabStrip —— 横向标签条。
 *
 * 亮点：
 * - 活动标签霓虹描边 + 发光阴影（API 28+ 生效，低版本自动降级）；
 * - 关闭中的标签淡出（撤销宽限期的视觉反馈），并不立即消失；
 * - 活动标签变化时自动滚动到可见区。
 */
@Composable
internal fun TabStrip(
    ui: ChromeUiState,
    controller: BrowserChromeController,
    modifier: Modifier = Modifier,
) {
    val p = chromePalette()
    val listState = rememberLazyListState()
    val activeIndex = ui.tabs.indexOfFirst { it.id == ui.activeTabId }
    LaunchedEffect(activeIndex, ui.tabs.size) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(p.stripBackground),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(ui.tabs, key = { it.id }) { tab ->
            TabChip(
                tab = tab,
                active = tab.id == ui.activeTabId,
                closing = tab.id in ui.closingTabIds,
                onSelect = { controller.selectTab(tab.id) },
                onClose = { controller.closeTab(tab.id) },
            )
        }
        item(key = "new-tab") {
            NewTabChip(onClick = { controller.newTab() })
        }
    }
}

@Composable
private fun TabChip(
    tab: TabCard,
    active: Boolean,
    closing: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val p = chromePalette()
    val pillShape = RoundedCornerShape(50)
    val alphaAnim by animateFloatAsState(
        targetValue = if (closing) 0.35f else 1f,
        animationSpec = tween(160),
        label = "tabClosing",
    )
    Surface(
        shape = pillShape,
        color = if (active) p.surfaceGlass else p.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (active) p.accent.copy(alpha = 0.85f) else p.stroke,
        ),
        modifier = Modifier
            .width(184.dp)
            .height(38.dp)
            .alpha(alphaAnim)
            .let { m ->
                if (active) {
                    m.shadow(
                        6.dp,
                        pillShape,
                        spotColor = p.accent.copy(alpha = 0.45f),
                        ambientColor = p.accent.copy(alpha = 0.5f),
                    )
                } else {
                    m
                }
            }
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tab.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = p.accent,
                )
            } else {
                FaviconBadge(host = tab.host)
            }
            Spacer(Modifier.width(7.dp))
            Text(
                text = tab.title.ifBlank { tab.host },
                fontSize = 12.sp,
                color = if (active) p.textPrimary else p.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭标签",
                tint = p.textSecondary,
                modifier = Modifier
                    .size(28.dp)
                    .let { m ->
                        if (closing) m.alpha(0.35f) else m
                    }
                    .clickable(onClick = onClose)
                    .padding(5.dp),
            )
        }
    }
}

@Composable
private fun NewTabChip(onClick: () -> Unit) {
    val p = chromePalette()
    val pillShape = RoundedCornerShape(50)
    Surface(
        shape = pillShape,
        color = p.surface,
        border = BorderStroke(1.dp, p.stroke),
        modifier = Modifier
            .width(42.dp)
            .height(38.dp)
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "新标签页",
                tint = p.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 站点首字母徽标（引擎将来可替换为真实 favicon 的占位实现）。 */
@Composable
internal fun FaviconBadge(host: String, size: Int = 22) {
    val p = chromePalette()
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(p.accent.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = host.firstOrNull()?.uppercase() ?: "?",
            fontSize = 11.sp,
            color = p.accent,
            fontWeight = FontWeight.Bold,
        )
    }
}
