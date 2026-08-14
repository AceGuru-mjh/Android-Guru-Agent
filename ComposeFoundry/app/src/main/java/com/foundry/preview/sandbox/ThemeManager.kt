package com.foundry.preview.sandbox

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.foundry.preview.dsl.ThemeConfig

@Composable
fun FoundryTheme(
    themeConfig: ThemeConfig,
    isDarkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val colors = if (isDarkTheme) {
        darkColorScheme(
            primary = themeConfig.primary(),
            secondary = themeConfig.secondary(),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            error = themeConfig.error()
        )
    } else {
        lightColorScheme(
            primary = themeConfig.primary(),
            secondary = themeConfig.secondary(),
            background = themeConfig.background(),
            surface = themeConfig.surface(),
            error = themeConfig.error()
        )
    }

    androidx.compose.material3.MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
