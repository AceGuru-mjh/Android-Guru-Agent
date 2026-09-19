package com.apex.agent.ui.screen.terminal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 终端页 v2（T85 — Apex Console 重做）。
 *
 * 旧版布局问题（用户实测反馈）：顶栏 + tab 条 + 状态条 + Ubuntu 横幅四层堆叠，
 * 浅色 Material 顶栏与深色终端区割裂；「未解包」横幅等用户行动而不是自动预备。
 *
 * 新设计（对齐 Termux / JuiceSSH 的「控制台优先」范式）：
 *  - **整页深色控制台**（ConsoleTheme，薄荷强调色对齐 App neon-mint 主题），
 *    顶栏 / 会话条 / 状态条与终端区视觉连续，不再浅深割裂；
 *  - **顶栏**：汉堡 + 标题 + 当前后端副标题（mono），动作区 = 新建 / 环境中心 / 终端设置；
 *  - **会话条**：仅多会话（≥2）时出现 —— 单会话零干扰（Termux 同款哲学）；
 *  - **状态条**：细 mono 行（会话状态 / 前台 job / 等待输入 / 反馈）；
 *  - **主区**：有会话 → 全幅终端 grid；无会话 → [EnvironmentPanel]（环境准备
 *    进度 / 失败重试 / 空态快捷入口）—— 取代旧的「未解包横幅 + 终端未启动占位」
 *    双重死区；
 *  - **环境自动预备**：ApexApp 启动即后台解包（T85），READY 后 ViewModel 自动
 *    拉起 Ubuntu 会话 —— 用户进页即见真实 bash，零点击。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onOpenNavDrawer: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val semantic by viewModel.semanticState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ubuntu by viewModel.ubuntuLifecycleState.collectAsStateWithLifecycle()
    val ubuntuProgress by viewModel.ubuntuProgress.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val blacklist by viewModel.blacklist.collectAsStateWithLifecycle()
    val whitelist by viewModel.whitelist.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var showEnvironmentCenter by remember { mutableStateOf(false) }

    // 保持屏幕常亮：看长任务输出（编译 / apt / 训练日志）时不被息屏打断 ——
    // Termux 默认持有 wakelock，这里用等价的 window flag，交给用户开关。
    val keepScreenOn = settings.keepScreenOn
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(keepScreenOn) {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            if (keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            // 离屏必须还原：否则终端页退出后整 App 一直亮屏耗电
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 环境中心打开时刷新一次占用（安装/删除后状态驱动刷新，这里兑底）
    LaunchedEffect(showEnvironmentCenter) {
        if (showEnvironmentCenter) viewModel.refreshRootfsSize()
    }

    // 反馈条自动消隐
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5000)
            viewModel.consumeNotice()
        }
    }

    val activeTab = sessions.firstOrNull { it.id == activeId }
    val hasSession = activeId != null && activeTab != null

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            TerminalSettingsDrawer(
                settings = settings,
                onSettings = viewModel::updateSettings,
                blacklist = blacklist,
                whitelist = whitelist,
                onAddBlack = viewModel::addBlacklist,
                onRemoveBlack = viewModel::removeBlacklist,
                onAddWhite = viewModel::addWhitelist,
                onRemoveWhite = viewModel::removeWhitelist,
                onClose = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ConsoleTheme.bg)
        ) {
            // ═══ 顶栏（深色控制台；外层 Scaffold 已提供 status bar inset）═══
            ConsoleTopBar(
                activeTab = activeTab,
                ubuntuPhase = ubuntu.phase,
                ubuntuPercent = ubuntuProgress?.percent ?: 0,
                onOpenNavDrawer = onOpenNavDrawer,
                onNewSession = { showNewSessionDialog = true },
                onOpenCenter = { showEnvironmentCenter = true },
                onOpenSettings = { scope.launch { drawerState.open() } }
            )

            // ═══ 会话条（≥2 个会话才显示 —— 单会话零干扰）═══
            if (sessions.size >= 2) {
                SessionStrip(
                    sessions = sessions,
                    activeId = activeId,
                    onSelect = viewModel::selectSession,
                    onClose = viewModel::closeSession
                )
            }

            // ═══ 状态条（有活跃会话时；细 mono 行）═══
            if (semantic != null) {
                StatusStrip(semantic = semantic, notice = notice)
            }

            // ═══ 主区：终端 / 环境面板 ═══
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (hasSession) {
                    TerminalRenderer(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    EnvironmentPanel(
                        phase = ubuntu.phase,
                        progress = ubuntuProgress,
                        lastError = ubuntu.lastError,
                        onRetry = viewModel::installUbuntu,
                        onNewUbuntu = { viewModel.createSession(TerminalViewModel.BACKEND_UBUNTU) },
                        onNewLocal = { viewModel.createSession(TerminalViewModel.BACKEND_LOCAL) },
                        onOpenCenter = { showEnvironmentCenter = true }
                    )
                }

                // 有会话但环境仍在后台收敛（用户先用 Android Shell）→ 细进度条贴底
                if (hasSession && ubuntu.phase in setOf(
                        UbuntuLifecycleCoordinator.Phase.INSTALLING,
                        UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING
                    )
                ) {
                    UbuntuBusyStrip(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        phase = ubuntu.phase,
                        progress = ubuntuProgress
                    )
                }
            }
        }
    }

    if (showNewSessionDialog) {
        NewSessionDialog(
            onDismiss = { showNewSessionDialog = false },
            onUbuntu = {
                showNewSessionDialog = false
                viewModel.createSession(TerminalViewModel.BACKEND_UBUNTU)
            },
            onLocal = {
                showNewSessionDialog = false
                viewModel.createSession(TerminalViewModel.BACKEND_LOCAL)
            }
        )
    }

    // ═══ 环境中心（顶栏图层入口）═══
    if (showEnvironmentCenter) {
        EnvironmentCenterSheet(
            onDismiss = { showEnvironmentCenter = false },
            ubuntu = ubuntu,
            progress = ubuntuProgress,
            rootfsSize = viewModel.rootfsSize.collectAsStateWithLifecycle().value,
            onInstallUbuntu = viewModel::installUbuntu,
            onCancelUbuntuInstall = viewModel::cancelUbuntuInstall,
            onRepairUbuntu = viewModel::repairUbuntu,
            onRemoveUbuntu = viewModel::removeUbuntu,
            onCreateUbuntuSession = {
                showEnvironmentCenter = false
                viewModel.createSession(TerminalViewModel.BACKEND_UBUNTU)
            },
            useMirror = viewModel.useMirror.collectAsStateWithLifecycle().value,
            onToggleMirror = viewModel::setUseMirror,
            depItems = viewModel.depItems,
            install = viewModel.install.collectAsStateWithLifecycle().value,
            onInstallDep = viewModel::installDep,
            onInstallAll = viewModel::installAll,
            onInstallAndroid = viewModel::installAndroidOnly
        )
    }
}

