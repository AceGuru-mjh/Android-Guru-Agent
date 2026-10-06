package com.foundry.preview.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.foundry.preview.dsl.UiDocument
import com.foundry.preview.engine.DiagnosticsEngine
import com.foundry.preview.engine.UiRenderer

// 注：FoundryTheme 的唯一定义在 ThemeManager.kt（含 material3 import）。
// 此处旧副本与它完全重复且缺 darkColorScheme/lightColorScheme import，
// 重复定义导致重载冲突 —— #275 编译门禁修复时删除。

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
