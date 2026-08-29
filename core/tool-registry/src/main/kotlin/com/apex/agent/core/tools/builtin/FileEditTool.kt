package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 文件编辑工具（搜索-替换块模式）
 *
 * 采用"搜索-替换"模式而非全文覆写：
 * - 指定要替换的原文（必须精确匹配文件中的内容）
 * - 指定替换后的新文本
 * - 支持一次调用中执行多个替换块
 * - 支持纯插入（search为空时在指定行后插入）
 * - 支持删除（replace为空时删除匹配内容）
 *
 * 优势：
 * - 不会意外覆盖文件其他部分
 * - 修改意图明确，便于审查
 * - 支持多处同时修改
 * - 如果search找不到，会报错而非盲目写入
 */
class FileEditTool(
    private val basePath: File
) : AgentTool {

    override val id = "edit_file"
    override val name = "Edit File"
    override val description = """
        Edit a file using search-and-replace blocks.
        Each edit specifies the exact text to find and what to replace it with.
        The search text must match the file content EXACTLY (including whitespace).

        Modes:
        - Replace: find old text, replace with new text
        - Insert: provide empty search, specify line to insert after
        - Delete: provide search text with empty replacement

        Multiple edits can be applied in one call (applied in order).
        If any search text is not found, the entire operation fails (atomic).

        Examples:
        - Replace a line:
          {"path": "main.py", "edits": [{"search": "x = 10", "replace": "x = 20"}]}

        - Insert after line 5:
          {"path": "main.py", "edits": [{"search": "", "replace": "import os", "insert_after_line": 5}]}

        - Multiple edits:
          {"path": "main.py", "edits": [
            {"search": "old_func()", "replace": "new_func()"},
            {"search": "# TODO: fix", "replace": "# Fixed"}
          ]}

        - Delete a block:
          {"path": "main.py", "edits": [{"search": "debug_print()", "replace": ""}]}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File to edit"},
                "edits": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "search": {"type": "string", "description": "Text to find (must match exactly). Empty for insert."},
                            "replace": {"type": "string", "description": "Replacement text. Empty for delete."},
                            "insert_after_line": {"type": "integer", "description": "For insert mode: insert after this line number"}
                        },
                        "required": ["search", "replace"]
                    },
                    "description": "List of edit operations"
                },
                "create_if_missing": {"type": "boolean", "description": "Create file if it doesn't exist (default: false)"}
            },
            "required": ["path", "edits"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val path = json["path"]?.jsonPrimitive?.content ?: return "Error: 'path' required"
            val editsArray = json["edits"]?.jsonArray ?: return "Error: 'edits' required"
            val createIfMissing = json["create_if_missing"]?.jsonPrimitive?.booleanOrNull ?: false

            val file = try {
                FilePathSafety.safeResolve(basePath, path)
            } catch (e: SecurityException) {
                return "Error: ${e.message}"
            }

            if (!file.exists()) {
                if (createIfMissing) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                } else {
                    return "Error: File not found: $path. Use write_file to create, or set create_if_missing=true."
                }
            }

            // Size cap: a multi-GB file would otherwise OOM the agent on readText().
            if (file.length() > MAX_FILE_BYTES) {
                return "Error: file too large (${file.length()} bytes, max ${MAX_FILE_BYTES} bytes). " +
                    "Use shell_execute with sed/awk for large files."
            }

            var content = file.readText()
            val originalContent = content
            val appliedEdits = mutableListOf<String>()

            for (editJson in editsArray) {
                val edit = editJson.jsonObject
                val search = edit["search"]?.jsonPrimitive?.content ?: ""
                val replace = edit["replace"]?.jsonPrimitive?.content ?: ""
                val insertAfterLine = edit["insert_after_line"]?.jsonPrimitive?.intOrNull

                when {
                    // 插入模式
                    search.isEmpty() && insertAfterLine != null -> {
                        val lines = content.lines().toMutableList()
                        val idx = insertAfterLine.coerceIn(0, lines.size)
                        replace.lines().forEachIndexed { i, line ->
                            lines.add(idx + i, line)
                        }
                        content = lines.joinToString("\n")
                        appliedEdits.add("Insert ${replace.lines().size} lines after line $insertAfterLine")
                    }

                    // 替换/删除模式
                    search.isNotEmpty() -> {
                        if (!content.contains(search)) {
                            // 尝试模糊匹配（忽略首尾空白差异）
                            val trimmedSearch = search.trim()
                            if (content.contains(trimmedSearch)) {
                                content = content.replace(trimmedSearch, replace.trim())
                                appliedEdits.add("Replace (trimmed match): '${trimmedSearch.take(50)}...'")
                            } else {
                                return buildString {
                                    appendLine("❌ Edit failed: search text not found in file.")
                                    appendLine("Searched for: '${search.take(100)}'")
                                    appendLine()
                                    appendLine("Tip: Use read_file first to see the exact content,")
                                    appendLine("then copy the exact text (including spaces/indentation).")
                                }
                            }
                        } else {
                            val occurrences = Regex(Regex.escape(search)).findAll(content).count()
                            content = content.replace(search, replace)
                            val action = if (replace.isEmpty()) "Delete" else "Replace"
                            appliedEdits.add("$action ($occurrences occurrence${if (occurrences > 1) "s" else ""}): '${search.take(50)}'")
                        }
                    }

                    else -> {
                        return "Error: Each edit needs non-empty 'search' or 'insert_after_line'"
                    }
                }
            }

            // 写入文件
            file.writeText(content)

            // 生成变更摘要
            val addedLines = content.lines().size - originalContent.lines().size
            buildString {
                appendLine("✅ Edited ${file.name} (${appliedEdits.size} operations)")
                appliedEdits.forEach { appendLine("  • $it") }
                if (addedLines != 0) {
                    appendLine("  Net change: ${if (addedLines > 0) "+" else ""}$addedLines lines")
                }
                appendLine("  File now: ${content.lines().size} lines, ${formatSize(content.length.toLong())}")
            }
        } catch (e: Exception) {
            "Edit error: ${e.message}"
        }
    }

    private fun resolveFile(path: String): File =
        FilePathSafety.safeResolve(basePath, path)

    private fun formatSize(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1048576 -> "${b / 1024}KB"
        else -> "${b / 1048576}MB"
    }

    companion object {
        // 16 MB cap: prevents a multi-GB file from OOM-killing the agent on readText().
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024
    }
}
