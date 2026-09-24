package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.CancellationException

/**
 * 工具调用流式执行（自 [ApexAgentEngine] 迁出的同包扩展；God-file 预算拆分，
 * 模式与 AgentChatEventApplier 相同）。
 *
 * v4.1 增补：**循环守卫**（rikkahub-agent LoopGuard 同款）——完全相同的
 * 工具+参数在第 3 次调用起被拦截，不执行，返回带修复指引的错误结果；
 * 防止"同参连点烧 token"（实测灾难样本：27 步 141K token 全是重复调用）。
 */
internal suspend fun ApexAgentEngine.executeToolCallStreaming(
    toolCall: ToolCall,
    emit: suspend (AgentEvent) -> Unit
) {
        // v4：模型回显的是 provider 安全名（terminal_exec）；执行器/截断策略
        // 需要注册表 id（terminal.exec）——经当前计划的反向映射解析。
        // 无映射时（旧会话回放/模型直呼 registry id）原样直查，两条路都通。
        val registryToolId = EngineToolPlanner.registryIdOf(currentToolPlan, toolCall.name)

        // ═══ v4.1 循环守卫：同工具+同参数第 3 次起拦截 ═══
        val fingerprint = "$registryToolId|${toolCall.arguments}"
        val repeated = loopGuard.record(fingerprint)
        if (repeated >= EngineLoopGuard.IDENTICAL_CALL_LIMIT) {
            val guardMessage = EngineLoopGuard.blockMessage(registryToolId, repeated)
            loopGuardTrips++
            emit(
                AgentEvent.ToolCallStart(
                    callId = toolCall.id,
                    toolName = toolCall.name,
                    arguments = toolCall.arguments
                )
            )
            emit(
                AgentEvent.ToolCallComplete(
                    callId = toolCall.id,
                    toolName = toolCall.name,
                    arguments = toolCall.arguments,
                    output = guardMessage,
                    fullOutput = guardMessage,
                    success = false,
                    durationMs = 0
                )
            )
            addMessage(LlmMessage.ToolResult(toolCall.id, guardMessage))
            return
        }

        emit(
            AgentEvent.ToolCallStart(
                callId = toolCall.id,
                toolName = toolCall.name,
                arguments = toolCall.arguments
            )
        )

        val toolStart = System.currentTimeMillis()
        val outputBuilder = StringBuilder()

        // 以流式事件信号为主判定成败：收到 ToolStreamEvent.Error 或捕获异常
        // 即视为失败。这样工具合法输出以 "Error" 开头（如 "Error: foo not found" 这类
        // 真实数据）也不会被误判为执行失败。
        var hadStreamError = false
        try {
            toolExecutor.executeStream(registryToolId, toolCall.arguments).collect { event ->
                when (event) {
                    is ToolStreamEvent.Output -> {
                        outputBuilder.append(event.chunk)
                        emit(
                            AgentEvent.ToolOutputChunk(
                                callId = toolCall.id,
                                chunk = event.chunk
                            )
                        )
                    }
                    is ToolStreamEvent.Progress -> {
                        emit(
                            AgentEvent.ToolProgress(
                                callId = toolCall.id,
                                percent = event.percent,
                                message = event.message
                            )
                        )
                    }
                    is ToolStreamEvent.Complete -> {
                        // 防御：仅当工具只发 Complete 没发 Output（非典型）时补发。
                        if (outputBuilder.isEmpty() && event.output.isNotEmpty()) {
                            outputBuilder.append(event.output)
                            emit(
                                AgentEvent.ToolOutputChunk(
                                    callId = toolCall.id,
                                    chunk = event.output
                                )
                            )
                        }
                    }
                    is ToolStreamEvent.Error -> {
                        hadStreamError = true
                        outputBuilder.append(event.message)
                        emit(
                            AgentEvent.ToolOutputChunk(
                                callId = toolCall.id,
                                chunk = event.message
                            )
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            hadStreamError = true
            outputBuilder.append("Error: ${e.message ?: "tool execution failed"}")
        }

        val duration = System.currentTimeMillis() - toolStart

        // P7 Layer 1: 工具输出截断（始终生效）
        val rawOutput = outputBuilder.toString()
        val truncationResult = toolTruncator.smartTruncate(rawOutput, registryToolId)
        val result = truncationResult.text

        // 成功判定：优先采用流式事件信号；仅当工具未发任何 Error 事件且
        // 异常分支未触发时，才回退到文本前缀检测（兼容只返回 "Error: ..." 文本
        // 而不发 Error 事件的旧工具）。
        val actionSuccess = !hadStreamError && !result.startsWith("Error")
        if (!actionSuccess) anyActionFailed = true

        emit(
            AgentEvent.ToolCallComplete(
                callId = toolCall.id,
                toolName = toolCall.name,
                arguments = toolCall.arguments,
                output = result.take(config.maxToolOutputLength),
                fullOutput = rawOutput.take(100_000),
                success = actionSuccess,
                durationMs = duration
            )
        )

        // 截断后的结果存入历史（节省后续 token）
        addMessage(
            LlmMessage.ToolResult(toolCall.id, result)
        )

        // 隐式记忆采集（报告 P2）：记录每个已执行动作及其成败。
        // 传入 actionSuccess 供 CS-Mem 蒸馏时过滤失败动作（避免"鼠标连点失败"
        // 也被压进 FSM 宏技能，使学到的宏技能必然无法回放）。
        memoryObserver?.onActionExecuted(
            "${toolCall.name}(${toolCall.arguments.take(120)})",
            success = actionSuccess
        )
    }

