package com.apex.agent.ui.screen.code

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.platform.code.ws.CodeWorkspace
import com.apex.agent.ui.component.GithubIconButton
import com.apex.agent.ui.component.GithubTokenDialog
import com.apex.agent.ui.component.MarkdownText
import com.apex.agent.ui.component.SlashAutoCompleteHost
import com.apex.agent.ui.component.SlashCommandButton
import com.apex.agent.ui.component.SkillChipInputField
import com.apex.agent.ui.component.SlashMenuProvider
import com.apex.agent.ui.component.rememberSlashMenuProvider
import com.apex.agent.ui.screen.agent.CODING_SCREEN_MODES
import com.apex.agent.ui.screen.agent.AgentModeSelector
import com.apex.agent.ui.screen.agent.PendingPipelineCommand
import com.apex.agent.ui.screen.agent.PlanConfirmationCard
import com.apex.agent.ui.screen.agent.QuestionCard
import com.apex.agent.ui.screen.agent.ToolRef
import com.apex.agent.ui.screen.agent.ToolkitChipsRow
import com.apex.agent.ui.screen.agent.ToolkitRingButton
import com.apex.agent.core.code.standard.StandardLogicMode
import com.apex.agent.core.code.stream.StreamEntry
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.ui.screen.agent.toolkit.OutputFormat
import com.apex.agent.ui.screen.code.editor.CodeEditorPanel
import com.apex.agent.ui.screen.code.longtask.CodeLongTaskSheet
import com.apex.agent.ui.screen.code.stream.CodeStreamTimeline
import com.apex.agent.ui.screen.code.stream.CodeTerminalPanel
import com.apex.agent.ui.screen.code.stream.CodeToolDetailSheet
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassStyle
import dev.chrisbanes.haze.HazeState

/**
 * # Code Screen — Coding 模式主屏（与 Agent 聊天屏同级别）
 *
 * #197 双工位升级后的布局：工作区条（切换/新建）→ Todo 面板（可折叠）→
 * 编辑器面板 → **胶囊时间轴** → 终端面板 → 工具/权限提问卡 → 错误条 →
 * **模式+思考选择器行（Build/Plan + 七档思考）** → 输入栏（**斜杠 +
 * GitHub + 小圆环函数调用 + 输入框 + 发送**）。
 * PLAN 模式的计划确认卡插在时间轴上方（人控门）；胶囊点击进详情弹层。
 */
