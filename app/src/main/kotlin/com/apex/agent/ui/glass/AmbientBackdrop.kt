package com.apex.agent.ui.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop

/**
 * ═══════════════════════════════════════════════════════════════
 *  AmbientBackdrop —— 程序化玻璃材质源（kyant0 CanvasBackdrop）
 * ═══════════════════════════════════════════════════════════════
 *
 * 语义（诚实声明）：本源绘制的是**程序化材质纹理**（主题派生的霜面 +
 * 斜向光带），不是背后真实 UI 内容的采样 —— 但它流经与真实采样完全
 * 相同的 kyant0 玻璃管线：GPU RenderEffect blur +（API 33+）AGSL lens
 * 折射 + tint 霜面。物理上等价于「磨砂玻璃内部的受光结构与折射」——
 * iOS Liquid Glass 在纯色背景上的观感正是这一层。
 *
 * 使用场景：
 *  - 聊天气泡（AgentBubbleGlass）：列表内嵌件，背后只有平面底色 ——
 *    真实采样无内容可取，Cloudy 时代的 CPU 位图模糊由本源 + GPU 管线替代；
 *  - 设置 / 诊断等静态页的 GlassCard：无 hazeSource 传统（页面从未接过
 *    采样源），本源让全页玻璃获得统一的折射材质语言。
 *
 * 成本：每表面一次小尺寸 GraphicsLayer 录制 + GPU RenderEffect ——
 * 替代 Cloudy 的位图回读 + NEON CPU 迭代模糊，流式/滚动不再有掉帧尖峰。
 */
@Composable
internal fun rememberAmbientBackdrop(palette: GlassPalette): Backdrop {
    // #262 同款纪律：lambda 按 palette 记住 —— rememberCanvasBackdrop 的
    // remember(onDraw) 才能命中（GlassPalette 是 data class，equals 即稳定键），
    // 重组不再重建 CanvasBackdrop / 击穿 DrawBackdropElement 更新链。
    val onDraw: DrawScope.() -> Unit = remember(palette) { { drawAmbientTexture(palette) } }
    return rememberCanvasBackdrop(onDraw = onDraw)
}

/**
 * 材质纹理 —— 全部 palette 派生、DrawScope 手绘，无位图资源：
 *  - 垂直渐变：frostLift 顶部受光 → frostBase 底部沉降；
 *  - 斜向光带 ×2（主受光带 + 交叉细亮带）：经 blur/lens 扩散成
 *    柔和的受光磨砂 —— 白天 = 乳白底上的微光带（治「一片死白」），
 *    夜间 = 近黑底上的霓虹光雾（品牌延续）。
 *  光带色用白高光 + 主题色微染（#269 物理语义豁免：玻璃镜面反射就是白光）。
 */
private fun DrawScope.drawAmbientTexture(palette: GlassPalette) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // 霜面底：上亮下沉的垂直渐变
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(palette.frostLift, palette.frostBase),
            startY = 0f,
            endY = h
        )
    )

    // 主受光带：左上 → 右下的宽斜带（真实玻璃面板的受光方向）
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = if (palette.dark) 0.16f else 0.10f),
                Color.Transparent
            ),
            start = Offset(w * 0.05f, 0f),
            end = Offset(w * 0.75f, h * 0.9f)
        )
    )

    // 交叉细亮带：与主带交叉的窄反光带（玻璃中层内反射）
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = if (palette.dark) 0.07f else 0.05f),
                Color.Transparent
            ),
            start = Offset(w * 0.45f, 0f),
            end = Offset(w * 0.95f, h)
        )
    )
}
