package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*

/**
 * Shell执行工具
 * Agent通过此工具执行设备命令
 */
class ShellExecuteTool(
    private val executor: (String) -> String
) : AgentTool {
    
    override val id = "shell_execute"
    override val name = "Execute Shell Command"
    override val description = """
        Execute a shell command on the Android device.
        The command runs with the highest available privilege (Root > Shizuku > normal shell).
        
        Common use cases:
        - File operations: ls, cat, cp, mv, rm, mkdir
        - App management: pm list packages, pm install, am start, am force-stop
        - System info: getprop, dumpsys, settings get/put
        - Process management: ps, kill
        - Network: ping, curl, ifconfig
        
        Examples:
        - "ls -la /sdcard/Download/"
        - "pm list packages -3" (list user-installed apps)
        - "dumpsys battery" (battery status)
        - "am start -n com.android.settings/.Settings" (open Settings)
        - "getprop ro.build.version.release" (Android version)
        - "cat /proc/meminfo" (memory info)
        - "df -h" (disk usage)
        
        Note: Commands run synchronously. For long-running commands, set appropriate timeout.
    """.trimIndent()
    
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "The shell command to execute"
                }
            },
            "required": ["command"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val command = json["command"]?.jsonPrimitive?.content
                ?: return "Error: 'command' parameter is required"
            
            if (command.isBlank()) return "Error: command cannot be empty"
            
            executor(command)
        } catch (e: Exception) {
            "Error parsing arguments: ${e.message}"
        }
    }
}
