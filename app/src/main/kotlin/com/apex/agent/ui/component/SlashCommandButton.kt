package com.apex.agent.ui.component

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import kotlinx.coroutines.flow.StateFlow

/**
 * 斜杠指令按钮（成品版 · 数据驱动）
 *
 * 菜单数据由 [SlashMenuProvider] 实时提供，覆盖 Skills / MCP / 插件 / 连接器 四类，
 * 并随插件加载状态自动刷新。每个条目附带状态角标（已连接 / 离线 / 未安装 / 示例）。
 *
 * ## #197 工位作用域
 *
 * `scope` 参数（默认 "agent"）：Agent 屏传 "agent"、Coding 屏传 "coding" ——
 * 菜单在展示前经 [SlashMenuData.forScope] 过滤，两工位各自看到自己的
 * 技能/MCP 子集（市场分级同源口径）。"all" = 不过滤（调试用）。
 *
 * ## v5：技能 chip 输入框（多选）配套
 *
 * - `onItemSelected` 改为返回 Boolean：**true = 菜单保持展开**（流水线条目
 *   选中 → 追加为输入框内 chip，可继续多选），false = 选中即收起（普通
 *   文本插入类指令）；
 * - `isSelected`：条目当前是否已挂载为 chip（菜单内显示 ✓ 勾选态，再点
 *   一次由调用方摘除）；
 * - 弹窗 `focusable = false`：不抢主窗口焦点 —— **键盘不收起**，选完继续
 *   打字；返回键经 [BackHandler] 关菜单。
 *
 * ## v5：空间翻转（用户反馈「选择技能时 UI 重叠」）
 *
 * 旧实现固定 `BottomStart` 向上生长，键盘弹出/小屏时上方可用高度不足，
 * 系统把弹窗钳位到可用区内 → 面板直接盖住输入行。现在实测锚点在窗口
 * 可视区（扣除 IME）内的上方空间，不足 320dp 自动**向下翻转**（TopStart）。
 *
 * ## v3 修复：点击卡死（ANR）
 *
 * 旧实现把 [Popup]（focusable = true）**无条件**留在组合树里，仅靠内层
 * AnimatedVisibility 控制显隐 —— 常驻 focusable 弹窗抢焦点引发输入管线
 * 卡死。v3 起对齐 M3 模式：菜单打开时才组合 Popup，关闭即整体移除。
 */
@Composable
fun SlashCommandButton(
    slashMenuProvider: SlashMenuProvider,
    onItemSelected: (SlashMenuItem) -> Boolean,
    modifier: Modifier = Modifier,
    scope: String = "agent",
    isSelected: (SlashMenuItem) -> Boolean = { false }
) {
    var showMenu by remember { mutableStateOf(false) }

    // UI-016：青粉硬编码渐变改 primary→tertiary token —— 暗态恰为薄荷→品红
    // （保住霓虹渐变身份），亮态 0E7A57→B33A65 对浅底达 AA（原青 00E5FF 仅 1.44:1）
    val slashGradient = Brush.linearGradient(
        colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    )
    val openMenuCd = stringResource(R.string.chat_cd_slash_menu)

    // 锚点在窗口内的纵坐标（翻转判定用；组合期由 onGloballyPositioned 持续刷新）
    var anchorTopInWindow by remember { mutableStateOf(Float.MAX_VALUE) }

    Box(
        modifier = modifier
            // UI-012：48dp 触区红线（原 40dp 视觉系提升为 48 系，与
            // Attach/Github/Send/Toolkit 全行统一；圆底视觉随节点同步放大）
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .onGloballyPositioned { coords ->
                anchorTopInWindow = coords.positionInWindow().y
            }
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = CircleShape // 精修：与 Attach/Github/Toolkit 统一圆形剪裁（原 10dp 方角，行内独树一帜）
            )
            .semantics { contentDescription = openMenuCd }
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

        // v3：菜单打开时才组合 Popup —— 弹窗窗口不再常驻。
        if (showMenu) {
            SlashMenuPopup(
                menuFlow = slashMenuProvider.menu,
                scope = scope,
                anchorTopInWindow = anchorTopInWindow,
                isSelected = isSelected,
                onRefresh = slashMenuProvider::refresh,
                onDismiss = { showMenu = false },
                onItemSelected = { item ->
                    // 调用方决定保持展开（多选 chip）还是收起（普通指令）
                    showMenu = onItemSelected(item) && showMenu
                }
            )
        }
    }
}

