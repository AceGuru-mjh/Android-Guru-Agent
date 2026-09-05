package com.apex.agent.ui.screen.code

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.code.CodeAgentEngine
import com.apex.agent.core.codetools.problems.ProblemsAggregator
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.platform.code.ws.CodeWorkspaceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named

/**
 * Code Mode ViewModel（Spec §47）。
 *
 * 注入 `@Named("code") AgentEngine`（独立于 Agent 的 engine 实例，Spec §10）+
 * [CodeAgentEngine]（用于 prepareForTask 注入 JIT 上下文）+ [CodeWorkspaceManager]
 * （workspace 生命周期）+ [ProblemsAggregator]（Problems 面板数据）。
 *
 * 与 [com.apex.agent.ui.screen.agent.AgentChatViewModel] 互不干扰：
 * - 独立 conversationHistory（code engine 实例自带）
 * - 独立 CodeConversationMemory（per-workspaceId 分键）
 * - 独立 AgentConfig（code 系统提示 + 收窄工具集）
 */
@HiltViewModel
class CodeViewModel @Inject constructor(
    @Named("code") private val codeEngine: CodeAgentEngine,
    private val workspaceManager: CodeWorkspaceManager,
    private val problems: ProblemsAggregator
) : ViewModel() {

    private val _uiState = MutableStateFlow(CodeUiState(historyDepth = 0))
    val uiState: StateFlow<CodeUiState> = _uiState.asStateFlow()

    init {
        // 启动即尝试恢复上次 workspace（Spec §12）
        refreshRecent()
        restoreLastWorkspace()
    }

    // ═══ Workspace 生命周期 ═══

    fun refreshRecent() {
        viewModelScope.launch {
            _uiState.update { it.copy(recentWorkspaces = workspaceManager.list()) }
        }
    }

    /**
     * 打开 workspace（Spec §7）：resolve host dir + detect env + bind code memory + 设 active FS。
     */
    fun openWorkspace(workspaceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = workspaceManager.open(workspaceId)
            result.fold(
                onSuccess = { ws ->
                    codeEngine.setActiveWorkspace(ws.workspaceId)
                    _uiState.update {
                        it.copy(
                            activeWorkspace = ws, isLoading = false,
                            historyDepth = codeEngine.historyCount(),
                            messages = emptyList(),  // code memory 已 bindWorkspace，历史由 engine 持有
                            problemsSummary = problems.summary().toString()
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = "open failed: ${e.message}") }
                }
            )
        }
    }

    fun createWorkspace(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            workspaceManager.create(name).fold(
                onSuccess = { ws -> openWorkspace(ws.workspaceId) },
                onFailure = { e -> _uiState.update { it.copy(error = "create failed: ${e.message}") } }
            )
        }
    }

    fun closeWorkspace() {
        val ws = _uiState.value.activeWorkspace ?: return
        viewModelScope.launch {
            codeEngine.clearHistory()
            workspaceManager.close(ws.workspaceId)
            _uiState.update { it.copy(activeWorkspace = null, messages = emptyList(), problemsSummary = "—") }
            refreshRecent()
        }
    }

    private fun restoreLastWorkspace() {
        viewModelScope.launch(Dispatchers.IO) {
            workspaceManager.restoreLast()?.let { summary ->
                openWorkspace(summary.workspaceId)
            }
        }
    }

    fun selectBottomTab(tab: CodeBottomTab) {
        _uiState.update { it.copy(activeBottomTab = tab) }
    }

    fun updateInput(text: String) = _uiState.update { it.copy(inputText = text) }

    // ═══ 任务执行 ═══

    /**
     * 发送 Code 任务（Spec §24 自动验证闭环）。
     * 1. prepareForTask 注入 JIT 上下文（当前文件/diagnostics/git diff）
     * 2. 追加 user message
     * 3. collect codeEngine.execute Flow<AgentEvent> → 映射 UI message
     */
    fun sendTask(task: String) {
        if (task.isBlank()) return
        val ws = _uiState.value.activeWorkspace
        if (ws == null) {
            _uiState.update { it.copy(error = "请先打开一个 Code workspace（项目仓库）") }
            return
        }
        _uiState.update {
            it.copy(
                messages = it.messages + CodeUiMessage.User(task),
                inputText = "", isLoading = true, error = null,
                currentResponse = "", currentThinking = "", currentToolCall = ""
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                codeEngine.prepareForTask(task)
            } catch (_: Exception) { /* context provider 故障不阻塞 */ }
            val userMsg = CodeUiMessage.User(task)
            val flow = try {
                codeEngine.execute(task)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "engine error: ${e.message}") }
                return@launch
            }
            val newMessages = mutableListOf<CodeUiMessage>(userMsg)
            val currentAssistant = StringBuilder()
            try {
                flow.collect { ev -> handleEvent(ev, newMessages, currentAssistant) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "stream error: ${e.message}") }
                return@launch
            }
            if (currentAssistant.isNotEmpty()) {
                newMessages.add(CodeUiMessage.Assistant(currentAssistant.toString()))
            }
            _uiState.update { it.copy(isLoading = false, messages = it.messages.dropLast(1) + newMessages.drop(1), currentResponse = "", currentThinking = "", currentToolCall = "", problemsSummary = problems.summary().toString()) }
            refreshRecent()
        }
    }

    private fun handleEvent(ev: AgentEvent, msgs: MutableList<CodeUiMessage>, currentAssistant: StringBuilder) {
        when (ev) {
            is AgentEvent.ThinkingStart -> _uiState.update { it.copy(currentThinking = "thinking…") }
            is AgentEvent.ThinkingChunk -> _uiState.update { it.copy(currentThinking = it.currentThinking + ev.text) }
            is AgentEvent.ThinkingComplete -> {
                if (_uiState.value.currentThinking.isNotBlank()) msgs.add(CodeUiMessage.Thinking(_uiState.value.currentThinking))
                _uiState.update { it.copy(currentThinking = "") }
            }
            is AgentEvent.ToolCallStart -> _uiState.update { it.copy(currentToolCall = "${ev.toolName}(${ev.arguments.take(80)})") }
            is AgentEvent.ToolOutputChunk -> _uiState.update { it.copy(currentToolCall = _uiState.value.currentToolCall) }
            is AgentEvent.ToolCallComplete -> {
                msgs.add(CodeUiMessage.Tool(ev.toolName, ev.arguments, ev.output.take(2000), ev.success))
                _uiState.update { it.copy(currentToolCall = "") }
            }
            is AgentEvent.ResponseChunk -> { currentAssistant.append(ev.text); _uiState.update { it.copy(currentResponse = currentAssistant.toString()) } }
            is AgentEvent.ResponseComplete -> { /* final assistant assembled below */ }
            is AgentEvent.Complete -> _uiState.update { it.copy(isLoading = false) }
            is AgentEvent.Error -> _uiState.update { it.copy(isLoading = false, error = ev.message) }
            is AgentEvent.Aborted -> _uiState.update { it.copy(isLoading = false, error = "aborted") }
            else -> { /* PlanGenerated / StepStart / etc — v1 不专门渲染 */ }
        }
    }

    fun abort() {
        viewModelScope.launch { runCatching { codeEngine.abort() } }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
