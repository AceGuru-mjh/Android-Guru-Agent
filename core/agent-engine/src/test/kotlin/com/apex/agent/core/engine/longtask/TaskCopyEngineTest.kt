package com.apex.agent.core.engine.longtask

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [TaskCopyEngine] 测试。
 *
 * 覆盖：copy 全字段语义（新 id / 血缘 / 归零 / 副本编号 / 选项开关 /
 * 档位与工作区覆盖 / 源计数维护 / 不存在的源）、forkChain 链回溯与防环、
 * buildRelaunchPrompt 的段落组成与 FAILED 收尾指令、buildContextDigest
 * 的开关矩阵与文件清单聚合截断、prompt 总长 3000 截断保头。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TaskCopyEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LongTaskStore
    private lateinit var engine: TaskCopyEngine

    @Before
    fun setup() {
        store = LongTaskStore(tmp.newFolder("longtask"))
        engine = TaskCopyEngine(store)
    }

    /** 已完结的源记录（复制语义测试的基准形态）。 */
    private fun sourceRecord(
        id: String = "task-source-0001",
        copyCount: Int = 2,
        status: LongTaskStatus = LongTaskStatus.FAILED,
        goal: String = "把 Volley 迁移到 OkHttp 并补齐单元测试",
        checkpoints: List<LongTaskCheckpoint> = listOf(
            LongTaskCheckpoint(
                id = "cp-1", atIteration = 5, timestamp = 3000L,
                toolCallCount = 8, filesTouchedCount = 1,
                recentExchange = listOf("用户: 把 Volley 迁移到 OkHttp", "工具 code_edit ✓"),
                todoDigest = listOf("☐ 迁移 Client")
            )
        )
    ) = LongTaskRecord(
        id = id,
        title = "重构网络层",
        goal = goal,
        workspaceId = "ws-alpha-1a2b3c",
        workspaceName = "Alpha 项目",
        thinkingLevel = "DEEP",
        agentMode = "BUILD",
        status = status,
        createdAt = 1000L,
        updatedAt = 9000L,
        endedAt = 9000L,
        iterations = 22,
        toolCalls = 35,
        durationMs = 480_000L,
        filesTouched = listOf("net/Api.kt", "net/Client.kt", "net/Repo.kt"),
        toolsUsed = mapOf("code_edit" to 20, "code_check" to 15),
        errorMessage = "两个测试未通过",
        summary = "迁移完成 80%，余下两个集成测试未过。",
        checkpoints = checkpoints,
        todoSnapshot = listOf("☑ 迁移 Client", "☐ 修集成测试"),
        parentTaskId = null,
        copyCount = copyCount,
        isTemplate = false,
        tags = listOf("refactor")
    )

    // ═══ copy：核心语义 ════════════════════════════════════════

    @Test
    fun `copy derives a fresh running record with lineage and zeroed stats`() = runTest {
        val source = sourceRecord()
        store.upsert(source)

        val result = engine.copy(source.id, LongTaskCopyOptions())
        assertTrue(result.isSuccess)
        val copy = result.getOrThrow()

        // 新身份 + 血缘
        assertFalse(copy.id.isEmpty())
        assertEquals(source.id, copy.parentTaskId)
        // 归零（新运行的起点）
        assertEquals(LongTaskStatus.RUNNING, copy.status)
        assertNull(copy.endedAt)
        assertEquals(0, copy.iterations)
        assertEquals(0, copy.toolCalls)
        assertEquals(0L, copy.durationMs)
        assertEquals(0, copy.copyCount)
        assertNull(copy.errorMessage)
        assertNull(copy.summary)
        assertTrue(copy.toolsUsed.isEmpty())
        // 沿用
        assertEquals(source.goal, copy.goal)
        assertEquals(source.agentMode, copy.agentMode)
        assertEquals(source.workspaceId, copy.workspaceId)
        assertEquals(source.thinkingLevel, copy.thinkingLevel)
        assertEquals(source.tags, copy.tags)
        assertFalse(copy.isTemplate)
        // 默认开关：todos / files 复制，checkpoints 不复制
        assertEquals(source.todoSnapshot, copy.todoSnapshot)
        assertEquals(source.filesTouched, copy.filesTouched)
        assertTrue(copy.checkpoints.isEmpty())

        // 副本标题编号 = 源 copyCount + 1
        assertEquals("重构网络层（副本 3）", copy.title)

        // 源记录计数 +1；副本已入库
        assertEquals(3, store.get(source.id)!!.copyCount)
        assertEquals(copy, store.get(copy.id))
    }

    @Test
    fun `copy with new title and thinking override`() = runTest {
        val source = sourceRecord()
        store.upsert(source)
        val copy = engine.copy(
            source.id,
            LongTaskCopyOptions(newTitle = "自定义标题", thinkingLevelOverride = "ULTRACODE")
        ).getOrThrow()

        assertEquals("自定义标题", copy.title)
        assertEquals("ULTRACODE", copy.thinkingLevel)
        // 源档位不动
        assertEquals("DEEP", store.get(source.id)!!.thinkingLevel)
    }

    @Test
    fun `copy to another workspace keeps source display name by design`() = runTest {
        val source = sourceRecord()
        store.upsert(source)
        val copy = engine.copy(
            source.id,
            LongTaskCopyOptions(targetWorkspaceId = "ws-beta-4d5e6f")
        ).getOrThrow()

        assertEquals("ws-beta-4d5e6f", copy.workspaceId)
        // 设计取舍：选项只带 id，显示名沿用源（UI 以 id 解析实时名）
        assertEquals("Alpha 项目", copy.workspaceName)
    }

    @Test
    fun `copy switches control context material in the record`() = runTest {
        val source = sourceRecord()
        store.upsert(source)

        val stripped = engine.copy(
            source.id,
            LongTaskCopyOptions(includeTodos = false, includeFilesList = false)
        ).getOrThrow()
        assertTrue(stripped.todoSnapshot.isEmpty())
        assertTrue(stripped.filesTouched.isEmpty())

        val audited = engine.copy(
            source.id,
            LongTaskCopyOptions(includeCheckpoints = true)
        ).getOrThrow()
        assertEquals(source.checkpoints, audited.checkpoints)
    }

    @Test
    fun `copy of missing source fails with no such element`() = runTest {
        val result = engine.copy("task-ghost", LongTaskCopyOptions())
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue("应失败于 NoSuchElementException，实际 $error", error is NoSuchElementException)
        // 失败路径不产生任何记录
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `copy of a template yields a non template record`() = runTest {
        val template = LongTaskTemplates.instantiate(
            LongTaskTemplates.byKey("refactor")!!, "ws-alpha-1a2b3c", "Alpha 项目"
        )
        store.upsert(template)
        val copy = engine.copy(template.id, LongTaskCopyOptions()).getOrThrow()
        assertFalse(copy.isTemplate)
        assertEquals(template.id, copy.parentTaskId)
    }

    @Test
    fun `second copy of same source gets next number`() = runTest {
        val source = sourceRecord(copyCount = 0)
        store.upsert(source)
        val first = engine.copy(source.id, LongTaskCopyOptions()).getOrThrow()
        val second = engine.copy(source.id, LongTaskCopyOptions()).getOrThrow()
        assertEquals("重构网络层（副本 1）", first.title)
        assertEquals("重构网络层（副本 2）", second.title)
        assertEquals(2, store.get(source.id)!!.copyCount)
    }

    // ═══ forkChain ═════════════════════════════════════════════

    @Test
    fun `forkChain walks ancestry from oldest to newest`() = runTest {
        val root = sourceRecord(id = "task-root", copyCount = 0)
        store.upsert(root)
        val mid = engine.copy(root.id, LongTaskCopyOptions(includeCheckpoints = true)).getOrThrow()
        val leaf = engine.copy(mid.id, LongTaskCopyOptions()).getOrThrow()

        val chain = engine.forkChain(leaf.id)
        assertEquals(listOf("task-root", mid.id, leaf.id), chain.map { it.id })
        // 中间节点与叶子各自视角
        assertEquals(listOf("task-root", mid.id), engine.forkChain(mid.id).map { it.id })
        assertEquals(listOf("task-root"), engine.forkChain("task-root").map { it.id })
    }

    @Test
    fun `forkChain of missing id returns empty`() = runTest {
        assertTrue(engine.forkChain("task-none").isEmpty())
    }

    @Test
    fun `forkChain survives a fabricated cycle by truncating`() = runTest {
        // 手工构造环：X 的 parent 是 Y，Y 的 parent 是 X（正常流程造不出来，
        // 防的是记录被手工编辑后的脏数据）
        val x = sourceRecord(id = "task-x").copy(parentTaskId = "task-y")
        val y = sourceRecord(id = "task-y").copy(parentTaskId = "task-x")
        store.upsert(x)
        store.upsert(y)

        val chain = engine.forkChain("task-x")
        // 环被截断：沿 x → y 停住（x 重复出现即终止），不死循环
        assertEquals(listOf("task-y", "task-x"), chain.map { it.id })
    }

    // ═══ buildRelaunchPrompt / buildContextDigest ══════════════

    @Test
    fun `relaunch prompt for failed source warns about errors and leftovers`() = runTest {
        val source = sourceRecord(status = LongTaskStatus.FAILED)
        val prompt = engine.buildRelaunchPrompt(source, LongTaskCopyOptions())

        assertTrue(prompt.startsWith("重跑任务：重构网络层"))
        assertTrue(prompt.contains("把 Volley 迁移到 OkHttp 并补齐单元测试"))
        assertTrue(prompt.contains("## 上次运行上下文"))
        // 概览行
        assertTrue(prompt.contains("FAILED · 22 次迭代 · 35 次工具调用 · 8分0秒"))
        // 对话摘要（来自检查点 recentExchange）+ 上次结论（summary）
        assertTrue(prompt.contains("用户: 把 Volley 迁移到 OkHttp"))
        assertTrue(prompt.contains("上次结论：迁移完成 80%"))
        // todo 与文件清单
        assertTrue(prompt.contains("待办快照"))
        assertTrue(prompt.contains("☐ 修集成测试"))
        assertTrue(prompt.contains("涉及文件"))
        assertTrue(prompt.contains("net/Api.kt"))
        // FAILED 收尾指令
        assertTrue(prompt.contains("注意上次的错误与未竟事项"))
    }

    @Test
    fun `relaunch prompt for completed source asks to redo`() = runTest {
        val source = sourceRecord(status = LongTaskStatus.COMPLETED)
        val prompt = engine.buildRelaunchPrompt(source, LongTaskCopyOptions())
        assertTrue(prompt.contains("请重新完成该任务"))
        assertFalse(prompt.contains("注意上次的错误与未竟事项"))
    }

    @Test
    fun `prompt sections follow conversation switches`() = runTest {
        val source = sourceRecord()
        val withoutConversation = engine.buildRelaunchPrompt(
            source, LongTaskCopyOptions(includeConversation = false)
        )
        assertTrue(withoutConversation.contains("待办快照"))
        assertTrue(withoutConversation.contains("net/Api.kt"))
        assertFalse(withoutConversation.contains("用户: 把 Volley 迁移到 OkHttp"))
        assertFalse(withoutConversation.contains("上次结论"))
        // 概览行不依赖对话开关（只要摘要非空就有）
        assertTrue(withoutConversation.contains("次迭代"))

        val withoutAnything = engine.buildRelaunchPrompt(
            source,
            LongTaskCopyOptions(includeConversation = false, includeTodos = false, includeFilesList = false)
        )
        // 摘要为空 → 上下文段整体省略
        assertFalse(withoutAnything.contains("## 上次运行上下文"))
    }

    @Test
    fun `prompt from a default copy relies on switches for context`() = runTest {
        val source = sourceRecord()
        store.upsert(source)
        // 默认 copy：summary 清空 + checkpoints 不复制 → 副本的对话素材为空
        val copy = engine.copy(source.id, LongTaskCopyOptions()).getOrThrow()
        val promptFromCopy = engine.buildRelaunchPrompt(copy, LongTaskCopyOptions())
        assertTrue(promptFromCopy.contains("重跑任务：重构网络层（副本 3）"))
        assertTrue(promptFromCopy.contains("待办快照"))
        assertTrue(promptFromCopy.contains("net/Api.kt"))
        assertFalse(promptFromCopy.contains("上次结论"))

        // 带 checkpoints 的 copy：对话摘要随检查点进入副本
        val auditedCopy = engine.copy(source.id, LongTaskCopyOptions(includeCheckpoints = true)).getOrThrow()
        val promptFromAudited = engine.buildRelaunchPrompt(auditedCopy, LongTaskCopyOptions())
        assertTrue(promptFromAudited.contains("用户: 把 Volley 迁移到 OkHttp"))
    }

    @Test
    fun `prompt is capped at three thousand chars keeping the head`() = runTest {
        val source = sourceRecord().copy(
            goal = "G".repeat(4000),
            filesTouched = (1..200).map { "src/very/long/path/file-$it.kt" }
        )
        val prompt = engine.buildRelaunchPrompt(source, LongTaskCopyOptions())
        assertEquals(3000, prompt.length)
        // 保头：标题与 goal 开头必须在
        assertTrue(prompt.startsWith("重跑任务：重构网络层"))
        assertTrue(prompt.contains("GGGG"))
    }

    // ═══ buildContextDigest ════════════════════════════════════

    @Test
    fun `digest aggregates long file lists with a count line`() {
        val source = sourceRecord().copy(
            filesTouched = (1..45).map { "src/f$it.kt" },
            todoSnapshot = emptyList()
        )
        val digest = engine.buildContextDigest(source, LongTaskCopyOptions())
        assertTrue(digest.contains("src/f1.kt"))
        assertTrue(digest.contains("src/f30.kt"))
        assertFalse("第 31 个文件不应逐行列出", digest.contains("src/f31.kt"))
        assertTrue(digest.contains("…等共 45 个文件"))
    }

    @Test
    fun `digest is empty when all switches off or no material`() {
        val rich = sourceRecord()
        assertTrue(engine.buildContextDigest(rich, LongTaskCopyOptions(
            includeConversation = false, includeTodos = false, includeFilesList = false
        )).isEmpty())

        val bare = sourceRecord().copy(
            filesTouched = emptyList(), todoSnapshot = emptyList(),
            checkpoints = emptyList(), summary = null
        )
        assertTrue(engine.buildContextDigest(bare, LongTaskCopyOptions()).isEmpty())
    }

    @Test
    fun `digest formats durations in chinese units`() {
        val source = sourceRecord().copy(durationMs = 372_500L)
        val digest = engine.buildContextDigest(source, LongTaskCopyOptions())
        assertTrue(digest.contains("6分12秒"))
    }

    // ═══ 检查点续跑（buildResumePrompt）═════════════════════════

    @Test
    fun `resume prompt builds from last checkpoint by default`() {
        val source = sourceRecord()
        val prompt = engine.buildResumePrompt(source)

        assertTrue(prompt.startsWith("续跑任务：重构网络层"))
        assertTrue(prompt.contains("把 Volley 迁移到 OkHttp 并补齐单元测试"))
        assertTrue(prompt.contains("## 进度快照（第 5 轮检查点）"))
        // 进度行：检查点计数（atIteration=5, toolCallCount=8, filesTouchedCount=1）
        assertTrue(prompt.contains("已进行：5 次迭代 · 8 次工具调用 · 已触碰 1 个文件"))
        // 最近进展（recentExchange）与待办状态（todoDigest）
        assertTrue(prompt.contains("最近进展"))
        assertTrue(prompt.contains("用户: 把 Volley 迁移到 OkHttp"))
        assertTrue(prompt.contains("待办状态"))
        assertTrue(prompt.contains("☐ 迁移 Client"))
        // 续跑收尾指令（区别于重跑）
        assertTrue(prompt.contains("已完成项不要重做"))
    }

    @Test
    fun `resume prompt honors explicit checkpoint id`() {
        val source = sourceRecord(
            checkpoints = listOf(
                LongTaskCheckpoint("cp-a", 5, 3000L, 8, 1, listOf("用户: 第一步"), listOf("☐ A")),
                LongTaskCheckpoint("cp-b", 10, 6000L, 20, 3, listOf("用户: 第二步"), listOf("☑ A", "☐ B"))
            )
        )
        val prompt = engine.buildResumePrompt(source, checkpointId = "cp-a")
        assertTrue(prompt.contains("第 5 轮检查点"))
        assertTrue(prompt.contains("已进行：5 次迭代 · 8 次工具调用"))
        assertTrue(prompt.contains("用户: 第一步"))
        // 不应混入 cp-b 的素材
        assertTrue(!prompt.contains("用户: 第二步"))
    }

    @Test
    fun `resume prompt empty when record has no checkpoints`() {
        val bare = sourceRecord().copy(checkpoints = emptyList())
        assertTrue(engine.buildResumePrompt(bare).isEmpty())
    }

    @Test
    fun `resume prompt unknown checkpoint id falls back to empty`() {
        val source = sourceRecord()
        assertTrue(engine.buildResumePrompt(source, checkpointId = "no-such-cp").isEmpty())
    }

    @Test
    fun `resume prompt truncates to cap keeping head`() {
        val huge = sourceRecord(
            goal = "巨".repeat(5000),
            checkpoints = listOf(
                LongTaskCheckpoint(
                    "cp-big", 5, 1L, 8, 1,
                    recentExchange = (1..40).map { "行$it " + "长".repeat(100) },
                    todoDigest = (1..40).map { "todo$it " + "长".repeat(100) }
                )
            )
        )
        val prompt = engine.buildResumePrompt(huge)
        assertEquals(3000, prompt.length)
        assertTrue(prompt.startsWith("续跑任务："))
    }
}
