package com.apex.agent.ui.screen.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.llm.ReasoningEffort
import com.apex.agent.ui.component.AttachButton
import com.apex.agent.ui.component.AdaptiveInputField
import com.apex.agent.ui.component.AttachmentPreviewBar
import com.apex.agent.ui.component.FileOpener
import com.apex.agent.ui.component.GithubIconButton
import com.apex.agent.ui.component.GithubTokenDialog
import com.apex.agent.ui.component.ImageLightbox
import com.apex.agent.ui.component.MessageAttachmentList
import com.apex.agent.ui.component.SlashCommandButton
import com.apex.agent.ui.component.SlashMenuProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    viewModel: AgentChatViewModel = hiltViewModel(),
    slashMenuProvider: SlashMenuProvider = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // ★ 缺陷 3 修复：inputText 提升到 ViewModel + SavedStateHandle，跨配置变更存活
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val pendingQuestion by viewModel.pendingQuestion.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scrollScope = rememberCoroutineScope()

    // Lightbox 状态：点击附件图片时展开全屏预览
    var lightboxImage by remember { mutableStateOf<Any?>(null) }

    // ═══ /mcp:github 未连接时的连接对话框 ═══
    // ViewModel 在路由 /mcp:github 时若发现 GitHub 未连接，会发射 requestGithubConnect
    // 一次性事件；这里收集后打开复用的 GithubTokenDialog，避免用户必须先点输入栏 GitHub
    // 图标才能连接 —— 让斜杠命令自身引导完成连接闭环。
    var showGithubConnectDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.requestGithubConnect.collect { showGithubConnectDialog = true }
    }

    // ═══ 缺陷 4 修复：智能滚动策略 ═══
    // 追踪用户是否在底部附近（150px 阈值）
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            if (lastVisibleItem == null) true
            else {
                val viewportHeight = layoutInfo.viewportSize.height
                val itemBottom = lastVisibleItem.offset + lastVisibleItem.size
                val distanceToBottom = viewportHeight - itemBottom
                distanceToBottom < 150
            }
        }
    }

    // 追踪用户是否主动向上滑动（进入「阅读模式」）
    var userScrolledUp by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val totalItems = uiState.messages.size
            if (firstVisibleIndex < totalItems - 2) {
                userScrolledUp = true
            }
        }
    }

    // 仅在用户处于底部 或 未进入阅读模式时自动滚动
    LaunchedEffect(uiState.messages.size, uiState.currentResponse) {
        val total = uiState.messages.size +
            (if (uiState.currentThinking.isNotEmpty()) 1 else 0) +
            (if (uiState.currentResponse.isNotEmpty()) 1 else 0) +
            (if (uiState.currentToolCall != null) 1 else 0) +
            (if (pendingQuestion != null) 1 else 0)
        if (total > 0 && (isAtBottom || !userScrolledUp)) {
            listState.animateScrollToItem(total - 1)
            userScrolledUp = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {

        // ═══ 顶部模式栏 ═══
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 模式切换
                FilterChip(
                    selected = uiState.mode == AgentMode.BUILD,
                    onClick = { viewModel.setMode(AgentMode.BUILD) },
                    label = { Text("Build") }
                )
                FilterChip(
                    selected = uiState.mode == AgentMode.PLAN,
                    onClick = { viewModel.setMode(AgentMode.PLAN) },
                    label = { Text("Plan") }
                )

                Spacer(modifier = Modifier.width(4.dp))

                // 思考深度
                ThinkingLevelSelector(
                    current = uiState.thinkingLevel,
                    onSelect = { viewModel.setThinkingLevel(it) }
                )

                Spacer(modifier = Modifier.weight(1f))

                // 新会话按钮
                IconButton(
                    onClick = { viewModel.newChat() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "新会话",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ═══ 消息列表 ═══
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            itemsIndexed(uiState.messages, key = { index, _ -> index }) { _, message ->
                AgentMessageItem(
                    message = message,
                    onImageClick = { att ->
                        lightboxImage = att.thumbnailUri ?: att.localPath
                    },
                    onFileClick = { att ->
                        att.localPath?.let { FileOpener.openFile(context, it, att.mimeType) }
                    }
                )
            }

            // 流式思考中
            if (uiState.currentThinking.isNotEmpty()) {
                item { ThinkingBubble(uiState.currentThinking) }
            }

            // 流式回复中
            if (uiState.currentResponse.isNotEmpty()) {
                item { StreamingResponseBubble(uiState.currentResponse) }
            }

            // 当前工具调用
            uiState.currentToolCall?.let { toolCall ->
                item { RunningToolCallCard(toolCall) }
            }

            // Plan 确认
            if (uiState.awaitingPlanConfirmation && uiState.plan != null) {
                item {
                    PlanConfirmationCard(
                        plan = uiState.plan!!,
                        onConfirm = { viewModel.confirmPlan(true) },
                        onReject = { viewModel.confirmPlan(false) }
                    )
                }
            }

            // Agent 主动提问
            pendingQuestion?.let { question ->
                item {
                    QuestionCard(
                        question = question,
                        onAnswer = { optionId, customText ->
                            viewModel.answerQuestion(optionId, customText)
                        },
                        onCancel = {
                            viewModel.cancelQuestion()
                        }
                    )
                }
            }
        }

        // ═══ 加载条 ═══
        AnimatedVisibility(uiState.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // ═══ 输入栏（/ 斜杠 + GitHub + 旋转加号 + 输入框 + 发送）═══
        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                // ═══ 附件预览条（发送前）═══
                val attachments by viewModel.attachments.collectAsStateWithLifecycle()
                AttachmentPreviewBar(
                    attachments = attachments,
                    onRemove = { index -> viewModel.removeAttachment(index) }
                )

                // 模型原生思考强度 chip
                ReasoningEffortRow(
                    current = uiState.reasoningEffort,
                    onSelect = { viewModel.setReasoningEffort(it) }
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // ═══ / 斜杠指令按钮 ═══
                    SlashCommandButton(
                        slashMenuProvider = slashMenuProvider,
                        onCommandSelected = { command ->
                            // Insert the command rather than overwriting existing input.
                            // If the user has already typed something (e.g.
                            // "请帮我用 ... 查询"), the selected command is space-joined
                            // after it so the original intent is preserved. The command
                            // itself carries a trailing space so the user can keep typing
                            // arguments right away.
                            val merged = if (inputText.isBlank()) {
                                command
                            } else {
                                inputText.trimEnd() + " " + command
                            }
                            viewModel.updateInputText(merged)
                        },
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // ═══ GitHub 连接状态按钮 ═══
                    GithubIconButton(
                        tokenManager = viewModel.githubTokenManager,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // ═══ + 旋转附件按钮 ═══
                    AttachButton(
                        onFileSelected = { uri ->
                            viewModel.attachFile(uri)
                        },
                        onImageSelected = { uri ->
                            viewModel.attachImage(uri)
                        },
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // ═══ 输入框（自适应高度 + 手势扩展 + 双击全屏）═══
                    AdaptiveInputField(
                        value = inputText,
                        onValueChange = { viewModel.updateInputText(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (uiState.mode == AgentMode.PLAN) {
                                    "描述任务，Agent先规划..."
                                } else {
                                    "输入指令，/ 触发快捷..."
                                }
                            )
                        }
                    )

                    // ═══ 发送/停止 ═══
                    if (uiState.isLoading) {
                        FilledTonalIconButton(
                            onClick = { viewModel.abort() },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "停止")
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    viewModel.sendMessage(inputText.trim())
                                    // ★ viewModel.sendMessage 内部已调用 updateInputText("")
                                }
                            },
                            enabled = inputText.isNotBlank(),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                        }
                    }
                }
            }
        }
    }

        // ═══ 缺陷 4 修复：回到底部 FAB ═══
        // 当用户向上滚动且 Agent 正在输出时，显示"回到底部"按钮
        AnimatedVisibility(
            visible = userScrolledUp && uiState.isLoading,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 80.dp, end = 16.dp)
        ) {
            FilledIconButton(
                onClick = {
                    userScrolledUp = false
                    scrollScope.launch {
                        val total = uiState.messages.size +
                            (if (uiState.currentThinking.isNotEmpty()) 1 else 0) +
                            (if (uiState.currentResponse.isNotEmpty()) 1 else 0) +
                            (if (uiState.currentToolCall != null) 1 else 0) +
                            (if (pendingQuestion != null) 1 else 0)
                        if (total > 0) {
                            listState.animateScrollToItem(total - 1)
                        }
                    }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "回到底部")
            }
        }
    }

    // ═══ Lightbox 全屏预览（点击附件图片时展开）═══
    if (lightboxImage != null) {
        ImageLightbox(
            imageModel = lightboxImage!!,
            onDismiss = { lightboxImage = null }
        )
    }

    // ═══ /mcp:github 连接对话框（未连接时由 ViewModel 信号触发）═══
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
}

