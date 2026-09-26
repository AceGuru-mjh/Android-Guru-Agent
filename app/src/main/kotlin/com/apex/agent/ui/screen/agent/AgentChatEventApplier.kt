package com.apex.agent.ui.screen.agent

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.ApexAgentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 引擎事件 → UI 状态的同步器（God-file 预算拆分，模式同 AgentChatHistoryController.kt）。
 *
 * 原 [AgentChatViewModel.handleEvent] 成员整体迁出为同包扩展：事件归约只依赖
 * VM 已开放为 internal 的流式缓冲/步骤流/横幅状态，与 VM 的输入/生命周期职责解耦。
 *
 * P0 修复（主界面卡死）背景：本函数运行在 viewModelScope（Main.immediate）收集链上，
 * 两处重活必须下沉：
 *  - [AgentEvent.ToolCallComplete]：对可能数百 KB 的终端/read_file 输出跑 ANSI 正则；
 *  - [AgentEvent.Complete]：historyCount() 触发对话史全量 JSON 解析 +
 *    currentTokenCount() 全历史逐字符估算 —— 历史一长每次收尾都在主线程忙
 *    数百毫秒到秒级，触摸与 IME 请求排队无法处理（体感“卡死/点输入框没反应”）。
 *
 * 因此保持 suspend：仅这两处重计算 withContext(Dispatchers.Default)，事件顺序仍由
 * collect 串行保证；轻量事件（chunk 追加等）保持在主线程零切换。
 */
