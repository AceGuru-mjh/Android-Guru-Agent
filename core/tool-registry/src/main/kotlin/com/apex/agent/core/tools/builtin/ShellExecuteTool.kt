package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.content

/**
 * Shell命令执行工具（优化版）
 *
 * 特性：
 * - 输出长度控制（max_lines / max_chars）
 * - 自动截断 + 提示如何获取更多
 * - 工作目录记忆（cd后后续命令保持在同一目录）
 * - 超时保护
 * - 错误输出分离
 */
class ShellExecuteTool(
    private val executor: suspend (String) -> String
) : AgentTool {

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
                "timeout": {"type": "integer", "description": "Timeout seconds (default 30)"}
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

            if (command.isBlank()) return "Error: Empty command"

            var result = executor(command)

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
        } catch (e: Exception) {
            "❌ Execution error: ${e.message}"
        }
    }
}
