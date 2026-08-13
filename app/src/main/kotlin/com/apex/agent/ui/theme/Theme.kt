package com.apex.agent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 美学方向：Terminal-native AI OS
 * 深色优先，青绿霓虹 (mint) 作主色、琥珀 (amber) 作强调、品红 (magenta) 作危险态。
 * 近黑带蓝的基底 + 玻璃拟态容器，营造"运行中的 AI 终端"质感。
 */

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4EE9B0),          // neon mint
    onPrimary = Color(0xFF00251A),
    primaryContainer = Color(0xFF0C3A2C),
    onPrimaryContainer = Color(0xFF9CF3D2),
    secondary = Color(0xFFFFB454),        // amber accent
    onSecondary = Color(0xFF3A2400),
    secondaryContainer = Color(0xFF432D00),
    onSecondaryContainer = Color(0xFFFFD89E),
    tertiary = Color(0xFFFF6B9D),         // magenta (danger / highlight)
    onTertiary = Color(0xFF3D0018),
    tertiaryContainer = Color(0xFF52122E),
    onTertiaryContainer = Color(0xFFFFB3CE),
    background = Color(0xFF0A0E14),       // near-black w/ blue tint
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0F141C),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C2530),
    onSurfaceVariant = Color(0xFF9DA9B8),
    surfaceContainerLowest = Color(0xFF080B11),
    surfaceContainerLow = Color(0xFF0F141C),
    surfaceContainer = Color(0xFF141B24),
    surfaceContainerHigh = Color(0xFF1C2530),
    surfaceContainerHighest = Color(0xFF26313D),
    outline = Color(0xFF324150),
    outlineVariant = Color(0xFF223040),
    error = Color(0xFFFF6B9D),
    onError = Color(0xFF3D0018),
    errorContainer = Color(0xFF52122E),
    onErrorContainer = Color(0xFFFFB3CE),
    scrim = Color(0xFF05070B)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0E7A57),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB4F3DC),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF8A5300),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDDA8),
    onSecondaryContainer = Color(0xFF2E1A00),
    tertiary = Color(0xFFB33A65),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E4),
    onTertiaryContainer = Color(0xFF3F0018),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF10161D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10161D),
    surfaceVariant = Color(0xFFE3E8EE),
    onSurfaceVariant = Color(0xFF4A5562),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F7),
    surfaceContainer = Color(0xFFEBEFF3),
    surfaceContainerHigh = Color(0xFFE5EAEF),
    surfaceContainerHighest = Color(0xFFDFE5EB),
    outline = Color(0xFFC2CAD3),
    outlineVariant = Color(0xFFD9DFE6),
    error = Color(0xFFBA1A4A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9E4),
    onErrorContainer = Color(0xFF3F0018),
    scrim = Color(0xFF0A0E14)
)

@Composable
fun ApexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ApexTypography,
        content = content
    )
}
