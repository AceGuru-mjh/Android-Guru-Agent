package com.apex.agent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 美学方向：Terminal-native AI OS
 * 深色优先，多套预设配色（默认霓虹薄荷：青绿霓虹主色、琥珀强调、品红危险态）。
 * 近黑带蓝的基底 + 玻璃拟态容器，营造"运行中的 AI 终端"质感。
 *
 * 配色数据与方案定义见 [ThemePalettes.kt]（中性基底共享，强调色按方案切换）。
 *
 * ## 界面风格维度（UiStyle）
 *
 * [uiStyle] 是与深浅模式正交的风格开关：
 *  - [UiStyle.MINIMAL]（默认）：极简黑白 —— [minimalColorScheme] 纯灰阶，
 *    Dynamic Color / 预设配色不参与；
 *  - [UiStyle.LIQUID_GLASS]：液态玻璃 —— 既有 neutral base + accent 体系
 *    （Dynamic Color / 18 套预设照常生效）。
 *
 * 风格经 [LocalUiStyle] 下发；玻璃体系的扁平化短路收敛在
 * GlassSurface 单点（业务组件零感知）。
 */
@Composable
fun ApexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    accentPalette: AccentPalette = AccentPalette.MINT,
    uiStyle: UiStyle = UiStyle.MINIMAL,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // 极简黑白：纯灰阶基底，忽略 Dynamic Color / 预设配色
        uiStyle == UiStyle.MINIMAL -> minimalColorScheme(darkTheme)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        // 预设配色：中性基底 + 方案强调色（MINT 与旧版单配色逐字段等价）
        else -> accentColorScheme(accentPalette, darkTheme)
    }

    // UI-016 基建：success / warning 扩展语义色随明暗注入（独立于
    // accent 派生，dynamicColor 路径同样适用）
    val extendedColors = if (darkTheme) ExtendedColors.Dark else ExtendedColors.Light

    CompositionLocalProvider(
        LocalUiStyle provides uiStyle,
        LocalExtendedColors provides extendedColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ApexTypography,
            content = content
        )
    }
}

/** 是否在聊天气泡旁显示时间戳（由设置中心驱动，MainActivity 提供）。 */
val LocalShowTimestamps = staticCompositionLocalOf { true }
