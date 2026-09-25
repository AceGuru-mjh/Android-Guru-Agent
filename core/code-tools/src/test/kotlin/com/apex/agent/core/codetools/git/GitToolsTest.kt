package com.apex.agent.core.codetools.git

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * code_git_* 五工具的行为契约测试（纯 JVM，脚本化 FakeGitRunner）。
 *
 * FakeGitRunner 按「参数前缀」路由预设结果（逐元素 startsWith，便于匹配
 * --format=... 这类长参数；先注册先匹配——具体前缀应先于宽泛前缀注册）；
 * 未命中路由时返回 defaultResult。
 */
class GitToolsTest {

    /** 记录每次调用并按前缀路由的假 runner。 */
    private class FakeGitRunner : GitCommandRunner {
        data class Call(val args: List<String>, val timeoutMs: Long)

        val calls = mutableListOf<Call>()
        private val routes = mutableListOf<Pair<List<String>, GitCommandResult>>()
        var defaultResult: GitCommandResult = GitCommandResult(0, "", "")

        /** 注册路由：argv 逐元素前缀命中即返回（先注册优先）。 */
        fun on(vararg prefix: String, result: GitCommandResult) {
            routes += prefix.toList() to result
        }

        override suspend fun run(args: List<String>, timeoutMs: Long): GitCommandResult {
            calls += Call(args, timeoutMs)
            return routes.firstOrNull { (prefix, _) ->
                args.size >= prefix.size &&
                    prefix.indices.all { i -> args[i].startsWith(prefix[i]) }
            }?.second ?: defaultResult
        }

        /** 第一个逐元素前缀命中的调用。 */
        fun call(vararg prefix: String): Call =
            calls.first { c ->
                c.args.size >= prefix.size &&
                    prefix.indices.all { i -> c.args[i].startsWith(prefix[i]) }
            }
    }

    private fun ok(stdout: String = "") = GitCommandResult(0, stdout, "")
    private fun notRepo() = GitCommandResult(
        128, "",
        "fatal: not a git repository (or any of the parent directories): .git"
    )

    // ── code_git_status ─────────────────────────────────────────────

    @Test
    fun `status argv runs porcelain v1 with branch and all untracked`() = runTest {
        val fake = FakeGitRunner()
        fake.on("status", result = ok("## main\n"))

        val out = GitStatusTool(fake).executeSafe("{}").render()
        assertTrue(out, out.contains("工作区干净"))
        assertEquals(
            listOf("status", "--porcelain=v1", "-b", "--untracked-files=all"),
            fake.calls.single().args
        )
    }

    @Test
    fun `status parses branch line and groups xy codes`() = runTest {
        val fake = FakeGitRunner()
        fake.on(
            "status",
            result = ok(
                "## main...origin/main [ahead 2, behind 1]\n" +
                    "M  core/B.kt\n" +
                    "A  new.txt\n" +
                    "D  gone.kt\n" +
                    "R  old.txt -> new.txt\n" +
                    " M src/A.kt\n" +
                    "?? notes.txt\n"
            )
        )

        val out = GitStatusTool(fake).executeSafe("{}").render()
        assertTrue(out, out.contains("分支: main（跟踪 origin/main，领先 2 / 落后 1）"))
        assertTrue(out, out.contains("已暂存（将进入下次提交）（4）："))
        assertTrue(out, out.contains("修改  core/B.kt"))
        assertTrue(out, out.contains("新增  new.txt"))
        assertTrue(out, out.contains("删除  gone.kt"))
        assertTrue(out, out.contains("重命名  old.txt -> new.txt"))
        assertTrue(out, out.contains("未暂存（1）："))
        assertTrue(out, out.contains("修改  src/A.kt"))
        assertTrue(out, out.contains("未跟踪（1）："))
        assertTrue(out, out.contains("notes.txt"))
        assertFalse(out, out.contains("工作区干净"))
    }

