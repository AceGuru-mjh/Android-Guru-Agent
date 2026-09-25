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
 * # code_glob — 文件名模式查找（编码会话专用）
 *
 * 递归后缀匹配（“** + .kt” 形态）、目录前缀 + 后缀、顶层通配三类形态的纯 JVM
 * 匹配，输出相对路径列表（字母序，上限 100 + 截断提示）。与 code_grep 一样排除噪音目录。
 *
 * 参数描述显式防呆："不要传字符串 'undefined'/'null'，直接省略 path"
 * （opencode 在描述里处理的同款模型坏习惯）。
 */
class CodeGlobTool(
    private val roots: CodeWorkspaceRoots
) : BaseTool(
    id = "code_glob",
    name = "Code Glob",
    description = """
        Find files by name pattern in the coding workspace.

        Pattern forms: "**/*.kt" (recursive), "src/**/*.xml", "*.md" (top level).
        Noise dirs (.git, build, node_modules, …) are excluded.

        Results are workspace-relative paths, alphabetically sorted, capped at 100.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("pattern", required = true, description = "Glob pattern, e.g. \"**/*.kt\"")
        string("path", description = "Base directory (workspace-relative). Default: workspace root. Do NOT pass the string \"undefined\" or \"null\" — omit it instead")
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("code")
        tag("glob")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val pattern = args.requireString("pattern")
        val pathText = args.optionalString("path")?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }

        val root = roots.activeRoot()
            ?: return ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "No active coding workspace. Ask the user to open the Code screen and select a workspace first."
            )

        val base = try {
            if (pathText.isNullOrBlank()) root else FilePathSafety.safeResolve(root, pathText)
        } catch (e: SecurityException) {
            return ToolResult.fail(ToolErrorCode.SANDBOX_VIOLATION, e.message ?: "path escapes workspace")
        }
        if (!base.exists()) {
            return ToolResult.fail(ToolErrorCode.NOT_FOUND, "path not found: $pathText")
        }

        val matcher = compilePattern(pattern)
        val matches = mutableListOf<String>()

        fun walk(dir: File, rel: String, depth: Int) {
            if (matches.size >= MAX_RESULTS || depth > MAX_DEPTH) return
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name }) {
                if (matches.size >= MAX_RESULTS) return
                val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                if (child.isDirectory) {
                    if (child.name in EXCLUDED_DIRS) continue
                    if (matcher(childRel)) matches += "$childRel/"
                    walk(child, childRel, depth + 1)
                } else {
                    if (matcher(childRel)) matches += childRel
                }
            }
        }

        walk(base, "", 0)

        if (matches.isEmpty()) {
            return ToolResult.ok("no files matching \"$pattern\"${pathText?.let { " under $it" } ?: ""}")
        }
        val truncated = if (matches.size >= MAX_RESULTS) "\n(results capped at $MAX_RESULTS — narrow the pattern or path)" else ""
        return ToolResult.ok(matches.joinToString("\n") + truncated)
    }

    /**
     * 编译三类形态（注释中用 “** + 后缀” 描述，避免写出块注释终止序列）：
     * 1. 递归后缀（双星号 + “/” + “.ext”，或仅 “*.ext”）→ 任意深度后缀匹配；
     * 2. 指定前缀下的递归后缀；
     * 3. 其它 → 按段精确/单星通配（“*” 段内任意）。
     */
    private fun compilePattern(pattern: String): (String) -> Boolean {
        val normalized = pattern.trim().trimStart('/')
        val doubleStar = normalized.startsWith("**/")
        val suffix = if (doubleStar) {
            normalized.removePrefix("**/")
        } else {
            normalized
        }
        val lastSlash = suffix.lastIndexOf('/')
        val ext = suffix.substringAfterLast('.', "")
        val hasWildcardAnywhere = normalized.contains("*")

        if (doubleStar && lastSlash == -1 && suffix.startsWith("*.")) {
            // **/*.ext → 任意深度后缀匹配（含顶层）
            val e = ".$ext"
            return { rel -> rel.endsWith(e) }
        }
        if (doubleStar && lastSlash >= 0) {
            // prefix/**/*.ext
            val prefix = normalized.removePrefix("**/").substringBeforeLast("/**/")
            val e = ".$ext"
            return { rel -> rel.startsWith("$prefix/") && rel.endsWith(e) }
        }
        if (!hasWildcardAnywhere) {
            // 精确名
            return { rel -> rel == normalized }
        }
        // 通用段匹配
        val segRegex = normalized.split('/').joinToString("/") { seg ->
            if (seg == "**") ".*" else Regex.escape(seg).replace("\\*", "[^/]*")
        }
        val regex = try {
            Regex("^$segRegex$")
        } catch (e: Exception) {
            return { rel -> rel == normalized }
        }
        return { rel -> regex.matches(rel) }
    }

    private companion object {
        val EXCLUDED_DIRS = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules",
            "__pycache__", ".venv", "dist", "target"
        )
        const val MAX_RESULTS = 100
        const val MAX_DEPTH = 12
    }
}
