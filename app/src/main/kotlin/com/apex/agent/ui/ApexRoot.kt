package com.apex.agent.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.BuildConfig
import com.apex.agent.R
import com.apex.agent.ui.component.ContextMeterBar
import com.apex.agent.ui.component.FeedbackHost
import com.apex.agent.ui.component.OfflineBanner
import com.apex.agent.ui.component.UpdateBanner
import com.apex.agent.ui.glass.GlassIconButton
import com.apex.agent.ui.screen.about.AboutScreen
import com.apex.agent.ui.screen.agent.AgentChatScreen
import com.apex.agent.ui.screen.agent.AgentChatViewModel
import com.apex.agent.ui.screen.code.CodeScreen
import com.apex.agent.ui.screen.diagnostics.DiagnosticsScreen
import com.apex.agent.ui.screen.glass.GlassLabScreen
import com.apex.agent.ui.screen.log.LogViewerScreen
import com.apex.agent.ui.screen.market.MarketScreen
import com.apex.agent.ui.screen.permissions.PermissionsScreen
import com.apex.agent.ui.screen.settings.SettingsScreen
import com.apex.agent.ui.screen.memory.MemoryScreen
import com.apex.agent.ui.screen.storage.StorageScreen
import com.apex.agent.ui.screen.tasks.TaskHistoryScreen
import com.apex.agent.ui.screen.terminal.TerminalScreen
import com.apex.agent.ui.screen.templates.TemplateStudioScreen
import com.apex.agent.ui.screen.usage.UsageDashboardScreen
import com.apex.agent.ui.screen.vault.VaultScreen
import com.apex.agent.update.UpdateCenter
import com.apex.agent.service.StartupWatchdog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 抽屉导航目标
 *
 * labelRes：目的地名称的字符串资源（i18n，经 stringResource 取词；
 * 原硬编码中文 label 已迁移 values[-zh]/strings_core.xml 的 drawer_* key）。
 */
sealed class DrawerDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    data object Agent : DrawerDestination("agent", R.string.drawer_agent, Icons.Default.SmartToy)
    // Coding 模式（与 Agent 模式同级别）：独立引擎实例 + 工作区 + code_* 工具集，
    // 与 Agent 模式共享 ToolRegistry/Skills/MCP/插件 —— 能力互用。
    data object Code : DrawerDestination("code", R.string.drawer_code, Icons.Default.Code)
    // #197 模板工坊：Agent/Coding 双层模板 + Agent 角色详细设置的独立页面入口。
    data object Templates : DrawerDestination("templates", R.string.drawer_templates, Icons.AutoMirrored.Filled.ListAlt)
    data object Terminal : DrawerDestination("terminal", R.string.drawer_terminal, Icons.Default.Terminal)
    // Skill 屏已移除 —— 技能的安装/启停统一由「市场 · Skills」页承担，
    // 抽屉里再放一个只读列表是重复入口（两者数据源同一份 SkillRegistry）。
    data object Market : DrawerDestination("market", R.string.drawer_market, Icons.Default.Storefront)
    data object Memory : DrawerDestination("memory", R.string.drawer_memory, Icons.Default.Storage)
    // 任务历史（T76 审计 §7：TaskRuntime.loadTaskHistory 首次接线 —— 步骤回放/统计）
    data object Tasks : DrawerDestination("tasks", R.string.drawer_tasks, Icons.Default.Checklist)
    // 存储与数据管理（附件/rootfs 占用/会话历史导出清空 —— 均为真实数据源）
    data object Storage : DrawerDestination("storage", R.string.drawer_storage, Icons.Default.FolderOpen)
    data object Permissions : DrawerDestination("permissions", R.string.drawer_permissions, Icons.Default.Security)
    // #167 加密剪切板金库：人工储放 GitHub token / AI 密钥，Agent 只见标签不见明文
    data object Vault : DrawerDestination("vault", R.string.drawer_vault, Icons.Default.Lock)
    data object Log : DrawerDestination("log", R.string.drawer_log, Icons.Filled.Info)
    data object Settings : DrawerDestination("settings", R.string.drawer_settings, Icons.Default.Settings)
    // 玻璃实验室 —— 内部 Liquid Glass 验收页（Spec §20：背景变化/网格/高对比文字/移动元素）
    data object GlassLab : DrawerDestination("glasslab", R.string.drawer_glasslab, Icons.Default.BlurOn)
    // v1.4.4 #6：用量仪表盘 —— 按天/按模型/按会话的 token 消耗分解（持久化账本）
    data object Usage : DrawerDestination("usage", R.string.drawer_usage, Icons.Default.DataUsage)
    // v1.4.4 #7：诊断中心 —— 应用/设备信息 + 崩溃记录 + 日志文件 + 一键诊断包
    data object Diagnostics : DrawerDestination("diagnostics", R.string.drawer_diagnostics, Icons.Default.BugReport)
    // 关于页 —— 固定在抽屉最下方的独立入口（v1.4.3：从设置页「关于」区升级为一级页面；
    // 图标用 Outlined 与 Log 页的 Filled.Info 区分）
    data object About : DrawerDestination("about", R.string.drawer_about, Icons.Outlined.Info)
}