    @Test
    fun `status renders conflict group and unborn branch header`() = runTest {
        val fake = FakeGitRunner()
        fake.on("status", result = ok("## No commits yet on main\nUU merge.txt\n"))

        val out = GitStatusTool(fake).executeSafe("{}").render()
        assertTrue(out, out.contains("分支: main（尚无提交）"))
        assertTrue(out, out.contains("合并冲突（需先解决再提交）（1）："))
        assertTrue(out, out.contains("双方修改  merge.txt"))
    }

    @Test
    fun `status not a repo returns guidance error`() = runTest {
        val fake = FakeGitRunner()
        fake.on("status", result = notRepo())

        val result = GitStatusTool(fake).executeSafe("{}")
        assertFalse(result.render(), result.isSuccess)
        assertTrue(result.render(), result.render().contains("还不是 git 仓库"))
        assertTrue(result.render(), result.render().contains("code_git_commit"))
    }

    @Test
    fun `status output truncated at 6000 chars`() = runTest {
        val fake = FakeGitRunner()
        // 400 个未跟踪条目，每行约 20 字符 → 约 8000 字符输出
        val porcelain = buildString {
            appendLine("## main")
            repeat(400) { appendLine("?? file-number-$it.txt") }
        }
        fake.on("status", result = ok(porcelain))

        val out = GitStatusTool(fake).executeSafe("{}").render()
        assertTrue(out, out.contains("输出超长，已截断至 6000 字符"))
        assertTrue("length=${out.length}", out.length <= 6100)
    }

    // ── code_git_diff ───────────────────────────────────────────────

    @Test
    fun `diff unstaged maps to diff HEAD with pathspec`() = runTest {
        val fake = FakeGitRunner()
        fake.on("diff", "HEAD", result = ok(""))

        val out = GitDiffTool(fake).executeSafe("""{"path": "src"}""").render()
        assertTrue(out, out.contains("没有差异"))
        assertEquals(listOf("diff", "HEAD", "--", "src"), fake.calls.single().args)
    }

    @Test
    fun `diff staged maps to diff cached`() = runTest {
        val fake = FakeGitRunner()
        fake.on("diff", "--cached", result = ok("+hello\n-world\n"))

        val out = GitDiffTool(fake)
            .executeSafe("""{"staged": true, "path": "a.txt"}""").render()
        assertTrue(out, out.contains("+hello"))
        assertEquals(listOf("diff", "--cached", "--", "a.txt"), fake.calls.single().args)
    }

    @Test
    fun `diff truncates long diffs to max_lines with hint`() = runTest {
        val fake = FakeGitRunner()
        val longDiff = (1..20).joinToString("\n") { "+line $it" }
        fake.on("diff", "HEAD", result = ok(longDiff))

        val out = GitDiffTool(fake)
            .executeSafe("""{"max_lines": 5}""").render()
        assertTrue(out, out.contains("+line 5"))
        assertFalse(out, out.contains("+line 6"))
        assertTrue(out, out.contains("差异共 20 行，已截断至前 5 行"))
    }

    @Test
    fun `diff falls back to index diff when HEAD is unborn`() = runTest {
        val fake = FakeGitRunner()
        // 先注册具体前缀（diff HEAD），再注册宽泛前缀（diff）
        fake.on(
            "diff", "HEAD",
            result = GitCommandResult(128, "", "fatal: ambiguous argument 'HEAD': unknown revision")
        )
        fake.on("diff", result = ok("+staged only\n"))

        val out = GitDiffTool(fake).executeSafe("{}").render()
        assertTrue(out, out.contains("HEAD 尚不存在"))
        assertTrue(out, out.contains("+staged only"))
        // 第二次调用应是不带 HEAD 的降级 diff
        assertEquals(listOf("diff"), fake.calls[1].args)
    }