@Composable
fun CodeScreen(
    viewModel: CodeViewModel,
    slashMenuProvider: SlashMenuProvider = rememberSlashMenuProvider()
) {
    // E4（#2-c P1-6）：主状态流对齐全仓 117 处先例换 lifecycle 版 —— 后台/不可见
    // 期间停收集（StateFlow 无参重载语义与 collectAsState 一致，初始值取 value）。
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingAgentQuestion by viewModel.pendingAgentQuestion.collectAsStateWithLifecycle()
    val pendingCommands by viewModel.pendingCommands.collectAsStateWithLifecycle()
    val llmConfigured by viewModel.llmConfigured.collectAsStateWithLifecycle()

    // ═══ #197 「小圆环」工具菜单状态（Coding 工位独占）═══
    val toolkit = viewModel.toolkitStore
    val webSearchEnabled by toolkit.webSearchEnabled.collectAsStateWithLifecycle()
    val timeEnabled by toolkit.timeEnabled.collectAsStateWithLifecycle()
    val selectedFunctionIds by toolkit.selectedFunctionIds.collectAsStateWithLifecycle()
    val outputFormat by toolkit.outputFormat.collectAsStateWithLifecycle()
    val customSchema by toolkit.customSchema.collectAsStateWithLifecycle()
    val rules by toolkit.rules.collectAsStateWithLifecycle()
    val availableTools = remember { viewModel.availableTools() }

    // ═══ #197 /mcp:github 未连接信号（斜杠引导连接闭环）═══
    var showGithubConnectDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.requestGithubConnect.collect { showGithubConnectDialog = true }
    }

    // ═══ #197 模型 API 未配置浮窗（发送拦截）═══
    var showApiMissingNotice by rememberSaveable { mutableStateOf(false) }

    var showNewWorkspace by rememberSaveable { mutableStateOf(false) }
    var showThinkingGuide by rememberSaveable { mutableStateOf(false) }
    // 胶囊详情弹层选中项（存 id 不存对象：StreamToolCall 是不可变快照，
    // 每 25ms 批次按 index 替换新实例——存对象会冻结在点击瞬间，
    // 状态/耗时/Diff 永不更新；存 id 每次重组从活快照重查）。
    // rememberSaveable：旋转/重建后详情弹层不丢（String? 可入 Bundle）。
    var selectedToolCallId by rememberSaveable { mutableStateOf<String?>(null) }
    // 终端面板折叠态（默认展开——BASH 是 Coding 工作流主舞台）
    var terminalCollapsed by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize()
        // IME insets 已由 ApexRoot 的 contentWindowInsets(systemBars ∪ ime) 统一
        // 注入——这里再叠 .imePadding() 会双重抬升（键盘弹出时输入栏被顶过高，
        // 第三轮审计对 Agent 屏的同款修复，见 ApexRoot.kt 注释）。
    ) {
        WorkspaceBar(
            active = state.activeWorkspace,
            workspaces = state.workspaces,
            onSelect = viewModel::switchWorkspace,
            onCreate = { showNewWorkspace = true },
            onDelete = viewModel::deleteWorkspace,
            onClearChat = viewModel::clearConversation,
            onOpenLongTasks = viewModel::openLongTaskCenter,
            // v1.5 右上角思考逻辑切换（深潜 = 自研 / 标准 = 标准任务循环）
            logicMode = state.logicMode,
            onSwitchLogic = viewModel::setLogicMode
        )

        if (state.todos.isNotEmpty()) {
            TodoPanel(todos = state.todos)
        }

        // v1.0 #154：编辑器面板——当前文件预览（行号/着色/行点击回填 @file:line）
        state.editorFilePath?.let { editorPath ->
            CodeEditorPanel(
                filePath = editorPath,
                file = state.editorFile,
                isLoading = state.editorLoading,
                errorText = state.editorError,
                onClose = viewModel::closeEditor,
                onLineClick = { line -> viewModel.insertAtRef("@$editorPath:$line") }
            )
        }

        // ═══ #197 PLAN 模式计划确认卡（人控门；勾选/重排后确认执行）═══
        if (state.awaitingPlanConfirmation && state.plan != null) {
            PlanConfirmationCard(
                plan = state.plan!!,
                onConfirm = { enabledSteps, order ->
                    viewModel.confirmPlan(true, enabledSteps, order)
                },
                onReject = { viewModel.confirmPlan(false) }
            )
        }

        // ═══ v5 玻璃悬浮层重构（Agent 屏同款 overlay 模式）═══
        // 时间轴 = 玻璃采样源（hazeSource）；底部栈（终端尾窗/提问卡/错误条/
        // 模式行/输入栏）悬浮于时间轴之上 —— 输入栏 GlassCard(Floating) 从
        // 时间轴获得真实 backdrop 采样（此前 Coding 屏零玻璃：死色输入条 +
        // 流式结论裸铺在背景上）。lowerStackInsetPx 动态测量悬浮栈高度，
        // 时间轴 contentPadding 补偿，最后一条不被遮挡。
        val glassState = remember { HazeState() }
        var lowerStackInsetPx by remember { mutableIntStateOf(0) }
        val lowerStackInsetDp = with(LocalDensity.current) { lowerStackInsetPx.toDp() }
        Box(modifier = Modifier.weight(1f)) {
            CodeStreamTimeline(
                snapshot = state.stream,
                isStreaming = state.isRunning,
                bottomInset = lowerStackInsetDp,
                hazeState = glassState,
                onToolClick = { call -> selectedToolCallId = call.id }
            )

            // ═══ 底部悬浮栈：悬浮于时间轴之上（backdrop 采样前提）═══
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { lowerStackInsetPx = it.height }
            ) {
                // 终端面板（活跃 BASH 的脉冲尾窗；独立锚定与时间轴互不抢占）
                if (state.stream.activeTerminalCallId != null || state.stream.terminalContent.isNotBlank()) {
                    CodeTerminalPanel(
                        content = state.stream.terminalContent,
                        activeCommand = state.stream.activeTerminalCallId,
                        collapsed = terminalCollapsed,
                        onToggleCollapse = { terminalCollapsed = !terminalCollapsed },
                        glassState = glassState
                    )
                }

                // v1.0 #155：工具/权限门的结构化提问卡（与 ask_user 的纯文本对话框并存；
                // 工具执行已挂起，用户必须作答才能继续）
                pendingAgentQuestion?.let { question ->
                    QuestionCard(
                        question = question,
                        onAnswer = { optionIds, customText ->
                            viewModel.answerAgentQuestion(optionIds, customText)
                        },
                        onCancel = viewModel::cancelAgentQuestion,
                        glassState = glassState
                    )
                }

                state.error?.let { err ->
                    // #209：运行失败类错误（errorRetriable）提供一键重试；运行中不重复触发。
                    // v6 玻璃接线：错误条从实色 errorContainer 改 GlassCard 真采样 ——
                    // accent=error 保错误语义在玻璃材质上仍可辨。
                    ErrorBar(
                        message = err,
                        glassState = glassState,
                        onRetry = if (state.errorRetriable && !state.isRunning) viewModel::retryLastRun else null,
                        onDismiss = viewModel::dismissError
                    )
                }

                // ═══ #197 模式 + 思考档位选择器行（Build/Plan 双档 + 七档思考）═══
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    AgentModeSelector(
                        current = state.mode,
                        onSelect = viewModel::setMode,
                        modes = CODING_SCREEN_MODES
                    )
                    CodeThinkingSelector(
                        current = state.thinkingLevel,
                        adaptiveDecision = state.adaptiveDecision,
                        onSelect = viewModel::setThinkingLevel,
                        onOpenGuide = { showThinkingGuide = true }
                    )
                }

                CodeInputBar(
                    draft = state.inputDraft,
                    onDraftChange = viewModel::updateInputDraft,
                    isRunning = state.isRunning,
                    llmConfigured = llmConfigured,
                    onSendBlocked = { showApiMissingNotice = true },
                    onSend = viewModel::sendMessage,
                    onAbort = viewModel::abort,
                    slashMenuProvider = slashMenuProvider,
                    onAddPendingCommand = { viewModel.addPendingCommand(it) },
                    pendingCommands = pendingCommands,
                    onChipsChange = { viewModel.setPendingCommands(it) },
                    glassState = glassState,
                    githubTokenManager = viewModel.githubTokenManager,
                    toolkit = toolkit,
                    toolkitState = ToolkitUiState(
                        webSearchEnabled = webSearchEnabled,
                        timeEnabled = timeEnabled,
                        selectedFunctionIds = selectedFunctionIds,
                        availableTools = availableTools,
                        outputFormat = outputFormat,
                        customSchema = customSchema,
                        rules = rules,
                        exposeAllTools = toolkit.exposeAllTools.collectAsStateWithLifecycle().value
                    )
                )
            }
        }
    }

        // ═══ #197 模型 API 未配置浮窗（同 Agent 屏，发送拦截时弹出）═══
        if (showApiMissingNotice) {
            ApiMissingFloatingNotice(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(10f),
                onOpenSettings = { showApiMissingNotice = false },
                onDismiss = { showApiMissingNotice = false }
            )
        }
    }

    // v1.0 #155：工具/权限门的结构化提问已在上方 Column 内渲染
    state.pendingQuestion?.let { question ->
        PendingQuestionDialog(
            question = question,
            onSubmit = viewModel::submitUserInput
        )
    }

    if (showNewWorkspace) {
        NewWorkspaceDialog(
            onConfirm = { name ->
                viewModel.createWorkspace(name)
                showNewWorkspace = false
            },
            onDismiss = { showNewWorkspace = false }
        )
    }

    // coding 七档思考指南（ModalBottomSheet）：阶梯总表 + 逐档卡片，可直切档
    if (showThinkingGuide) {
        CodeThinkingGuideSheet(
            currentLevel = state.thinkingLevel,
            onDismiss = { showThinkingGuide = false },
            onSelect = viewModel::setThinkingLevel
        )
    }

    // ═══ #197 GitHub 连接对话框（斜杠 /mcp:github 未连接信号）═══
    if (showGithubConnectDialog) {
        GithubTokenDialog(
            onDismiss = { showGithubConnectDialog = false },
            onSubmit = { token -> viewModel.githubTokenManager.validateToken(token) },
            onSuccess = { token, username ->
                viewModel.githubTokenManager.saveToken(token, username)
                showGithubConnectDialog = false
            }
        )
    }

    // 胶囊详情弹层（分族路由：BASH→终端全文 / EDIT→Diff / GREP→命中列表）
    // —— 从活快照按 id 重查（含折叠进轮次卡内的胶囊），状态实时翻转
    val selectedCall: StreamToolCall? = selectedToolCallId?.let { id ->
        state.stream.entries.asSequence().mapNotNull { e ->
            when (e) {
                is StreamEntry.ToolCapsuleEntry -> e.call
                is StreamEntry.VerifyCycleEntry -> e.calls.firstOrNull { it.id == id }
                else -> null
            }
        }.firstOrNull { it.id == id }
    }
    selectedCall?.let { call ->
        CodeToolDetailSheet(
            call = call,
            terminalFallback = viewModel.terminalLogOf(call.id),
            onDismiss = { selectedToolCallId = null }
        )
    }

    // v1.2 长任务中心（ModalBottomSheet）：任务记录 + 任务模板 + 档位效能三页签
    CodeLongTaskSheet(
        visible = state.longTaskSheetVisible,
        records = state.longTasks,
        loading = state.longTaskLoading,
        stats = state.thinkingStats,
        onDismiss = viewModel::closeLongTaskCenter,
        onCopy = viewModel::copyTask,
        onRelaunch = viewModel::relaunchTask,
        onResume = viewModel::resumeTask,
        onDelete = viewModel::deleteLongTask,
        onCompareWithParent = viewModel::compareWithParent,
        onStartTemplate = viewModel::startFromTemplate
    )
}

