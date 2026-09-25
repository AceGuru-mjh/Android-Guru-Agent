package com.apex.agent.ui.screen.code

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.code.CodeAgentEngine
import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.core.engine.AgentAnswer
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.engine.UserQuestionBridge
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.code.ws.CodeWorkspace
import com.apex.agent.platform.code.ws.CodeWorkspaceManager
import com.apex.agent.ui.screen.code.editor.AtRefParser
import com.apex.agent.ui.screen.code.editor.EditorFileLoader
import com.apex.agent.ui.screen.code.session.CodeSessionSnapshot
import com.apex.agent.ui.screen.code.session.CodeSessionStore
import com.apex.agent.ui.screen.code.session.toCodeTodos
import com.apex.agent.ui.screen.code.session.toStorable
import com.apex.agent.ui.screen.code.session.withFreshIds
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * # Code ViewModel — Coding 模式屏的状态与事件归约
 *
 * 与 AgentChatViewModel 的关系：**平行实现而非复用**（两模式的交互面差异大 ——
 * Code 屏以工作区为中心、工具卡以 diff/验证为核心），但引擎侧契约完全一致：
 * AgentEvent 流 → UI 状态归约 → ConfirmationSink/submitUserInput 回传。
 *
 * 精简的事件归约（16ms 级流式节流不做 —— 编码回复长度可控，直接增量 append）：
 * - ResponseChunk → 当前助手消息追加；
 * - ToolCallStart/Complete → 工具卡（code_edit/write 渲染 diff 摘要）；
 * - UserInputRequired → 挂起等待用户输入（ask_user）；
 * - Complete/Aborted/Error → 收尾 + todo 快照刷新。
 *
 * ## v1.0 会话恢复（#152）
 *
 * 引擎侧上下文由 CodeConversationMemory 按 workspaceId 持久化（API 级完整
 * 历史，setActiveWorkspace 时自动恢复）；本层补齐 **UI 快照**——消息列表 /
 * todos / 当前文件存 `code_sessions/ws_<id>.json`，进程被杀后回到现场：
 * - bindWorkspace 时「冲刷旧快照 → 恢复新快照」串行（Mutex 防快速切换交错）；
 * - 消息变更后 800ms 防抖落盘（对齐 Agent 模式 ChatHistoryManager 惯例）；
 * - onCleared 时经独立 IO scope 最后冲刷一次（viewModelScope 在 onCleared
 *   前已被取消，防抖窗口内的尾巴变更不能丢）。
 *
 * ## v1.0 编辑器面板与 @ 引用（#154）
 *
 * - sendMessage 前经 AtRefParser 提取 `@file:line` 选区引用：引用块拼进
 *   引擎输入（用户消息 UI 展示保持原文），首个引用设为当前文件并打开面板；
 * - code_edit / code_write 成功后自动跟随被改文件（编辑器显示最新现场）；
 * - 面板数据直读工作区文件（EditorFileLoader，绕开工具输出的 8000 字符
 *   截断），行点击回填 `@file:line` 到输入草稿。
 */
