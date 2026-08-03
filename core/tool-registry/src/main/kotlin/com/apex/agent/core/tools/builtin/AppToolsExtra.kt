package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*

/**
 * 应用安装工具
 *
 * Installs an APK file already on device storage.
 * Use download_file first if the APK is from a URL.
 */
class AppInstallTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "app_install"
    override val name = "Install APK"
    override val description = """
        Install an APK file on the device.
        The APK must already be on device storage.
        Use download_file first if the APK is from a URL.

        Examples:
        - {"path": "/sdcard/Download/app.apk"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path to APK file"}
            },
            "required": ["path"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val path = json["path"]?.jsonPrimitive?.content ?: return "Error: 'path' required"
        return shellExecutor("pm install -r $path")
    }
}

/**
 * 应用卸载工具
 *
 * Uninstalls an app by package name.
 * WARNING: This removes the app and its data permanently (unless keep_data=true).
 */
class AppUninstallTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "app_uninstall"
    override val name = "Uninstall App"
    override val description = """
        Uninstall an app by package name.
        WARNING: This removes the app and its data permanently.

        Examples:
        - {"package": "com.example.app"}
        - {"package": "com.example.app", "keep_data": true}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "package": {"type": "string", "description": "Package name to uninstall"},
                "keep_data": {"type": "boolean", "description": "Keep app data (default: false)"}
            },
            "required": ["package"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val pkg = json["package"]?.jsonPrimitive?.content ?: return "Error: 'package' required"
        val keepData = json["keep_data"]?.jsonPrimitive?.booleanOrNull ?: false

        val cmd = if (keepData) "pm uninstall -k $pkg" else "pm uninstall $pkg"
        return shellExecutor(cmd)
    }
}

/**
 * 强制停止应用
 */
class AppForceStopTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "app_force_stop"
    override val name = "Force Stop App"
    override val description = "Force stop a running app by package name."

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "package": {"type": "string", "description": "Package name to force stop"}
            },
            "required": ["package"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val pkg = json["package"]?.jsonPrimitive?.content ?: return "Error: 'package' required"
        return shellExecutor("am force-stop $pkg")
    }
}

/**
 * 应用详情工具
 *
 * Get detailed info about an installed app: version, permissions, storage, activities.
 */
class AppInfoTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "app_info"
    override val name = "App Info"
    override val description = """
        Get detailed info about an installed app: version, permissions, storage, activities.

        Examples:
        - {"package": "com.android.chrome"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "package": {"type": "string", "description": "Package name"},
                "section": {"type": "string", "enum": ["all", "version", "permissions", "storage", "activities"], "description": "Info section (default: all)"}
            },
            "required": ["package"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val pkg = json["package"]?.jsonPrimitive?.content ?: return "Error: 'package' required"
        val section = json["section"]?.jsonPrimitive?.content ?: "all"

        return when (section) {
            "version" -> shellExecutor("dumpsys package $pkg | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime'")
            "permissions" -> shellExecutor("dumpsys package $pkg | grep -A 100 'requested permissions:' | head -30")
            "storage" -> shellExecutor("du -sh /data/data/$pkg 2>/dev/null || echo 'No access to app data'")
            "activities" -> shellExecutor("dumpsys package $pkg | grep -A 50 'Activity Resolver Table' | head -30")
            else -> buildString {
                appendLine("=== App: $pkg ===")
                appendLine(shellExecutor("dumpsys package $pkg | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime|dataDir'"))
                appendLine("\n=== Permissions (first 15) ===")
                appendLine(shellExecutor("dumpsys package $pkg | grep -A 20 'requested permissions:' | head -15"))
            }
        }
    }
}
