package com.apex.agent

import android.content.Intent
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
import androidx.core.content.ContextCompat
import com.apex.agent.service.ApexCoreService
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
        // P1-3（6-c）：ApexCoreService 此前仅 BootReceiver（BOOT/MY_PACKAGE_REPLACED）拉起，
        // 全新安装直到重启前核心前台服务从未运行——BrowserOverlay/CyberNeonBall 的
        // WAITING_HUMAN 接管浮窗永远不注册。在前台 Activity 创建时幂等启动（服务已在跑
        // 时重复 startForegroundService 仅再次 onStartCommand，无害；前台 Activity 不受
        // Android 12+ 后台 FGS 启动限制；Manifest 已声明 FOREGROUND_SERVICE(_SPECIAL_USE)
        // + specialUse 类型 + PROPERTY_SPECIAL_USE_FGS_SUBTYPE，服务 onCreate 建 channel、
        // onStartCommand 立即 startForeground，满足 5s 前台化窗口）。
        ContextCompat.startForegroundService(this, Intent(this, ApexCoreService::class.java))
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
