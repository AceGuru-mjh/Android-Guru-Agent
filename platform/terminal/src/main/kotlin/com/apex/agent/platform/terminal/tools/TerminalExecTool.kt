package com.apex.agent.platform.terminal.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.platform.terminal.TerminalManager
import kotlinx.serialization.json.*

class TerminalExecTool(
    private val manager: TerminalManager
) : AgentTool {

    override val id = "terminal_exec"
    override val name = "Execute in Terminal"
    override val description = """
        Execute a command in an existing terminal session and wait for completion.
        The session maintains state: working directory, env vars, shell history.
        
        Output is automatically cleaned (ANSI codes stripped).
        If output is very long, it will be truncated with a notice.
        
        For commands that produce continuous output (top, tail -f), use a short timeout
        then terminal_read to check output, and terminal_send with ctrl_c to stop.
        
        Examples:
        - {"session": 1, "command": "cd /sdcard && ls -la"}
        - {"session": 1, "command": "export FOO=bar && echo $FOO"}
        - {"session": 1, "command": "python3 script.py", "timeout": 60000}
        - {"session": 1, "command": "top -n 1", "timeout": 5000}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "session": {"type": "integer", "description": "Session ID"},
                "command": {"type": "string", "description": "Command to execute"},
                "timeout": {"type": "integer", "description": "Timeout in ms (default 30000)"},
                "max_output": {"type": "integer", "description": "Max output chars to return (default 3000)"}
            },
            "required": ["session", "command"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val sessionId = json["session"]?.jsonPrimitive?.intOrNull
            ?: return "❌ Error: 'session' parameter required"
        val command = json["command"]?.jsonPrimitive?.content
            ?: return "❌ Error: 'command' parameter required"
        val timeout = json["timeout"]?.jsonPrimitive?.longOrNull ?: 30000L
        val maxOutput = json["max_output"]?.jsonPrimitive?.intOrNull ?: 3000

        if (!manager.isAlive(sessionId)) {
            return "❌ Session $sessionId is dead. Create a new one with terminal_create."
        }

        val result = manager.execute(sessionId, command, timeout)

        buildString {
            // 状态行
            if (result.timedOut) {
                appendLine("⚠️ Command timed out after ${timeout}ms")
            }
            if (!result.sessionAlive) {
                appendLine("💀 Session terminated during execution")
            }

            // 输出
            val output = result.output
            if (output.length > maxOutput) {
                appendLine(output.take(maxOutput))
                appendLine()
                appendLine("[... truncated: showing $maxOutput/${output.length} chars]")
                appendLine("Use terminal_read(session=$sessionId) for remaining output")
            } else {
                append(output.ifBlank { "(no output)" })
            }

            // 元信息
            if (result.durationMs > 1000) {
                appendLine()
                appendLine("⏱ ${result.durationMs}ms")
            }
        }
    }
}
