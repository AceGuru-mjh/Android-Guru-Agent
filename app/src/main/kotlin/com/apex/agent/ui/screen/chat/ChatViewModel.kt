package com.apex.agent.ui.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.engine.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Chat UI状态
 */
data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isLoading: Boolean = false,
    val currentThinking: String = "",       // 当前思考内容（流式）
    val currentResponse: String = "",       // 当前回复内容（流式）
    val currentToolCall: ToolCallUi? = null, // 当前执行的工具
    val mode: AgentMode = AgentMode.BUILD,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.STANDARD,
    val plan: ExecutionPlan? = null,         // Plan模式的计划
    val awaitingPlanConfirmation: Boolean = false
)

sealed interface UiMessage {
    data class User(val text: String, val timestamp: Long = System.currentTimeMillis()) : UiMessage
    data class Agent(val text: String, val timestamp: Long = System.currentTimeMillis()) : UiMessage
    data class ToolCall(
        val toolName: String,
        val args: String,
        val output: String? = null,
        val success: Boolean? = null,
        val durationMs: Long = 0
    ) : UiMessage
    data class System(val text: String) : UiMessage
    data class PlanMessage(val plan: ExecutionPlan) : UiMessage
    data class ThinkingMessage(val thought: String) : UiMessage
}

data class ToolCallUi(
    val toolName: String,
    val args: String,
    val isRunning: Boolean = true
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentEngine: AgentEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    private var currentJob: Job? = null

    /**
     * 发送消息
     */
    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isLoading) return
        
        _uiState.update { state ->
            state.copy(
                messages = state.messages + UiMessage.User(text),
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
    
    /**
     * 处理Agent事件，更新UI状态
     */
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
                        messages = state.messages + UiMessage.ThinkingMessage(event.fullThought),
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
                        messages = state.messages + UiMessage.PlanMessage(event.plan)
                    )
                }
            }
            
            // ═══ 工具调用 ═══
            is AgentEvent.ToolCallStart -> {
                _uiState.update { state ->
                    state.copy(
                        currentToolCall = ToolCallUi(
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
                        messages = state.messages + UiMessage.ToolCall(
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
                        messages = state.messages + UiMessage.Agent(event.fullText),
                        currentResponse = "",
                        isLoading = false
                    )
                }
            }
            
            // ═══ 压缩 ═══
            is AgentEvent.ContextCompressed -> {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + UiMessage.System(
                            "📦 Context compressed: ${event.beforeTokens}→${event.afterTokens} tokens"
                        )
                    )
                }
            }
            
            // ═══ 错误/完成 ═══
            is AgentEvent.Error -> {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + UiMessage.System("❌ ${event.message}"),
                        isLoading = false
                    )
                }
            }
            is AgentEvent.Complete -> {
                _uiState.update { it.copy(isLoading = false) }
            }
            is AgentEvent.Aborted -> {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + UiMessage.System("⏹ 已中止"),
                        isLoading = false
                    )
                }
            }
            
            else -> {} // 其他事件暂不处理
        }
    }
    
    /**
     * 切换模式
     */
    fun setMode(mode: AgentMode) {
        _uiState.update { it.copy(mode = mode) }
        // 更新引擎配置
        (agentEngine as? ApexAgentEngine)?.updateConfig(
            AgentConfig(mode = mode, thinkingLevel = _uiState.value.thinkingLevel)
        )
    }
    
    /**
     * 设置思考深度
     */
    fun setThinkingLevel(level: ThinkingLevel) {
        _uiState.update { it.copy(thinkingLevel = level) }
        (agentEngine as? ApexAgentEngine)?.updateConfig(
            AgentConfig(mode = _uiState.value.mode, thinkingLevel = level)
        )
    }
    
    /**
     * 确认计划
     */
    fun confirmPlan(confirmed: Boolean) {
        _uiState.update { it.copy(awaitingPlanConfirmation = false) }
        // TODO: 通知引擎
    }
    
    /**
     * 中止当前执行
     */
    fun abort() {
        currentJob?.cancel()
        viewModelScope.launch { agentEngine.abort() }
    }
}