// ═══════════════════════ 控制台主题 ═══════════════════════

/**
 * 终端页控制台配色（自含深色调色 —— 与 [TerminalRenderer] 的终端内容区一致，
 * 不随 App 浅/深主题漂移；强调色取 App dark 主题 primary「neon mint」0xFF4EE9B0）。
 */
internal object ConsoleTheme {
    /** 页面底色（顶栏/条带下的 chrome 层）。 */
    val bg = Color(0xFF0C1210)
    /** 顶栏 / 状态条底色。 */
    val bar = Color(0xFF111815)
    /** 会话 chip 底色。 */
    val chip = Color(0xFF18211C)
    /** 活跃 chip 底色。 */
    val chipActive = Color(0xFF1F3429)
    /** 分隔线。 */
    val stroke = Color(0xFF233029)
    /** 主文本。 */
    val text = Color(0xFFDDEBE3)
    /** 次级文本。 */
    val dim = Color(0xFF7E948A)
    /** 强调（neon mint —— App dark primary）。 */
    val accent = Color(0xFF4EE9B0)
    /** 强调弱底（选中态 / 进度底）。 */
    val accentSoft = Color(0xFF1B382D)
    /** 警示（amber —— App dark secondary）。 */
    val amber = Color(0xFFFFB454)
    /** 危险（magenta —— App dark tertiary）。 */
    val danger = Color(0xFFFF6B9D)
    /** 危险弱底。 */
    val dangerSoft = Color(0xFF38182A)
}

