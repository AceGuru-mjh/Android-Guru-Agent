package com.foundry.preview.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.foundry.preview.dsl.ThemeConfig
import com.foundry.preview.dsl.UiDocument
import com.foundry.preview.engine.DiagnosticsEngine
import com.foundry.preview.engine.UiRenderer

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

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}

@Composable
fun PreviewSurface(
    document: UiDocument?,
    deviceWidth: Int,
    deviceHeight: Int,
    diagnostics: DiagnosticsEngine
) {
    Box(
        modifier = Modifier
            .size(width = deviceWidth.dp, height = deviceHeight.dp)
            .border(2.dp, Color.Gray)
            .background(Color.White)
    ) {
        if (document != null) {
            FoundryTheme(
                themeConfig = document.theme,
                isDarkTheme = false
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(document.theme.background())
                ) {
                    UiRenderer(document.root, document.theme)
                }
            }
        } else if (diagnostics.hasErrors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFFEBEE)),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.material3.Text(
                    text = "❌ ${diagnostics.errorCount} error(s)\nCheck diagnostics panel",
                    color = Color.Red
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.material3.Text(
                    text = "📝 Enter DSL and click Render",
                    color = Color.Gray
                )
            }
        }
    }
}
