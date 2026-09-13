package com.apex.agent.browser.chrome

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * ChromePalette —— 霓虹设计系统（与 CyberNeonBall / NeonRingView 同一视觉语言）。
 *
 * - 暗色玻璃面板 + 青色主霓虹 + 品红副霓虹；
 * - BrowserChrome 会同时据此派生一个 MaterialTheme（暗色），宿主无需再包裹主题。
 */
data class ChromePalette(
    val background: Color,
    val surface: Color,
    val surfaceGlass: Color,
    val accent: Color,
    val accentAlt: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val stroke: Color,
    /** 标签条横向底衬。 */
    val stripBackground: Color,
) {
    companion object {
        fun neon(): ChromePalette = ChromePalette(
            background = Color(0xFF0B0E14),
            surface = Color(0xFF131A26),
            surfaceGlass = Color(0xE61A2333),
            accent = Color(0xFF00E5FF),
            accentAlt = Color(0xFFFF2D95),
            ok = Color(0xFF35D98C),
            warn = Color(0xFFFFB454),
            danger = Color(0xFFFF5D6E),
            textPrimary = Color(0xFFEAF2FF),
            textSecondary = Color(0xFF93A4BE),
            stroke = Color(0xFF2C3A55),
            stripBackground = Color(0xFF0E121B),
        )
    }
}

val LocalChromePalette = staticCompositionLocalOf { ChromePalette.neon() }
