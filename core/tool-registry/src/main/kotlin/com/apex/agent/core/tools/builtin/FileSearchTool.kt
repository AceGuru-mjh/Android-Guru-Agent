package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 文件内容搜索工具（上下文 + 文件类型感知）
 *
 * 特性：
 * - 正则/纯文本搜索
 * - 匹配行前后显示上下文
 * - 文件类型过滤（代码/文档/配置）
 * - 自动跳过二进制和超大文件
 * - 分页输出，引导Agent继续查看
 * - 显示匹配总数帮助Agent判断是否需要缩小范围
 */
class FileSearchTool(
    private val basePath: File
) : AgentTool {

    override val id = "search_files"
    override val name = "Search in Files"
    override val description = """
        Search for a pattern across files in a directory.
        Returns matches with line numbers, file paths, and optional context lines.

        Results are paginated. Check 'total_matches' to know if more results exist.

        Tips:
        - Use file_type to narrow search (code, docs, config, all)
        - Use context_lines=2 to see surrounding code
        - Use specific patterns to reduce noise
        - For single file, use read_file + around parameter instead

        Examples:
        - {"pattern": "import os", "path": ".", "file_type": "code"}
        - {"pattern": "TODO|FIXME", "path": "./src", "context_lines": 2}
        - {"pattern": "password", "path": ".", "file_type": "config"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Search pattern (regex supported)"},
                "path": {"type": "string", "description": "Directory to search (default: workspace)"},
                "file_type": {"type": "string", "enum": ["all", "code", "docs", "config"], "description": "File type filter"},
                "file_ext": {"type": "string", "description": "Specific extension filter (e.g., '.py', '.json')"},
                "context_lines": {"type": "integer", "description": "Context lines before/after match (default 0, max 5)"},
                "max_results": {"type": "integer", "description": "Results per page (default 15)"},
                "page": {"type": "integer", "description": "Page number for pagination (default 1)"},
                "case_sensitive": {"type": "boolean", "description": "Default: false"}
            },
            "required": ["pattern"]
        }
    """.trimIndent()

    // 文件类型对应的扩展名
    private val codeExts = setOf("py", "kt", "java", "js", "ts", "c", "cpp", "h", "go", "rs", "sh", "rb", "php", "swift")
    private val docExts = setOf("md", "txt", "rst", "doc", "pdf")
    private val configExts = setOf("json", "yaml", "yml", "toml", "ini", "xml", "properties", "env", "conf")
    private val skipDirs = setOf(".git", "node_modules", "__pycache__", ".gradle", "build", ".idea", "venv", ".venv")

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val pattern = json["pattern"]?.jsonPrimitive?.content ?: return "Error: 'pattern' required"
            val path = json["path"]?.jsonPrimitive?.content ?: "."
            val fileType = json["file_type"]?.jsonPrimitive?.content ?: "all"
            val fileExt = json["file_ext"]?.jsonPrimitive?.content
            val contextLines = (json["context_lines"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 5)
            val maxResults = json["max_results"]?.jsonPrimitive?.intOrNull ?: 15
            val page = json["page"]?.jsonPrimitive?.intOrNull ?: 1
            val caseSensitive = json["case_sensitive"]?.jsonPrimitive?.booleanOrNull ?: false

            val dir = if (path.startsWith("/")) File(path) else File(basePath, path)
            if (!dir.exists()) return "Error: Directory not found: $path"

            // 构建正则
            val regex = try {
                val opts = if (caseSensitive) setOf<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
                Regex(pattern, opts)
            } catch (e: Exception) {
                val opts = if (caseSensitive) setOf<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
                Regex(Regex.escape(pattern), opts)
            }

            // 搜索
            data class Match(val file: String, val lineNum: Int, val line: String, val context: List<Pair<Int, String>>)

            val allMatches = mutableListOf<Match>()
            var filesScanned = 0
            var filesSkipped = 0

            dir.walkTopDown()
                .onEnter { dirFile -> dirFile.name !in skipDirs }
                .filter { it.isFile }
                .filter { it.length() < 5 * 1024 * 1024 } // 跳过>5MB
                .filter { f -> matchesTypeFilter(f, fileType, fileExt) }
                .take(500) // 安全限制
                .forEach { file ->
                    if (isBinary(file)) { filesSkipped++; return@forEach }
                    filesScanned++

                    try {
                        val lines = file.readLines()
                        for (i in lines.indices) {
                            if (regex.containsMatchIn(lines[i])) {
                                val context = if (contextLines > 0) {
                                    val start = maxOf(0, i - contextLines)
                                    val end = minOf(lines.size, i + contextLines + 1)
                                    (start until end).filter { it != i }.map { it to lines[it] }
                                } else emptyList()

                                allMatches.add(Match(
                                    file = file.relativeTo(dir).path,
                                    lineNum = i + 1,
                                    line = lines[i].trimEnd().take(200),
                                    context = context
                                ))
                            }
                        }
                    } catch (_: Exception) { filesSkipped++ }
                }

            // 分页
            val totalMatches = allMatches.size
            val totalPages = (totalMatches + maxResults - 1) / maxResults
            val pageMatches = allMatches.drop((page - 1) * maxResults).take(maxResults)

            if (totalMatches == 0) {
                return "No matches for '$pattern' in $filesScanned files (${filesSkipped} skipped)."
            }

            buildString {
                appendLine("🔍 '$pattern' — $totalMatches matches in $filesScanned files")
                if (totalPages > 1) appendLine("Page $page/$totalPages (showing ${pageMatches.size} of $totalMatches)")
                appendLine("─".repeat(50))

                var lastFile = ""
                pageMatches.forEach { m ->
                    if (m.file != lastFile) {
                        appendLine()
                        appendLine("📄 ${m.file}")
                        lastFile = m.file
                    }

                    // 上下文行（匹配前）
                    m.context.filter { it.first < m.lineNum - 1 }.forEach { (num, text) ->
                        appendLine("  ${(num + 1).toString().padStart(5)} │ $text")
                    }

                    // 匹配行（高亮）
                    appendLine("→ ${m.lineNum.toString().padStart(5)} │ ${m.line}")

                    // 上下文行（匹配后）
                    m.context.filter { it.first > m.lineNum - 1 }.forEach { (num, text) ->
                        appendLine("  ${(num + 1).toString().padStart(5)} │ $text")
                    }
                }

                appendLine("─".repeat(50))
                if (page < totalPages) {
                    appendLine("📌 More results available. Use page=${page + 1} to see next.")
                }
                appendLine("💡 To see full context, use: read_file(path, around=<line_number>)")
            }
        } catch (e: Exception) {
            "Search error: ${e.message}"
        }
    }

    private fun matchesTypeFilter(file: File, fileType: String, fileExt: String?): Boolean {
        if (fileExt != null) return file.name.endsWith(fileExt)
        val ext = file.extension.lowercase()
        return when (fileType) {
            "code" -> ext in codeExts
            "docs" -> ext in docExts
            "config" -> ext in configExts
            else -> true
        }
    }

    private fun isBinary(file: File): Boolean {
        val bytes = file.inputStream().use { it.readNBytes(128) }
        return bytes.count { it == 0.toByte() } > 10
    }
}
