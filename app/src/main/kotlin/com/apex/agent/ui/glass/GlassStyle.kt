package com.apex.agent.ui.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ═══════════════════════════════════════════════════════════════
 *  Liquid Glass 材质系统 —— GlassStyle（kyant0/backdrop 底座）
 * ═══════════════════════════════════════════════════════════════
 *
 * 底层引擎：vendored kyant0/AndroidLiquidGlass（backdrop @ 1.0.0，Apache-2.0，
 * vendor/backdrop/）。真实能力分层（诚实声明，禁止冒充）：
 *  - API 33+（T）：AGSL RuntimeShader —— lens 折射/色散（真液态玻璃形变）
 *    + Default/Ambient 高光着色；
 *  - API 31+（S）：RenderEffect —— GPU blur 链 + ColorFilter（vibrancy）；
 *  - API < 31：无 RenderEffect —— GlassSurface 直接走 Frosted 档（霜面 +
 *    边缘光 + 阴影），不绘制未模糊的 backdrop。
 *
 * 设计原则（Selective Liquid Glass Spec §1/§4/§18 延续）：
 *  1. 不同组件不得使用完全相同的玻璃材质 —— 每个档位独立调参；
 *  2. tint / 高光 / 阴影全部从 MaterialTheme 动态派生，跟随 Light/Dark/Dynamic；
 *  3. 采样源由业务页面显式提供（rememberLayerBackdrop）—— 不再有无中生有的
 *    「假 backdrop」。
 */

/** 玻璃材质档位 —— Spec §4 规定的七档（枚举形状保持稳定）。 */
enum class GlassTier { Subtle, Control, Card, Navigation, Floating, Dialog, Strong }

/**
 * 玻璃材质参数集。字段与 kyant0 效果链的映射：
 *  - [blurRadius] → `effects { blur() }`（API 31+ GPU RenderEffect）
 *  - [lensHeight]/[lensAmount] → `effects { lens() }`（API 33+ 折射；0 = 不折射）
 *  - [tintAlpha] → `onDrawSurface` 霜面染色强度
 *  - [edgeAlpha]/[specularAlpha] → Highlight 边缘受光（BlurMaskFilter + 着色器）
 *  - [elevation] → Shadow 投影
 *  - [scrimAlpha] → Frosted 档（无采样）底色浓度
 */