internal suspend fun AgentChatViewModel.handleEvent(event: AgentEvent) {
    when (event) {
        // ═══ 思考 ═══
        is AgentEvent.ThinkingStart -> {
            // 新一轮思考：清掉上一轮可能残留的缓冲（防串轮），并开始计时（秒数显示）。
            streamBuffers.clearThinking()
            thinkingStartElapsed = SystemClock.elapsedRealtime()
            _uiState.update { it.copy(currentThinking = "", currentThinkingStartElapsed = thinkingStartElapsed) }
        }
        is AgentEvent.ThinkingChunk -> {
            streamBuffers.appendThinking(event.text)
        }
        is AgentEvent.ThinkingComplete -> {
            // 最终 flush：把仍在缓冲中的思考文本刷入 UI 后再收尾；实测耗时落盘秒数。
            streamBuffers.flush()
            val durationMs = if (thinkingStartElapsed > 0) {
                SystemClock.elapsedRealtime() - thinkingStartElapsed
            } else 0L
            thinkingStartElapsed = 0
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + AgentUiMessage.ThinkingMessage(event.fullThought, durationMs),
                    currentThinking = "",
                    currentThinkingStartElapsed = 0
                )
            }
        }

        // ═══ #168 六档思考：AUTO 档逐轮拉取引擎侧自适应选档理由 ═══
        is AgentEvent.IterationStart -> {
            // 引擎在 emit IterationStart 前已解析本轮档位，此处拉取即最新决策；
            // 非 AUTO 档引擎返回 null → 覆盖旧值，UI 不再显示过期理由。
            _lastAdaptiveDecision.value = (agentEngine as? ApexAgentEngine)?.currentThinkingDecision()
        }

        // ═══ Plan模式 ═══
        is AgentEvent.PlanGenerated -> {
            _uiState.update { it.copy(plan = event.plan) }
        }
        is AgentEvent.PlanAwaitingConfirmation -> {
            _uiState.update { it.copy(awaitingPlanConfirmation = true) }
        }
        is AgentEvent.UserInputRequired -> {
            // #168 HUMAN_ASSIST 决策点拦截：把已流出的草稿（方案对比文本）先落为
            // 一条独立 Agent 消息（用户需要看到模型摆出的选项才能决策），并清空
            // currentResponse——避免拦截后的下一轮回答与草稿拼接成一条消息。
            // 选择菜单由 pendingUserInput 驱动（UserInputDialog，CHOICE 类型
            // 按编号行渲染单选卡，与 HumanAssistFlow.formatQuestion 的编码对齐）。
            streamBuffers.flush()
            val draft = _uiState.value.currentResponse
            _uiState.update { state ->
                state.copy(
                    messages = if (draft.isNotBlank()) {
                        state.messages + AgentUiMessage.Agent(draft)
                    } else {
                        state.messages
                    },
                    currentResponse = "",
                    pendingUserInput = UserInputRequest(event.prompt, event.type)
                )
            }
        }
        is AgentEvent.PlanConfirmed -> {
            _uiState.update { state ->
                state.copy(
                    awaitingPlanConfirmation = false,
                    // #169：锁定后的计划（已应用用户勾选/重排 + 拓扑排序）——
                    // uiState.plan 同步为锁定版，锁定卡（PlanMessage）只读展示。
                    planConfirmed = true,
                    plan = event.plan,
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
            streamBuffers.flush()
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
                    text = strFmt(R.string.chat_tool_step_start, event.toolName, event.arguments),
                    seq = nextStepSeq()
                )
            )

            val (kind, server) = classifyTool(event.toolName, event.arguments, routeContextKind,
                metadata = toolRegistry.metadataOf(event.toolName))
            val skill = if (kind == ToolKind.SKILL) routeContextName else null

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

            toolOutputBuffer.append(stripAnsi(event.chunk))

            // 16ms 内的多个 chunk 合并为一次 UI 更新（≈1 帧节流）。
            if (toolFlushJob == null) {
                toolFlushJob = viewModelScope.launch {
                    delay(AgentChatViewModel.FLUSH_INTERVAL_MS)
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
                val msg = event.message
                    ?: strFmt(R.string.chat_progress_percent, ((event.percent ?: 0f) * 100).toInt())
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

            // P0（卡死修复）：完整输出可能数百 KB，ANSI 正则清洗下沉 Default，
            // 主线程只接收结果。collect 串行 → 事件顺序不变。
            val (cleanOutput, cleanFullOutput) = withContext(Dispatchers.Default) {
                stripAnsi(event.output) to stripAnsi(event.fullOutput.ifBlank { event.output })
            }
            val (kind, server) = classifyTool(event.toolName, event.arguments, routeContextKind,
                metadata = toolRegistry.metadataOf(event.toolName))
            val skill = if (kind == ToolKind.SKILL) routeContextName else null

            // 最终过程流：丢弃"活输出"步骤（其快照与完整输出重复），仅保留
            // START / PROGRESS 等结构性步骤 + 收尾步；输出统一去 ANSI 转义序列。
            // 收尾步文本只保留尾部摘要（与活输出步 MAX_LIVE_TOOL_OUTPUT_CHARS 同款口径）——
            // 完整输出已由 message.output/fullOutput 承载，步内再塞全量会把
            // 数百 KB 文本重复渲染进时间线（主线程字符串拼接 + 布局洪峰）。
            val stepOutputDigest = cleanOutput.takeLast(AgentToolCallUi.MAX_LIVE_TOOL_OUTPUT_CHARS)
            val finalSteps = ((currentToolCallSteps ?: emptyList())
                .filter { it.id != liveOutputStepId } + ToolStep(
                phase = if (event.success) StepPhase.COMPLETE else StepPhase.ERROR,
                text = if (event.success)
                    strFmt(R.string.chat_tool_step_done, event.durationMs, stepOutputDigest)
                else
                    strFmt(R.string.chat_tool_step_failed, event.durationMs, stepOutputDigest),
                seq = nextStepSeq()
            )).takeLast(AgentToolCallUi.MAX_LIVE_TOOL_STEPS)
            liveOutputStepId = null

            _uiState.update { state ->
                state.copy(
                    currentToolCall = null,
                    messages = state.messages + AgentUiMessage.ToolCall(
                        toolName = event.toolName,
                        args = event.arguments,
                        output = cleanOutput,
                        fullOutput = cleanFullOutput,
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
            streamBuffers.flush()
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
            streamBuffers.appendResponse(event.text)
        }
        is AgentEvent.ResponseComplete -> {
            // 最终 flush：把仍在缓冲中的回复文本刷入 UI 后再落为完整消息。
            streamBuffers.flush()
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + AgentUiMessage.Agent(event.fullText),
                    currentResponse = "",
                    isLoading = false
                )
            }
        }

        // ═══ Plan 模式：步骤开始（流水线分隔卡，长任务进度可视化）═══
        // #169：currentStepIndex 同步驱动锁定计划卡（PlanCard）的当前步高亮。
        is AgentEvent.StepStart -> {
            streamBuffers.flush()
            _uiState.update { state ->
                state.copy(
                    currentStepIndex = event.stepIndex,
                    messages = state.messages + AgentUiMessage.StepMarker(
                        stepIndex = event.stepIndex,
                        description = event.description
                    )
                )
            }
        }

        // ═══ 压缩 ═══
        is AgentEvent.ContextCompressed -> {
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + AgentUiMessage.System(
                        strFmt(
                            R.string.chat_vm_context_compressed,
                            event.beforeTokens,
                            event.afterTokens,
                            event.strategy,
                            event.messagesRemoved,
                            if (event.messagesTruncated > 0)
                                strFmt(R.string.chat_vm_truncated_suffix, event.messagesTruncated)
                            else ""
                        )
                    )
                )
            }
        }

        // ═══ 错误/完成 ═══
        is AgentEvent.Error -> {
            // 出错时把已流式输出的部分回复落为 isPartial 消息，避免流式气泡悬挂；
            // 思考计时同步收尾（避免残留 live 计时器）。
            streamBuffers.flush()
            finishActiveBanner()
            thinkingStartElapsed = 0
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
                    currentThinkingStartElapsed = 0,
                    currentStepIndex = -1,
                    isLoading = false
                )
            }
        }
        is AgentEvent.Complete -> {
            // 本轮任务收尾：展示运行总结卡（旧实现直接丢弃了 Complete 事件的信息）。
            finishActiveBanner()
            // P0（卡死修复）：historyCount()=全量 JSON 解析、currentTokenCount()=全历史
            // 逐字符估算 —— 历史越长收尾越重（数百 ms～秒级），必须离开主线程。
            // 仪表盘字段与消息列表解耦：总结卡先落，仪表稍后追平（毫秒级延迟无感）。
            val metrics = withContext(Dispatchers.Default) {
                val engine = agentEngine as? ApexAgentEngine
                Triple(
                    engine?.historyCount(),
                    engine?.currentTokenCount(),
                    engine?.maxContextTokens()
                )
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    currentStepIndex = -1,
                    messages = it.messages + AgentUiMessage.RunSummary(
                        summary = event.summary,
                        totalIterations = event.totalIterations,
                        totalToolCalls = event.totalToolCalls,
                        totalDurationMs = event.totalDurationMs
                    ),
                    historyDepth = metrics.first ?: it.historyDepth,
                    contextUsedTokens = metrics.second ?: it.contextUsedTokens,
                    contextMaxTokens = metrics.third ?: it.contextMaxTokens
                )
            }
        }
        is AgentEvent.Aborted -> {
            finishActiveBanner()
            thinkingStartElapsed = 0
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + AgentUiMessage.System(str(R.string.chat_aborted)),
                    isLoading = false,
                    currentThinking = "",
                    currentThinkingStartElapsed = 0,
                    currentStepIndex = -1
                )
            }
        }

        else -> {}
    }
}
