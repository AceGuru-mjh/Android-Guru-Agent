package com.apex.agent.platform.terminal.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.platform.terminal.TerminalManager
import kotlinx.serialization.json.*

class TerminalReadTool(
    private val manager: TerminalManager
) : AgentTool {

    override val id = "terminal_read"
    override val name = "Read Terminal"
    override val description = """
        Read current output from a terminal session without sending input.
        Use to check what's on screen after a long-running command or interactive program.

        Examples:
        - {"session": 1}
        - {"session": 1, "max_chars": 5000}
        - {"session": 1, "wait": true, "wait_timeout": 3000}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "session": {"type": "integer", "description": "Session ID"},
                "max_chars": {"type": "integer", "description": "Max chars to return (default 3000)"},
                "wait": {"type": "boolean", "description": "Wait for new data before reading"},
                "wait_timeout": {"type": "integer", "description": "Wait timeout in ms (default 3000)"}
            },
            "required": ["session"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val sessionId = json["session"]?.jsonPrimitive?.intOrNull
            ?: return "❌ Error: 'session' required"
        val maxChars = json["max_chars"]?.jsonPrimitive?.intOrNull ?: 3000
        val wait = json["wait"]?.jsonPrimitive?.booleanOrNull ?: false
        val waitTimeout = json["wait_timeout"]?.jsonPrimitive?.intOrNull ?: 3000

        if (!manager.isAlive(sessionId)) {
            return "❌ Session $sessionId is dead."
        }

        val output = if (wait) {
            manager.waitAndRead(sessionId, waitTimeout, maxChars)
        } else {
            manager.readOutput(sessionId, maxChars)
        }

        return output.ifBlank { "(no output)" }
    }
}
