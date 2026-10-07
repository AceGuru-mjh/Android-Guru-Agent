package com.apex.agent.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import android.os.Build

/**
 * ═══════════════════════════════════════════════════════════════
 *  TerminalGlass —— 终端语义玻璃（恒定深色材质，kyant0/backdrop 底座）
 * ═══════════════════════════════════════════════════════════════
 *
 * 终端尾窗是 Coding 屏底部悬浮栈的一员（与时间轴 backdrop 源互为兄弟
 * 节点）—— 接入 [backdrop] 即获得 kyant0 真实采样 + GPU blur + 折射；
 * 终端语义要求材质**不随主题翻转**（白天模式也是深色玻璃面板），
 * 因此调色不走 glassPalette 的主题派生，而用本文件固定的深色系。
 *
 * 诚实分层（与 GlassSurface 同一套声明）：
 *  - Backdrop（backdrop != null 且 API 31+）：采样 + 深色 tint —— 模糊的
 *    流式内容透出暗色磨砂质感，白天模式不再是一块实心黑板；
 *  - Frosted（backdrop == null 或 API < 31）：固定深色渐变兜底，不冒充。
 *
 * 仍归口玻璃系统：业务侧（CodeTerminalPanel）只传 backdrop，不触碰
 * kyant0 API —— 底层库替换时与 GlassSurface 同步迁移。
 */
@Composable
fun TerminalGlassSurface(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable () -> Unit
) {
    val tier = GlassStyle.Card

    // ═══ 极简黑白风格：终端玻璃短路为扁平深色面板 ═══
    // 终端语义恒定深色不随主题翻转（本文件不变式）—— 极简分支同样保持
    // 深底，仅去掉玻璃管线：实色深底 + 发丝描边，与 FlatSurface 同语言。
    if (com.apex.agent.ui.theme.LocalUiStyle.current ==
        com.apex.agent.ui.theme.UiStyle.MINIMAL
    ) {
        Box(
            modifier = modifier
                .shadow(elevation = tier.elevation, shape = shape, clip = false)
                .graphicsLayer {
                    this.shape = shape
                    this.clip = true
                }
                .background(TERMINAL_BASE, shape)
                .border(1.dp, TERMINAL_EDGE, shape),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
        return
    }

    val canSample = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val effectiveBackdrop: Backdrop =
        if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop
        } else {
            emptyBackdrop()
        }

    // Frosted 档的固定深色渐变（组合期构建 —— draw 零分配）
    val frostBrush = remember(TERMINAL_LIFT, TERMINAL_BASE) {
        Brush.verticalGradient(
            colors = listOf(TERMINAL_LIFT, TERMINAL_BASE),
            startY = 0f,
            endY = Float.POSITIVE_INFINITY
        )
    }

    Box(
        modifier = modifier
            .shadow(elevation = tier.elevation, shape = shape, clip = false)
            // 层级裁剪（白天模式矩形露角修复，同 GlassSurface 根因）
            .graphicsLayer {
                this.shape = shape
                this.clip = true
            }
            .drawBackdrop(
                backdrop = effectiveBackdrop,
                shape = { shape },
                effects = {
                    if (canSample) {
                        blur(tier.blurRadius.toPx())
                        lens(
                            refractionHeight = 8.dp.toPx(),
                            refractionAmount = 14.dp.toPx()
                        )
                    }
                },
                highlight = {
                    Highlight(
                        width = 0.8.dp,
                        blurRadius = 1.dp,
                        alpha = 0.45f,
                        style = HighlightStyle.Plain(
                            color = Color.White.copy(alpha = 0.10f),
                            blendMode = androidx.compose.ui.graphics.BlendMode.Plus
                        )
                    )
                },
                shadow = { null },
                innerShadow = {
                    InnerShadow(
                        radius = 6.dp,
                        offset = androidx.compose.ui.unit.DpOffset(0.dp, 3.dp),
                        color = Color.Black.copy(alpha = 0.40f),
                        alpha = 0.6f
                    )
                },
                onDrawSurface = {
                    if (canSample) {
                        drawRect(TERMINAL_BASE.copy(alpha = TERMINAL_GLASS_ALPHA))
                    } else {
                        drawRect(frostBrush)
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 终端基底（与 CodeTerminalPanel 既有恒定深底同源）。 */
private val TERMINAL_BASE = Color(0xFF101418)

/** 极简分支的发丝描边（深底上一档的冷灰）。 */
private val TERMINAL_EDGE = Color(0xFF2A323C)

/** 终端霜面顶部提亮（与头行色同源）。 */
private val TERMINAL_LIFT = Color(0xFF1A2027)

/** Backdrop 档深色玻璃浓度 —— 模糊内容透出但终端文字对比不受影响。 */
private const val TERMINAL_GLASS_ALPHA = 0.80f
