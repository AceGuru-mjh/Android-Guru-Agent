package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 目录列表工具（优化版）
 *
 * 新增：
 * - depth: 递归深度控制
 * - pattern: 文件名过滤
 * - show_size: 是否显示大小
 * - 输出截断 + 提示
 */
class ListFilesTool(
    private val basePath: File
) : AgentTool {

    override val id = "list_files"
    override val name = "List Files"
    override val description = """
        List directory contents with optional recursion and filtering.

        Examples:
        - {"path": "."} - list workspace root
        - {"path": ".", "depth": 2} - recursive 2 levels
        - {"path": ".", "pattern": "*.py"} - only Python files
        - {"path": "/sdcard/Download", "show_size": true}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Directory path (default: '.')"},
                "depth": {"type": "integer", "description": "Recursion depth (default 1, max 4)"},
                "pattern": {"type": "string", "description": "Filename glob filter (e.g., '*.kt')"},
                "show_hidden": {"type": "boolean", "description": "Show hidden files (default: false)"},
                "show_size": {"type": "boolean", "description": "Show file sizes (default: true)"},
                "max_items": {"type": "integer", "description": "Max items to show (default 50)"}
            },
            "required": []
        }
    """.trimIndent()

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val path = json["path"]?.jsonPrimitive?.content ?: "."
            val depth = (json["depth"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 4)
            val pattern = json["pattern"]?.jsonPrimitive?.content
            val showHidden = json["show_hidden"]?.jsonPrimitive?.booleanOrNull ?: false
            val showSize = json["show_size"]?.jsonPrimitive?.booleanOrNull ?: true
            val maxItems = json["max_items"]?.jsonPrimitive?.intOrNull ?: 50

            val dir = if (path.startsWith("/")) File(path) else File(basePath, path)
            if (!dir.exists()) return "Error: Not found: $path"
            if (!dir.isDirectory) return "Error: Not a directory: $path"

            val items = mutableListOf<String>()
            var totalItems = 0
            listDirRecursive(dir, "", depth, 0, pattern, showHidden, showSize, items, { totalItems++ }, maxItems)

            buildString {
                appendLine("📁 ${dir.absolutePath}")
                appendLine("---")
                items.forEach { appendLine(it) }
                if (totalItems > maxItems) {
                    appendLine("---")
                    appendLine("📌 Showing $maxItems of $totalItems items. Use pattern filter or increase max_items.")
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun listDirRecursive(
        dir: File, indent: String, maxDepth: Int, currentDepth: Int,
        pattern: String?, showHidden: Boolean, showSize: Boolean,
        items: MutableList<String>, countIncrement: () -> Unit, maxItems: Int
    ) {
        if (currentDepth >= maxDepth || items.size >= maxItems) return

        val files = dir.listFiles()
            ?.filter { showHidden || !it.name.startsWith(".") }
            ?.filter { f -> pattern == null || f.isDirectory || matchesGlob(f.name, pattern) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return

        for (file in files) {
            if (items.size >= maxItems) break
            countIncrement()

            val icon = if (file.isDirectory) "📁" else getFileIcon(file.name)
            val size = if (showSize && file.isFile) " (${formatSize(file.length())})" else ""
            items.add("$indent$icon ${file.name}$size")

            if (file.isDirectory && currentDepth + 1 < maxDepth) {
                listDirRecursive(file, "$indent  ", maxDepth, currentDepth + 1, pattern, showHidden, showSize, items, countIncrement, maxItems)
            }
        }
    }

    private fun getFileIcon(name: String): String = when {
        name.endsWith(".py") -> "🐍"
        name.endsWith(".kt") || name.endsWith(".java") -> "☕"
        name.endsWith(".js") || name.endsWith(".ts") -> "📜"
        name.endsWith(".json") -> "📋"
        name.endsWith(".xml") || name.endsWith(".html") -> "🌐"
        name.endsWith(".md") || name.endsWith(".txt") -> "📝"
        name.endsWith(".sh") -> "⚙️"
        name.endsWith(".png") || name.endsWith(".jpg") -> "🖼️"
        name.endsWith(".zip") || name.endsWith(".tar") -> "📦"
        else -> "📄"
    }

    private fun matchesGlob(name: String, glob: String): Boolean {
        val regex = glob.replace(".", "\\.").replace("*", ".*").replace("?", ".")
        return Regex(regex).matches(name)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${bytes / (1024 * 1024)}MB"
    }
}

/**
 * 文件删除工具
 *
 * Deletes a single file or an empty directory. For non-empty directories, instructs
 * the agent to fall back to `shell_execute` with `rm -rf`.
 */
class DeleteFileTool(
    private val basePath: File
) : AgentTool {

    override val id = "delete_file"
    override val name = "Delete File"
    override val description = """
        Delete a file or empty directory.
        WARNING: This operation is irreversible.
        For directories with content, use shell_execute with 'rm -rf'.

        Examples:
        - {"path": "temp.txt"}
        - {"path": "/sdcard/Download/old_file.pdf"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "File or empty directory path to delete"
                }
            },
            "required": ["path"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val path = json["path"]?.jsonPrimitive?.content
                ?: return "Error: 'path' parameter is required"

            val file = if (path.startsWith("/")) File(path) else File(basePath, path)

            if (!file.exists()) {
                return "Error: File not found: $path"
            }

            if (file.isDirectory && file.listFiles()?.isNotEmpty() == true) {
                return "Error: Directory is not empty. Use shell_execute with 'rm -rf $path' for recursive delete."
            }

            val deleted = file.delete()
            if (deleted) "OK: Deleted $path" else "Error: Failed to delete $path"
        } catch (e: Exception) {
            "Error deleting file: ${e.message}"
        }
    }
}
