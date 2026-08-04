package com.apex.agent.platform.terminal.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.platform.terminal.TerminalManager
import kotlinx.serialization.json.*

class TerminalCreateTool(
    private val manager: TerminalManager
) : AgentTool {

    override val id = "terminal_create"
    override val name = "Create Terminal"
    override val description = """
        Create a persistent terminal session with a real PTY (pseudo-terminal).
        
        Unlike shell_execute (one-shot, no state), a terminal session:
        - Remembers working directory across commands
        - Remembers environment variables
        - Supports interactive programs (python, node, vim, top)
        - Supports Ctrl+C, Ctrl+D, Tab completion
        - Can run background processes
        
        Returns a session_id for use with other terminal_* tools.
        
        When to use terminal vs shell_execute:
        - shell_execute: quick one-shot commands (ls, cat, grep)
        - terminal: multi-step workflows, interactive programs, persistent state
        
        Examples:
        - {"work_dir": "/sdcard/project"}
        - {"shell": "/system/bin/sh", "work_dir": "/data/local/tmp"}
        - {"work_dir": "/sdcard", "env": {"MY_VAR": "hello", "PATH_EXTRA": "/custom/bin"}}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "shell": {"type": "string", "description": "Shell binary path (default: /system/bin/sh)"},
                "work_dir": {"type": "string", "description": "Initial working directory"},
                "env": {"type": "object", "description": "Extra environment variables as key-value pairs"},
                "rows": {"type": "integer", "description": "Terminal rows (default 50)"},
                "cols": {"type": "integer", "description": "Terminal columns (default 120)"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val shell = json["shell"]?.jsonPrimitive?.contentOrNull ?: "/system/bin/sh"
        val workDir = json["work_dir"]?.jsonPrimitive?.contentOrNull ?: "/data/local/tmp"
        val rows = json["rows"]?.jsonPrimitive?.intOrNull ?: 50
        val cols = json["cols"]?.jsonPrimitive?.intOrNull ?: 120

        val envVars = mutableMapOf<String, String>()
        json["env"]?.jsonObject?.forEach { (k, v) ->
            envVars[k] = v.jsonPrimitive.content
        }

        val sessionId = manager.createSession(shell, workDir, envVars, rows, cols)

        return if (sessionId > 0) {
            val info = manager.getSessionInfo(sessionId)
            buildString {
                appendLine("✅ Terminal session created")
                appendLine("  Session ID: $sessionId")
                appendLine("  Shell: $shell")
                appendLine("  WorkDir: $workDir")
                appendLine("  PID: ${info?.pid ?: "?"}")
                appendLine()
                appendLine("Commands:")
                appendLine("  terminal_exec(session=$sessionId, command=\"...\")")
                appendLine("  terminal_send(session=$sessionId, input=\"...\")")
                appendLine("  terminal_read(session=$sessionId)")
                appendLine("  terminal_close(session=$sessionId)")
            }
        } else {
            "❌ Failed to create terminal session. Check if shell exists: $shell"
        }
    }
}
