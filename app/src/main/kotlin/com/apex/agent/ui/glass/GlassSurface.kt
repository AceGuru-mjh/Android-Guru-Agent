package com.apex.agent.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
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
 *  - Frosted（state == null）两变体（[frostMaterial]）：
 *    - Gradient（默认）：主题色垂直渐变假霜面，零额外开销；
 *    - Cloudy：材质层自绘光带纹理，经 Cloudy 位图模糊（原生 NEON CPU）
 *      扩散成真磨砂发光材质 —— 仍不采样 backdrop（自体模糊，不冒充）。
 *  - Material response：pressed / focused / selected / disabled 驱动
 *    激活度动画，影响高光亮度、边缘强度与按压缩放；动画短促、非循环。
 *  - Edge lighting：drawOutline 内描边渐变 —— 上强下弱的受光边缘。
 *  - Specular sweep（可选）：外部传入共享扫掠相位（State<Float>，-0.4..1.4 归一化），
 *    光带绘制在材质之上、内容之下 —— 多卡共享同一时钟相位一致，
 *    且高光永不覆盖文字（原 demo 在 drawWithContent 里画在内容之上会洗白文本）。
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
    /** Frosted 档材质变体（state == null 时生效；Backdrop 档忽略此参数） */
    frostMaterial: GlassFrostMaterial = GlassFrostMaterial.Gradient,
    /** 共享扫掠相位（null = 不绘制动态光带）；多卡传同一 State 即完全同步 */
    specularSweep: State<Float>? = null,
    content: @Composable () -> Unit
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // ═══ 激活度：Normal 0 / Focused 0.35 / Selected 0.55 / Pressed 1 —— Spec §14 ═══
    // #262 延迟读取：保留 State 而非在此取值 —— 按压缩放（graphicsLayer）与
    // 叠加层（onDrawBehind）各自在读点订阅，激活度动画期间 GlassSurface
    // 零重组，drawWithCache 缓存才能跨帧存活（组合期读取会让每帧重组
    // 重建 drawWithCache 闭包，缓存被动画逐帧击穿）。
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
    val hazeState = state
    // Cloudy 变体只在 Frosted 档（无 backdrop 采样）生效；Backdrop 档有更强的真采样能力
    val useCloudyFrost = hazeState == null && frostMaterial == GlassFrostMaterial.Cloudy

    // ═══ 材质层 ═══
    val materialModifier = if (hazeState != null) {
        // Backdrop 档：真实采样 + 模糊 + tint + noise 全由 Haze 绘制
        Modifier.hazeEffect(
            state = hazeState,
            style = style.toHazeStyle(palette)
        )
    } else {
        // Frosted 档：主题色薄霜渐变 —— 明确不冒充 backdrop；
        // Cloudy 变体下它是模糊层之下的零底（blur 失败时的诚实降级）
        // #262：Brush 记住复用（GlassPalette 是 data class，equals 命中
        // 即跳过重建），避免每次重组重分配。
        val frostBrush = remember(palette) {
            Brush.verticalGradient(
                colors = listOf(palette.frostLift, palette.frostBase),
                startY = 0f,
                endY = Float.POSITIVE_INFINITY
            )
        }
        Modifier.background(brush = frostBrush, shape = shape)
    }

    Box(
        modifier = modifier
            // 深度：外阴影在最外层，不被裁剪
            .shadow(elevation = style.elevation, shape = shape, clip = false)
            // 按压缩放：玻璃的“形变”反馈 —— 替代涟漪的材质语言
            //（#262：graphicsLayer 内读 State，动画只失效图层不重组）
            .glassScale(style, activationState, scaleOnPress)
            // ═══ 层级裁剪（白天模式矩形露角根因修复 v1.4.5）═══
            // Haze 的 backdrop 采样层（API 32+ RenderEffect）与 scrim 兜底层
            //（API < 32）都按**矩形**绘制，`Modifier.clip(shape)` 的 Canvas
            // 裁剪在 RenderEffect/离屏合成路径上不可靠 —— 乳白 tint 层以
            // 方角矩形浮出圆角卡片（白天模式高对比下尤其扎眼）。
            // `graphicsLayer { shape; clip = true }` 把圆角下推到
            // RenderNode 层（setClipToBounds + Outline），任何子层、任何
            // 渲染效果、任何 API 级别都无法逃逸 —— 这是 Compose 里最强的
            // 裁剪保证。禁用态 alpha 合并进同一层（省一个离屏层）。
            .graphicsLayer {
                this.shape = shape
                this.clip = true
                alpha = if (enabled) 1f else 0.55f
            }
            .then(materialModifier)
            // 边缘光 / 镜面高光 / 扫掠光带 / 底部内阴影 / 激活增亮 —— 绘制在材质之上、内容之下
            //（Cloudy 变体改由内部 crisp 叠层子节点绘制，保证在模糊材质之上）
            //
            // #262 Compose 最佳实践（draw 阶段零分配）：drawBehind → drawWithCache。
            // 缓存层（size 变化才重建）：createOutline 的形状代数 + 描边宽度 ——
            // 这是旧实现每帧分配的大头（扫掠/流式/按压缩放的 draw 失效以前
            // 每次都重算）。激活度与扫掠相位是动画 State，刻意只在 onDrawBehind
            // 里读取：缓存层若读取它们，动画会逐帧击穿缓存，退化为 drawBehind。
            .then(
                if (useCloudyFrost) Modifier else Modifier.drawWithCache {
                    if (size.width <= 0f || size.height <= 0f) {
                        onDrawBehind { /* 空占位：零尺寸不绘制 */ }
                    } else {
                        val outline = shape.createOutline(
                            size = size,
                            layoutDirection = layoutDirection,
                            density = this
                        )
                        val strokePx = 1.5.dp.toPx()
                        onDrawBehind {
                            drawGlassOverlays(
                                outline = outline,
                                strokePx = strokePx,
                                palette = palette,
                                activation = activationState.value,
                                selected = selected,
                                accent = accent,
                                sweepPhase = specularSweep
                            )
                        }
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (useCloudyFrost) {
            // ═══ Cloudy 真模糊材质（v7）═══
            // 结构（自下而上）：霜面零底（外层 background）→ 模糊材质层
            //（CloudyFrostLayer：光带纹理 + 真位图模糊）→ crisp 叠加层
            //（边缘光/高光，不被模糊）→ 内容。文字与 crisp 叠加永不受模糊
            // 影响 —— Cloudy 自体模糊只包裹材质层自身节点（drawContent 仅
            // 光带纹理，内容是兄弟节点不在其子树内）。
            CloudyFrostLayer(
                palette = palette,
                style = style,
                modifier = Modifier.matchParentSize()
            )
            // crisp 叠加层同样走 #262 drawWithCache 缓存路径（零分配 + 缓存跨帧存活）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithCache {
                        if (size.width <= 0f || size.height <= 0f) {
                            onDrawBehind { /* 空占位：零尺寸不绘制 */ }
                        } else {
                            val outline = shape.createOutline(
                                size = size,
                                layoutDirection = layoutDirection,
                                density = this
                            )
                            val strokePx = 1.5.dp.toPx()
                            onDrawBehind {
                                drawGlassOverlays(
                                    outline = outline,
                                    strokePx = strokePx,
                                    palette = palette,
                                    activation = activationState.value,
                                    selected = selected,
                                    accent = accent,
                                    sweepPhase = specularSweep
                                )
                            }
                        }
                    }
            )
        }
        content()
    }
}

