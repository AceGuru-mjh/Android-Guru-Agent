package com.apex.agent.core.tools

import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * SafeAgentTool
 *
 * 包装任意 AgentTool，保证其 execute() 永远不会向调用方抛出异常。
 *
 * 设计原则：
 * - LLM 工具调用结果必须是字符串，而不是异常。
 * - 异常属于基础设施故障，应转换为可恢复错误信息。
 * - CancellationException 必须继续抛出，否则会破坏协程取消语义。
 * - 错误字符串统一以 "Error:" 开头，方便 ApexAgentEngine 判断失败。
 */
class SafeAgentTool(
    private val delegate: AgentTool
) : AgentTool by delegate {

    override suspend fun execute(arguments: String): String {
        return try {
            delegate.execute(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            "Error: 权限不足，无法执行。${e.message ?: delegate.id}"
        } catch (e: IOException) {
            "Error: 权限不足或 I/O 失败，无法执行。${e.message ?: delegate.id}"
        } catch (e: Throwable) {
            "Error: 工具执行失败。${e.message ?: e::class.simpleName}"
        }
    }
}
