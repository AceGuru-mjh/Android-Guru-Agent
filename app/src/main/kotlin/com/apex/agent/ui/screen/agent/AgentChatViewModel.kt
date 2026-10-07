package com.apex.agent.ui.screen.agent

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.attachment.AttachmentCleanupManager
import com.apex.agent.attachment.ImageAttachmentConverter
import com.apex.agent.attachment.PredictiveAttachmentPreprocessor
import com.apex.agent.core.engine.*
import com.apex.agent.core.llm.ImageContent
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.core.llm.ReasoningEffort
import com.apex.agent.core.engine.modes.ModePreset
import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.skill.SkillActivationStore
import com.apex.agent.core.tools.skill.SkillAutoActivator
import com.apex.agent.platform.csmem.session.CsMemSessionManager
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.loop.LoopScheduler
import com.apex.agent.net.NetworkMonitor
import com.apex.agent.notify.ApexNotifications
import com.apex.agent.notify.ForegroundTracker
import com.apex.agent.share.SharedIntake
import com.apex.agent.usage.UsageLedger
import com.apex.agent.ui.screen.settings.AgentSettings
import com.apex.agent.ui.screen.settings.SettingsRepository
import com.apex.agent.ui.screen.settings.activeModePreset
import com.apex.agent.ui.screen.settings.activeRole
import com.apex.agent.ui.screen.settings.allRoles
import com.apex.agent.ui.screen.settings.withRoleActivated
import com.apex.agent.ui.language.LanguageManager
import com.apex.agent.R
import androidx.annotation.StringRes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

// 工具来源分类 classifyTool() 已抽出到 ToolKindClassifier.kt（God-file 预算拆分）。