@Immutable
data class GlassStyle(
    val tier: GlassTier,
    /** Backdrop 模糊半径（API 31+）*/
    val blurRadius: Dp,
    /** 材质着色强度 0..1 —— 玻璃霜面底色透明度 */
    val tintAlpha: Float,
    /** lens 折射带高度（API 33+；0.dp = 关闭折射）*/
    val lensHeight: Dp,
    /** lens 折射位移量（API 33+）*/
    val lensAmount: Dp,
    /** lens 色散（边缘彩虹分离；对话/卡片档默认关，悬浮件可开）*/
    val chromaticAberration: Boolean,
    /** 背景饱和度提升（vibrancy，API 31+ ColorFilter）*/
    val vibrancy: Float,
    /** 边缘高光强度 0..1 —— Highlight 受光的基准亮度 */
    val edgeAlpha: Float,
    /** 顶部镜面高光强度 0..1 */
    val specularAlpha: Float,
    /** 外阴影深度 —— 物理深度线索 */
    val elevation: Dp,
    /** 按压缩放系数 —— pressed 时的形变反馈（layerBlock 内实现，backdrop 采样自动反演）*/
    val pressedScale: Float,
    /** Frosted 档（state == null 或 API < 31）底色浓度 0..1 */
    val scrimAlpha: Float
) {
    companion object {
        /** 极轻玻璃：小型静态状态组件（徽标）—— 不折射，避免小尺寸形变夸张。 */
        val Subtle = GlassStyle(
            tier = GlassTier.Subtle, blurRadius = 8.dp, tintAlpha = 0.30f,
            lensHeight = 0.dp, lensAmount = 0.dp, chromaticAberration = false, vibrancy = 0f,
            edgeAlpha = 0.10f, specularAlpha = 0.05f, elevation = 0.dp,
            pressedScale = 1f, scrimAlpha = 0.42f
        )

        /** 控制件玻璃：返回按钮 / 图标按钮 / 输入控制器 —— 轻折射（按压形变）。 */
        val Control = GlassStyle(
            tier = GlassTier.Control, blurRadius = 10.dp, tintAlpha = 0.38f,
            lensHeight = 6.dp, lensAmount = 12.dp, chromaticAberration = false, vibrancy = 0f,
            edgeAlpha = 0.16f, specularAlpha = 0.08f, elevation = 1.dp,
            pressedScale = 0.94f, scrimAlpha = 0.52f
        )

        /** 卡片玻璃：Agent 卡 / Tool 卡 / 状态卡 / 设置与诊断页卡片。 */
        val Card = GlassStyle(
            tier = GlassTier.Card, blurRadius = 12.dp, tintAlpha = 0.42f,
            lensHeight = 8.dp, lensAmount = 16.dp, chromaticAberration = false, vibrancy = 0f,
            edgeAlpha = 0.18f, specularAlpha = 0.10f, elevation = 2.dp,
            pressedScale = 1f, scrimAlpha = 0.58f
        )

        /** 导航项玻璃：Drawer 每一项 —— 正常态必须「非常轻」。 */
        val Navigation = GlassStyle(
            tier = GlassTier.Navigation, blurRadius = 10.dp, tintAlpha = 0.34f,
            lensHeight = 5.dp, lensAmount = 10.dp, chromaticAberration = false, vibrancy = 0f,
            edgeAlpha = 0.13f, specularAlpha = 0.06f, elevation = 0.dp,
            pressedScale = 0.98f, scrimAlpha = 0.46f
        )

        /** 悬浮件玻璃：FAB / 输入栏 / 快捷浮动操作 —— 最强折射与高光，色散可辨。 */
        val Floating = GlassStyle(
            tier = GlassTier.Floating, blurRadius = 16.dp, tintAlpha = 0.46f,
            lensHeight = 10.dp, lensAmount = 20.dp, chromaticAberration = true, vibrancy = 0f,
            edgeAlpha = 0.24f, specularAlpha = 0.14f, elevation = 6.dp,
            pressedScale = 0.92f, scrimAlpha = 0.64f
        )

        /** 对话框玻璃：强材质，但内容保持清晰。 */
        val Dialog = GlassStyle(
            tier = GlassTier.Dialog, blurRadius = 20.dp, tintAlpha = 0.52f,
            lensHeight = 10.dp, lensAmount = 20.dp, chromaticAberration = false, vibrancy = 0f,
            edgeAlpha = 0.24f, specularAlpha = 0.12f, elevation = 12.dp,
            pressedScale = 1f, scrimAlpha = 0.74f
        )

        /** 最强玻璃：低频、高聚焦的特殊场景。 */
        val Strong = GlassStyle(
            tier = GlassTier.Strong, blurRadius = 24.dp, tintAlpha = 0.58f,
            lensHeight = 12.dp, lensAmount = 24.dp, chromaticAberration = true, vibrancy = 0f,
            edgeAlpha = 0.28f, specularAlpha = 0.16f, elevation = 4.dp,
            pressedScale = 1f, scrimAlpha = 0.82f
        )

        /**
         * 聊天气泡玻璃：Agent 回复 / 流式 / 思考三气泡专用。
         *
         * 与 Card 档的差异化调参（「不同组件不同材质」）：
         *  - 折射更轻（6/12dp）：气泡圆角大（18dp），重折射会让文字边缘形变；
         *  - tint 更透（0.34）：长文阅读优先，磨砂下透出氛围背景的呼吸感；
         *  - 不开色散：正文边缘出现彩虹分离会毁掉可读性；
         *  - 档位语义沿用 Card（七档枚举保持稳定，仅参数差异化）。
         */
        val Bubble = GlassStyle(
            tier = GlassTier.Card, blurRadius = 10.dp, tintAlpha = 0.34f,
            lensHeight = 6.dp, lensAmount = 12.dp, chromaticAberration = false, vibrancy = 0f,
            edgeAlpha = 0.20f, specularAlpha = 0.12f, elevation = 1.dp,
            pressedScale = 1f, scrimAlpha = 0.48f
        )
    }
}

