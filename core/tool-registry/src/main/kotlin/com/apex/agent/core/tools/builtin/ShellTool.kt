package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*

/**
 * Shell执行工具
 * 实际执行委托给platform层的PrivilegeDispatcher
 */
class ShellTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {
    
    override val id = "shell_execute"
    override val name = "Execute Shell Command"
    override val description = """
        Execute a shell command on the Android device.
        Commands run with elevated privileges (Shizuku/Root).
        Examples:
        - "pm list packages -3" (list user-installed apps)
        - "dumpsys battery" (battery info)
        - "am start -n com.example/.MainActivity" (launch app)
        - "ls -la /sdcard/" (list files)
        - "getprop ro.build.version.release" (Android version)
        - "settings put system screen_brightness 200" (change brightness)
    """.trimIndent()
    
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "The shell command to execute"
                },
                "timeout": {
                    "type": "integer",
                    "description": "Timeout in seconds (default 30)"
                }
            },
            "required": ["command"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val command = json["command"]?.jsonPrimitive?.content
            ?: return "Error: Missing 'command' parameter"
        
        return shellExecutor(command)
    }
}