@HiltViewModel
class AgentChatViewModel @Inject constructor(
    internal val agentEngine: AgentEngine,
    private val memory: ConversationMemory,
    private val csMemSessionManager: CsMemSessionManager,
    val githubTokenManager: GithubTokenManager,
    private val savedStateHandle: SavedStateHandle,
    private val preprocessor: PredictiveAttachmentPreprocessor,
    internal val userQuestionBridge: UserQuestionBridge,
    // v1.4.4 #4：internal —— EventApplier 读 taskCompletionNotify 设置
    internal val settingsRepository: SettingsRepository,
    // God-file 预算拆分：AgentChatEventApplier 扩展需读工具元数据（classifyTool 路由徽章）
    internal val toolRegistry: ToolRegistry,
    // v1.4.4 #4/#6/#7：internal —— ChatSessionOps/EventApplier 扩展共用；
    // 亦供 AgentChatHtmlPreview.kt 扩展（God-file 预算拆分）。
    @ApplicationContext internal val context: Context,
    // T76：任务运行时控制器（execute/abort 经此获得 checkpoint/恢复能力）
    internal val taskController: AgentTaskStatusController,
    // 历史对话仓库（归档/恢复/删除；逻辑主体在 AgentChatHistoryController.kt）
    internal val chatHistory: ChatHistoryManager,
    // 工作区根解析（HTML 预览路径用）：code_* 工具写的 HTML 按此根解析相对路径；
    // default 工作区即 Agent 沙箱根（终端会话 guest /workspace 同源）。
    // internal —— AgentChatHtmlPreview.kt 扩展共用（God-file 预算拆分）。
    internal val workspaceRoots: CodeWorkspaceRoots,
    // i18n：用户可见 toast / 系统行 / 工具步骤文案按当前语言取词（组合外场景）
    private val languageManager: LanguageManager,
    // v2：斜杠路由需要 MCP 连接快照（/mcp:<id> 引导提示词据此生成）
    internal val mcpManager: com.apex.agent.core.tools.mcp.McpManager,
    // P2：删除/清空历史会话时同步清理附件文件（AgentChatHistoryController 扩展使用）
    internal val attachmentCleanup: AttachmentCleanupManager,
    // 技能渐进披露：自动装备（消息命中 tags 零成本预激活）+ 斜杠装备写入。
    private val skillAutoActivator: SkillAutoActivator,
    internal val skillActivation: SkillActivationStore,
    // ═══ v1.4.4 新增（全部 internal —— 扩展文件共用）═══
    /** #6 用量账本：每轮 LLM 真实 usage 落盘（EventApplier UsageUpdated 钩子）。 */
    internal val usageLedger: UsageLedger,
    /** #7 分享接收：外部 ACTION_SEND 入站的待处理载荷（init 订阅消费）。 */
    internal val sharedIntake: SharedIntake,
    /** #4 前台跟踪：任务完成通知的静音判定（用户正看屏幕时不打批）。 */
    internal val foregroundTracker: ForegroundTracker,
    /** #4 通知中心：任务完成通知发射器。 */
    internal val notifications: ApexNotifications,
    /** #6 网络监测：离线状态源（Screen 顶部横幅消费）。 */
    val networkMonitor: NetworkMonitor,
    /** S2：LOOP 循环调度器（会话屏打开即 start——循环真正的驱动源；
     *  事件收集与轮次注入逻辑在 AgentChatLoopController.kt 扩展文件）。 */
    internal val loopScheduler: LoopScheduler
) : ViewModel() {

    /** i18n：按当前语言取无参文案（internal —— AgentChatEventApplier 扩展共用）。 */
    internal fun str(@StringRes resId: Int): String = languageManager.getString(resId)

    /**
     * v1.4.9 闪退防御：VM init 链路协程的统一安全启动器。
     *
     * viewModelScope 裸 SupervisorJob 无 CoroutineExceptionHandler —— init
     * 阶段启动的十余个常驻协程（恢复扫描 / 会话归档 / 引擎热同步 / 循环调度）
     * 任何一个抛出未捕获异常，都会冒泡到线程默认 UncaughtExceptionHandler
     * 直接杀死进程 —— 这正是「点击开始使用即闪退 + 此后秒闪退」的放大器。
     * 此处统一拦截：CancellationException 重抛（结构化取消语义不变），其余
     * 异常降级为日志留痕（对应功能退化为不生效，App 存活优先）。
     */
    internal fun launchSafely(
        context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        tag: String,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
    ) {
        viewModelScope.launch(context) {
            try {
                block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.e(
                    "AgentChatViewModel",
                    "init coroutine [$tag] failed (suppressed, process kept alive): " +
                        "${t::class.simpleName}: ${t.message}",
                    t
                )
            }
        }
    }

    /** i18n：带占位符文案（%1$s/%1$d）在组合外格式化。 */
    internal fun strFmt(@StringRes resId: Int, vararg args: Any): String =
        String.format(languageManager.getString(resId), *args)

    // v2：memory.count() 在主线程 = 全量 JSON 反序列化（旧实现在构造器调用，
    // 几千条历史时进聊天页卡顿），改为 IO 线程异步回填（见下方 init）；
    // 同时保留 internal 可见性（供抽出的 AgentChatQuestionHandler.kt 扩展访问）。
    internal val _uiState = MutableStateFlow(AgentChatUiState())

    // ═══ 历史对话（ChatHistory）：逻辑主体在 AgentChatHistoryController.kt ═══
    // （God-file 预算拆分，模式同 AgentChatQuestionHandler.kt：归档/flush/恢复/删除均为 internal 扩展）
    internal val _chatSessions = MutableStateFlow<List<ChatSessionSummary>>(emptyList())
    val chatSessions: StateFlow<List<ChatSessionSummary>> = _chatSessions.asStateFlow()
    /** 当前会话在历史库的 id（null=新会话）/ 创建时间 / 防抖归档在途任务。 */
    internal var currentHistorySessionId: String? = null
    internal var currentHistorySessionCreatedAt: Long? = null
    internal var historyPersistJob: Job? = null
    val uiState: StateFlow<AgentChatUiState> = _uiState.asStateFlow()

    /**
     * 抽屉徽标窄化流（P1 重组风暴修复）：抽屉只消费 mode/thinkingLevel/
     * historyDepth 三个字段——直接 collectAsState uiState 会让流式输出期间
     * 每个 token 的 copy() 新实例都把整个抽屉（含 13 个玻璃导航项）重组
     * 一遍；结构去重后流式期间抽屉零重组。
     */
    val drawerBadges: StateFlow<DrawerBadges> = _uiState
        .map { DrawerBadges(it.mode, it.thinkingLevel, it.historyDepth) }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DrawerBadges(AgentMode.BUILD, ThinkingLevel.STANDARD, 0)
        )

    /** 抽屉徽标快照（窄字段 + distinctUntilChanged，天然结构去重）。 */
    data class DrawerBadges(
        val mode: AgentMode,
        val thinkingLevel: ThinkingLevel,
        val historyDepth: Int
    )

    /** #168 AUTO 档：最近一次自适应选档理由（引擎 IterationStart 后由 EventApplier 拉取刷新）。 */
    internal val _lastAdaptiveDecision = MutableStateFlow<String?>(null)
    val lastAdaptiveDecision: StateFlow<String?> = _lastAdaptiveDecision.asStateFlow()
    init {
        launchSafely(tag = "historyDepth") { _uiState.update { it.copy(historyDepth = withContext(Dispatchers.IO) { runCatching { memory.count() }.getOrDefault(0) }) } }
        // ═══ #197 双工位拆分：Agent 屏模式矫正 ═══
        // 启动快照（AgentModule）可能携带旧档位（build/plan/reflect/...——历史
        // 用户的持久化 defaultMode），而 Agent 屏现在只认 Chat/Agent。
        // 引擎模式不在双模式白名单时回退 AGENT（全能智能体，向上兼容），
        // UI 与引擎保持同相（选择器不出现"不在菜单里的当前值"）。
        val startupMode = (agentEngine as? ApexAgentEngine)?.currentConfig()?.mode
        if (startupMode == null || startupMode !in AGENT_SCREEN_MODES) {
            setMode(AgentMode.AGENT)
        } else {
            _uiState.update { it.copy(mode = startupMode) }
        }
        // P2-8/P3（6-c）：reasoningEffort chip 初始跟随默认 Profile；contextMaxTokens 回填引擎真实值（原恒 1 → 仪表盘 0%/上限1）。
        settingsRepository.profiles.value.firstOrNull { it.isDefault }?.let { p -> _uiState.update { it.copy(reasoningEffort = p.reasoningEffort) } }
        // 双级思考控制第二级：恢复强制深度思考开关（优先读新字段 forceDeepThinking；
        // 旧版本仅存 thinkingLevelOverride —— deep/maximum 视为强制开；旧档位
        // （如 auto 自适应）按原档回放，行为零回归）。
        val agentSettingsSnapshot = settingsRepository.agentSettings.value
        val savedOverrideLevel = agentSettingsSnapshot.thinkingLevelOverride
            .takeIf { it.isNotBlank() }
            ?.let { thinkingLevelFromOverride(it) }
        val forcedDeep = agentSettingsSnapshot.forceDeepThinking ||
            savedOverrideLevel == ThinkingLevel.DEEP || savedOverrideLevel == ThinkingLevel.MAXIMUM
        when {
            forcedDeep -> applyThinkingLevel(ThinkingLevel.MAXIMUM)
            savedOverrideLevel != null -> applyThinkingLevel(savedOverrideLevel)
            else -> Unit // 未覆盖：跟随启动默认档位（AgentModule 快照）
        }
        (agentEngine as? ApexAgentEngine)?.let { e -> _uiState.update { it.copy(contextMaxTokens = e.maxContextTokens()) } }
        // Issue #222：上下文窗口跟随所选模型（聊天页切换 + 设置页改动统一兜底，
        // 逻辑在 AgentChatModelSync.kt —— God-file 预算拆分既定模式）。
        installContextWindowSync()
        // 历史对话：会话列表初始加载 + 消息流防抖自动归档
        installChatHistoryAutoPersist()
        // ═══ Agent 角色：监听激活角色变化 → 引擎配置热更新 ═══
        // 设置页/聊天顶栏切换角色（持久化到 agentSettings）→ 此 collector
        // patchConfig 拍平后的 6 个人设字段 —— 无需重启、下一轮请求即生效
        // （与 setMode/setThinkingLevel 同款运行时通道，P1-1 语义：只改人设
        // 字段，绝不重置其余引擎配置）。
        launchSafely(tag = "roleSync") {
            settingsRepository.agentSettings
                .map { it.activeRole() }
                .distinctUntilChanged()
                .collect { role -> applyRoleToEngine(role) }
        }

        // ═══ Issue #164：全局规则 → 引擎热更新 ═══
        // 设置页 RulesSettingsSection 编辑后无需重启：EnginePrompts 的
        // "## Global Rules" 段在下一轮 buildSystemPrompt 即生效（coding
        // 模式走 CodeAgentEngine.rulesProvider 通道，两通道互斥防双注）。
        launchSafely(tag = "globalRules") {
            settingsRepository.agentSettings
                .map { it.globalRules }
                .distinctUntilChanged()
                .collect { rules -> (agentEngine as? ApexAgentEngine)?.updateGlobalRules(rules) }
        }

        // ═══ #168 CUSTOM 模式预设：选中预设/指令变化 → 引擎热更新 ═══
        // 选中预设持久化在 agentSettings（设置页/聊天页均可改）；此处把
        // 「当前生效指令」（选中预设优先，回退旧单串）拍平进引擎
        // customInstruction——下一轮请求生效，无需重启。无预设无旧串时
        // 置 null（CUSTOM 模式不注入额外指令，语义合法）。
        launchSafely(tag = "customInstruction") {
            settingsRepository.agentSettings
                .map { settingsRepository.effectiveCustomInstruction() }
                .distinctUntilChanged()
                .collect { instruction ->
                    (agentEngine as? ApexAgentEngine)?.patchConfig { cfg ->
                        cfg.copy(customInstruction = instruction.ifBlank { null })
                    }
                }
        }

        // #214/#215：问题桥超时诚实提示 + 迟交兜底（逻辑在 AgentChatQuestionHandler.kt）
        installQuestionExpiredNotice()

        // ═══ S2：LOOP 循环控制接线（dueEvents 收集 / activeLoop 状态同步 /
        // 调度器心跳复活 —— 逻辑主体在 AgentChatLoopController.kt 扩展文件）═══
        setupLoopController()
    }

    /** 全量角色列表（内置在前；AgentRoleSelector / 设置页共用）。 */
    // #277：Eagerly → WhileSubscribed(5000) —— 纯 UI 订阅消费（无 .value 命令式
    // 读点），后台 5s 后停管道，回前台自动重订阅（LLM#84 的 ModelRoleRouter
    // 读法禁改，此处已核实无同型读点）。
    val agentRoles: StateFlow<List<com.apex.agent.ui.screen.settings.AgentRole>> =
        settingsRepository.agentSettings
            .map { it.allRoles() }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                listOf(com.apex.agent.ui.screen.settings.AgentRole.ALL_ROUNDER)
            )

    /** 当前激活角色（UI 展示）。 */
    // #277：同 agentRoles —— 纯 UI 订阅消费，停更窗口不影响正确性。
    val activeAgentRole: StateFlow<com.apex.agent.ui.screen.settings.AgentRole> =
        settingsRepository.agentSettings
            .map { it.activeRole() }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                com.apex.agent.ui.screen.settings.AgentRole.ALL_ROUNDER
            )

    /** 激活角色（聊天顶栏 AgentRoleSelector 入口；持久化 + collector 负责引擎生效）。 */
    fun setAgentRole(roleId: String) {
        settingsRepository.updateAgentSettings { withRoleActivated(roleId) }
    }

    /** 角色数据模型 → 引擎 AgentConfig 人设字段（内置 = 全空 = 历史行为）。 */
    private fun applyRoleToEngine(role: com.apex.agent.ui.screen.settings.AgentRole) {
        (agentEngine as? ApexAgentEngine)?.patchConfig { cfg ->
            cfg.copy(
                agentName = if (role.isBuiltIn) "" else role.name,
                userTitle = role.userTitle,
                roleDefinition = role.roleDefinition,
                rolePrompt = role.systemPrompt,
                roleStyle = role.style,
                roleLanguage = role.replyLanguage
            )
        }
    }

    /** 附件管理器：附件状态流 + 追加/移除/沙箱拷贝的唯一负责人（抽出的单一职责协作类；scope 即 viewModelScope）。 */
    private val attachmentManager = AttachmentManager(
        context = context,
        preprocessor = preprocessor,
        scope = viewModelScope
    )

    val attachments: StateFlow<List<Attachment>> get() = attachmentManager.attachments

    // ═══════════════════════════════════════════════════════════
    // T76 — 任务运行时接线（长任务执行体系）
    // ═══════════════════════════════════════════════════════════

    /** 任务状态卡数据源（TaskStatusCard 消费）。 */
    val taskState: StateFlow<com.apex.agent.core.engine.task.AgentTask?> get() = taskController.taskState

    /** 崩溃恢复发现（D-3：VM init 确定性扫描，IO 阻塞扫描放 IO dispatcher；结果供 RecoveryBanner 呈现）。 */
    private val _recoveryCandidates = MutableStateFlow<List<com.apex.agent.core.engine.task.AgentTask>>(emptyList())
    val recoveryCandidates: StateFlow<List<com.apex.agent.core.engine.task.AgentTask>> = _recoveryCandidates.asStateFlow()

    init {
        // v1.4.9 闪退防御：崩溃恢复发现（TaskRuntime 迁移/JSON 解析/磁盘 IO 任一
        // 抽出即进程死亡 —— 「点击开始使用后闪退」的最可疑首帧路径）。
        launchSafely(kotlinx.coroutines.Dispatchers.IO, tag = "discoverRecoverable") {
            val discovered = taskController.discoverRecoverable()
            if (discovered.isNotEmpty()) {
                _recoveryCandidates.value = discovered
            }
        }
    }

    /** 暂停当前任务（TaskStatusCard）。 */
    fun pauseTask() {
        viewModelScope.launch { taskController.pause() }
    }

    /** 恢复暂停任务：返回的执行流接入与 sendMessage 相同的事件管线。 */
    fun resumeTask() {
        taskController.resume()?.let { flow ->
            currentJob?.cancel() // 混沌审查修复：与 sendMessage 对齐，覆盖前取消旧收集器，防双消费者交错写 _uiState
            currentJob = viewModelScope.launch { collectEngineFlowSafely(flow) }
        }
    }

    /** 取消当前任务（终态）。 */
    fun cancelTask() {
        viewModelScope.launch { taskController.cancel() }
    }

    /** 重试失败任务。 */
    fun retryTask() {
        taskController.retry()?.let { flow ->
            currentJob?.cancel() // 混沌审查修复：与 sendMessage 对齐，覆盖前取消旧收集器，防双消费者交错写 _uiState
            currentJob = viewModelScope.launch { collectEngineFlowSafely(flow) }
        }
    }

    /** 恢复横幅：继续崩溃任务（仅移除被继续的那一项，原实现误清空全部候选）。 */
    fun resumeCrashedTask(taskId: String) {
        taskController.resumeFromCrash(taskId)?.let { flow ->
            _recoveryCandidates.update { list -> list.filterNot { it.taskId == taskId } }
            currentJob?.cancel() // 混沌审查修复：与 sendMessage 对齐，覆盖前取消旧收集器，防双消费者交错写 _uiState
            currentJob = viewModelScope.launch { collectEngineFlowSafely(flow) }
        }
    }

    /** 恢复横幅：放弃单个崩溃任务（原实现误弃/误清全部候选）。 */
    fun dismissCrashedTask(taskId: String) {
        _recoveryCandidates.update { list -> list.filterNot { it.taskId == taskId } }
        // 仅当被放弃的候选就是当前激活任务（发现扫描会激活首项）时走运行时终态链；
        // 其余候选只从横幅移除——若其仍处于崩溃态，下次重启扫描会再次出现（诚实语义）
        if (taskController.taskState.value?.taskId == taskId) {
            viewModelScope.launch { taskController.abandonCrashed() }
        }
    }

    /** #237：关掉通知权限回收引导条（点击「去开启」跳转后 / 用户忽略）。 */
    fun dismissNotifPermissionHint() {
        _uiState.update { it.copy(notifPermissionHint = false) }
    }

    /**
     * One-shot signal emitted when a slash command needs the user to complete
     * the GitHub connection flow before it can execute (currently only
     * `/mcp:github` when no token is saved). The Agent chat screen collects
     * this and opens the GitHub token dialog.
     *
     * Uses [MutableSharedFlow] (not StateFlow) because this is an event, not
     * a persistent state — repeated `/mcp:github` attempts while still
     * unconnected should re-open the dialog each time.
     */
    internal val _requestGithubConnect = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestGithubConnect: SharedFlow<Unit> = _requestGithubConnect.asSharedFlow()

    /** 一次性 UI 反馈（Toast 级）：异步动作真实结果由 Screen 收集展示。UX-1：internal（非 private）供 AgentMessageActions.kt 同包扩展访问。 */
    internal val _uiFeedback = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val uiFeedback: SharedFlow<String> = _uiFeedback.asSharedFlow()

    // ═══ v1.4.4 #7：分享接收 —— 外部分享文本/图片预填输入区 ═══
    // 刻意放在这里的独立 init 块（在 attachmentManager/_uiFeedback 声明之后）：
    // Kotlin 属性与 init 块按声明顺序执行，而 viewModelScope 是 Main.immediate ——
    // StateFlow.collect 会同步发射当前值，若此代码在首个 init 块（依赖成员声明
    // 之前），冷启动分享路径会在构造期访问未初始化成员直接 NPE。
    // StateFlow 持久载荷（冷启动 onCreate 路径也不丢）：文本进草稿、图片挂
    // 附件条；不自动发送（分享 ≠ 授权发送，用户仍需点发送键）。图片必须在
    // URI 权限窗口内即时拷沙箱，不等用户点发送。
    init {
        launchSafely(tag = "sharedIntake") {
            sharedIntake.pending.collect { payload ->
                if (payload == null) return@collect
                val textFilled = payload.text?.takeIf { it.isNotBlank() }
                if (textFilled != null) updateInputText(textFilled)
                payload.imageUri?.let { uri ->
                    runCatching { attachmentManager.attachImage(uri) }
                }
                _uiFeedback.tryEmit(strFmt(R.string.chat_share_received))
                sharedIntake.consume()
            }
        }
    }

    /**
     * Agent 主动提问时的待处理问题。
     */
    val pendingQuestion: StateFlow<AgentQuestion?> = userQuestionBridge.pendingQuestion

    /**
     * 输入框草稿持久化（缺陷 3 修复）。
     *
     * 使用 [SavedStateHandle] 而非 [androidx.compose.runtime.rememberSaveable]：
     * - 跨配置变更（旋转 / 主题切换 / 语言切换）存活；
     * - 进程被系统回收后仍可恢复；
     * - 无 Bundle 1MB 大小限制，适合超长草稿。
     */
    val inputText: StateFlow<String> = savedStateHandle.getStateFlow(KEY_DRAFT_INPUT, "")

    fun updateInputText(text: String) {
        savedStateHandle[KEY_DRAFT_INPUT] = text
    }

    // ═══ 技能 chip 集合（斜杠菜单多选 → 输入框内 chip，v5 单胶囊改多选）═══

    private val _pendingCommands = MutableStateFlow<List<PendingPipelineCommand>>(emptyList())

    /**
     * 当前挂在输入框内的技能 chip 列表（顺序 = 追加顺序）。
     * 发送时：单条拼回 `/type:id` 走既有斜杠管线；多条逐个路由后合并为
     * 一轮引擎执行（见 [sendMessage]）。
     */
    val pendingCommands: StateFlow<List<PendingPipelineCommand>> = _pendingCommands.asStateFlow()

    /**
     * 追加一枚技能 chip（斜杠菜单/实时联想选中项）。
     *
     * - 按 `type:id` 去重（重复选择不重复挂载）；
     * - 输入框里若已有同源斜杠残文（如手动输入了 `/skill:` 前缀），顺手清掉，
     *   避免 chip + 残文双份指令。
     */
    fun addPendingCommand(command: PendingPipelineCommand) {
        val key = command.type + ":" + command.id
        _pendingCommands.value =
            if (_pendingCommands.value.any { (it.type + ":" + it.id) == key }) _pendingCommands.value
            else _pendingCommands.value + command
        val draft = inputText.value
        if (draft.isNotBlank() && draft.trimStart().startsWith("/")) {
            updateInputText("")
        }
    }

    /**
     * 整体同步 chip 集合（输入框内退格/点击删除 → 回报新集合；幂等）。
     */
    fun setPendingCommands(commands: List<PendingPipelineCommand>) {
        _pendingCommands.value = commands.distinctBy { it.type + ":" + it.id }
    }

    /** 清空全部 chip（发送后收尾 / 外部重置）。 */
    fun clearPendingCommands() {
        _pendingCommands.value = emptyList()
    }

    /**
     * 自定义模式指令（持久化到 SharedPreferences）。
     *
     * CUSTOM 模式选中时，该指令会随 [setMode] 一起写入引擎配置，
     * 拼入 system prompt 的 "## Custom Instructions" 段落。
     */
    private val _customInstruction = MutableStateFlow(
        context.getSharedPreferences(KEY_SETTINGS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_INSTRUCTION, "") ?: ""
    )
    val customInstruction: StateFlow<String> = _customInstruction.asStateFlow()

    /**
     * #168 当前选中的 CUSTOM 模式预设（null = 未选，回退旧单串指令）。
     * AgentChatScreen 据此在顶部显示预设名 chip（点击编辑该预设）。
     */
    val activeModePreset: StateFlow<ModePreset?> =
        settingsRepository.agentSettings
            .map { it.activeModePreset() }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setCustomInstruction(text: String) {
        val trimmed = text.trim()
        _customInstruction.value = trimmed
        context.getSharedPreferences(KEY_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_INSTRUCTION, trimmed)
            .apply()
        // #168：旧单串仅是后备通道——无选中预设时才即时生效；
        // 选中预设时预设指令优先（agentSettings collector 已接管）。
        if (activeModePreset.value == null && _uiState.value.mode == AgentMode.CUSTOM) {
            (agentEngine as? ApexAgentEngine)?.patchConfig { cfg -> cfg.copy(customInstruction = trimmed) }
        }
    }

    /**
     * 将一条 Agent 回复整理进记忆（接 CS-Mem 显式整理入口）。
     *
     * 委托 [CsMemSessionManager.organizeText] 把文本按行切片为语义节点写入长期记忆，
     * 使其可被 memory_search_nodes 按关键词召回。整理主题取文本前 40 字符。
     */
    fun organizeToMemory(text: String) {
        val goal = text.take(40).trim().ifBlank { str(R.string.chat_memory_goal_default) }
        viewModelScope.launch {
            runCatching { csMemSessionManager.organizeText(goal, text) }
                .onSuccess { _uiFeedback.tryEmit(strFmt(R.string.chat_organized_to_memory, goal)) }
                .onFailure { e ->
                    android.util.Log.e("AgentChatViewModel", "organizeToMemory failed", e)
                    _uiFeedback.tryEmit(
                        strFmt(
                            R.string.chat_organize_failed,
                            e.message ?: str(R.string.chat_unknown_error)
                        )
                    )
                }
        }
    }

    /**
     * 主动压缩上下文（顶部仪表盘"压缩上下文"按钮触发）。
     *
     * 委托 [ApexAgentEngine.compressNow] 执行真实压缩，并把结果以系统消息呈现，
     * 同时刷新顶部仪表盘的 token 统计。compressor 未注入时提示"压缩不可用"。
     */
    fun compressNow() {
        val engine = agentEngine as? ApexAgentEngine ?: return
        viewModelScope.launch {
            val report = runCatching { engine.compressNow() }.getOrNull()
            if (report == null) {
                _uiState.update { s ->
                    s.copy(messages = s.messages + AgentUiMessage.System(str(R.string.chat_compress_unavailable)))
                }
                return@launch
            }
            _uiState.update { s ->
                s.copy(
                    messages = s.messages + AgentUiMessage.System(
                        strFmt(
                            R.string.chat_vm_context_compressed_manual,
                            report.beforeTokens,
                            report.afterTokens,
                            report.strategy,
                            report.messagesRemoved
                        )
                    ),
                    contextUsedTokens = engine.currentTokenCount(),
                    contextMaxTokens = engine.maxContextTokens()
                )
            }
        }
    }

    // internal：AgentChatHistoryController.kt 的恢复会话需取消在途执行（拆分既定模式）
    internal var currentJob: Job? = null

    // ═══ 工具输出流式缓冲（16ms 节流刷新）═══
    // 每个 ToolOutputChunk 直接更新 StateFlow 会导致高频重组（模型/工具吐字快时
    // 每秒数十次）。这里先把 chunk 追加到 [toolOutputBuffer]，并启动一个 16ms
    // (≈1 帧) 的 flush Job；期间到达的 chunk 不再启动新 Job，到点后一次性把
    // 缓冲区快照写入 currentToolCall.output。既保留所有文本，又把重组次数压到
    // 每秒 ≤60 次。
    // 以下流式/工具运行态 internal：resetStreamingState()（历史恢复共用）
    // 在 AgentChatHistoryController.kt 扩展中访问 —— God-file 预算拆分既定模式。
    internal val toolOutputBuffer = StringBuilder()
    internal var activeToolCallId: String? = null
    internal var toolFlushJob: Job? = null
    /**
     * 运行期工具步骤流的单一事实源（带时间戳的步骤序列）。
     * [ToolCallStart] 时重置，[ToolOutputChunk] 原地替换"活输出"步/[ToolProgress] 追加；
     * [ToolCallComplete] 读取它构造最终过程流，避免反复从 StateFlow 派生。
     */
    internal var currentToolCallSteps: List<ToolStep>? = null
    /** 当前"活输出"步骤（唯一一条被反复原地替换的 OUTPUT 步）的 id；null 表示暂无。 */
    internal var liveOutputStepId: String? = null
    /** 步骤序列号发生器（单调递增），供时间线自动滚动 key 使用。 */
    private var stepSeqCounter: Long = 0
    internal fun nextStepSeq(): Long = ++stepSeqCounter

    // ═══ 回复/思考流式缓冲（33ms 节流刷新）═══
    // 详见 [StreamingFlushBuffers]（为守住 God-file 行数预算抽出的独立文件）：
    // chunk 入缓冲、33ms flush Job 统一刷入 UI 状态；Complete/ThinkingComplete/
    // ToolCallStart/abort/Error 时由调用方做最终 flush。
    internal val streamBuffers = StreamingFlushBuffers(viewModelScope) { responseDelta, thinkingDelta ->
        _uiState.update { state ->
            state.copy(
                currentResponse = state.currentResponse + responseDelta,
                currentThinking = state.currentThinking + thinkingDelta
            )
        }
    }

    /**
     * 运行期"活输出"步骤的唯一写入口：每次 flush 用最新尾部快照【原地替换】同一条
     * OUTPUT 步骤（而非追加新步骤），消除旧实现里逐次叠加重复文本的缺陷。
     *
     * 替换时**保留原步骤 id**：时间线以 s.id 作 LazyColumn key —— 每次换新 UUID
     * 会让活输出行每 16ms 被 dispose/recreate（横滚位置重置、重组放大），
     * 与「原地替换」的注释意图相悖。seq 仍然递增（时间线滚动感知更新靠它）。
     * （internal —— 事件归约已迁出至 AgentChatEventApplier.kt 扩展）
     */
    internal fun upsertLiveOutputStep(snapshot: String) {
        val existingId = liveOutputStepId
        // 替换形态：沿用原 id（LazyColumn key 稳定）；新建形态：全新 id。
        val live = ToolStep(
            id = existingId ?: java.util.UUID.randomUUID().toString(),
            phase = StepPhase.OUTPUT,
            text = snapshot,
            seq = nextStepSeq()
        )
        val steps = currentToolCallSteps ?: emptyList()
        if (existingId != null) {
            val idx = steps.indexOfFirst { it.id == existingId }
            if (idx >= 0) {
                currentToolCallSteps = steps.toMutableList().also { it[idx] = live }
                return
            }
        }
        liveOutputStepId = live.id
        currentToolCallSteps = steps + live
    }

    /**
     * 当前斜杠指令触发的流水线上下文（`/skill:xxx` `/connector:xxx` `/plugin:xxx`）。
     *
     * 在该流水线的 agent 循环中产生的工具调用会携带对应来源分类与名称，
     * 便于 UI 区分"这是一个 Skill / 连接器 / 插件调用"。循环结束后清空。
     */
    internal var routeContextKind: ToolKind? = null
    internal var routeContextName: String? = null

    /** 正在展示中的流水线横幅 id（[AgentUiMessage.PipelineBanner]），收尾时原地置为完成态。 */
    internal var activeBannerId: String? = null

    // ═══ 思考耗时计时（ThinkingBubble 秒数显示）═══
    /** 当前一轮思考的起始时刻（SystemClock.elapsedRealtime 基；0 = 未在思考）。
     *  [AgentEvent.ThinkingStart] 置位、[AgentEvent.ThinkingComplete]/abort 清零；
     *  ThinkingComplete 用它与当前时刻差值落盘 ThinkingMessage.durationMs。 */
    internal var thinkingStartElapsed: Long = 0

    /** 引擎一轮执行收尾时调用：把未完成的流水线横幅置为完成态（停止脉冲、显示耗时）。 */
    internal fun finishActiveBanner() {
        val id = activeBannerId ?: return
        activeBannerId = null
        _uiState.update { state ->
            state.copy(messages = state.messages.map { m ->
                if (m is AgentUiMessage.PipelineBanner && m.id == id && m.finishedAt == null) {
                    m.copy(finishedAt = java.lang.System.currentTimeMillis())
                } else {
                    m
                }
            })
        }
    }

    /**
     * 发送消息（含附件）。
     *
     * 修复点：
     * - 取消前一个流式任务（防竞态）；
     * - 斜杠指令不再吞掉附件：先清空附件再分流（缺陷 2 修复）；
     * - 附件复制到沙箱全部切到 [Dispatchers.IO]（缺陷 1 修复）。
     */
    fun sendMessage(text: String) {
        val trimmedText = text.trim()
        // 二轮审计 A-1：不计入 ERROR 占位附件——「空文本 + 全部附件读取失败」时
        // 不应发出空消息（P2-9 的 enabled 判定与 drainAttachments 的过滤口径对齐）。
        val hasUsableAttachment = attachmentManager.attachments.value.any { it.status != UploadStatus.ERROR }
        // chip 挂起时输入框文本视为"附加要求"（可为空）——chip 本身即指令主体。
        val pendingCmds = _pendingCommands.value
        if (trimmedText.isEmpty() && !hasUsableAttachment && pendingCmds.isEmpty()) return

        // 取消前一个尚未完成的流式任务
        currentJob?.cancel()
        // 被取消任务的横幅收尾：若上一轮是 /skill: /connector: /plugin: 流水线且尚未结束，
        // 此处把横幅置为完成态，避免旧横幅永远脉冲在"运行中"。
        finishActiveBanner()

        // ★ 缺陷 2 修复：无条件收集并清空附件，避免斜杠指令分支 return 后附件永久残留
        val currentAttachments = attachmentManager.drainAttachments()

        // 清空草稿（无论是否斜杠指令，发送后都应清空输入框）+ 摘下全部 chip
        updateInputText("")
        _pendingCommands.value = emptyList()

        // ═══ v5 多 chip 链路：≥2 枚时逐个路由（副作用：skill 装备）合并为一轮引擎执行 ═══
        if (pendingCmds.size >= 2) {
            // 附件与流水线互斥（与单 chip 路径同口径：提示后丢弃）
            if (currentAttachments.isNotEmpty()) {
                _uiState.update { s ->
                    s.copy(
                        messages = s.messages + AgentUiMessage.System(
                            strFmt(R.string.chat_slash_attachments_removed, currentAttachments.size)
                        )
                    )
                }
            }
            handleMultiChipPipeline(pendingCmds, trimmedText)
            return
        }

        // 单 chip（或无 chip）→ 既有路径：拼回斜杠命令 `/type:id` +（输入框有文本时）附加要求。
        // 附件与斜杠指令互斥（下方分支提示后丢弃），胶囊路径同样遵循。
        val pendingCmd = pendingCmds.firstOrNull()
        val effectiveText = if (pendingCmd != null) {
            if (trimmedText.isEmpty()) pendingCmd.toCommandToken()
            else pendingCmd.toCommandToken() + " " + trimmedText
        } else {
            trimmedText
        }

        // 斜杠指令分支：附件已被收集，但不随指令发送（给出 System 提示）
        if (effectiveText.startsWith("/")) {
            if (currentAttachments.isNotEmpty()) {
                _uiState.update { s ->
                    s.copy(
                        messages = s.messages + AgentUiMessage.System(
                            strFmt(R.string.chat_slash_attachments_removed, currentAttachments.size)
                        )
                    )
                }
            }
            handleSlashCommand(effectiveText)
            return
        }

        currentJob = viewModelScope.launch {
            // P0 修复（互斥锁冲突丢消息）：旧实现只 cancel UI 收集器（currentJob），
            // TaskRuntime 镜像收集器与执行锁不随 UI 生命周期走 —— 在途任务继续持锁。
            // 下一轮 executeNormalMessage → taskController.execute 撞互斥锁 →
            // "TaskRuntime rejects concurrent execution" 错误气泡，用户消息打水漂
            //（abort 后快速重发的窗口期必现）。先 await cancel 完成再执行新消息
            //（串行化，无竞态；无在途任务时 cancel 立即返回 false）。
            taskController.cancel()
            executeNormalMessage(trimmedText, currentAttachments)
        }
    }

    /**
     * 错误重试：重新执行上一条用户消息。
     *
     * 附件已在历史气泡中保留（[MessageAttachment.localPath] 已落盘），
     * 重试直接复用其本地路径，不再重新落盘——透传给 [runEngine] 即可。
     * （缺陷 5 修复：旧实现传 emptyList()，导致重试丢失原始附件上下文。）
     */
    fun retry(text: String, attachments: List<MessageAttachment>) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        currentJob?.cancel()
        // 同 sendMessage：被取消的上一轮流水线横幅收尾，避免残留"运行中"脉冲。
        finishActiveBanner()
        // P2：不清草稿 —— retry 的文本来自历史消息而非输入框；用户正在打的新草稿
        // 不该被无声清掉（regenerateResponse 已特意保留草稿，此路径漏修对齐）。
        currentJob = viewModelScope.launch {
            runEngine(trimmed, attachments)
        }
    }

    /**
     * 普通消息发送：附件落盘 + UI 追加 User 气泡 + 调用 AgentEngine。
     *
     * 创新优化：优先使用预测性预处理的预拷贝结果（零等待），
     * 未预拷贝的附件回退到 [AttachmentManager.copyToSandboxSafe]（64KB buffer + ensureActive）。
     *
     * 缺陷 4 修复：附件落盘（IO）外包 try/catch，失败时发 [AgentUiMessage.Error]
     * 并复位 isLoading，避免异常冒泡到 viewModelScope 导致崩溃或 isLoading 卡死；
     * [CancellationException] 重抛以保留协程取消语义。
     */
    internal suspend fun executeNormalMessage(
        text: String,
        currentAttachments: List<Attachment>
    ) {
        // 技能自动装备（渐进披露零成本路）：消息命中已安装技能的 tags/id/name
        // → 发送前预激活（≤ 2 个），本轮提示词即携带方法论全文；异常静默不阻断发送。
        runCatching { skillAutoActivator.autoEquip(text) }

        // 异步落盘附件（IO 线程）
        // 优先使用预拷贝结果，未预拷贝的回退到同步拷贝
        val persistedAttachments = try {
            withContext(Dispatchers.IO) {
                currentAttachments.map { att ->
                    // 尝试从预拷贝缓存获取（零等待）
                    val preprocessedPath = preprocessor.getSandboxPath(att.uri)
                    // 命中预拷贝：晋升到正式 attachments 目录（生命周期归一，
                    // 否则已发送附件留在 attachments_pre，进程重启后即成永久孤儿）
                    val localPath = preprocessedPath?.let { attachmentCleanup.promoteToAttachments(it) }
                        ?: attachmentManager.copyToSandboxSafe(att.uri, att.name)

                    MessageAttachment(
                        name = att.name,
                        mimeType = att.mimeType,
                        sizeBytes = att.sizeBytes,
                        type = att.type,
                        localPath = localPath,
                        thumbnailUri = if (att.type == AttachmentType.IMAGE) att.uri else null
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e // 协程取消必须重抛，不得吞掉
        } catch (e: Exception) {
            _uiState.update { s ->
                s.copy(
                    messages = s.messages + AgentUiMessage.Error(
                        message = strFmt(
                            R.string.chat_attachment_failed,
                            e.message ?: e::class.simpleName ?: ""
                        ),
                        canRetry = true
                    ),
                    isLoading = false
                )
            }
            return
        }

        runEngine(text, persistedAttachments)
    }

    /**
     * 执行引擎（附件已落盘）。供 [executeNormalMessage]（新发送）与 [retry]（重试，
     * 附件已带 localPath，无需重新落盘）共用，避免重试时丢失附件上下文。
     *
     * 缺陷 4 修复：引擎流 [agentEngine.execute] 的 collect 包 try/catch，
     * 引擎在发射 AgentEvent.Error 之前抛异常时（如 LLM IO 异常）发
     * [AgentUiMessage.Error] 并复位 isLoading；[CancellationException] 重抛。
     */
    private suspend fun runEngine(text: String, persistedAttachments: List<MessageAttachment>) {
        // 新一轮流式开始：清空上一轮可能残留的流式缓冲（防跨轮串字）；retry 路径同样受益。
        streamBuffers.reset()
        // P1（旧工具卡悬挂）：runEngine/retry 覆盖新一轮时必须清掉上一轮的工具运行态
        //（对比 abort() 的完整清理面）—— 否则被取消轮次的工具卡永久「运行中」脉冲
        // + 计时器常跑；toolOutputBuffer/flush Job/activeToolCallId 同属该清理面。
        toolFlushJob?.cancel()
        toolFlushJob = null
        activeToolCallId = null
        toolOutputBuffer.setLength(0)
        liveOutputStepId = null
        currentToolCallSteps = null
        // 在途部分回复保留为 isPartial（abort() 同款语义 —— 新发送取消旧任务时，
        // 已流出的内容不该无声消失）。
        val inFlightResponse = _uiState.value.currentResponse
        _uiState.update { state ->
            state.copy(
                messages = state.messages +
                    (if (inFlightResponse.isNotBlank())
                        listOf(AgentUiMessage.Agent(text = inFlightResponse, isPartial = true))
                    else emptyList()) +
                    AgentUiMessage.User(
                        text = text,
                        attachments = persistedAttachments
                    ),
                isLoading = true,
                currentThinking = "",
                currentResponse = "",
                currentToolCall = null
            )
        }

        // ═══ 多模态输入：图片 → ImageContent（Vision），非图片 → FileRef（路径上下文）═══
        // 图片附件经 ImageAttachmentConverter 压缩成 base64 ImageContent，注入
        // LlmMessage.User.images 让 Vision-capable LLM 真正看图；单次最多 3 张图，
        // 防止请求体过大 / token 超限。
        // Bug 修复：旧实现 take(3) 超出的图片与解码失败的图片被**静默丢弃**
        //（既不进 Vision 也不进 FileRef，用户发 5 张图第 4/5 张无声消失）。
        // 现在一律降级为 FileRef —— Agent 仍可用 read_file 读取它们。
        val imageContents = mutableListOf<ImageContent>()
        val fileRefs = mutableListOf<FileRef>()

        for (attachment in persistedAttachments) {
            val localPath = attachment.localPath ?: continue
            val file = File(localPath)
            if (!file.exists()) continue

            if (attachment.type == AttachmentType.IMAGE &&
                imageContents.size < MAX_VISION_IMAGES
            ) {
                val imageContent = ImageAttachmentConverter.fromFile(
                    file = file,
                    mimeType = attachment.mimeType
                )
                if (imageContent != null) {
                    imageContents.add(imageContent)
                    continue
                }
                // 解码/压缩失败 → 降级 FileRef（下方统一收集）
            }
            // 非图片 / 超出 Vision 张数上限 / 转换失败的附件：以文件引用交给 Agent
            fileRefs.add(
                FileRef(
                    name = attachment.name,
                    mimeType = attachment.mimeType,
                    localPath = localPath,
                    sizeBytes = attachment.sizeBytes
                )
            )
        }

        val userInput = UserInput(
            text = text,
            images = imageContents,
            files = fileRefs
        )

        // #197 「小圆环」函数调用/工具菜单已迁至 Coding 屏：Agent 屏发送前不再
        // 注入 toolkit 会话上下文/强制工具参数（Chat/Agent 模式由提示词层驱动；
        // AGENT 模式的默认工具计划由 EngineToolPlanner 按模式自动处理）。

        try {
            taskController.execute(userInput).collect { event ->
                handleEvent(event)
            }
        } catch (e: CancellationException) {
            throw e // 协程取消必须重抛（用户发送新消息会 cancel 旧 job）
        } catch (e: Exception) {
            _uiState.update { s ->
                s.copy(
                    messages = s.messages + AgentUiMessage.Error(
                        message = strFmt(
                            R.string.chat_execution_failed,
                            e.message ?: e::class.simpleName ?: ""
                        ),
                        canRetry = true
                    ),
                    isLoading = false
                )
            }
        }
    }

    // ═══ HTML 产物预览（应用内 WebView）═══
    // resolveHtmlPreviewPath() 已抽出到 AgentChatHtmlPreview.kt（internal 扩展，
    // 调用点 vm.resolveHtmlPreviewPath 解析不变）。

    /**
     * #169 计划确认（人控升级）：confirmed=false 取消；true 时可携带步骤勾选
     * （enabledSteps：原 index 清单，null = 全量）与重排（order：原 index 顺序，
     * null = 声明顺序）。引擎 Phase 3.5 应用调整 + 拓扑排序后锁定计划。
     */
    fun confirmPlan(confirmed: Boolean, enabledSteps: List<Int>? = null, order: List<Int>? = null) {
        _uiState.update { it.copy(awaitingPlanConfirmation = false) }
        (agentEngine as? ApexAgentEngine)?.submitPlanConfirmation(confirmed, enabledSteps, order)
    }

    /** 用户回答了 Agent 的提问，恢复引擎执行（#214 迟到投递回执在 AgentChatQuestionHandler 扩展内收口）。 */
    fun submitUserInput(answer: String) {
        _uiState.update { it.copy(pendingUserInput = null) }
        deliverUserInputOrNotice(answer)
    }

    /** 用户取消了 Agent 的提问，中止等待。 */
    fun cancelUserInput() {
        _uiState.update { it.copy(pendingUserInput = null) }
        (agentEngine as? ApexAgentEngine)?.cancelUserInput()
    }

    // answerQuestion() / cancelQuestion() 已抽出到 AgentChatQuestionHandler.kt
    // （internal 扩展，调用点 viewModel.answerQuestion/cancelQuestion 解析不变）。

    /**
     * 中止当前任务。
     *
     * 引擎的 [AgentEvent.Aborted] 在已取消的收集协程内发射，永远不会送达 UI，
     * 因此这里在 ViewModel 侧补偿收尾：
     * - 取消前先 flush 流式缓冲并快照当前回复/思考文本；
     * - 取消后把非空的部分回复落为 isPartial 的 Agent 消息（部分思考落为 ThinkingMessage），
     *   并追加 "⏹ 已中止" 系统行；
     * - 无条件复位 isLoading，清空 currentResponse/currentThinking 与进行中的工具卡片。
     */
    fun abort() {
        // ═══ 快照竞态修复：先停收集器，再做最终 flush/快照 ═══
        // 旧顺序（flush → 快照 → cancel）下，cancel 只是异步信号——取消传播
        // 到 OkHttp readLine 需经 invokeOnCompletion{response.close()} 触发
        // IOException → flow 终止；信号生效前网络新推的 token 仍会进入
        // streamBuffers（33ms flush Job 独立运行）追加 currentResponse，而
        // 快照已读走旧值 → 落盘的 isPartial 比用户屏幕最后一帧少一截。
        // 先 cancel 收集器 → 缓冲不再有新数据 → flush 后读到的才是真正终态。
        currentJob?.cancel()
        // T76：取消走任务运行时（引擎 abort + CANCELLED 落盘，重启不复活）
        viewModelScope.launch { taskController.cancel() }

        // 取消已生效：刷出未落盘的流式缓冲，拿到完整文本快照。
        streamBuffers.flush()
        val partialResponse = _uiState.value.currentResponse
        val partialThinking = _uiState.value.currentThinking

        // 取消后：部分产物落盘 + 状态复位（部分思考附带实测秒数）。
        finishActiveBanner()
        val partialThinkingDurationMs = if (thinkingStartElapsed > 0) {
            android.os.SystemClock.elapsedRealtime() - thinkingStartElapsed
        } else 0L
        thinkingStartElapsed = 0
        _uiState.update { state ->
            val extra = buildList<AgentUiMessage> {
                if (partialResponse.isNotBlank()) {
                    add(AgentUiMessage.Agent(text = partialResponse, isPartial = true))
                }
                if (partialThinking.isNotBlank()) {
                    add(AgentUiMessage.ThinkingMessage(partialThinking, partialThinkingDurationMs))
                }
                add(AgentUiMessage.System(str(R.string.chat_aborted)))
            }
            state.copy(
                messages = state.messages + extra,
                isLoading = false,
                currentResponse = "",
                currentThinking = "",
                currentThinkingStartElapsed = 0,
                currentToolCall = null
            )
        }
        streamBuffers.reset()
        toolFlushJob?.cancel()
        toolFlushJob = null
        toolOutputBuffer.clear()
        activeToolCallId = null
        liveOutputStepId = null
        currentToolCallSteps = null
        routeContextKind = null
        routeContextName = null
    }

    // newChat() 已迁至 AgentChatHistoryController.kt（历史归档冲刷 + 会话态清空一体；行数预算拆分）

    /** P2-8（6-c）：原写 legacy 死键 llm_reasoning_effort（全工程无消费者）；改持久化到默认 ModelProfile（DynamicLlmClient 监听 profiles 即时重建生效）。 */
    fun setReasoningEffort(effort: ReasoningEffort) {
        settingsRepository.profiles.value.firstOrNull { it.isDefault }?.let {
            settingsRepository.upsertProfile(it.copy(reasoningEffort = effort))
        }
        _uiState.update { it.copy(reasoningEffort = effort) }
    }

    // ═══════════════════════════════════════════════════════════
    // "小大脑"智能菜单：模型切换 + 采样参数调节 + 配置跳转
    // ═══════════════════════════════════════════════════════════

    /** 当前选中的模型 Profile id（跟随默认 Profile，UI 只读）。 */
    val currentProfileId: StateFlow<String?> =
        settingsRepository.profiles.map { list ->
            list.firstOrNull { it.isDefault }?.id
                ?: list.firstOrNull()?.id
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 全部模型 Profile（菜单列表数据源）。 */
    val profiles: StateFlow<List<ModelProfile>> = settingsRepository.profiles

    /** 全部 Provider（用于模型列表展示 Provider 名）。 */
    val providers: StateFlow<List<ProviderConfig>> = settingsRepository.providers

    /** 界面相关 Agent 设置（sendKeyBehavior / showRunSummary 等即时生效项的数据源）。 */
    val uiSettings: StateFlow<AgentSettings> = settingsRepository.agentSettings

    /** UX-3：LLM 是否已配置（判定口径 = DynamicLlmClient 的真/NoOp 边界，见 AgentChatOnboarding.kt；空会话+未配置时聊天区显示引导卡）。 */
    val llmConfigured: StateFlow<Boolean> = settingsRepository.llmConfiguredFlow(viewModelScope)

    // ═══ "小大脑"智能菜单：模型切换 + 采样参数调节（selectProfile /
    // updateModelParams）+ Issue #222 上下文窗口同步（installContextWindowSync）
    // 已拆至 AgentChatModelSync.kt（God-file 预算拆分既定模式，调用点无感知）。═══

    /** 函数调用二级菜单候选：全部已注册工具（id + 显示名 + v2 元数据）。 */
    fun availableTools(): List<ToolRef> =
        toolRegistry.getAllTools()
            .map { tool ->
                val meta = tool.metadata
                ToolRef(
                    id = tool.id,
                    name = tool.name,
                    category = meta.category,
                    highRisk = meta.isHighRisk
                )
            }
            .sortedWith(compareBy<ToolRef> { it.category?.order ?: Int.MAX_VALUE }.thenBy { it.id })

    /**
     * 已注册工具数量（透传 [ToolRegistry.toolCount]）。供 [AgentChatScreen] 作为
     * `remember(toolCount)` 的 key——注册表变更后下次重组即重新读取 [availableTools]，
     * 避免函数调用菜单快照被永久缓存（缺陷 6 修复）。
     */
    val toolCount: Int
        get() = toolRegistry.toolCount

    // #197：toolkitStore 已从 Agent 屏移除（函数调用/小圆环迁至 Coding 屏的
    // CodeViewModel 注入使用）。

    // ═══════════════════════════════════════════════════════════
    // 附件处理（缺陷 1 修复：全部异步化）—— 已抽出至 [AttachmentManager]
    // ═══════════════════════════════════════════════════════════

    /**
     * 处理文件附件。立即添加 UPLOADING 占位项，IO 线程读取真实元数据后回填。
     * 同时触发预测性预处理（后台拷贝到沙箱），发送时零等待。
     */
    fun attachFile(uri: Uri) = attachmentManager.attachFile(uri)

    /**
     * 处理图片附件。立即添加 UPLOADING 占位项，IO 线程读取真实元数据后回填。
     * 同时触发预测性预处理（后台拷贝到沙箱），发送时零等待。
     */
    fun attachImage(uri: Uri) = attachmentManager.attachImage(uri)

    /**
     * 移除附件。同时取消对应的元数据读取 Job 和预测性预拷贝。
     */
    fun removeAttachment(index: Int) = attachmentManager.removeAttachment(index)

    override fun onCleared() {
        super.onCleared()
        attachmentManager.dispose()
    }

    companion object {
        private const val KEY_DRAFT_INPUT = "draft_input"
        private const val KEY_SETTINGS = "apex_settings"
        private const val KEY_CUSTOM_INSTRUCTION = "custom_mode_instruction"

        /**
         * 单轮注入 Vision 的图片上限（请求体体积 / token 防护）。
         * 超出的图片降级为 FileRef（Agent 可用 read_file 读取），不再静默丢弃。
         */
        private const val MAX_VISION_IMAGES = 3

        /** 工具输出 UI 刷新节流间隔（≈1 帧 = 16ms；internal —— AgentChatEventApplier 共用）。 */
        internal const val FLUSH_INTERVAL_MS = 16L
    }
}
