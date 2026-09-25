package com.apex.agent.core.codetools.tools

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.codetools.diagnostics.CodeDiagnostics
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.builtin.FilePathSafety
import com.apex.agent.core.tools.toolSchema
import java.io.File

/**
 * # code_check — 进程内即时诊断（Issue #148）
 *
 * 对工作区内单个文件跑轻量诊断引擎 [CodeDiagnostics]（JSON/XML 语法、括号
 * 配平、缩进一致性、Markdown 链接），毫秒级返回 —— 把"改完想知道有没有写错"
 * 从"跑一次构建"降为一次本地扫描。
 *
 * 与 code_edit / code_write 的自动回注互补：那是写入成功后自动附加发现，
 * 本工具面向**显式**检查任意文件（包括未启用回注链路的会话）。只读、无副作用。
 *
 * 超过 512KB 的文件只诊断前 512KB 并附注说明；文件不存在时给出同目录相似名
 * 建议（前缀/包含匹配，前 3 个）。
 */
class CodeCheckTool(
    private val roots: CodeWorkspaceRoots,
    private val diagnostics: CodeDiagnostics = CodeDiagnostics()
) : BaseTool(
    id = "code_check",
    name = "Code Check",
    description = """
        Run instant in-process diagnostics (JSON/XML syntax, bracket balance,
        indent consistency, markdown links) on a file. Fast local check without
        build.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("path", required = true, description = "File path relative to the workspace root")
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("code")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val path = args.requireString("path")

        val root = roots.activeRoot()
            ?: return ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "No active coding workspace. Ask the user to open the Code screen and select a workspace first."
            )

        val file = try {
            FilePathSafety.safeResolve(root, path)
        } catch (e: SecurityException) {
            return ToolResult.fail(ToolErrorCode.SANDBOX_VIOLATION, e.message ?: "path escapes workspace")
        }

        if (!file.exists()) {
            return ToolResult.fail(ToolErrorCode.NOT_FOUND, buildNotFoundMessage(root, file, path))
        }
        if (file.isDirectory) {
            return ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT, "not a file: $path — code_check diagnoses a single file")
        }
        if (!file.canRead()) {
            return ToolResult.fail(ToolErrorCode.PERMISSION_DENIED, "cannot read: $path")
        }

        // 大文件只诊断前 512KB：流式读取头部，避免整读超大文件
        val oversized = file.length() > MAX_SCAN_CHARS
        val content = if (oversized) readHead(file) else file.readText(Charsets.UTF_8)
        val note = if (oversized) "\n(file exceeds 512KB — only the first 512KB was checked)" else ""

        val block = diagnostics.renderFindings(content, path, file)
        return if (block.isEmpty()) {
            ToolResult.ok("✅ $path: no diagnostics found$note")
        } else {
            ToolResult.ok(block + note)
        }
    }

    // ── 辅助 ─────────────────────────────────────────────────────────

    /** 未命中时的同目录相似名建议（前缀/包含双向匹配，取前 3 个）。 */
    private fun buildNotFoundMessage(root: File, file: File, path: String): String {
        val parent = file.parentFile?.takeIf { it.exists() } ?: return "file not found: $path"
        val wanted = file.name.lowercase()
        val similar = parent.listFiles()
            ?.map { it.name }
            ?.filter { name ->
                val n = name.lowercase()
                n != wanted && (n.startsWith(wanted) || wanted.startsWith(n) ||
                    n.contains(wanted) || wanted.contains(n))
            }
            ?.take(3)
            .orEmpty()
        return if (similar.isEmpty()) {
            "file not found: $path (workspace root: ${root.name})"
        } else {
            "file not found: $path — did you mean: ${similar.joinToString(", ")}?"
        }
    }

    /** 只读文件头部（最多 MAX_SCAN_CHARS 个字符），避免整读超大文件。 */
    private fun readHead(file: File): String = file.bufferedReader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(MAX_SCAN_CHARS)
        var read = 0
        while (read < MAX_SCAN_CHARS) {
            val n = reader.read(buffer, read, MAX_SCAN_CHARS - read)
            if (n < 0) break
            read += n
        }
        if (read == MAX_SCAN_CHARS) String(buffer) else String(buffer, 0, read)
    }

    private companion object {
        const val MAX_SCAN_CHARS = 512 * 1024
    }
}