/**
 * 动态级联菜单弹窗（数据驱动）。
 *
 * v5：①上方空间不足自动向下翻转（不再盖住输入行）②focusable = false
 * 保键盘 ③多选：流水线条目选中保持展开 + ✓ 勾选态 ④文案全部 i18n
 * （原硬编码中文）。
 */
@Composable
private fun SlashMenuPopup(
    menuFlow: StateFlow<SlashMenuData>,
    scope: String,
    anchorTopInWindow: Float,
    isSelected: (SlashMenuItem) -> Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onItemSelected: (SlashMenuItem) -> Unit
) {
    val menuDataRaw by menuFlow.collectAsStateWithLifecycle()
    // #197 工位作用域过滤（"all" = 不过滤）
    val menuData = remember(menuDataRaw, scope) { menuDataRaw.forScope(scope) }
    var expandedCategory by remember { mutableStateOf<String?>(null) }

    // ── 空间翻转判定：锚点上方（窗口可视区，扣除 IME）能否容下 320dp ──
    // Popup 仅在菜单打开时组合（v3 模式）→ 每次打开都重估；锚点位移（键盘
    // 弹出/收起改变可视区）也触发重估。无需 showMenu 键（不在本作用域）。
    val view = LocalView.current
    val density = LocalDensity.current
    val flipDown = remember(anchorTopInWindow) {
        val visible = android.graphics.Rect()
        view.getWindowVisibleDisplayFrame(visible)
        val spaceAbovePx = anchorTopInWindow - visible.top
        val needPx = with(density) { 320.dp.toPx() }
        spaceAbovePx < needPx
    }

    Popup(
        onDismissRequest = onDismiss,
        alignment = if (flipDown) Alignment.TopStart else Alignment.BottomStart,
        // 4dp 呼吸间隙：向上生长取负（底边上移），向下翻转取正（顶边下移）
        offset = IntOffset(0, with(density) { (if (flipDown) 4 else -4).dp.roundToPx() }),
        // focusable = false：不抢焦点（键盘不收起、输入框组合段不被打断）；
        // 点外部仍走 onDismissRequest，返回键由 BackHandler 兜住。
        properties = PopupProperties(focusable = false)
    ) {
        // 返回键关菜单（非 focusable 弹窗不消费按键，须在组合层拦截）
        BackHandler(onBack = onDismiss)

        // 入场动画：组合即播放（MutableTransitionState 初始 false → 目标 true）
        val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = entrance,
            enter = fadeIn(tween(150)) +
                scaleIn(initialScale = 0.95f, animationSpec = tween(150)) +
                slideInVertically(
                    initialOffsetY = { if (flipDown) 8 else -8 },
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
                        // 限高：菜单主体超出部分内部滚动 —— 弹窗永不顶出屏幕
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
                            stringResource(R.string.chat_slash_menu_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onRefresh,
                            // UI-012：48dp 触区红线（原 28dp）
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.chat_slash_refresh),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    menuData.categories.forEach { category ->
                        DynamicCategoryItem(
                            category = category,
                            isExpanded = expandedCategory == category.id,
                            isSelected = isSelected,
                            onToggle = {
                                expandedCategory =
                                    if (expandedCategory == category.id) null else category.id
                            },
                            onItemClick = { item -> onItemSelected(item) }
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
    isSelected: (SlashMenuItem) -> Boolean,
    onToggle: () -> Unit,
    onItemClick: (SlashMenuItem) -> Unit
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
                        text = category.hint ?: stringResource(R.string.chat_slash_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 4.dp)
                    )
                } else {
                    category.items.forEach { item ->
                        SlashItemRow(
                            item = item,
                            selected = isSelected(item),
                            onClick = { onItemClick(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlashItemRow(
    item: SlashMenuItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    val selectedCd = stringResource(R.string.chat_slash_cd_selected)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else Color.Transparent
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
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = selectedCd,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: SlashItemStatus) {
    val (textRes, containerColor, contentColor) = when (status) {
        SlashItemStatus.CONNECTED -> Triple(
            R.string.chat_slash_status_connected,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        SlashItemStatus.OFFLINE -> Triple(
            R.string.chat_slash_status_offline,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        SlashItemStatus.NOT_INSTALLED -> Triple(
            R.string.chat_slash_status_not_installed,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        SlashItemStatus.EXTERNAL -> Triple(
            R.string.chat_slash_status_external,
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
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
