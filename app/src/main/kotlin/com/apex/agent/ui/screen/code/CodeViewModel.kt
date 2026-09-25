package com.apex.agent.ui.screen.code

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.code.CodeAgentEngine
import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.InputType
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.code.ws.CodeWorkspace
import com.apex.agent.platform.code.ws.CodeWorkspaceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 */
@HiltViewModel
class CodeViewModel @Inject constructor(
    @Named("code") private val codeEngine: AgentEngine,
    private val workspaceManager: CodeWorkspaceManager,
    private val codeTodoTool: CodeTodoTool
) : ViewModel() {

    private val _uiState = MutableStateFlow(CodeUiState())
    val uiState: StateFlow<CodeUiState> = _uiState.asStateFlow()

    private val idGen = AtomicLong(0)
    private var runJob: Job? = null

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

        _uiState.update { it.copy(messages = it.messages + CodeChatMessage(idGen.incrementAndGet(), CodeChatMessage.Role.USER, trimmed)) }

        codeEngineImpl?.prepareForTask()

        runJob = viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true, error = null) }
            try {
                codeEngine.execute(UserInput(text = trimmed)).collect { event ->
                    reduce(event)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
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

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearConversation() {
        if (_uiState.value.isRunning) return
        codeEngineImpl?.clearConversation()
        codeTodoTool.clear()
        _uiState.update { it.copy(messages = emptyList(), todos = emptyList(), contextUsedTokens = 0) }
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
        refreshWorkspaces()
        val active = workspaceManager.activeWorkspace()
        if (active != null) bindWorkspace(active) else clearConversation()
    }

    private fun bindWorkspace(ws: CodeWorkspace) {
        codeEngineImpl?.setActiveWorkspace(
            workspaceId = ws.workspaceId,
            name = ws.name,
            root = File(ws.hostRootPath)
        )
        _uiState.update {
            it.copy(
                activeWorkspace = ws,
                workspaces = workspaceManager.list(),
                contextUsedTokens = codeEngineImpl?.currentTokenCount() ?: 0,
                contextMaxTokens = codeEngineImpl?.maxContextTokens() ?: 0
            )
        }
        if (_uiState.value.messages.none { m -> m.role == CodeChatMessage.Role.SYSTEM }) {
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

            is AgentEvent.ResponseComplete -> _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { m ->
                        if (m.isStreaming) m.copy(isStreaming = false) else m
                    }
                )
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

            is AgentEvent.ToolCallComplete -> _uiState.update { state ->
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

            is AgentEvent.UserInputRequired -> _uiState.update {
                it.copy(pendingQuestion = event.prompt)
            }

            is AgentEvent.Error -> _uiState.update {
                it.copy(error = event.message)
            }

            is AgentEvent.ContextCompressed -> _uiState.update {
                it.copy(
                    contextUsedTokens = event.afterTokens,
                    messages = it.messages + CodeChatMessage(
                        id = idGen.incrementAndGet(),
                        role = CodeChatMessage.Role.SYSTEM,
                        text = "上下文已压缩（${event.beforeTokens} → ${event.afterTokens} tokens，${event.strategy}）"
                    )
                )
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
            }

            is AgentEvent.Aborted -> _uiState.update { it.copy(isRunning = false) }

            // Plan/Spec/Reflection/Step 事件在 CODING(BUILD) 循环不触发，保持完备即可
            else -> Unit
        }
    }

    override fun onCleared() {
        runJob?.cancel()
        super.onCleared()
    }
}
