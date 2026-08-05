package com.apex.agent.platform.terminal.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.platform.terminal.TerminalManager
import kotlinx.serialization.json.*

class TerminalListTool(
    private val manager: TerminalManager
) : AgentTool {

    override val id = "terminal_list"
    override val name = "List Terminals"
    override val description = "List all active terminal sessions with their status."

    override val parametersSchema = """
        {"type": "object", "properties": {}, "required": []}
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val sessions = manager.listSessions()

        if (sessions.isEmpty()) {
            return "No active terminal sessions. Use terminal_create to start one."
        }

        return buildString {
            appendLine("Active terminal sessions (${sessions.size}):")
            appendLine("─".repeat(50))
            sessions.forEach { s ->
                val alive = manager.isAlive(s.id)
                val statusIcon = when {
                    !alive -> "💀"
                    s.state == com.apex.agent.platform.terminal.SessionState.RUNNING -> "⚡"
                    else -> "🟢"
                }
                appendLine("$statusIcon Session ${s.id}")
                appendLine("   Shell: ${s.shell}")
                appendLine("   Dir: ${s.workDir}")
                appendLine("   PID: ${s.pid}")
                appendLine("   Commands run: ${s.totalCommandsExecuted}")
                if (s.lastCommand.isNotBlank()) {
                    appendLine("   Last: ${s.lastCommand.take(60)}")
                }
                appendLine()
            }
        }
    }
}
