package com.apex.agent.ui.screen.agent

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.attachment.ImageAttachmentConverter
import com.apex.agent.attachment.PredictiveAttachmentPreprocessor
import com.apex.agent.core.engine.*
import com.apex.agent.core.llm.ImageContent
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.core.llm.ReasoningEffort
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.platform.csmem.session.CsMemSessionManager
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.ui.screen.agent.toolkit.ChatToolkitStore
import com.apex.agent.ui.screen.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Agent 对话界面状态
 *
 * 保留与旧 ChatUiState 相同的字段名（currentResponse / currentThinking / currentToolCall），
 * ApexDrawerContent 已依赖这些字段显示模式/思考深度/记忆深度。
 */
data class AgentChatUiState(
    val messages: List<AgentUiMessage> = emptyList(),
    val isLoading: Boolean = false,
    val currentThinking: String = "",       // 当前思考内容（流式）
    val currentResponse: String = "",       // 当前回复内容（流式）
    val currentToolCall: AgentToolCallUi? = null, // 当前执行的工具
    val mode: AgentMode = AgentMode.BUILD,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.STANDARD,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    val plan: ExecutionPlan? = null,
    val awaitingPlanConfirmation: Boolean = false,
    /** Spec 模式的当前规格与确认状态。 */
    val spec: ExecutionSpec? = null,
    val awaitingSpecConfirmation: Boolean = false,
    val pendingUserInput: UserInputRequest? = null,
    val historyDepth: Int = 0,
    /** 上下文仪表盘：当前占用 token 数与上限（分子/分母） */
    val contextUsedTokens: Int = 0,
    val contextMaxTokens: Int = 1
)

/**
 * Agent 通过 [AgentEvent.UserInputRequired] 向用户提问时，UI 需要展示的待回答请求。
 */
data class UserInputRequest(
    val prompt: String,
    val type: InputType
)

/**
 * 工具调用来源分类，用于 UI 差异化呈现（图标 / 颜色 / 标签）。
 *
 * 引擎事件本身没有"类型"字段，ViewModel 在 [classifyTool] 中根据
 * toolName 前缀与已知 id 推断。这样用户能一眼区分本地工具 / MCP /
 * 联网搜索 / 网页抓取 / Skill 调用。
 */
enum class ToolKind { LOCAL, MCP, WEB_SEARCH, WEB_FETCH, SKILL }

@Immutable
sealed interface AgentUiMessage {
    /** 稳定 id：LazyColumn key 用（各子类以构造参数 override 实现，copy() 保留同一 id）。 */
    val id: String