@HiltViewModel
class CodeViewModel @Inject constructor(
    @Named("code") private val codeEngine: AgentEngine,
    private val workspaceManager: CodeWorkspaceManager,
    private val codeTodoTool: CodeTodoTool,
    private val codeSessionStore: CodeSessionStore,
    private val workspaceRoots: CodeWorkspaceRoots,
    private val userQuestionBridge: UserQuestionBridge
) : ViewModel() {

    private val _uiState = MutableStateFlow(CodeUiState())
    val uiState: StateFlow<CodeUiState> = _uiState.asStateFlow()

    /** 工具/权限门的主动提问（AgentQuestion 结构化选项；与 ask_user 的纯文本通道并存）。 */
    val pendingAgentQuestion: StateFlow<AgentQuestion?> = userQuestionBridge.pendingQuestion

    private val idGen = AtomicLong(0)
    private var runJob: Job? = null
    private var editorJob: Job? = null
    private var sessionPersistJob: Job? = null
    private var sessionLoadJob: Job? = null

    /** 当前绑定的会话归属工作区（null = 尚未绑定，不落盘）。 */
    private var boundWorkspaceId: String? = null

    /** 工作区恢复/冲刷串行锁（快速连续切换时防交错）。 */
    private val sessionMutex = Mutex()

    /**
     * 会话落盘的独立 scope：viewModelScope 在 onCleared 之前就被取消，
     * 最后一次冲刷必须有地方落地。Job 短命（单次文件写），无泄漏之虞。
     */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val codeEngineImpl: CodeAgentEngine?
        get() = codeEngine as? CodeAgentEngine

    init {
        // 工作区清单 + 激活恢复（manager init 已恢复 activeId）
        refreshWorkspaces()
        val active = workspaceManager.activeWorkspace()
        if (active != null) {
            bindWorkspace(active)
        }
    }

    // ═══ 消息发送 ═══

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.isRunning) return

        // #154：@file:line 选区引用——引用块只进引擎输入，UI 消息保持原文；
        // 首个引用立即设为当前文件并打开编辑器面板（选区即上下文）。
        val refs = AtRefParser.parse(trimmed)
        val engineInput = AtRefParser.buildContextBlock(refs)?.let { trimmed + it } ?: trimmed
        refs.firstOrNull()?.let { openEditorFile(it.path) }

        _uiState.update {
            it.copy(
                messages = it.messages + CodeChatMessage(idGen.incrementAndGet(), CodeChatMessage.Role.USER, trimmed),
                inputDraft = ""
            )
        }

        codeEngineImpl?.prepareForTask()

        runJob = viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true, error = null) }
            try {
                codeEngine.execute(UserInput(text = engineInput)).collect { event ->
                    reduce(event)
                }
            } catch (e: CancellationException) {
                // abort 或 VM 清理：静默
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "执行失败") }
            } finally {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        messages = it.messages.map { m -> if (m.isStreaming) m.copy(isStreaming = false) else m },
                        contextUsedTokens = codeEngineImpl?.currentTokenCount() ?: 0,
                        contextMaxTokens = codeEngineImpl?.maxContextTokens() ?: 0,
                        todos = codeTodoTool.snapshot()
                    )
                }
                scheduleSessionPersist()
            }
        }
    }

    fun abort() {
        runJob?.cancel()
        viewModelScope.launch { codeEngine.abort() }
        _uiState.update { it.copy(isRunning = false) }
    }

    fun submitUserInput(answer: String) {
        _uiState.update { it.copy(pendingQuestion = null) }
        codeEngine.submitUserInput(answer)
    }

    /** 用户回答了工具/权限门的结构化提问（QuestionCard 回传）。 */
    fun answerAgentQuestion(selectedIds: List<String>, customText: String?) {
        val question = userQuestionBridge.pendingQuestion.value ?: return
        userQuestionBridge.submit(
            AgentAnswer(
                questionId = question.id,
                selectedOptionId = selectedIds.firstOrNull(),
                selectedOptionIds = selectedIds,
                customText = customText?.takeIf { it.isNotBlank() }
            )
        )
    }

    /** 用户取消了工具/权限门的结构化提问（视作跳过）。 */
    fun cancelAgentQuestion() {
        val question = userQuestionBridge.pendingQuestion.value ?: return
        userQuestionBridge.submit(AgentAnswer(questionId = question.id, skipped = true))
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearConversation() {
        if (_uiState.value.isRunning) return
        codeEngineImpl?.clearConversation()
        codeTodoTool.clear()
        boundWorkspaceId?.let { wsId ->
            viewModelScope.launch {
                withContext(Dispatchers.IO) { runCatching { codeSessionStore.clear(wsId) } }
            }
        }
        _uiState.update {
            it.copy(
                messages = emptyList(), todos = emptyList(), contextUsedTokens = 0,
                inputDraft = "", editorFilePath = null, editorFile = null, editorError = null
            )
        }
    }

    // ═══ 输入草稿（#154：@file:line 程序化插入通道）═══

    fun updateInputDraft(text: String) {
        _uiState.update { it.copy(inputDraft = text) }
    }

    /** 把一段引用文本（如 `src/Foo.kt:12`）追加进输入草稿。 */
    fun insertAtRef(ref: String) {
        val piece = ref.trim()
        if (piece.isEmpty()) return
        _uiState.update { state ->
            val joined = when {
                state.inputDraft.isBlank() -> piece
                state.inputDraft.endsWith(" ") -> state.inputDraft + piece
                else -> state.inputDraft + " " + piece
            }
            state.copy(inputDraft = joined)
        }
    }

    // ═══ 编辑器面板（#154）═══

    /** 打开（或切换到）某个工作区相对路径的文件；引擎上下文同步跟随。 */
    fun openEditorFile(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { it.copy(editorFilePath = trimmed, editorFile = null, editorLoading = true, editorError = null) }
        codeEngineImpl?.setActiveFile(trimmed)
        editorJob?.cancel()
        editorJob = viewModelScope.launch {
            try {
                val root = workspaceRoots.activeRoot()
                val file = withContext(Dispatchers.IO) { EditorFileLoader.loadEditorFile(root, trimmed) }
                // 防过期回写：加载期间用户可能已关闭或切到别的文件
                if (_uiState.value.editorFilePath == trimmed) {
                    _uiState.update { it.copy(editorFile = file, editorLoading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value.editorFilePath == trimmed) {
                    _uiState.update { it.copy(editorLoading = false, editorError = e.message ?: "文件加载失败") }
                }
            }
        }
    }

    fun closeEditor() {
        editorJob?.cancel()
        _uiState.update { it.copy(editorFilePath = null, editorFile = null, editorLoading = false, editorError = null) }
    }

    // ═══ 工作区管理 ═══

    fun refreshWorkspaces() {
        _uiState.update {
            it.copy(
                workspaces = workspaceManager.list(),
                activeWorkspace = workspaceManager.activeWorkspace()
            )
        }
    }

    fun createWorkspace(name: String) {
        val created = workspaceManager.create(name)
        if (created == null) {
            _uiState.update { it.copy(error = "无法创建工作区（名称为空或已存在）") }
            return
        }
        bindWorkspace(created)
    }

    fun switchWorkspace(workspaceId: String) {
        val activated = workspaceManager.activate(workspaceId) ?: return
        bindWorkspace(activated)
    }

    fun deleteWorkspace(workspaceId: String) {
        if (_uiState.value.isRunning) {
            _uiState.update { it.copy(error = "任务运行中，不能删除工作区") }
            return
        }
        if (workspaceId == "default") {
            _uiState.update { it.copy(error = "默认工作区不可删除") }
            return
        }
        workspaceManager.delete(workspaceId)
        // 会话快照随工作区一并清理（引擎记忆目录由 manager 负责清理或不影响正确性）
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { codeSessionStore.clear(workspaceId) } }
        }
        refreshWorkspaces()
        val active = workspaceManager.activeWorkspace()
        if (active != null) bindWorkspace(active) else clearConversation()
    }

    private fun bindWorkspace(ws: CodeWorkspace) {
        // 切走前捕获旧会话快照（同工作区重复 bind 不冲刷）
        val previousId = boundWorkspaceId
        val previousSnapshot = if (previousId != null && previousId != ws.workspaceId) {
            buildSessionSnapshot(previousId)
        } else null
        boundWorkspaceId = ws.workspaceId
        sessionPersistJob?.cancel()
        sessionLoadJob?.cancel()
        editorJob?.cancel()

        // 引擎绑定（同步：clearHistory + restoreHistory + refreshContext）
        codeEngineImpl?.setActiveWorkspace(
            workspaceId = ws.workspaceId,
            name = ws.name,
            root = File(ws.hostRootPath)
        )

        // UI 先切到「新工作区空态」，快照异步恢复（含旧快照冲刷）
        _uiState.update {
            it.copy(
                activeWorkspace = ws,
                workspaces = workspaceManager.list(),
                contextUsedTokens = codeEngineImpl?.currentTokenCount() ?: 0,
                contextMaxTokens = codeEngineImpl?.maxContextTokens() ?: 0,
                editorFilePath = null, editorFile = null, editorError = null,
                editorLoading = false
            )
        }

        sessionLoadJob = viewModelScope.launch {
            sessionMutex.withLock {
                if (previousSnapshot != null) {
                    withContext(Dispatchers.IO) { runCatching { codeSessionStore.save(previousSnapshot) } }
                        .onFailure { AppLogger.instance.warn(LogCategory.UI, "CodeSession", "冲刷旧会话快照失败：${it.message}") }
                }
                restoreSessionSnapshot(ws)
            }
        }
    }

    /** 恢复当前工作区的 UI 会话快照；无快照 = 全新会话（欢迎提示）。 */
    private suspend fun restoreSessionSnapshot(ws: CodeWorkspace) {
        val snapshot = withContext(Dispatchers.IO) {
            runCatching { codeSessionStore.load(ws.workspaceId) }.getOrNull()
        }
        if (snapshot != null && snapshot.messages.isNotEmpty()) {
            val restored = snapshot.messages.withFreshIds(1L)
            idGen.set(restored.lastOrNull()?.id ?: 0L)
            codeTodoTool.restore(snapshot.todos.toCodeTodos())
            _uiState.update { it.copy(messages = restored, todos = snapshot.todos.toCodeTodos()) }
            snapshot.lastActiveFile?.let { lastFile -> openEditorFile(lastFile) }
        } else {
            // 全新会话：清 UI 态（引擎侧 setActiveWorkspace 已重置上下文）+ 欢迎提示
            codeTodoTool.clear()
            _uiState.update { it.copy(messages = emptyList(), todos = emptyList()) }
            val env = ws.detectedEnvironment ?: "空工作区"
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + CodeChatMessage(
                        id = idGen.incrementAndGet(),
                        role = CodeChatMessage.Role.SYSTEM,
                        text = "已切换到工作区「${ws.name}」（$env）。描述你的编码任务开始吧。"
                    )
                )
            }
        }
    }

    /** 从当前 UI 态构造会话快照（消息 + todos + 当前文件）。 */
    private fun buildSessionSnapshot(workspaceId: String): CodeSessionSnapshot {
        val state = _uiState.value
        return CodeSessionSnapshot(
            workspaceId = workspaceId,
            messages = state.messages.toStorable(),
            todos = codeTodoTool.snapshot().toStorable(),
            lastActiveFile = state.editorFilePath,
            updatedAt = System.currentTimeMillis()
        )
    }

    /** 会话快照防抖落盘（800ms；对齐 Agent 模式 ChatHistoryManager 惯例）。 */
    private fun scheduleSessionPersist() {
        val wsId = boundWorkspaceId ?: return
        sessionPersistJob?.cancel()
        sessionPersistJob = viewModelScope.launch {
            delay(800)
            // 守卫：防抖期间工作区已切换 → 旧快照已由 bindWorkspace 冲刷，跳过
            if (boundWorkspaceId != wsId) return@launch
            val snapshot = buildSessionSnapshot(wsId)
            withContext(Dispatchers.IO) { runCatching { codeSessionStore.save(snapshot) } }
                .onFailure { AppLogger.instance.warn(LogCategory.UI, "CodeSession", "会话快照落盘失败：${it.message}") }
        }
    }

    // ═══ 事件归约 ═══

    private fun reduce(event: AgentEvent) {
        when (event) {
            is AgentEvent.IterationStart -> _uiState.update { it.copy(currentIteration = event.iteration) }

            is AgentEvent.ResponseChunk -> _uiState.update { state ->
                val messages = state.messages.toMutableList()
                val last = messages.lastOrNull()
                if (last != null && last.role == CodeChatMessage.Role.ASSISTANT && last.isStreaming) {
                    messages[messages.size - 1] = last.copy(text = last.text + event.text)
                } else {
                    messages += CodeChatMessage(
                        id = idGen.incrementAndGet(),
                        role = CodeChatMessage.Role.ASSISTANT,
                        text = event.text,
                        isStreaming = true
                    )
                }
                state.copy(messages = messages)
            }

            is AgentEvent.ResponseComplete -> {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { m ->
                            if (m.isStreaming) m.copy(isStreaming = false) else m
                        }
                    )
                }
                scheduleSessionPersist()
            }

            is AgentEvent.ThinkingChunk -> Unit // 编码屏不渲染思维链（保持输出紧凑）

            is AgentEvent.ToolCallStart -> _uiState.update { state ->
                state.copy(
                    messages = state.messages + CodeChatMessage(
                        id = idGen.incrementAndGet(),
                        role = CodeChatMessage.Role.TOOL,
                        text = "",
                        toolName = event.toolName,
                        isStreaming = true
                    )
                )
            }

            is AgentEvent.ToolOutputChunk -> _uiState.update { state ->
                val messages = state.messages.toMutableList()
                val idx = messages.indexOfLast { it.role == CodeChatMessage.Role.TOOL && it.isStreaming }
                if (idx >= 0) {
                    val m = messages[idx]
                    messages[idx] = m.copy(text = (m.text + event.chunk).take(4000))
                }
                state.copy(messages = messages)
            }

            is AgentEvent.ToolCallComplete -> {
                _uiState.update { state ->
                    val messages = state.messages.toMutableList()
                    val idx = messages.indexOfLast { it.role == CodeChatMessage.Role.TOOL && it.isStreaming }
                    val card = CodeChatMessage(
                        id = idGen.incrementAndGet(),
                        role = CodeChatMessage.Role.TOOL,
                        text = event.output.take(4000),
                        toolName = event.toolName,
                        toolSuccess = event.success,
                        durationMs = event.durationMs,
                        isStreaming = false
                    )
                    if (idx >= 0) messages[idx] = card else messages += card
                    state.copy(messages = messages, todos = codeTodoTool.snapshot())
                }
                // #154：编辑/写成功的文件自动成为「当前文件」（编辑器跟随最新现场）
                if (event.success && (event.toolName == "code_edit" || event.toolName == "code_write")) {
                    extractToolPath(event.arguments)?.let { openEditorFile(it) }
                }
                scheduleSessionPersist()
            }

            is AgentEvent.UserInputRequired -> _uiState.update {
                it.copy(pendingQuestion = event.prompt)
            }

            is AgentEvent.Error -> {
                _uiState.update { it.copy(error = event.message) }
                scheduleSessionPersist()
            }

            is AgentEvent.ContextCompressed -> {
                _uiState.update {
                    it.copy(
                        contextUsedTokens = event.afterTokens,
                        messages = it.messages + CodeChatMessage(
                            id = idGen.incrementAndGet(),
                            role = CodeChatMessage.Role.SYSTEM,
                            text = "上下文已压缩（${event.beforeTokens} → ${event.afterTokens} tokens，${event.strategy}）"
                        )
                    )
                }
                scheduleSessionPersist()
            }

            is AgentEvent.Complete -> {
                val summary = buildString {
                    append("完成 · ${event.totalIterations} 轮 · ${event.totalToolCalls} 次工具 · ${event.totalDurationMs / 1000}s")
                }
                _uiState.update {
                    it.copy(
                        messages = it.messages + CodeChatMessage(
                            id = idGen.incrementAndGet(),
                            role = CodeChatMessage.Role.SYSTEM,
                            text = summary
                        ),
                        todos = codeTodoTool.snapshot()
                    )
                }
                scheduleSessionPersist()
            }

            is AgentEvent.Aborted -> _uiState.update { it.copy(isRunning = false) }

            // Plan/Spec/Reflection/Step 事件在 CODING(BUILD) 循环不触发，保持完备即可
            else -> Unit
        }
    }

    /** 从工具调用参数 JSON 里提取 path 字段（code_edit/code_write 的文件跟随）。 */
    private fun extractToolPath(arguments: String): String? = runCatching {
        Json.parseToJsonElement(arguments).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun onCleared() {
        runJob?.cancel()
        // #152：viewModelScope 在 onCleared 前已被取消——防抖尾巴经独立 scope 冲刷
        val wsId = boundWorkspaceId
        if (wsId != null) {
            val snapshot = buildSessionSnapshot(wsId)
            persistScope.launch {
                runCatching { codeSessionStore.save(snapshot) }
                    .onFailure { AppLogger.instance.warn(LogCategory.UI, "CodeSession", "最终快照冲刷失败：${it.message}") }
            }
        }
        super.onCleared()
    }
}
