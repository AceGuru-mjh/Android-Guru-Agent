package com.apex.agent.core.codetools.git

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema

/**
 * # code_git_log — 提交历史（经 PRoot Ubuntu 沙箱执行）
 *
 * `git log --oneline --date=short --pretty=format:%h %ad %s -n <limit> [-- path]`
 * 的直通渲染：每行「短哈希 日期 主题」，limit 默认 10（1-50 钳制），可选
 * path 只看某条路径的历史。
 *
 * 空历史（新仓库）→ 「暂无提交」；非 git 仓库 → isError 引导文本。
 */
class GitLogTool(
    private val runner: GitCommandRunner
) : BaseTool(
    id = "code_git_log",
    name = "Code Git Log",
    description = """
        Show recent commits of the active workspace, one per line:
        "<short-hash> <date> <subject>" (git log --oneline --date=short).

        - limit: number of commits (default 10, 1-50)
        - path: optional pathspec to filter history to one file/directory

        Empty history returns "暂无提交".
    """.trimIndent(),
    declaredSchema = toolSchema {
        integer("limit", description = "Number of commits to show (default 10, 1-50)", minimum = 1.0, maximum = 50.0, defaultValue = 10)
        string("path", description = "Optional pathspec relative to the workspace root (omit for the whole repo)")
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("code")
        tag("git")
        tag("log")
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
            sanitizePathspec(rawPath)
                ?: return ToolResult.invalid("path", invalidPathMessage("path"))
        }
        val limit = args.intWithDefault("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val result = runner.run(logArgs(limit, path))
        if (result.indicatesNotRepo()) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, notRepoGuidance())
        }
        if (!result.success) {
            // 空仓库的 log 报 "does not have any commits yet"——按语义降级为空历史
            if (result.stderr.contains("does not have any commits")) {
                return ToolResult.ok("暂无提交")
            }
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure(result))
        }
        val body = result.stdout.trim()
        if (body.isEmpty()) {
            return ToolResult.ok("暂无提交")
        }
        return ToolResult.ok(boundedToolOutput(body))
    }

    // ── argv ─────────────────────────────────────────────────────────

    /** log 参数：--oneline --date=short --pretty 覆盖为 %h %ad %s，-n 限条数。 */
    internal fun logArgs(limit: Int, path: String?): List<String> =
        listOf(
            "log", "--oneline", "--date=short",
            "--pretty=format:%h %ad %s",
            "-n", limit.toString()
        ) + if (path == null) emptyList() else listOf("--", path)

    /** git 失败时的统一呈现：stderr 末行 + 退出码。 */
    private fun renderFailure(result: GitCommandResult): String {
        val detail = result.stderr.lineSequence().lastOrNull { it.isNotBlank() }
            ?: result.stdout.trim().take(200)
        return "git log 失败（退出码 ${result.exitCode}）：$detail"
    }

    private companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
    }
}