/**
 * 主题派生颜色集 —— 组合期从 MaterialTheme 解析，Light/Dark 双态。
 * 深色主题玻璃偏「提亮」——近黑基底上的发光材质；
 * 浅色主题玻璃偏「白霜」——白基底上的乳白材质。
 */
@Immutable
internal data class GlassPalette(
    val dark: Boolean,
    /** Backdrop 档霜面染色（onDrawSurface 绘制在模糊采样之上）*/
    val glassTint: Color,
    /** Frosted 档底色（无采样的诚实降级底）*/
    val frostBase: Color,
    /** Frosted 档顶部提亮色 */
    val frostLift: Color,
    /** 边缘高光色 —— Highlight 受光 */
    val edgeColor: Color,
    /** 外阴影色 */
    val shadowColor: Color
)

/**
 * 当前主题下的玻璃调色板。跟随 Dynamic Color。
 */
@Composable
internal fun glassPalette(style: GlassStyle, accent: Color): GlassPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val base = if (dark) {
        GlassPalette(
            dark = dark,
            // 深色：玻璃 = 比基底略亮的材质，主色轻微浸染呼应霓虹主题
            glassTint = scheme.surfaceContainerHigh.copy(alpha = style.tintAlpha)
                .compositeOverNeutral(scheme.primary.copy(alpha = 0.06f)),
            frostBase = scheme.surfaceContainerHigh.copy(alpha = style.scrimAlpha),
            frostLift = scheme.surfaceContainerHighest.copy(
                alpha = style.scrimAlpha * 0.6f + style.specularAlpha * 0.8f
            ),
            // #269 豁免说明：玻璃体系的白高光是物理语义 —— 光照在玻璃上的
            // 镜面反射就是白色，与主题明暗无关（刻意设计，非漏网）。
            edgeColor = Color.White.copy(alpha = style.edgeAlpha),
            shadowColor = Color.Black.copy(alpha = 0.30f)
        )
    } else {
        GlassPalette(
            dark = dark,
            // 浅色：乳白磨砂 —— surfaceVariant 比纯白深一档，白底上叠得出「一层玻璃」；
            // 再薄叠 primary（0.05）给玻璃一点主题色倾向 —— 只给倾向，不刷屏
            glassTint = scheme.surfaceVariant.copy(alpha = style.tintAlpha)
                .compositeOverNeutral(scheme.primary.copy(alpha = 0.05f)),
            frostBase = scheme.surfaceVariant.copy(
                alpha = (style.scrimAlpha + 0.10f).coerceAtMost(1f)
            ),
            frostLift = Color.White.copy(alpha = style.specularAlpha * 1.6f + 0.20f),
            edgeColor = Color.White.copy(alpha = style.edgeAlpha * 1.3f + 0.07f),
            // 白天投影更淡：白底上重阴影显脏
            shadowColor = Color.Black.copy(alpha = 0.12f)
        )
    }
    // 状态强调色：工具卡运行态 / 错误态等着色 —— 不用大面积高饱和，保持克制
    return if (accent.alpha > 0f) base.copy(
        glassTint = accent.copy(alpha = 0.10f + 0.14f * style.tintAlpha)
            .compositeOverNeutral(base.glassTint),
        edgeColor = accent.copy(alpha = style.edgeAlpha * 1.1f)
            .compositeOverNeutral(base.edgeColor)
    ) else base
}

/** 将薄薄一层色叠加到中性基色 —— 让主色/强调色只提供“倾向”而不刷屏。 */
@Stable
private fun Color.compositeOverNeutral(overlay: Color): Color {
    if (overlay.alpha <= 0f) return this
    return Color(
        red = red * (1f - overlay.alpha) + overlay.red * overlay.alpha,
        green = green * (1f - overlay.alpha) + overlay.green * overlay.alpha,
        blue = blue * (1f - overlay.alpha) + overlay.blue * overlay.alpha,
        alpha = alpha
    )
}

/** 应用级玻璃圆角基准 —— 与既有设计令牌对齐：chip 6 / 卡片 12 / 气泡 18。 */
object GlassShapes {
    val button = RoundedCornerShape(50)
    val card = RoundedCornerShape(12.dp)
    val navItem = RoundedCornerShape(12.dp)
    val floating = RoundedCornerShape(50)
    val dialog = RoundedCornerShape(20.dp)
}
