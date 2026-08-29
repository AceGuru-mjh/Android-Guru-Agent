package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*

/**
 * UI 交互接口 —— UI 工具可选的语义交互通道。
 *
 * 当 AccessibilityService 就绪时，UI 工具可通过此接口
 * 执行语义化的 UI 操作（手势执行、UI 树捕获），获得更高可靠性和更细粒度的控制。
 * 未就绪时回退到 input 命令方式。
 */
interface UiInteractionProvider {
    /** 是否可用（AccessibilityService 已就绪） */
    val isAvailable: Boolean

    /** 执行手势操作（点击/长按/滑动） */
    suspend fun performGesture(action: GestureAction): String

    /** 获取当前屏幕的结构化 UI 树 */
    suspend fun dumpUiTree(maxDepth: Int = 20): String
}

/**
 * 手势操作描述。
 */
sealed class GestureAction {
    data class Tap(val x: Int, val y: Int) : GestureAction()
    data class LongPress(val x: Int, val y: Int, val durationMs: Int = 1000) : GestureAction()
    data class Swipe(
        val x1: Int, val y1: Int,
        val x2: Int, val y2: Int,
        val durationMs: Int = 300
    ) : GestureAction()
    data class DirectionalScroll(val direction: String, val durationMs: Int = 300) : GestureAction()
    data class Back(val x: Int = 0, val y: Int = 0) : GestureAction()
    data class Home(val x: Int = 0, val y: Int = 0) : GestureAction()
}

/**
 * UI点击工具 —— 优先使用 AccessibilityService 语义交互，回退到 input 命令。
 */
class UiTapTool(
    private val shellExecutor: suspend (String) -> String,
    private val uiProvider: UiInteractionProvider? = null
) : AgentTool {

    override val id = "ui_tap"
    override val name = "Tap Screen"
    override val description = """
        Tap at specific screen coordinates.
        Use ui_dump first to find element positions, or screenshot to see the screen.
        Coordinates are in pixels from top-left corner.
        When accessibility service is active, uses semantic gesture dispatch
        for more reliable interaction.

        Examples:
        - {"x": 540, "y": 1200} - tap center-ish of a 1080x2400 screen
        - {"x": 100, "y": 100} - tap top-left area
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "X coordinate (pixels)"},
                "y": {"type": "integer", "description": "Y coordinate (pixels)"},
                "long_press": {"type": "boolean", "description": "Long press instead of tap (default: false)"}
            },
            "required": ["x", "y"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val x = json["x"]?.jsonPrimitive?.intOrNull ?: return "Error: 'x' required"
        val y = json["y"]?.jsonPrimitive?.intOrNull ?: return "Error: 'y' required"
        val longPress = json["long_press"]?.jsonPrimitive?.booleanOrNull ?: false

        // 优先使用 AccessibilityService 语义手势
        if (uiProvider?.isAvailable == true) {
            val action = if (longPress) {
                GestureAction.LongPress(x, y)
            } else {
                GestureAction.Tap(x, y)
            }
            return uiProvider.performGesture(action)
        }

        // 回退到 input 命令
        return if (longPress) {
            shellExecutor("input swipe $x $y $x $y 1000")
        } else {
            shellExecutor("input tap $x $y")
        }
    }
}

/**
 * UI滑动工具 —— 优先使用 AccessibilityService 语义交互，回退到 input 命令。
 */
class UiSwipeTool(
    private val shellExecutor: suspend (String) -> String,
    private val uiProvider: UiInteractionProvider? = null
) : AgentTool {

    override val id = "ui_swipe"
    override val name = "Swipe Screen"
    override val description = """
        Swipe from one point to another on the screen.
        Use for scrolling, navigating, or gesture-based actions.
        When accessibility service is active, uses semantic gesture dispatch.

        Examples:
        - {"x1": 540, "y1": 1800, "x2": 540, "y2": 600} - scroll up
        - {"x1": 540, "y1": 600, "x2": 540, "y2": 1800} - scroll down
        - {"x1": 900, "y1": 1200, "x2": 100, "y2": 1200} - swipe left
        - {"direction": "up"} - simplified scroll
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "x1": {"type": "integer", "description": "Start X"},
                "y1": {"type": "integer", "description": "Start Y"},
                "x2": {"type": "integer", "description": "End X"},
                "y2": {"type": "integer", "description": "End Y"},
                "direction": {"type": "string", "enum": ["up", "down", "left", "right"], "description": "Simplified direction (overrides coordinates)"},
                "duration_ms": {"type": "integer", "description": "Swipe duration in ms (default: 300)"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val direction = json["direction"]?.jsonPrimitive?.content
        val duration = json["duration_ms"]?.jsonPrimitive?.intOrNull ?: 300

        // 优先使用 AccessibilityService 语义手势
        if (uiProvider?.isAvailable == true && direction != null) {
            return uiProvider.performGesture(
                GestureAction.DirectionalScroll(direction, duration)
            )
        }

        if (direction != null) {
            val coords = when (direction) {
                "up" -> listOf(540, 1800, 540, 600)
                "down" -> listOf(540, 600, 540, 1800)
                "left" -> listOf(900, 1200, 180, 1200)
                "right" -> listOf(180, 1200, 900, 1200)
                else -> return "Error: Unknown direction"
            }
            val cmd = "input swipe ${coords[0]} ${coords[1]} ${coords[2]} ${coords[3]} $duration"

            if (uiProvider?.isAvailable == true) {
                return uiProvider.performGesture(
                    GestureAction.Swipe(coords[0], coords[1], coords[2], coords[3], duration)
                )
            }
            return shellExecutor(cmd)
        }

        val x1 = json["x1"]?.jsonPrimitive?.intOrNull ?: return "Error: coordinates or direction required"
        val y1 = json["y1"]?.jsonPrimitive?.intOrNull ?: return "Error: 'y1' required"
        val x2 = json["x2"]?.jsonPrimitive?.intOrNull ?: return "Error: 'x2' required"
        val y2 = json["y2"]?.jsonPrimitive?.intOrNull ?: return "Error: 'y2' required"

        if (uiProvider?.isAvailable == true) {
            return uiProvider.performGesture(GestureAction.Swipe(x1, y1, x2, y2, duration))
        }
        return shellExecutor("input swipe $x1 $y1 $x2 $y2 $duration")
    }
}