// ═══════════════════════ 顶栏 ═══════════════════════

@Composable
private fun ConsoleTopBar(
    activeTab: TerminalViewModel.SessionTab?,
    ubuntuPhase: UbuntuLifecycleCoordinator.Phase,
    ubuntuPercent: Int,
    onOpenNavDrawer: () -> Unit,
    onNewSession: () -> Unit,
    onOpenCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConsoleTheme.bar)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenNavDrawer) {
            Icon(Icons.Default.Menu, contentDescription = "打开导航", tint = ConsoleTheme.text)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                "终端",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = ConsoleTheme.text
            )
            // 副标题：当前后端 / 环境收敛进度（mono，一行）
            val subtitle = when {
                activeTab != null && activeTab.isUbuntu -> "ubuntu 24.04 · bash"
                activeTab != null -> "android shell"
                ubuntuPhase in setOf(
                    UbuntuLifecycleCoordinator.Phase.INSTALLING,
                    UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING
                ) -> "环境准备中 ${ubuntuPercent}%"
                ubuntuPhase == UbuntuLifecycleCoordinator.Phase.FAILED -> "环境异常"
                else -> "等待会话"
            }
            Text(
                subtitle,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = ConsoleTheme.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onNewSession) {
            Icon(Icons.Default.Add, contentDescription = "新建会话", tint = ConsoleTheme.accent)
        }
        IconButton(onClick = onOpenCenter) {
            Icon(Icons.Default.Layers, contentDescription = "环境中心", tint = ConsoleTheme.accent)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "终端设置", tint = ConsoleTheme.accent)
        }
    }
}

// ═══════════════════════ 会话条 ═══════════════════════

/** 会话 tab 标题的最大字符数 —— shell 标题（tmux/ssh）可能很长，UI 只取前若干字符 + 省略号。 */
private const val MAX_TAB_TITLE = 24

@Composable
private fun SessionStrip(
    sessions: List<TerminalViewModel.SessionTab>,
    activeId: Long?,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConsoleTheme.bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(sessions, key = { it.id }) { tab ->
            SessionChip(
                tab = tab,
                active = tab.id == activeId,
                onSelect = { onSelect(tab.id) },
                onClose = { onClose(tab.id) }
            )
        }
    }
}

@Composable
private fun SessionChip(
    tab: TerminalViewModel.SessionTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    // 标题优先显示 shell 自己设的窗口名（OSC 0/1/2 —— vim/tmux/ssh 都会设），
    // 没有才退回 "#id 后端"。对齐 Termux / JuiceSSH：多会话靠标题分辨在跑什么。
    val title = tab.title?.take(MAX_TAB_TITLE)
        ?: "#${tab.id} ${if (tab.isUbuntu) "Ubuntu" else "Android"}"
    val chipBg by animateColorAsState(
        targetValue = if (active) ConsoleTheme.accentSoft else ConsoleTheme.chip,
        label = "chip-bg"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(chipBg)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // 存活状态点
        Box(
            Modifier
                .size(6.dp)
                .background(
                    if (tab.isAlive) ConsoleTheme.accent else ConsoleTheme.danger,
                    CircleShape
                )
        )
        Column {
            Text(
                title,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = ConsoleTheme.text
            )
            Text(
                if (tab.title != null) "#${tab.id} ${if (tab.isUbuntu) "Ubuntu" else "Android"}" else tab.state,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = ConsoleTheme.dim,
                maxLines = 1
            )
        }
        Icon(
            Icons.Default.Close, "关闭会话",
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(3.dp),
            tint = ConsoleTheme.dim
        )
    }
}