// ═══ 消息组件 ═══

@Composable
private fun AgentMessageItem(
    message: AgentUiMessage,
    onImageClick: (MessageAttachment) -> Unit = {},
    onFileClick: (MessageAttachment) -> Unit = {}
) {
    when (message) {
        is AgentUiMessage.User -> UserBubble(message, onImageClick, onFileClick)
        is AgentUiMessage.Agent -> AgentBubble(message.text)
        is AgentUiMessage.ToolCall -> ToolCallCard(message)
        is AgentUiMessage.System -> SystemMessage(message.text)
        is AgentUiMessage.ThinkingMessage -> ThinkingBubble(message.thought)
        is AgentUiMessage.PlanMessage -> PlanCard(message.plan)
    }
}

@Composable
private fun UserBubble(
    message: AgentUiMessage.User,
    onImageClick: (MessageAttachment) -> Unit = {},
    onFileClick: (MessageAttachment) -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 附件展示（如果有）
                if (message.attachments.isNotEmpty()) {
                    MessageAttachmentList(
                        attachments = message.attachments,
                        onFileClick = onFileClick,
                        onImageClick = onImageClick,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                // 文本内容
                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StreamingResponseBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = text, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = "▊",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ThinkingBubble(text: String) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                expanded = !expanded
            }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("🧠", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = if (expanded) "思考过程" else "Thinking...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "折叠思考内容" else "展开思考内容",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ToolCallCard(toolCall: AgentUiMessage.ToolCall) {
    var expanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                expanded = !expanded
            },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = when {
                        toolCall.success == true -> "✅"
                        toolCall.success == false -> "❌"
                        else -> "🔧"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = toolCall.toolName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                if (toolCall.durationMs > 0) {
                    Text(
                        text = "${toolCall.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "折叠工具详情" else "展开工具详情",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded && toolCall.args.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "参数",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = toolCall.args,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState())
                    )
                }
            }

            val outputText = if (expanded) {
                toolCall.fullOutput ?: toolCall.output
            } else {
                toolCall.output
            }

            outputText?.let { output ->
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (expanded) "完整输出" else "输出摘要",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(8.dp)
                            .heightIn(max = if (expanded) 420.dp else 120.dp)
                            .verticalScroll(rememberScrollState()),
                        maxLines = if (expanded) Int.MAX_VALUE else 8,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun RunningToolCallCard(toolCall: AgentToolCallUi) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "⚡ ${toolCall.toolName}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = toolCall.args.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // ═══ 进度条 + 进度说明（由 ToolProgress 事件驱动）═══
            // - progress != null        → 确定性进度条（download_file 有 Content-Length）
            // - progress == null 且有 message → 不定进度条（download_file 无 Content-Length）
            // - 其他                    → 不显示
            if (toolCall.progress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { toolCall.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (!toolCall.progressMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!toolCall.progressMessage.isNullOrBlank()) {
                Text(
                    text = toolCall.progressMessage.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // ═══ 工具实时输出（流式）═══
            // 由 AgentEvent.ToolOutputChunk 逐段累积（ViewModel 16ms 节流刷新）。
            // 实现了 StreamingAgentTool 的工具（shell_execute）会逐行显示；
            // 普通工具一次性显示完整输出。等宽字体 + 可滚动 + maxLines 限制高度，
            // 避免长输出（logcat / ping）撑爆聊天流。
            if (toolCall.output.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = toolCall.output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun SystemMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun PlanCard(plan: ExecutionPlan) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("📋 Execution Plan", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            plan.steps.forEach { step ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${step.index + 1}.", style = MaterialTheme.typography.bodySmall)
                    Text(step.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PlanConfirmationCard(
    plan: ExecutionPlan,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("确认执行此计划？", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReject) { Text("取消") }
                androidx.compose.material3.Button(onClick = onConfirm) { Text("执行") }
            }
        }
    }
}

// ═══ 思考深度选择器 ═══

@Composable
private fun ThinkingLevelSelector(
    current: ThinkingLevel,
    onSelect: (ThinkingLevel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("💭 ${current.name}") },
            leadingIcon = {
                Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ThinkingLevel.entries.forEach { level ->
                DropdownMenuItem(
                    text = { Text("${level.name} - ${level.description}") },
                    onClick = {
                        onSelect(level)
                        expanded = false
                    },
                    trailingIcon = {
                        if (level == current) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
        }
    }
}

/**
 * 模型原生思考强度选择条
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningEffortRow(
    current: ReasoningEffort,
    onSelect: (ReasoningEffort) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "原生思考:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
        ReasoningEffort.entries.forEach { effort ->
            FilterChip(
                selected = effort == current,
                onClick = { onSelect(effort) },
                label = { Text(effort.displayName, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (effort == current) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                } else null
            )
        }
    }
}

@Composable
private fun QuestionCard(
    question: AgentQuestion,
    onAnswer: (String?, String?) -> Unit,
    onCancel: () -> Unit
) {
    var selectedOptionId by remember { mutableStateOf<String?>(null) }
    var customSelected by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }

    val canSubmit = selectedOptionId != null ||
        (customSelected && customText.isNotBlank())

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "🧩 Agent 需要你选择",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = question.title,
                style = MaterialTheme.typography.bodyLarge
            )

            question.description?.let { description ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            question.options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedOptionId = option.id
                            customSelected = false
                        }
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = selectedOptionId == option.id,
                        onClick = {
                            selectedOptionId = option.id
                            customSelected = false
                        }
                    )

                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            if (option.recommended) {
                                Text(
                                    text = "推荐",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        option.description?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (question.allowCustom) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            customSelected = true
                            selectedOptionId = null
                        }
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = customSelected,
                        onClick = {
                            customSelected = true
                            selectedOptionId = null
                        }
                    )

                    Text(
                        text = "自定义",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                if (customSelected) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        placeholder = { Text(question.customPlaceholder) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (question.allowSkip) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("跳过")
                    }
                }

                androidx.compose.material3.Button(
                    onClick = {
                        if (customSelected) {
                            onAnswer(null, customText.trim())
                        } else {
                            onAnswer(selectedOptionId, null)
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("继续")
                }
            }
        }
    }
}
