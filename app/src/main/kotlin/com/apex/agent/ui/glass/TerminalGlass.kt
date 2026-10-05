package com.apex.agent.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * ═══════════════════════════════════════════════════════════════
 *  TerminalGlass —— 终端语义玻璃（恒定深色材质）
 * ═══════════════════════════════════════════════════════════════
 *
 * 终端尾窗是 Coding 屏底部悬浮栈的一员（与时间轴 hazeSource 互为兄弟
 * 节点）—— 接入 [state] 即获得 Haze 真实 backdrop 采样 + RenderEffect
 * 模糊；终端语义要求材质**不随主题翻转**（白天模式也是深色玻璃面板），
 * 因此调色不走 glassPalette 的主题派生，而用本文件固定的深色系。
 *
 * 诚实分层（与 GlassSurface 同一套声明）：
 *  - Backdrop（state != null）：Haze 采样 + 深色 tint —— 模糊的流式
 *    内容透出暗色磨砂质感，白天模式不再是一块实心黑板；
 *  - Frosted（state == null）：固定深色渐变兜底，不冒充 backdrop。
 *
 * 仍归口玻璃系统：业务侧（CodeTerminalPanel）只传 state，不触碰
 * Haze API —— 底层库替换时与 GlassSurface 同步迁移。
 */
@Composable
fun TerminalGlassSurface(
    state: HazeState?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable () -> Unit
) {
    val tier = GlassStyle.Card
    val hazeStyle = HazeStyle(
        backgroundColor = TERMINAL_BASE,
        tints = listOf(HazeTint(TERMINAL_BASE.copy(alpha = TERMINAL_GLASS_ALPHA))),
        blurRadius = tier.blurRadius,
        noiseFactor = tier.noiseFactor,
        fallbackTint = HazeTint(TERMINAL_BASE.copy(alpha = TERMINAL_FROST_ALPHA))
    )
    val materialModifier = if (state != null) {
        Modifier.hazeEffect(state = state, style = hazeStyle)
    } else {
        Modifier.background(
            brush = Brush.verticalGradient(
                colors = listOf(TERMINAL_LIFT, TERMINAL_BASE),
                startY = 0f,
                endY = Float.POSITIVE_INFINITY
            ),
            shape = shape
        )
    }
    Box(
        modifier = modifier
            .shadow(elevation = tier.elevation, shape = shape, clip = false)
            // 层级裁剪（白天模式矩形露角修复，同 GlassSurface v1.4.5 根因）
            .graphicsLayer {
                this.shape = shape
                this.clip = true
            }
            .then(materialModifier)
            // #262 Compose 最佳实践：终端表面随文本滚动/刷新频繁重绘，
            // 边缘描边的 outline / Brush / 描边宽度全部静态 —— drawWithCache
            // 后仅在尺寸变化时重建，绘制期零分配。
            .drawWithCache {
                if (size.width <= 0f || size.height <= 0f) {
                    onDrawBehind { /* 空占位：零尺寸不绘制 */ }
                } else {
                    val outline = shape.createOutline(
                        size = size,
                        layoutDirection = layoutDirection,
                        density = this
                    )
                    val edgeBrush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.02f)
                        ),
                        startY = 0f,
                        endY = size.height
                    )
                    val strokePx = 1.dp.toPx()
                    onDrawBehind {
                        drawOutline(
                            outline = outline,
                            brush = edgeBrush,
                            style = Stroke(width = strokePx)
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 终端基底（与 CodeTerminalPanel 既有恒定深底同源）。 */
private val TERMINAL_BASE = Color(0xFF101418)

/** 终端霜面顶部提亮（与头行色同源）。 */
private val TERMINAL_LIFT = Color(0xFF1A2027)

/** Backdrop 档深色玻璃浓度 —— 模糊内容透出但终端文字对比不受影响。 */
private const val TERMINAL_GLASS_ALPHA = 0.80f

/** 低 API 无 blur 的 scrim 兜底浓度（近实底，正文可读）。 */
private const val TERMINAL_FROST_ALPHA = 0.94f