// #224：导航状态与 route 映射已抽至 NavigationBackStack.kt（含
// rememberSaveable Saver 与 destinationFromRoute 容错映射）——
// 单值 currentDestination 无法表达「来源页」，返回栈需要 current +
// history 两份持久化状态，独立成文件保持 ApexRoot 精简。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApexRoot() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // #224：真实导航返回栈 —— current + history 一并 rememberSaveable
    // （route 列表经 Saver 往返，旋转/进程重建不丢）。取代 P2-5 的单值
    // currentDestination：单值无法表达「来源页」，返回被硬编码回 Agent。
    // 语义：抽屉一级导航 = 替换（清栈）；应用内前进（去配置/看更新）= 压栈；
    // 返回 = 弹栈，栈空兜底回 Agent。详见 NavigationBackStack.kt。
    val navStack = rememberNavigationBackStack()
    val currentDestination = navStack.current
    // #224（症状②）：按 route 隔离各目的地 rememberSaveable 状态 ——
    // 切页时旧屏不再随 when 分支整体离开组合而丢失滚动位置/展开态。
    val destinationStateHolder = rememberSaveableStateHolder()

    // 上下文仪表盘数据源（单例作用域 VM，全局共享）
    val agentVm: AgentChatViewModel = hiltViewModel()
    val agentState by agentVm.uiState.collectAsStateWithLifecycle()

    // ═══ v1.4.4 #6：全局网络状态 —— 离线横幅（所有页面顶部）═══
    val isOnline by agentVm.networkMonitor.isOnline.collectAsStateWithLifecycle()

    // ═══ v1.4.5：新版本浮窗（非强制）—— App 启动静默检查发现新版时顶部提醒 ═══
    val updateBanner by UpdateCenter.bannerVisible.collectAsStateWithLifecycle()

    // ═══ v1.4.5：启动即检查更新（UpdateCenter 内部 6h 节流；进程重建不重复拉）═══
    // v1.4.9 闪退防御：整体 runCatching —— requireContext 在中枢未就绪时
    // error() 抛 ISE，裸 LaunchedEffect 协程异常同样杀死进程。
    LaunchedEffect(Unit) {
        runCatching {
            UpdateCenter.checkForUpdate(
                currentVersionCode = BuildConfig.VERSION_CODE,
                force = false,
                fromTrigger = "app-start"
            )
        }.onFailure {
            android.util.Log.w("ApexRoot", "startup update check failed: ${it.message}")
        }
    }

    // ═══ v1.4.9：启动看门狗稳定窗口 —— 主 UI 存活 20s 即宣告稳定 ═══
    // ApexRoot 首次组合存活至此，说明首帧/VM 构造/init 协程均已扛过最危险
    // 窗口；清零看门狗连续崩溃计数（此后崩溃属普通崩溃，不再触发自愈梯度）。
    val watchdogContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        delay(StartupWatchdog.STABLE_MS)
        runCatching { StartupWatchdog.noteMainUiStable(watchdogContext) }
    }

    // ═══ UX-2 / #224：系统返回键导航链 ═══
    // 先沿 #224 历史栈回退（聊天页「去配置」→设置→改完返回 = 回聊天上下文，
    // 不再被强制拽回 Agent）；栈空（抽屉直入的一级页）保持 UX-2 既有行为：
    // 回 Agent 聊天主页。Agent 页不拦截（交系统默认行为）。
    // 注意组合顺序：BackHandler 后组合者先消费（LIFO）——抽屉关闭器放在
    // 目标回退之后组合，保证抽屉打开时优先只关抽屉，不再连带跳页。
    val navigateBack: () -> Unit = {
        if (!navStack.pop()) navStack.navigateToTopLevel(DrawerDestination.Agent)
    }
    BackHandler(enabled = currentDestination != DrawerDestination.Agent) {
        navigateBack()
    }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // ═══ v1.4.4 #6：统一反馈层 —— 全树提供 LocalFeedbackController ═══
    // 包在最外层：任何页面的 Toast/Snackbar 反馈都走统一视觉（严重级配色 +
    // SnackbarHost 底部弹出）。子树未消费无副作用；Noop 默认保证预览可用。
    FeedbackHost {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ApexDrawerContent(
                currentDestination = currentDestination,
                onDestinationSelected = { dest ->
                    // #224：抽屉一级导航 = 替换语义（清空历史栈 ——
                    // 顶层切换不产生返回层级，标准 drawer/bottom-nav 惯例）
                    navStack.navigateToTopLevel(dest)
                    scope.launch { drawerState.close() }
                },
                tokenManager = agentVm.githubTokenManager
            )
        }
    ) {
        Scaffold(
            // 修复：edge-to-edge 后 adjustResize 失效，键盘弹出会直接盖住输入栏 ——
            // 将 IME insets 并入内容内边距，键盘弹出时整个内容区（含底部输入栏）上移。
            contentWindowInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // ★ 修复（终端页「输入框挡住命令」的 API<30 档配套）：API 30+ 沿用
                //   edge-to-edge 的 ime insets 动画通道；API<30 走经典 decor-fits
                //   （MainActivity 同步开关），装饰层已物理避让系统栏且窗口随键盘
                //   resize —— 这里传零 insets，避免 Compose 侧再加一份系统栏边距。
                WindowInsets.systemBars.union(WindowInsets.ime)
            } else {
                WindowInsets(0, 0, 0, 0)
            },
            topBar = {
                // 终端屏自带二级顶栏（含终端抽屉入口）——若此处再渲染根顶栏，会出现双顶栏双汉堡
                if (currentDestination != DrawerDestination.Terminal) {
                    TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        currentDestination.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = stringResource(currentDestination.labelRes),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    navigationIcon = {
                        // 顶栏菜单钮 → GlassIconButton —— Frosted 档：
                        // 顶栏无内容可采样，诚实降级为主题薄霜 + 边缘光 + 高光
                        GlassIconButton(
                            icon = Icons.Default.Menu,
                            contentDescription = stringResource(R.string.drawer_open_nav),
                            onClick = { scope.launch { drawerState.open() } },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary
                    )
                )
                }
            }
        ) { padding ->
            // P2-4（6-c）：Scaffold 已用 contentWindowInsets = systemBars.union(ime)
            // 把 IME insets 并入内容内边距，此处再 .imePadding() 会双重叠加（键盘弹出
            // 时输入栏被抬得过高）。去掉冗余 .imePadding()，保留 contentWindowInsets 路径。
            Column(modifier = Modifier.padding(padding)) {
                // ═══ v1.4.4 #6：离线横幅（断网即现，恢复即隐；不影响任何页面布局）═══
                OfflineBanner(isOnline = isOnline)

                // ═══ v1.4.5：新版本浮窗（非强制 —— 检测到新版且未忽略时顶部提醒）═══
                UpdateBanner(
                    visible = updateBanner,
                    // #224：压栈语义 —— 「查看」进关于页后按返回回原页
                    onOpenAbout = { navStack.push(DrawerDestination.About) }
                )

                // ═══ 顶部上下文仪表盘长条（全局）═══
                // T89：终端页隐藏 —— Agent token 用量与终端会话无关，却吃掉
                // 终端竖屏 20dp 高度（终端页本就被键区挤压，条带堆叠是「页面
                // 一坨」的直接成因之一）。其余页面不受影响。
                if (currentDestination != DrawerDestination.Terminal) {
                    ContextMeterBar(
                        usedTokens = agentState.contextUsedTokens,
                        maxTokens = agentState.contextMaxTokens,
                        sessionTotalTokens = agentState.sessionTotalTokens,
                        onCompress = { agentVm.compressNow() }
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // #224（症状②）：SaveableStateProvider 以 route 为 key 隔离并
                    // 保留各目的地 rememberSaveable 状态（滚动位置/展开态）——
                    // 切页不再随 when 分支整体离开组合而蒸发，返回来源页原样恢复。
                    destinationStateHolder.SaveableStateProvider(currentDestination.route) {
                        when (currentDestination) {
                            DrawerDestination.Agent -> AgentChatScreen(
                                // "小大脑"菜单 → 配置模型：跳转设置页模型配置区
                                // （Models 区块默认展开且在设置页顶部，天然满足自动定位）
                                // #224：改压栈语义 —— 改完按返回回到聊天上下文，
                                // 不再被强制拽回 Agent
                                onOpenSettings = { navStack.push(DrawerDestination.Settings) }
                            )
                            DrawerDestination.Code -> CodeScreen(
                                viewModel = hiltViewModel()
                            )
                            DrawerDestination.Templates -> TemplateStudioScreen()
                            DrawerDestination.Terminal -> TerminalScreen(
                                onOpenNavDrawer = { scope.launch { drawerState.open() } }
                            )
                            DrawerDestination.Market -> MarketScreen()
                            DrawerDestination.Memory -> MemoryScreen()
                            DrawerDestination.Tasks -> TaskHistoryScreen()
                            DrawerDestination.Storage -> StorageScreen()
                            DrawerDestination.Permissions -> PermissionsScreen()
                            DrawerDestination.Vault -> VaultScreen()
                            DrawerDestination.Log -> LogViewerScreen()
                            DrawerDestination.Usage -> UsageDashboardScreen()
                            DrawerDestination.Diagnostics -> DiagnosticsScreen()
                            DrawerDestination.Settings -> SettingsScreen(
                                // P2-6（6-c）：最小修复双顶栏返回链——SettingsScreen 自带
                                // TopAppBar 的返回键原为空操作（默认 onBack={}）。
                                // #224：顶栏返回与系统返回统一语义 —— 先弹历史栈回
                                // 来源页（如聊天页），栈空兜底回 Agent。
                                onBack = navigateBack
                            )
                            DrawerDestination.GlassLab -> GlassLabScreen()
                            DrawerDestination.About -> AboutScreen()
                        }
                    }
                }
            }
        }
    }
    }
}
