package com.apex.agent.ui.screen.agent

import com.apex.agent.ui.component.MarkdownText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Stroke
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.hilt.navigation.compose.hiltViewModel
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.InputType
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
                    vm = viewModel,
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

            // Agent 通过 ask_user 请求用户输入，等待用户回答
            uiState.pendingUserInput?.let { request ->
                item {
                    UserInputDialog(
                        request = request,
                        onSubmit = { viewModel.submitUserInput(it) },
                        onCancel = { viewModel.cancelUserInput() }
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

            // Agent 通过 ask_user 工具主动等待用户输入
            uiState.pendingUserInput?.let { request ->
                item {
                    UserInputDialog(
                        request = request,
                        onSubmit = { viewModel.submitUserInput(it) },
                        onCancel = { viewModel.cancelUserInput() }
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

                    // ═══ 输入框（自适应高度 + 手势扩展 + 双击全屏 + IME 发送）═══
                    AdaptiveInputField(
                        value = inputText,
                        onValueChange = { viewModel.updateInputText(it) },
                        modifier = Modifier.weight(1f),
                        onSend = {
                            if (inputText.isNotBlank() && !uiState.isLoading) {
                                viewModel.sendMessage(inputText.trim())
                            }
                        },
                        placeholder = {
                            Text(
                                if (uiState.mode == AgentMode.PLAN) {
                                    "描述任务，Agent先规划..."
                                } else {
                                    "输入指令，/ 触发快捷..."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )

                    // ═══ 发送/停止（带按压缩放反馈）═══
                    val sendInteraction = remember { MutableInteractionSource() }
                    val isSendPressed by sendInteraction.collectIsPressedAsState()
                    val sendScale by animateFloatAsState(
                        targetValue = if (isSendPressed) 0.88f else 1f,
                        animationSpec = tween(durationMillis = 100),
                        label = "send_press_scale"
                    )
                    if (uiState.isLoading) {
                        FilledTonalIconButton(
                            onClick = { viewModel.abort() },
                            interactionSource = sendInteraction,
                            modifier = Modifier
                                .size(40.dp)
                                .scale(sendScale)
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
                            interactionSource = sendInteraction,
                            modifier = Modifier
                                .size(40.dp)
                                .scale(sendScale)
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
    vm: AgentChatViewModel,
    onImageClick: (MessageAttachment) -> Unit = {},
    onFileClick: (MessageAttachment) -> Unit = {}
) {
    when (message) {
        is AgentUiMessage.User -> UserBubble(message, onImageClick, onFileClick)
        is AgentUiMessage.Agent -> AgentBubble(
            message = message,
            onOrganize = { text ->
                Toast.makeText(LocalContext.current, "已整理到记忆", Toast.LENGTH_SHORT).show()
                vm.organizeToMemory(text)
            }
        )
        is AgentUiMessage.ToolCall -> ToolCallCard(message)
        is AgentUiMessage.System -> SystemMessage(message.text)
        is AgentUiMessage.Error -> ErrorBlock(
            message = message.message,
            canRetry = message.canRetry,
            onRetry = {
                val lastUser = vm.uiState.value.messages.lastOrNull { it is AgentUiMessage.User } as? AgentUiMessage.User
                lastUser?.let { vm.retry(it.text, it.attachments) }
            }
        )
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
    val timeStr = remember(message.timestamp) {
        DateTimeFormatter.ofPattern("HH:mm").format(
            java.time.Instant.ofEpochMilli(message.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 角色标识 + 时间戳
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Text(
                        text = "YOU",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }

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
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentBubble(
    message: AgentUiMessage.Agent,
    onOrganize: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val timeStr = remember(message.timestamp) {
        DateTimeFormatter.ofPattern("HH:mm").format(
            java.time.Instant.ofEpochMilli(message.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        val outlineVariant = MaterialTheme.colorScheme.outlineVariant
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            modifier = Modifier
                .widthIn(max = 340.dp)
                .drawBehind {
                    drawRoundRect(
                        color = outlineVariant,
                        style = Stroke(width = 1.dp.toPx()),
                        cornerRadius = CornerRadius(14.dp.toPx())
                    )
                }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 头像 + 角色标识 + 时间戳
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "✦",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text(
                        text = "AGENT",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 正文（Markdown 渲染：支持代码块 / 行内代码 / 粗体 / 列表）
                SelectionContainer {
                    MarkdownText(markdown = message.text)
                }

                // 操作行：复制 / 整理到记忆（UI 占位，暂未接入 CS-Mem 后端）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(message.text))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            onOrganize(message.text)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = "整理到记忆",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingResponseBubble(text: String) {
    val pulse by rememberInfiniteTransition(label = "stream-cursor").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "cursor-alpha"
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        val outlineVariant = MaterialTheme.colorScheme.outlineVariant
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            modifier = Modifier
                .widthIn(max = 340.dp)
                .drawBehind {
                    drawRoundRect(
                        color = outlineVariant,
                        style = Stroke(width = 1.dp.toPx()),
                        cornerRadius = CornerRadius(14.dp.toPx())
                    )
                }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "✦",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text(
                        text = "AGENT",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                SelectionContainer {
                    MarkdownText(markdown = text)
                }
                Text(
                    text = "▍",
                    color = MaterialTheme.colorScheme.primary.copy(alpha = pulse),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp, start = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun ThinkingBubble(text: String) {
    var expanded by remember { mutableStateOf(false) }

    val tertiaryColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = tertiaryColor,
                    style = Stroke(width = 1.dp.toPx()),
                    cornerRadius = CornerRadius(12.dp.toPx())
                )
            }
            .clickable {
                expanded = !expanded
            }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                    modifier = Modifier.padding(0.dp)
                ) {
                    Text(
                        text = "THINK",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = if (expanded) "思考过程" else "推理中…",
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

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 工具来源分类的视觉规格：图标 + 标签 + 主题色。
 * 集中管理，保证 ToolCallCard / RunningToolCallCard / ErrorBlock 一致。
 */
private data class ToolKindStyle(
    val label: String,
    val icon: ImageVector,
    val color: Color
)

@Composable
private fun toolKindStyle(kind: ToolKind): ToolKindStyle = when (kind) {
    ToolKind.LOCAL -> ToolKindStyle(
        "本地工具", Icons.Default.Build,
        MaterialTheme.colorScheme.primary
    )
    ToolKind.MCP -> ToolKindStyle(
        "MCP", Icons.Default.Hub,
        MaterialTheme.colorScheme.tertiary
    )
    ToolKind.WEB_SEARCH -> ToolKindStyle(
        "联网搜索", Icons.Default.Search,
        MaterialTheme.colorScheme.secondary
    )
    ToolKind.WEB_FETCH -> ToolKindStyle(
        "网页抓取", Icons.Default.Language,
        MaterialTheme.colorScheme.secondary
    )
    ToolKind.SKILL -> ToolKindStyle(
        "Skill", Icons.Default.AutoAwesome,
        MaterialTheme.colorScheme.primary
    )
}

/**
 * 工具调用来源徽章（图标 + 文字 + 浅色底），用于区分 本地/MCP/搜索/抓取/Skill。
 */
@Composable
private fun ToolKindBadge(kind: ToolKind, server: String? = null, skill: String? = null) {
    val style = toolKindStyle(kind)
    val color = style.color
    val label = when (kind) {
        ToolKind.SKILL -> skill?.let { "Skill: $it" } ?: style.label
        ToolKind.MCP -> server?.let { "MCP · $it" } ?: style.label
        else -> style.label
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.heightIn(min = 22.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}

@Composable
private fun ToolCallCard(toolCall: AgentUiMessage.ToolCall) {
    var expanded by remember { mutableStateOf(false) }
    val isError = toolCall.success == false
    val kindStyle = toolKindStyle(toolCall.kind)
    val accent = if (isError) MaterialTheme.colorScheme.error else kindStyle.color

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 来源图标（带类型色圆形底）
                Surface(
                    color = accent.copy(alpha = 0.16f),
                    shape = CircleShape,
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = kindStyle.icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = toolCall.toolName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // 状态徽章
                        val status = when {
                            toolCall.success == true -> Pair("完成", MaterialTheme.colorScheme.primary)
                            toolCall.success == false -> Pair("失败", MaterialTheme.colorScheme.error)
                            else -> Pair("运行", MaterialTheme.colorScheme.secondary)
                        }
                        Surface(
                            color = status.second.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = status.first,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = status.second,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    ToolKindBadge(toolCall.kind, toolCall.server, toolCall.skill)
                }

                if (toolCall.durationMs > 0) {
                    Text(
                        text = "${toolCall.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
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

            val outputText = if (expanded) toolCall.fullOutput ?: toolCall.output else toolCall.output

            outputText?.let { output ->
                Spacer(modifier = Modifier.height(6.dp))
                if (toolCall.kind == ToolKind.WEB_SEARCH) {
                    // 联网搜索：优先解析为结构化结果卡片；解析失败回退纯文本。
                    val results = remember(output) { parseWebSearchResults(output) }
                    if (results.isNotEmpty()) {
                        WebSearchResultsCard(results, query = extractSearchQuery(output))
                    } else {
                        PlainOutputBlock(output, expanded, if (expanded) "完整输出" else "输出摘要")
                    }
                } else {
                    PlainOutputBlock(output, expanded, if (expanded) "完整输出" else "输出摘要")
                }
            }
        }
    }
}

/**
 * 纯文本输出块（参数/输出通用），可折叠高度 + 横向滚动（等宽字体）。
 */
@Composable
private fun PlainOutputBlock(text: String, expanded: Boolean, label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
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

/**
 * 从 WebSearchTool 的文本输出中解析出结构化搜索结果。
 * 工具输出形如：
 *   Search results for: "query" (N results)
 *   ---
 *   1. Title
 *      URL: https://...
 *      snippet text
 * 解析失败（如被截断/格式变化）时返回空列表，由调用方回退纯文本。
 */
private fun parseWebSearchResults(text: String): List<WebSearchItem> {
    val items = mutableListOf<WebSearchItem>()
    val pattern = Regex(
        """(\d+)\.\s+(.+?)\s*\n\s*URL:\s*(\S+)\s*\n\s*(.*?)(?=\n\s*\n\s*\d+\.\s|\n\s*\nUse web_fetch|$)""",
        RegexOption.DOT_MATCHES_ALL
    )
    for (m in pattern.findAll(text)) {
        val title = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
        val url = m.groupValues[3].trim()
        val snippet = m.groupValues[4].replace(Regex("<[^>]+>"), "").trim()
        if (title.isNotBlank() && url.startsWith("http")) {
            items.add(WebSearchItem(title, url, snippet))
        }
    }
    return items
}

private fun extractSearchQuery(text: String): String? {
    val m = Regex("""Search results for:\s*"(.*?)"""").find(text) ?: return null
    return m.groupValues[1].trim().takeIf { it.isNotBlank() }
}

private data class WebSearchItem(val title: String, val url: String, val snippet: String)

/**
 * 联网搜索结果的结构化卡片：标题 + 域名 + 摘要 + 外链图标。
 * 点击在新窗口打开（Android 上用隐式 Intent 打开浏览器）。
 */
@Composable
private fun WebSearchResultsCard(results: List<WebSearchItem>, query: String?) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        query?.let {
            Text(
                text = "🔍 搜索：$it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        results.forEach { item ->
            val host = runCatching { java.net.URI(item.url).host }.getOrNull() ?: item.url
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(item.url)
                            )
                            context.startActivity(intent)
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = host,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.snippet.isNotBlank()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = item.snippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "打开链接",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RunningToolCallCard(toolCall: AgentToolCallUi) {
    val kindStyle = toolKindStyle(toolCall.kind)
    val accent = kindStyle.color

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = accent.copy(alpha = 0.10f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = accent.copy(alpha = 0.18f),
                    shape = CircleShape,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = kindStyle.icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = toolCall.toolName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    ToolKindBadge(toolCall.kind, toolCall.server, toolCall.skill)
                }
                // 脉冲进度环（运行态）
                val transition = rememberInfiniteTransition(label = "toolRunning")
                val ringAlpha by transition.animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "ringAlpha"
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.5.dp,
                    color = accent.copy(alpha = ringAlpha)
                )
            }

            // ═══ 进度条 + 进度说明（由 ToolProgress 事件驱动）═══
            if (toolCall.progress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { toolCall.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
            } else if (!toolCall.progressMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accent)
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
            if (toolCall.output.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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

/**
 * 错误提示块：区别于灰色 System 行，使用红色高亮卡片 + 图标 + 可选重试。
 */
@Composable
private fun ErrorBlock(
    message: String,
    canRetry: Boolean = false,
    onRetry: () -> Unit = {}
) {
    val errorColor = MaterialTheme.colorScheme.error
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = errorColor,
                    style = Stroke(width = 1.5.dp.toPx()),
                    cornerRadius = CornerRadius(12.dp.toPx())
                )
            }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "执行出错",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.weight(1f))
                if (canRetry) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { onRetry() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "重试",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onError
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
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

/**
 * ask_user 工具触发的用户输入对话框。
 * 用户提交后引擎恢复执行；取消则中止等待。
 */
@Composable
private fun UserInputDialog(
    request: UserInputRequest,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val isChoice = request.type == InputType.CHOICE
    val isConfirmation = request.type == InputType.CONFIRMATION

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = { onSubmit(text) },
                enabled = !isChoice // 选项类暂以确认框展示，提交默认空串
            ) {
                Text("提交")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
        title = { Text("需要你的输入") },
        text = {
            Column {
                Text(
                    text = request.prompt,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (!isConfirmation) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("你的回答") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6
                    )
                } else {
                    Text(
                        text = "点击「提交」以确认，或「取消」拒绝。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
