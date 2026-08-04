package com.apex.agent.platform.terminal.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.platform.terminal.TerminalManager
import kotlinx.serialization.json.*

class TerminalCloseTool(
    private val manager: TerminalManager
) : AgentTool {

    override val id = "terminal_close"
    override val name = "Close Terminal"
    override val description = """
        Close a terminal session and free resources.
        Always close sessions when done to avoid resource leaks.
        
        Examples:
        - {"session": 1}
        - {"all": true}  (close all sessions)
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "session": {"type": "integer", "description": "Session ID to close"},
                "all": {"type": "boolean", "description": "Close all sessions"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val sessionId = json["session"]?.jsonPrimitive?.intOrNull
        val all = json["all"]?.jsonPrimitive?.booleanOrNull ?: false

        if (all) {
            val count = manager.listSessions().size
            manager.closeAll()
            return "✅ Closed all $count sessions"
        }

        if (sessionId == null) {
            return "❌ Provide 'session' ID or set all=true"
        }

        manager.closeSession(sessionId)
        return "✅ Session $sessionId closed"
    }
}
