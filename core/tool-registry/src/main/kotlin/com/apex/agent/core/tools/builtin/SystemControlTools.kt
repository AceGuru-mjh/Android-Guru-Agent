package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*

/**
 * 系统设置读写工具
 *
 * Read or modify Android system settings (system/secure/global namespaces).
 */
class SettingsTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "get_set_settings"
    override val name = "Get/Set System Settings"
    override val description = """
        Read or modify Android system settings.
        Namespaces: system, secure, global

        Common settings:
        - system: screen_brightness, screen_off_timeout, volume_ring
        - secure: location_mode, bluetooth_on, wifi_on
        - global: airplane_mode_on, mobile_data, wifi_sleep_policy

        Examples:
        - {"action": "get", "namespace": "system", "key": "screen_brightness"}
        - {"action": "set", "namespace": "system", "key": "screen_brightness", "value": "200"}
        - {"action": "get", "namespace": "global", "key": "airplane_mode_on"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["get", "set"], "description": "get or set"},
                "namespace": {"type": "string", "enum": ["system", "secure", "global"], "description": "Settings namespace"},
                "key": {"type": "string", "description": "Setting key name"},
                "value": {"type": "string", "description": "Value to set (for 'set' action)"}
            },
            "required": ["action", "namespace", "key"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val action = json["action"]?.jsonPrimitive?.content ?: return "Error: 'action' required"
        val namespace = json["namespace"]?.jsonPrimitive?.content ?: "system"
        val key = json["key"]?.jsonPrimitive?.content ?: return "Error: 'key' required"
        val value = json["value"]?.jsonPrimitive?.content

        return when (action) {
            "get" -> {
                val result = shellExecutor("settings get $namespace $key")
                "$namespace/$key = ${result.trim()}"
            }
            "set" -> {
                if (value == null) return "Error: 'value' required for set action"
                shellExecutor("settings put $namespace $key $value")
                "OK: Set $namespace/$key = $value"
            }
            else -> "Error: action must be 'get' or 'set'"
        }
    }
}

/**
 * 媒体控制工具（音量/亮度/播放）
 *
 * Control device volume, brightness, and media playback via shell commands.
 */
class MediaControlTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "control_media"
    override val name = "Control Media"
    override val description = """
        Control device volume, brightness, and media playback.

        Examples:
        - {"action": "volume", "stream": "music", "level": 10}
        - {"action": "brightness", "level": 200}
        - {"action": "media_play"}
        - {"action": "media_pause"}
        - {"action": "media_next"}
        - {"action": "volume_mute"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["volume", "brightness", "media_play", "media_pause", "media_next", "media_prev", "volume_mute", "volume_up", "volume_down"], "description": "Control action"},
                "stream": {"type": "string", "enum": ["music", "ring", "alarm", "notification", "call"], "description": "Audio stream (for volume)"},
                "level": {"type": "integer", "description": "Level value (volume 0-15, brightness 0-255)"}
            },
            "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val action = json["action"]?.jsonPrimitive?.content ?: return "Error: 'action' required"
        val stream = json["stream"]?.jsonPrimitive?.content ?: "music"
        val level = json["level"]?.jsonPrimitive?.intOrNull

        val streamCode = when (stream) {
            "music" -> 3; "ring" -> 2; "alarm" -> 4
            "notification" -> 5; "call" -> 0; else -> 3
        }

        return when (action) {
            "volume" -> {
                val lvl = level ?: return "Error: 'level' required for volume"
                shellExecutor("media volume --stream $streamCode --set $lvl")
            }
            "brightness" -> {
                val lvl = level ?: return "Error: 'level' required for brightness"
                shellExecutor("settings put system screen_brightness_mode 0 && settings put system screen_brightness $lvl")
            }
            "media_play" -> shellExecutor("input keyevent 126")
            "media_pause" -> shellExecutor("input keyevent 127")
            "media_next" -> shellExecutor("input keyevent 87")
            "media_prev" -> shellExecutor("input keyevent 88")
            "volume_up" -> shellExecutor("input keyevent 24")
            "volume_down" -> shellExecutor("input keyevent 25")
            "volume_mute" -> shellExecutor("input keyevent 164")
            else -> "Error: Unknown action '$action'"
        }
    }
}

/**
 * 剪贴板工具
 *
 * Read or write the device clipboard via shell commands. Reading clipboard
 * may require the app to be in foreground on Android 10+.
 */
class ClipboardTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "clipboard"
    override val name = "Clipboard"
    override val description = """
        Read or write the device clipboard.
        Note: Reading clipboard may require the app to be in foreground on Android 10+.

        Examples:
        - {"action": "read"}
        - {"action": "write", "content": "text to copy"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["read", "write"], "description": "read or write"},
                "content": {"type": "string", "description": "Content to write (for write action)"}
            },
            "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val action = json["action"]?.jsonPrimitive?.content ?: "read"
        val content = json["content"]?.jsonPrimitive?.content

        return when (action) {
            "read" -> {
                val result = shellExecutor("service call clipboard 2 s16 com.android.shell 2>/dev/null || echo 'Clipboard read requires foreground'")
                result.ifBlank { "(clipboard empty or inaccessible)" }
            }
            "write" -> {
                if (content == null) return "Error: 'content' required for write"
                shellExecutor("am broadcast -a clipper.set -e text '$content' 2>/dev/null || echo 'Clipboard write may need Clipper app or accessibility'")
            }
            else -> "Error: action must be 'read' or 'write'"
        }
    }
}

/**
 * 获取当前时间工具
 */
class GetTimeTool : AgentTool {

    override val id = "get_time"
    override val name = "Get Current Time"
    override val description = "Get current date, time, timezone, and timestamp."

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "format": {"type": "string", "description": "Date format (default: full)"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val now = java.util.Calendar.getInstance()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", java.util.Locale.getDefault())
        val timestamp = System.currentTimeMillis()

        return buildString {
            appendLine("Date: ${sdf.format(now.time)}")
            appendLine("Timestamp: $timestamp")
            appendLine("Timezone: ${java.util.TimeZone.getDefault().id}")
            appendLine("Day of week: ${now.getDisplayName(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.LONG, java.util.Locale.getDefault())}")
        }
    }
}

/**
 * 系统日志工具
 *
 * Read Android system logs (logcat) for debugging and monitoring.
 */
class LogcatTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "logcat"
    override val name = "System Log"
    override val description = """
        Read Android system logs (logcat).
        Useful for debugging apps, checking errors, monitoring system events.

        Examples:
        - {"lines": 50} - last 50 lines
        - {"filter": "MyApp", "lines": 30}
        - {"level": "E", "lines": 20} - errors only
        - {"clear": true} - clear log buffer
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "lines": {"type": "integer", "description": "Number of lines (default 50)"},
                "filter": {"type": "string", "description": "Filter by tag or keyword"},
                "level": {"type": "string", "enum": ["V", "D", "I", "W", "E", "F"], "description": "Min log level"},
                "clear": {"type": "boolean", "description": "Clear log buffer"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val lines = json["lines"]?.jsonPrimitive?.intOrNull ?: 50
        val filter = json["filter"]?.jsonPrimitive?.content
        val level = json["level"]?.jsonPrimitive?.content
        val clear = json["clear"]?.jsonPrimitive?.booleanOrNull ?: false

        if (clear) {
            return shellExecutor("logcat -c")
        }

        val cmd = buildString {
            append("logcat -d -t $lines")
            level?.let { append(" *:$it") }
            filter?.let { append(" | grep -i '$it'") }
        }

        return shellExecutor(cmd)
    }
}
