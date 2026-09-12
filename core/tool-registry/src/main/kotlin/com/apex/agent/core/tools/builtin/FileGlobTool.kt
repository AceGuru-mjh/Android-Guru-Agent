package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件发现工具（glob_files）
 *
 * 按模式匹配查找文件（类似find/glob）。
 * 与search_files不同：search_files搜索文件内容，glob_files搜索文件名/路径。
 *
 * 用途：
 * - 找到所有Python文件
 * - 找到最近修改的文件
 * - 找到特定大小的文件
 * - 项目结构探索
 */
class FileGlobTool(
    private val basePath: File
) : AgentTool {

    override val id = "glob_files"
    override val name = "Find Files"
    override val description = """
        Find files by name pattern, extension, size, or modification time.
        Use this to discover files before reading or editing them.

        Examples:
        - {"pattern": "**/*.py"} - all Python files
        - {"pattern": "*.json", "path": "./config"} - JSON in config dir
        - {"modified_within_hours": 24} - files modified in last 24h
        - {"min_size_kb": 100} - files larger than 100KB
        - {"pattern": "**/test_*"} - all test files
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Glob pattern (e.g., '**/*.py', '*.json', 'test_*')"},
                "path": {"type": "string", "description": "Base directory (default: workspace)"},
                "modified_within_hours": {"type": "integer", "description": "Only files modified within N hours"},
                "min_size_kb": {"type": "integer", "description": "Minimum file size in KB"},
                "max_results": {"type": "integer", "description": "Max results (default 30)"},
                "sort_by": {"type": "string", "enum": ["name", "size", "modified"], "description": "Sort order (default: name)"}
            },
            "required": []
        }
    """.trimIndent()

    private val skipDirs = setOf(".git", "node_modules", "__pycache__", ".gradle", "build", ".idea")

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val pattern = json["pattern"]?.jsonPrimitive?.content
            val path = json["path"]?.jsonPrimitive?.content ?: "."
            val modifiedWithin = json["modified_within_hours"]?.jsonPrimitive?.intOrNull
            val minSizeKb = json["min_size_kb"]?.jsonPrimitive?.intOrNull
            val maxResults = json["max_results"]?.jsonPrimitive?.intOrNull ?: 30
            val sortBy = json["sort_by"]?.jsonPrimitive?.content ?: "name"

            val dir = try {
                FilePathSafety.safeResolve(basePath, path)
            } catch (e: SecurityException) {
                return "Error: ${e.message}"
            }
            if (!dir.exists()) return "Error: Directory not found: $path"

            val now = System.currentTimeMillis()
            var files = dir.walkTopDown()
                .onEnter { it.name !in skipDirs }
                .filter { it.isFile }
                .toList()

            // 模式过滤
            if (pattern != null) {
                val regex = globToRegex(pattern)
                files = files.filter { regex.matches(it.relativeTo(dir).path) || regex.matches(it.name) }
            }

            // 时间过滤
            if (modifiedWithin != null) {
                val cutoff = now - modifiedWithin * 3600_000L
                files = files.filter { it.lastModified() > cutoff }
            }

            // 大小过滤
            if (minSizeKb != null) {
                files = files.filter { it.length() >= minSizeKb * 1024L }
            }

            // 排序
            files = when (sortBy) {
                "size" -> files.sortedByDescending { it.length() }
                "modified" -> files.sortedByDescending { it.lastModified() }
                else -> files.sortedBy { it.relativeTo(dir).path }
            }

            val total = files.size
            val shown = files.take(maxResults)

            if (total == 0) {
                return "No files found${if (pattern != null) " matching '$pattern'" else ""} in ${dir.path}"
            }

            buildString {
                appendLine("📁 Found $total files${if (pattern != null) " matching '$pattern'" else ""}")
                if (total > maxResults) appendLine("Showing first $maxResults:")
                appendLine("─".repeat(50))

                shown.forEach { f ->
                    val rel = f.relativeTo(dir).path
                    val size = formatSize(f.length())
                    val modified = dateFormat.format(Date(f.lastModified()))
                    appendLine("  ${rel.padEnd(45)} ${size.padStart(8)}  $modified")
                }

                if (total > maxResults) {
                    appendLine("─".repeat(50))
                    appendLine("📌 ${total - maxResults} more files. Increase max_results or narrow pattern.")
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /**
     * Glob → Regex 转换。
     *
     * P1-1 修复：旧实现是五连 replace，顺序错误导致展开结果被后续替换二次破坏：
     * `.` 的转义步骤会把前面步骤引入的元字符点也转义掉；`*` 的替换步骤又会
     * 命中前面步骤引入的 `*`。实测"双星斜杠 + 星号.py"这类递归 glob 生成的
     * 正则永远匹配不到任何文件（主打的递归文件搜索全部失效）。
     *
     * 修复：单遍扫描，模式 token 逐个输出正则片段，字面字符用 [Regex.escape] 转义：
     * - 双星+斜杠 → 可选的"零个或多个目录前缀"分组（跨任意层目录）
     * - 双星     → 跨目录通配（匹配包括路径分隔符在内的任意字符）
     * - 单星     → `[^/]*`（单层通配，不跨目录）
     * - 问号     → `[^/]`（单字符，不跨目录）
     */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder(glob.length + 16)
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**/", i) -> {
                    sb.append("(?:.*/)?")
                    i += 3
                }
                glob.startsWith("**", i) -> {
                    sb.append(".*")
                    i += 2
                }
                glob[i] == '*' -> {
                    sb.append("[^/]*")
                    i++
                }
                glob[i] == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                else -> {
                    sb.append(Regex.escape(glob[i].toString()))
                    i++
                }
            }
        }
        return Regex(sb.toString())
    }

    private fun formatSize(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1048576 -> "${b / 1024}KB"
        else -> "${b / 1048576}MB"
    }
}
