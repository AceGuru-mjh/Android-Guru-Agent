package com.apex.agent.ui.screen.code

import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.core.engine.AgentEvent
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # Code 事件归约（引擎事件 → UI 状态）
 *
 * God-file 预算拆分（模式同 AgentChatEventApplier.kt）：原 [CodeViewModel.reduce]
 * 成员整体迁出为同包扩展——事件归约只依赖 VM 已开放为 internal 的状态流/追踪器/
 * 落盘与错误出口，与 VM 的输入/生命周期/会话快照职责解耦。
 *
 * 归约口径（原语义不变）：
 * - ResponseChunk → 当前助手消息增量 append（编码回复长度可控，不做 16ms 节流）；
 * - ToolCallStart/Complete → 工具卡（code_edit/write 成功后编辑器跟随最新现场）；
 * - UserInputRequired/UserInputExpired → 挂起/关闭结构化提问输入框；
 * - Plan 确认 / 压缩 / Complete → 系统消息 + todo/仪表刷新；
 * - Error → 错误条（#209：recoverable 透传为「重试」入口）。
 */
internal fun CodeViewModel.reduce(event: AgentEvent) {
    when (event) {
        is AgentEvent.IterationStart -> {
            _uiState.update { it.copy(currentIteration = event.iteration) }
            // AUTO 可解释性已前移到发送前预检（resolveRuntimeThinkingLevel
            // 产生 adaptiveDecision + 系统消息）；引擎侧从不接收 AUTO，
            // 此处不再拉取引擎决策。
        }

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
            // v1.2 长任务追踪：todo 变化即刷新追踪器快照（工具完成后是
            // code_todo 改写的主要时点）
            longTaskTracker.noteTodos(renderTodosForTracker(codeTodoTool.snapshot()))
            // #154：编辑/写成功的文件自动成为「当前文件」（编辑器跟随最新现场）
            if (event.success && (event.toolName == "code_edit" || event.toolName == "code_write")) {
                extractToolPath(event.arguments)?.let { openEditorFile(it) }
            }
            scheduleSessionPersist()
        }

        is AgentEvent.UserInputRequired -> _uiState.update {
            it.copy(pendingQuestion = event.prompt)
        }

        // #214 输入等待超时：关闭挂起的提问输入框（引擎已按未决继续；
        // 时间轴留痕由 CodeStreamSession 的 UserInputExpired 分支负责）
        is AgentEvent.UserInputExpired -> _uiState.update {
            it.copy(pendingQuestion = null)
        }

        // ═══ #197 PLAN 模式事件（Coding 屏 Build/Plan 双档）═══
        is AgentEvent.PlanGenerated -> _uiState.update {
            it.copy(plan = event.plan)
        }

        is AgentEvent.PlanAwaitingConfirmation -> _uiState.update {
            it.copy(plan = event.plan, awaitingPlanConfirmation = true)
        }

        is AgentEvent.PlanConfirmed -> _uiState.update {
            it.copy(
                awaitingPlanConfirmation = false,
                messages = it.messages + CodeChatMessage(
                    id = idGen.incrementAndGet(),
                    role = CodeChatMessage.Role.SYSTEM,
                    text = "计划已确认，开始执行（${event.plan.steps.size} 步）"
                )
            )
        }

        is AgentEvent.Error -> {
            // #209/#216：文案由引擎侧给中文可读版本；recoverable 决定错误条
            // 是否提供「重试」（与 Agent 屏 ErrorBlock canRetry 口径一致）。
            showError(event.message, retriable = event.recoverable)
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

/** todo → 可渲染行（追踪器快照用：「☑ 文本」）。 */
private fun renderTodosForTracker(todos: List<CodeTodoTool.Todo>): List<String> = todos.map { todo ->
    val mark = when (todo.status) {
        "completed" -> "☑"
        "in_progress" -> "◐"
        "cancelled" -> "✕"
        else -> "☐"
    }
    "$mark ${todo.content}"
}

/** 从工具调用参数 JSON 里提取 path 字段（code_edit/code_write 的文件跟随）。 */
private fun extractToolPath(arguments: String): String? = runCatching {
    Json.parseToJsonElement(arguments).jsonObject["path"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() }
