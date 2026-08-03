package com.apex.agent.ui.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ChatMessage {
    data class User(val text: String) : ChatMessage
    data class Agent(val text: String) : ChatMessage
    data class ToolCall(
        val toolName: String,
        val args: String,
        val output: String? = null,
        val isRunning: Boolean = false
    ) : ChatMessage
    data class System(val text: String) : ChatMessage
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentEngine: AgentEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun sendMessage(text: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage.User(text),
                isLoading = true
            )
        }

        viewModelScope.launch {
            agentEngine.execute(text).collect { event ->
                when (event) {
                    is AgentEvent.Thinking -> {
                        // 可选：显示思考中
                    }
                    is AgentEvent.ToolCallStart -> {
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages + ChatMessage.ToolCall(
                                    toolName = event.toolName,
                                    args = event.argsSummary,
                                    isRunning = true
                                )
                            )
                        }
                    }
                    is AgentEvent.ToolCallResult -> {
                        _uiState.update { state ->
                            val msgs = state.messages.toMutableList()
                            val lastToolIdx = msgs.indexOfLast { 
                                it is ChatMessage.ToolCall && it.isRunning 
                            }
                            if (lastToolIdx >= 0) {
                                val tool = msgs[lastToolIdx] as ChatMessage.ToolCall
                                msgs[lastToolIdx] = tool.copy(
                                    output = event.output.take(500),
                                    isRunning = false
                                )
                            }
                            state.copy(messages = msgs)
                        }
                    }
                    is AgentEvent.TextResponse -> {
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages + ChatMessage.Agent(event.text),
                                isLoading = false
                            )
                        }
                    }
                    is AgentEvent.Error -> {
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages + ChatMessage.System(
                                    "❌ 错误: ${event.message}"
                                ),
                                isLoading = false
                            )
                        }
                    }
                    is AgentEvent.Complete -> {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
            }
        }
    }
}
