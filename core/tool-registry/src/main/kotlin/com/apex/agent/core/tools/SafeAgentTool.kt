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
 * ## 错误前缀协议（与 v3 管线同源）
 *
 * 折叠串的机器可判前缀与 [EnhancedToolExecutor] 的
 * ioFailureResult / securityResult / crashResult 完全一致（英文），中文
 * 细节放尾注：RetryClassifier 的终止前缀表（"Error: permission denied" →
 * Terminal，不重试）与 FailureClassifier.PERMISSION_PATTERNS（contains
 * "permission denied"）都靠英文前缀命中 —— 旧版纯中文串会把权限拒绝
 * 误判成 Retryable，readOnlyHint 工具白白重放注定失败的调用。
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
 *
 * ## 环境前置门透传（P2 修复）
 *
 * [ToolEnvironmentGate] 通过 `tool as? EnvironmentAwareTool` 检测；本类
 * 旧实现未实现该接口 → 包装后门禁恒放行（fail-open），`input_text` 等
 * 工具的「先 ui_tap 聚焦输入框」引导提示全部失效，工具真跑失败后模型
 * 在更深的错误里打转。现在透传 delegate 的 [EnvironmentAwareTool.requiredEnv]。
 */
class SafeAgentTool(
    private val delegate: AgentTool
) : StreamingAgentTool, EnvironmentAwareTool {

    override val id: String get() = delegate.id
    override val name: String get() = delegate.name
    override val description: String get() = delegate.description
    override val parametersSchema: String get() = delegate.parametersSchema

    /** v2 元数据透传：包装层不丢失类别/风险/标签（engine 与 UI 依赖它）。 */
    override val metadata: ToolMetadata get() = delegate.metadata

    /** P2 修复：环境前置门透传 —— delegate 声明的硬前置不再被包装层吞掉。 */
    override val requiredEnv: List<String>
        get() = (delegate as? EnvironmentAwareTool)?.requiredEnv ?: emptyList()

    override suspend fun execute(arguments: String): String {
        return try {
            delegate.execute(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // 前缀与 EnhancedToolExecutor.securityResult 同源（见类 KDoc
            // 「错误前缀协议」），中文细节保尾注 —— 命中 RetryClassifier 终止前缀。
            "Error: permission denied: 权限不足，无法执行。${e.message ?: delegate.id}"
        } catch (e: IOException) {
            // 前缀与 EnhancedToolExecutor.ioFailureResult 同源；I/O 抖动属
            // Retryable（不进终止前缀表），中文细节保尾注。
            "Error: execution failed: I/O error in '${delegate.id}': ${e.message ?: e::class.simpleName}（权限不足或 I/O 失败）"
        } catch (e: Throwable) {
            // 前缀与 EnhancedToolExecutor.crashResult 同源。
            "Error: execution failed: 工具执行失败（${e::class.simpleName ?: "exception"}）: ${e.message ?: "no message"}"
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
            // 与 execute() 的折叠串逐字一致（错误前缀协议见类 KDoc）。
            emit(ToolStreamEvent.Error("Error: permission denied: 权限不足，无法执行。${e.message ?: delegate.id}"))
        } catch (e: IOException) {
            emit(ToolStreamEvent.Error("Error: execution failed: I/O error in '${delegate.id}': ${e.message ?: e::class.simpleName}（权限不足或 I/O 失败）"))
        } catch (e: Throwable) {
            emit(ToolStreamEvent.Error("Error: execution failed: 工具执行失败（${e::class.simpleName ?: "exception"}）: ${e.message ?: "no message"}"))
        }
    }
}
