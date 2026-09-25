package com.apex.agent.core.codetools.git

import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema

/**
 * # code_git_status — 工作区状态一览（经 PRoot Ubuntu 沙箱执行）
 *
 * `git status --porcelain=v1 -b --untracked-files=all` 的友好化渲染：
 *
 * - 首行「分支: xxx（领先 N / 落后 M）」——解析 ## 分支行与 upstream 跟踪
 *   状态（ahead/behind/gone）；
 * - 变更按三组呈现（已暂存 / 未暂存 / 未跟踪），XY 状态码翻译成人话
 *   （修改/新增/删除/重命名/复制/类型变更），重命名显示 old -> new；
 * - 存在合并冲突（U 系列或 AA/DD 状态）时单独成组，优先提醒；
 * - 无任何变更 → 「工作区干净」；
 * - 非 git 仓库 → isError 引导文本（提示先初始化或安装 Ubuntu 环境）。
 */
class GitStatusTool(
    private val runner: GitCommandRunner
) : BaseTool(
    id = "code_git_status",
    name = "Code Git Status",
    description = """
        Show the git status of the active coding workspace: current branch,
        ahead/behind vs upstream, and all changes grouped as staged /
        unstaged / untracked (porcelain status codes translated to plain
        descriptions). No arguments needed. Returns "工作区干净" when there
        is nothing to commit.
    """.trimIndent(),
    declaredSchema = toolSchema { }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("code")
        tag("git")
        tag("status")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        // 无参数工具：多余实参按 schema 校验层「宽容未知键」的族内惯例忽略
        val result = runner.run(
            listOf("status", "--porcelain=v1", "-b", "--untracked-files=all")
        )
        if (result.indicatesNotRepo()) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, notRepoGuidance())
        }
        if (!result.success) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, renderFailure(result))
        }
        return ToolResult.ok(boundedToolOutput(render(result.stdout)))
    }

    // ── 渲染 ─────────────────────────────────────────────────────────

    /** 把 porcelain 输出翻译成分组文本（包内可见便于单测直测解析器）。 */
    internal fun render(porcelain: String): String {
        val lines = porcelain.lines().filter { it.isNotBlank() }
        val branchLine = lines.firstOrNull { it.startsWith("## ") }
        val entries = lines.filterNot { it.startsWith("## ") }

        val header = renderBranchHeader(branchLine)
        if (entries.isEmpty()) {
            return "$header\n工作区干净"
        }

        val staged = mutableListOf<String>()
        val unstaged = mutableListOf<String>()
        val untracked = mutableListOf<String>()
        val conflicts = mutableListOf<String>()

        for (line in entries) {
            if (line.length < 4) continue
            val x = line[0]
            val y = line[1]
            val path = line.substring(3)
            when {
                x == '?' && y == '?' -> untracked += path
                x == '!' && y == '!' -> Unit // 已忽略条目：默认输出不含，防御性跳过
                isUnmerged(x, y) -> conflicts += "${translateConflict(x, y)}  $path"
                x != ' ' -> staged += "${translateCode(x)}  $path"
                y != ' ' -> unstaged += "${translateCode(y)}  $path"
            }
        }

        return buildString {
            append(header)
            appendGroup("合并冲突（需先解决再提交）", conflicts)
            appendGroup("已暂存（将进入下次提交）", staged)
            appendGroup("未暂存", unstaged)
            appendGroup("未跟踪", untracked)
        }
    }

    /** 解析 ## 分支行：分支名、upstream、ahead/behind、分离头、unborn 分支。 */
    private fun renderBranchHeader(branchLine: String?): String {
        if (branchLine == null) return "分支:（未知——git 未返回分支信息）"
        val body = branchLine.removePrefix("## ").trim()

        // 分离头指针：## HEAD (no branch)
        if (body.startsWith("HEAD (no branch)")) {
            return "分支:（分离 HEAD——不在任何分支上，可用 code_git_branch 切回分支）"
        }

        // unborn 分支：## No commits yet on main[...origin/main]
        val noCommits = body.startsWith("No commits yet on ")
        val effective = if (noCommits) body.removePrefix("No commits yet on ") else body

        // 拆出方括号跟踪状态：[ahead 2, behind 1] / [gone]
        var main = effective
        var trackNote = ""
        val bracketStart = effective.indexOf(" [")
        if (bracketStart >= 0 && effective.endsWith("]")) {
            main = effective.substring(0, bracketStart)
            trackNote = translateTrack(effective.substring(bracketStart + 2, effective.length - 1))
        }

        // main 形如 branch...origin/remote（... 之后是 upstream 简写）
        val branch = main.substringBefore("...")
        val upstream = main.substringAfter("...", "").ifEmpty { null }

        val notes = buildList {
            if (noCommits) add("尚无提交")
            if (!noCommits && upstream != null) add("跟踪 $upstream")
            if (trackNote.isNotEmpty()) add(trackNote)
        }
        return if (notes.isEmpty()) "分支: $branch" else "分支: $branch（${notes.joinToString("，")}）"
    }

    /** [ahead 2, behind 1] / [ahead 2] / [behind 1] / [gone] → 中文描述。 */
    private fun translateTrack(bracket: String): String {
        if (bracket == "gone") return "远端分支已删除"
        val ahead = Regex("ahead (\\d+)").find(bracket)?.groupValues?.get(1)
        val behind = Regex("behind (\\d+)").find(bracket)?.groupValues?.get(1)
        return when {
            ahead != null && behind != null -> "领先 $ahead / 落后 $behind"
            ahead != null -> "领先 $ahead"
            behind != null -> "落后 $behind"
            else -> ""
        }
    }

    /** porcelain 单字符状态码 → 中文（X=暂存区侧，Y=工作区侧）。 */
    private fun translateCode(code: Char): String = when (code) {
        'M' -> "修改"
        'A' -> "新增"
        'D' -> "删除"
        'R' -> "重命名"
        'C' -> "复制"
        'T' -> "类型变更"
        else -> "变更"
    }

    /** 合并冲突的 XY 组合 → 中文描述（porcelain v1 的七种 unmerged 组合）。 */
    private fun translateConflict(x: Char, y: Char): String = when {
        x == 'U' && y == 'U' -> "双方修改"
        x == 'A' && y == 'A' -> "双方新增"
        x == 'D' && y == 'D' -> "双方删除"
        x == 'A' && y == 'U' -> "我方新增/对方修改"
        x == 'U' && y == 'D' -> "我方修改/对方删除"
        x == 'D' && y == 'U' -> "我方删除/对方修改"
        x == 'U' && y == 'A' -> "我方修改/对方新增"
        else -> "冲突"
    }

    /** unmerged 状态判定。 */
    private fun isUnmerged(x: Char, y: Char): Boolean =
        (x == 'U' || y == 'U') || (x == y && (x == 'A' || x == 'D'))

    private fun StringBuilder.appendGroup(title: String, items: List<String>) {
        if (items.isEmpty()) return
        append("\n\n").append(title).append("（").append(items.size).append("）：")
        for (item in items) {
            append("\n- ").append(item)
        }
    }

    /** git 失败时的统一呈现：stderr 末行（最有信息量）+ 退出码。 */
    private fun renderFailure(result: GitCommandResult): String {
        val detail = result.stderr.lineSequence().lastOrNull { it.isNotBlank() }
            ?: result.stdout.trim().take(200)
        return "git status 失败（退出码 ${result.exitCode}）：$detail"
    }
}