    @Immutable
    data class User(
        val text: String,
        val attachments: List<MessageAttachment> = emptyList(),
        val timestamp: Long = java.lang.System.currentTimeMillis(),
        /** 稳定 id：LazyColumn key 用（copy() 保留同一 id）。 */
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    @Immutable
    data class Agent(
        val text: String,
        val timestamp: Long = java.lang.System.currentTimeMillis(),
        /** 中止/出错时保留的部分回复（isPartial=true，完整回复为 false）。 */
        val isPartial: Boolean = false,
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    @Immutable
    data class ToolCall(
        val toolName: String,
        val args: String = "",
        val output: String? = null,
        val fullOutput: String? = null,
        val success: Boolean? = null,
        val durationMs: Long = 0,
        /** 调用来源分类（本地 / MCP / 搜索 / 抓取 / Skill）。 */
        val kind: ToolKind = ToolKind.LOCAL,
        /** MCP server 名称（仅 KIND=MCP 时有意义）。 */
        val server: String? = null,
        /** Skill 名称（仅 KIND=SKILL 时有意义）。 */
        val skill: String? = null,
        /** 逐步执行过程（带时间戳的步骤序列），用于"执行过程"时间线渲染。 */
        val steps: List<ToolStep> = emptyList(),
        val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    @Immutable
    data class System(
        val text: String,
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    /**
     * Skill 开始执行横幅（区别于普通 System 行）：`/skill:xxx` 路由触发时展示，
     * 让用户一眼看出"当前正在执行哪个 Skill"，并为其后 SKILL 来源的工具调用提供上下文。
     */
    @Immutable
    data class SkillStart(
        val skill: String,
        val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    /**
     * 错误提示块（区别于灰色 System 行）：红色高亮 + 可重试标记。
     */
    @Immutable
    data class Error(
        val message: String,
        val canRetry: Boolean = false,
        val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    @Immutable
    data class PlanMessage(
        val plan: ExecutionPlan,
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    /** Spec 模式的规格卡片（确认通过后展示）。 */
    @Immutable
    data class SpecMessage(
        val spec: ExecutionSpec,
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    /** 反思模式的评审意见卡片（生成 → 评审 → 修正 中的评审产物）。 */
    @Immutable
    data class ReflectionReviewMessage(
        val text: String,
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    @Immutable
    data class ThinkingMessage(
        val thought: String,
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
}

@Immutable
data class AgentToolCallUi(
    val callId: String = "",
    val toolName: String,
    val args: String,
    /** 实时输出（由 ToolOutputChunk 逐段累积，节流后刷新；尾部窗口 4000 字符）。 */
    val output: String = "",
    /** 逐步执行过程（带时间戳的步骤序列），实时追加。 */
    val steps: List<ToolStep> = emptyList(),
    /** 进度（0..1），由 ToolProgress 事件更新；null 表示工具无进度信息。 */
    val progress: Float? = null,
    /** 进度说明文本，由 ToolProgress 事件更新。 */
    val progressMessage: String? = null,
    val isRunning: Boolean = true,
    /** 调用来源分类，逐帧流式卡片也使用。 */
    val kind: ToolKind = ToolKind.LOCAL,
    /** MCP server 名称。 */
    val server: String? = null,
    /** Skill 名称。 */
    val skill: String? = null,
    val id: String = java.util.UUID.randomUUID().toString()
) {

    companion object {
        /** 运行中工具卡片最多保留的实时输出字符数（尾部窗口）。 */
        const val MAX_LIVE_TOOL_OUTPUT_CHARS = 4000
        /** 运行中步骤流最多保留的条目数（尾部窗口），避免重组膨胀。 */
        const val MAX_LIVE_TOOL_STEPS = 200
    }
}

/**
 * 工具执行过程的单步记录，承载逐步可视化（区别于 harness 的"挂起→完成"两态卡片）。
 *
 * 每一步对应一条引擎事件：
 * - [StepPhase.START]    ← [AgentEvent.ToolCallStart]（工具名 + 参数摘要）
 * - [StepPhase.OUTPUT]   ← [AgentEvent.ToolOutputChunk]（节流后整段原始输出）
 * - [StepPhase.PROGRESS] ← [AgentEvent.ToolProgress]（进度说明 / 百分比）
 * - [StepPhase.COMPLETE] ← [AgentEvent.ToolCallComplete]（成功时的输出摘要）
 * - [StepPhase.ERROR]    ← [AgentEvent.ToolCallComplete] 且 success=false
 */
enum class StepPhase { START, OUTPUT, PROGRESS, COMPLETE, ERROR }

@Immutable
data class ToolStep(
    val phase: StepPhase,
    val text: String,
    val timestamp: Long = java.lang.System.currentTimeMillis(),
    /** 进度百分比（仅 PROGRESS 阶段有意义），范围 0..1。 */
    val percent: Float? = null,
    /** 单调递增序列号：时间线自动滚动 key（步骤被 cap 截断后 size 恒定，靠它感知更新）。 */
    val seq: Long = 0,
    val id: String = java.util.UUID.randomUUID().toString()
)

/** [classifyTool] 用的 server 字段提取正则（原实现在每次调用时重复编译，现提升到顶层）。 */
private val SERVER_FIELD_REGEX = Regex("""(?i)"server"\s*:\s*"([^"]+)"""")

/**
 * 根据工具名推断调用来源分类，用于 UI 差异化呈现。
 *
 * 规则（按优先级）：
 * - `mcp_call` / `mcp_call_<server>_<tool>` → MCP，并从参数中解析 server；
 * - `web_search` → 联网搜索；`web_fetch` → 网页抓取；
 * - `/skill:` 路由触发的工具 → Skill（toolName 含 `:skill` 或来自 skill 上下文）；
 * - 其余 → 本地工具。
 */
fun classifyTool(toolName: String, args: String): Pair<ToolKind, String?> {
    if (toolName.startsWith("mcp_call")) {
        // RouterMcpTool 通过 arguments 的 "server" 字段传入 server 名。
        val server = SERVER_FIELD_REGEX.find(args)
            ?.groupValues?.getOrNull(1)
        return ToolKind.MCP to server
    }
    if (toolName == "web_search") return ToolKind.WEB_SEARCH to null
    if (toolName == "web_fetch") return ToolKind.WEB_FETCH to null
    if (toolName.contains("skill", ignoreCase = true)) return ToolKind.SKILL to null
    return ToolKind.LOCAL to null
}

@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val agentEngine: AgentEngine,
    private val memory: ConversationMemory,
    private val csMemSessionManager: CsMemSessionManager,
    val githubTokenManager: GithubTokenManager,
    private val savedStateHandle: SavedStateHandle,
    private val preprocessor: PredictiveAttachmentPreprocessor,
    private val userQuestionBridge: UserQuestionBridge,
    private val settingsRepository: SettingsRepository,
    private val chatToolkit: ChatToolkitStore,
    private val toolRegistry: ToolRegistry,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentChatUiState(historyDepth = memory.count()))
    val uiState: StateFlow<AgentChatUiState> = _uiState.asStateFlow()

    /**
     * 附件管理器：附件状态流 + 追加/移除/沙箱拷贝的唯一负责人
     * （从本类抽出的单一职责协作类，逻辑逐行等价；scope 即 viewModelScope）。
     */
    private val attachmentManager = AttachmentManager(
        context = context,
        preprocessor = preprocessor,
        scope = viewModelScope
    )

    val attachments: StateFlow<List<Attachment>> get() = attachmentManager.attachments

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
    private val _requestGithubConnect = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestGithubConnect: SharedFlow<Unit> = _requestGithubConnect.asSharedFlow()

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

    fun setCustomInstruction(text: String) {
        val trimmed = text.trim()
        _customInstruction.value = trimmed
        context.getSharedPreferences(KEY_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_INSTRUCTION, trimmed)
            .apply()
        // CUSTOM 模式运行时立即生效；非 CUSTOM 模式在下次切换时携带。
        if (_uiState.value.mode == AgentMode.CUSTOM) {
            (agentEngine as? ApexAgentEngine)?.updateConfig(
                AgentConfig(
                    mode = AgentMode.CUSTOM,
                    thinkingLevel = _uiState.value.thinkingLevel,
                    customInstruction = trimmed
                )
            )
        }
    }

    /**
     * 将一条 Agent 回复整理进记忆（UI 占位实现）。
     *
     * 当前仅记录日志与埋点占位，尚未接入 CS-Mem 后端：
     * 后续版本会把 [text] 交给 CsMemSessionManager 做显式整理/蒸馏，
     * 使本次对话可被后续任务通过 memory_recall_* 工具召回。
     * Toast 提示由调用方（AgentBubble）负责，本方法保持纯业务占位。
     */
    /**
     * 将一条 Agent 回复整理进记忆（接 CS-Mem 显式整理入口）。
     *
     * 委托 [CsMemSessionManager.organizeText] 把文本按行切片为语义节点写入长期记忆，
     * 使其可被 memory_search_nodes 按关键词召回。整理主题取文本前 40 字符。
     */
    fun organizeToMemory(text: String) {
        val goal = text.take(40).trim().ifBlank { "对话整理" }
        viewModelScope.launch {
            runCatching { csMemSessionManager.organizeText(goal, text) }
                .onFailure { e ->
                    android.util.Log.e("AgentChatViewModel", "organizeToMemory failed", e)
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
                    s.copy(messages = s.messages + AgentUiMessage.System("⚠️ 压缩不可用（未启用压缩引擎）"))
                }
                return@launch
            }
            _uiState.update { s ->
                s.copy(
                    messages = s.messages + AgentUiMessage.System(
                        "📦 已压缩上下文：${report.beforeTokens}→${report.afterTokens} tokens " +
                            "(策略=${report.strategy}, 移除 ${report.messagesRemoved} 条)"
                    ),
                    contextUsedTokens = engine.currentTokenCount(),
                    contextMaxTokens = engine.maxContextTokens()
                )
            }
        }
    }

    private var currentJob: Job? = null

    // ═══ 工具输出流式缓冲（16ms 节流刷新）═══
    // 每个 ToolOutputChunk 直接更新 StateFlow 会导致高频重组（模型/工具吐字快时
    // 每秒数十次）。这里先把 chunk 追加到 [toolOutputBuffer]，并启动一个 16ms
    // (≈1 帧) 的 flush Job；期间到达的 chunk 不再启动新 Job，到点后一次性把
    // 缓冲区快照写入 currentToolCall.output。既保留所有文本，又把重组次数压到
    // 每秒 ≤60 次。
    private val toolOutputBuffer = StringBuilder()
    private var activeToolCallId: String? = null
    private var toolFlushJob: Job? = null
    /**
     * 运行期工具步骤流的单一事实源（带时间戳的步骤序列）。
     * [ToolCallStart] 时重置，[ToolOutputChunk] 原地替换"活输出"步/[ToolProgress] 追加；
     * [ToolCallComplete] 读取它构造最终过程流，避免反复从 StateFlow 派生。
     */
    private var currentToolCallSteps: List<ToolStep>? = null
    /** 当前"活输出"步骤（唯一一条被反复原地替换的 OUTPUT 步）的 id；null 表示暂无。 */
    private var liveOutputStepId: String? = null
    /** 步骤序列号发生器（单调递增），供时间线自动滚动 key 使用。 */
    private var stepSeqCounter: Long = 0
    private fun nextStepSeq(): Long = ++stepSeqCounter

    // ═══ 回复/思考流式缓冲（33ms 节流刷新）═══
    // ResponseChunk/ThinkingChunk 每 token 直接 _uiState.update { copy(currentResponse += text) }
    // 是 O(n²) 字符串拷贝 + 每秒上百次重组。改为 StringBuilder 累积，由一个 33ms
    // (≈2 帧) 的 flush Job 统一刷入 UI 状态（与下方工具输出节流同款模式）。
    // Complete/ThinkingComplete/ToolCallStart/abort/Error 时做最终 flush。
    private val responseBuffer = StringBuilder()
    private val thinkingBuffer = StringBuilder()
    private var streamFlushJob: Job? = null

    /** 把流式缓冲一次性刷入 UI 状态（两缓冲都为空时是 no-op）。 */
    private fun flushStreamBuffers() {
        val responseSnapshot = if (responseBuffer.isEmpty()) "" else {
            val s = responseBuffer.toString()
            responseBuffer.setLength(0)
            s
        }
        val thinkingSnapshot = if (thinkingBuffer.isEmpty()) "" else {
            val s = thinkingBuffer.toString()
            thinkingBuffer.setLength(0)
            s
        }
        if (responseSnapshot.isEmpty() && thinkingSnapshot.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                currentResponse = state.currentResponse + responseSnapshot,
                currentThinking = state.currentThinking + thinkingSnapshot
            )
        }
    }

    /** 确保存在一个 33ms 后到期的 flush Job（期间到达的 chunk 复用同一 Job）。 */
    private fun ensureStreamFlushJob() {
        if (streamFlushJob == null) {
            streamFlushJob = viewModelScope.launch {
                delay(STREAM_FLUSH_INTERVAL_MS)
                flushStreamBuffers()
                streamFlushJob = null
            }
        }
    }

    /** 取消流式 flush Job 并清空两个流式缓冲（新会话/新消息/中止时防串轮残留）。 */
    private fun resetStreamBuffers() {
        streamFlushJob?.cancel()
        streamFlushJob = null
        responseBuffer.clear()
        thinkingBuffer.clear()
    }

    /**
     * 运行期"活输出"步骤的唯一写入口：每次 flush 用最新尾部快照【原地替换】同一条
     * OUTPUT 步骤（而非追加新步骤），消除旧实现里逐次叠加重复文本的缺陷。
     */
    private fun upsertLiveOutputStep(snapshot: String) {
        val live = ToolStep(phase = StepPhase.OUTPUT, text = snapshot, seq = nextStepSeq())
        val steps = currentToolCallSteps ?: emptyList()
        val existingId = liveOutputStepId
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
     * 当前 Slash 指令触发的 Skill 名称（若来自 `/skill:xxx`）。
     * 在该 Skill 的 agent 循环中产生的工具调用会被标记为 SKILL 来源，
     * 便于 UI 区分"这是一个 Skill 调用"。循环结束后清空。
     */
    private var skillContext: String? = null

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
        if (trimmedText.isEmpty() && attachmentManager.attachments.value.isEmpty()) return

        // 取消前一个尚未完成的流式任务
        currentJob?.cancel()

        // ★ 缺陷 2 修复：无条件收集并清空附件，避免斜杠指令分支 return 后附件永久残留
        val currentAttachments = attachmentManager.drainAttachments()

        // 清空草稿（无论是否斜杠指令，发送后都应清空输入框）
        updateInputText("")

        // 斜杠指令分支：附件已被收集，但不随指令发送（给出 System 提示）
        if (trimmedText.startsWith("/")) {
            if (currentAttachments.isNotEmpty()) {
                _uiState.update { s ->
                    s.copy(
                        messages = s.messages + AgentUiMessage.System(
                            "⚠️ 斜杠指令不携带附件，已移除 ${currentAttachments.size} 个附件"
                        )
                    )
                }
            }
            handleSlashCommand(trimmedText)
            return
        }

        currentJob = viewModelScope.launch {
            executeNormalMessage(trimmedText, currentAttachments)
        }
    }

    /**
     * 错误重试：重新执行上一条用户消息。
     *
     * 附件已在历史气泡中保留，重试聚焦"重新发起文本指令"触发 AgentEngine 重跑，
     * 不再重新落盘附件（其本地路径在 [executeNormalMessage] 中通过 FileRef 复用）。
     */
    fun retry(text: String, attachments: List<MessageAttachment>) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        currentJob?.cancel()
        updateInputText("")
        currentJob = viewModelScope.launch {
            executeNormalMessage(trimmed, emptyList())
        }
    }

    /**
     * 普通消息发送：附件落盘 + UI 追加 User 气泡 + 调用 AgentEngine。
     *
     * 创新优化：优先使用预测性预处理的预拷贝结果（零等待），
     * 未预拷贝的附件回退到 [AttachmentManager.copyToSandboxSafe]（64KB buffer + ensureActive）。
     */
    private suspend fun executeNormalMessage(
        text: String,
        currentAttachments: List<Attachment>
    ) {
        // 异步落盘附件（IO 线程）
        // 优先使用预拷贝结果，未预拷贝的回退到同步拷贝
        val persistedAttachments = withContext(Dispatchers.IO) {
            currentAttachments.map { att ->
                // 尝试从预拷贝缓存获取（零等待）
                val preprocessedPath = preprocessor.getSandboxPath(att.uri)
                val localPath = preprocessedPath ?: attachmentManager.copyToSandboxSafe(att.uri, att.name)

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

        // 新一轮流式开始：清空上一轮可能残留的流式缓冲（防跨轮串字）。
        resetStreamBuffers()

        _uiState.update { state ->
            state.copy(
                messages = state.messages + AgentUiMessage.User(
                    text = text,
                    attachments = persistedAttachments
                ),
                isLoading = true,
                currentThinking = "",
                currentResponse = ""
            )
        }

        // ═══ 多模态输入：图片 → ImageContent（Vision），非图片 → FileRef（路径上下文）═══
        // 图片附件经 ImageAttachmentConverter 压缩成 base64 ImageContent，注入
        // LlmMessage.User.images 让 Vision-capable LLM 真正看图；非图片附件仍作为
        // 文件路径上下文（Agent 可用 read_file / search_files 读取）。单次最多 3 张图，
        // 防止请求体过大 / token 超限。
        val imageContents = mutableListOf<ImageContent>()
        val fileRefs = mutableListOf<FileRef>()

        for (attachment in persistedAttachments) {
            val localPath = attachment.localPath ?: continue
            val file = File(localPath)
            if (!file.exists()) continue

            if (attachment.type == AttachmentType.IMAGE) {
                val imageContent = ImageAttachmentConverter.fromFile(
                    file = file,
                    mimeType = attachment.mimeType
                )
                if (imageContent != null) imageContents.add(imageContent)
            } else {
                fileRefs.add(
                    FileRef(
                        name = attachment.name,
                        mimeType = attachment.mimeType,
                        localPath = localPath,
                        sizeBytes = attachment.sizeBytes
                    )
                )
            }
        }

        val userInput = UserInput(
            text = text,
            images = imageContents.take(3),
            files = fileRefs
        )

        // ═══ "小圆环"工具菜单：发送前注入会话上下文 + 收窄工具白名单 ═══
        // 时间注入在调用时刻生成；规则/结构化输出/网络搜索指令组装为
        // "## Session Context" 段落；函数调用圈选则只向模型暴露白名单工具。
        (agentEngine as? ApexAgentEngine)?.patchConfig { cfg ->
            cfg.copy(
                additionalSystemContext = chatToolkit.buildSessionContext(),
                enabledToolIds = chatToolkit.effectiveToolWhitelist()
            )
        }

        agentEngine.execute(userInput).collect { event ->
            handleEvent(event)
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            // ═══ 思考 ═══
            is AgentEvent.ThinkingStart -> {
                // 新一轮思考：清掉上一轮可能残留的缓冲（防串轮）。
                thinkingBuffer.clear()
                _uiState.update { it.copy(currentThinking = "") }
            }
            is AgentEvent.ThinkingChunk -> {
                thinkingBuffer.append(event.text)
                ensureStreamFlushJob()
            }
            is AgentEvent.ThinkingComplete -> {
                // 最终 flush：把仍在缓冲中的思考文本刷入 UI 后再收尾。
                flushStreamBuffers()
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + AgentUiMessage.ThinkingMessage(event.fullThought),
                        currentThinking = ""
                    )
                }
            }

            // ═══ Plan模式 ═══
            is AgentEvent.PlanGenerated -> {
                _uiState.update { it.copy(plan = event.plan) }
            }
            is AgentEvent.PlanAwaitingConfirmation -> {
                _uiState.update { it.copy(awaitingPlanConfirmation = true) }
            }
            is AgentEvent.UserInputRequired -> {
                _uiState.update { it.copy(pendingUserInput = UserInputRequest(event.prompt, event.type)) }
            }
            is AgentEvent.PlanConfirmed -> {
                _uiState.update { state ->
                    state.copy(
                        awaitingPlanConfirmation = false,
                        messages = state.messages + AgentUiMessage.PlanMessage(event.plan)
                    )
                }
            }

            // ═══ Spec 模式 ═══
            is AgentEvent.SpecGenerated -> {
                _uiState.update { it.copy(spec = event.spec) }
            }
            is AgentEvent.SpecAwaitingConfirmation -> {
                _uiState.update { it.copy(awaitingSpecConfirmation = true) }
            }
            is AgentEvent.SpecConfirmed -> {
                _uiState.update { state ->
                    state.copy(
                        awaitingSpecConfirmation = false,
                        messages = state.messages + AgentUiMessage.SpecMessage(event.spec)
                    )
                }
            }

            // ═══ 工具调用（流式）═══
            is AgentEvent.ToolCallStart -> {
                // 流式回复/思考暂停：先刷出缓冲，保证已有文本先于工具卡落盘。
                flushStreamBuffers()
                // 重置缓冲区 + 节流状态，记录当前活跃工具 callId 用于 chunk 路由。
                activeToolCallId = event.callId
                toolOutputBuffer.clear()
                toolFlushJob?.cancel()
                toolFlushJob = null
                liveOutputStepId = null
                // 重置运行期步骤流（START 步）。
                currentToolCallSteps = listOf(
                    ToolStep(
                        phase = StepPhase.START,
                        text = "调用 ${event.toolName}，参数：\n${event.arguments}",
                        seq = nextStepSeq()
                    )
                )

                val (kind, server) = classifyTool(event.toolName, event.arguments)
                val skill = if (kind == ToolKind.SKILL) skillContext else null

                _uiState.update { state ->
                    state.copy(
                        currentToolCall = AgentToolCallUi(
                            callId = event.callId,
                            toolName = event.toolName,
                            args = event.arguments,
                            output = "",
                            steps = currentToolCallSteps ?: emptyList(),
                            progress = null,
                            progressMessage = null,
                            isRunning = true,
                            kind = kind,
                            server = server,
                            skill = skill
                        )
                    )
                }
            }
            is AgentEvent.ToolOutputChunk -> {
                // 仅处理当前活跃工具的 chunk；上一轮工具迟到的 chunk 丢弃（安全）。
                if (event.callId != activeToolCallId) return

                toolOutputBuffer.append(event.chunk)

                // 16ms 内的多个 chunk 合并为一次 UI 更新（≈1 帧节流）。
                if (toolFlushJob == null) {
                    toolFlushJob = viewModelScope.launch {
                        delay(FLUSH_INTERVAL_MS)
                        val snapshot = toolOutputBuffer.toString()
                            .takeLast(AgentToolCallUi.MAX_LIVE_TOOL_OUTPUT_CHARS)
                        // 原地替换唯一的"活输出"步骤（不追加），避免重叠文本重复叠加。
                        upsertLiveOutputStep(snapshot)
                        _uiState.update { state ->
                            val tc = state.currentToolCall ?: return@update state
                            state.copy(
                                currentToolCall = tc.copy(
                                    output = snapshot,
                                    steps = (currentToolCallSteps ?: emptyList())
                                        .takeLast(AgentToolCallUi.MAX_LIVE_TOOL_STEPS)
                                )
                            )
                        }
                        toolFlushJob = null
                    }
                }
            }
            is AgentEvent.ToolProgress -> {
                if (event.callId != activeToolCallId) return
                _uiState.update { state ->
                    val tc = state.currentToolCall ?: return@update state
                    val msg = event.message ?: "进度 ${((event.percent ?: 0f) * 100).toInt()}%"
                    val progressStep = ToolStep(
                        phase = StepPhase.PROGRESS,
                        text = msg,
                        percent = event.percent,
                        seq = nextStepSeq()
                    )
                    // 同步写入运行期步骤流单一事实源。
                    currentToolCallSteps = (currentToolCallSteps ?: emptyList()) +
                        progressStep
                    state.copy(
                        currentToolCall = tc.copy(
                            progress = event.percent,
                            progressMessage = event.message,
                            steps = (tc.steps + progressStep)
                                .takeLast(AgentToolCallUi.MAX_LIVE_TOOL_STEPS)
                        )
                    )
                }
            }
            is AgentEvent.ToolCallComplete -> {
                // 取消尚未刷新的 flush Job；剩余缓冲不再单独成步——完整输出已由
                // output/fullOutput 承载。
                toolFlushJob?.cancel()
                toolFlushJob = null
                activeToolCallId = null
                toolOutputBuffer.clear()

                val (kind, server) = classifyTool(event.toolName, event.arguments)
                val skill = if (kind == ToolKind.SKILL) skillContext else null

                // 最终过程流：丢弃"活输出"步骤（其快照与完整输出重复），仅保留
                // START / PROGRESS 等结构性步骤 + 收尾步。
                val finalSteps = ((currentToolCallSteps ?: emptyList())
                    .filter { it.id != liveOutputStepId } + ToolStep(
                    phase = if (event.success) StepPhase.COMPLETE else StepPhase.ERROR,
                    text = if (event.success)
                        "完成（${event.durationMs}ms）：${event.output}"
                    else
                        "失败（${event.durationMs}ms）：${event.output}",
                    seq = nextStepSeq()
                )).takeLast(AgentToolCallUi.MAX_LIVE_TOOL_STEPS)
                liveOutputStepId = null

                _uiState.update { state ->
                    state.copy(
                        currentToolCall = null,
                        messages = state.messages + AgentUiMessage.ToolCall(
                            toolName = event.toolName,
                            args = event.arguments,
                            output = event.output,
                            fullOutput = event.fullOutput.ifBlank { event.output },
                            success = event.success,
                            durationMs = event.durationMs,
                            kind = kind,
                            server = server,
                            skill = skill,
                            steps = finalSteps
                        )
                    )
                }
                // 清理运行期步骤缓存（已被写入完成卡）。
                currentToolCallSteps = null
            }

            // ═══ 反思模式：评审意见 ═══
            // 引擎在草稿流式结束后发射本事件。草稿已在 currentResponse 中流式累积，
            // 这里先把草稿落为一条 Agent 消息（"生成"），再追加评审卡片；
            // 随后引擎流式发射修正后的最终回复（ResponseChunk → ResponseComplete）。
            is AgentEvent.ReflectionReview -> {
                // 草稿流式结束即评审：先做最终 flush，确保缓冲中的草稿文本完整落为消息。
                flushStreamBuffers()
                _uiState.update { state ->
                    val draft = state.currentResponse
                    state.copy(
                        messages = state.messages +
                            (if (draft.isNotBlank()) listOf(AgentUiMessage.Agent(draft)) else emptyList()) +
                            listOf(AgentUiMessage.ReflectionReviewMessage(event.reviewText)),
                        currentResponse = ""
                    )
                }
            }

            // ═══ 流式回复 ═══
            is AgentEvent.ResponseChunk -> {
                responseBuffer.append(event.text)
                ensureStreamFlushJob()
            }
            is AgentEvent.ResponseComplete -> {
                // 最终 flush：把仍在缓冲中的回复文本刷入 UI 后再落为完整消息。
                flushStreamBuffers()
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + AgentUiMessage.Agent(event.fullText),
                        currentResponse = "",
                        isLoading = false
                    )
                }
            }

            // ═══ 压缩 ═══
            is AgentEvent.ContextCompressed -> {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + AgentUiMessage.System(
                            "📦 Context compressed: ${event.beforeTokens}→${event.afterTokens} tokens " +
                            "(${event.strategy}, removed ${event.messagesRemoved} msgs" +
                            (if (event.messagesTruncated > 0) ", truncated ${event.messagesTruncated}" else "") +
                            ")"
                        )
                    )
                }
            }

            // ═══ 错误/完成 ═══
            is AgentEvent.Error -> {
                // 出错时把已流式输出的部分回复落为 isPartial 消息，避免流式气泡悬挂。
                flushStreamBuffers()
                _uiState.update { state ->
                    val partial = state.currentResponse
                    state.copy(
                        messages = state.messages +
                            (if (partial.isNotBlank())
                                listOf(AgentUiMessage.Agent(text = partial, isPartial = true))
                            else emptyList()) +
                            listOf(
                                AgentUiMessage.Error(
                                    message = event.message,
                                    canRetry = event.recoverable
                                )
                            ),
                        currentResponse = "",
                        currentThinking = "",
                        isLoading = false
                    )
                }
            }
            is AgentEvent.Complete -> {
                _uiState.update {
                    val engine = agentEngine as? ApexAgentEngine
                    it.copy(
                        isLoading = false,
                        historyDepth = engine?.historyCount() ?: it.historyDepth,
                        contextUsedTokens = engine?.currentTokenCount() ?: it.contextUsedTokens,
                        contextMaxTokens = engine?.maxContextTokens() ?: it.contextMaxTokens
                    )
                }
            }
            is AgentEvent.Aborted -> {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + AgentUiMessage.System("⏹ 已中止"),
                        isLoading = false
                    )
                }
            }

            else -> {}
        }
    }

