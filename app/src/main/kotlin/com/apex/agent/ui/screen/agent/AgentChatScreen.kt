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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.EntryPointAccessors
import androidx.annotation.StringRes
import com.apex.agent.R
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.ui.component.AdaptiveInputField
import com.apex.agent.ui.component.AttachButton
import com.apex.agent.ui.component.AttachmentPreviewBar
import com.apex.agent.ui.component.FileOpener
import com.apex.agent.ui.component.GithubIconButton
import com.apex.agent.ui.component.GithubTokenDialog
import com.apex.agent.ui.component.HtmlPreviewDialog
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
    // P1 修复（每键全屏重组 → 打字卡顿）：旧写法 `val inputText by …collect…`
    // 在根作用域读取 State —— 每次按键（updateInputText → StateFlow 发射）都会
    // 重组整个 888 行 Screen 体（LazyColumn 脚架 + ~20 个状态收集 + 玻璃采样
    // 输入栏 + 横滚工具栏全部 lambda 重建），中低端机打字明显卡顿。
    // 现在只持有稳定的 State 对象；读取下沉到输入行 lambda（composable 作用域）
    // 与点击回调（即时读 .value），按键只重组输入行本身。
    val inputTextState = viewModel.inputText.collectAsStateWithLifecycle()
    // 流水线指令胶囊：斜杠菜单选中项（[</> skill: 名字] 形态挂在输入栏上方）
    val pendingCommand by viewModel.pendingCommand.collectAsStateWithLifecycle()
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
    // 界面相关 Agent 设置（sendKeyBehavior / showRunSummary，即时生效）
    val uiSettings by viewModel.uiSettings.collectAsStateWithLifecycle()

    // ═══ Agent 角色（人设）：当前角色 + 全量列表（顶栏 AgentRoleSelector）═══
    val activeRole by viewModel.activeAgentRole.collectAsStateWithLifecycle()
    val roles by viewModel.agentRoles.collectAsStateWithLifecycle()
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

    // HTML 产物预览状态：工具卡「预览」钮打开应用内 WebView 对话框
    var htmlPreviewPath by remember { mutableStateOf<String?>(null) }

    // ═══ T76：任务状态卡 + 崩溃恢复横幅状态 ═══
    val taskState by viewModel.taskState.collectAsStateWithLifecycle()
    val recoveryCandidates by viewModel.recoveryCandidates.collectAsStateWithLifecycle()
    val showTaskCard = taskState?.isActive == true

    // ═══ Viro 桌宠情绪：Agent 运行态 → 宠物动画形态（推导见文件尾 viroPetMoodOf）═══
    val viroMood = viroPetMoodOf(uiState, pendingQuestion, taskState)

    // ═══ 自定义模式指令对话框（点击 Custom 模式 chip 时打开）═══
    var showCustomInstructionDialog by remember { mutableStateOf(false) }
    val customInstruction by viewModel.customInstruction.collectAsStateWithLifecycle()

    // ═══ #168 CUSTOM 模式预设：当前选中预设（顶栏 chip 展示/点击编辑）═══
    val activeModePreset by viewModel.activeModePreset.collectAsStateWithLifecycle()
    var editingPreset by remember { mutableStateOf<com.apex.agent.core.engine.modes.ModePreset?>(null) }

    // ═══ #168 模式指南底部弹层（AgentModeSelector 的「?」图标打开）═══
    var showModeGuide by remember { mutableStateOf(false) }

    // ═══ 历史对话：会话列表 + 抽屉开关（顶栏「历史」按钮唤起）═══
    val chatSessions by viewModel.chatSessions.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }

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

        // ═══ 顶部模式栏（v4 紧凑化：用户反馈「上面那一部分太高」）═══
        // 高度收敛三处：Row 垂直 padding 8→4、图标按钮 40→34dp、预设 chip 32→28dp；
        // 内部胶囊统一 28dp（AgentModeSelector / AgentRoleSelector / ThinkingLevelSelector
        // 已同步紧凑化）→ 整行 ~36dp（原 ~52dp），叠 TopAppBar+ContextMeter 后顶部
        // 从 ~164dp 收敛到 ~114dp。
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // ═══ Agent 角色选择器（人设胶囊 + 下拉菜单）═══
                // 选中即持久化 → VM collector patchConfig（下一轮请求生效，
                // 无需重启）。自定义角色的创建/编辑入口在 设置 → Agent →
                // Agent 角色分区。
                AgentRoleSelector(
                    current = activeRole,
                    roles = roles,
                    onSelect = { roleId -> viewModel.setAgentRole(roleId) }
                )

                // ═══ 任务模式选择器（v3：胶囊 + 下拉菜单）═══
                // 旧实现 6 个 FilterChip 横排在 weight(1f, fill=false) 的滚动行里，
                // 窄屏只露出第一个「Build」—— 其余模式被裁在视口外且无任何提示，
                // 用户表现为「只有 Build 一个模式，没法切换」。换成显式菜单后
                // 全部 6 个模式在任何屏宽下都单次点击可达。
                AgentModeSelector(
                    current = uiState.mode,
                    onSelect = { mode ->
                        viewModel.setMode(mode)
                        // Custom 模式：有选中预设 → 弹预设编辑（改的是预设本体）；
                        // 无预设 → 旧单串指令对话框（兼容路径）
                        if (mode == AgentMode.CUSTOM) {
                            val preset = activeModePreset
                            if (preset != null) editingPreset = preset
                            else showCustomInstructionDialog = true
                        }
                    },
                    onOpenGuide = { showModeGuide = true }
                )

                // ═══ #168 CUSTOM 模式：当前预设名 chip（选中预设的可见锚点）═══
                // 让「现在跑的是哪套指令」一眼可见；点击直接编辑该预设。
                activeModePreset?.let { preset ->
                    val presetChipCd = stringResource(R.string.chat_mode_preset_cd, preset.name)
                    androidx.compose.material3.AssistChip(
                        onClick = { editingPreset = preset },
                        label = {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 28.dp)
                            .semantics { contentDescription = presetChipCd }
                    )
                }

                // ═══ 双级思考控制（RikkaHub 式）：第一级 = 模型原生 reasoning effort（API 参数），
                // 第二级 = 强制深度思考（提示词层，任何模型生效）═══
                ThinkingControlMenu(
                    reasoningEffort = uiState.reasoningEffort,
                    forceDeepThinking = uiState.forceDeepThinking,
                    onReasoningEffortSelect = { viewModel.setReasoningEffort(it) },
                    onForceDeepThinkingChange = { viewModel.setForceDeepThinking(it) }
                )

                Spacer(modifier = Modifier.weight(1f))

                // 历史对话入口：消息流自动归档，点击恢复接续上下文
                IconButton(
                    onClick = { showHistory = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = stringResource(R.string.chat_cd_chat_history),
                        modifier = Modifier.size(19.dp)
                    )
                }

                // 新会话按钮
                IconButton(
                    onClick = { viewModel.newChat() },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.chat_cd_new_chat),
                        modifier = Modifier.size(19.dp)
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
                statusLabelRes = { status -> statusLabelResOf(status) },
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
                    // #169：当前执行步骤 → 锁定计划卡（PlanCard）当前步高亮
                    currentStepIndex = uiState.currentStepIndex,
                    // UX-1：流式生成中禁用消息删除/重生成（菜单内对应条目置灰，复制仍可用）
                    actionsEnabled = !uiState.isLoading,
                    // 任务总结卡按设置显隐：showRunSummary=false 时完全不渲染（不占位）
                    showRunSummary = uiSettings.showRunSummary,
                    onImageClick = { att ->
                        lightboxImage = att.thumbnailUri ?: att.localPath
                    },
                    onFileClick = { att ->
                        att.localPath?.let { FileOpener.openFile(context, it, att.mimeType) }
                    },
                    // 多模态输出：markdown 生成图片（URL / data URI）→ Lightbox
                    onMarkdownImageClick = { url -> lightboxImage = url },
                    // HTML 产物：工具卡预览钮 → 应用内 WebView 对话框
                    onPreviewHtml = { path -> htmlPreviewPath = path }
                )
            }

            // 流式思考中（UX-4：固定 key —— 列表增删时保留 ThinkingBubble 展开态）；
            // 传入 elapsedRealtime 起点 → 头部实时秒数计时。
            if (uiState.currentThinking.isNotEmpty()) {
                item(key = "streaming-thinking") {
                    ThinkingBubble(
                        text = uiState.currentThinking,
                        liveStartElapsed = uiState.currentThinkingStartElapsed
                    )
                }
            }

            // 流式回复中（多模态输出：生成图片直接可点开 Lightbox，与完成态一致）
            if (uiState.currentResponse.isNotEmpty()) {
                item(key = "streaming-response") {
                    StreamingResponseBubble(
                        text = uiState.currentResponse,
                        onImageClick = { url -> lightboxImage = url }
                    )
                }
            }

            // 当前工具调用
            uiState.currentToolCall?.let { toolCall ->
                item(key = "active-tool-call") { RunningToolCallCard(toolCall) }
            }

            // Plan 确认（#169 人控：勾选/重排经 onConfirm 回传引擎）
            if (uiState.awaitingPlanConfirmation && uiState.plan != null) {
                item(key = "plan-confirmation") {
                    PlanConfirmationCard(
                        plan = uiState.plan!!,
                        onConfirm = { enabledSteps, order ->
                            viewModel.confirmPlan(true, enabledSteps, order)
                        },
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
                        contentDescription = stringResource(R.string.chat_cd_back_to_bottom),
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
            // 输入面板内边距收紧至 6dp：空态高度 ≈ 6+40+4+56+6 = 112dp（标准单行
            // 输入框 56dp），不压内容 —— 与「输入框高度正常化」验收口径对齐。
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                // ═══ 附件预览条（发送前）═══
                val attachments by viewModel.attachments.collectAsStateWithLifecycle()
                AttachmentPreviewBar(
                    attachments = attachments,
                    onRemove = { index -> viewModel.removeAttachment(index) }
                )

                // ═══ 流水线指令胶囊行（[</> skill: 名字 ×] —— 无挂起指令时不占位）═══
                // 斜杠菜单选中的 Skill / MCP / 连接器 / 插件以迷你胶囊挂在输入栏，
                // 输入框不再出现 `/skill:xxx` 裸文本；发送时 VM 拼回斜杠管线。
                PipelineCapsuleRow(
                    pending = pendingCommand,
                    onRemove = { viewModel.clearPendingCommand() }
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

                // ═══ 工具栏行：功能按钮 + 原生思考档位同一行（统一横向滚动）═══
                // 修复"输入框太高"：旧布局把 5 个 40dp 按钮 + 发送键与输入框挤同一
                // Row，窄屏（360dp 档）输入框仅剩 ~70dp —— 占位文字逐字换行成竖
                // 排、多行撑出近半屏高的"大框"。现在按钮与思考 chips 合并为一行
                // 可横滚工具栏，输入框独占下一行全部剩余宽度，默认 56dp 单行高度
                //（长内容仍自动扩行 + 双击全屏编辑）。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
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
                        exposeAllTools = toolkit.exposeAllTools.collectAsStateWithLifecycle().value,
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

                    // ═══ / 斜杠指令按钮 ═══
                    // 选中项挂成输入栏迷你胶囊（[</> skill: 名字]），不再裸文本入框；
                    // 解析失败的非管线命令（理论上不存在）回退旧文本插入路径。
                    SlashCommandButton(
                        slashMenuProvider = slashMenuProvider,
                        onItemSelected = { item ->
                            val capsule = PendingPipelineCommand.fromCommand(item.command, item.label)
                            if (capsule != null) {
                                viewModel.setPendingCommand(capsule)
                            } else {
                                // 插入而非覆盖：已输入内容时空格拼接保留原意图；
                                // 指令自带尾随空格，选中后可继续输入参数。
                                val command = item.command
                                val merged = if (inputTextState.value.isBlank()) {
                                    command
                                } else {
                                    inputTextState.value.trimEnd() + " " + command
                                }
                                viewModel.updateInputText(merged)
                            }
                        }
                            }
                        }
                    )

                    // ═══ GitHub 连接状态按钮 ═══
                    GithubIconButton(
                        tokenManager = viewModel.githubTokenManager
                    )

                    // ═══ + 旋转附件按钮 ═══
                    AttachButton(
                        onFileSelected = { uri ->
                            viewModel.attachFile(uri)
                        },
                        onImageSelected = { uri ->
                            viewModel.attachImage(uri)
                        }
                    )

                    // ═══ 小大脑：模型切换 + 参数调节 + 配置跳转 ═══
                    // providerNameOf 回调在非 @Composable 上下文触发 —— 兜底文案
                    // 在本 Composable 层预解析。
                    val unknownProviderLabel = stringResource(R.string.chat_unknown_provider)
                    BrainMenuButton(
                        profiles = profiles,
                        currentProfileId = currentProfileId ?: "",
                        providerNameOf = { providerId ->
                            providers.firstOrNull { it.id == providerId }?.displayName
                                ?: unknownProviderLabel
                        },
                        onSelectProfile = { viewModel.selectProfile(it) },
                        onParamsChanged = { t, p, m -> viewModel.updateModelParams(t, p, m) },
                        onConfigure = onOpenSettings
                    )
                    // 注：模型原生思考强度已并入顶栏「思考控制」菜单（ThinkingControlMenu
                    // 第一级），不再在输入工具栏重复占位 —— 单一控制点，避免两处 UI
                    // 控同一状态造成的困惑与行宽浪费。
                }
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // P1 修复（每键全屏重组）：inputText 在此 lambda 内读取 ——
                    // State 读取订阅的是最近的 composable 作用域（本 Row content），
                    // 按键只重组本行，不再牵动整个 Screen。
                    val inputText = inputTextState.value
                    // ═══ 输入框（自适应高度 + 手势扩展 + 双击全屏 + IME 发送）═══
                    //（斜杠实时联想收纳进输入框 Box：菜单锚定在文本框下方而非整行左缘）
                    Box(modifier = Modifier.weight(1f)) {
                        AdaptiveInputField(
                            value = inputText,
                            onValueChange = { viewModel.updateInputText(it) },
                            // 发送键行为：send → 回车直接发送；newline → 回车仅换行（设置页可配）
                            sendKeyBehavior = uiSettings.sendKeyBehavior,
                            onSend = {
                                // P2-9（6-c）：附件-only 消息同样可发（仅计可用附件；二轮审计 A-1 口径对齐）
                                // 胶囊-only（输入框空文本）同样可发：VM 拼回 /type:id 走斜杠管线
                                val hasUsableAttachment = attachments.any { it.status != UploadStatus.ERROR }
                                if ((inputText.isNotBlank() || hasUsableAttachment || pendingCommand != null) && !uiState.isLoading) {
                                    viewModel.sendMessage(inputText.trim())
                                }
                            },
                            placeholder = {
                                Text(
                                    text = when (uiState.mode) {
                                        AgentMode.PLAN -> stringResource(R.string.chat_hint_plan)
                                        AgentMode.SPEC -> stringResource(R.string.chat_hint_spec)
                                        AgentMode.REFLECTION -> stringResource(R.string.chat_hint_reflection)
                                        AgentMode.HUMAN_ASSIST -> stringResource(R.string.chat_hint_human_assist)
                                        AgentMode.CUSTOM -> stringResource(R.string.chat_hint_custom)
                                        AgentMode.BUILD -> stringResource(R.string.chat_hint_build)
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                        // ═══ / 实时联想（输入以 / 开头时弹出命令候选，点击挂胶囊）═══
                        SlashAutoCompleteHost(
                            inputText = inputText,
                            slashMenuProvider = slashMenuProvider,
                            onItemSelected = { item ->
                                val capsule = PendingPipelineCommand.fromCommand(item.command, item.label)
                                if (capsule != null) {
                                    // 选中即挂胶囊（VM 清掉框内 / 残文），附加要求直接继续打字
                                    viewModel.setPendingCommand(capsule)
                                } else {
                                    viewModel.updateInputText(item.command)
                                }
                            }
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
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.chat_cd_stop))
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                val hasUsableAttachment = attachments.any { it.status != UploadStatus.ERROR }
                                if (inputText.isNotBlank() || hasUsableAttachment || pendingCommand != null) {
                                    viewModel.sendMessage(inputText.trim())
                                    // ★ viewModel.sendMessage 内部已调用 updateInputText("") + 摘胶囊
                                }
                            },
                            enabled = inputText.isNotBlank() || attachments.any { it.status != UploadStatus.ERROR } || pendingCommand != null,
                            interactionSource = sendInteraction,
                            modifier = Modifier
                                .size(40.dp)
                                .scale(sendScale)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_cd_send))
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

    // ═══ HTML 产物应用内预览（Agent 写出的 .html → WebView 即时渲染）═══
    htmlPreviewPath?.let { path ->
        HtmlPreviewDialog(
            filePath = path,
            onDismiss = { htmlPreviewPath = null }
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

    // ═══ #168 模式指南底部弹层（六模式行为矩阵 + 思考档位简表）═══
    if (showModeGuide) {
        ModeGuideSheet(onDismiss = { showModeGuide = false })
    }

    // ═══ #168 CUSTOM 模式预设编辑（顶栏 chip /切模式入口打开）═══
    // 复用设置页同款编辑器（名称 + 多行指令）；保存经 ViewModel upsert
    // 到 agentSettings，选中即生效（init collector patchConfig）。
    editingPreset?.let { preset ->
        com.apex.agent.ui.screen.settings.ModePresetEditorDialog(
            initial = preset,
            isNew = false,
            onDismiss = { editingPreset = null },
            onSave = { updated ->
                viewModel.upsertModePreset(updated)
                editingPreset = null
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

    // ═══ 历史对话抽屉：恢复 / 删除 / 清空（数据源为防抖自动归档的会话库）═══
    if (showHistory) {
        ChatHistorySheet(
            sessions = chatSessions,
            currentSessionId = viewModel.currentHistorySessionId,
            onRestore = { viewModel.restoreChatSession(it) },
            onDelete = { viewModel.deleteChatSession(it) },
            onClearAll = { viewModel.clearAllChatSessions() },
            onDismiss = { showHistory = false }
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
 * T76 — 任务状态文案资源（TaskStatusCard 用；与 Controller 状态机一致）。
 * i18n：返回 @StringRes，由 TaskStatusCard 在组合内 stringResource 取词
 *（原 String 映射无法在非 Composable 上下文取词，最小改动方案）。
 */
@StringRes
private fun statusLabelResOf(status: com.apex.agent.core.engine.task.TaskStatus): Int = when (status) {
    com.apex.agent.core.engine.task.TaskStatus.PENDING -> R.string.chat_task_status_pending
    com.apex.agent.core.engine.task.TaskStatus.PLANNING -> R.string.chat_task_status_planning
    com.apex.agent.core.engine.task.TaskStatus.RUNNING -> R.string.chat_task_status_running
    com.apex.agent.core.engine.task.TaskStatus.WAITING_USER -> R.string.chat_task_status_waiting_user
    com.apex.agent.core.engine.task.TaskStatus.PAUSED -> R.string.chat_task_status_paused
    com.apex.agent.core.engine.task.TaskStatus.CANCELLING -> R.string.chat_task_status_cancelling
    com.apex.agent.core.engine.task.TaskStatus.RECOVERING -> R.string.chat_task_status_recovering
    com.apex.agent.core.engine.task.TaskStatus.RETRYING -> R.string.chat_task_status_retrying
    com.apex.agent.core.engine.task.TaskStatus.COMPLETED -> R.string.chat_task_status_completed
    com.apex.agent.core.engine.task.TaskStatus.FAILED -> R.string.chat_task_status_failed
    com.apex.agent.core.engine.task.TaskStatus.CANCELLED -> R.string.chat_task_status_cancelled
}