// ═══ 工作区条 ═══

@Composable
private fun WorkspaceBar(
    active: CodeWorkspace?,
    workspaces: List<CodeWorkspace>,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
    onClearChat: () -> Unit,
    onOpenLongTasks: () -> Unit,
    logicMode: StandardLogicMode = StandardLogicMode.DEEP_DIVE,
    onSwitchLogic: (StandardLogicMode) -> Boolean = { false }
) {
    var menuOpen by remember { mutableStateOf(false) }
    // v1.5 切换被拒提示（运行中拒绝；2s 自清，行内告警形态）
    var logicSwitchBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(logicSwitchBlocked) {
        if (logicSwitchBlocked) {
            delay(2000)
            logicSwitchBlocked = false
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                Icons.Default.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            // v5 防挤压（与 Agent 屏顶栏同修复）：工作区名 + 下拉占满前段后，
            // 窄屏上尾部三件（逻辑选择器/长任务/新会话）被剩余空间压到近乎 0 宽。
            // 前段收进 weight(1f) 横向滚动行，尾部按钮固定永远可见。
            Box(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { menuOpen = true }
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.widthIn(max = 180.dp)) {
                        Text(
                            text = active?.name ?: stringResource(R.string.code_no_workspace),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        active?.detectedEnvironment?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.code_switch_workspace),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    workspaces.forEach { ws ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(ws.name, style = MaterialTheme.typography.bodyMedium)
                                    ws.detectedEnvironment?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            leadingIcon = if (ws.workspaceId == active?.workspaceId) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            trailingIcon = if (ws.workspaceId != "default" && ws.workspaceId != active?.workspaceId) {
                                {
                                    IconButton(onClick = { onDelete(ws.workspaceId) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.code_delete_workspace),
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            } else null,
                            onClick = {
                                menuOpen = false
                                onSelect(ws.workspaceId)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.code_new_workspace)) },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            menuOpen = false
                            onCreate()
                        }
                    )
                }
            }

            // v1.5 思考逻辑切换（右上角：深潜 = 自研七档 / 标准 = 标准任务循环）
            CodeLogicModeSelector(
                current = logicMode,
                onSwitchBlocked = logicSwitchBlocked,
                onSelect = { mode ->
                    val accepted = onSwitchLogic(mode)
                    if (!accepted) logicSwitchBlocked = true
                    accepted
                }
            )

            Spacer(Modifier.width(4.dp))

            // v1.2 长任务中心入口（记录/模板两页签的 ModalBottomSheet）
            // UI-012：48dp 触区红线（原 28dp）
            IconButton(onClick = onOpenLongTasks, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = stringResource(R.string.code_longtask_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 新会话：图标按钮（对齐 Agent 屏 History 邻钮；UI-012：48dp 触区红线，原 28dp）
            IconButton(onClick = onClearChat, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.code_clear_chat),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ═══ Todo 面板 ═══

@Composable
private fun TodoPanel(todos: List<CodeTodoTool.Todo>) {
    var expanded by remember { mutableStateOf(true) }
    val done = todos.count { it.status == "completed" }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Icon(
                    Icons.Default.Checklist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.code_todo_progress, done, todos.size),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                todos.forEach { todo ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 1.dp)
                    ) {
                        val mark = when (todo.status) {
                            "completed" -> "✔"
                            "in_progress" -> "▶"
                            "cancelled" -> "✖"
                            else -> "○"
                        }
                        val color = when (todo.status) {
                            "completed" -> MaterialTheme.colorScheme.primary
                            "in_progress" -> MaterialTheme.colorScheme.tertiary
                            "cancelled" -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(mark, color = color, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (todo.priority == "high") "${todo.content} (!)" else todo.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (todo.status == "cancelled") MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// ═══ 消息流 ═══
// v 胶囊流式：旧 CodeMessageList/CodeMessageItem/ToolCard/DiffOutput 渲染族
// 已由 stream 包（CodeStreamTimeline + 卡片族）整体取代——Diff 着色/
// 等宽回放/流式态全部升级为胶囊 + 详情弹层分族路由形态。

// ═══ 输入栏（#197 升级：斜杠 + GitHub + 小圆环函数调用 + 输入框 + 发送）═══

/** 小圆环状态包（避免 CodeInputBar 参数爆炸）。 */
private data class ToolkitUiState(
    val webSearchEnabled: Boolean,
    val timeEnabled: Boolean,
    val selectedFunctionIds: Set<String>,
    val availableTools: List<ToolRef>,
    val outputFormat: OutputFormat,
    val customSchema: String,
    val rules: List<com.apex.agent.ui.screen.agent.toolkit.ChatRule>,
    val exposeAllTools: Boolean
)

@Composable
private fun CodeInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    isRunning: Boolean,
    llmConfigured: Boolean,
    onSendBlocked: () -> Unit,
    onSend: (String) -> Unit,
    onAbort: () -> Unit,
    slashMenuProvider: SlashMenuProvider,
    onAddPendingCommand: (PendingPipelineCommand) -> Unit,
    pendingCommands: List<PendingPipelineCommand>,
    onChipsChange: (List<PendingPipelineCommand>) -> Unit,
    glassState: HazeState,
    githubTokenManager: com.apex.agent.github.GithubTokenManager,
    toolkit: com.apex.agent.ui.screen.agent.toolkit.ChatToolkitStore,
    toolkitState: ToolkitUiState
) {
    // ═══ v5 玻璃输入栏（Agent 屏同款）：Floating 档 + 顶部双角圆角，悬浮栈
    // 布局使本卡悬浮于时间轴之上 → glassState 采样获得真实 backdrop。═══
    GlassCard(
        state = glassState,
        style = GlassStyle.Floating,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            // ═══ v5：技能 chip 已内联进输入框（SkillChipInputField），独立的
            // 胶囊行移除 —— 不再出现「胶囊行叠在输入框上方」的重叠观感。═══

            // ═══ 小圆环状态标签行（搜索/时间/函数/格式/规则芯片，可单独关闭）═══
            ToolkitChipsRow(
                webSearchEnabled = toolkitState.webSearchEnabled,
                timeEnabled = toolkitState.timeEnabled,
                selectedFunctionIds = toolkitState.selectedFunctionIds,
                toolNameOf = { id -> toolkitState.availableTools.firstOrNull { it.id == id }?.name ?: id },
                outputFormat = toolkitState.outputFormat,
                enabledRulesCount = toolkitState.rules.count { it.enabled },
                onCloseWebSearch = { toolkit.setWebSearchEnabled(false) },
                onCloseTime = { toolkit.setTimeEnabled(false) },
                onRemoveFunction = { toolkit.toggleFunction(it) },
                onCloseFormat = { toolkit.setOutputFormat(OutputFormat.NONE) },
                onDisableAllRules = { toolkitState.rules.filter { it.enabled }.forEach { r -> toolkit.setRuleEnabled(r.id, false) } }
            )

            // ═══ 工具栏行：斜杠 + GitHub + 小圆环（横向滚动）═══
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                // ═══ / 斜杠指令按钮（coding 域：开发技能 + coding 工位 MCP）═══
                // v5 多选：流水线条目选中保持展开（继续多选）+ ✓ 勾选态；
                // 再点一次已选条目 = 摘除。返回 false（普通指令）才收起。
                SlashCommandButton(
                    slashMenuProvider = slashMenuProvider,
                    scope = "coding",
                    isSelected = { item ->
                        PendingPipelineCommand.fromCommand(item.command, item.label)
                            ?.let { c -> pendingCommands.any { it.type == c.type && it.id == c.id } }
                            ?: false
                    },
                    onItemSelected = { item ->
                        val capsule = PendingPipelineCommand.fromCommand(item.command, item.label)
                        if (capsule != null) {
                            val exists = pendingCommands.any { it.type == capsule.type && it.id == capsule.id }
                            if (exists) {
                                onChipsChange(pendingCommands.filterNot { it.type == capsule.type && it.id == capsule.id })
                            } else {
                                onAddPendingCommand(capsule)
                            }
                            true // 流水线条目：保持展开，继续多选
                        } else {
                            val merged = if (draft.isBlank()) item.command
                            else draft.trimEnd() + " " + item.command
                            onDraftChange(merged)
                            false
                        }
                    }
                )

                // ═══ GitHub 连接按钮（#197 从 Agent 屏迁入）═══
                GithubIconButton(tokenManager = githubTokenManager)

                // ═══ 小圆环：函数调用/搜索/时间/结构化输出/规则（#197 迁入）═══
                ToolkitRingButton(
                    webSearchEnabled = toolkitState.webSearchEnabled,
                    timeEnabled = toolkitState.timeEnabled,
                    selectedFunctionIds = toolkitState.selectedFunctionIds,
                    availableTools = toolkitState.availableTools,
                    outputFormat = toolkitState.outputFormat,
                    customSchema = toolkitState.customSchema,
                    rules = toolkitState.rules,
                    exposeAllTools = toolkitState.exposeAllTools,
                    onToggleWebSearch = { toolkit.setWebSearchEnabled(it) },
                    onToggleTime = { toolkit.setTimeEnabled(it) },
                    onToggleFunction = { toolkit.toggleFunction(it) },
                    onToggleExposeAllTools = { toolkit.setExposeAllTools(it) },
                    onSelectFormat = { toolkit.setOutputFormat(it) },
                    onSetCustomSchema = { toolkit.setCustomSchema(it) },
                    onUpsertRule = { toolkit.upsertRule(it) },
                    onDeleteRule = { toolkit.deleteRule(it) },
                    onToggleRule = { id, enabled -> toolkit.setRuleEnabled(id, enabled) }
                )
            }
            Spacer(Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    // ═══ v5 技能 chip 输入框（与 Agent 屏同款组件）═══
                    SkillChipInputField(
                        value = draft,
                        onValueChange = onDraftChange,
                        chips = pendingCommands,
                        onChipsChange = onChipsChange,
                        placeholder = stringResource(
                            if (llmConfigured) R.string.code_input_hint
                            else R.string.code_input_hint_no_api
                        )
                    )
                    // / 实时联想（coding 域）
                    SlashAutoCompleteHost(
                        inputText = draft,
                        slashMenuProvider = slashMenuProvider,
                        scope = "coding",
                        onItemSelected = { item ->
                            val capsule = PendingPipelineCommand.fromCommand(item.command, item.label)
                            if (capsule != null) {
                                onAddPendingCommand(capsule)
                            } else {
                                onDraftChange(item.command)
                            }
                        }
                    )
                }
                if (isRunning) {
                    IconButton(onClick = onAbort) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(R.string.code_stop),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            // #197 模型 API 未配置：拦截发送并弹浮窗
                            if (!llmConfigured) {
                                onSendBlocked()
                                return@IconButton
                            }
                            if (draft.isNotBlank() || pendingCommands.isNotEmpty()) {
                                onSend(draft)
                            }
                        },
                        enabled = draft.isNotBlank() || pendingCommands.isNotEmpty()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.code_send),
                            tint = if (draft.isNotBlank() || pendingCommands.isNotEmpty()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * #197 模型 API 未配置浮窗（Coding 屏版本；同 Agent 屏语义）。
 * 4.5s 自动消散；Coding 屏无直达设置页的路由参数——提示用户去抽屉设置。
 */
@Composable
private fun ApiMissingFloatingNotice(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4_500)
        onDismiss()
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = androidx.compose.animation.slideInVertically(initialOffsetY = { -it }) +
            androidx.compose.animation.fadeIn(),
        modifier = modifier.padding(top = 8.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(horizontal = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_api_missing_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.chat_api_missing_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { onOpenSettings(); onDismiss() }) {
                    Text(stringResource(R.string.code_api_missing_got_it))
                }
            }
        }
    }
}

// ═══ 对话框 ═══

@Composable
private fun ErrorBar(
    message: String,
    glassState: HazeState?,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    GlassCard(
        state = glassState,
        style = GlassStyle.Floating,
        shape = RoundedCornerShape(12.dp),
        accent = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            // #209：可恢复错误提供「重试」（复用错误条既有 action 按钮模式；
            // 文案复用 chat_retry，与 Agent 屏 RetryChip 同词）。
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.chat_retry))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.code_dismiss))
            }
        }
    }
}

@Composable
private fun PendingQuestionDialog(
    question: String,
    onSubmit: (String) -> Unit
) {
    // rememberSaveable：旋转/重建后已输入的回答不丢（String 可入 Bundle）。
    var answer by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.code_question_title)) },
        text = {
            Column {
                Text(question, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    placeholder = { Text(stringResource(R.string.code_question_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val defaultYes = stringResource(R.string.code_question_default_yes)
            TextButton(
                onClick = { onSubmit(answer.ifBlank { defaultYes }) }
            ) { Text(stringResource(R.string.code_question_submit)) }
        }
    )
}

@Composable
private fun NewWorkspaceDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // rememberSaveable：旋转/重建后已输入的工作区名不丢（String 可入 Bundle）。
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.code_new_workspace_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.code_workspace_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.code_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.code_cancel)) }
        }
    )
}