    fun setMode(mode: AgentMode) {
        _uiState.update { it.copy(mode = mode) }
        (agentEngine as? ApexAgentEngine)?.updateConfig(
            AgentConfig(
                mode = mode,
                thinkingLevel = _uiState.value.thinkingLevel,
                customInstruction = if (mode == AgentMode.CUSTOM) _customInstruction.value else null
            )
        )
    }

    /** 用户确认/驳回了 Spec 模式的规格，恢复引擎执行。 */
    fun submitSpecConfirmation(confirmed: Boolean) {
        _uiState.update { it.copy(awaitingSpecConfirmation = false) }
        (agentEngine as? ApexAgentEngine)?.submitSpecConfirmation(confirmed)
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        _uiState.update { it.copy(thinkingLevel = level) }
        (agentEngine as? ApexAgentEngine)?.updateConfig(
            AgentConfig(mode = _uiState.value.mode, thinkingLevel = level)
        )
    }

    fun confirmPlan(confirmed: Boolean) {
        _uiState.update { it.copy(awaitingPlanConfirmation = false) }
        (agentEngine as? ApexAgentEngine)?.submitPlanConfirmation(confirmed)
    }

    /** 用户回答了 Agent 的提问，恢复引擎执行。 */
    fun submitUserInput(answer: String) {
        _uiState.update { it.copy(pendingUserInput = null) }
        (agentEngine as? ApexAgentEngine)?.submitUserInput(answer)
    }