    @Test
    fun `diff rejects traversal and absolute pathspecs`() = runTest {
        val fake = FakeGitRunner()
        val tool = GitDiffTool(fake)

        val traversal = tool.executeSafe("""{"path": "../outside"}""")
        assertFalse(traversal.render(), traversal.isSuccess)
        assertTrue(traversal.render(), traversal.render().contains(".."))

        val absolute = tool.executeSafe("""{"path": "/etc/passwd"}""")
        assertFalse(absolute.render(), absolute.isSuccess)
        // 被拒绝时不应发起任何 git 调用
        assertTrue(fake.calls.isEmpty())
    }

    // ── code_git_log ────────────────────────────────────────────────

    @Test
    fun `log argv maps limit with 1-50 clamp`() = runTest {
        val fake = FakeGitRunner()
        fake.on("log", result = ok("a1b2c3d 2024-01-02 init\n"))

        val tool = GitLogTool(fake)
        tool.executeSafe("""{"limit": 100}""")
        assertEquals(
            listOf(
                "log", "--oneline", "--date=short",
                "--pretty=format:%h %ad %s", "-n", "50"
            ),
            fake.call("log").args
        )

        fake.calls.clear()
        tool.executeSafe("""{"limit": 0}""")
        assertEquals("1", fake.call("log").args[5])

        fake.calls.clear()
        tool.executeSafe("{}")
        assertEquals("10", fake.call("log").args[5])
    }

    @Test
    fun `log appends pathspec and renders raw lines`() = runTest {
        val fake = FakeGitRunner()
        fake.on("log", result = ok("a1b2c3d 2024-01-02 feat: init\n"))

        val out = GitLogTool(fake).executeSafe("""{"path": "src"}""").render()
        assertTrue(out, out.contains("a1b2c3d 2024-01-02 feat: init"))
        assertEquals(
            listOf(
                "log", "--oneline", "--date=short",
                "--pretty=format:%h %ad %s", "-n", "10", "--", "src"
            ),
            fake.call("log").args
        )
    }

    @Test
    fun `log empty output and unborn branch both mean no commits`() = runTest {
        val fake = FakeGitRunner()
        fake.on("log", result = ok(""))
        val tool = GitLogTool(fake)

        assertTrue(tool.executeSafe("{}").render().contains("暂无提交"))

        fake.calls.clear()
        fake.on(
            "log",
            result = GitCommandResult(
                128, "",
                "fatal: your current branch 'main' does not have any commits yet"
            )
        )
        assertTrue(tool.executeSafe("{}").render().contains("暂无提交"))
    }

    // ── code_git_commit ─────────────────────────────────────────────

    @Test
    fun `commit auto inits repo then adds and commits with -c identity`() = runTest {
        val fake = FakeGitRunner()
        fake.on("rev-parse", "--is-inside-work-tree", result = notRepo())
        fake.on("init", result = ok("Initialized empty Git repository"))
        fake.on("add", result = ok())
        fake.on("-c", result = ok())
        fake.on("rev-parse", "HEAD", result = ok("a1b2c3d4e5f6789abcdef\n"))
        // 摘要用的 status 不带 -b，stdout 只有变更行
        fake.on("status", result = ok(" M left.kt\n?? new.txt\n"))

        val out = GitCommitTool(fake)
            .executeSafe("""{"message": "chore: 首次提交"}""").render()
        assertTrue(out, out.contains("已提交 a1b2c3d: chore: 首次提交"))
        assertTrue(out, out.contains("剩余未提交变更：2 个文件"))

        // 调用顺序：探测 → init → add → commit → 取哈希 → 状态摘要
        val argvOrder = fake.calls.map { it.args.first() }
        assertEquals(listOf("rev-parse", "init", "add", "-c", "rev-parse", "status"), argvOrder)
        // 摘要 status 不带 -b（省一个字段解析）
        assertEquals(
            listOf("status", "--porcelain=v1", "--untracked-files=all"),
            fake.calls[5].args
        )
    }