// ═══════════════════════ 状态条 ═══════════════════════

@Composable
private fun StatusStrip(
    semantic: com.apex.agent.platform.terminal.state.TerminalSemanticState?,
    notice: String?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConsoleTheme.bar)
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (semantic == null) {
            Text(
                "未连接会话",
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = ConsoleTheme.dim
            )
        } else {
            Text(
                "#${semantic.session.id} ${semantic.session.state.name} ${semantic.session.rows}×${semantic.session.cols}",
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = ConsoleTheme.dim,
                maxLines = 1
            )
            // 前台 job
            semantic.foregroundJob?.let { job ->
                Text(
                    "▶ ${job.command.take(26)}",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ConsoleTheme.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 等待输入（prompt 检测）
            if (semantic.prompt?.detected == true || semantic.input.state.name == "HIGH_CONFIDENCE") {
                Text(
                    "⌨ 等待输入",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ConsoleTheme.amber,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (notice != null) {
            Text(
                notice,
                fontSize = 10.sp,
                color = ConsoleTheme.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.72f, fill = false)
            )
        }
    }
}

// ═══════════════════════ 环境面板（无会话时的主区）═══════════════════════

/**
 * 无会话时的主区面板 —— 三个形态：
 *  - **准备中**（NOT_INSTALLED/INSTALLING/ROOTFS_READY/BOOTSTRAPPING/RECOVERING）：
 *    三步进度（解压内置环境 → 配置系统 → 初始化工具链）+ 百分比/字节 + 当前阶段消息。
 *    T85 自动预备语义：App 启动即后台进行，用户无需任何操作，完成后自动进终端；
 *  - **失败**（FAILED）：错误详情 + 重试 + 环境中心；
 *  - **就绪空态**（READY，会话被用户全部关闭）：快捷新建入口。
 */
@Composable
private fun EnvironmentPanel(
    phase: UbuntuLifecycleCoordinator.Phase,
    progress: UbuntuLifecycleCoordinator.LifecycleProgress?,
    lastError: String?,
    onRetry: () -> Unit,
    onNewUbuntu: () -> Unit,
    onNewLocal: () -> Unit,
    onOpenCenter: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (phase) {
            UbuntuLifecycleCoordinator.Phase.FAILED -> EnvironmentFailureContent(
                lastError = lastError,
                onRetry = onRetry,
                onOpenCenter = onOpenCenter
            )
            UbuntuLifecycleCoordinator.Phase.READY -> EnvironmentReadyEmptyContent(
                onNewUbuntu = onNewUbuntu,
                onNewLocal = onNewLocal
            )
            else -> EnvironmentPreparingContent(
                phase = phase,
                progress = progress,
                onNewLocal = onNewLocal,
                onOpenCenter = onOpenCenter
            )
        }
    }
}

/** 环境准备步骤序号：0 解压内置档案 → 1 配置系统 → 2 初始化工具链。 */
private fun setupStepIndex(stage: String?): Int = when {
    stage == null -> 0
    stage.startsWith("install:CONFIGURING") ||
        stage.startsWith("install:ACTIVATING") ||
        stage.startsWith("install:VALIDATING") -> 1
    stage.startsWith("bootstrap:") -> 2
    else -> 0 // install:RESOLVING/DOWNLOADING/VERIFYING/EXTRACTING
}

@Composable
private fun EnvironmentPreparingContent(
    phase: UbuntuLifecycleCoordinator.Phase,
    progress: UbuntuLifecycleCoordinator.LifecycleProgress?,
    onNewLocal: () -> Unit,
    onOpenCenter: () -> Unit
) {
    // ── 顶部图标：呼吸光晕的终端符号 ──
    val transition = rememberInfiniteTransition(label = "env-glow")
    val glow by transition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "env-glow-alpha"
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(84.dp)
                .background(ConsoleTheme.accent.copy(alpha = 0.14f * glow), CircleShape)
        )
        Surface(
            shape = CircleShape,
            color = ConsoleTheme.accentSoft,
            border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleTheme.accent.copy(alpha = 0.35f))
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                Icon(
                    Icons.Default.Terminal, contentDescription = null,
                    tint = ConsoleTheme.accent,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(22.dp))
    Text(
        "Ubuntu 环境准备中",
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        color = ConsoleTheme.text
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "完整 Linux 开发环境随应用内置 · 离线解包，无需下载",
        fontSize = 12.sp,
        color = ConsoleTheme.dim,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(28.dp))

    // ── 三步进度 ──
    val currentStep = setupStepIndex(progress?.stage)
    val steps = listOf("解压内置 Ubuntu 档案", "配置系统环境", "初始化工具链")
    steps.forEachIndexed { index, label ->
        SetupStepRow(
            label = label,
            state = when {
                index < currentStep -> SetupStepState.DONE
                index == currentStep -> SetupStepState.ACTIVE
                else -> SetupStepState.PENDING
            }
        )
    }
    Spacer(Modifier.height(26.dp))

    // ── 进度条 + 百分比 / 字节 ──
    val percent = progress?.percent ?: 0
    val bytes = progress?.bytesTransferred ?: 0L
    val bytesTotal = progress?.bytesTotal
    val determinate = percent > 0 || (bytesTotal != null && bytesTotal > 0L)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ConsoleTheme.bar)
            .border(androidx.compose.foundation.BorderStroke(1.dp, ConsoleTheme.stroke), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    progress == null -> "启动中…"
                    else -> progress.message.ifBlank { progress.stage }
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = ConsoleTheme.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (determinate) {
                Text(
                    "$percent%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = ConsoleTheme.accent
                )
            }
        }
        if (determinate) {
            LinearProgressIndicator(
                progress = { (percent.coerceIn(0, 100)) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = ConsoleTheme.accent,
                trackColor = ConsoleTheme.accentSoft
            )
            if (bytesTotal != null && bytesTotal > 0L) {
                Text(
                    "${formatMb(bytes)} / ${formatMb(bytesTotal)}",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ConsoleTheme.dim
                )
            }
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = ConsoleTheme.accent,
                trackColor = ConsoleTheme.accentSoft
            )
        }
        Text(
            "首次准备约 2~5 分钟（约 1GB 磁盘）· 完成后自动进入终端",
            fontSize = 10.5.sp,
            color = ConsoleTheme.dim
        )
    }
    Spacer(Modifier.height(18.dp))

    // ── 次级入口：不等环境，先用 Android Shell / 环境中心 ──
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onNewLocal) {
            Icon(Icons.Default.Android, null, Modifier.size(15.dp), tint = ConsoleTheme.dim)
            Spacer(Modifier.width(6.dp))
            Text("先用 Android Shell", color = ConsoleTheme.dim, fontSize = 12.sp)
        }
        TextButton(onClick = onOpenCenter) {
            Icon(Icons.Default.Layers, null, Modifier.size(15.dp), tint = ConsoleTheme.dim)
            Spacer(Modifier.width(6.dp))
            Text("环境中心", color = ConsoleTheme.dim, fontSize = 12.sp)
        }
    }
}

