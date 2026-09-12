package com.apex.agent.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/**
 * ═══════════════════════════════════════════════════════════════
 *  GlassSurface —— 统一玻璃渲染核心
 * ═══════════════════════════════════════════════════════════════
 *
 * 业务 UI 禁止直接散落 Haze / 第三方玻璃 API —— 一律经由本系统。
 * 底层库替换时只需改本文件，业务组件零感知。
 *
 * 真实能力分层（诚实声明，禁止冒充）：
 *  - Backdrop：仅当 state != null 且本组件悬浮于 hazeSource 内容之上时生效；
 *    Haze 通过 GraphicsLayer 采样背后内容，API 32+ 走 GPU RenderEffect 模糊，
 *    低版本自动降级 scrim —— 不虚报为 blur。
 *  - Material response：pressed / focused / selected / disabled 驱动
 *    激活度动画，影响高光亮度、边缘强度与按压缩放；动画短促、非循环。
 *  - Edge lighting：drawOutline 内描边渐变 —— 上强下弱的受光边缘。
 *  - Depth：外阴影 + 底部内阴影两级线索。
 *  - Refraction：未实现 —— 本组件不声明折射位移。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    state: HazeState? = null,
    style: GlassStyle = GlassStyle.Card,
    shape: Shape = GlassShapes.card,
    accent: Color = Color.Unspecified,
    selected: Boolean = false,
    enabled: Boolean = true,
    focused: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    scaleOnPress: Boolean = true,
    content: @Composable () -> Unit
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // ═══ 激活度：Normal 0 / Focused 0.35 / Selected 0.55 / Pressed 1 —— Spec §14 ═══
    val activation by animateFloatAsState(
        targetValue = when {
            !enabled -> 0f
            pressed -> 1f
            selected -> 0.55f
            focused -> 0.35f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 140),
        label = "glass_activation"
    )

    val palette = glassPalette(style = style, accent = accent)
    val hazeState = state

    // ═══ 材质层 ═══
    val materialModifier = if (hazeState != null) {
        // Backdrop 档：真实采样 + 模糊 + tint + noise 全由 Haze 绘制
        Modifier.hazeEffect(
            state = hazeState,
            style = style.toHazeStyle(palette)
        )
    } else {
        // Frosted 档：主题色薄霜渐变 —— 明确不冒充 backdrop
        Modifier.background(
            brush = Brush.verticalGradient(
                colors = listOf(palette.frostLift, palette.frostBase),
                startY = 0f,
                endY = Float.POSITIVE_INFINITY
            ),
            shape = shape
        )
    }

    Box(
        modifier = modifier
            // 深度：外阴影在最外层，不被裁剪
            .shadow(elevation = style.elevation, shape = shape, clip = false)
            // 按压缩放：玻璃的“形变”反馈 —— 替代涟漪的材质语言
            .glassScale(style, activation, scaleOnPress)
            // 禁用态整体降权
            .graphicsLayer { alpha = if (enabled) 1f else 0.55f }
            .clip(shape)
            .then(materialModifier)
            // 边缘光 / 镜面高光 / 底部内阴影 / 激活增亮 —— 绘制在材质之上、内容之下
            .drawBehind {
                drawGlassOverlays(shape, palette, activation, selected, accent)
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 按压缩放 —— activation 已经是动画值，直接映射，无需额外动画。 */
private fun Modifier.glassScale(
    style: GlassStyle,
    activation: Float,
    scaleOnPress: Boolean
): Modifier = if (!scaleOnPress || style.pressedScale >= 1f) this else graphicsLayer {
    val scale = 1f - (1f - style.pressedScale) * activation
    scaleX = scale
    scaleY = scale
}

/**
 * 玻璃叠加层 —— 全部为单次绘制，不产生额外图层分配。
 * activation 提升边缘与高光亮度，形成“按压即受光”的材质响应。
 */
private fun DrawScope.drawGlassOverlays(
    shape: Shape,
    palette: GlassPalette,
    activation: Float,
    selected: Boolean,
    accent: Color
) {
    if (size.width <= 0f || size.height <= 0f) return
    val outline = shape.createOutline(
        size = size,
        layoutDirection = layoutDirection,
        density = this
    )
    val boost = 1f + activation * 0.9f

    // ═══ 边缘高光：上缘受光强、下缘余晖 —— 真实的“玻璃边”层次 ═══
    val edgeTop = palette.edgeTop.copy(alpha = (palette.edgeTop.alpha * boost).coerceAtMost(0.9f))
    val edgeBottom = palette.edgeBottom.copy(
        alpha = (palette.edgeBottom.alpha * (0.6f + activation * 0.8f)).coerceAtMost(0.7f)
    )
    drawOutline(
        outline = outline,
        brush = Brush.verticalGradient(
            colors = listOf(edgeTop, edgeBottom),
            startY = 0f,
            endY = size.height
        ),
        style = Stroke(width = 1.5.dp.toPx())
    )

    // ═══ 镜面高光：顶部 45% 高度的受光扫掠 ═══
    val specular = palette.specular.copy(
        alpha = (palette.specular.alpha * boost).coerceAtMost(0.35f)
    )
    if (specular.alpha > 0.005f) {
        drawOutline(
            outline = outline,
            brush = Brush.verticalGradient(
                colors = listOf(specular, Color.Transparent),
                startY = 0f,
                endY = size.height * 0.45f
            )
        )
    }

    // ═══ 底部内阴影：自下而上的深度渐暗 ═══
    drawOutline(
        outline = outline,
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.07f + activation * 0.05f)),
            startY = size.height * 0.65f,
            endY = size.height
        )
    )

    // ═══ 选中态强调浸染：低饱和 wash，拒绝大面积刷色 ═══
    if (selected && accent.alpha > 0f) {
        drawOutline(
            outline = outline,
            brush = Brush.verticalGradient(
                colors = listOf(
                    accent.copy(alpha = 0.05f + activation * 0.05f),
                    accent.copy(alpha = 0.03f)
                )
            )
        )
    }
}

/**
 * 便捷 clickable：无涟漪 —— 玻璃的材质响应本身就是按压反馈，
 * 涟漪 + 高光双重反馈会显得廉价。
 */
fun Modifier.glassClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = clickable(
    interactionSource = interactionSource,
    indication = null,
    enabled = enabled,
    onClick = onClick
)
