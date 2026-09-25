package com.apex.agent.vault

import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ═══ 密钥脱敏执行器装饰器（#167 纵深防御）═══
 *
 * 包装内层 [ToolExecutor]：[execute] 的返回串与 [executeStream] 的
 * 全部文本事件（[ToolStreamEvent.Output] / [Complete] / [Error]）
 * 一律经 [SecretRedactor.redact] 擦洗后才交给模型。
 *
 * 动机：金库密钥只应经 vault_paste 的三条通道「直投」出去；
 * 但任何工具都可能意外回显它（如 http 工具回显请求头、shell 输出
 * echo 出环境变量、子代理转述终端内容）。本装饰器保证——只要密钥
 * 已在 SecretRedactor 登记（= 当前在库），它出现在任何工具输出里的
 * 每一处都会被替换为 `«vault:***»`，**无论它来自哪个工具**。
 *
 * 注意：参数（arguments）**不**脱敏 —— vault_save 的 content 参数必须
 * 原样进库；而模型无法读回密钥，也就无法把它写进其他工具的参数。
 *
 * 脱敏幂等：重复包装 / 批量工具嵌套（tool_batch_run 内外两层执行器）
 * 时二次脱敏不改变结果。
 */
class SecretRedactingExecutor(
    private val delegate: ToolExecutor,
    private val redactor: SecretRedactor
) : ToolExecutor {

    override suspend fun execute(toolId: String, arguments: String): String =
        redactor.redact(delegate.execute(toolId, arguments))

    override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> =
        delegate.executeStream(toolId, arguments).map { event ->
            when (event) {
                is ToolStreamEvent.Output -> event.copy(chunk = redactor.redact(event.chunk))
                is ToolStreamEvent.Complete -> event.copy(output = redactor.redact(event.output))
                is ToolStreamEvent.Error -> event.copy(message = redactor.redact(event.message))
                // Progress 只有百分比与人类可读说明（不含工具输出），透传。
                is ToolStreamEvent.Progress -> event
            }
        }
}
