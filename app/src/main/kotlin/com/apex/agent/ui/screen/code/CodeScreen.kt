package com.apex.agent.ui.screen.code

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.apex.agent.ui.component.SlashMenuProvider
import com.apex.agent.ui.component.rememberSlashMenuProvider
import com.apex.agent.ui.screen.agent.CODING_SCREEN_MODES
import com.apex.agent.ui.screen.agent.AgentModeSelector
import com.apex.agent.ui.screen.agent.PendingPipelineCommand
import com.apex.agent.ui.screen.agent.PipelineCapsuleRow
import com.apex.agent.ui.screen.agent.PlanConfirmationCard
import com.apex.agent.ui.screen.agent.QuestionCard
import com.apex.agent.ui.screen.agent.ToolRef
import com.apex.agent.ui.screen.agent.ToolkitChipsRow
import com.apex.agent.ui.screen.agent.ToolkitRingButton
import com.apex.agent.core.code.stream.StreamEntry
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.ui.screen.agent.toolkit.OutputFormat
import com.apex.agent.ui.screen.code.editor.CodeEditorPanel
import com.apex.agent.ui.screen.code.longtask.CodeLongTaskSheet
import com.apex.agent.ui.screen.code.stream.CodeStreamTimeline
import com.apex.agent.ui.screen.code.stream.CodeTerminalPanel
import com.apex.agent.ui.screen.code.stream.CodeToolDetailSheet

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
    val state by viewModel.uiState.collectAsState()
    val pendingAgentQuestion by viewModel.pendingAgentQuestion.collectAsState()
    val pendingCommand by viewModel.pendingCommand.collectAsState()
    val llmConfigured by viewModel.llmConfigured.collectAsState()

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
    var showGithubConnectDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.requestGithubConnect.collect { showGithubConnectDialog = true }
    }

    // ═══ #197 模型 API 未配置浮窗（发送拦截）═══
    var showApiMissingNotice by remember { mutableStateOf(false) }

    var showNewWorkspace by remember { mutableStateOf(false) }
    var showThinkingGuide by remember { mutableStateOf(false) }
    // 胶囊详情弹层选中项（存 id 不存对象：StreamToolCall 是不可变快照，
    // 每 25ms 批次按 index 替换新实例——存对象会冻结在点击瞬间，
    // 状态/耗时/Diff 永不更新；存 id 每次重组从活快照重查）
    var selectedToolCallId by remember { mutableStateOf<String?>(null) }
    // 终端面板折叠态（默认展开——BASH 是 Coding 工作流主舞台）
    var terminalCollapsed by remember { mutableStateOf(false) }

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
            onOpenLongTasks = viewModel::openLongTaskCenter
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

        Box(modifier = Modifier.weight(1f)) {
            // 胶囊时间轴（渲染主通道）：Diff + 终端日志 + 结构化错误三件套
            CodeStreamTimeline(
                snapshot = state.stream,
                isStreaming = state.isRunning,
                onToolClick = { call -> selectedToolCallId = call.id }
            )
        }

        // 终端面板（活跃 BASH 的脉冲尾窗；独立锚定与时间轴互不抢占）
        if (state.stream.activeTerminalCallId != null || state.stream.terminalContent.isNotBlank()) {
            CodeTerminalPanel(
                content = state.stream.terminalContent,
                activeCommand = state.stream.activeTerminalCallId,
                collapsed = terminalCollapsed,
                onToggleCollapse = { terminalCollapsed = !terminalCollapsed }
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
                onCancel = viewModel::cancelAgentQuestion
            )
        }

        state.error?.let { err ->
            // #209：运行失败类错误（errorRetriable）提供一键重试；运行中不重复触发。
            ErrorBar(
                message = err,
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
            onPendingCommand = { viewModel.setPendingCommand(it) },
            pendingCommand = pendingCommand,
            onRemovePendingCommand = viewModel::clearPendingCommand,
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
    onOpenLongTasks: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

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
            Box {
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

            Spacer(Modifier.weight(1f))

            // v1.2 长任务中心入口（记录/模板两页签的 ModalBottomSheet）
            IconButton(onClick = onOpenLongTasks, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = stringResource(R.string.code_longtask_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 新会话：图标按钮（对齐 Agent 屏 34/19dp 规格与 History 邻钮密度）
            IconButton(onClick = onClearChat, modifier = Modifier.size(28.dp)) {
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
    onPendingCommand: (PendingPipelineCommand) -> Unit,
    pendingCommand: PendingPipelineCommand?,
    onRemovePendingCommand: () -> Unit,
    githubTokenManager: com.apex.agent.github.GithubTokenManager,
    toolkit: com.apex.agent.ui.screen.agent.toolkit.ChatToolkitStore,
    toolkitState: ToolkitUiState
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            // ═══ 流水线指令胶囊行（[/> skill: 名字 ×]；无挂起不占位）═══
            // 紧凑规格（v2）：胶囊高度收敛到与工具栏按钮同量级，行下留 2dp
            // 间距——旧版 48dp 关闭钮把胶囊撑到 54dp，视觉上“糊”在输入框上。
            PipelineCapsuleRow(
                pending = pendingCommand,
                onRemove = onRemovePendingCommand,
                modifier = Modifier.padding(bottom = 2.dp)
            )

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
                SlashCommandButton(
                    slashMenuProvider = slashMenuProvider,
                    scope = "coding",
                    onItemSelected = { item ->
                        val capsule = PendingPipelineCommand.fromCommand(item.command, item.label)
                        if (capsule != null) {
                            onPendingCommand(capsule)
                        } else {
                            val merged = if (draft.isBlank()) item.command
                            else draft.trimEnd() + " " + item.command
                            onDraftChange(merged)
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
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        placeholder = {
                            Text(
                                stringResource(
                                    if (llmConfigured) R.string.code_input_hint
                                    else R.string.code_input_hint_no_api
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 5,
                        shape = RoundedCornerShape(20.dp)
                    )
                    // / 实时联想（coding 域）
                    SlashAutoCompleteHost(
                        inputText = draft,
                        slashMenuProvider = slashMenuProvider,
                        scope = "coding",
                        onItemSelected = { item ->
                            val capsule = PendingPipelineCommand.fromCommand(item.command, item.label)
                            if (capsule != null) {
                                onPendingCommand(capsule)
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
                            if (draft.isNotBlank() || pendingCommand != null) {
                                onSend(draft)
                            }
                        },
                        enabled = draft.isNotBlank() || pendingCommand != null
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.code_send),
                            tint = if (draft.isNotBlank() || pendingCommand != null) MaterialTheme.colorScheme.primary
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
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
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
    var answer by remember { mutableStateOf("") }
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
    var name by remember { mutableStateOf("") }
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
