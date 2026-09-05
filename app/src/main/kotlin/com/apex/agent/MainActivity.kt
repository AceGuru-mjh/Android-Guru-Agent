package com.apex.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.apex.agent.ui.ApexRoot
import com.apex.agent.ui.screen.settings.SettingsRepository
import com.apex.agent.ui.theme.ApexTheme
import com.apex.agent.ui.theme.LocalShowTimestamps
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 全局外观由设置中心驱动：主题模式 / 动态取色 / 字体缩放 / 时间戳开关
            val settings by remember { settingsRepository.agentSettings }.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            ApexTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
                // 全局字体缩放：在系统 fontScale 基础上叠加设置中心的缩放系数
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = density.density,
                        fontScale = density.fontScale * settings.fontScale
                    ),
                    LocalShowTimestamps provides settings.showTimestamps
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        ApexRoot()
                    }
                }
            }
        }
    }
}