private enum class SetupStepState { DONE, ACTIVE, PENDING }

@Composable
private fun SetupStepRow(label: String, state: SetupStepState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            SetupStepState.DONE -> Icon(
                Icons.Default.CheckCircle, contentDescription = null,
                tint = ConsoleTheme.accent, modifier = Modifier.size(18.dp)
            )
            SetupStepState.ACTIVE -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 1.8.dp,
                color = ConsoleTheme.accent
            )
            SetupStepState.PENDING -> Box(
                Modifier
                    .size(16.dp)
                    .background(ConsoleTheme.stroke, CircleShape)
            )
        }
        Text(
            label,
            fontSize = 13.sp,
            color = when (state) {
                SetupStepState.DONE -> ConsoleTheme.text
                SetupStepState.ACTIVE -> ConsoleTheme.accent
                SetupStepState.PENDING -> ConsoleTheme.dim
            },
            fontWeight = if (state == SetupStepState.ACTIVE) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun EnvironmentFailureContent(
    lastError: String?,
    onRetry: () -> Unit,
    onOpenCenter: () -> Unit
) {
    Icon(
        Icons.Default.Warning, contentDescription = null,
        tint = ConsoleTheme.danger, modifier = Modifier.size(56.dp)
    )
    Spacer(Modifier.height(18.dp))
    Text("环境准备失败", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = ConsoleTheme.text)
    Spacer(Modifier.height(8.dp))
    if (!lastError.isNullOrBlank()) {
        Text(
            lastError.take(300),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = ConsoleTheme.dim,
            textAlign = TextAlign.Center,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ConsoleTheme.dangerSoft,
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .clickable(onClick = onRetry)
                .padding(horizontal = 26.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = ConsoleTheme.danger, modifier = Modifier.size(17.dp))
            Text("重试", color = ConsoleTheme.danger, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(14.dp))
    TextButton(onClick = onOpenCenter) {
        Text("打开环境中心诊断", color = ConsoleTheme.dim, fontSize = 12.sp)
    }
}

@Composable
private fun EnvironmentReadyEmptyContent(
    onNewUbuntu: () -> Unit,
    onNewLocal: () -> Unit
) {
    Icon(
        Icons.Default.Terminal, contentDescription = null,
        tint = ConsoleTheme.accent, modifier = Modifier.size(52.dp)
    )
    Spacer(Modifier.height(18.dp))
    Text("当前没有终端会话", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ConsoleTheme.text)
    Spacer(Modifier.height(6.dp))
    Text(
        "Ubuntu 环境已就绪 · bash / gcc / python3 / git 开箱即用",
        fontSize = 12.sp,
        color = ConsoleTheme.dim,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ConsoleTheme.accentSoft,
        border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleTheme.accent.copy(alpha = 0.4f)),
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .clickable(onClick = onNewUbuntu)
                .padding(horizontal = 30.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(Icons.Default.Terminal, contentDescription = null, tint = ConsoleTheme.accent, modifier = Modifier.size(18.dp))
            Text("新建 Ubuntu 会话", color = ConsoleTheme.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onNewLocal) {
        Icon(Icons.Default.Android, null, Modifier.size(15.dp), tint = ConsoleTheme.dim)
        Spacer(Modifier.width(6.dp))
        Text("Android Shell", color = ConsoleTheme.dim, fontSize = 12.sp)
    }
}

// ═══════════════════════ 有会话时的环境收敛细条 ═══════════════════════

@Composable
private fun UbuntuBusyStrip(
    modifier: Modifier = Modifier,
    phase: UbuntuLifecycleCoordinator.Phase,
    progress: UbuntuLifecycleCoordinator.LifecycleProgress?
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6101714))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.4.dp,
            color = ConsoleTheme.accent
        )
        Text(
            if (phase == UbuntuLifecycleCoordinator.Phase.INSTALLING) {
                "Ubuntu 环境解包中 ${progress?.percent ?: 0}% · 完成后可新建 Ubuntu 会话"
            } else {
                "Ubuntu 工具链初始化中…"
            },
            fontSize = 11.sp,
            color = ConsoleTheme.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ═══════════════════════ 新建会话对话框 ═══════════════════════

@Composable
private fun NewSessionDialog(
    onDismiss: () -> Unit,
    onUbuntu: () -> Unit,
    onLocal: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建终端会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SessionTypeCard(
                    icon = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
                    title = "Ubuntu 会话",
                    desc = "完整 Linux 环境 · bash / gcc / python3 / git（推荐）",
                    onClick = onUbuntu
                )
                SessionTypeCard(
                    icon = { Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp)) },
                    title = "Android Shell",
                    desc = "系统 toybox 环境 · 适合快查设备（无完整工具链）",
                    onClick = onLocal
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SessionTypeCard(
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        icon()
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                desc,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 字节 → MB 文本（1 位小数）。 */
private fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

// ═══════════════════════ 终端专属设置抽屉 ═══════════════════════

@Composable
private fun TerminalSettingsDrawer(
    settings: TerminalViewModel.TerminalSettings,
    onSettings: (TerminalViewModel.TerminalSettings.() -> TerminalViewModel.TerminalSettings) -> Unit,
    blacklist: Set<String>,
    whitelist: Set<String>,
    onAddBlack: (String) -> Unit,
    onRemoveBlack: (String) -> Unit,
    onAddWhite: (String) -> Unit,
    onRemoveWhite: (String) -> Unit,
    onClose: () -> Unit
) {
    ModalDrawerSheet(modifier = Modifier.width(340.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 标题
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SurfaceBadge(Icons.Default.Settings, MaterialTheme.colorScheme.primary)
                Text("终端设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            // ═══ 1. 终端外观与交互 ═══
            SettingsCard(Icons.Default.Settings, "终端外观") {
                LabeledNumber("字号", settings.fontSize, 8, 32) { onSettings { copy(fontSize = it) } }
                ToggleRow("单色模式", settings.monochrome) { onSettings { copy(monochrome = it) } }
                ToggleRow("键盘辅助行（ESC / CTRL / 方向键）", settings.showKeybar) { onSettings { copy(showKeybar = it) } }
            }

            // ═══ 1b. 反馈（对齐 Termux / ConnectBot 的终端反馈习惯）═══
            SettingsCard(Icons.Default.Settings, "反馈") {
                ToggleRow("响铃时振动（BEL）", settings.vibrateOnBell) {
                    onSettings { copy(vibrateOnBell = it) }
                }
                Text(
                    "shell 发出 BEL（补全失败、Ctrl+G、命令报错）时振动一下。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ToggleRow("保持屏幕常亮", settings.keepScreenOn) {
                    onSettings { copy(keepScreenOn = it) }
                }
                Text(
                    "看长任务输出（编译 / apt / 日志）时不被息屏打断。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ═══ 2. 黑名单 / 白名单 ═══
            SettingsCard(Icons.Default.Block, "命令黑名单 / 白名单") {
                Text(
                    "白名单非空时仅允许其中命令；黑名单中的命令始终禁止。按命令首段（如 rm / adb）匹配。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                CommandListEditor(
                    title = "黑名单",
                    items = blacklist.toList().sorted(),
                    onAdd = onAddBlack,
                    onRemove = onRemoveBlack,
                    danger = true
                )
                Spacer(Modifier.height(8.dp))
                CommandListEditor(
                    title = "白名单",
                    items = whitelist.toList().sorted(),
                    onAdd = onAddWhite,
                    onRemove = onRemoveWhite,
                    danger = false
                )
            }

            // ═══ 3. 入口提示（环境解包在环境中心）═══
            Text(
                "环境解包与管理（内置 Ubuntu / 依赖工具链）在顶栏图层图标的环境中心。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text("关闭")
            }
        }
    }
}

// LabeledNumber / CommandListEditor 仅终端设置抽屉使用；SettingsCard / SurfaceBadge /
// ToggleRow / DepRow / ActionButton 已提升至 EnvironmentCenterSheet.kt（同包共享）。

@Composable
private fun LabeledNumber(label: String, value: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                // 修复静默分叉：越界输入只标红不落盘（原 coerce 后写入但框内仍显示越界值）
                val n = t.toIntOrNull()
                if (n != null && n in min..max) onSet(n)
            },
            isError = text.toIntOrNull()?.let { it !in min..max } ?: true,
            supportingText = if (text.toIntOrNull()?.let { it !in min..max } ?: true) {
                { Text("$min–$max") }
            } else null,
            modifier = Modifier.width(88.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun CommandListEditor(title: String, items: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit, danger: Boolean) {
    var input by remember { mutableStateOf("") }
    Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("如 rm / adb / format", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = {
            if (input.isNotBlank()) { onAdd(input.trim()); input = "" }
        }) { Text("添加") }
    }
    if (items.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.height((items.size.coerceAtMost(4) * 36).dp)) {
            items(items) { cmd ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("• $cmd", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { onRemove(cmd) }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
