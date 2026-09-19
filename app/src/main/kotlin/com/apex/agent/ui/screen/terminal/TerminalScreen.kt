package com.apex.agent.ui.screen.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 终端页（P83 — Terminal 产品化）：
 *  - 顶栏：左汉堡开全局导航，Tune 开终端设置抽屉（外观 / 黑白名单 / 依赖下载中心）
 *  - 会话 tab 条：多会话切换 + 关闭 + 新建（Android shell / Ubuntu 后端选择）
 *  - 状态条：后端徽章 + 会话状态 + 前台 job + 等待输入提示 + 操作反馈
 *  - Ubuntu 生命周期横幅：未安装一键安装 / 安装与引导进度 / 失败重试
 *  - 主区：[TerminalRenderer]（styled grid + 输入 + resize + 特殊键工具栏）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    // 默认 hiltViewModel()：与 AgentChatScreen/MemoryScreen 等既有屏一致
    viewModel: TerminalViewModel = hiltViewModel(),
    onOpenNavDrawer: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val blacklist by viewModel.blacklist.collectAsStateWithLifecycle()
    val whitelist by viewModel.whitelist.collectAsStateWithLifecycle()
    val useMirror by viewModel.useMirror.collectAsStateWithLifecycle()
    val install by viewModel.install.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val semantic by viewModel.semanticState.collectAsStateWithLifecycle()
    val ubuntu by viewModel.ubuntuLifecycleState.collectAsStateWithLifecycle()
    val ubuntuProgress by viewModel.ubuntuProgress.collectAsStateWithLifecycle()
    val rootfsSize by viewModel.rootfsSize.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showNewSessionDialog by remember { mutableStateOf(false) }

    // 保持屏幕常亮：看长任务输出（编译 / apt / 训练日志）时不被息屏打断 ——
    // Termux 默认持有 wakelock，这里用等价的 window flag，交给用户开关。
    val keepScreenOn = settings.keepScreenOn
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(keepScreenOn) {
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
    var showEnvironmentCenter by remember { mutableStateOf(false) }
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

    androidx.compose.material3.ModalNavigationDrawer(
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
        Scaffold(
            topBar = {
                TopAppBar(
                    // 顶栏置零 windowInsets，避免与根 Scaffold 状态栏双重叠加
                    //（本屏根顶栏被抑制，状态栏空间由根 Scaffold contentWindowInsets 提供）
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = { Text(stringResource(R.string.term_title)) },
                    navigationIcon = {
                        // 修复双顶栏：根顶栏在本屏隐藏，左汉堡改为打开全局导航抽屉
                        IconButton(onClick = onOpenNavDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.term_cd_nav), tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    actions = {
                        // 环境中心：内置 Ubuntu rootfs 解包/管理与环境依赖（离线交付，无下载）
                        IconButton(onClick = { showEnvironmentCenter = true }) {
                            Icon(Icons.Default.Layers, contentDescription = stringResource(R.string.term_cd_env_center), tint = MaterialTheme.colorScheme.primary)
                        }
                        // 终端专属设置抽屉（外观/键盘行/命令黑白名单）
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.term_cd_settings), tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // ── 会话 tab 条 ──
                SessionTabStrip(
                    sessions = sessions,
                    activeId = activeId,
                    onSelect = viewModel::selectSession,
                    onClose = viewModel::closeSession,
                    onNew = { showNewSessionDialog = true }
                )

                // ── 状态条 ──
                TerminalStatusBar(
                    semantic = semantic,
                    notice = notice
                )

                // ── Ubuntu 生命周期横幅（未就绪时；详情与操作入口在环境中心）──
                UbuntuLifecycleBanner(
                    phase = ubuntu.phase.name,
                    installing = ubuntu.phase == UbuntuLifecycleCoordinator.Phase.INSTALLING,
                    onInstall = viewModel::installUbuntu,
                    onOpenCenter = { showEnvironmentCenter = true }
                )

                // ── 终端主体（grid + 输入 + 工具栏）──
                TerminalRenderer(
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showNewSessionDialog) {
        AlertDialog(
            onDismissRequest = { showNewSessionDialog = false },
            title = { Text(stringResource(R.string.term_new_session_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.term_new_session_ubuntu_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewSessionDialog = false
                    viewModel.createSession(TerminalViewModel.BACKEND_UBUNTU)
                }) {
                    Icon(Icons.Default.Terminal, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.term_ubuntu_session))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewSessionDialog = false
                    viewModel.createSession(TerminalViewModel.BACKEND_LOCAL)
                }) {
                    Icon(Icons.Default.Android, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Android shell")
                }
            }
        )
    }

    // ═══ 环境中心（顶栏图标入口）═══
    if (showEnvironmentCenter) {
        EnvironmentCenterSheet(
            onDismiss = { showEnvironmentCenter = false },
            ubuntu = ubuntu,
            progress = ubuntuProgress,
            rootfsSize = rootfsSize,
            onInstallUbuntu = viewModel::installUbuntu,
            onCancelUbuntuInstall = viewModel::cancelUbuntuInstall,
            onRepairUbuntu = viewModel::repairUbuntu,
            onRemoveUbuntu = viewModel::removeUbuntu,
            onCreateUbuntuSession = {
                showEnvironmentCenter = false
                viewModel.createSession(TerminalViewModel.BACKEND_UBUNTU)
            },
            useMirror = useMirror,
            onToggleMirror = viewModel::setUseMirror,
            depItems = viewModel.depItems,
            install = install,
            onInstallDep = viewModel::installDep,
            onInstallAll = viewModel::installAll,
            onInstallAndroid = viewModel::installAndroidOnly
        )
    }
}

// ═══ 会话 tab 条 ═══

@Composable
private fun SessionTabStrip(
    sessions: List<TerminalViewModel.SessionTab>,
    activeId: Long?,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(sessions, key = { it.id }) { tab ->
                SessionTab(
                    tab = tab,
                    active = tab.id == activeId,
                    onSelect = { onSelect(tab.id) },
                    onClose = { onClose(tab.id) }
                )
            }
        }
        // 新建会话
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .clickable(onClick = onNew),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add, stringResource(R.string.term_cd_new_session),
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 会话 tab 标题的最大字符数 —— shell 标题（tmux/ssh）可能很长，UI 只取前若干字符 + 省略号。 */
private const val MAX_TAB_TITLE = 24

@Composable
private fun SessionTab(
    tab: TerminalViewModel.SessionTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceContainerHighest
            )
            .clickable(onClick = onSelect)
            .padding(start = 9.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            if (tab.isUbuntu) Icons.Default.Terminal else Icons.Default.Android,
            null, Modifier.size(13.dp),
            tint = if (tab.isUbuntu) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
        )
        Column {
            // 标题优先显示 shell 自己设的窗口名（OSC 0/1/2 —— PS1 里的 \[\e]0;…\a\]、
            // vim/tmux/ssh 都会设），没有才退回 "#id 后端"。
            // 对齐 Termux / JuiceSSH / ConnectBot：多会话时靠标题分辨在跑什么。
            Text(
                tab.title?.take(MAX_TAB_TITLE) ?: "#${tab.id} ${if (tab.isUbuntu) "Ubuntu" else "Android"}",
                fontSize = 11.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                if (tab.title != null) "#${tab.id} ${if (tab.isUbuntu) "Ubuntu" else "Android"}" else tab.state,
                fontSize = 9.sp,
                color = if (tab.isAlive) Color(0xFF3E9C51) else Color(0xFFB06055),
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
        Icon(
            Icons.Default.Close, stringResource(R.string.term_cd_close_session),
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(2.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══ 状态条（后端/会话/前台 job/等待输入/反馈）═══

@Composable
private fun TerminalStatusBar(
    semantic: com.apex.agent.platform.terminal.state.TerminalSemanticState?,
    notice: String?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (semantic == null) {
            Text(
                stringResource(R.string.term_no_session),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 会话状态点
            Box(
                Modifier
                    .size(7.dp)
                    .background(
                        when {
                            semantic.session.state.name in setOf("RUNNING", "WAITING_INPUT") -> Color(0xFFE0A63C)
                            else -> Color(0xFF3E9C51)
                        },
                        CircleShape
                    )
            )
            Text(
                "#${semantic.session.id} ${semantic.session.state.name} ${semantic.session.rows}×${semantic.session.cols}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 前台 job
            semantic.foregroundJob?.let { job ->
                Text(
                    "▶ ${job.command.take(26)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            // 等待输入（prompt 检测）
            if (semantic.prompt?.detected == true || semantic.input.state.name == "HIGH_CONFIDENCE") {
                Text(
                    stringResource(R.string.term_waiting_input),
                    fontSize = 11.sp,
                    color = Color(0xFFE0A63C)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (notice != null) {
            Text(
                notice,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                modifier = Modifier.weight(0.72f, fill = false)
            )
        }
    }
}

// ═══ Ubuntu 生命周期横幅 ═══

@Composable
private fun UbuntuLifecycleBanner(
    phase: String,
    installing: Boolean,
    onInstall: () -> Unit,
    onOpenCenter: () -> Unit
) {
    when (phase) {
        "NOT_INSTALLED", "FAILED" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (phase == "FAILED") Color(0x33B06055) else Color(0x1A4C8DFF)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (phase == "FAILED") stringResource(R.string.term_banner_failed_title)
                        else stringResource(R.string.term_banner_not_installed_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (phase == "FAILED") stringResource(R.string.term_banner_failed_desc)
                        else stringResource(R.string.term_banner_not_installed_desc),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onInstall) {
                    Text(if (phase == "FAILED") stringResource(R.string.term_retry)
                    else stringResource(R.string.term_unpack))
                }
                TextButton(onClick = onOpenCenter) {
                    Text(stringResource(R.string.term_env_center))
                }
            }
        }
        "INSTALLING", "BOOTSTRAPPING", "RECOVERING", "ROOTFS_READY" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x1A4C8DFF))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                Text(
                    when (phase) {
                        "INSTALLING" -> stringResource(R.string.term_installing_banner)
                        "BOOTSTRAPPING" -> stringResource(R.string.term_bootstrapping_banner)
                        else -> stringResource(R.string.term_converging_banner)
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else -> { /* READY：不占空间 */ }
    }
}

// ═══ 终端专属设置抽屉（环境能力已迁至环境中心，此处专注终端本身）═══

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
                Text(stringResource(R.string.term_settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            // ═══ 1. 终端外观与交互 ═══
            SettingsCard(Icons.Default.Settings, stringResource(R.string.term_appearance)) {
                LabeledNumber(stringResource(R.string.term_font_size), settings.fontSize, 8, 32) { onSettings { copy(fontSize = it) } }
                ToggleRow(stringResource(R.string.term_monochrome), settings.monochrome) { onSettings { copy(monochrome = it) } }
                ToggleRow(stringResource(R.string.term_keybar), settings.showKeybar) { onSettings { copy(showKeybar = it) } }
            }

            // ═══ 1b. 反馈（对齐 Termux / ConnectBot 的终端反馈习惯）═══
            SettingsCard(Icons.Default.Settings, stringResource(R.string.term_feedback)) {
                ToggleRow(stringResource(R.string.term_vibrate_on_bell), settings.vibrateOnBell) {
                    onSettings { copy(vibrateOnBell = it) }
                }
                Text(
                    stringResource(R.string.term_vibrate_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ToggleRow(stringResource(R.string.term_keep_screen_on), settings.keepScreenOn) {
                    onSettings { copy(keepScreenOn = it) }
                }
                Text(
                    stringResource(R.string.term_keep_screen_on_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ═══ 2. 黑名单 / 白名单 ═══
            SettingsCard(Icons.Default.Block, stringResource(R.string.term_blacklist_title)) {
                Text(
                    stringResource(R.string.term_blacklist_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                CommandListEditor(
                    title = stringResource(R.string.term_blacklist),
                    items = blacklist.toList().sorted(),
                    onAdd = onAddBlack,
                    onRemove = onRemoveBlack,
                    danger = true
                )
                Spacer(Modifier.height(8.dp))
                CommandListEditor(
                    title = stringResource(R.string.term_whitelist),
                    items = whitelist.toList().sorted(),
                    onAdd = onAddWhite,
                    onRemove = onRemoveWhite,
                    danger = false
                )
            }

            // ═══ 3. 入口提示（环境解包在环境中心）═══
            Text(
                stringResource(R.string.term_env_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.term_close))
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
            placeholder = { Text(stringResource(R.string.term_cmd_hint), style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = {
            if (input.isNotBlank()) { onAdd(input.trim()); input = "" }
        }) { Text(stringResource(R.string.term_add)) }
    }
    if (items.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        LazyColumn(modifier = Modifier.height((items.size.coerceAtMost(4) * 36).dp)) {
            items(items) { cmd ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("• $cmd", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { onRemove(cmd) }) { Text(stringResource(R.string.term_remove), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
