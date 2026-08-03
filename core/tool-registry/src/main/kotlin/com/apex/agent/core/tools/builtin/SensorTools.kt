package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*

/**
 * 获取位置工具
 *
 * Get device location info via `dumpsys location`. Requires location services
 * to be enabled and (on Android 10+) the app to hold location permission.
 */
class GetLocationTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "get_location"
    override val name = "Get Location"
    override val description = """
        Get device location info (if available).
        Uses network-based location or GPS.
        Note: Requires location permission to be granted.
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {},
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val result = shellExecutor("dumpsys location | grep -A 5 'last location' | head -10")
        return if (result.isBlank() || result.contains("Error")) {
            "Location not available. Ensure location services are enabled."
        } else {
            result
        }
    }
}

/**
 * 通知读取工具
 *
 * Read current active notifications via `dumpsys notification`. Requires
 * notification access permission for full results.
 */
class NotificationReadTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "notification_read"
    override val name = "Read Notifications"
    override val description = """
        Read current active notifications on the device.
        Returns notification title, text, and package.
        Note: Requires notification access permission.
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "description": "Max notifications to read (default 10)"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val limit = json["limit"]?.jsonPrimitive?.intOrNull ?: 10

        val result = shellExecutor("dumpsys notification | grep -E 'android.title|android.text|pkg=' | head ${limit * 3}")

        return if (result.isBlank()) {
            "No active notifications or permission not granted."
        } else {
            result
        }
    }
}
