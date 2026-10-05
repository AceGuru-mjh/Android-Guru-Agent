package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.StreamingAgentTool
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shell命令执行工具（优化版）
 *
 * 特性：
 * - 输出长度控制（max_lines / max_chars）
 * - 自动截断 + 提示如何获取更多
 * - 工作目录记忆：命令以纯 `cd <dir>` 结尾且执行成功时，后续命令以新目录为
 *   起始目录（静态解析最后一个 cd 段；`cd -` / 变量路径不做记忆）
 * - 超时保护（timeout 参数真实生效：P1 修复 —— 旧实现 schema 声明了
 *   timeout 参数却从不读取，模型显式传 `"timeout": 120` 时仍在 30s
 *   默认值被杀，长命令（编译/安装/构建）全部误判失败，连环重试推高
 *   任务失败率。现在参数透传到权限通道，钳位 [MIN_TIMEOUT_SECONDS]..[MAX_TIMEOUT_SECONDS]）
 * - 错误输出分离
 *
 * 权限通道（自动选最高可用）：Root(`su -c`) > Shizuku(ADB 级 uid=2000) >
 * 普通shell(仅 app 沙箱)。命令真实执行，失败如实返回退出码与输出。
 */
class ShellExecuteTool(
    private val executor: suspend (command: String, timeoutSec: Int) -> String
) : StreamingAgentTool {

    override val id = "shell_execute"
    override val name = "Run Command"
    override val description = """
        Execute a shell command on the device.

        Output management:
        - Output is limited to max_lines (default 50) to avoid flooding
        - Use head/tail/grep/awk in your command for precise control
        - Check the metadata at the end for truncation info

        Tips:
        - Chain commands: "cd /path && ls && cat file.txt"
        - Filter output: "pm list packages | grep chrome"
        - Limit output: "find / -name '*.log' | head -20"
        - Get exit code: "command; echo EXIT_CODE=$?"

        Examples:
        - {"command": "ls -la /sdcard/Download"}
        - {"command": "pm list packages -3 | head -20", "max_lines": 25}
        - {"command": "df -h && free -m"}
        - {"command": "cat /proc/cpuinfo | grep 'model name' | head -4"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Shell command to execute"},
                "max_lines": {"type": "integer", "description": "Max output lines (default 50)"},
                "max_chars": {"type": "integer", "description": "Max output chars (default 3000)"},
                "timeout": {"type": "integer", "description": "Timeout in seconds, clamped to 5..600 (default 30). Long builds/installs should raise this"}
            },
            "required": ["command"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val command = json["command"]?.jsonPrimitive?.content
                ?: return "Error: 'command' required"
            val maxLines = json["max_lines"]?.jsonPrimitive?.intOrNull ?: 50
            val maxChars = json["max_chars"]?.jsonPrimitive?.intOrNull ?: 3000
            // P1 修复（timeout 死参数）：schema 承诺可调超时却从不读取 ——
            // 模型按自己的参数预期等待，实际 30s 必杀。现在真实透传，
            // 钳位到 [MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS]（与
            // terminal.exec 的 1s..600ms 口径对齐为秒级 5..600）。
            val timeoutSec = (json["timeout"]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_SECONDS)
                .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)

            if (command.isBlank()) return "Error: Empty command"

            var result = executor(command, timeoutSec)

            if (result.isBlank()) return "✅ Command completed (no output)"

            // 分离错误信息
            val isError = result.startsWith("Error")
            val prefix = if (isError) "❌ " else ""

            // 行数限制
            val lines = result.lines()
            val totalLines = lines.size
            val truncatedByLines = totalLines > maxLines

            var output = if (truncatedByLines) {
                lines.take(maxLines).joinToString("\n")
            } else {
                result
            }

            // 字符数限制
            val truncatedByChars = output.length > maxChars
            if (truncatedByChars) {
                output = output.take(maxChars)
            }

            buildString {
                append(prefix)
                append(output)

                // 截断提示
                if (truncatedByLines || truncatedByChars) {
                    appendLine()
                    appendLine()
                    appendLine("─".repeat(40))
                    append("⚠️ Output truncated")
                    if (truncatedByLines) append(" ($totalLines lines → $maxLines shown)")
                    if (truncatedByChars) append(" (${result.length} chars → $maxChars shown)")
                    appendLine()
                    appendLine("   Use | head -N, | tail -N, or | grep to filter.")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Must rethrow CancellationException — `executor` is a suspend call, and
            // kotlinx.coroutines.CancellationException is a subtype of Exception, so
            // the generic catch below would otherwise swallow abort() signals and
            // break SafeAgentTool's outer cancellation handling.
            throw e
        } catch (e: Exception) {
            "❌ Execution error: ${e.message}"
        }
    }

    /**
     * 流式入口：在同步执行前后补发 PROGRESS 阶段（命令开始 / 完成），
     * 让 UI 的工具步骤时间线能看到执行过程。executor 是黑盒闭包、无法逐行回流，
     * 因此只能给出"开始→完成"两阶段提示，而非逐行进度。
     */
    override fun executeStream(arguments: String): Flow<ToolStreamEvent> = flow {
        val cmd = runCatching {
            Json.parseToJsonElement(arguments).jsonObject["command"]?.jsonPrimitive?.content
        }.getOrNull()
        emit(ToolStreamEvent.Progress(percent = 0.2f, message = "执行命令: ${cmd ?: "(未知)"}"))
        val result = execute(arguments)
        emit(ToolStreamEvent.Progress(percent = 1f, message = "命令执行完成"))
        if (result.startsWith("Error") || result.startsWith("❌")) {
            emit(ToolStreamEvent.Error(result))
        } else {
            emit(ToolStreamEvent.Output(result))
            emit(ToolStreamEvent.Complete(result))
        }
    }

    companion object {
        /** 默认超时（秒）——与 schema 声明一致。 */
        const val DEFAULT_TIMEOUT_SECONDS = 30

        /** 超时下限（秒）：过短的超时连 spawn 都完不成，只产出噪声错误。 */
        const val MIN_TIMEOUT_SECONDS = 5

        /** 超时上限（秒）：与 terminal.exec 的 600s 上限对齐。 */
        const val MAX_TIMEOUT_SECONDS = 600
    }
}
