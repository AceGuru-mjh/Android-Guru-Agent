package com.apex.agent.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material3 colorScheme 之外的扩展语义色（success / warning）。
 *
 * 动机（UI-016）：仓库此前「成功绿 / 警告橙」散落 20+ 处硬编码
 * （4CAF50 / 22C55E / FFB020 / F59E0B / E0A63C ...），均为单态色 ——
 * 深色模式下可用，浅色模式下对浅底对比度仅 1.5-2.9:1，是「浅色主题
 * 不可用」的一半原因（2026-08-27 全应用 UI 审查报告 UI-016 条目）。
 * error 在 colorScheme 里已有明暗成对槽位，success / warning 没有 ——
 * 本文件补上这块基建，各处统一从 LocalExtendedColors 取词。
 *
 * 色值全部取自仓内既有色（终端琥珀 FFB454 = 品牌 amber 身份、语法
 * 高亮绿 4ADE80、Mint 浅色族 15803D / B45309），非外来色；明暗成对
 * 定义，四值在各自背景下均 >= 4.5:1（AA）。danger 不新建 —— 复用
 * colorScheme.error（已成对，暗 FF6B9D / 亮 BA1A4A）。
 *
 * 注入见 Theme.kt（ApexTheme 内 CompositionLocalProvider），dynamicColor
 * 路径同样适用 —— 扩展色独立于 accent 派生，不随 Material You 漂移。
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val warning: Color
) {
    companion object {
        /** 深色基底（近黑带蓝）：亮字色，视觉延续既有霓虹观感。 */
        val Dark = ExtendedColors(
            success = Color(0xFF4ADE80), // 语法高亮既有绿，深底 10.6:1
            warning = Color(0xFFFFB454) // 终端琥珀 = 品牌 amber 身份
        )

        /** 浅色基底：深一档的同族色，对浅底达 AA。 */
        val Light = ExtendedColors(
            success = Color(0xFF15803D), // Mint 浅色族绿，5.0:1
            warning = Color(0xFFB45309) // 琥珀深档，5.0:1
        )
    }
}

/** 当前扩展色（由 ApexTheme 按明暗注入；未注入时兜底深色组）。 */
val LocalExtendedColors = staticCompositionLocalOf { ExtendedColors.Dark }
