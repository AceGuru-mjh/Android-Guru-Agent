package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 文件读取工具（视口滚动模式）
 *
 * 采用视口模式：每次返回一个"窗口"的内容，Agent通过scroll上下翻页。
 * 类似IDE中打开大文件时的行为——不会一次加载全部，而是按需滚动。
 *
 * 支持的定位方式：
 * - offset + limit：从第offset行开始读limit行
 * - scroll："down"/"up" 基于上次位置翻页
 * - tail：读最后N行
 * - around：读指定行号周围的内容
 */
class FileReadTool(
    private val basePath: File
) : AgentTool {

    override val id = "read_file"
    override val name = "Read File"
    override val description = """
        View file content through a scrollable viewport.
        Each call shows a window of lines. Use scroll or offset to navigate.

        Navigation:
        - First read: {"path": "file.py", "limit": 80}
        - Scroll down: {"path": "file.py", "scroll": "down"}
        - Scroll up: {"path": "file.py", "scroll": "up"}
        - Jump to line: {"path": "file.py", "around": 150, "limit": 40}
        - Read tail: {"path": "file.py", "tail": 30}

        Output includes line numbers and a position indicator showing
        where you are in the file. Use this to navigate precisely.
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path"},
                "offset": {"type": "integer", "description": "Start line (0-based)"},
                "limit": {"type": "integer", "description": "Lines to show (default 80, max 500)"},
                "scroll": {"type": "string", "enum": ["down", "up"], "description": "Scroll direction from last position"},
                "around": {"type": "integer", "description": "Center view on this line number"},
                "tail": {"type": "integer", "description": "Show last N lines"}
            },
            "required": ["path"]
        }
    """.trimIndent()

    // 记住每个文件的当前视口位置（用于scroll）
    private val viewportCache = mutableMapOf<String, Int>()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val path = json["path"]?.jsonPrimitive?.content ?: return "Error: 'path' required"
            val limit = (json["limit"]?.jsonPrimitive?.intOrNull ?: 80).coerceIn(1, 500)
            val scroll = json["scroll"]?.jsonPrimitive?.content
            val around = json["around"]?.jsonPrimitive?.intOrNull
            val tail = json["tail"]?.jsonPrimitive?.intOrNull
            val offset = json["offset"]?.jsonPrimitive?.intOrNull

            val file = try {
                FilePathSafety.safeResolve(basePath, path)
            } catch (e: SecurityException) {
                return "Error: ${e.message}"
            }
            if (!file.exists()) return "Error: File not found: $path"
            if (!file.canRead()) return "Error: Permission denied: $path"
            if (file.isDirectory) return "Error: '$path' is a directory. Use list_files."
            if (isBinary(file)) return "Binary file (${formatSize(file.length())}). Use shell_execute for inspection."

            // Size cap: a multi-GB logcat dump would otherwise OOM the agent on readLines().
            if (file.length() > MAX_FILE_BYTES) {
                return "Error: file too large (${file.length()} bytes, max ${MAX_FILE_BYTES} bytes). " +
                    "Use offset/limit or shell_execute with grep/sed/tail."
            }

            val lines = file.readLines()
            val total = lines.size
            if (total == 0) return "📄 ${file.name} — empty file (0 lines)"

            // 确定起始位置
            val startLine = when {
                tail != null -> maxOf(0, total - tail)
                around != null -> maxOf(0, around - limit / 2)
                scroll == "down" -> (viewportCache[path] ?: 0) + limit
                scroll == "up" -> maxOf(0, (viewportCache[path] ?: limit) - limit)
                offset != null -> offset.coerceIn(0, total - 1)
                else -> 0
            }

            if (startLine >= total) {
                return "📄 ${file.name} ($total lines) — offset $startLine beyond EOF."
            }

            val endLine = minOf(startLine + limit, total)
            val visibleLines = lines.subList(startLine, endLine)

            // 更新视口缓存
            viewportCache[path] = startLine

            // 构建输出
            buildString {
                // 头部元信息
                appendLine("📄 ${file.name} (${formatSize(file.length())}, $total lines)")
                val pct = if (total > 0) (endLine * 100) / total else 100
                appendLine("Viewing lines ${startLine + 1}–$endLine of $total ($pct%)")
                appendLine("─".repeat(50))

                // 带行号的内容
                visibleLines.forEachIndexed { i, line ->
                    val num = startLine + i + 1
                    appendLine("${num.toString().padStart(5)} │ $line")
                }

                appendLine("─".repeat(50))

                // 导航提示
                val hasBefore = startLine > 0
                val hasAfter = endLine < total
                when {
                    hasBefore && hasAfter -> {
                        appendLine("⬆️ ${startLine} lines above | ⬇️ ${total - endLine} lines below")
                        appendLine("   scroll:\"up\" to go back | scroll:\"down\" or offset:$endLine to continue")
                    }
                    hasAfter -> {
                        appendLine("⬇️ ${total - endLine} more lines below. offset:$endLine or scroll:\"down\" to continue.")
                    }
                    hasBefore -> {
                        appendLine("⬆️ ${startLine} lines above. scroll:\"up\" to go back.")
                    }
                    else -> {
                        appendLine("✅ Showing entire file.")
                    }
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun isBinary(file: File): Boolean {
        if (file.length() == 0L) return false
        val bytes = file.inputStream().use { it.readNBytes(256) }
        return bytes.count { it == 0.toByte() } > bytes.size / 10
    }

    private fun resolveFile(path: String): File =
        FilePathSafety.safeResolve(basePath, path)

    private fun formatSize(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1048576 -> "${b / 1024}KB"
        else -> "${b / 1048576}MB"
    }

    companion object {
        // 16 MB cap: prevents a multi-GB logcat dump / dataset from OOM-killing the
        // agent when readLines() materializes the whole file as a List<String>.
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024
    }
}
