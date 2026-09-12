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
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

/**
 * ═══════════════════════════════════════════════════════════════
 *  Liquid Glass 材质系统 —— GlassStyle
 * ═══════════════════════════════════════════════════════════════
 *
 * 设计原则（Selective Liquid Glass Spec §1/§4/§18）：
 *  1. 不同组件不得使用完全相同的玻璃材质 —— 每个档位独立调参；
 *  2. tint / edge / specular 全部从 MaterialTheme 动态派生，
 *     跟随 Light / Dark / Dynamic Color，禁止固定 white alpha 一招走天下；
 *  3. 诚实分层：
 *     - Backdrop 档（state != null）：经 Haze 真实采样背后内容 + GPU RenderEffect 模糊；
 *     - Frosted 档（state == null）：仅主题色薄霜 + 边缘光 + 高光，不冒充 backdrop。
 *
 * 明确未实现：Refraction —— 本系统不声明折射位移能力。
 */

/** 玻璃材质档位 —— Spec §4 规定的七档。 */
enum class GlassTier { Subtle, Control, Card, Navigation, Floating, Dialog, Strong }

/**
 * 玻璃材质参数集。所有字段均为“强度/半径”类参数，
 * 颜色一律在组合期从当前主题解析，保证动态主题适应。
 */
@Immutable
data class GlassStyle(
    val tier: GlassTier,
    /** Backdrop 模糊半径；Frosted 档作为颗粒感的视觉参照保留 */
    val blurRadius: Dp,
    /** 材质着色强度 0..1 —— 主体玻璃底色透明度 */
    val tintAlpha: Float,
    /** 玻璃颗粒噪声 0..1 */
    val noiseFactor: Float,
    /** 边缘高光强度 0..1 —— 内描边渐变的基准亮度 */
    val edgeAlpha: Float,
    /** 顶部镜面高光强度 0..1 */
    val specularAlpha: Float,
    /** 外阴影深度 —— 物理深度线索 */
    val elevation: Dp,
    /** 按压缩放系数 —— pressed 时的形变反馈 */
    val pressedScale: Float,
    /** Frosted 档底色浓度 0..1 */
    val scrimAlpha: Float
) {
    companion object {
        /** 极轻玻璃：小型静态状态组件 */
        val Subtle = GlassStyle(
            tier = GlassTier.Subtle, blurRadius = 8.dp, tintAlpha = 0.42f, noiseFactor = 0.06f,
            edgeAlpha = 0.08f, specularAlpha = 0.03f, elevation = 0.dp,
            pressedScale = 1f, scrimAlpha = 0.35f
        )

        /** 控制件玻璃：返回按钮 / 图标按钮 / 输入控制器 */
        val Control = GlassStyle(
            tier = GlassTier.Control, blurRadius = 10.dp, tintAlpha = 0.50f, noiseFactor = 0.08f,
            edgeAlpha = 0.16f, specularAlpha = 0.06f, elevation = 1.dp,
            pressedScale = 0.94f, scrimAlpha = 0.44f
        )

        /** 卡片玻璃：Agent 卡 / Tool 卡 / 状态卡 */
        val Card = GlassStyle(
            tier = GlassTier.Card, blurRadius = 14.dp, tintAlpha = 0.58f, noiseFactor = 0.10f,
            edgeAlpha = 0.18f, specularAlpha = 0.08f, elevation = 2.dp,
            pressedScale = 1f, scrimAlpha = 0.55f
        )

        /** 导航项玻璃：Drawer 每一项 —— 正常态必须“非常轻” */
        val Navigation = GlassStyle(
            tier = GlassTier.Navigation, blurRadius = 12.dp, tintAlpha = 0.46f, noiseFactor = 0.07f,
            edgeAlpha = 0.13f, specularAlpha = 0.05f, elevation = 0.dp,
            pressedScale = 0.98f, scrimAlpha = 0.38f
        )

        /** 悬浮件玻璃：FAB / 快捷浮动操作 —— 可用比卡片更强的边缘与高光 */
        val Floating = GlassStyle(
            tier = GlassTier.Floating, blurRadius = 18.dp, tintAlpha = 0.62f, noiseFactor = 0.12f,
            edgeAlpha = 0.24f, specularAlpha = 0.11f, elevation = 6.dp,
            pressedScale = 0.92f, scrimAlpha = 0.60f
        )

        /** 对话框玻璃：强材质，但内容保持清晰 */
        val Dialog = GlassStyle(
            tier = GlassTier.Dialog, blurRadius = 22.dp, tintAlpha = 0.66f, noiseFactor = 0.12f,
            edgeAlpha = 0.24f, specularAlpha = 0.10f, elevation = 12.dp,
            pressedScale = 1f, scrimAlpha = 0.72f
        )

        /** 最强玻璃：低频、高聚焦的特殊场景 */
        val Strong = GlassStyle(
            tier = GlassTier.Strong, blurRadius = 26.dp, tintAlpha = 0.74f, noiseFactor = 0.14f,
            edgeAlpha = 0.28f, specularAlpha = 0.13f, elevation = 4.dp,
            pressedScale = 1f, scrimAlpha = 0.80f
        )
    }
}

