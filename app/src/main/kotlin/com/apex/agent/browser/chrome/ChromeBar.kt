package com.apex.agent.browser.chrome

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * ChromeBar —— 折叠态导航行：[后退][前进][刷新/停止] [地址胶囊(=进度条)] [菜单]
 *
 * 独有设计：地址胶囊本身就是加载进度条——加载时胶囊底部以霓虹渐变按比例填充，
 * 不再另设进度条；加载图标与胶囊内小转圈同时提示状态。
 */
@Composable
internal fun ChromeBar(
    ui: ChromeUiState,
    controller: BrowserChromeController,
    onCopyLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEditing = ui.address is AddressBarUi.Editing

    Column(modifier = modifier.fillMaxWidth().background(paletteBackground())) {
        Crossfade(targetState = isEditing, animationSpec = tween(160)) { editing ->
            if (editing) {
                AddressEditRow(
                    edit = ui.address as AddressBarUi.Editing,
                    controller = controller,
                )
            } else {
                val collapsed = (ui.address as? AddressBarUi.Collapsed)
                    ?: AddressBarUi.Collapsed("", null, false, null)
                CollapsedNavRow(
                    collapsed = collapsed,
                    ui = ui,
                    controller = controller,
                    onCopyLink = onCopyLink,
                )
            }
        }
        val suggestions = (ui.address as? AddressBarUi.Editing)?.suggestions.orEmpty()
        AnimatedVisibility(visible = isEditing && suggestions.isNotEmpty()) {
            SuggestionsPanel(suggestions = suggestions, controller = controller)
        }
    }
}

@Composable
private fun CollapsedNavRow(
    collapsed: AddressBarUi.Collapsed,
    ui: ChromeUiState,
    controller: BrowserChromeController,
    onCopyLink: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavKey(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "后退",
            enabled = ui.activeTab?.canGoBack == true,
            onClick = { controller.goBack() },
        )
        NavKey(
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "前进",
            enabled = ui.activeTab?.canGoForward == true,
            onClick = { controller.goForward() },
        )
        if (collapsed.progress != null) {
            NavKey(
                icon = Icons.Filled.StopCircle,
                contentDescription = "停止加载",
                enabled = true,
                onClick = { controller.stopLoading() },
            )
        } else {
            NavKey(
                icon = Icons.Filled.Refresh,
                contentDescription = "刷新",
                enabled = ui.activeTab != null,
                onClick = { controller.reload() },
            )
        }

        AddressPill(collapsed = collapsed, controller = controller, modifier = Modifier.weight(1f))

        var menuExpanded by remember { mutableStateOf(false) }
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "更多",
                tint = chromePalette().textSecondary,
            )
        }
        ChromeOverflowMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            ui = ui,
            controller = controller,
            onCopyLink = onCopyLink,
        )
    }
}

@Composable
private fun NavKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val p = chromePalette()
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) p.textPrimary else p.textSecondary.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 地址胶囊：点击进入编辑，长按「粘贴即走」，加载时胶囊即进度条。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddressPill(
    collapsed: AddressBarUi.Collapsed,
    controller: BrowserChromeController,
    modifier: Modifier = Modifier,
) {
    val p = chromePalette()
    val pillShape = RoundedCornerShape(50)
    val progressAnim by animateFloatAsState(
        targetValue = collapsed.progress ?: 0f,
        animationSpec = tween(180),
        label = "addrProgress",
    )

    Surface(
        shape = pillShape,
        color = p.surfaceGlass,
        border = BorderStroke(1.dp, p.stroke),
        modifier = modifier
            .height(38.dp)
            .combinedClickable(
                onClick = { controller.enterAddressEdit() },
                onLongClick = { controller.pasteAndGo() },
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 进度填充：地址栏即进度条
            if (collapsed.progress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progressAnim.coerceIn(0.03f, 1f))
                        .clip(pillShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    p.accent.copy(alpha = 0.30f),
                                    p.accentAlt.copy(alpha = 0.06f),
                                ),
                            ),
                        ),
                )
            }
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (collapsed.isSecure) Icons.Filled.Lock else Icons.Outlined.Lock,
                    contentDescription = if (collapsed.isSecure) "安全连接" else "非安全连接",
                    tint = if (collapsed.isSecure) p.ok else p.warn,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = buildAnnotatedString {
                        val host = collapsed.host.ifBlank { "输入或搜索地址" }
                        if (collapsed.host.isBlank()) {
                            pushStyle(SpanStyle(color = p.textSecondary, fontSize = 13.sp))
                            append(host)
                            pop()
                        } else {
                            pushStyle(
                                SpanStyle(color = p.textPrimary, fontWeight = FontWeight.Medium),
                            )
                            append(host)
                            pop()
                            collapsed.pathQuery?.let { path ->
                                pushStyle(SpanStyle(color = p.textSecondary, fontSize = 12.sp))
                                append(path)
                                pop()
                            }
                        }
                    },
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (collapsed.progress != null) {
                    Spacer(Modifier.width(6.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = p.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun paletteBackground() = chromePalette().background

@Composable
internal fun chromePalette(): ChromePalette = LocalChromePalette.current
