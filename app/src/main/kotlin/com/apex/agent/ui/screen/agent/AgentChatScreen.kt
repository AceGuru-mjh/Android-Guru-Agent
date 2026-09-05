package com.apex.agent.ui.screen.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.ui.component.AdaptiveInputField
import com.apex.agent.ui.component.AttachButton
import com.apex.agent.ui.component.AttachmentPreviewBar
import com.apex.agent.ui.component.FileOpener
import com.apex.agent.ui.component.GithubIconButton
import com.apex.agent.ui.component.GithubTokenDialog
import com.apex.agent.ui.component.ImageLightbox
import com.apex.agent.ui.component.SlashAutoCompleteHost
import com.apex.agent.ui.component.SlashCommandButton
import com.apex.agent.ui.component.SlashMenuProvider
import com.apex.agent.ui.screen.agent.toolkit.OutputFormat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    viewModel: AgentChatViewModel = hiltViewModel(),
    slashMenuProvider: SlashMenuProvider = androidx.hilt.navigation.compose.hiltViewModel(),
    onOpenSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // ★ 缺陷 3 修复：inputText 提升到 ViewModel + SavedStateHandle，跨配置变更存活
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val pendingQuestion by viewModel.pendingQuestion.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scrollScope = rememberCoroutineScope()

    // ═══ "小大脑" + "小圆环"菜单状态收集 ═══
    val toolkit = viewModel.toolkitStore
    val webSearchEnabled by toolkit.webSearchEnabled.collectAsStateWithLifecycle()
    val timeEnabled by toolkit.timeEnabled.collectAsStateWithLifecycle()
    val selectedFunctionIds by toolkit.selectedFunctionIds.collectAsStateWithLifecycle()
    val outputFormat by toolkit.outputFormat.collectAsStateWithLifecycle()
    val customSchema by toolkit.customSchema.collectAsStateWithLifecycle()
    val rules by toolkit.rules.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val currentProfileId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    // 函数调用二级菜单候选工具（注册表快照，v2：含类别/风险元数据）。
    // 缺陷 6 修复：用 viewModel.toolCount 作 key，
    // 注册表变更后下次重组即重新读取，避免 remember{} 永久缓存导致新装 Skill/插件不出现。
    val availableTools = remember(viewModel.toolCount) {
        viewModel.availableTools()
    }

    // Lightbox 状态：点击附件图片时展开全屏预览
    var lightboxImage by remember { mutableStateOf<Any?>(null) }

    // ═══ T76：任务状态卡 + 崩溃恢复横幅状态 ═══
    val taskState by viewModel.taskState.collectAsStateWithLifecycle()
    val recoveryCandidates by viewModel.recoveryCandidates.collectAsStateWithLifecycle()
    val showTaskCard = taskState?.isActive == true

    // ═══ 自定义模式指令对话框（点击 Custom 模式 chip 时打开）═══
    var showCustomInstructionDialog by remember { mutableStateOf(false) }
    val customInstruction by viewModel.customInstruction.collectAsStateWithLifecycle()

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

    // 仅在用户处于底部 或 未进入阅读模式时自动滚动。
    // key 只含"列表结构变化"（消息数 / 流式项出现与消失 / 加载态），
    // 不含 currentResponse 文本——否则每 token 重启动画导致抖动。
    // 流式期间用即时 scrollToItem（跟手、无动画叠加）；非流式收尾保留动画。
    LaunchedEffect(
        uiState.messages.size,
        uiState.isLoading,
        uiState.currentThinking.isNotEmpty(),
        uiState.currentResponse.isNotEmpty(),
        uiState.currentToolCall != null,
        pendingQuestion != null
    ) {
        val total = uiState.messages.size +
            (if (uiState.currentThinking.isNotEmpty()) 1 else 0) +
            (if (uiState.currentResponse.isNotEmpty()) 1 else 0) +
            (if (uiState.currentToolCall != null) 1 else 0) +
            (if (pendingQuestion != null) 1 else 0)
        if (total > 0 && (isAtBottom || !userScrolledUp)) {
            if (uiState.isLoading) {
                listState.scrollToItem(total - 1)
            } else {
                listState.animateScrollToItem(total - 1)
            }
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
                // ═══ 模式切换（6 种模式，横向滚动）═══
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AgentMode.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.mode == mode,
                            onClick = {
                                viewModel.setMode(mode)
                                // Custom 模式：弹出指令编辑对话框（可反复点击修改）
                                if (mode == AgentMode.CUSTOM) {
                                    showCustomInstructionDialog = true
                                }
                            },
                            label = { Text(mode.displayName) }
                        )
                    }
                }

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

        // ═══ T76：崩溃恢复横幅（重启后发现未完成任务）═══
        if (recoveryCandidates.isNotEmpty()) {
            TaskRecoveryBanner(
                tasks = recoveryCandidates,
                onResume = { viewModel.resumeCrashedTask(it.taskId) },
                onDismiss = { viewModel.dismissCrashedTask() }
            )
        }

        // ═══ T76：任务状态卡（活跃任务时显示进度 + 控制）═══
        if (showTaskCard && taskState != null) {
            TaskStatusCard(
                task = taskState!!,
                statusLabel = { status -> statusLabelOf(status) },
                onPause = { viewModel.pauseTask() },
                onResume = { viewModel.resumeTask() },
                onCancel = { viewModel.cancelTask() },
                onRetry = { viewModel.retryTask() },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
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
            itemsIndexed(uiState.messages, key = { _, m -> m.id }) { _, message ->
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

            // Spec 确认
            if (uiState.awaitingSpecConfirmation && uiState.spec != null) {
                item {
                    SpecConfirmationCard(
                        spec = uiState.spec!!,
                        onConfirm = { viewModel.submitSpecConfirmation(true) },
                        onReject = { viewModel.submitSpecConfirmation(false) }
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

                // ═══ "小圆环"工具菜单状态标签行（可单独关闭）═══
                ToolkitChipsRow(
                    webSearchEnabled = webSearchEnabled,
                    timeEnabled = timeEnabled,
                    selectedFunctionIds = selectedFunctionIds,
                    toolNameOf = { id -> availableTools.firstOrNull { it.id == id }?.name ?: id },
                    outputFormat = outputFormat,
                    enabledRulesCount = rules.count { it.enabled },
                    onCloseWebSearch = { toolkit.setWebSearchEnabled(false) },
                    onCloseTime = { toolkit.setTimeEnabled(false) },
                    onRemoveFunction = { toolkit.toggleFunction(it) },
                    onCloseFormat = { toolkit.setOutputFormat(OutputFormat.NONE) },
                    onDisableAllRules = { rules.filter { it.enabled }.forEach { r -> toolkit.setRuleEnabled(r.id, false) } }
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
                    // ═══ / 实时联想（输入以 / 开头时弹出命令候选，点击回填）═══
                    SlashAutoCompleteHost(
                        inputText = inputText,
                        slashMenuProvider = slashMenuProvider,
                        onCommandSelected = { viewModel.updateInputText(it) }
                    )

                    // ═══ 迷你小圆环：工具菜单（搜索/时间/函数/结构化输出/规则）═══
                    ToolkitRingButton(
                        webSearchEnabled = webSearchEnabled,
                        timeEnabled = timeEnabled,
                        selectedFunctionIds = selectedFunctionIds,
                        availableTools = availableTools,
                        outputFormat = outputFormat,
                        customSchema = customSchema,
                        rules = rules,
                        onToggleWebSearch = { toolkit.setWebSearchEnabled(it) },
                        onToggleTime = { toolkit.setTimeEnabled(it) },
                        onToggleFunction = { toolkit.toggleFunction(it) },
                        onSelectFormat = { toolkit.setOutputFormat(it) },
                        onSetCustomSchema = { toolkit.setCustomSchema(it) },
                        onUpsertRule = { toolkit.upsertRule(it) },
                        onDeleteRule = { toolkit.deleteRule(it) },
                        onToggleRule = { id, enabled -> toolkit.setRuleEnabled(id, enabled) },
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

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

                    // ═══ 小大脑：模型切换 + 参数调节 + 配置跳转 ═══
                    BrainMenuButton(
                        profiles = profiles,
                        currentProfileId = currentProfileId ?: "",
                        providerNameOf = { providerId ->
                            providers.firstOrNull { it.id == providerId }?.displayName ?: "未知 Provider"
                        },
                        onSelectProfile = { viewModel.selectProfile(it) },
                        onParamsChanged = { t, p, m -> viewModel.updateModelParams(t, p, m) },
                        onConfigure = onOpenSettings,
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
                                text = when (uiState.mode) {
                                    AgentMode.PLAN -> "描述任务，Agent先规划..."
                                    AgentMode.SPEC -> "描述需求，Agent先产出规格..."
                                    AgentMode.REFLECTION -> "描述任务，Agent生成→评审→修正..."
                                    AgentMode.HUMAN_ASSIST -> "描述任务，有选择时Agent弹出选项菜单..."
                                    AgentMode.CUSTOM -> "输入指令（自定义模式生效）..."
                                    AgentMode.BUILD -> "输入指令，/ 触发快捷..."
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

    // ═══ 自定义模式指令对话框（点击 Custom 模式 chip 时打开）═══
    if (showCustomInstructionDialog) {
        CustomInstructionDialog(
            initial = customInstruction,
            onDismiss = { showCustomInstructionDialog = false },
            onSave = { text ->
                viewModel.setCustomInstruction(text)
                showCustomInstructionDialog = false
            },
            onClear = {
                viewModel.setCustomInstruction("")
                showCustomInstructionDialog = false
            }
        )
    }
}


/**
 * T76 — 任务状态文案（TaskStatusCard 用；与 Controller 状态机一致）。
 */
private fun statusLabelOf(status: com.apex.agent.core.engine.task.TaskStatus): String = when (status) {
    com.apex.agent.core.engine.task.TaskStatus.PENDING -> "准备中"
    com.apex.agent.core.engine.task.TaskStatus.PLANNING -> "规划中"
    com.apex.agent.core.engine.task.TaskStatus.RUNNING -> "执行中"
    com.apex.agent.core.engine.task.TaskStatus.WAITING_USER -> "等待输入"
    com.apex.agent.core.engine.task.TaskStatus.PAUSED -> "已暂停"
    com.apex.agent.core.engine.task.TaskStatus.CANCELLING -> "正在取消"
    com.apex.agent.core.engine.task.TaskStatus.RECOVERING -> "崩溃恢复"
    com.apex.agent.core.engine.task.TaskStatus.RETRYING -> "重试中"
    com.apex.agent.core.engine.task.TaskStatus.COMPLETED -> "已完成"
    com.apex.agent.core.engine.task.TaskStatus.FAILED -> "失败"
    com.apex.agent.core.engine.task.TaskStatus.CANCELLED -> "已取消"
}
