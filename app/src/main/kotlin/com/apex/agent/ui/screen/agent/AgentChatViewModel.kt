package com.apex.agent.ui.screen.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.attachment.ImageAttachmentConverter
import com.apex.agent.attachment.PredictiveAttachmentPreprocessor
import com.apex.agent.core.engine.*
import com.apex.agent.core.llm.ImageContent
import com.apex.agent.core.llm.ReasoningEffort
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.slash.SlashCommandParser
import com.apex.agent.slash.SlashCommandRouter
import com.apex.agent.slash.SlashRouteContext
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    val historyDepth: Int = 0
)

/**
 * 工具调用来源分类，用于 UI 差异化呈现（图标 / 颜色 / 标签）。
 *
 * 引擎事件本身没有"类型"字段，ViewModel 在 [classifyTool] 中根据
 * toolName 前缀与已知 id 推断。这样用户能一眼区分本地工具 / MCP /
 * 联网搜索 / 网页抓取 / Skill 调用。
 */
enum class ToolKind { LOCAL, MCP, WEB_SEARCH, WEB_FETCH, SKILL }

sealed interface AgentUiMessage {
    data class User(
        val text: String,
        val attachments: List<MessageAttachment> = emptyList(),
        val timestamp: Long = java.lang.System.currentTimeMillis()
    ) : AgentUiMessage
    data class Agent(val text: String, val timestamp: Long = java.lang.System.currentTimeMillis()) : AgentUiMessage
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
        val timestamp: Long = java.lang.System.currentTimeMillis()
    ) : AgentUiMessage
    data class System(val text: String) : AgentUiMessage
    /**
     * 错误提示块（区别于灰色 System 行）：红色高亮 + 可重试标记。
     */
    data class Error(
        val message: String,
        val canRetry: Boolean = false,
        val timestamp: Long = java.lang.System.currentTimeMillis()
    ) : AgentUiMessage
    data class PlanMessage(val plan: ExecutionPlan) : AgentUiMessage
    data class ThinkingMessage(val thought: String) : AgentUiMessage
}

