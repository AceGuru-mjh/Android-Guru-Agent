package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 文件写入工具（简化版）
 *
 * 用于创建新文件或完全重写。
 * 对于修改已有文件，优先使用 edit_file（搜索-替换模式更安全）。
 */
class FileWriteTool(
    private val basePath: File
) : AgentTool {

    override val id = "write_file"
    override val name = "Write File"
    override val description = """
        Create a new file or completely overwrite an existing one.
        For modifying existing files, prefer edit_file (search-replace) to avoid losing content.

        Use write_file when:
        - Creating a brand new file
        - You want to replace the ENTIRE content

        Use edit_file when:
        - Modifying specific parts of an existing file
        - You want to preserve other content in the file

        Examples:
        - {"path": "new_script.py", "content": "#!/usr/bin/env python3\nprint('hello')"}
        - {"path": "/sdcard/notes.txt", "content": "Meeting notes...", "mode": "append"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path to write"},
                "content": {"type": "string", "description": "Full file content"},
                "mode": {"type": "string", "enum": ["write", "append"], "description": "write=overwrite, append=add to end. Default: write"},
                "create_dirs": {"type": "boolean", "description": "Create parent directories (default: true)"}
            },
            "required": ["path", "content"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val path = json["path"]?.jsonPrimitive?.content ?: return "Error: 'path' required"
            val content = json["content"]?.jsonPrimitive?.content ?: ""
            val mode = json["mode"]?.jsonPrimitive?.content ?: "write"
            val createDirs = json["create_dirs"]?.jsonPrimitive?.booleanOrNull ?: true

            val file = resolveFile(path)

            if (createDirs) file.parentFile?.mkdirs()

            val existed = file.exists()
            val oldSize = if (existed) file.length() else 0L
            // 覆盖/追加模式下读取旧内容，用于输出 diff 统计
            val oldContent = if (existed) file.readText() else ""

            when (mode) {
                "append" -> file.appendText(content + "\n")
                else -> file.writeText(content)
            }

            val lineCount = lineCountOf(content)
            buildString {
                if (existed && mode == "write") {
                    val diffStat = computeLineDiffStat(oldContent, content)
                    appendLine("✅ Overwritten: ${file.name}")
                    appendLine("  ${diffStat.toSummaryLine()}")
                    appendLine("  Old: ${formatSize(oldSize)} → New: ${formatSize(file.length())}")
                } else if (mode == "append") {
                    val oldLineCount = lineCountOf(oldContent)
                    appendLine("✅ Appended to: ${file.name} (+${formatSize(content.length.toLong())})")
                    appendLine("  Diff stat: added=$lineCount, deleted=0, net=+$lineCount, " +
                        "changedRange=${oldLineCount + 1}-${oldLineCount + lineCount}")
                } else {
                    appendLine("✅ Created: ${file.name}")
                    appendLine("  Diff stat: added=$lineCount, deleted=0, net=+$lineCount, changedRange=1-$lineCount")
                }
                appendLine("  $lineCount lines, ${formatSize(file.length())}")
                appendLine("  Path: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            "Write error: ${e.message}"
        }
    }

    private fun resolveFile(path: String): File =
        if (path.startsWith("/")) File(path) else File(basePath, path)

    private fun formatSize(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1048576 -> "${b / 1024}KB"
        else -> "${b / 1048576}MB"
    }
}
