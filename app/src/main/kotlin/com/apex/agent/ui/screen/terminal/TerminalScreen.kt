package com.apex.agent.ui.screen.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * 终端页：左上角三条杠菜单（终端专属设置抽屉），含三个分区：
 *  1. 终端设置（字号/行数/单色）
 *  2. Agent 命令黑名单 / 白名单
 *  3. 环境依赖下载中心（官方源+镜像，一键全装 / 独立装 / Android 依赖一键装）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val blacklist by viewModel.blacklist.collectAsStateWithLifecycle()
    val whitelist by viewModel.whitelist.collectAsStateWithLifecycle()
    val useMirror by viewModel.useMirror.collectAsStateWithLifecycle()
    val install by viewModel.install.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val interactive by viewModel.interactive.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 进入页面即确保交互会话存在
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.ensureInteractiveSession() }

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
                useMirror = useMirror,
                onToggleMirror = viewModel::setUseMirror,
                depItems = viewModel.depItems,
                installed = installed,
                install = install,
                onInstallDep = viewModel::installDep,
                onInstallAll = viewModel::installAll,
                onClose = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("终端")
                            SessionStatusChip(alive = interactive.alive, busy = interactive.busy)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "终端设置", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::interrupt, enabled = interactive.alive) {
                            Icon(Icons.Default.Clear, contentDescription = "中断 (Ctrl+C)", tint = MaterialTheme.colorScheme.tertiary)
                        }
                        IconButton(onClick = viewModel::clearOutput) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空输出", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = viewModel::newSession, enabled = interactive.alive) {
                            Icon(Icons.Default.Add, contentDescription = "新建会话", tint = MaterialTheme.colorScheme.primary)
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
            InteractiveTerminal(
                modifier = Modifier.fillMaxSize().padding(padding),
                output = interactive.output,
                fontSize = settings.fontSize,
                monochrome = settings.monochrome,
                alive = interactive.alive,
                history = history,
                onSend = viewModel::sendCommand
            )
        }
    }
}

@Composable
private fun SessionStatusChip(alive: Boolean, busy: Boolean) {
    val (label, color) = when {
        !alive -> "离线" to MaterialTheme.colorScheme.error
        busy -> "执行中" to MaterialTheme.colorScheme.secondary
        else -> "就绪" to MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.height(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun InteractiveTerminal(
    modifier: Modifier = Modifier,
    output: String,
    fontSize: Int,
    monochrome: Boolean,
    alive: Boolean,
    history: List<String>,
    onSend: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var historyIdx by remember { mutableStateOf(-1) } // -1 表示当前编辑行
    val listState = remember { androidx.compose.foundation.lazy.rememberLazyListState() }
    val scope = rememberCoroutineScope()

    // 新输出到达时自动滚到底部
    androidx.compose.runtime.LaunchedEffect(output) {
        val count = outputLineCount(output)
        if (count > 0) scope.launch { listState.scrollToItem((count - 1).coerceAtLeast(0)) }
    }

    val onEnter: () -> Unit = {
        if (input.isNotBlank() && alive) {
            onSend(input)
            historyIdx = -1
            input = ""
        }
    }

    Column(modifier = modifier) {
        // 输出区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (output.isEmpty()) {
                Text(
                    if (alive) "会话已就绪，输入命令后回车执行。\n（黑名单/白名单策略见左上菜单）" else "终端会话不可用（设备不支持 PTY）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(outputLineCount(output)) { i ->
                        val line = output.lineAt(i)
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = fontSize.sp),
                            fontFamily = FontFamily.Monospace,
                            color = if (monochrome) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 输入行
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; historyIdx = -1 },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = alive,
                    placeholder = { Text("输入命令，回车执行", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontSize = fontSize.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onEnter() })
                )
            }
        }
    }
}

// 输出按行切分（LazyColumn 逐行渲染，避免单个巨大 Text 卡顿）
private fun outputLineCount(text: String): Int = text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1
private fun String.lineAt(index: Int): String {
    val lines = this.lineSequence().toList()
    return lines.getOrElse(index) { "" }
}

