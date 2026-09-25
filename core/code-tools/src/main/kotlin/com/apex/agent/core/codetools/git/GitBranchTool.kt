package com.apex.agent.core.codetools.git

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema

/**
 * # code_git_branch — 分支管理（经 PRoot Ubuntu 沙箱执行）
 *
 * 一个工具四个动作（action 分派）：
 *
 * - list（默认）：`git branch --format=%(refname:short) %(upstream:track)`，
 *   另探 `git branch --show-current` 标出当前分支（星号开头），跟踪状态
 *   （[ahead N] / [behind N] / [gone]）翻译成中文；
 * - create：`git branch <name> [<create_from>]`（create_from 可选，默认
 *   基于当前 HEAD）；
 * - switch：`git switch <name>`；
 * - delete：`git branch -d <name>`（安全删除——未合并的分支会被 git 拒绝，
 *   需要强删时引导用户去终端执行 git branch -D）。
 *
 * name 在 create/switch/delete 必填；name 与 create_from 拒绝空白字符与
 * 前导短横线（防止被 git 当作选项解析）。输出全中文。
 */
class GitBranchTool(
    private val runner: GitCommandRunner
) : BaseTool(
    id = "code_git_branch",
    name = "Code Git Branch",
    description = """
        Manage git branches of the active workspace.

        - action: list (default) / create / switch / delete
        - name: branch name, required for create/switch/delete
        - create_from: optional base branch or commit for create
          (defaults to current HEAD)

        list marks the current branch with * and translates upstream
        tracking state ([ahead N]/[behind N]/[gone]) to Chinese. delete
        uses the safe -d (unmerged branches are refused by git — force
        deletion must be done by the user in the terminal).
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "action",
            description = "Branch operation: list / create / switch / delete",
            enumValues = listOf("list", "create", "switch", "delete"),
            defaultValue = "list"
        )
        string("name", description = "Branch name (required for create/switch/delete)")
        string("create_from", description = "Base branch or commit for create (optional)")
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.MEDIUM)
        tag("code")
        tag("git")
        tag("branch")
        annotations(com.apex.agent.core.tools.ToolAnnotations.mutating())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val action = args.stringWithDefault("action", ACTION_LIST)
        if (action !in VALID_ACTIONS) {
            return ToolResult.invalid("action", "action 必须是 ${VALID_ACTIONS.joinToString("/")} 之一（收到 \"$action\"）")
        }
        val name = args.optionalString("name")?.trim().orEmpty()
        val createFrom = args.optionalString("create_from")?.trim().orEmpty()

        return when (action) {
            ACTION_LIST -> list()
            ACTION_CREATE -> {
                val checkedName = validateBranchName(name) ?: return invalidNameResult()
                val checkedFrom = if (createFrom.isEmpty()) {
                    null
                } else {
                    validateBranchName(createFrom) ?: return invalidFromResult()
                }
                create(checkedName, checkedFrom)
            }
            ACTION_SWITCH -> {
                val checkedName = validateBranchName(name) ?: return invalidNameResult()
                switch(checkedName)
            }
            else -> {
                val checkedName = validateBranchName(name) ?: return invalidNameResult()
                delete(checkedName)
            }
        }
    }

    // ── 动作实现 ─────────────────────────────────────────────────────

    /** list：分支列表 + 当前分支星标 + 跟踪状态翻译。 */
    private suspend fun list(): ToolResult {
        val result = runner.run(
            listOf("branch", "--format=%(refname:short) %(upstream:track)")
        )
        if (result.indicatesNotRepo()) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, notRepoGuidance())
        }
        if (!result.success) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure("git branch", result))
        }
        val current = runner.run(listOf("branch", "--show-current"))
            .takeIf { it.success }
            ?.stdout?.trim().orEmpty()

        val entries = result.stdout.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
        if (entries.isEmpty()) {
            // unborn 分支：HEAD 指向尚无提交的分支，列表为空但 --show-current 有值
            return if (current.isEmpty()) {
                ToolResult.ok("分支列表为空")
            } else {
                ToolResult.ok(
                    "当前分支: $current（尚无提交——首次提交后才会出现在分支列表中）"
                )
            }
        }

        val rendered = entries.joinToString("\n") { entry ->
            val branch = entry.substringBefore(" [").trim()
            val track = entry.substringAfter(" [", "").removeSuffix("]")
            val prefix = if (branch == current) "* " else "  "
            val note = translateTrack(track)
            if (note.isEmpty()) "$prefix$branch" else "$prefix$branch（$note）"
        }
        return ToolResult.ok(boundedToolOutput("分支列表（${entries.size}）：\n$rendered"))
    }

    /** create：git branch <name> [<from>]。 */
    private suspend fun create(name: String, createFrom: String?): ToolResult {
        val argv = listOf("branch", name) + (createFrom?.let { listOf(it) } ?: emptyList())
        val result = runner.run(argv)
        if (result.indicatesNotRepo()) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, notRepoGuidance())
        }
        if (!result.success) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure("git branch", result))
        }
        val baseNote = createFrom ?: "当前 HEAD"
        return ToolResult.ok("已创建分支 $name（基于 $baseNote）")
    }

    /** switch：git switch <name>。 */
    private suspend fun switch(name: String): ToolResult {
        val result = runner.run(listOf("switch", name))
        if (result.indicatesNotRepo()) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, notRepoGuidance())
        }
        if (!result.success) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure("git switch", result))
        }
        return ToolResult.ok("已切换到分支 $name")
    }

    /** delete：git branch -d <name>（安全删除——未合并的分支会被 git 拒绝）。 */
    private suspend fun delete(name: String): ToolResult {
        val result = runner.run(listOf("branch", "-d", name))
        if (result.indicatesNotRepo()) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, notRepoGuidance())
        }
        if (!result.success) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure("git branch", result))
        }
        return ToolResult.ok("已删除分支 $name")
    }

    // ── 校验与翻译 ───────────────────────────────────────────────────

    /** 分支名合法性：非空、无空白、不以短横线开头（防选项解析）。 */
    private fun validateBranchName(name: String): String? =
        if (name.isNotEmpty() && !name.startsWith("-") &&
            name.none { it.isWhitespace() }
        ) name else null

    private fun invalidNameResult(): ToolResult = ToolResult.invalid(
        "name",
        "name 必须是非空分支名：不能以 - 开头、不能包含空白字符"
    )

    private fun invalidFromResult(): ToolResult = ToolResult.invalid(
        "create_from",
        "create_from 必须是分支名或提交哈希：不能以 - 开头、不能包含空白字符"
    )

    /** [ahead 2, behind 1] 内文 → 中文（输入已去方括号）。 */
    private fun translateTrack(track: String): String {
        if (track.isEmpty()) return ""
        if (track == "gone") return "远端分支已删除"
        val ahead = Regex("ahead (\\d+)").find(track)?.groupValues?.get(1)
        val behind = Regex("behind (\\d+)").find(track)?.groupValues?.get(1)
        return when {
            ahead != null && behind != null -> "领先 $ahead / 落后 $behind"
            ahead != null -> "领先 $ahead"
            behind != null -> "落后 $behind"
            else -> ""
        }
    }

    /** git 失败时的统一呈现：stderr 末行 + 退出码。 */
    private fun renderFailure(command: String, result: GitCommandResult): String {
        val detail = result.stderr.lineSequence().lastOrNull { it.isNotBlank() }
            ?: result.stdout.trim().take(200)
        return "$command 失败（退出码 ${result.exitCode}）：$detail"
    }

    private companion object {
        const val ACTION_LIST = "list"
        const val ACTION_CREATE = "create"
        const val ACTION_SWITCH = "switch"
        val VALID_ACTIONS = listOf(ACTION_LIST, ACTION_CREATE, ACTION_SWITCH, "delete")
    }
}
