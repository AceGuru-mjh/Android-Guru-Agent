package com.apex.agent.ui.glass

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import androidx.compose.ui.unit.DpOffset

/**
 * ═══════════════════════════════════════════════════════════════
 *  GlassSurface —— 统一玻璃渲染核心（kyant0/backdrop 底座）
 * ═══════════════════════════════════════════════════════════════
 *
 * 业务 UI 禁止直接散落玻璃 API —— 一律经由本系统。底层库替换时只需改
 * ui/glass 包，业务组件零感知。
 *
 * 真实能力分层（诚实声明，禁止冒充）：
 *  - Backdrop 档（[backdrop] != null 且 API 31+）：kyant0 backdrop 真实采样
 *    源内容 → GPU RenderEffect blur →（API 33+）AGSL lens 折射/色散 →
 *    tint 霜面 → Highlight 边缘受光 → InnerShadow 厚度定界。折射形变与
 *    按压缩放经 layerBlock 反演，采样内容不随形变错位（kyant0 核心能力）。
 *  - Ambient 档（[backdrop] == null 且 API 31+）：程序化材质源
 *    （[rememberAmbientBackdrop]：主题霜面 + 斜向光带）流经同一条玻璃管线
 *    （blur + lens + tint）—— 不是背后内容的采样，不冒充 Backdrop；
 *    纯色背景页面（设置/诊断/气泡）的统一材质语言。
 *  - Frosted 档（API < 31）：无 RenderEffect —— 霜面渐变 + 边缘光，零管线。
 *
 * 采样源由页面提供：`val backdrop = rememberLayerBackdrop()` 挂在内容上
 * （Modifier.layerBackdrop(backdrop)），玻璃件悬浮其上；气泡等列表内嵌件
 * 可采样屏幕氛围背景（kyant0 支持任意同布局树源 —— Haze 时代的嵌套限制不存在）。
 *
 * 渲染层级（自下而上）：Compose shadow（裁剪外，可外溢）→ RenderNode 圆角
 * 裁剪层（v1.4.5 白天方角根因的同款保证，杜绝 blur 角部外溢）→ kyant0
 * drawBackdrop（Highlight/InnerShadow 自管理形状）→ 内容。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
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
    // #262 延迟读取：保留 State，layerBlock（graphicsLayer 块）内取值 ——
    // 按压缩放动画期间 GlassSurface 零重组（组合期读取会让每帧重组）。
    val activationState = animateFloatAsState(
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

    // ═══ 能力门禁：RenderEffect 是玻璃管线的硬前提（API 31+）═══
    // 低于 31 无法 blur/lens —— 整档降级 Frosted（霜面 + 边缘光，零管线、诚实）。
    val canRender = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // ═══ 三档源选择 ═══
    // Backdrop：页面提供真实采样源；Ambient：程序化材质源（同一条 GPU 管线）；
    // Frosted：API < 31 的零管线降级。
    val ambient = rememberAmbientBackdrop(palette)
    val effectiveBackdrop: Backdrop = when {
        backdrop != null && canRender -> backdrop
        canRender -> ambient
        else -> emptyBackdrop()
    }

    // 边缘受光：kyant0 Highlight —— BlurMaskFilter 柔边描边（全 API 一致），
    // 选中态提亮 + 强调色浸染（低饱和倾向，不刷屏）。
    val highlightAlpha = (style.specularAlpha + if (selected) 0.12f else 0f)
        .coerceAtMost(1f)
    val highlightColor = if (accent.alpha > 0f && selected) {
        accent.copy(alpha = 0.55f).compositeOver(palette.edgeColor)
    } else {
        palette.edgeColor
    }

    // Frosted 档霜面渐变（组合期构建，draw 零分配 —— #262）
    val frostBrush = remember(palette) {
        Brush.verticalGradient(
            colors = listOf(palette.frostLift, palette.frostBase),
            startY = 0f,
            endY = Float.POSITIVE_INFINITY
        )
    }

    Box(
        modifier = modifier
            // 深度：外阴影在最外层，不被裁剪（Compose shadow 形状感知；
            // 影色随主题派生 —— 白天淡影防「灰框脏边」，夜间深影撑发光材质）
            .shadow(
                elevation = style.elevation,
                shape = shape,
                clip = false,
                ambientColor = palette.shadowColor,
                spotColor = palette.shadowColor
            )
            // ═══ 层级裁剪（白天模式矩形露角根因，v1.4.5 同款保证）═══
            // RenderNode 级圆角裁剪：blur/lens 的 backdrop 绘制、霜面、内容
            // 任何子层都无法以方角浮出圆角卡片。禁用态 alpha 合并进同一层。
            .graphicsLayer {
                this.shape = shape
                this.clip = true
                alpha = if (enabled) 1f else 0.55f
            }
            // ═══ kyant0 backdrop 引擎 ═══
            // v1.4.9 闪退防御：blur/vibrancy/lens 运行在 draw 阶段 —— OEM
            // RenderEffect/AGSL（RuntimeShader 编译）差异可在首帧直接炸掉
            // RenderThread 或主线程。效果级 runCatching：单项失败降级为
            // 无该效果（Frosted 观感），组合树与进程存活优先。
            .drawBackdrop(
                backdrop = effectiveBackdrop,
                shape = { shape },
                effects = {
                    if (canRender) {
                        if (style.vibrancy > 0f) runCatching { vibrancy() }
                        runCatching { blur(style.blurRadius.toPx()) }
                        if (style.lensHeight > 0.dp) {
                            runCatching {
                                lens(
                                    refractionHeight = style.lensHeight.toPx(),
                                    refractionAmount = style.lensAmount.toPx(),
                                    chromaticAberration = style.chromaticAberration
                                )
                            }
                        }
                    }
                },
                highlight = {
                    Highlight(
                        width = 0.8.dp,
                        blurRadius = 1.2.dp,
                        alpha = highlightAlpha,
                        style = HighlightStyle.Plain(
                            color = highlightColor,
                            blendMode = androidx.compose.ui.graphics.BlendMode.Plus
                        )
                    )
                },
                // 深度走外层 Compose shadow（可外溢）；kyant0 Shadow 元素
                // 会被本组件的裁剪层截断，故不启用。
                shadow = { null },
                innerShadow = {
                    InnerShadow(
                        radius = 6.dp,
                        offset = DpOffset(0.dp, 3.dp),
                        color = Color.Black.copy(
                            alpha = if (palette.dark) 0.35f else 0.18f
                        ),
                        alpha = 0.6f
                    )
                },
                // 按压缩放：graphicsLayer 块内读 State（#262 零重组）；
                // kyant0 把同一 layerBlock 反演到 backdrop 采样 —— 表面
                // 形变时镜中世界保持对位（液态玻璃的「果冻挤压」语义）。
                layerBlock = glassScaleLayerBlock(style, activationState, scaleOnPress),
                // ═══ 霜面染色：绘制在模糊材质之上、内容之下 ═══
                // Backdrop/Ambient 档 = 透出模糊材质的 tint；Frosted 档 = 霜面渐变。
                onDrawSurface = {
                    if (canRender) {
                        drawRect(palette.glassTint)
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

/**
 * 按压缩放 —— kyant0 layerBlock 形态。
 *
 * #262：接收 State 在 graphicsLayer 块内读取 —— 动画只失效图层属性，
 * 不触发重组。
 */
private fun glassScaleLayerBlock(
    style: GlassStyle,
    activationState: State<Float>,
    scaleOnPress: Boolean
): (GraphicsLayerScope.() -> Unit)? =
    if (!scaleOnPress || style.pressedScale >= 1f) null else {
        {
            val activation = activationState.value
            val scale = 1f - (1f - style.pressedScale) * activation
            scaleX = scale
            scaleY = scale
        }
    }

/** 混色辅助 —— 选中态强调色边缘光的低饱和倾向。 */
private fun Color.compositeOver(overlay: Color): Color {
    if (overlay.alpha <= 0f) return this
    return Color(
        red = red * (1f - overlay.alpha) + overlay.red * overlay.alpha,
        green = green * (1f - overlay.alpha) + overlay.green * overlay.alpha,
        blue = blue * (1f - overlay.alpha) + overlay.blue * overlay.alpha,
        alpha = alpha
    )
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
