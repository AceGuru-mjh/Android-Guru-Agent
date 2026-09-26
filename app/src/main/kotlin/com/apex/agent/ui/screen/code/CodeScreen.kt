package com.apex.agent.ui.screen.code

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
import androidx.compose.foundation.layout.imePadding
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
import com.apex.agent.R
import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.platform.code.ws.CodeWorkspace
import com.apex.agent.ui.component.MarkdownText
import com.apex.agent.ui.screen.agent.QuestionCard
import com.apex.agent.ui.screen.code.editor.CodeEditorPanel
import com.apex.agent.ui.screen.code.longtask.CodeLongTaskSheet

/**
 * # Code Screen — Coding 模式主屏（与 Agent 聊天屏同级别）
 *
 * 布局：工作区条（切换/新建）→ Todo 面板（可折叠）→ 消息流（用户/助手/
 * 工具卡）→ 输入栏。工具卡对 code_edit / code_write 输出做 diff 感知着色
 * （+绿/-红），其余工具以等宽文本呈现。
 */
@Composable
fun CodeScreen(
    viewModel: CodeViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val pendingAgentQuestion by viewModel.pendingAgentQuestion.collectAsState()
    var showNewWorkspace by remember { mutableStateOf(false) }
    var showThinkingGuide by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
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

        Box(modifier = Modifier.weight(1f)) {
            CodeMessageList(
                messages = state.messages,
                isRunning = state.isRunning,
                modifier = Modifier.fillMaxSize()
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
            ErrorBar(message = err, onDismiss = viewModel::dismissError)
        }

        // v1.2 七档思考系统：输入栏上方的档位选择器（紧凑入口，点开下拉；
        // 下拉底部可进档位指南）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
        ) {
            CodeThinkingSelector(
                current = state.thinkingLevel,
                onSelect = viewModel::setThinkingLevel,
                onOpenGuide = { showThinkingGuide = true }
            )
        }

        CodeInputBar(
            draft = state.inputDraft,
            onDraftChange = viewModel::updateInputDraft,
            isRunning = state.isRunning,
            onSend = viewModel::sendMessage,
            onAbort = viewModel::abort
        )
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

    // v1.2 思考档位指南（ModalBottomSheet）：阶梯总表 + 逐档卡片，可直切档
    if (showThinkingGuide) {
        CodeThinkingGuideSheet(
            currentLevel = state.thinkingLevel,
            onDismiss = { showThinkingGuide = false },
            onSelect = viewModel::setThinkingLevel
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

            Text(
                text = stringResource(R.string.code_clear_chat),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onClearChat() }
                    .padding(6.dp)
            )
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

@Composable
private fun CodeMessageList(
    messages: List<CodeChatMessage>,
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            CodeMessageItem(message)
        }
        if (isRunning) {
            item(key = "running-indicator") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(4.dp)
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.code_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeMessageItem(message: CodeChatMessage) {
    when (message.role) {
        CodeChatMessage.Role.USER -> UserBubble(message.text)
        CodeChatMessage.Role.ASSISTANT -> AssistantMessage(message)
        CodeChatMessage.Role.TOOL -> ToolCard(message)
        CodeChatMessage.Role.SYSTEM -> SystemNote(message.text)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun AssistantMessage(message: CodeChatMessage) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (message.isStreaming && message.text.isEmpty()) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        } else {
            MarkdownText(markdown = message.text)
        }
    }
}

@Composable
private fun SystemNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

/**
 * 工具卡片：code_edit / code_write 的输出含统一 diff（+/- 行），逐行着色；
 * 其余工具等宽文本（可折叠）。
 */
@Composable
private fun ToolCard(message: CodeChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    // v1.0 #153：code_git_diff 输出统一 diff 原文，与 code_edit/write 共用 diff 着色
    val isDiffTool = message.toolName == "code_edit" || message.toolName == "code_write" ||
        message.toolName == "code_git_diff"

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                val (icon, tint) = if (message.isStreaming) {
                    null to MaterialTheme.colorScheme.tertiary
                } else if (message.toolSuccess) {
                    Icons.Default.Check to MaterialTheme.colorScheme.primary
                } else {
                    Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
                }
                if (message.isStreaming) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                } else {
                    icon?.let {
                        Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = message.toolName ?: "tool",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                if (!message.isStreaming && message.durationMs > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${message.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (expanded) "−" else "+",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded || isDiffTool || message.text.length < 400) {
                Spacer(Modifier.height(6.dp))
                val display = if (expanded) message.text else message.text.take(600) + if (message.text.length > 600) "\n…" else ""
                if (isDiffTool) {
                    DiffOutput(display)
                } else {
                    Text(
                        text = display,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

/** diff 感知着色：+ 行绿 / - 行红 / 其余淡。 */
@Composable
private fun DiffOutput(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        text.split('\n').take(120).forEach { line ->
            val (bg, color) = when {
                line.startsWith("+") -> androidx.compose.ui.graphics.Color(0x1400A884) to androidx.compose.ui.graphics.Color(0xFF1B8A5A)
                line.startsWith("-") -> androidx.compose.ui.graphics.Color(0x14D84040) to androidx.compose.ui.graphics.Color(0xFFB03A3A)
                else -> androidx.compose.ui.graphics.Color.Transparent to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            }
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 6.dp, vertical = 0.dp)
            )
        }
    }
}

// ═══ 输入栏 ═══

@Composable
private fun CodeInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    isRunning: Boolean,
    onSend: (String) -> Unit,
    onAbort: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text(stringResource(R.string.code_input_hint), style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                shape = RoundedCornerShape(20.dp)
            )
            Spacer(Modifier.width(8.dp))
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
                        if (draft.isNotBlank()) {
                            onSend(draft)
                        }
                    },
                    enabled = draft.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.code_send),
                        tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ═══ 对话框 ═══

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
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
