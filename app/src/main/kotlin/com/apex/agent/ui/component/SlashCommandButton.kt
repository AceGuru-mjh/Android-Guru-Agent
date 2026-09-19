package com.apex.agent.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * 斜杠指令按钮（成品版 · 数据驱动）
 *
 * 菜单数据由 [SlashMenuProvider] 实时提供，覆盖 Skills / MCP / 插件 / 连接器 四类，
 * 并随插件加载状态自动刷新。每个条目附带状态角标（已连接 / 离线 / 未安装 / 示例）。
 *
 * ## v3 修复：点击卡死（ANR）
 * 旧实现把 [Popup]（`PopupProperties(focusable = true)`）**无条件**留在组合树里，
 * 仅靠内层 AnimatedVisibility 控制内容显隐 —— 这与 Material3 DropdownMenu 的
 * `if (expanded) { Popup(...) }` 模式相悖：
 *  - 聊天页一进入组合，就存在一个**常驻的 focusable 弹窗窗口**：它在出现瞬间
 *    抢走主窗口焦点（IME 关闭/输入框失焦），且把全屏触摸作为 ACTION_OUTSIDE
 *    消费用于"关闭弹窗"，但弹窗本身因无条件组合永远不会真正移除 ——
 *    焦点在弹窗窗口与主窗口之间反复拉锯，点击斜杠按钮弹开内容的瞬间
 *    窗口尺寸变化 + 焦点迁移叠加，主线程输入管线卡死，表现为"一点就死机"。
 *  - v3 对齐 M3 模式：**菜单打开时才组合 Popup，关闭即整体移除**。
 *    弹窗锚定按钮左下角向上生长（BottomStart），限高 400dp 内部滚动；
 *    点外部 / 返回键关闭（focusable 弹窗的标准语义）。
 */
@Composable
fun SlashCommandButton(
    slashMenuProvider: SlashMenuProvider,
    onCommandSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val slashGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF00E5FF), Color(0xFFFF4081))
    )

    Box(
        modifier = modifier
            .size(40.dp)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = CircleShape // 精修：与 Attach/Github/Toolkit 统一圆形剪裁（原 10dp 方角，行内独树一帜）
            )
            .semantics { contentDescription = "打开斜杠指令菜单" }
            .clickable { showMenu = true },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val canvasSize = size
            val strokeWidth = canvasSize.width * 0.18f
            drawLine(
                brush = slashGradient,
                start = Offset(canvasSize.width * 0.78f, canvasSize.height * 0.18f),
                end = Offset(canvasSize.width * 0.22f, canvasSize.height * 0.82f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }

        // v3：菜单打开时才组合 Popup —— focusable 弹窗窗口不再常驻。
        // 锚定在按钮 Box 上（BottomStart：弹窗底边对齐按钮底边，向上生长）。
        if (showMenu) {
            SlashMenuPopup(
                menuFlow = slashMenuProvider.menu,
                onRefresh = slashMenuProvider::refresh,
                onDismiss = { showMenu = false },
                onCommandSelected = { command ->
                    onCommandSelected(command)
                    showMenu = false
                }
            )
        }
    }
}

/**
 * 动态级联菜单弹窗（数据驱动）。
 *
 * v3：仅在 [SlashCommandButton] 菜单打开期间存在；关闭（onDismiss / 选中命令）
 * 即整体离开组合，focusable 窗口随之销毁 —— 不再有常驻弹窗抢焦点/吞触摸。
 */
@Composable
private fun SlashMenuPopup(
    menuFlow: StateFlow<SlashMenuData>,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onCommandSelected: (String) -> Unit
) {
    val menuData by menuFlow.collectAsStateWithLifecycle()
    var expandedCategory by remember { mutableStateOf<String?>(null) }

    Popup(
        onDismissRequest = onDismiss,
        alignment = Alignment.BottomStart,
        // 向上留 4dp 呼吸间隙，避免弹窗底边紧贴按钮底边
        offset = IntOffset(0, with(LocalDensity.current) { (-4).dp.roundToPx() }),
        properties = PopupProperties(focusable = true)
    ) {
        // 入场动画：组合即播放（MutableTransitionState 初始 false → 目标 true）
        val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = entrance,
            enter = fadeIn(tween(150)) +
                scaleIn(initialScale = 0.95f, animationSpec = tween(150)) +
                slideInVertically(
                    initialOffsetY = { -8 },
                    animationSpec = tween(150)
                )
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 8.dp,
                tonalElevation = 3.dp,
                modifier = Modifier.width(300.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        // 限高：菜单向上生长，超出部分内部滚动 —— 弹窗永不顶出屏幕
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "快捷指令",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "刷新菜单",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    menuData.categories.forEach { category ->
                        DynamicCategoryItem(
                            category = category,
                            isExpanded = expandedCategory == category.id,
                            onToggle = {
                                expandedCategory =
                                    if (expandedCategory == category.id) null else category.id
                            },
                            onItemClick = { command -> onCommandSelected(command) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicCategoryItem(
    category: SlashMenuCategory,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onItemClick: (String) -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "arrow_rotation"
    )

    Column {
        Surface(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = if (isExpanded)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else Color.Transparent
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                // 数量 / 状态角标
                category.badge?.let { badge ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(arrowRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(150))
        ) {
            Column(modifier = Modifier.padding(start = 20.dp)) {
                if (category.items.isEmpty()) {
                    Text(
                        text = category.hint ?: "暂无可用项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 4.dp)
                    )
                } else {
                    category.items.forEach { item ->
                        SlashItemRow(item = item, onClick = { onItemClick(item.command) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SlashItemRow(
    item: SlashMenuItem,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "•",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            StatusChip(status = item.status)
        }
    }
}

@Composable
private fun StatusChip(status: SlashItemStatus) {
    val (text, containerColor, contentColor) = when (status) {
        SlashItemStatus.CONNECTED -> Triple(
            "已连接",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        SlashItemStatus.OFFLINE -> Triple(
            "离线",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        SlashItemStatus.NOT_INSTALLED -> Triple(
            "未安装",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        SlashItemStatus.EXTERNAL -> Triple(
            "示例",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        SlashItemStatus.READY -> return
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        modifier = Modifier.padding(start = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
