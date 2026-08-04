package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

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
