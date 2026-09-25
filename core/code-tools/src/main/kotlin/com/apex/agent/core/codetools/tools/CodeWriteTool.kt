package com.apex.agent.core.codetools.tools

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.codetools.diagnostics.CodeDiagnostics
import com.apex.agent.core.codetools.diagnostics.appendDiagnostics
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.builtin.FilePathSafety
import com.apex.agent.core.tools.toolSchema
import java.io.File

/**
 * # code_write — 全量写文件（编码会话专用）
 *
 * 与 code_edit 的分工（opencode 同款契约）：
 * - 新文件 / 整文件重写 / 改动超过一半 → code_write；
 * - 局部修改 → code_edit（省 token 且更安全）。
 *
 * 行为：全量覆盖；存在文件必须显式确认（overwrite=true）——防止模型在没读过
 * 文件的情况下盲目覆盖。写入后回显统一 diff 摘要（存在时），并附带即时诊断
 * 回注（注入 [diagnostics] 时，发现以「⚠️ 诊断」块追加在成功输出尾部）。
 */
class CodeWriteTool(
    private val roots: CodeWorkspaceRoots,
    private val diagnostics: CodeDiagnostics? = null
) : BaseTool(
    id = "code_write",
    name = "Code Write",
    description = """
        Write a full file (create or overwrite) in the coding workspace.

        Use code_edit for targeted changes to an existing file — it is cheaper and
        safer. Use this tool when creating new files or rewriting most of a file.

        Overwriting an existing file requires overwrite=true (read it with
        code_read first to avoid destroying work).
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("path", required = true, description = "File path relative to the workspace root")
        string("content", required = true, description = "Full file content")
        boolean("overwrite", description = "Allow replacing an existing file (default false)")
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.MEDIUM)
        tag("code")
        tag("write")
        annotations(com.apex.agent.core.tools.ToolAnnotations.idempotentWrite())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val path = args.requireString("path")
        val content = args.requireString("content")
        val overwrite = args.booleanWithDefault("overwrite", false)

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

        if (file.exists()) {
            if (!overwrite) {
                return ToolResult.fail(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "file exists: $path — set overwrite=true after reading it (code_read), " +
                        "or use code_edit for targeted changes"
                )
            }
            if (!file.canWrite()) {
                return ToolResult.fail(ToolErrorCode.PERMISSION_DENIED, "cannot write: $path")
            }
            val before = file.readText(Charsets.UTF_8)
            file.writeText(content, Charsets.UTF_8)
            val diff = UnifiedDiff.mini(before, content, path, 4)
            return ToolResult.ok(
                appendDiagnostics("✅ overwrote $path (${content.length} chars)\n$diff", content, diagnostics, root, file)
            )
        }

        file.parentFile?.mkdirs()
        val created = file.createNewFile()
        if (!created && !file.exists()) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "failed to create: $path")
        }
        file.writeText(content, Charsets.UTF_8)
        return ToolResult.ok(
            appendDiagnostics(
                "✅ created $path (${content.count { it == '\n' } + 1} lines, ${content.length} chars)",
                content,
                diagnostics,
                root,
                file
            )
        )
    }
}
