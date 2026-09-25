package com.apex.agent.core.codetools.git

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema

/**
 * # code_git_commit — 提交助手（经 PRoot Ubuntu 沙箱执行）
 *
 * 提交工作区变更的编排工具：
 *
 * 1. 先探测 `git rev-parse --is-inside-work-tree`——非 git 仓库且 add_all
 *    时自动先 `git init`（幂等；add_all=false 时直接返回引导文本，因为
 *    无仓库连暂存都不可能）；
 * 2. add_all=true（默认）先 `git add -A` 全量暂存；
 * 3. 提交走 `git -c user.name=<n> -c user.email=<e> commit -m <message>
 *    --no-verify --quiet`——作者身份用 -c 临时注入（不写全局配置，不污染
 *    用户环境），--no-verify 跳过钩子（沙箱内通常没有钩子，防御性跳过）；
 * 4. 成功输出「已提交 <hash 前 7 位>: <message 首行>」+ git status 摘要
 *    尾行（剩余未提交变更数）；
 * 5. message 基础清洗：整体 trim，多行保留（-m 单参数原样传入），首行
 *    作为 subject 用于输出与摘要。
 *
 * 超时 60s（init/add/commit 串行三步的累计预算比单命令更宽）。
 */
class GitCommitTool(
    private val runner: GitCommandRunner
) : BaseTool(
    id = "code_git_commit",
    name = "Code Git Commit",
    description = """
        Commit changes in the active workspace.

        - message (required): commit message; first line is the subject,
          further lines become the body (passed as one -m argument)
        - add_all (default true): run "git add -A" before committing;
          when the workspace is not a git repo yet, it is initialized
          with "git init" first (idempotent)
        - author_name / author_email (optional): commit author identity,
          defaults Guru Agent <agent@guru.local> (injected via -c,
          never written to git config)

        Commits skip hooks (--no-verify). Success reports the new commit
        hash (first 7 chars) and how many changes remain uncommitted.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("message", required = true, description = "Commit message; first line = subject, optional following lines = body")
        boolean("add_all", description = "Stage everything with git add -A before committing (default true)", defaultValue = true)
        string("author_name", description = "Commit author name (default Guru Agent)", defaultValue = "Guru Agent")
        string("author_email", description = "Commit author email (default agent@guru.local)", defaultValue = "agent@guru.local")
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.MEDIUM)
        tag("code")
        tag("git")
        tag("commit")
        annotations(com.apex.agent.core.tools.ToolAnnotations.mutating())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        // message 清洗：整体 trim 后必须非空；subject 取首行
        val message = args.requireString("message").trim()
        if (message.isEmpty()) {
            return ToolResult.invalid("message", "message 不能为空白")
        }
        val subject = message.lineSequence().firstOrNull { it.isNotBlank() } ?: message
        val addAll = args.booleanWithDefault("add_all", true)
        val authorName = args.optionalString("author_name")?.trim().orEmpty().ifEmpty { DEFAULT_AUTHOR_NAME }
        val authorEmail = args.optionalString("author_email")?.trim().orEmpty().ifEmpty { DEFAULT_AUTHOR_EMAIL }

        // ── 1. 仓库探测（非仓库 + add_all → 自动 init） ────────────────
        val probe = runner.run(listOf("rev-parse", "--is-inside-work-tree"))
        if (!probe.success && probe.indicatesNotRepo()) {
            if (!addAll) {
                return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, notRepoGuidance())
            }
            val init = runner.run(listOf("init"), timeoutMs = COMMIT_TIMEOUT_MS)
            if (!init.success) {
                return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure("git init", init))
            }
        } else if (!probe.success) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure("git rev-parse", probe))
        }

        // ── 2. 全量暂存（可选） ────────────────────────────────────────
        if (addAll) {
            val add = runner.run(listOf("add", "-A"), timeoutMs = COMMIT_TIMEOUT_MS)
            if (!add.success) {
                return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure("git add", add))
            }
        }

        // ── 3. 提交（-c 注入身份；--no-verify 跳过钩子） ───────────────
        val commit = runner.run(
            listOf(
                "-c", "user.name=$authorName",
                "-c", "user.email=$authorEmail",
                "commit", "-m", message,
                "--no-verify", "--quiet"
            ),
            timeoutMs = COMMIT_TIMEOUT_MS
        )
        if (!commit.success) {
            // 空提交是合法状态而非失败：add_all 后工作区干净 / 无已暂存内容
            if (commit.stderr.contains("nothing to commit") ||
                commit.stderr.contains("nothing added to commit")
            ) {
                return ToolResult.ok("没有可提交的变更（工作区干净或没有已暂存内容）")
            }
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure("git commit", commit))
        }

        // ── 4. 成功摘要：短哈希 + subject + 剩余变更数 ─────────────────
        val hash = runner.run(listOf("rev-parse", "HEAD"))
            .takeIf { it.success }
            ?.stdout?.trim()?.take(7)
            ?: "unknown"
        val remaining = runner.run(listOf("status", "--porcelain=v1", "--untracked-files=all"))
            .takeIf { it.success }
            ?.stdout?.lineSequence()?.count { it.isNotBlank() }
        val remainingNote = when {
            remaining == null -> ""
            remaining == 0 -> "\n剩余未提交变更：无"
            else -> "\n剩余未提交变更：$remaining 个文件"
        }
        return ToolResult.ok(boundedToolOutput("已提交 $hash: $subject$remainingNote"))
    }

    /** git 失败时的统一呈现：stderr 末行 + 退出码。 */
    private fun renderFailure(command: String, result: GitCommandResult): String {
        val detail = result.stderr.lineSequence().lastOrNull { it.isNotBlank() }
            ?: result.stdout.trim().take(200)
        return "$command 失败（退出码 ${result.exitCode}）：$detail"
    }

    private companion object {
        const val DEFAULT_AUTHOR_NAME = "Guru Agent"
        const val DEFAULT_AUTHOR_EMAIL = "agent@guru.local"

        /** 提交链（init/add/commit）单步超时预算。 */
        const val COMMIT_TIMEOUT_MS = 60_000L
    }
}
