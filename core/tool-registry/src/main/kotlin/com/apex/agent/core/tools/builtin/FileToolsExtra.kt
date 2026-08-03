package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*
import java.io.File

/**
 * 文件内容搜索工具（grep）
 *
 * Searches for a text/regex pattern across files in a directory.
 * Returns matching lines with file paths and line numbers.
 */
class SearchFilesTool(
    private val basePath: File
) : AgentTool {

    override val id = "search_files"
    override val name = "Search Files"
    override val description = """
        Search for a text pattern across files in a directory.
        Returns matching lines with file paths and line numbers.
        Supports regex patterns.

        Examples:
        - {"pattern": "TODO", "path": "."} - find all TODOs in workspace
        - {"pattern": "def main", "path": "./src", "file_filter": "*.py"}
        - {"pattern": "password", "path": "/sdcard", "file_filter": "*.txt"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "Text or regex pattern to search for"
                },
                "path": {
                    "type": "string",
                    "description": "Directory to search in (default: workspace)"
                },
                "file_filter": {
                    "type": "string",
                    "description": "File glob filter (e.g., '*.py', '*.json'). Default: all files"
                },
                "max_results": {
                    "type": "integer",
                    "description": "Max results (default 20)"
                },
                "case_sensitive": {
                    "type": "boolean",
                    "description": "Case sensitive search (default: false)"
                }
            },
            "required": ["pattern"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val pattern = json["pattern"]?.jsonPrimitive?.content
                ?: return "Error: 'pattern' is required"
            val path = json["path"]?.jsonPrimitive?.content ?: "."
            val fileFilter = json["file_filter"]?.jsonPrimitive?.content
            val maxResults = json["max_results"]?.jsonPrimitive?.intOrNull ?: 20
            val caseSensitive = json["case_sensitive"]?.jsonPrimitive?.booleanOrNull ?: false

            val dir = if (path.startsWith("/")) File(path) else File(basePath, path)
            if (!dir.exists() || !dir.isDirectory) {
                return "Error: Directory not found: $path"
            }

            val regex = try {
                val options = if (caseSensitive) setOf<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
                Regex(pattern, options)
            } catch (e: Exception) {
                // 如果不是有效regex，当作纯文本
                val escaped = Regex.escape(pattern)
                val options = if (caseSensitive) setOf<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
                Regex(escaped, options)
            }

            val results = mutableListOf<String>()
            var filesSearched = 0

            dir.walkTopDown()
                .filter { it.isFile }
                .filter { file ->
                    fileFilter == null || matchesGlob(file.name, fileFilter)
                }
                .filter { it.length() < 1024 * 1024 } // 跳过 >1MB 文件
                .take(200) // 最多搜索200个文件
                .forEach { file ->
                    filesSearched++
                    try {
                        file.readLines().forEachIndexed { lineNum, line ->
                            if (results.size >= maxResults) return@forEachIndexed
                            if (regex.containsMatchIn(line)) {
                                val relPath = file.relativeTo(dir).path
                                results.add("$relPath:${lineNum + 1}: ${line.trim().take(150)}")
                            }
                        }
                    } catch (_: Exception) { /* skip binary files */ }
                }

            if (results.isEmpty()) {
                return "No matches found for '$pattern' in $filesSearched files"
            }

            buildString {
                appendLine("Found ${results.size} matches in $filesSearched files:")
                appendLine("---")
                results.forEach { appendLine(it) }
                if (results.size >= maxResults) appendLine("[... limited to $maxResults results]")
            }
        } catch (e: Exception) {
            "Search error: ${e.message}"
        }
    }

    private fun matchesGlob(filename: String, glob: String): Boolean {
        val regexPattern = glob
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return Regex(regexPattern).matches(filename)
    }
}

/**
 * 文件复制/移动工具
 *
 * Copy or move a file/directory from source to destination.
 * Creates destination directories if needed.
 */
class CopyMoveFileTool(
    private val basePath: File
) : AgentTool {

    override val id = "copy_move_file"
    override val name = "Copy/Move File"
    override val description = """
        Copy or move a file/directory from source to destination.
        Creates destination directories if needed.

        Examples:
        - {"source": "main.py", "dest": "backup/main.py", "action": "copy"}
        - {"source": "/sdcard/Download/file.pdf", "dest": "/sdcard/Documents/", "action": "move"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "source": {"type": "string", "description": "Source file/directory path"},
                "dest": {"type": "string", "description": "Destination path"},
                "action": {"type": "string", "enum": ["copy", "move"], "description": "copy or move (default: copy)"}
            },
            "required": ["source", "dest"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val source = json["source"]?.jsonPrimitive?.content ?: return "Error: 'source' required"
            val dest = json["dest"]?.jsonPrimitive?.content ?: return "Error: 'dest' required"
            val action = json["action"]?.jsonPrimitive?.content ?: "copy"

            val srcFile = resolve(source)
            if (!srcFile.exists()) return "Error: Source not found: $source"

            val destFile = resolve(dest)
            destFile.parentFile?.mkdirs()

            when (action) {
                "move" -> {
                    val success = srcFile.renameTo(destFile)
                    if (success) {
                        "OK: Moved $source → $dest"
                    } else {
                        // renameTo失败时尝试copy+delete
                        srcFile.copyRecursively(destFile, overwrite = true)
                        srcFile.deleteRecursively()
                        "OK: Moved (copy+delete) $source → $dest"
                    }
                }
                else -> {
                    if (srcFile.isDirectory) {
                        srcFile.copyRecursively(destFile, overwrite = true)
                    } else {
                        srcFile.copyTo(destFile, overwrite = true)
                    }
                    "OK: Copied $source → $dest (${srcFile.length() / 1024}KB)"
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun resolve(path: String): File =
        if (path.startsWith("/")) File(path) else File(basePath, path)
}
