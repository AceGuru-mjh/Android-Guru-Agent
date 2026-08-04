package com.apex.agent.platform.terminal.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.platform.terminal.SpecialKey
import com.apex.agent.platform.terminal.TerminalManager
import kotlinx.serialization.json.*

class TerminalSendTool(
    private val manager: TerminalManager
) : AgentTool {

    override val id = "terminal_send"
    override val name = "Send to Terminal"
    override val description = """
        Send input to a terminal session. Use for:
        - Typing into interactive programs (python REPL, node, vim)
        - Responding to prompts (y/n, password)
        - Sending special keys (Ctrl+C to interrupt, Ctrl+D for EOF)
        
        Special keys: enter, ctrl_c, ctrl_d, ctrl_z, ctrl_l, tab, escape,
                      up, down, left, right, home, end, page_up, page_down,
                      delete, backspace
        
        Examples:
        - {"session": 1, "input": "print('hello')"}  (sends text + Enter)
        - {"session": 1, "key": "ctrl_c"}             (interrupt)
        - {"session": 1, "input": "exit()", "no_enter": true}  (no Enter)
        - {"session": 1, "key": "tab"}                (autocomplete)
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "session": {"type": "integer", "description": "Session ID"},
                "input": {"type": "string", "description": "Text to send (Enter added unless no_enter=true)"},
                "key": {"type": "string", "enum": ["enter","ctrl_c","ctrl_d","ctrl_z","ctrl_l","tab","escape","up","down","left","right","home","end","page_up","page_down","delete","backspace"], "description": "Special key to send"},
                "no_enter": {"type": "boolean", "description": "Don't append Enter after input (default: false)"},
                "read_after": {"type": "boolean", "description": "Read output after sending (default: true)"},
                "wait_ms": {"type": "integer", "description": "Wait time before reading (default 300)"}
            },
            "required": ["session"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val sessionId = json["session"]?.jsonPrimitive?.intOrNull
            ?: return "❌ Error: 'session' required"
        val input = json["input"]?.jsonPrimitive?.contentOrNull
        val key = json["key"]?.jsonPrimitive?.contentOrNull
        val noEnter = json["no_enter"]?.jsonPrimitive?.booleanOrNull ?: false
        val readAfter = json["read_after"]?.jsonPrimitive?.booleanOrNull ?: true
        val waitMs = json["wait_ms"]?.jsonPrimitive?.intOrNull ?: 300

        if (!manager.isAlive(sessionId)) {
            return "❌ Session $sessionId is dead."
        }

        // 发送
        when {
            key != null -> {
                val specialKey = try {
                    SpecialKey.valueOf(key.uppercase())
                } catch (e: Exception) {
                    return "❌ Unknown key: '$key'. Valid: enter, ctrl_c, ctrl_d, ctrl_z, tab, escape, up, down, left, right"
                }
                manager.sendKey(sessionId, specialKey)
            }
            input != null -> {
                if (noEnter) {
                    manager.sendRaw(sessionId, input)
                } else {
                    manager.sendLine(sessionId, input)
                }
            }
            else -> return "❌ Provide 'input' or 'key'"
        }

        // 读取响应
        if (readAfter) {
            kotlinx.coroutines.delay(waitMs.toLong())
            val output = manager.readOutput(sessionId)
            return output.ifBlank { "(sent, no output yet)" }
        }

        return "✅ Sent"
    }
}
