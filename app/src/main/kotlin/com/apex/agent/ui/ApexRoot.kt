package com.apex.agent.ui

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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.ui.component.ContextMeterBar
import com.apex.agent.ui.glass.GlassIconButton
import com.apex.agent.ui.screen.agent.AgentChatScreen
import com.apex.agent.ui.screen.agent.AgentChatViewModel
import com.apex.agent.ui.screen.glass.GlassLabScreen
import com.apex.agent.ui.screen.log.LogViewerScreen
import com.apex.agent.ui.screen.market.MarketScreen
import com.apex.agent.ui.screen.permissions.PermissionsScreen
import com.apex.agent.ui.screen.settings.SettingsScreen
import com.apex.agent.ui.screen.memory.MemoryScreen
import com.apex.agent.ui.screen.terminal.TerminalScreen
import kotlinx.coroutines.launch

/**
 * 抽屉导航目标
 */
sealed class DrawerDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Agent : DrawerDestination("agent", "Agent", Icons.Default.SmartToy)
    data object Terminal : DrawerDestination("terminal", "终端", Icons.Default.Terminal)
    // Skill 屏已移除 —— 技能的安装/启停统一由「市场 · Skills」页承担，
    // 抽屉里再放一个只读列表是重复入口（两者数据源同一份 SkillRegistry）。
    data object Market : DrawerDestination("market", "市场", Icons.Default.Storefront)
    data object Memory : DrawerDestination("memory", "记忆", Icons.Default.Storage)
    data object Permissions : DrawerDestination("permissions", "权限", Icons.Default.Security)
    data object Log : DrawerDestination("log", "运行日志", Icons.Filled.Info)
    data object Settings : DrawerDestination("settings", "设置", Icons.Default.Settings)
    // 玻璃实验室 —— 内部 Liquid Glass 验收页（Spec §20：背景变化/网格/高对比文字/移动元素）
    data object GlassLab : DrawerDestination("glasslab", "玻璃实验室", Icons.Default.BlurOn)
}

/**
 * P2-5（6-c）：[DrawerDestination] 的 rememberSaveable Saver——DrawerDestination
 * 是 sealed class（非 enum，无 name/entries），以 route 字符串往返映射；
 * 未知 route 兜底回 Agent。
 */
private val DestinationSaver = Saver<DrawerDestination, String>(
    save = { it.route },
    restore = { route ->
        when (route) {
            DrawerDestination.Terminal.route -> DrawerDestination.Terminal
            // "skill" route 保留兜底：老用户重建时若停留在原 Skill 页，落到市场
            "skill" -> DrawerDestination.Market
            DrawerDestination.Market.route -> DrawerDestination.Market
            DrawerDestination.Memory.route -> DrawerDestination.Memory
            DrawerDestination.Permissions.route -> DrawerDestination.Permissions
            DrawerDestination.Log.route -> DrawerDestination.Log
            DrawerDestination.Settings.route -> DrawerDestination.Settings
            else -> DrawerDestination.Agent
        }
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApexRoot() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // P2-5（6-c）：原 remember → 旋转/进程重建丢失当前页面（navigation-compose
    // 依赖声明了却从未使用）。改 rememberSaveable（route 经 Saver 往返）。
    var currentDestination by rememberSaveable(stateSaver = DestinationSaver) {
        mutableStateOf(DrawerDestination.Agent)
    }

    // 上下文仪表盘数据源（单例作用域 VM，全局共享）
    val agentVm: AgentChatViewModel = hiltViewModel()
    val agentState by agentVm.uiState.collectAsStateWithLifecycle()

    // ═══ UX-2：系统返回键导航链 ═══
    // 非抽屉一级页（Settings/Terminal/Skill…）按返回 → 回 Agent 聊天主页；
    // Agent 页不拦截（交系统默认行为）。currentDestination 为 rememberSaveable
    // （P2-5 已修），route 经 DestinationSaver 往返，返回后旋转/重建不丢。
    // 注意组合顺序：BackHandler 后组合者先消费（LIFO）——抽屉关闭器放在
    // 目标回退之后组合，保证抽屉打开时优先只关抽屉，不再连带跳页。
    BackHandler(enabled = currentDestination != DrawerDestination.Agent) {
        currentDestination = DrawerDestination.Agent
    }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ApexDrawerContent(
                currentDestination = currentDestination,
                onDestinationSelected = { dest ->
                    currentDestination = dest
                    scope.launch { drawerState.close() }
                },
                tokenManager = agentVm.githubTokenManager
            )
        }
    ) {
        Scaffold(
            // 修复：edge-to-edge 后 adjustResize 失效，键盘弹出会直接盖住输入栏 ——
            // 将 IME insets 并入内容内边距，键盘弹出时整个内容区（含底部输入栏）上移。
            contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
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
                                text = currentDestination.label,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    navigationIcon = {
                        // 顶栏菜单钮 → GlassIconButton —— Frosted 档：
                        // 顶栏无内容可采样，诚实降级为主题薄霜 + 边缘光 + 高光
                        GlassIconButton(
                            icon = Icons.Default.Menu,
                            contentDescription = "打开导航",
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
                // ═══ 顶部上下文仪表盘长条（全局）═══
                ContextMeterBar(
                    usedTokens = agentState.contextUsedTokens,
                    maxTokens = agentState.contextMaxTokens,
                    onCompress = { agentVm.compressNow() }
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    when (currentDestination) {
                        DrawerDestination.Agent -> AgentChatScreen(
                            // "小大脑"菜单 → 配置模型：跳转设置页模型配置区
                            // （Models 区块默认展开且在设置页顶部，天然满足自动定位）
                            onOpenSettings = { currentDestination = DrawerDestination.Settings }
                        )
                        DrawerDestination.Terminal -> TerminalScreen(
                            onOpenNavDrawer = { scope.launch { drawerState.open() } }
                        )
                        DrawerDestination.Market -> MarketScreen()
                        DrawerDestination.Memory -> MemoryScreen()
                        DrawerDestination.Permissions -> PermissionsScreen()
                        DrawerDestination.Log -> LogViewerScreen()
                        DrawerDestination.Settings -> SettingsScreen(
                            // P2-6（6-c）：最小修复双顶栏返回链——SettingsScreen 自带
                            // TopAppBar 的返回键原为空操作（默认 onBack={}）；接回 Agent 页。
                            onBack = { currentDestination = DrawerDestination.Agent }
                        )
                        DrawerDestination.GlassLab -> GlassLabScreen()
                    }
                }
            }
        }
    }
}