/**
 * UI树读取工具 —— 优先使用 AccessibilityService 结构化树，回退到 uiautomator XML。
 */
class UiDumpTool(
    private val shellExecutor: suspend (String) -> String,
    private val uiProvider: UiInteractionProvider? = null
) : AgentTool {

    override val id = "ui_dump"
    override val name = "Dump UI Hierarchy"
    override val description = """
        Dump the current screen's UI hierarchy (view tree).
        Returns structured info with all visible elements, their types, text, and bounds.
        Use this to find elements before tapping or interacting.

        When accessibility service is active, returns parsed semantic tree.
        Falls back to shell 'uiautomator dump' when unavailable.
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "max_depth": {"type": "integer", "description": "Max tree depth (default: 10)"},
                "filter_text": {"type": "string", "description": "Only show elements containing this text"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val maxDepth = json["max_depth"]?.jsonPrimitive?.intOrNull ?: 10
        val filterText = json["filter_text"]?.jsonPrimitive?.content

        // 优先使用 AccessibilityService 结构化树
        if (uiProvider?.isAvailable == true) {
            val result = uiProvider.dumpUiTree(maxDepth)
            if (result.isNotBlank()) {
                return if (filterText != null) {
                    val lines = result.lines().filter { it.contains(filterText, ignoreCase = true) }
                    "Found ${lines.size} elements matching '$filterText':\n${lines.joinToString("\n").take(5000)}"
                } else {
                    result.take(8000).let {
                        if (it.length < result.length) "$it\n[... truncated]" else it
                    }
                }
            }
        }

        // 回退到 uiautomator dump
        var result = shellExecutor("uiautomator dump /data/local/tmp/ui_dump.xml 2>/dev/null && cat /data/local/tmp/ui_dump.xml")

        if (result.contains("Error") || result.isBlank()) {
            return "UI dump failed. This may require accessibility service or specific permissions."
        }

        if (filterText != null) {
            val lines = result.split("><").filter { it.contains(filterText, ignoreCase = true) }
            return "Found ${lines.size} elements matching '$filterText':\n${lines.joinToString("\n") { "<$it>" }.take(3000)}"
        }

        if (result.length > 5000) {
            result = result.take(5000) + "\n[... truncated at 5000 chars]"
        }

        return result
    }
}

/**
 * 截图工具
 */
class ScreenshotTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "screenshot"
    override val name = "Take Screenshot"
    override val description = """
        Capture the current screen as a PNG image.
        Returns the file path of the saved screenshot.

        Examples:
        - {} - save to default location
        - {"path": "/sdcard/Pictures/my_screenshot.png"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Save path (default: /sdcard/Pictures/apex_screen.png)"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val path = json["path"]?.jsonPrimitive?.content ?: "/sdcard/Pictures/apex_screen.png"

        // Shell-escape the screenshot path — unescaped `;` / `$(...)` / `'` in the path
        // would inject into `screencap -p $path`.
        val result = shellExecutor("screencap -p ${ShellQuote.shellQuote(path)}")
        return if (result.contains("Error") && !result.contains("written")) {
            "Error: $result"
        } else {
            "OK: Screenshot saved to $path"
        }
    }
}

/**
 * 文本输入工具
 *
 * Type text into the currently focused input field.
 * Make sure the target input field is focused (tap it first with ui_tap).
 */
class InputTextTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "input_text"
    override val name = "Input Text"
    override val description = """
        Type text into the currently focused input field.
        Make sure the target input field is focused (tap it first with ui_tap).

        For special keys, use key codes:
        - Enter: keyevent 66
        - Backspace: keyevent 67
        - Tab: keyevent 61

        Examples:
        - {"text": "Hello World"}
        - {"text": "search query", "submit": true} - type and press Enter
        - {"keyevent": 66} - press Enter
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to type"},
                "submit": {"type": "boolean", "description": "Press Enter after typing (default: false)"},
                "keyevent": {"type": "integer", "description": "Press a specific key code instead of typing text"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val text = json["text"]?.jsonPrimitive?.content
        val submit = json["submit"]?.jsonPrimitive?.booleanOrNull ?: false
        val keyevent = json["keyevent"]?.jsonPrimitive?.intOrNull

        if (keyevent != null) {
            return shellExecutor("input keyevent $keyevent")
        }

        if (text == null) return "Error: 'text' or 'keyevent' required"

        // 转义特殊字符
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "'\\''")
            .replace(" ", "%s")
            .replace("&", "\\&")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("|", "\\|")

        var result = shellExecutor("input text '$escaped'")

        if (submit) {
            result += "\n" + shellExecutor("input keyevent 66")
        }

        return result.ifBlank { "OK: Typed '${text.take(50)}'" }
    }
}
