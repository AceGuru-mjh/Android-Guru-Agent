package com.apex.agent.core.codetools.tools

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.codetools.diagnostics.CodeDiagnostics
import com.apex.agent.core.codetools.diagnostics.appendDiagnostics
import com.apex.agent.core.codetools.io.FileWriteGuard
import com.apex.agent.core.codetools.io.TextFileStyle
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
 * 与 code_edit 的分工（业界标准 CLI 编码智能体同款契约）：
 * - 新文件 / 整文件重写 / 改动超过一半 → code_write；
 * - 局部修改 → code_edit（省 token 且更安全）。
 *
 * 行为：全量覆盖；存在文件必须显式确认（overwrite=true）——防止模型在没读过
 * 文件的情况下盲目覆盖。写入后回显统一 diff 摘要（存在时），并附带即时诊断
 * 回注（注入 [diagnostics] 时，发现以「⚠️ 诊断」块追加在成功输出尾部）。
 *
 * 工程细节（与 code_edit 共享 [FileWriteGuard] 契约）：
 * - **原子写**：tmp + fsync + rename，进程死亡不留半写文件；
 * - **跨工具按路径互斥**：与 code_edit 共享同一把 per-path 锁，读 before →
 *   覆盖是完整临界区，并发写同一路径互斥；
 * - **BOM/CRLF 保留**：覆盖既有文件时按原风格还原（[TextFileStyle]），
 *   全量写不悄悄改变文件形态；
 * - **读取上限**：before 全文读入有 [MAX_FILE_BYTES] 上限（与 code_edit 同
 *   口径）—— 超限文件跳过 diff（降级为统计行提示），不做无上限 readText
 *   （OOM 向量）；写入本身不受影响。
 */
class CodeWriteTool(
    private val roots: CodeWorkspaceRoots,
    private val diagnostics: CodeDiagnostics? = null
) : BaseTool(
    id = "code_write",
    name = "Code Write",
    description = """
        Write a full file (create or overwrite) in the coding workspace.

        ALWAYS prefer editing an existing file over creating a new one — use
        code_edit for targeted changes; it is cheaper and safer. Use this tool
        mainly for new files or full rewrites.

        NEVER proactively create documentation files (*.md, README) unless
        the user explicitly asks.

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

        return FileWriteGuard.withPathLock(file) {
            if (file.exists()) overwriteLocked(root, file, path, content, overwrite)
            else createNew(root, file, path, content)
        }
    }

    /** 覆盖既有文件：风格保留 + 有上限的 diff + 原子写。 */
    private fun overwriteLocked(
        root: File,
        file: File,
        path: String,
        content: String,
        overwrite: Boolean
    ): ToolResult {
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

        // before 全文读入有大小上限（对齐 code_edit 的 MAX_FILE_BYTES）——
        // 超限文件不读（OOM 防护），风格仍从头部有限字节探测。
        val previousBytes = file.length()
        val raw = if (previousBytes <= MAX_FILE_BYTES) file.readText(Charsets.UTF_8) else null
        val style = if (raw != null) TextFileStyle.of(raw) else TextFileStyle.sniff(file)

        val output = style.apply(content)
        FileWriteGuard.writeFileAtomically(file, output)

        val diff = if (raw != null) {
            // 归一形态上计算 diff —— 行尾/BOM 差异不把覆盖夸大成无关变更。
            UnifiedDiff.mini(style.normalize(raw), content, path, 4)
        } else {
            "(previous file was $previousBytes bytes — beyond the $MAX_FILE_BYTES-byte diff budget; " +
                "overwrite applied, ${content.count { it == '\n' } + 1} lines / ${content.length} chars written)"
        }
        return ToolResult.ok(
            appendDiagnostics("✅ overwrote $path (${content.length} chars)\n$diff", output, diagnostics, root, file)
        )
    }

    /** 新建文件：无既有风格，内容原样原子写。 */
    private fun createNew(
        root: File,
        file: File,
        path: String,
        content: String
    ): ToolResult {
        FileWriteGuard.writeFileAtomically(file, content)
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

    private companion object {
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
    }
}
