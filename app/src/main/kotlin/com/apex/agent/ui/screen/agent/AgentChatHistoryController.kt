package com.apex.agent.ui.screen.agent

import androidx.lifecycle.viewModelScope
import com.apex.agent.core.engine.ApexAgentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// 历史对话 —— AgentChatViewModel 的 internal 扩展（God-file 预算拆分，
// 模式同 AgentChatQuestionHandler.kt：调用点无感知，依赖的成员已开放 internal）。
//
// 归档策略：
//  - uiState 消息流 800ms 防抖快照落盘（ChatHistoryManager）；
//  - 首条消息产生时分配 sessionId，之后同一会话 upsert；
//  - newChat() 前 flushChatHistoryNow() 同步冲刷 + 复位 sessionId；
//  - 恢复会话 = 消息回填 UI + user/agent 文本对回填引擎上下文（详见
//    ChatHistoryModels.kt 文件头「设计取舍」）。
// ─────────────────────────────────────────────────────────────────────────────

/** 安装历史会话自动归档（VM init 调用一次，幂等）。 */
internal fun AgentChatViewModel.installChatHistoryAutoPersist() {
    // 会话列表初始加载（IO 读盘）
    viewModelScope.launch(Dispatchers.IO) {
        _chatSessions.value = chatHistory.loadSessions()
    }
    // 消息流防抖归档：collect 时捕获当时的 sessionId —— 800ms 内切了新会话，
    // 旧快照仍归档进旧会话，不会错挂到新会话头上。
    viewModelScope.launch {
        uiState.collect { state ->
            historyPersistJob?.cancel()
            val sessionIdAtCollect = currentHistorySessionId
            historyPersistJob = launch {
                delay(PERSIST_DEBOUNCE_MS)
                persistChatHistorySnapshot(state, sessionIdAtCollect)
            }
        }
    }
}

/**
 * 把当前消息快照归档进历史。
 *
 * @param presetSessionId collect 时捕获的会话 id（null = 当时是新会话）。
 *   执行时 currentHistorySessionId 已非 null（如恢复会话）则沿用现值，
 *   避免同一会话被拆成两条。
 */
internal fun AgentChatViewModel.persistChatHistorySnapshot(
    state: AgentChatUiState,
    presetSessionId: String?
) {
    val historyMessages = state.messages.mapNotNull { it.toHistoryMessage() }
    if (historyMessages.isEmpty()) return

    val sessionId = when {
        // presetSessionId 是函数参数（val）：smart cast 到 String 成立
        presetSessionId != null -> presetSessionId
        // currentHistorySessionId 是跨类可变属性：无法 smart cast，用 ?: 兜底建新档
        else -> currentHistorySessionId ?: UUID.randomUUID().toString()
            .also { currentHistorySessionId = it }
    }
    val createdAt = currentHistorySessionCreatedAt ?: System.currentTimeMillis()
    if (currentHistorySessionCreatedAt == null) currentHistorySessionCreatedAt = createdAt

    // 模型标记（展示用）：当前选中档案的 modelId / 名称
    val modelLabel = profiles.value.firstOrNull { it.id == currentProfileId.value }
        ?.let { p -> p.modelId.ifBlank { p.name } } ?: ""

    val summary = ChatSessionSummary(
        id = sessionId,
        title = historyMessages.historyTitle(),
        createdAt = createdAt,
        updatedAt = System.currentTimeMillis(),
        messageCount = historyMessages.size,
        modelId = modelLabel
    )
    viewModelScope.launch(Dispatchers.IO) {
        chatHistory.saveSession(summary, historyMessages)
        _chatSessions.value = chatHistory.loadSessions()
    }
}

/**
 * 立即归档当前会话并复位会话 id（newChat / 恢复其它会话前调用）。
 * 取消未触发的防抖任务，保证「最后一次编辑不丢」且不与下一次归档竞争。
 */
