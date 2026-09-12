package com.apex.agent.ui.screen.agent

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.EntryPointAccessors
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
import com.apex.agent.ui.component.ViroPetHost
import com.apex.agent.ui.component.ViroPetMood
import com.apex.agent.ui.component.rememberSlashMenuProvider
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassFloatingButton
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.screen.agent.toolkit.OutputFormat
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    viewModel: AgentChatViewModel = hiltViewModel(),
    // v2 闪退修复：旧默认值 hiltViewModel() 要求参数类型是 ViewModel——
    // SlashMenuProvider 是 @Singleton 普通类，进入聊天页时 ViewModelProvider
    // 反射创建必然抛 RuntimeException（K2 仅告警不拦截）。改为经 EntryPoint
    // 从 Hilt SingletonComponent 取真实单例，既不闪退也消除未来版本的编译错误。
    slashMenuProvider: SlashMenuProvider = rememberSlashMenuProvider(),
    onOpenSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // ★ 缺陷 3 修复：inputText 提升到 ViewModel + SavedStateHandle，跨配置变更存活
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val pendingQuestion by viewModel.pendingQuestion.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scrollScope = rememberCoroutineScope()

    // ═══ Liquid Glass：消息列表 = 采样源；悬浮输入栏 / FAB / 加载条 = 玻璃件 ═══
    // Spec §5/§8/§10：输入栏与悬浮操作悬浮于内容之上，真实 backdrop 采样 + 模糊。
    val glassState = remember { HazeState() }

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
    // ═══ UX-3：LLM 配置状态（未配置 && 空会话时在消息区顶部显示引导卡）═══
    val llmConfigured by viewModel.llmConfigured.collectAsStateWithLifecycle()
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

    // ═══ Viro 桌宠情绪：Agent 运行态 → 宠物动画形态（推导见文件尾 viroPetMoodOf）═══
    val viroMood = viroPetMoodOf(uiState, pendingQuestion, taskState)

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

    // 异步动作真实结果反馈（整理入记忆等：原“发起即报成功”，失败也误报）
    LaunchedEffect(Unit) {
        viewModel.uiFeedback.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
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

    // 追踪用户是否主动向上滚动（进入「阅读模式」）
    // P2-1（6-c）反模式修复：原实现只在 isScrollInProgress 翻转瞬间采样一次
    // firstVisibleItemIndex——手势从底部起步时采样值仍在列表尾端，userScrolledUp
    // 永不置位，流式 auto-follow 与用户拖动互相打架。改为 snapshotFlow 监听
    // firstVisibleItemIndex 的递减方向：本屏所有程序化滚动（auto-follow/FAB 回底）
    // 只会使索引递增，索引递减 ⇔ 用户主动向列表上方滚动（拖动/惯性/滚轮）。
    var userScrolledUp by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (index < previousIndex) userScrolledUp = true
            previousIndex = index
        }
    }

    // 修复：列表总项数统一计算（原漏计 Plan 确认 / Spec 确认 / ask_user 对话框三项，
    // 出现时自动滚动定位到对话框上方；FAB 回底同样用此值）。
    // P3-i（6-c）：UserInputDialog 已提升到屏级（不再占列表项），去掉 pendingUserInput
    // 计数，修复 off-by-one（原自动滚动目标索引多 1，仅靠 clamp 兼底）。
    val totalListItems by remember {
        derivedStateOf {
            uiState.messages.size +
                (if (uiState.currentThinking.isNotEmpty()) 1 else 0) +
                (if (uiState.currentResponse.isNotEmpty()) 1 else 0) +
                (if (uiState.currentToolCall != null) 1 else 0) +
                (if (pendingQuestion != null) 1 else 0) +
                (if (uiState.awaitingPlanConfirmation && uiState.plan != null) 1 else 0) +
                (if (uiState.awaitingSpecConfirmation && uiState.spec != null) 1 else 0)
        }
    }

    // 仅在用户处于底部 或 未进入阅读模式时自动滚动。
    // key 只含"列表结构变化"（总项数 / 加载态），不含 currentResponse 文本——
    // 否则每 token 重启动画导致抖动。
    // 流式期间用即时 scrollToItem（跟手、无动画叠加）；非流式收尾保留动画。
    LaunchedEffect(
        totalListItems,
        uiState.isLoading
    ) {
        if (totalListItems > 0 && (isAtBottom || !userScrolledUp)) {
            if (uiState.isLoading) {
                listState.scrollToItem(totalListItems - 1)
            } else {
                listState.animateScrollToItem(totalListItems - 1)
            }
            userScrolledUp = false
        }
    }

    // ═══ P2-2（6-c）：流式期间 auto-follow（节流档位）═══
    // 主 auto-scroll 的键只含"列表结构变化"（totalListItems/isLoading），流式期间
    // 气泡数固定、totalListItems 不变 → 长回复把"底部"推出视口无人跟随。
    // 以（回复+思考）字符数 / 200 作节流档位（键不含文本本身 → 不会每 token
    // 重启 effect）；档位推进且用户在底部附近 / 未进入阅读模式时即时跟随末项。
    LaunchedEffect((uiState.currentResponse.length + uiState.currentThinking.length) / 200) {
        if (uiState.isLoading && totalListItems > 0 && (isAtBottom || !userScrolledUp)) {
            listState.scrollToItem(totalListItems - 1)
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
                onDismiss = { task -> viewModel.dismissCrashedTask(task.taskId) }
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

        // ═══ 消息列表（FAB 收纳进列表区域，不再压住输入栏）═══
        // ═══ 消息列表 = 玻璃采样源；输入栏悬浮其上 —— 真实 backdrop 而非“背景色+blur”冒充 ═══
        // composerInsetPx 动态测量玻璃输入栏高度，保证最后一条消息不被悬浮层遮挡。
        var composerInsetPx by remember { mutableIntStateOf(0) }
        val composerInsetDp = with(LocalDensity.current) { composerInsetPx.toDp() }
        // ═══ UX-4：列表项稳定 key 契约 ═══
        // 消息项用 AgentUiMessage.id（UUID，copy() 保 id）；流式/确认卡等临时项
        // 用固定字符串 key。新增列表项时必须显式提供 key，禁止回落 index ——
        // index key 在删除/截断（UX-1 菜单）时会让气泡内部状态（菜单展开态、
        // ThinkingBubble 折叠态）错位串项，且整列表无差别重组。
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(glassState)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp + composerInsetDp)
        ) {
            // ═══ UX-3：未配置 API 引导卡（空会话 + 未配置模型时置顶显示；
            // 配置完成或出现首条消息即自动消失；固定 key 供 LazyColumn 复用）═══
            if (uiState.messages.isEmpty() && !llmConfigured) {
                item(key = "setup-guide-card") {
                    LlmSetupGuideCard(onOpenSettings = onOpenSettings)
                }
            }

            itemsIndexed(uiState.messages, key = { _, m -> m.id }) { _, message ->
                AgentMessageItem(
                    message = message,
                    vm = viewModel,
                    // UX-1：流式生成中禁用消息删除/重生成（菜单内对应条目置灰，复制仍可用）
                    actionsEnabled = !uiState.isLoading,
                    onImageClick = { att ->
                        lightboxImage = att.thumbnailUri ?: att.localPath
                    },
                    onFileClick = { att ->
                        att.localPath?.let { FileOpener.openFile(context, it, att.mimeType) }
                    }
                )
            }

            // 流式思考中（UX-4：固定 key —— 列表增删时保留 ThinkingBubble 展开态）
            if (uiState.currentThinking.isNotEmpty()) {
                item(key = "streaming-thinking") { ThinkingBubble(uiState.currentThinking) }
            }

            // 流式回复中
            if (uiState.currentResponse.isNotEmpty()) {
                item(key = "streaming-response") { StreamingResponseBubble(uiState.currentResponse) }
            }

            // 当前工具调用
            uiState.currentToolCall?.let { toolCall ->
                item(key = "active-tool-call") { RunningToolCallCard(toolCall) }
            }

            // Plan 确认
            if (uiState.awaitingPlanConfirmation && uiState.plan != null) {
                item(key = "plan-confirmation") {
                    PlanConfirmationCard(
                        plan = uiState.plan!!,
                        onConfirm = { viewModel.confirmPlan(true) },
                        onReject = { viewModel.confirmPlan(false) }
                    )
                }
            }

            // Spec 确认
            if (uiState.awaitingSpecConfirmation && uiState.spec != null) {
                item(key = "spec-confirmation") {
                    SpecConfirmationCard(
                        spec = uiState.spec!!,
                        onConfirm = { viewModel.submitSpecConfirmation(true) },
                        onReject = { viewModel.submitSpecConfirmation(false) }
                    )
                }
            }

            // Agent 主动提问
            pendingQuestion?.let { question ->
                item(key = "agent-question") {
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

            // ═══ 玻璃悬浮层：FAB + 加载条 + 输入栏 —— 悬浮于消息源之上 ═══
            // 覆盖式布局使输入栏与消息重叠 —— 这是 backdrop 采样的前提；
            // 列表用动态 contentPadding 补偿，最后一条消息不会被遮挡。
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                // ═══ 缺陷 4 修复：回到底部 FAB —— GlassFloatingButton 真实采样消息流 ═══
                //（收纳于输入栏上方右缘；顶层 AnimatedVisibility 限定——
                // 此处外层 Column 作用域在隐式接收链上，否则被解析为 ColumnScope 扩展）═══
                androidx.compose.animation.AnimatedVisibility(
                    visible = userScrolledUp && uiState.isLoading,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(bottom = 10.dp, end = 16.dp)
                ) {
                    GlassFloatingButton(
                        icon = Icons.Default.KeyboardArrowDown,
                        contentDescription = "回到底部",
                        onClick = {
                            userScrolledUp = false
                            scrollScope.launch {
                                if (totalListItems > 0) {
                                    listState.animateScrollToItem(totalListItems - 1)
                                }
                            }
                        },
                        state = glassState,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // ═══ 加载条 ═══
                AnimatedVisibility(uiState.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // ═══ Viro 桌宠：站于输入栏上方（独占 56dp 行槽位，不遮挡消息列表与功能控件）═══
                // 情绪随 Agent 运行态切换（思考/执行工具/回复/等待输入/出错/完成），
                // 新会话挥手打招呼、RunSummary 出现跳跃庆祝、点击可随机跳跃/挥手。
                ViroPetHost(
                    mood = viroMood,
                    chatEmpty = uiState.messages.isEmpty(),
                    celebrationKey = (uiState.messages.lastOrNull() as? AgentUiMessage.RunSummary)?.id
                )

                // ═══ 玻璃输入栏（/ 斜杠 + GitHub + 旋转加号 + 输入框 + 发送）═══
                // GlassStyle.Floating：悬浮主面 —— 比卡片更强的 blur/边缘/高光；
                // 文本/光标/IME 行为零改动（AdaptiveInputField 原样保留）。
                GlassCard(
                    state = glassState,
                    style = GlassStyle.Floating,
                    // 精修延续：底部输入面板顶部双角圆角（原矩形硬边 + 矩形阴影）
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { composerInsetPx = it.height }
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
                        modifier = Modifier.padding(bottom = 4.dp) // 对齐修复：与其他 40dp 圆形图标钮统一底垫 4dp
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
                    //（斜杠实时联想收纳进输入框 Box：菜单锚定在文本框下方而非整行左缘）
                    Box(modifier = Modifier.weight(1f)) {
                        AdaptiveInputField(
                            value = inputText,
                            onValueChange = { viewModel.updateInputText(it) },
                            onSend = {
                                // P2-9（6-c）：附件-only 消息同样可发（仅计可用附件；二轮审计 A-1 口径对齐）
                                val hasUsableAttachment = attachments.any { it.status != UploadStatus.ERROR }
                                if ((inputText.isNotBlank() || hasUsableAttachment) && !uiState.isLoading) {
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
                        // ═══ / 实时联想（输入以 / 开头时弹出命令候选，点击回填）═══
                        SlashAutoCompleteHost(
                            inputText = inputText,
                            slashMenuProvider = slashMenuProvider,
                            onCommandSelected = { viewModel.updateInputText(it) }
                        )
                    }

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
                                val hasUsableAttachment = attachments.any { it.status != UploadStatus.ERROR }
                                if (inputText.isNotBlank() || hasUsableAttachment) {
                                    viewModel.sendMessage(inputText.trim())
                                    // ★ viewModel.sendMessage 内部已调用 updateInputText("")
                                }
                            },
                            enabled = inputText.isNotBlank() || attachments.any { it.status != UploadStatus.ERROR },
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
        }
    }

    // ═══ ask_user 模态对话框（Agent 请求用户输入）═══
    // 修复：原挂在 LazyColumn item 内，滚动出视口即被销毁——用户填一半的答案丢失；
    // 提升到屏幕层级与生命周期解耦，只要 pendingUserInput 存在就常驻。
    uiState.pendingUserInput?.let { request ->
        UserInputDialog(
            request = request,
            onSubmit = { viewModel.submitUserInput(it) },
            onCancel = { viewModel.cancelUserInput() }
        )
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
}


/**
 * Viro 桌宠情绪推导：聊天流式状态优先 → 用户交互等待 → 消息尾态 → 后台任务状态兜底。
 *
 * 映射到宠物形态（见 ViroPetHost）：思考中/待确认 → Review（放大镜审视）、
 * 执行工具 → Running、回复中 → RunningRight、等待输入 → Waiting、
 * 出错 → Failed、完成 → Idle（RunSummary 触发的跳跃庆祝由 celebrationKey 驱动）。
 */
private fun viroPetMoodOf(
    uiState: AgentChatUiState,
    pendingQuestion: com.apex.agent.core.engine.AgentQuestion?,
    taskState: com.apex.agent.core.engine.task.AgentTask?
): ViroPetMood {
    val lastMessage = uiState.messages.lastOrNull()
    return when {
        // ── Agent 正在干活：工具 > 流式回复 > 思考 ──
        uiState.isLoading && uiState.currentToolCall != null -> ViroPetMood.ToolRunning
        uiState.isLoading && uiState.currentResponse.isNotEmpty() -> ViroPetMood.Streaming
        uiState.isLoading -> ViroPetMood.Thinking
        // ── 需要用户决策 / 输入 ──
        uiState.awaitingPlanConfirmation || uiState.awaitingSpecConfirmation -> ViroPetMood.ReviewPlan
        uiState.pendingUserInput != null || pendingQuestion != null -> ViroPetMood.WaitingUser
        // ── 本轮收尾态（消息流末尾）──
        lastMessage is AgentUiMessage.Error -> ViroPetMood.Error
        lastMessage is AgentUiMessage.RunSummary -> ViroPetMood.Success
        // ── 后台任务兜底（TaskStatusCard 同源状态机）──
        else -> when (taskState?.status) {
            com.apex.agent.core.engine.task.TaskStatus.PLANNING,
            com.apex.agent.core.engine.task.TaskStatus.RUNNING,
            com.apex.agent.core.engine.task.TaskStatus.RECOVERING,
            com.apex.agent.core.engine.task.TaskStatus.RETRYING,
            com.apex.agent.core.engine.task.TaskStatus.CANCELLING -> ViroPetMood.ToolRunning
            com.apex.agent.core.engine.task.TaskStatus.WAITING_USER -> ViroPetMood.WaitingUser
            com.apex.agent.core.engine.task.TaskStatus.FAILED -> ViroPetMood.Error
            else -> ViroPetMood.Idle
        }
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