// ═══ 终端专属设置抽屉 ═══
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
    useMirror: Boolean,
    onToggleMirror: (Boolean) -> Unit,
    depItems: List<TerminalViewModel.DepItem>,
    installed: Set<String>,
    install: TerminalViewModel.InstallState,
    onInstallDep: (TerminalViewModel.DepItem, Int) -> Unit,
    onInstallAll: () -> Unit,
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
                SurfaceBadge(Icons.Default.Terminal, MaterialTheme.colorScheme.primary)
                Text("终端设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            // ═══ 1. 终端设置 ═══
            SettingsCard(Icons.Default.Settings, "终端外观") {
                LabeledNumber("字号", settings.fontSize, 8, 32) { onSettings { copy(fontSize = it) } }
                LabeledNumber("最大行数", settings.maxLines, 100, 10000) { onSettings { copy(maxLines = it) } }
                ToggleRow("单色模式", settings.monochrome) { onSettings { copy(monochrome = it) } }
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

            // ═══ 3. 环境依赖下载中心 ═══
            SettingsCard(Icons.Default.Download, "环境依赖下载中心") {
                Text(
                    "下载官方工具链并自动解压配置，下载完即可在终端/Agent 中直接使用（已注入 ANDROID_HOME / PATH）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("默认优先镜像源", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("关闭则走 Google 官方源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = useMirror,
                        onCheckedChange = onToggleMirror,
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(Modifier.height(10.dp))
                ActionButton("一键下载安装全部", install.runningId != null, Modifier.fillMaxWidth()) { onInstallAll() }
                Spacer(Modifier.height(10.dp))
                Text("可独立下载安装：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                depItems.forEach { item ->
                    DepRow(
                        item = item,
                        installed = installed.contains(item.id),
                        installing = install.runningId == item.id,
                        progress = if (install.runningId == item.id) install.progress else if (installed.contains(item.id)) 100 else 0,
                        onInstall = { srcIdx -> onInstallDep(item, srcIdx) }
                    )
                }
                // 安装日志
                if (install.log.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            install.log,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun SettingsCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SurfaceBadge(icon, MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SurfaceBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color) {
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.15f), modifier = Modifier.size(34.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = tint) }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun LabeledNumber(label: String, value: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                t.toIntOrNull()?.coerceIn(min, max)?.let(onSet)
            },
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
        LazyColumn(modifier = Modifier.height((items.size.coerceAtMost(4) * 36).dp)) {
            items(items) { cmd ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("• $cmd", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { onRemove(cmd) }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun DepRow(
    item: TerminalViewModel.DepItem,
    installed: Boolean,
    installing: Boolean,
    progress: Int,
    onInstall: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedSrc by remember { mutableStateOf(0) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Android, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                if (installed) {
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                        Text("已就绪", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
            if (installing && progress > 0) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text("下载中 $progress%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 下载源选择
                    Box {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickableSafe(enabled = !installing) { expanded = true }
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.CloudDownload, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.sources.getOrNull(selectedSrc)?.label ?: "选择源", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            item.sources.forEachIndexed { i, src ->
                                DropdownMenuItem(text = { Text(src.label) }, onClick = { selectedSrc = i; expanded = false })
                            }
                        }
                    }
                    ActionButton(
                        if (installed) "重新下载" else "下载安装",
                        installing,
                        Modifier.weight(1f)
                    ) { onInstall(selectedSrc) }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, loading: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (loading) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).then(Modifier.clickableSafe(enabled = !loading, onClick = onClick))
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                if (loading) "$label…" else label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (loading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

// 轻量 clickable 包装，复用已 import 的 androidx.compose.foundation.clickable
@Composable
private fun Modifier.clickableSafe(enabled: Boolean, onClick: () -> Unit): Modifier =
    this.then(this.clickable(enabled = enabled, onClick = onClick))
