package com.apex.agent.browser.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * TabSwitcherSheet —— 标签总览（卡片网格底部面板）。
 *
 * 与标签条互为补充：标签条用于 2-5 个标签的快速切换；
 * 卡片总览在标签较多时提供「一眼扫全部」的空间，也为将来接入真实页面缩略图预留了卡片版式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabSwitcherSheet(
    ui: ChromeUiState,
    controller: BrowserChromeController,
) {
    if (ui.sheet != ChromeSheet.TABS) return
    val p = chromePalette()

    ChromeBottomSheet(visible = true, onDismiss = { controller.closeSheet() }) {
        Text(
            text = "标签页 · ${ui.tabs.size}",
            fontSize = 15.sp,
            color = p.textPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = p.stroke, modifier = Modifier.padding(vertical = 6.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(ui.tabs, key = { it.id }) { tab ->
                TabCardView(
                    tab = tab,
                    active = tab.id == ui.activeTabId,
                    closing = tab.id in ui.closingTabIds,
                    onSelect = { controller.selectTab(tab.id) },
                    onClose = { controller.closeTab(tab.id) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(onClick = { controller.newTab(); controller.closeSheet() }) {
                Text("新建标签")
            }
            OutlinedButton(onClick = { controller.closeAll(); controller.closeSheet() }) {
                Text("关闭全部")
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun TabCardView(
    tab: TabCard,
    active: Boolean,
    closing: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val p = chromePalette()
    val cardShape = RoundedCornerShape(16.dp)
    Surface(
        shape = cardShape,
        color = if (active) p.surfaceGlass else p.surface,
        border = BorderStroke(
            width = if (active) 1.5.dp else 1.dp,
            color = if (active) p.accent.copy(alpha = 0.8f) else p.stroke,
        ),
        modifier = Modifier
            .height(150.dp)
            .alpha(if (closing) 0.35f else 1f)
            .clickable(onClick = onSelect),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FaviconBadge(host = tab.host)
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tab.title.ifBlank { tab.host },
                        fontSize = 12.sp,
                        color = p.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = tab.host,
                        fontSize = 10.sp,
                        color = p.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭标签",
                        tint = p.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // 预留缩略图版式：当前用首字母 + URL 大字占位，引擎接入截图后直接替换
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = tab.host.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 30.sp,
                        color = p.accent.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = tab.url,
                        fontSize = 9.sp,
                        color = p.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
