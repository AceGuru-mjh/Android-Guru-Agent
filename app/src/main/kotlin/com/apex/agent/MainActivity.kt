package com.apex.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.lifecycle.lifecycleScope
import com.apex.agent.service.ApexCoreService
import com.apex.agent.service.CoreServiceGate
import com.apex.agent.share.SharedIntake
import com.apex.agent.ui.ApexRoot
import com.apex.agent.ui.language.LanguageManager
import com.apex.agent.ui.screen.onboarding.OnboardingScreen
import com.apex.agent.ui.screen.settings.SettingsRepository
import com.apex.agent.ui.theme.AccentPalette
import com.apex.agent.ui.theme.ApexTheme
import com.apex.agent.ui.theme.LocalShowTimestamps
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var languageManager: LanguageManager

    /** v1.4.4 #7：分享接收中转（ACTION_SEND → Agent 输入区）。 */
    @Inject
    lateinit var sharedIntake: SharedIntake

    /** P0-1（#223/TerminalRuntime 生命周期接线）：Activity 是运行时的前台宿主
     * ——isFinishing 且 Keep Alive 关闭时负责优雅收尾（详见 onDestroy）。 */
    @Inject
    lateinit var terminalRuntime: com.apex.agent.platform.terminal.runtime.TerminalRuntime

    /** attachBaseContext 时静态读出的已应用语言（system/zh/en）；供语言变化 recreate 判定。 */
    private var appliedLanguage: String? = null

    override fun attachBaseContext(newBase: Context) {
        // 语言切换基建：attachBaseContext 早于 onCreate / Hilt 字段注入，不能用注入的
        // LanguageManager —— 用其 companion 静态快读 SharedPreferences（apex_settings
        // 的 agent_settings_v2 JSON 里 language 字段），再 createConfigurationContext
        // 包裹对应 Locale；system 则原样返回（交系统 locale）。
        val lang = LanguageManager.resolveLanguageFromPrefs(newBase)
        appliedLanguage = lang
        super.attachBaseContext(LanguageManager.applyLanguage(newBase, lang))
    }

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
        // 二轮审计 A-6：companion 静态首启标记 —— 进程存活期只拉起一次，避免每次
        // 旋转重建都触发 onStartCommand 的系统噪音（通知/日志）。进程重启或服务被
        // 系统杀死后首次重建会再次拉起，语义不受影响。
        if (!CoreServiceGate.startedThisProcess) {
            CoreServiceGate.markStarted()
            ContextCompat.startForegroundService(this, Intent(this, ApexCoreService::class.java))
        }

        // ═══ v1.4.4 #7：分享接收（冷启动路径）═══
        // 图库/浏览器分享 → 系统拉起本 Activity → intent 在此入队；
        // AgentChatViewModel init 订阅消费（文本预填草稿 + 图片即时拷沙箱）。
        // 必须在 setContent 之前：VM 在首次组合时创建，晚于这里即不丢事件。
        sharedIntake.offer(intent)

        // ═══ v1.4.4 #7：温暖路径（App 已在运行时被分享唤起，singleTop 复用实例）═══
        // 用 OnNewIntentProvider（ComponentActivity 官方通道）而非覆写
        // onNewIntent —— 后者参数可空性注解在 androidx.activity 各版本间
        // 不一致，直接覆写有签名不匹配风险；listener 形态零歧义且无需 setIntent。
        addOnNewIntentListener { intent -> sharedIntake.offer(intent) }

        // ═══ v1.4.4 #4：电池优化白名单引导（一次性）═══
        // keepAlive 默认开（前台服务常驻语义）；厂商 ROM 的电池优化会在后台杀
        // 服务导致长任务中断。Manifest 已声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，
        // 首启且未在白名单时发起系统豁免请求；用户拒绝过就不再骚扰（SP 记问）。
        maybeRequestBatteryOptimizationExemption()

        // 语言切换：设置中心 language 与当前已应用语言不同 → recreate 重新走
        // attachBaseContext（新实例以新语言包裹，stringResource 即时取新资源）。
        // 首帧发射值 == attachBaseContext 读到的持久化值，不会误重建。
        lifecycleScope.launch {
            languageManager.language.collect { lang ->
                if (lang != appliedLanguage) {
                    appliedLanguage = lang
                    recreate()
                }
            }
        }
        setContent {
            // 全局外观由设置中心驱动：主题模式 / 动态取色 / 字体缩放 / 时间戳开关
            val settings by remember { settingsRepository.agentSettings }.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            ApexTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                accentPalette = AccentPalette.fromKey(settings.accentPalette)
            ) {
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
                        // 新手引导：首次启动（或升级后首次）先走四页 Onboarding，
                        // 完成标记持久化在 SettingsRepository.onboardingCompleted。
                        if (settings.onboardingCompleted) {
                            ApexRoot()
                        } else {
                            OnboardingScreen(
                                onFinished = {
                                    settingsRepository.updateAgentSettings {
                                        copy(onboardingCompleted = true)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // ═══ P0-1（#223）：TerminalRuntime 生命周期接线 ═══
        // TerminalRuntime.shutdown()（cancel 全部 job → close 全部 session →
        // nativeCloseAll 兜底 → 停 pump/协程域）此前在生产代码零调用 —— 用户
        // 退出 app 后 fd/Job 只能等进程死亡由内核回收，会话元数据依赖 2s
        // autosave 兜底（#223「崩溃恢复是空操作」的直接根因）。
        // 分工：Keep Alive **关**时本 Activity 是运行时宿主 —— isFinishing 即
        // 收尾；Keep Alive **开**时交由 ApexCoreService.onDestroy（服务在
        // 后台被停时收尾）—— 避免双重宿主语义打架。
        if (isFinishing && !CoreServiceGate.keepAliveEnabled(this)) {
            CoreServiceGate.markStopped()
            runCatching { stopService(Intent(this, ApexCoreService::class.java)) }
            gracefulShutdownTerminalRuntime()
        }
        super.onDestroy()
    }

    /** 优雅收尾终端运行时：独立作用域 + 10s 有界等待（幂等 —— runtime 内部
     * shutdownGate 保证重复调用直接返回首次结果）。失败仅记日志：收尾是
     * 增益路径，不允许炸 UI 生命周期。 */
    private fun gracefulShutdownTerminalRuntime() {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        ).launch {
            runCatching {
                kotlinx.coroutines.withTimeout(10_000L) { terminalRuntime.shutdown() }
            }.onSuccess { result ->
                result.getOrNull()?.let {
                    android.util.Log.i(
                        "MainActivity",
                        "terminal runtime shutdown: sessionsClosed=${it.sessionsClosed}, " +
                            "jobsCancelled=${it.jobsCancelled}, clean=${it.clean}"
                    )
                }
            }.onFailure {
                android.util.Log.w("MainActivity", "terminal runtime shutdown failed: ${it.message}")
            }
        }
    }

    /**
     * v1.4.4 #4：电池优化豁免引导 —— 仅首启一次（用户拒绝即不再问）。
     * 全链路 runCatching：引导是增益路径，任何 ROM 差异都不阻断启动。
     */
    private fun maybeRequestBatteryOptimizationExemption() {
        runCatching {
            val prefs = getSharedPreferences(BATTERY_ASK_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_ASKED, false)) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                prefs.edit().putBoolean(KEY_ASKED, true).apply()
                return
            }
            prefs.edit().putBoolean(KEY_ASKED, true).apply()
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    companion object {
        /** v1.4.4 #4：电池优化引导一次性标记（拒绝过不再问）。 */
        private const val BATTERY_ASK_PREFS = "apex_one_shot_flags"
        private const val KEY_ASKED = "battery_optim_asked"
    }
}
