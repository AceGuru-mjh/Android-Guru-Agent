package com.apex.agent.core.codetools.git

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema

/**
 * # code_git_diff — 差异视图（经 PRoot Ubuntu 沙箱执行）
 *
 * 输出统一 diff（unified diff）原文——UI 侧 Code 屏已有 DiffOutput 渲染器
 * （+ 行绿色 / - 行红色，逐行等宽着色），本工具只负责产出纯文本 diff：
 *
 * - staged=false（默认）：工作区 vs HEAD（`git diff HEAD -- path`）；
 * - staged=true：暂存区 vs HEAD（`git diff --cached -- path`）；
 * - path 可选（相对工作区），空则整个仓库；
 * - max_lines 默认 120（1-400）：diff 原文截断到该行数并追加提示行；
 * - 空差异 → 「没有差异」；
 * - 新初始化仓库尚无 HEAD 时，`git diff HEAD` 会报 unknown revision——
 *   自动降级为 `git diff -- path`（工作区 vs 暂存区）并在输出首行注明。
 */
class GitDiffTool(
    private val runner: GitCommandRunner
) : BaseTool(
    id = "code_git_diff",
    name = "Code Git Diff",
    description = """
        Show pending changes as a unified diff of the active workspace.

        - staged=false (default): working tree vs HEAD ("git diff HEAD -- path")
        - staged=true: index vs HEAD ("git diff --cached -- path")
        - path: optional pathspec relative to the workspace root
        - max_lines: diff line budget (default 120, max 400); the raw diff is
          truncated to this many lines with a trailing hint.

        Empty diff returns "没有差异". Output is raw unified diff text —
        the UI renders +/- lines with colors.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("path", description = "Optional pathspec relative to the workspace root (omit for the whole repo)")
        boolean("staged", description = "true = diff the index against HEAD; false (default) = working tree vs HEAD", defaultValue = false)
        integer("max_lines", description = "Max diff lines to return (default 120, 1-400)", minimum = 1.0, maximum = 400.0, defaultValue = 120)
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("code")
        tag("git")
        tag("diff")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val rawPath = args.optionalString("path")?.trim().orEmpty()
        val path: String? = if (rawPath.isEmpty()) {
            null
        } else {
            // 非空但被清洗拒绝（绝对路径 / 前导短横线 / .. 穿越）→ 参数错误
            sanitizePathspec(rawPath)
                ?: return ToolResult.invalid("path", invalidPathMessage("path"))
        }
        val staged = args.booleanWithDefault("staged", false)
        val maxLines = args.intWithDefault("max_lines", DEFAULT_MAX_LINES).coerceIn(1, MAX_LINES_CAP)

        if (staged) {
            val cached = runner.run(diffArgs(cached = true, path))
            if (cached.indicatesNotRepo()) {
                return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, notRepoGuidance())
            }
            if (!cached.success) {
                return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure("git diff", cached))
            }
            return renderDiff(cached.stdout, scopeNote = null, maxLines)
        }

        val head = runner.run(diffArgs(cached = false, path))
        if (head.indicatesNotRepo()) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, notRepoGuidance())
        }
        if (head.success) {
            return renderDiff(head.stdout, scopeNote = null, maxLines)
        }
        if (isMissingHead(head.stderr)) {
            // 尚无任何提交：HEAD 不存在，降级为「工作区 vs 暂存区」
            val fallback = runner.run(listOf("diff") + pathspec(path))
            if (fallback.success) {
                return renderDiff(
                    fallback.stdout,
                    scopeNote = "HEAD 尚不存在（仓库还没有任何提交）——以下为工作区与暂存区的差异",
                    maxLines
                )
            }
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure("git diff", fallback))
        }
        return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure("git diff", head))
    }

    // ── argv ─────────────────────────────────────────────────────────

    /** diff 参数：--cached 或 HEAD，-- 之后是可选 pathspec。 */
    internal fun diffArgs(cached: Boolean, path: String?): List<String> =
        if (cached) listOf("diff", "--cached") + pathspec(path)
        else listOf("diff", "HEAD") + pathspec(path)

    /** pathspec 追加段：有路径时 -- <path>，无路径时空。 */
    private fun pathspec(path: String?): List<String> =
        if (path == null) emptyList() else listOf("--", path)

    /** 「HEAD 尚不存在」的 stderr 特征（git 报 unknown revision / ambiguous argument）。 */
    private fun isMissingHead(stderr: String): Boolean =
        stderr.contains("unknown revision") || stderr.contains("ambiguous argument")

    // ── 渲染 ─────────────────────────────────────────────────────────

    /** 渲染 diff 原文：空 → 没有差异；超行 → 截断加提示。 */
    private fun renderDiff(diff: String, scopeNote: String?, maxLines: Int): ToolResult {
        val body = diff.trim()
        if (body.isEmpty()) {
            return ToolResult.ok("没有差异")
        }
        val note = scopeNote?.let { "$it\n" } ?: ""
        val lines = body.split('\n')
        if (lines.size <= maxLines) {
            return ToolResult.ok(boundedToolOutput(note + body))
        }
        val truncated = lines.take(maxLines).joinToString("\n")
        return ToolResult.ok(
            boundedToolOutput(
                note + truncated +
                    "\n（差异共 ${lines.size} 行，已截断至前 $maxLines 行——可调大 max_lines 或指定 path 缩小范围）"
            )
        )
    }

    /** git 失败时的统一呈现：stderr 末行 + 退出码。 */
    private fun renderFailure(command: String, result: GitCommandResult): String {
        val detail = result.stderr.lineSequence().lastOrNull { it.isNotBlank() }
            ?: result.stdout.trim().take(200)
        return "$command 失败（退出码 ${result.exitCode}）：$detail"
    }

    private companion object {
        const val DEFAULT_MAX_LINES = 120
        const val MAX_LINES_CAP = 400
    }
}
