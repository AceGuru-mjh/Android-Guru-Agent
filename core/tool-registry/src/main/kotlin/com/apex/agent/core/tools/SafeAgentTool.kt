package com.apex.agent.core.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * SafeAgentTool
 *
 * 包装任意 [AgentTool]，保证其 [execute] / [executeStream] 永远不会向调用方
 * 抛出业务异常。
 *
 * 设计原则：
 * - LLM 工具调用结果必须是字符串/事件，而不是异常。
 * - 异常属于基础设施故障，应转换为可恢复的 [ToolStreamEvent.Error] /
 *   `"Error:"` 字符串。
 * - [CancellationException] 必须继续抛出，否则会破坏协程取消语义（`abort()`
 *   会失效）。
 * - 错误字符串统一以 `"Error:"` 开头，方便 [ApexAgentEngine] 判断失败。
 *
 * ## 流式透传（关键）
 *
 * 本类实现 [StreamingAgentTool] 而非仅 [AgentTool]。原因：[DefaultToolExecutor]
 * 通过 `tool is StreamingAgentTool` 运行时检测来决定是否走流式路径。若
 * `SafeAgentTool` 只实现 `AgentTool`，那么即使被包装的 delegate 实现了
 * [StreamingAgentTool]，executor 也看不到 —— 流式能力会被这层包装静默吞掉。
 *
 * 因此本类显式实现 [StreamingAgentTool.executeStream]：
 * - delegate 是 [StreamingAgentTool]：透传其事件流（`emitAll`）。
 * - delegate 仅 [AgentTool]：调用 `delegate.execute(...)`，把结果包成单个
 *   [ToolStreamEvent.Output] + [ToolStreamEvent.Complete]（失败为 [ToolStreamEvent.Error]）。
 *
 * 异常处理与 [execute] 对称：CancellationException 重抛，其余转成
 * [ToolStreamEvent.Error]，保证收集方永远收到完整事件序列。
 */
class SafeAgentTool(
    private val delegate: AgentTool
) : StreamingAgentTool {

    override val id: String get() = delegate.id
    override val name: String get() = delegate.name
    override val description: String get() = delegate.description
    override val parametersSchema: String get() = delegate.parametersSchema

    override suspend fun execute(arguments: String): String {
        return try {
            delegate.execute(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            "Error: 权限不足，无法执行。${e.message ?: delegate.id}"
        } catch (e: IOException) {
            "Error: 权限不足或 I/O 失败。${e.message ?: delegate.id}"
        } catch (e: Throwable) {
            "Error: 工具执行失败。${e.message ?: e::class.simpleName}"
        }
    }

    override fun executeStream(arguments: String): Flow<ToolStreamEvent> = flow {
        try {
            if (delegate is StreamingAgentTool) {
                emitAll(delegate.executeStream(arguments))
            } else {
                val result = delegate.execute(arguments)
                if (result.startsWith("Error")) {
                    emit(ToolStreamEvent.Error(result))
                } else {
                    if (result.isNotEmpty()) {
                        emit(ToolStreamEvent.Output(result))
                    }
                    emit(ToolStreamEvent.Complete(result))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            emit(ToolStreamEvent.Error("Error: 权限不足，无法执行。${e.message ?: delegate.id}"))
        } catch (e: IOException) {
            emit(ToolStreamEvent.Error("Error: 权限不足或 I/O 失败。${e.message ?: delegate.id}"))
        } catch (e: Throwable) {
            emit(ToolStreamEvent.Error("Error: 工具执行失败。${e.message ?: e::class.simpleName}"))
        }
    }
}