/** 按压缩放 —— activation 已经是动画值，直接映射，无需额外动画。
 *  #262：接收 State 在 graphicsLayer 块内读取 —— 动画只失效图层属性，
 *  不触发重组（组合期读取会让整个 GlassSurface 每帧重组）。 */
private fun Modifier.glassScale(
    style: GlassStyle,
    activationState: State<Float>,
    scaleOnPress: Boolean
): Modifier = if (!scaleOnPress || style.pressedScale >= 1f) this else graphicsLayer {
    val activation = activationState.value
    val scale = 1f - (1f - style.pressedScale) * activation
    scaleX = scale
    scaleY = scale
}

/**
 * 玻璃叠加层 —— 单次绘制，不产生额外图层分配。
 * activation 提升边缘与高光亮度，形成“按压即受光”的材质响应。
 *
 * #262：outline / 描边宽度由调用方 drawWithCache 缓存层预建（size 变化
 * 才重建）；本函数只构建依赖动画值（activation / sweepPhase）的 Brush ——
 * 这部分输入逐帧变化，无法安全缓存，是设计内的最小剩余分配。
 */
private fun DrawScope.drawGlassOverlays(
    outline: androidx.compose.ui.graphics.Outline,
    strokePx: Float,
    palette: GlassPalette,
    activation: Float,
    selected: Boolean,
    accent: Color,
    sweepPhase: State<Float>? = null
) {
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
        style = Stroke(width = strokePx)
    )

    // ═══ 镜面高光：斜向扫掠（Liquid Glass 标志性受光）═══
    // 修复白天模式玻璃「一片死白没质感」：原纯垂直渐变在乳白底上几乎不可见。
    // 改为左上→右下的斜向扫掠（真实玻璃面板的受光方向），白天在乳白底上
    // 仍能看出光带流动；夜间在暗底上形成斜向光纹。
    val specular = palette.specular.copy(
        alpha = (palette.specular.alpha * boost).coerceAtMost(0.35f)
    )
    if (specular.alpha > 0.005f) {
        drawOutline(
            outline = outline,
            brush = Brush.linearGradient(
                colors = listOf(specular, Color.Transparent),
                start = Offset(size.width * 0.08f, 0f),
                end = Offset(size.width * 0.92f, size.height * 0.62f)
            )
        )
    }

    // ═══ 中带分隔高光：玻璃「厚度」层次 ═══
    // 顶部受光带与底部定界之间加一条极淡的水平亮线（玻璃板中层的
    // 内反射），白天模式下给乳白霜面提供纵深，不再是平面的白块。
    if (!palette.dark) {
        val mid = size.height * 0.55f
        drawOutline(
            outline = outline,
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.05f * boost), Color.Transparent),
                startY = mid - size.height * 0.08f,
                endY = mid + size.height * 0.08f
            )
        )
    }

    // ═══ 动态扫掠光带（可选）：材质之上、内容之下 ═══
    // 相位 -0.4..1.4 归一化：0..1 之外时带体在卡外（keyframes 停留段）。
    // 颜色来自 palette.sweepColor —— 浅色主题 primary 染色（白上白不可见），
    // 深色主题白色高光。绘制在内容之下：光带扫过时文字永不被洗白。
    if (sweepPhase != null) {
        val bandCenter = sweepPhase.value * size.width
        val half = size.width * 0.24f
        if (bandCenter + half > 0f && bandCenter - half < size.width) {
            drawOutline(
                outline = outline,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.sweepColor,
                        Color.Transparent
                    ),
                    start = Offset(bandCenter - half, 0f),
                    end = Offset(bandCenter + half, size.height)
                )
            )
        }
    }

    // ═══ 底部内阴影：自下而上的深度渐暗 ═══
    // 白天模式减弱（暗色在乳白底上极易显脏，原 0.07 会被误读为灰框脏边）
    val bottomShadeAlpha = if (palette.dark) 0.07f + activation * 0.05f else 0.035f + activation * 0.03f
    drawOutline(
        outline = outline,
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = bottomShadeAlpha)),
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
