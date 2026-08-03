package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*
import java.io.File

/**
 * 文件读取工具
 *
 * Reads a text file from the device. Supports workspace-relative or absolute paths.
 * For very large files, returns a windowed slice via max_lines / offset_lines.
 */
class ReadFileTool(
    private val basePath: File
) : AgentTool {

    override val id = "read_file"
    override val name = "Read File"
    override val description = """
        Read the content of a file. Returns the file content as text.
        Supports any text file (code, config, markdown, json, etc.)
        For large files, returns first N lines with truncation notice.

        Path resolution:
        - Relative paths are relative to the workspace directory
        - Absolute paths (starting with /) are used directly

        Examples:
        - {"path": "main.py"} - read workspace file
        - {"path": "/sdcard/Download/data.csv"} - read absolute path
        - {"path": "src/config.json", "max_lines": 100}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "File path (relative to workspace or absolute)"
                },
                "max_lines": {
                    "type": "integer",
                    "description": "Maximum lines to read (default 200)"
                },
                "offset_lines": {
                    "type": "integer",
                    "description": "Start reading from this line (default 0)"
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
            val maxLines = json["max_lines"]?.jsonPrimitive?.intOrNull ?: 200
            val offsetLines = json["offset_lines"]?.jsonPrimitive?.intOrNull ?: 0

            val file = resolveFile(path)

            if (!file.exists()) {
                return "Error: File not found: ${file.absolutePath}"
            }
            if (!file.canRead()) {
                return "Error: Permission denied: ${file.absolutePath}"
            }
            if (file.isDirectory) {
                return "Error: '$path' is a directory, use list_files instead"
            }

            val sizeKb = file.length() / 1024.0
            if (sizeKb > 512) {
                return "Error: File too large (${String.format("%.1f", sizeKb)}KB). " +
                    "Use max_lines/offset_lines to read portions, or shell_execute with head/tail."
            }

            val allLines = file.readLines()
            val totalLines = allLines.size

            if (offsetLines >= totalLines) {
                return "File has $totalLines lines, offset $offsetLines is beyond end."
            }

            val endLine = minOf(offsetLines + maxLines, totalLines)
            val selectedLines = allLines.subList(offsetLines, endLine)
            val content = selectedLines.joinToString("\n")

            val header = buildString {
                append("File: ${file.name} (${String.format("%.1f", sizeKb)}KB, $totalLines lines)\n")
                if (offsetLines > 0 || endLine < totalLines) {
                    append("Showing lines ${offsetLines + 1}-$endLine of $totalLines\n")
                }
                append("---\n")
            }

            header + content
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }
    }

    private fun resolveFile(path: String): File {
        return if (path.startsWith("/")) {
            File(path)
        } else {
            File(basePath, path)
        }
    }
}

/**
 * 文件写入工具
 *
 * Writes content to a file, creating parent directories as needed.
 * Default mode overwrites; "append" mode adds to end of file.
 */
class WriteFileTool(
    private val basePath: File
) : AgentTool {

    override val id = "write_file"
    override val name = "Write File"
    override val description = """
        Write content to a file. Creates the file and parent directories if they don't exist.
        Overwrites existing file content by default.
        Use mode="append" to add content to end of file.

        Examples:
        - {"path": "main.py", "content": "print('hello')"}
        - {"path": "log.txt", "content": "new entry", "mode": "append"}
        - {"path": "/sdcard/Documents/report.md", "content": "# Report\n..."}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "File path to write to"
                },
                "content": {
                    "type": "string",
                    "description": "Content to write"
                },
                "mode": {
                    "type": "string",
                    "enum": ["write", "append"],
                    "description": "Write mode: 'write' (overwrite) or 'append'. Default: write"
                }
            },
            "required": ["path", "content"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val path = json["path"]?.jsonPrimitive?.content
                ?: return "Error: 'path' parameter is required"
            val content = json["content"]?.jsonPrimitive?.content
                ?: return "Error: 'content' parameter is required"
            val mode = json["mode"]?.jsonPrimitive?.content ?: "write"

            val file = resolveFile(path)

            // 创建父目录
            file.parentFile?.mkdirs()

            when (mode) {
                "append" -> file.appendText(content + "\n")
                else -> file.writeText(content)
            }

            val sizeKb = file.length() / 1024.0
            "OK: Written to ${file.absolutePath} (${String.format("%.1f", sizeKb)}KB, mode=$mode)"
        } catch (e: Exception) {
            "Error writing file: ${e.message}"
        }
    }

    private fun resolveFile(path: String): File {
        return if (path.startsWith("/")) File(path) else File(basePath, path)
    }
}

/**
 * 目录列表工具
 *
 * Lists files and directories at a path with size, type, and modification time.
 */
class ListFilesTool(
    private val basePath: File
) : AgentTool {

    override val id = "list_files"
    override val name = "List Files"
    override val description = """
        List files and directories at a given path.
        Shows name, type (file/dir), size, and modification time.

        Examples:
        - {"path": "."} - list workspace root
        - {"path": "src"} - list src directory
        - {"path": "/sdcard/Download"} - list downloads
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Directory path (default: workspace root)"
                },
                "show_hidden": {
                    "type": "boolean",
                    "description": "Show hidden files (default: false)"
                }
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val path = json["path"]?.jsonPrimitive?.content ?: "."
            val showHidden = json["show_hidden"]?.jsonPrimitive?.booleanOrNull ?: false

            val dir = if (path.startsWith("/")) File(path) else File(basePath, path)

            if (!dir.exists()) {
                return "Error: Directory not found: ${dir.absolutePath}"
            }
            if (!dir.isDirectory) {
                return "Error: '$path' is not a directory, use read_file instead"
            }

            val files = dir.listFiles()
                ?.filter { showHidden || !it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()

            if (files.isEmpty()) {
                return "Directory is empty: ${dir.absolutePath}"
            }

            buildString {
                appendLine("Directory: ${dir.absolutePath} (${files.size} items)")
                appendLine("---")
                for (file in files) {
                    val type = if (file.isDirectory) "📁" else "📄"
                    val size = if (file.isFile) formatSize(file.length()) else "-"
                    val modified = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(file.lastModified()))
                    appendLine("$type ${file.name.padEnd(30)} ${size.padEnd(10)} $modified")
                }
            }
        } catch (e: Exception) {
            "Error listing files: ${e.message}"
        }
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