    @Test
    fun `commit argv carries -c identity and no-verify quiet with 60s timeout`() = runTest {
        val fake = FakeGitRunner()
        fake.on("rev-parse", "--is-inside-work-tree", result = ok("true\n"))
        fake.on("add", result = ok())
        fake.on("-c", result = ok())
        fake.on("rev-parse", "HEAD", result = ok("deadbeef1234567\n"))
        fake.on("status", result = ok(""))

        GitCommitTool(fake).executeSafe(
            """{"message": "fix: x", "author_name": "张三", "author_email": "z@ex.com"}"""
        )

        val commitCall = fake.call("-c")
        assertEquals(
            listOf(
                "-c", "user.name=张三", "-c", "user.email=z@ex.com",
                "commit", "-m", "fix: x", "--no-verify", "--quiet"
            ),
            commitCall.args
        )
        assertEquals(60_000L, commitCall.timeoutMs)
        // add 也在 60s 预算内
        assertEquals(60_000L, fake.call("add").timeoutMs)
    }

    @Test
    fun `commit cleans message and uses first line as subject`() = runTest {
        val fake = FakeGitRunner()
        fake.on("rev-parse", "--is-inside-work-tree", result = ok("true\n"))
        fake.on("add", result = ok())
        fake.on("-c", result = ok())
        fake.on("rev-parse", "HEAD", result = ok("1234567890abcdef\n"))
        fake.on("status", result = ok(""))

        val out = GitCommitTool(fake).executeSafe(
            """{"message": "  fix: 登录崩溃\n\n正文第二段\n"}"""
        ).render()
        // -m 收到整体 trim 后的多行 message（argv：-c/-c/commit/-m/<message>/...）
        assertEquals(
            "fix: 登录崩溃\n\n正文第二段",
            fake.call("-c").args[6]
        )
        // 输出主题取首行，哈希取前 7 位
        assertTrue(out, out.contains("已提交 1234567: fix: 登录崩溃"))
    }

    @Test
    fun `commit nothing to commit is an informational success`() = runTest {
        val fake = FakeGitRunner()
        fake.on("rev-parse", "--is-inside-work-tree", result = ok("true\n"))
        fake.on("add", result = ok())
        fake.on(
            "-c",
            result = GitCommandResult(1, "", "nothing to commit, working tree clean")
        )

        val result = GitCommitTool(fake).executeSafe("""{"message": "noop"}""")
        assertTrue(result.render(), result.isSuccess)
        assertTrue(result.render(), result.render().contains("没有可提交的变更"))
    }

    @Test
    fun `commit rejects blank message and non-repo without add_all`() = runTest {
        val fake = FakeGitRunner()
        fake.on("rev-parse", "--is-inside-work-tree", result = notRepo())
        val tool = GitCommitTool(fake)

        val blank = tool.executeSafe("""{"message": "   "}""")
        assertFalse(blank.render(), blank.isSuccess)
        // 空白 message 在探测前就被拒绝——零 git 调用
        assertTrue(fake.calls.isEmpty())

        val noInit = tool.executeSafe("""{"message": "x", "add_all": false}""")
        assertFalse(noInit.render(), noInit.isSuccess)
        assertTrue(noInit.render(), noInit.render().contains("还不是 git 仓库"))
    }

    // ── code_git_branch ─────────────────────────────────────────────

    @Test
    fun `branch list marks current and translates track state`() = runTest {
        val fake = FakeGitRunner()
        fake.on(
            "branch", "--format",
            result = ok(
                "feature-x \n" +
                    "main [ahead 2]\n" +
                    "old [gone]\n"
            )
        )
        fake.on("branch", "--show-current", result = ok("main\n"))

        val out = GitBranchTool(fake).executeSafe("{}").render()
        assertTrue(out, out.contains("分支列表（3）："))
        assertTrue(out, out.contains("* main（领先 2）"))
        assertTrue(out, out.contains("  feature-x"))
        assertTrue(out, out.contains("  old（远端分支已删除）"))
    }