    /** 用户取消了 Agent 的提问，中止等待。 */
    fun cancelUserInput() {
        _uiState.update { it.copy(pendingUserInput = null) }
        (agentEngine as? ApexAgentEngine)?.cancelUserInput()
    }

    fun answerQuestion(selectedIds: List<String>, customText: String?) {
        val question = pendingQuestion.value ?: return

        val answer = AgentAnswer(
            questionId = question.id,
            selectedOptionId = selectedIds.firstOrNull(),
            selectedOptionIds = selectedIds,
            customText = customText?.takeIf { it.isNotBlank() }
        )

        val displayAnswer = when {
            !customText.isNullOrBlank() -> customText.trim()
            selectedIds.isNotEmpty() -> question.options
                .filter { it.id in selectedIds }
                .joinToString("、") { it.label }
                .ifBlank { "未知选项" }
            else -> "跳过"
        }

        _uiState.update { state ->
            state.copy(
                messages = state.messages + AgentUiMessage.System(
                    "✅ 已回答：$displayAnswer"
                )
            )
        }

        userQuestionBridge.submit(answer)
    }

    fun cancelQuestion() {
        val question = pendingQuestion.value ?: return

        _uiState.update { state ->
            state.copy(
                messages = state.messages + AgentUiMessage.System(
                    "⏹ 已跳过 Agent 提问"
                )
            )
        }

        userQuestionBridge.submit(
            AgentAnswer(
                questionId = question.id,
                skipped = true
            )
        )
    }

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
        // 取消前：先刷出未落盘的流式缓冲，拿到完整文本快照。
        flushStreamBuffers()
        val partialResponse = _uiState.value.currentResponse
        val partialThinking = _uiState.value.currentThinking