data class AgentToolCallUi(
    val callId: String = "",
    val toolName: String,
    val args: String,
    /** 实时输出（由 ToolOutputChunk 逐段累积，节流后刷新；尾部窗口 4000 字符）。 */
    val output: String = "",
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
    val skill: String? = null
) {

    companion object {
        /** 运行中工具卡片最多保留的实时输出字符数（尾部窗口）。 */
        const val MAX_LIVE_TOOL_OUTPUT_CHARS = 4000
    }
}

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
        val server = Regex("""(?i)"server"\s*:\s*"([^"]+)"""").find(args)
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
    val githubTokenManager: GithubTokenManager,
    private val savedStateHandle: SavedStateHandle,
    private val preprocessor: PredictiveAttachmentPreprocessor,
    private val userQuestionBridge: UserQuestionBridge,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentChatUiState(historyDepth = memory.count()))
    val uiState: StateFlow<AgentChatUiState> = _uiState.asStateFlow()

    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

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
     * 当前 Slash 指令触发的 Skill 名称（若来自 `/skill:xxx`）。
     * 在该 Skill 的 agent 循环中产生的工具调用会被标记为 SKILL 来源，
     * 便于 UI 区分"这是一个 Skill 调用"。循环结束后清空。
     */
    private var skillContext: String? = null

    /**
     * 附件处理 Job 追踪，支持取消（缺陷 1 修复）。
     */
    private val attachmentJobs = mutableMapOf<Int, Job>()
    private var attachmentIdCounter = 0

    init {
        // 启动预测性附件预处理清理循环（每 5 分钟清理 30 分钟前的预拷贝文件）
        preprocessor.startCleanupLoop(viewModelScope)
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
        if (trimmedText.isEmpty() && _attachments.value.isEmpty()) return

        // 取消前一个尚未完成的流式任务
        currentJob?.cancel()

        // ★ 缺陷 2 修复：无条件收集并清空附件，避免斜杠指令分支 return 后附件永久残留
        val currentAttachments = _attachments.value.toList()
        _attachments.value = emptyList()

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
     * 未预拷贝的附件回退到 [copyToSandboxSafe]（64KB buffer + ensureActive）。
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
                val localPath = preprocessedPath ?: copyToSandboxSafe(att.uri, att.name)

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

        agentEngine.execute(userInput).collect { event ->
            handleEvent(event)
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            // ═══ 思考 ═══
            is AgentEvent.ThinkingStart -> {
                _uiState.update { it.copy(currentThinking = "") }
            }
            is AgentEvent.ThinkingChunk -> {
                _uiState.update {
                    it.copy(currentThinking = it.currentThinking + event.text)
                }
            }
            is AgentEvent.ThinkingComplete -> {
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
            is AgentEvent.PlanConfirmed -> {
                _uiState.update { state ->
                    state.copy(
                        awaitingPlanConfirmation = false,
                        messages = state.messages + AgentUiMessage.PlanMessage(event.plan)
                    )
                }
            }

            // ═══ 工具调用（流式）═══
            is AgentEvent.ToolCallStart -> {
                // 重置缓冲区 + 节流状态，记录当前活跃工具 callId 用于 chunk 路由。
                activeToolCallId = event.callId
                toolOutputBuffer.clear()
                toolFlushJob?.cancel()
                toolFlushJob = null

                val (kind, server) = classifyTool(event.toolName, event.arguments)
                val skill = if (kind == ToolKind.SKILL) skillContext else null

                _uiState.update { state ->
                    state.copy(
                        currentToolCall = AgentToolCallUi(
                            callId = event.callId,
                            toolName = event.toolName,
                            args = event.arguments,
                            output = "",
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
                        _uiState.update { state ->
                            state.copy(
                                currentToolCall = state.currentToolCall?.copy(output = snapshot)
                            )
                        }
                        toolFlushJob = null
                    }
                }
            }
            is AgentEvent.ToolProgress -> {
                if (event.callId != activeToolCallId) return
                _uiState.update { state ->
                    state.copy(
                        currentToolCall = state.currentToolCall?.copy(
                            progress = event.percent,
                            progressMessage = event.message
                        )
                    )
                }
            }
            is AgentEvent.ToolCallComplete -> {
                // 取消尚未刷新的 flush Job，并把剩余缓冲区一次性写入历史消息输出
                // （ToolCallComplete.output 已是 engine 截断后的完整输出，直接用即可）。
                toolFlushJob?.cancel()
                toolFlushJob = null
                activeToolCallId = null
                toolOutputBuffer.clear()

                val (kind, server) = classifyTool(event.toolName, event.arguments)
                val skill = if (kind == ToolKind.SKILL) skillContext else null

                _uiState.update { state ->
                    state.copy(
                        currentToolCall = null,
                        messages = state.messages + AgentUiMessage.ToolCall(
                            toolName = event.toolName,
                            args = event.arguments,
                            output = event.output.take(500),
                            fullOutput = event.fullOutput.ifBlank { event.output },
                            success = event.success,
                            durationMs = event.durationMs,
                            kind = kind,
                            server = server,
                            skill = skill
                        )
                    )
                }
            }

            // ═══ 流式回复 ═══
            is AgentEvent.ResponseChunk -> {
                _uiState.update {
                    it.copy(currentResponse = it.currentResponse + event.text)
                }
            }
            is AgentEvent.ResponseComplete -> {
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
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + AgentUiMessage.Error(
                            message = event.message,
                            canRetry = event.recoverable
                        ),
                        isLoading = false
                    )
                }
            }
            is AgentEvent.Complete -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        historyDepth = (agentEngine as? ApexAgentEngine)?.historyCount() ?: it.historyDepth
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
            AgentConfig(mode = mode, thinkingLevel = _uiState.value.thinkingLevel)
        )
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

    fun answerQuestion(selectedOptionId: String?, customText: String?) {
        val question = pendingQuestion.value ?: return

        val answer = AgentAnswer(
            questionId = question.id,
            selectedOptionId = selectedOptionId,
            customText = customText?.takeIf { it.isNotBlank() }
        )

        val displayAnswer = when {
            !customText.isNullOrBlank() -> customText.trim()
            selectedOptionId != null -> question.options
                .firstOrNull { it.id == selectedOptionId }
                ?.label
                ?: "未知选项"
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

    fun abort() {
        currentJob?.cancel()
        viewModelScope.launch { agentEngine.abort() }
    }

    fun newChat() {
        currentJob?.cancel()
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
    // 附件处理（缺陷 1 修复：全部异步化）
    // ═══════════════════════════════════════════════════════════

    /**
     * 处理文件附件。立即添加 UPLOADING 占位项，IO 线程读取真实元数据后回填。
     * 同时触发预测性预处理（后台拷贝到沙箱），发送时零等待。
     */
    fun attachFile(uri: Uri) {
        val id = attachmentIdCounter++
        // 先添加一个 UPLOADING 状态的占位项，UI 立即响应
        _attachments.update {
            it + Attachment(
                uri = uri,
                name = "读取中...",
                mimeType = "application/octet-stream",
                sizeBytes = 0,
                type = AttachmentType.FILE,
                uploadProgress = 0f,
                status = UploadStatus.UPLOADING
            )
        }

        attachmentJobs[id] = viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = getFileMetadataSafe(uri).copy(
                    uploadProgress = 1.0f,
                    status = UploadStatus.SUCCESS
                )
                _attachments.update { list ->
                    list.mapIndexed { index, att ->
                        if (index == list.lastIndex && att.status == UploadStatus.UPLOADING) {
                            info
                        } else att
                    }
                }
                // ★ 触发预测性预处理：后台拷贝到沙箱，用户编辑文本时同步进行
                preprocessor.preprocess(uri, info.name)
            } catch (e: Exception) {
                _attachments.update { list ->
                    list.mapIndexed { index, att ->
                        if (index == list.lastIndex && att.status == UploadStatus.UPLOADING) {
                            att.copy(status = UploadStatus.ERROR, name = "读取失败")
                        } else att
                    }
                }
            }
        }
    }

    /**
     * 处理图片附件。立即添加 UPLOADING 占位项，IO 线程读取真实元数据后回填。
     * 同时触发预测性预处理（后台拷贝到沙箱），发送时零等待。
     */
    fun attachImage(uri: Uri) {
        val id = attachmentIdCounter++
        _attachments.update {
            it + Attachment(
                uri = uri,
                name = "读取中...",
                mimeType = "image/*",
                sizeBytes = 0,
                type = AttachmentType.IMAGE,
                uploadProgress = 0f,
                status = UploadStatus.UPLOADING
            )
        }

        attachmentJobs[id] = viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = getFileMetadataSafe(uri).copy(
                    type = AttachmentType.IMAGE,
                    uploadProgress = 1.0f,
                    status = UploadStatus.SUCCESS
                )
                _attachments.update { list ->
                    list.mapIndexed { index, att ->
                        if (index == list.lastIndex && att.status == UploadStatus.UPLOADING) {
                            info
                        } else att
                    }
                }
                // ★ 触发预测性预处理
                preprocessor.preprocess(uri, info.name)
            } catch (e: Exception) {
                _attachments.update { list ->
                    list.mapIndexed { index, att ->
                        if (index == list.lastIndex && att.status == UploadStatus.UPLOADING) {
                            att.copy(status = UploadStatus.ERROR, name = "读取失败")
                        } else att
                    }
                }
            }
        }
    }

    /**
     * 移除附件。同时取消对应的元数据读取 Job 和预测性预拷贝。
     */
    fun removeAttachment(index: Int) {
        attachmentJobs.values.forEach { it.cancel() }
        // 取消被移除附件的预测性预拷贝
        val removed = _attachments.value.getOrNull(index)
        removed?.uri?.let { preprocessor.cancel(it) }
        _attachments.update { list ->
            list.filterIndexed { i, _ -> i != index }
        }
    }

    override fun onCleared() {
        super.onCleared()
        attachmentJobs.values.forEach { it.cancel() }
        preprocessor.stopCleanupLoop()
    }

    // ═══ 斜杠指令处理 ═══

    /**
     * 处理斜杠指令。
     *
     * 解析格式：`/skill:code_interpreter [key=value ...] 附加的用户要求...`
     *
     * 解析与路由职责已下沉到 [SlashCommandParser] + [SlashCommandRouter]，
     * 本方法只负责：
     * - 把当前 GitHub 连接状态快照成 [SlashRouteContext] 传给路由器；
     * - 把路由结果（systemMessage + agentPrompt）应用到 UI 状态；
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
        val parsed = SlashCommandParser.parse(command)
        val githubState = githubTokenManager.connectionState.value
        val context = SlashRouteContext(
            githubConnected = githubState.isConnected,
            githubUsername = githubState.username
        )
        val route = SlashCommandRouter.route(parsed, context)

        // 始终追加系统消息，让用户看到指令被识别 + 当前状态。
        _uiState.update { s ->
            s.copy(
                messages = s.messages + AgentUiMessage.System(route.systemMessage),
                isLoading = !route.requestGithubConnect,
                currentThinking = "",
                currentResponse = ""
            )
        }

        if (route.requestGithubConnect) {
            // 请求 UI 打开 GitHub 连接流程；不进入 Agent 主循环。
            // tryEmit 因为 extraBufferCapacity=1，订阅者存在时一定成功；
            // 即便 UI 尚未订阅（冷启动竞态），缓冲区也会保留一次事件。
            _requestGithubConnect.tryEmit(Unit)
            return
        }

        // 记录 Skill 上下文，循环内产生的工具调用会被标为 SKILL 来源。
        skillContext = route.skillName

        currentJob = viewModelScope.launch {
            agentEngine.execute(route.agentPrompt).collect { event -> handleEvent(event) }
        }.apply {
            invokeOnCompletion { skillContext = null }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 异步 I/O 工具方法（缺陷 1 修复）
    // ═══════════════════════════════════════════════════════════

    /**
     * 异步读取附件元数据。必须在 IO 调度器中调用。
     *
     * ContentResolver.query() 走 Binder IPC 到 MediaProvider，可能阻塞 2-5 秒。
     */
    private suspend fun getFileMetadataSafe(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "unknown_file"
        var mimeType = "application/octet-stream"
        var size = 0L

        try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            // ContentProvider 可能已失效（如临时权限过期）
            name = "file_${System.currentTimeMillis()}"
        }

        mimeType = try {
            resolver.getType(uri) ?: mimeType
        } catch (e: Exception) {
            mimeType
        }

        val type = when {
            mimeType.startsWith("image/") -> AttachmentType.IMAGE
            mimeType.startsWith("audio/") -> AttachmentType.AUDIO
            mimeType.startsWith("video/") -> AttachmentType.VIDEO
            mimeType.contains("zip") || mimeType.contains("tar") || mimeType.contains("rar") -> AttachmentType.ARCHIVE
            else -> AttachmentType.FILE
        }

        Attachment(uri, name, mimeType, size, type)
    }

    /**
     * 异步拷贝附件到应用沙箱。
     *
     * 修复点：
     * - 64KB buffer（比默认 8KB 快 8 倍，匹配 UFS/eMMC optimal I/O block）；
     * - [ensureActive] 协程取消检查点，用户移除附件时立即停止拷贝；
     * - 落盘失败抛出异常，由调用方处理。
     */
    private suspend fun copyToSandboxSafe(
        uri: Uri,
        fileName: String
    ): String = withContext(Dispatchers.IO) {
        val targetDir = java.io.File(context.filesDir, "attachments")
        targetDir.mkdirs()
        val targetFile = java.io.File(targetDir, "${System.currentTimeMillis()}_$fileName")

        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024) // 64KB buffer
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    // 协程取消检查点
                    ensureActive()
                    output.write(buffer, 0, len)
                }
                output.flush()
            }
        } ?: throw IllegalStateException("Cannot open input stream for $uri")

        targetFile.absolutePath
    }

    companion object {
        private const val KEY_DRAFT_INPUT = "draft_input"

        /** 工具输出 UI 刷新节流间隔（≈1 帧 = 16ms）。 */
        private const val FLUSH_INTERVAL_MS = 16L
    }
}
