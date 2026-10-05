package com.apex.agent.ui.screen.code

import com.apex.agent.core.codetools.git.GitCommandResult
import com.apex.agent.core.codetools.git.GitCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * # CodeGitPanelController 单测 —— v6 Git 工作区（右上角）
 *
 * 用户规格：「显示变动的文件和 git 功能」。用脚本化 GitCommandRunner
 * 假件驱动（仓内惯例：手写 fake，不用 mock 框架）：
 *
 * - status 解析：分支行（含 ahead/behind 后缀）+ 变更行（含 rename）+
 *   非仓库识别（exit 128 + "not a git repository"）；
 * - commitAll：add -A → -c 身份注入 commit（不动用户全局配置），
 *   成功后自动刷新；失败带回 stderr 摘要；
 * - loadDiff：按需拉取 + 二次点击走缓存（不重复执行）；
 * - 空提交/运行中重复提交被拒绝（幂等护栏）。
 *
 * 调度器注入（StandardTestDispatcher）：runTest 虚拟时间推进，无真实
 * IO 线程等待。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodeGitPanelControllerTest {

    /** 脚本化 runner：命令序列回放 + 调用记录。 */
    private class FakeGitRunner(
        private val responses: List<Pair<String, String>> = emptyList()
    ) : GitCommandRunner {
        val executed = mutableListOf<List<String>>()
        private var index = 0

        override suspend fun run(
            args: List<String>,
            timeoutMs: Long
        ): GitCommandResult {
            executed += args
            val (out, err) = responses.getOrElse(index++) { "" to "" }
            return GitCommandResult(
                exitCode = if (err.isBlank()) 0 else 128,
                stdout = out,
                stderr = err
            )
        }
    }

    private fun controller(
        scope: TestScope,
        responses: List<Pair<String, String>>
    ): Pair<CodeGitPanelController, FakeGitRunner> {
        val runner = FakeGitRunner(responses)
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val controller = CodeGitPanelController(
            gitRunner = runner,
            scope = CoroutineScope(dispatcher),
            ioDispatcher = dispatcher
        )
        return controller to runner
    }

    private val porcelain = """
        ## main...origin/main [ahead 2]
         M app/src/Main.kt
        A  new_file.kt
        D  deleted.kt
        ?? untracked.txt
        R  renamed_old.kt -> renamed_new.kt
        UU conflicted.kt
    """.trimIndent()

    @Test
    fun `refresh parses branch and changes from porcelain output`() = runTest {
        val (controller, runner) = controller(this, listOf(porcelain to ""))
        controller.refresh()
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state.isRepo)
        assertEquals("main", state.branch)
        assertEquals(6, state.changes.size)

        val first = state.changes.first()
        assertEquals("M", first.badge)
        assertEquals("app/src/Main.kt", first.path)

        // rename 取新路径展示
        val renamed = state.changes.first { it.path == "renamed_new.kt" }
        assertEquals("R", renamed.badge)
        // 未跟踪 → N 徽标
        assertEquals("N", state.changes.first { it.path == "untracked.txt" }.badge)
        // 冲突 U 徽标
        assertEquals("U", state.changes.first { it.path == "conflicted.kt" }.badge)

        assertEquals(listOf(listOf("status", "--porcelain=v1", "-b")), runner.executed)
    }

    @Test
    fun `not a repository shows the init guidance state`() = runTest {
        val (controller, _) = controller(
            this,
            listOf("" to "fatal: not a git repository (or any of the parent directories): .git")
        )
        controller.refresh()
        advanceUntilIdle()

        val state = controller.state.value
        assertFalse(state.isRepo)
        assertNull(state.branch)
        assertTrue(state.changes.isEmpty())
    }

    @Test
    fun `commitAll stages everything and injects the agent identity`() = runTest {
        val (controller, runner) = controller(
            this,
            listOf(
                porcelain to "",                       // refresh（打开面板）
                "" to "",                              // add -A
                "[main abc1234] feat: x\n 1 file changed" to ""  // commit
            )
        )
        controller.refresh()
        advanceUntilIdle()

        controller.commitAll("feat: x")
        advanceUntilIdle()

        // add -A + commit（-c 身份注入、--no-verify、-m 消息）
        // 执行序列：status(打开面板) → add -A → commit → status(成功后自动刷新)
        val commitCall = runner.executed[2]
        assertEquals(listOf("-c", "user.name=Guru Agent", "-c", "user.email=agent@guru.local", "commit", "-m", "feat: x", "--no-verify"), commitCall)
        assertTrue(runner.executed.any { it == listOf("add", "-A") })
        // 成功提交 → 自动刷新一次现场（回执后状态即最新）
        assertEquals(4, runner.executed.size)

        val state = controller.state.value
        assertFalse(state.committing)
        assertEquals("feat: x", state.message)
        assertFalse(state.messageIsError)
    }

    @Test
    fun `commit failure surfaces stderr without throwing`() = runTest {
        val (controller, runner) = controller(
            this,
            listOf(
                "" to "",                                  // add -A
                "" to "nothing to commit, working tree clean" // commit 失败
            )
        )
        controller.commitAll("msg")
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state.messageIsError)
        assertTrue(state.message.orEmpty().contains("nothing to commit"))
        assertFalse(state.committing)
    }

    @Test
    fun `blank message and re-entrant commits are rejected`() = runTest {
        val (controller, runner) = controller(this, emptyList())
        controller.commitAll("   ")
        advanceUntilIdle()
        assertEquals(0, runner.executed.size)

        controller.commitAll("valid")
        advanceUntilIdle()
        // 空脚本 = 全部成功：add + commit + 自动刷新 status = 3 次
        assertEquals(3, runner.executed.size)

        // 再来一次（state.committing 已复位）→ 允许，行为照常
        controller.commitAll("again")
        advanceUntilIdle()
        assertEquals(6, runner.executed.size)
    }

    @Test
    fun `loadDiff fetches once and caches by path`() = runTest {
        val (controller, runner) = controller(
            this,
            listOf(
                "+diff hunk 1" to "",
                "+diff hunk 1" to "" // 若未缓存会再执行一次
            )
        )
        controller.loadDiff("Main.kt")
        advanceUntilIdle()
        assertEquals(1, runner.executed.size)
        assertEquals("+diff hunk 1", controller.state.value.diffs["Main.kt"])

        // 二次加载走缓存：不再新增执行
        controller.loadDiff("Main.kt")
        advanceUntilIdle()
        assertEquals(1, runner.executed.size)
    }
}
