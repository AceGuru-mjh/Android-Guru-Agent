package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*

/**
 * 设备信息工具
 *
 * Retrieves device info (model, battery, storage, memory, network, display)
 * via shell commands. Routes through the injected shell executor so it uses
 * whatever privilege backend (Root / Shizuku / normal shell) is available.
 */
class DeviceInfoTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "get_device_info"
    override val name = "Device Info"
    override val description = """
        Get device information: model, Android version, battery, storage, network, display.
        Specify what info you need, or get all.

        Examples:
        - {"info": "all"}
        - {"info": "battery"}
        - {"info": "storage"}
        - {"info": "model"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "info": {
                    "type": "string",
                    "enum": ["all", "model", "battery", "storage", "memory", "network", "display"],
                    "description": "What info to retrieve (default: all)"
                }
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val info = json["info"]?.jsonPrimitive?.content ?: "all"

        return when (info) {
            "model" -> shellExecutor("getprop ro.product.model && getprop ro.product.brand && getprop ro.build.version.release")
            "battery" -> shellExecutor("dumpsys battery")
            "storage" -> shellExecutor("df -h /data /sdcard 2>/dev/null || df -h")
            "memory" -> shellExecutor("cat /proc/meminfo | head -5")
            "network" -> shellExecutor("ip addr show wlan0 2>/dev/null | grep inet")
            "display" -> shellExecutor("dumpsys display | grep -E 'mBaseDisplayInfo|DisplayDeviceInfo' | head -3")
            else -> buildString {
                appendLine("=== Device ===")
                appendLine(shellExecutor("getprop ro.product.model"))
                appendLine(shellExecutor("getprop ro.build.version.release"))
                appendLine("\n=== Battery ===")
                appendLine(shellExecutor("dumpsys battery | head -8"))
                appendLine("\n=== Storage ===")
                appendLine(shellExecutor("df -h /data 2>/dev/null | tail -1"))
                appendLine("\n=== Memory ===")
                appendLine(shellExecutor("cat /proc/meminfo | head -3"))
            }
        }
    }
}

/**
 * 应用列表工具
 *
 * Lists installed apps via `pm list packages`. Supports filtering by
 * user/system/all and an optional name search keyword.
 */
class AppListTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "app_list"
    override val name = "List Apps"
    override val description = """
        List installed applications.
        Filter: "user" (third-party), "system", "all", or search by name.

        Examples:
        - {"filter": "user"} - list user-installed apps
        - {"filter": "all", "search": "chrome"} - find Chrome
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "filter": {
                    "type": "string",
                    "enum": ["user", "system", "all"],
                    "description": "Filter type (default: user)"
                },
                "search": {
                    "type": "string",
                    "description": "Search keyword to filter results"
                }
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val filter = json["filter"]?.jsonPrimitive?.content ?: "user"
        val search = json["search"]?.jsonPrimitive?.content

        val cmd = when (filter) {
            "system" -> "pm list packages -s"
            "all" -> "pm list packages"
            else -> "pm list packages -3"
        }

        var result = shellExecutor(cmd)

        if (search != null) {
            result = result.lines()
                .filter { it.contains(search, ignoreCase = true) }
                .joinToString("\n")
        }

        val count = result.lines().filter { it.isNotBlank() }.size
        return "Found $count apps:\n$result"
    }
}

/**
 * 应用启动工具
 *
 * Launches an Android app by package name. Uses `monkey` by default
 * (works without knowing the launcher Activity), or `am start -n pkg/activity`
 * when an explicit activity is provided.
 */
class AppLaunchTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "app_launch"
    override val name = "Launch App"
    override val description = """
        Launch an Android app by package name.
        Use app_list first if you don't know the package name.

        Examples:
        - {"package": "com.android.settings"}
        - {"package": "com.android.chrome"}
        - {"package": "com.tencent.mm"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "package": {
                    "type": "string",
                    "description": "App package name"
                },
                "activity": {
                    "type": "string",
                    "description": "Specific activity to launch (optional)"
                }
            },
            "required": ["package"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val pkg = json["package"]?.jsonPrimitive?.content
            ?: return "Error: 'package' parameter is required"
        val activity = json["activity"]?.jsonPrimitive?.content

        return if (activity != null) {
            shellExecutor("am start -n $pkg/$activity")
        } else {
            // monkey命令启动app（不需要知道具体Activity）
            shellExecutor("monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>/dev/null || am start $pkg")
        }
    }
}