internal fun AgentChatViewModel.flushChatHistoryNow() {
    historyPersistJob?.cancel()
    historyPersistJob = null
    val state = _uiState.value
    if (state.messages.isNotEmpty()) {
        persistChatHistorySnapshot(state, currentHistorySessionId)
    }
    currentHistorySessionId = null
    currentHistorySessionCreatedAt = null
}

/**
 * 恢复历史会话：消息回填消息流，user/agent 文本对回填引擎上下文，
 * 后续对话自然接续该会话的历史。
 */
internal fun AgentChatViewModel.restoreChatSession(sessionId: String) {
    // 混沌审查修复：与 sendMessage 对齐，覆盖前取消旧收集器，防双消费者交错写 _uiState
    currentJob?.cancel()
    currentJob = viewModelScope.launch {
        val (messages, createdAt) = withContext(Dispatchers.IO) {
            chatHistory.loadMessages(sessionId) to chatHistory.sessionCreatedAt(sessionId)
        }
        if (messages.isEmpty()) return@launch

        // 引擎上下文恢复（内存 history + 持久化 memory 同步替换）
        val llmMessages = messages.mapNotNull { it.toLlmMessage() }
        (agentEngine as? ApexAgentEngine)?.restoreHistory(llmMessages)

        // 当前会话若有未归档内容，先落盘再切换（防丢）
        flushChatHistoryNow()
        resetStreamingState()

        _uiState.update {
            it.copy(
                messages = messages.mapNotNull { m -> m.toUiMessage() },
                currentThinking = "",
                currentResponse = "",
                currentToolCall = null,
                plan = null,
                awaitingPlanConfirmation = false,
                spec = null,
                awaitingSpecConfirmation = false,
                pendingUserInput = null,
                isLoading = false,
                historyDepth = llmMessages.size
            )
        }
        currentHistorySessionId = sessionId
        currentHistorySessionCreatedAt = createdAt ?: System.currentTimeMillis()
    }
}

/**
 * 新开会话：当前会话先归档进历史（含取消未触发的防抖归档任务），
 * 再清空引擎历史 / 持久化记忆 / UI 消息流与全部流式运行态。
 * （自 AgentChatViewModel.kt 迁入 —— 行数预算拆分；调用点 viewModel.newChat() 无感知。）
 */
internal fun AgentChatViewModel.newChat() {
    currentJob?.cancel()
    // 当前会话先归档进历史（含取消未触发的防抖归档），再复位会话 id
    flushChatHistoryNow()
    // 清空所有流式/工具运行态，防止残留缓冲串入新会话。
    resetStreamingState()
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

/** 删除单个历史会话（正在聊的那条也允许删：删除后当前会话脱离历史索引）。 */
internal fun AgentChatViewModel.deleteChatSession(sessionId: String) {
    if (currentHistorySessionId == sessionId) {
        currentHistorySessionId = null
        currentHistorySessionCreatedAt = null
    }
    viewModelScope.launch(Dispatchers.IO) {
        chatHistory.deleteSession(sessionId)
        _chatSessions.value = chatHistory.loadSessions()
    }
}

/** 清空全部历史会话（当前会话同时脱离历史索引）。 */
internal fun AgentChatViewModel.clearAllChatSessions() {
    currentHistorySessionId = null
    currentHistorySessionCreatedAt = null
    viewModelScope.launch(Dispatchers.IO) {
        chatHistory.clearAll()
        _chatSessions.value = chatHistory.loadSessions()
    }
}

/** 清空流式 / 工具运行态（newChat 与恢复会话共用，防残留缓冲串会话）。 */
internal fun AgentChatViewModel.resetStreamingState() {
    streamBuffers.reset()
    toolFlushJob?.cancel()
    toolFlushJob = null
    toolOutputBuffer.clear()
    activeToolCallId = null
    liveOutputStepId = null
    currentToolCallSteps = null
    activeBannerId = null
    routeContextKind = null
    routeContextName = null
    // 思考计时器同步归零（防会话切换后 live 秒数残留）。
    thinkingStartElapsed = 0
    _uiState.update { it.copy(currentThinkingStartElapsed = 0) }
}

private const val PERSIST_DEBOUNCE_MS = 800L