/**
 * 主题派生颜色集 —— 组合期从 MaterialTheme 解析，Light/Dark 双态。
 * 深色主题玻璃偏“提亮”——近黑基底上的发光材质；
 * 浅色主题玻璃偏“白霜”——白基底上的乳白材质。
 */
@Immutable
internal data class GlassPalette(
    val dark: Boolean,
    /** Haze 采样背景兜底色 —— HazeEffectScope.backgroundColor 必填 */
    val hazeBackground: Color,
    /** Backdrop 档材质着色 */
    val hazeTint: Color,
    /** API 低于 32 时的 scrim 兜底色 —— 无 blur 时仍可读 */
    val hazeFallback: Color,
    /** Frosted 档底色 */
    val frostBase: Color,
    /** Frosted 档顶部提亮色 */
    val frostLift: Color,
    /** 边缘高光顶色 —— 光源方向：上方 */
    val edgeTop: Color,
    /** 边缘高光底色 */
    val edgeBottom: Color,
    /** 镜面高光色 */
    val specular: Color
)

/** 当前主题下的玻璃调色板。跟随 Dynamic Color。 */
@Composable
internal fun glassPalette(style: GlassStyle, accent: Color): GlassPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val base = if (dark) {
        GlassPalette(
            dark = dark,
            hazeBackground = scheme.background,
            // 深色：玻璃 = 比基底略亮的青蓝灰材质，主色轻微浸染呼应霓虹主题
            hazeTint = scheme.surfaceContainerHigh.copy(alpha = style.tintAlpha)
                .compositeOverNeutral(scheme.primary.copy(alpha = 0.05f + 0.04f * style.tintAlpha)),
            hazeFallback = scheme.surfaceContainerHigh.copy(alpha = style.scrimAlpha + 0.25f),
            frostBase = scheme.surfaceContainerHigh.copy(alpha = style.scrimAlpha),
            frostLift = scheme.surfaceContainerHighest.copy(
                alpha = style.scrimAlpha * 0.6f + style.specularAlpha * 0.8f
            ),
            edgeTop = Color.White.copy(alpha = style.edgeAlpha),
            edgeBottom = Color.White.copy(alpha = style.edgeAlpha * 0.22f),
            specular = Color.White.copy(alpha = style.specularAlpha)
        )
    } else {
        GlassPalette(
            dark = dark,
            hazeBackground = scheme.background,
            // 浅色：乳白玻璃，边缘转向冷灰以在白底上可见
            hazeTint = scheme.surface.copy(alpha = style.tintAlpha + 0.08f)
                .compositeOverNeutral(scheme.primary.copy(alpha = 0.04f)),
            hazeFallback = scheme.surface.copy(alpha = style.scrimAlpha + 0.18f),
            frostBase = scheme.surface.copy(alpha = style.scrimAlpha + 0.15f),
            frostLift = Color.White.copy(alpha = style.specularAlpha * 1.4f + 0.10f),
            edgeTop = scheme.onSurface.copy(alpha = style.edgeAlpha * 0.55f),
            edgeBottom = scheme.onSurface.copy(alpha = style.edgeAlpha * 0.18f),
            specular = Color.White.copy(alpha = style.specularAlpha * 1.5f)
        )
    }
    // 状态强调色：工具卡运行态 / 错误态等着色 —— 不用大面积高饱和，保持克制
    return if (accent.alpha > 0f) base.copy(
        hazeTint = accent.copy(alpha = 0.14f + 0.18f * style.tintAlpha)
            .compositeOverNeutral(base.hazeTint),
        edgeTop = accent.copy(alpha = style.edgeAlpha * 1.1f)
            .compositeOverNeutral(base.edgeTop)
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

/** Backdrop 档的 HazeStyle —— tint / noise / blurRadius 全量来自玻璃档位与主题。 */
@Composable
internal fun GlassStyle.toHazeStyle(palette: GlassPalette): HazeStyle = HazeStyle(
    backgroundColor = palette.hazeBackground,
    tints = listOf(HazeTint(palette.hazeTint)),
    blurRadius = blurRadius,
    noiseFactor = noiseFactor,
    fallbackTint = HazeTint(palette.hazeFallback)
)

/** 应用级玻璃圆角基准 —— 与既有设计令牌对齐：chip 6 / 卡片 12 / 气泡 18。 */
object GlassShapes {
    val button = RoundedCornerShape(50)
    val card = RoundedCornerShape(12.dp)
    val navItem = RoundedCornerShape(12.dp)
    val floating = RoundedCornerShape(50)
    val dialog = RoundedCornerShape(20.dp)
}
