package com.apex.agent.ui.screen.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.engine.*
import com.apex.agent.core.llm.ReasoningEffort
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

sealed interface AgentUiMessage {
    data class User(val text: String, val timestamp: Long = System.currentTimeMillis()) : AgentUiMessage
    data class Agent(val text: String, val timestamp: Long = System.currentTimeMillis()) : AgentUiMessage
    data class ToolCall(
        val toolName: String,
        val args: String,
        val output: String? = null,
        val success: Boolean? = null,
        val durationMs: Long = 0
    ) : AgentUiMessage
    data class System(val text: String) : AgentUiMessage
    data class PlanMessage(val plan: ExecutionPlan) : AgentUiMessage
    data class ThinkingMessage(val thought: String) : AgentUiMessage
}

data class AgentToolCallUi(
    val toolName: String,
    val args: String,
    val isRunning: Boolean = true
)

@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val agentEngine: AgentEngine,
    private val memory: ConversationMemory,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentChatUiState(historyDepth = memory.count()))
    val uiState: StateFlow<AgentChatUiState> = _uiState.asStateFlow()

    private var currentJob: Job? = null

    /**
     * 发送消息
     */
    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isLoading) return

        // 检查是否是斜杠指令
        if (text.startsWith("/")) {
            handleSlashCommand(text)
            return
        }

        _uiState.update { state ->
            state.copy(
                messages = state.messages + AgentUiMessage.User(text),
                isLoading = true,
                currentThinking = "",
                currentResponse = ""
            )
        }

        currentJob = viewModelScope.launch {
            agentEngine.execute(text).collect { event ->
                handleEvent(event)
            }
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

            // ═══ 工具调用 ═══
            is AgentEvent.ToolCallStart -> {
                _uiState.update { state ->
                    state.copy(
                        currentToolCall = AgentToolCallUi(
                            toolName = event.toolName,
                            args = event.arguments,
                            isRunning = true
                        )
                    )
                }
            }
            is AgentEvent.ToolCallComplete -> {
                _uiState.update { state ->
                    state.copy(
                        currentToolCall = null,
                        messages = state.messages + AgentUiMessage.ToolCall(
                            toolName = event.toolName,
                            args = "",
                            output = event.output.take(500),
                            success = event.success,
                            durationMs = event.durationMs
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
                        messages = state.messages + AgentUiMessage.System("❌ ${event.message}"),
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

    // ═══ 附件处理 ═══

    /**
     * 处理文件附件
     */
    fun attachFile(uri: Uri) {
        val fileName = getFileNameFromUri(uri) ?: "file_${System.currentTimeMillis()}"
        _uiState.update { s ->
            s.copy(messages = s.messages + AgentUiMessage.System("📎 已附加文件: $fileName"))
        }
    }

    /**
     * 处理图片附件
     */
    fun attachImage(uri: Uri) {
        val fileName = getFileNameFromUri(uri) ?: "image_${System.currentTimeMillis()}"
        _uiState.update { s ->
            s.copy(messages = s.messages + AgentUiMessage.System("🖼️ 已附加图片: $fileName"))
        }
    }

    // ═══ 斜杠指令处理 ═══

    /**
     * 处理斜杠指令
     */
    private fun handleSlashCommand(command: String) {
        val parts = command.split(":", limit = 2)
        val type = parts[0].removePrefix("/")
        val name = parts.getOrNull(1)?.trim() ?: ""

        val message = when (type) {
            "skill" -> "🧩 激活 Skill: $name"
            "mcp" -> "🔌 连接 MCP: $name"
            "connector" -> "🔗 使用连接器: $name"
            "plugin" -> "📦 调用插件: $name"
            else -> "未知指令: $command"
        }

        _uiState.update { s ->
            s.copy(messages = s.messages + AgentUiMessage.System(message))
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
        } catch (e: Exception) { null }
    }
}