        currentJob?.cancel()
        viewModelScope.launch { agentEngine.abort() }

        // 取消后：部分产物落盘 + 状态复位。
        _uiState.update { state ->
            val extra = buildList<AgentUiMessage> {
                if (partialResponse.isNotBlank()) {
                    add(AgentUiMessage.Agent(text = partialResponse, isPartial = true))
                }
                if (partialThinking.isNotBlank()) {
                    add(AgentUiMessage.ThinkingMessage(partialThinking))
                }
                add(AgentUiMessage.System("⏹ 已中止"))
            }
            state.copy(
                messages = state.messages + extra,
                isLoading = false,
                currentResponse = "",
                currentThinking = "",
                currentToolCall = null
            )
        }
        resetStreamBuffers()
        toolFlushJob?.cancel()
        toolFlushJob = null
        toolOutputBuffer.clear()
        activeToolCallId = null
        liveOutputStepId = null
        currentToolCallSteps = null
    }

    fun newChat() {
        currentJob?.cancel()
        // 清空所有流式/工具运行态，防止残留缓冲串入新会话。
        resetStreamBuffers()
        toolFlushJob?.cancel()
        toolFlushJob = null
        toolOutputBuffer.clear()
        activeToolCallId = null
        liveOutputStepId = null
        currentToolCallSteps = null
        viewModelScope.launch {
            (agentEngine as? ApexAgentEngine)?.clearHistory()
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    currentThinking = "",
                    currentResponse = "",
                    currentToolCall = null,
                    plan = null,
                    awaitingPlanConfirmation = false,
                    spec = null,
                    awaitingSpecConfirmation = false,
                    pendingUserInput = null,
                    isLoading = false,
                    historyDepth = 0
                )
            }
        }
    }

    fun setReasoningEffort(effort: ReasoningEffort) {
        context.getSharedPreferences("apex_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("llm_reasoning_effort", effort.name)
            .apply()
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

    /**
     * 切换当前模型：把该 Profile 设为默认 + 同步角色映射 + 引擎温度，
     * 运行中的 LLM client 由 DynamicLlmClient 自动重建（即时生效）。
     */
    fun selectProfile(profileId: String) {
        val target = settingsRepository.getProfile(profileId) ?: return
        settingsRepository.setDefaultProfile(profileId)
        settingsRepository.updateRoles { copy(primaryProfileId = profileId) }
        // 引擎侧仅同步温度（temperature 是 Agent 引擎 chat 调用的入参）
        (agentEngine as? ApexAgentEngine)?.patchConfig { cfg ->
            cfg.copy(temperature = target.temperature)
        }
    }

    /**
     * 更新当前模型的采样参数（Temperature / Top-P / Max Tokens）。
     * 写入 Profile（持久化）后由 DynamicLlmClient 即时生效。
     */
    fun updateModelParams(temperature: Float, topP: Float, maxTokens: Int) {
        val cur = settingsRepository.getProfile(currentProfileId.value ?: return) ?: return
        settingsRepository.upsertProfile(
            cur.copy(
                temperature = temperature,
                topP = topP,
                maxOutputTokens = maxTokens
            )
        )
        (agentEngine as? ApexAgentEngine)?.patchConfig { cfg ->
            cfg.copy(temperature = temperature)
        }
    }

    /** 函数调用二级菜单候选：全部已注册工具（id + 显示名）。 */
    fun availableTools(): List<Pair<String, String>> =
        toolRegistry.getAllTools().map { it.id to it.name }.sortedBy { it.first }

    /** "小圆环"工具菜单状态仓库（UI 直接读写开关/规则/格式）。 */
    val toolkitStore: ChatToolkitStore = chatToolkit

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

    // ═══ 斜杠指令处理 ═══

    /**
     * 处理斜杠指令。
     *
     * 解析格式：`/skill:code_interpreter [key=value ...] 附加的用户要求...`
     *
     * 解析与路由职责已下沉到 [SlashCommands]（其内部再委托
     * SlashCommandParser + SlashCommandRouter），本方法只负责：
     * - 把解析/路由结果（banner + agentPrompt）应用到 UI 状态；
     * - GitHub 未连接特例下发射 [requestGithubConnect] 信号；
     * - 把 agentPrompt 交给 [agentEngine] 执行。
     *
     * 特例：当路由器返回 `requestGithubConnect = true`（目前仅 `/mcp:github`
     * 在未连接时触发）时，本方法只追加 systemMessage 并发射
     * [requestGithubConnect] 信号让 UI 打开 GitHub 连接对话框，**不**调用
     * agentEngine.execute —— 因为没有可执行的上下文。
     *
     * 与 [sendMessage] 共用同一个 [currentJob]：发送新指令会取消上一个流式任务。
     */
    private fun handleSlashCommand(command: String) {
        val result = SlashCommands.handle(command, githubTokenManager)

        // 指令会取消上一个流式任务：先清空流式缓冲，防残留文本串入新一轮。
        resetStreamBuffers()

        // 始终追加反馈消息，让用户看到指令被识别 + 当前状态：
        // Skill 指令使用专用横幅（SkillStart），其余指令用 System 行。
        _uiState.update { s ->
            s.copy(
                messages = s.messages + result.banner,
                isLoading = result.isLoading,
                currentThinking = "",
                currentResponse = ""
            )
        }

        if (result is SlashCommands.Result.RequestGithubConnect) {
            // 请求 UI 打开 GitHub 连接流程；不进入 Agent 主循环。
            // tryEmit 因为 extraBufferCapacity=1，订阅者存在时一定成功；
            // 即便 UI 尚未订阅（冷启动竞态），缓冲区也会保留一次事件。
            _requestGithubConnect.tryEmit(Unit)
            return
        }

        // 记录 Skill 上下文，循环内产生的工具调用会被标为 SKILL 来源。
        val execute = result as SlashCommands.Result.Execute
        skillContext = execute.skillName

        currentJob = viewModelScope.launch {
            agentEngine.execute(execute.agentPrompt).collect { event -> handleEvent(event) }
        }.apply {
            invokeOnCompletion { skillContext = null }
        }
    }

    companion object {
        private const val KEY_DRAFT_INPUT = "draft_input"
        private const val KEY_SETTINGS = "apex_settings"
        private const val KEY_CUSTOM_INSTRUCTION = "custom_mode_instruction"

        /** 工具输出 UI 刷新节流间隔（≈1 帧 = 16ms）。 */
        private const val FLUSH_INTERVAL_MS = 16L

        /** 回复/思考流式文本 UI 刷新节流间隔（≈2 帧 = 33ms，约 30fps）。 */
        private const val STREAM_FLUSH_INTERVAL_MS = 33L
    }
}