    @Test
    fun `branch list empty means unborn current branch`() = runTest {
        val fake = FakeGitRunner()
        fake.on("branch", "--format", result = ok(""))
        fake.on("branch", "--show-current", result = ok("main\n"))

        val out = GitBranchTool(fake).executeSafe("{}").render()
        assertTrue(out, out.contains("当前分支: main（尚无提交"))
    }

    @Test
    fun `branch create switch delete dispatch argv`() = runTest {
        val fake = FakeGitRunner()
        fake.on("branch", result = ok())
        fake.on("switch", result = ok())
        val tool = GitBranchTool(fake)

        val created = tool.executeSafe(
            """{"action": "create", "name": "feat", "create_from": "main"}"""
        ).render()
        assertTrue(created, created.contains("已创建分支 feat（基于 main）"))
        assertEquals(listOf("branch", "feat", "main"), fake.call("branch", "feat").args)

        val switched = tool.executeSafe(
            """{"action": "switch", "name": "dev"}"""
        ).render()
        assertTrue(switched, switched.contains("已切换到分支 dev"))
        assertEquals(listOf("switch", "dev"), fake.call("switch").args)

        val deleted = tool.executeSafe(
            """{"action": "delete", "name": "old"}"""
        ).render()
        assertTrue(deleted, deleted.contains("已删除分支 old"))
        assertEquals(listOf("branch", "-d", "old"), fake.call("branch", "-d").args)
    }

    @Test
    fun `branch validates action enum and name rules`() = runTest {
        val fake = FakeGitRunner()
        val tool = GitBranchTool(fake)

        val badAction = tool.executeSafe("""{"action": "rebase"}""")
        assertFalse(badAction.render(), badAction.isSuccess)
        assertTrue(badAction.render(), badAction.render().contains("list/create/switch/delete"))

        val missingName = tool.executeSafe("""{"action": "create"}""")
        assertFalse(missingName.render(), missingName.isSuccess)

        val dashName = tool.executeSafe("""{"action": "switch", "name": "--force"}""")
        assertFalse(dashName.render(), dashName.isSuccess)

        val spaceName = tool.executeSafe("""{"action": "create", "name": "a b"}""")
        assertFalse(spaceName.render(), spaceName.isSuccess)
        // 校验失败零 git 调用
        assertTrue(fake.calls.isEmpty())
    }

    // ── 聚合入口 ────────────────────────────────────────────────────

    @Test
    fun `GitTools all registers the five git tools in order`() {
        val ids = GitTools.all(FakeGitRunner()).map { it.id }
        assertEquals(
            listOf("code_git_status", "code_git_diff", "code_git_log", "code_git_commit", "code_git_branch"),
            ids
        )
    }

    @Test
    fun `metadata tiers mark reads read-only and writes mutating`() {
        val tools = GitTools.all(FakeGitRunner()).associateBy { it.id }
        for (id in listOf("code_git_status", "code_git_diff", "code_git_log")) {
            assertTrue("$id 应为 readOnly", tools.getValue(id).metadata.annotations.readOnlyHint)
        }
        for (id in listOf("code_git_commit", "code_git_branch")) {
            assertFalse("$id 应为写类", tools.getValue(id).metadata.annotations.readOnlyHint)
        }
    }

    // ── indicatesNotRepo 契约 ───────────────────────────────────────

    @Test
    fun `indicatesNotRepo matches 128 plus marker case-insensitively`() {
        assertTrue(
            GitCommandResult(128, "", "fatal: Not a git repository (or any parent directory)")
                .indicatesNotRepo()
        )
        assertFalse(GitCommandResult(128, "", "some other fatal").indicatesNotRepo())
        assertFalse(GitCommandResult(1, "", "not a git repository").indicatesNotRepo())
        assertFalse(GitCommandResult(-1, "", "Ubuntu 环境未安装").indicatesNotRepo())
    }
}
