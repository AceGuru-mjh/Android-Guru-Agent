package com.apex.agent.core.codetools.tools

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.builtin.FilePathSafety
import com.apex.agent.core.tools.toolSchema
import java.io.File

/**
 * # code_grep — 工作区正则搜索（ripgrep 语义的纯 JVM 实现）
 *
 * opencode 的 grep 由 `rg --json` 驱动；Android 端不假设 guest 里有 rg，这里用
 * 纯 JVM 实现**同款输出契约**：按文件分组、`  Line N: 内容` 行、命中上限 100、
 * 截断提示"用更精确的 path/pattern"。
 *
 * 工程细节：
 * - 常用目录排除（.git/.gradle/build/node_modules…）—— 搜索信噪比与速度的关键；
 * - 二进制/超大文件跳过（>1MB 不进正则引擎）；
 * - 输出钳制 16KB，防一次搜索撑爆上下文；
 * - glob 过滤参数（`*.kt` / `*.{ts,tsx}`）在遍历时短路，不读不匹配的文件。
 */
class CodeGrepTool(
    private val roots: CodeWorkspaceRoots
) : BaseTool(
    id = "code_grep",
    name = "Code Grep",
    description = """
        Search file contents with a regex across the coding workspace (ripgrep-style).

        Output is grouped by file: each match line shows "  Line N: <text>".
        Common noise dirs (.git, .gradle, build, node_modules) are excluded.

        Tips:
        - Narrow with `path` (subdirectory) and `glob` (e.g. "*.kt") before
          widening the pattern.
        - Results cap at 100 matches — refine the query instead of paging.
        - To count matches, run a shell command instead; this tool is for finding.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("pattern", required = true, description = "Regular expression (Java/Kotlin syntax)")
        string("path", description = "Directory or file to search (relative to workspace root). Default: whole workspace")
        string("glob", description = "File-name filter, e.g. \"*.kt\" or \"*.{ts,tsx}\"")
        boolean("ignore_case", description = "Case-insensitive matching (default false)")
        integer("max_results", description = "Max matches to return (default 100)", minimum = 1.0, maximum = 500.0)
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("code")
        tag("search")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val patternText = args.requireString("pattern")
        val pathText = args.optionalString("path")
        val glob = args.optionalString("glob")
        val ignoreCase = args.booleanWithDefault("ignore_case", false)
        val maxResults = (args.optionalInt("max_results") ?: 100).coerceIn(1, 500)

        val root = roots.activeRoot()
            ?: return ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "No active coding workspace. Ask the user to open the Code screen and select a workspace first."
            )

        val regex = try {
            if (ignoreCase) Regex(patternText, RegexOption.IGNORE_CASE) else Regex(patternText)
        } catch (e: Exception) {
            return ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT, "invalid regex: ${e.message}")
        }

        val scope = try {
            if (pathText.isNullOrBlank()) root else FilePathSafety.safeResolve(root, pathText)
        } catch (e: SecurityException) {
            return ToolResult.fail(ToolErrorCode.SANDBOX_VIOLATION, e.message ?: "path escapes workspace")
        }
        if (!scope.exists()) {
            return ToolResult.fail(ToolErrorCode.NOT_FOUND, "path not found: $pathText")
        }

        val globMatcher = glob?.let { compileGlob(it) }
        val results = mutableListOf<String>()
        var filesScanned = 0
        var truncated = false

        fun scanFile(file: File) {
            if (truncated) return
            if (globMatcher != null && !globMatcher(file.name)) return
            if (file.length() > MAX_SCAN_FILE_BYTES) return
            if (looksBinary(file)) return
            val matchesInFile = mutableListOf<String>()
            try {
                val lines = file.readLines(Charsets.UTF_8)
                for ((idx, line) in lines.withIndex()) {
                    if (regex.containsMatchIn(line)) {
                        matchesInFile += "  Line ${idx + 1}: ${line.trim().take(200)}"
                        if (matchesInFile.size >= MAX_MATCHES_PER_FILE) break
                    }
                }
            } catch (e: Exception) {
                return // 不可读文件静默跳过（权限/编码）
            }
            if (matchesInFile.isNotEmpty()) {
                filesScanned++
                results += relPathOf(root, file) + ":"
                results += matchesInFile
                if (results.size >= maxResults * 2) truncated = true
            }
        }

        fun walk(dir: File, depth: Int) {
            if (truncated || depth > MAX_DEPTH) return
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name }) {
                if (truncated) return
                if (child.isDirectory) {
                    if (child.name in EXCLUDED_DIRS) continue
                    walk(child, depth + 1)
                } else {
                    scanFile(child)
                }
            }
        }

        if (scope.isDirectory) walk(scope, 0) else scanFile(scope)

        if (results.isEmpty()) {
            return ToolResult.ok("no matches for /$patternText/ in ${pathText ?: "workspace"}")
        }
        val body = results.take(maxResults * 2).joinToString("\n")
        val suffix = if (truncated) {
            "\n(results truncated — narrow the search with `path`, `glob` or a more specific pattern)"
        } else ""
        return ToolResult.ok("$body$suffix")
    }

    // ── 辅助 ─────────────────────────────────────────────────────────

    private fun relPathOf(root: File, file: File): String {
        val rootPath = root.canonicalPath.trimEnd('/')
        return file.canonicalPath.removePrefix("$rootPath/")
    }

    private fun looksBinary(file: File): Boolean {
        val bytes = ByteArray(minOf(file.length(), 2048L).toInt())
        val read = file.inputStream().use { it.read(bytes) }
        if (read <= 0) return true
        for (i in 0 until read) if (bytes[i] == 0.toByte()) return true
        return false
    }

    /** 支持 "*.kt" 后缀、"*.{ts,tsx}" 花括号后缀、双星号递归三类常用形态（够用主义，非完整 glob）。 */
    private fun compileGlob(pattern: String): (String) -> Boolean {
        // brace 展开：*.{ts,tsx} → 多个后缀
        val braceMatch = Regex("^\\*\\.\\{(.+)}$").find(pattern)
        if (braceMatch != null) {
            val exts = braceMatch.groupValues[1].split(',').map { it.trim() }
            return { name -> exts.any { name.endsWith(it) } }
        }
        if (pattern.startsWith("**/")) {
            val suffix = pattern.removePrefix("**/")
            return { name -> name == suffix || name.endsWith("/$suffix") }
        }
        if (pattern.startsWith("*.")) {
            val ext = pattern.removePrefix("*")
            return { name -> name.endsWith(ext) }
        }
        return { name -> name == pattern }
    }

    private companion object {
        val EXCLUDED_DIRS = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules",
            ".npm", ".cache", "__pycache__", ".venv", "dist", "target"
        )
        const val MAX_DEPTH = 12
        const val MAX_SCAN_FILE_BYTES = 1_000_000L // 1MB
        const val MAX_MATCHES_PER_FILE = 20
    }
}
