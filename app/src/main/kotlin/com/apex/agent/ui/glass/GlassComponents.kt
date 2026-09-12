package com.apex.agent.ui.glass

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeDialog
import dev.chrisbanes.haze.HazeState

/**
 * ═══════════════════════════════════════════════════════════════
 *  Glass Component API —— 业务层唯一入口
 * ═══════════════════════════════════════════════════════════════
 *
 * Spec §3：业务 UI 不允许直接散落第三方玻璃 API。
 * 本文件提供全部玻璃组件；内部细节封装在 GlassSurface / GlassStyle。
 *
 * 用法速查：
 *  - state 传 null = Frosted 档——无 backdrop 采样，诚实降级；
 *  - state 传 HazeState = Backdrop 档——前提：组件悬浮于 hazeSource 之上；
 *  - 所有组件支持 Normal / Pressed / Focused / Selected / Disabled。
 */

/** 玻璃卡片 —— Agent 卡 / Tool 卡 / 状态卡 / Plan 卡的统一容器。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    state: HazeState? = null,
    style: GlassStyle = GlassStyle.Card,
    shape: Shape = GlassShapes.card,
    accent: Color = Color.Unspecified,
    selected: Boolean = false,
    enabled: Boolean = true,
    focused: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassSurface(
        modifier = modifier,
        state = state,
        style = style,
        shape = shape,
        accent = accent,
        selected = selected,
        enabled = enabled,
        focused = focused,
        interactionSource = interactionSource
    ) {
        Column { content() }
    }
}

/**
 * 玻璃图标按钮 —— 返回按钮 / 顶栏菜单 / 小型控制。
 * 小尺寸、高圆角、轻玻璃 —— Spec §5A：不得做成巨大玻璃按钮。
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: HazeState? = null,
    style: GlassStyle = GlassStyle.Control,
    size: Dp = 40.dp,
    iconSize: Dp = Dp.Unspecified,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val resolvedTint = if (tint.alpha > 0f) tint else scheme.onSurfaceVariant
    val resolvedIconSize = if (iconSize == Dp.Unspecified) size * 0.55f else iconSize
    GlassSurface(
        modifier = modifier.size(size),
        state = state,
        style = style,
        shape = CircleShape,
        enabled = enabled,
        interactionSource = interaction
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassClickable(interaction, enabled, onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(resolvedIconSize),
                tint = resolvedTint
            )
        }
    }
}

/** 玻璃文字按钮 —— 胶囊形态，用于 Dialog / 空态等轻场景。 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: HazeState? = null,
    style: GlassStyle = GlassStyle.Control,
    accent: Color = Color.Unspecified,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val labelColor = if (accent.alpha > 0f) accent else scheme.onSurface
    GlassSurface(
        modifier = modifier,
        state = state,
        style = style,
        shape = GlassShapes.button,
        accent = accent,
        enabled = enabled,
        interactionSource = interaction
    ) {
        Row(
            modifier = Modifier
                .glassClickable(interaction, enabled, onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = labelColor
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = labelColor
            )
        }
    }
}

/**
 * 玻璃悬浮按钮 —— FAB / 快捷操作 / 滚动控制。
 * 允许比卡片更强的 blur / 边缘 / 高光 / 深度 —— Spec §8。
 */
@Composable
fun GlassFloatingButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: HazeState? = null,
    size: Dp = 48.dp,
    accent: Color = Color.Unspecified,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true
) {
    GlassIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        state = state,
        style = GlassStyle.Floating,
        size = size,
        iconSize = size * 0.5f,
        tint = tint,
        enabled = enabled
    )
}

/**
 * 玻璃导航项 —— Drawer 每一项的专用形态。
 * Normal：非常轻；Selected：材质亮度 + 深度提升；Pressed：短暂高光。
 * 保留既有霓虹左指示条与圆形图标井 —— 品牌延续而非推翻。
 */
@Composable
fun GlassNavigationItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: HazeState? = null,
    enabled: Boolean = true,
    accent: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val itemAccent = if (accent.alpha > 0f) accent else scheme.primary
    val interaction = remember { MutableInteractionSource() }
    val indicatorWidth by animateDpAsState(
        targetValue = if (selected) 3.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "glass_nav_indicator"
    )

    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        state = state,
        style = GlassStyle.Navigation,
        shape = GlassShapes.navItem,
        accent = itemAccent,
        selected = selected,
        enabled = enabled,
        interactionSource = interaction
    ) {
        Row(
            modifier = Modifier
                .glassClickable(interaction, enabled && !selected, onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 霓虹指示条 —— 既有品牌元素，玻璃化后保留
            Box(
                modifier = Modifier
                    .width(indicatorWidth)
                    .height(24.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(itemAccent)
            )
            // 圆形图标井：选中态主色浸染，常态中性
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) itemAccent.copy(alpha = 0.20f)
                            else scheme.surfaceContainerHighest.copy(alpha = 0.75f)
                        )
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (selected) itemAccent else scheme.onSurfaceVariant
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) itemAccent else scheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (trailing != null) trailing()
        }
    }
}

/** 工具卡状态 —— Spec §7：状态改变 tint / 亮度 / 高光，不用强色制造廉价效果。 */
enum class GlassToolStatus { RUNNING, COMPLETED, FAILED, WAITING }

/** 玻璃工具卡 —— Tool 执行 UI 专用；状态驱动着色，可用 accent 覆盖。 */
@Composable
fun GlassToolCard(
    status: GlassToolStatus,
    modifier: Modifier = Modifier,
    state: HazeState? = null,
    shape: Shape = GlassShapes.card,
    expanded: Boolean = false,
    accent: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val statusAccent = when (status) {
        GlassToolStatus.RUNNING -> scheme.primary
        GlassToolStatus.COMPLETED -> Color(0xFF22C55E)
        GlassToolStatus.FAILED -> scheme.error
        GlassToolStatus.WAITING -> scheme.secondary
    }
    GlassCard(
        modifier = modifier,
        state = state,
        style = GlassStyle.Card,
        shape = shape,
        accent = if (accent.alpha > 0f) accent else statusAccent,
        selected = expanded,
        content = content
    )
}

/**
 * 玻璃对话框 —— Spec §9。
 * state != null 时走 HazeDialog：跨窗口采样 Activity 内容做真实 backdrop blur；
 * state == null 时退回普通 Dialog 窗口 + Frosted 面板。
 * 对话框内容保持清晰 —— 玻璃只作用于面板材质本身。
 */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    state: HazeState? = null,
    style: GlassStyle = GlassStyle.Dialog,
    shape: Shape = GlassShapes.dialog,
    properties: DialogProperties = DialogProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    val panel: @Composable () -> Unit = {
        GlassSurface(
            modifier = modifier,
            style = style,
            shape = shape
        ) {
            Column(modifier = Modifier.padding(24.dp)) { content() }
        }
    }
    if (state != null) {
        HazeDialog(
            hazeState = state,
            onDismissRequest = onDismissRequest,
            properties = properties,
            content = { panel() }
        )
    } else {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = properties,
            content = { panel() }
        )
    }
}

/** 玻璃徽标 —— 小型状态组件的极轻玻璃。 */
@Composable
fun GlassBadge(
    modifier: Modifier = Modifier,
    state: HazeState? = null,
    accent: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit
) {
    GlassSurface(
        modifier = modifier,
        state = state,
        style = GlassStyle.Subtle,
        shape = RoundedCornerShape(8.dp),
        accent = accent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}
