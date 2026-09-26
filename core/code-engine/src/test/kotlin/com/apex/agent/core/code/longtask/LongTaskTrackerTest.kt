package com.apex.agent.core.code.longtask

import com.apex.agent.core.engine.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [LongTaskTracker] 事件流聚合测试。
 *
 * 以脚本化 [AgentEvent] 序列（直接构造 data class）驱动 Tracker：
 * 短任务不入库、长任务全字段入库、检查点双触发（5 迭代 / 60 秒）、
 * filesTouched 的 path 提取（含失败排除 / 非文件工具排除 / 坏 JSON 防御）、
 * 连续 beginRun 自动 ABORTED 收尾、FAILED errorMessage 透传、summary 的
 * 尾环缓冲推导与显式覆盖、todo 快照、goal/title 截断。
 *
 * 时钟全部注入假钟（不依赖墙钟，检查点时间分支可确定驱动）；
 * 持久化断言用真实 [LongTaskStore]（TemporaryFolder）+ StandardTestDispatcher
 * 的 persistScope + advanceUntilIdle 等 fire-and-forget 落库完成。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LongTaskTrackerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LongTaskStore

    /** 假钟：测试手动推进（beginRun 与检查点时间分支都取它）。 */
    private var fakeNow = 1_000_000L

    @Before
    fun setup() {
        store = LongTaskStore(tmp.newFolder("longtask"))
    }

    /**
     * persistScope 直接用 runTest 的 TestScope：fire-and-forget 的 upsert
     * 落在测试调度器上，advanceUntilIdle 可确定地等它跑完（独立
     * StandardTestDispatcher() 自带调度器，不会被测试时钟推进——历史教训）。
     */
    private fun newTracker(scope: CoroutineScope) = LongTaskTracker(store, scope) { fakeNow }

    /** 一轮「迭代 + 工具调用」的标准事件对。 */
    private fun iterationWithTool(
        index: Int,
        toolName: String = "code_edit",
        arguments: String = """{"path":"src/Main.kt"}""",
        success: Boolean = true
    ): List<AgentEvent> = listOf(
        AgentEvent.IterationStart(index),
        AgentEvent.ToolCallStart("call-$index", toolName, arguments),
        AgentEvent.ToolCallComplete(
            callId = "call-$index",
            toolName = toolName,
            arguments = arguments,
            output = "ok",
            success = success,
            durationMs = 50L
        )
    )

    /** 泵入一段够 MEDIUM 门槛的脚本（9 迭代 × 1 工具）。 */
    private fun pumpMediumRun(tracker: LongTaskTracker) {
        for (i in 1..9) {
            iterationWithTool(i).forEach { tracker.onEvent(it) }
        }
    }

    // ═══ 短任务 / 长任务闸门 ════════════════════════════════════

    @Test
    fun `short run returns null and nothing is persisted`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("快速改个文案", "ws-a", "工作区A", "LIGHT", "BUILD")
        for (i in 1..3) {
            iterationWithTool(i).forEach { tracker.onEvent(it) }
        }
        fakeNow += 5_000L
        val record = tracker.endRun(LongTaskStatus.COMPLETED)
        assertNull(record)
        advanceUntilIdle()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `long run returns fully populated record and persists it`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun(
            "重构登录模块\n拆分 ViewModel 与 Repository",
            "ws-a", "工作区A", "DEEP", "BUILD"
        )
        tracker.noteTodos(listOf("☐ 拆分 ViewModel", "☑ 读代码"))
        pumpMediumRun(tracker)
        fakeNow += 200_000L
        val record = tracker.endRun(LongTaskStatus.COMPLETED)

        assertNotNull(record)
        record!!
        assertEquals("重构登录模块", record.title)
        assertEquals("重构登录模块\n拆分 ViewModel 与 Repository", record.goal)
        assertEquals("ws-a", record.workspaceId)
        assertEquals("工作区A", record.workspaceName)
        assertEquals("DEEP", record.thinkingLevel)
        assertEquals("BUILD", record.agentMode)
        assertEquals(LongTaskStatus.COMPLETED, record.status)
        assertEquals(9, record.iterations)
        assertEquals(9, record.toolCalls)
        assertEquals(200_000L, record.durationMs)
        assertEquals(listOf("src/Main.kt"), record.filesTouched)
        assertEquals(mapOf("code_edit" to 9), record.toolsUsed)
        assertEquals(listOf("☐ 拆分 ViewModel", "☑ 读代码"), record.todoSnapshot)
        assertNotNull(record.endedAt)
        assertNull(record.errorMessage)

        advanceUntilIdle()
        assertEquals(record, store.get(record.id))
    }

    @Test
    fun `duration driven threshold alone qualifies a run`() = runTest {
        // 迭代 / 工具 / 文件全低，但时长 150s ≥ 120s → MEDIUM
        val tracker = newTracker(this)
        tracker.beginRun("等待型任务", "ws-a", "工作区A", "STANDARD", "BUILD")
        for (i in 1..2) {
            iterationWithTool(i, toolName = "code_read", arguments = """{"path":"a.kt"}""")
                .forEach { tracker.onEvent(it) }
        }
        fakeNow += 150_000L
        assertNotNull(tracker.endRun(LongTaskStatus.COMPLETED))
    }

    // ═══ filesTouched 提取 ═════════════════════════════════════

    @Test
    fun `files touched extracted from successful code edit and write`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("改一批文件", "ws-a", "工作区A", "DEEP", "BUILD")
        iterationWithTool(1, toolName = "code_edit", arguments = """{"path":"src/A.kt","new_text":"x"}""")
            .forEach { tracker.onEvent(it) }
        iterationWithTool(2, toolName = "code_write", arguments = """{"path":"src/B.kt","content":"y"}""")
            .forEach { tracker.onEvent(it) }
        iterationWithTool(3, toolName = "code_edit", arguments = """{"path":"src/A.kt","new_text":"z"}""")
            .forEach { tracker.onEvent(it) }
        // 失败的编辑不落盘 → 不计入
        iterationWithTool(4, toolName = "code_edit", arguments = """{"path":"src/C.kt"}""", success = false)
            .forEach { tracker.onEvent(it) }
        // 非文件工具即使带 path 也不计入
        iterationWithTool(5, toolName = "code_read", arguments = """{"path":"src/D.kt"}""")
            .forEach { tracker.onEvent(it) }
        // 补足 MEDIUM 门槛：用 code_read 填迭代（不污染 filesTouched）
        for (i in 6..9) {
            iterationWithTool(i, toolName = "code_read", arguments = """{"path":"src/Main.kt"}""")
                .forEach { tracker.onEvent(it) }
        }
        val record = tracker.endRun(LongTaskStatus.COMPLETED)!!
        assertEquals(listOf("src/A.kt", "src/B.kt"), record.filesTouched) // 去重 + 排序
    }

    @Test
    fun `malformed tool arguments never crash the tracker`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("防御式", "ws-a", "工作区A", "DEEP", "BUILD")
        iterationWithTool(1, toolName = "code_edit", arguments = "not-a-json{{{")
            .forEach { tracker.onEvent(it) }
        iterationWithTool(2, toolName = "code_edit", arguments = "")
            .forEach { tracker.onEvent(it) }
        iterationWithTool(3, toolName = "code_edit", arguments = """{"path": 123}""") // path 非字符串
            .forEach { tracker.onEvent(it) }
        iterationWithTool(4, toolName = "code_edit", arguments = """{"other":"field"}""")
            .forEach { tracker.onEvent(it) }
        for (i in 5..9) {
            iterationWithTool(i, arguments = """{"path":"src/ok.kt"}""").forEach { tracker.onEvent(it) }
        }
        val record = tracker.endRun(LongTaskStatus.COMPLETED)!!
        assertEquals(listOf("src/ok.kt"), record.filesTouched)
    }

    @Test
    fun `tools used counts failed calls too`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("失败统计", "ws-a", "工作区A", "DEEP", "BUILD")
        iterationWithTool(1, toolName = "shell_execute", arguments = "ls", success = false)
            .forEach { tracker.onEvent(it) }
        iterationWithTool(2, toolName = "shell_execute", arguments = "ls", success = false)
            .forEach { tracker.onEvent(it) }
        for (i in 3..9) {
            iterationWithTool(i).forEach { tracker.onEvent(it) }
        }
        val record = tracker.endRun(LongTaskStatus.COMPLETED)!!
        assertEquals(mapOf("shell_execute" to 2, "code_edit" to 7), record.toolsUsed)
        assertEquals(9, record.toolCalls)
    }

    // ═══ 检查点 ════════════════════════════════════════════════

    @Test
    fun `checkpoints appended every five iterations`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("检查点节奏", "ws-a", "工作区A", "DEEP", "BUILD")
        for (i in 1..12) {
            iterationWithTool(i).forEach { tracker.onEvent(it) }
        }
        val record = tracker.endRun(LongTaskStatus.COMPLETED)!!
        assertEquals(listOf(5, 10), record.checkpoints.map { it.atIteration })
        // 检查点内容：计数 + 对话摘要 + todo 摘要
        val first = record.checkpoints[0]
        assertEquals(5, first.atIteration)
        assertEquals(5, first.toolCallCount)
        assertEquals(1, first.filesTouchedCount)
        assertTrue(first.recentExchange.any { it.startsWith("用户: 检查点节奏") })
        assertTrue(first.recentExchange.any { it.contains("code_edit ✓") })
        assertTrue(first.recentExchange.size <= LongTaskCheckpoint.RECENT_EXCHANGE_MAX)
    }

    @Test
    fun `time driven checkpoint fires after sixty seconds of silence`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("时间检查点", "ws-a", "工作区A", "DEEP", "BUILD")
        // 4 次迭代（避开 5 的倍数），时钟静止 → 无检查点
        for (i in 1..4) {
            iterationWithTool(i).forEach { tracker.onEvent(it) }
        }
        // 时长推到 150s（≥120s MEDIUM 门槛）：endRun 本身不拍检查点，
        // 这里只为了让记录够格入库便于断言其 checkpoints 为空
        fakeNow += 150_000L
        val record0 = tracker.endRun(LongTaskStatus.ABORTED)!!
        assertTrue("4 迭代远未到迭代触发，事件期时钟静止也不该有时间触发", record0.checkpoints.isEmpty())

        // 重跑：2 迭代后推进假钟 61s 再来一个事件 → 时间触发（时长门槛由
        // 后续 200s 推进深证，任意事件可触发含 Start 型）
        val tracker2 = newTracker(this)
        tracker2.beginRun("时间检查点", "ws-a", "工作区A", "DEEP", "BUILD")
        for (i in 1..2) {
            iterationWithTool(i).forEach { tracker2.onEvent(it) }
        }
        fakeNow += 61_000L
        tracker2.onEvent(AgentEvent.ToolCallStart("call-x", "code_read", """{"path":"a.kt"}"""))
        fakeNow += 200_000L
        val record = tracker2.endRun(LongTaskStatus.COMPLETED)!!
        assertEquals(1, record.checkpoints.size)
        assertEquals(2, record.checkpoints[0].atIteration)
    }

    @Test
    fun `checkpoints capped at ten keeping the newest`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("长跑", "ws-a", "工作区A", "DEEP", "BUILD")
        for (i in 1..70) {
            iterationWithTool(i, arguments = """{"path":"src/F$i.kt"}""")
                .forEach { tracker.onEvent(it) }
        }
        val record = tracker.endRun(LongTaskStatus.COMPLETED)!!
        assertEquals(LongTaskRecord.CHECKPOINTS_MAX, record.checkpoints.size)
        // 丢最旧保最新：最后一个检查点在第 70 次迭代
        assertEquals(70, record.checkpoints.last().atIteration)
    }

    // ═══ 未收尾防御与状态透传 ═══════════════════════════════════

    @Test
    fun `beginRun while active auto finalizes old run as aborted`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("旧任务目标", "ws-a", "工作区A", "DEEP", "BUILD")
        pumpMediumRun(tracker)
        // 未 endRun 直接开新 run
        tracker.beginRun("新任务目标", "ws-a", "工作区A", "STANDARD", "BUILD")

        advanceUntilIdle()
        val persisted = store.list()
        assertEquals(1, persisted.size)
        val old = persisted[0]
        assertEquals(LongTaskStatus.ABORTED, old.status)
        assertEquals("旧任务目标", old.goal)
        assertEquals(9, old.iterations)

        // 新 run 独立计数（旧聚合不泄漏）
        for (i in 1..2) {
            iterationWithTool(i).forEach { tracker.onEvent(it) }
        }
        fakeNow += 10_000L
        val newRecord = tracker.endRun(LongTaskStatus.COMPLETED)
        assertNull("2 迭代的短 run 不入库", newRecord)
    }

    @Test
    fun `auto finalize of a short old run persists nothing`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("短的旧任务", "ws-a", "工作区A", "LIGHT", "BUILD")
        for (i in 1..2) {
            iterationWithTool(i).forEach { tracker.onEvent(it) }
        }
        tracker.beginRun("新任务", "ws-a", "工作区A", "LIGHT", "BUILD")
        advanceUntilIdle()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `failed status carries error message`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("会失败的任务", "ws-a", "工作区A", "DEEP", "BUILD")
        pumpMediumRun(tracker)
        val record = tracker.endRun(LongTaskStatus.FAILED, errorMessage = "编译超时")!!
        assertEquals(LongTaskStatus.FAILED, record.status)
        assertEquals("编译超时", record.errorMessage)
    }

    @Test
    fun `error message is truncated to five hundred chars`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("超长错误", "ws-a", "工作区A", "DEEP", "BUILD")
        pumpMediumRun(tracker)
        val record = tracker.endRun(LongTaskStatus.FAILED, errorMessage = "E".repeat(900))!!
        assertEquals(500, record.errorMessage!!.length)
    }

    // ═══ summary 推导 ══════════════════════════════════════════

    @Test
    fun `summary derives from response chunk tail ring buffer`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("摘要任务", "ws-a", "工作区A", "DEEP", "BUILD")
        pumpMediumRun(tracker)
        // 250 字符的助手回复：环只保尾部 200（150A + 100B → 删头 50A）
        tracker.onEvent(AgentEvent.ResponseChunk("A".repeat(150)))
        tracker.onEvent(AgentEvent.ResponseChunk("B".repeat(100)))
        tracker.onEvent(AgentEvent.ResponseComplete("A".repeat(150) + "B".repeat(100)))
        fakeNow += 10_000L

        val record = tracker.endRun(LongTaskStatus.COMPLETED)!!
        assertEquals("A".repeat(100) + "B".repeat(100), record.summary)
    }

    @Test
    fun `explicit summary overrides derived tail`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("显式摘要", "ws-a", "工作区A", "DEEP", "BUILD")
        pumpMediumRun(tracker)
        tracker.onEvent(AgentEvent.ResponseChunk("模型自己写的结论"))
        tracker.onEvent(AgentEvent.ResponseComplete("模型自己写的结论"))
        fakeNow += 10_000L

        val record = tracker.endRun(LongTaskStatus.COMPLETED, summary = "Complete 事件的官方摘要")!!
        assertEquals("Complete 事件的官方摘要", record.summary)
    }

    @Test
    fun `non streaming complete falls back to full text tail`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("非流式", "ws-a", "工作区A", "DEEP", "BUILD")
        pumpMediumRun(tracker)
        // 无 ResponseChunk 直达 Complete（防御非流式路径）
        tracker.onEvent(AgentEvent.ResponseComplete("X".repeat(300)))
        fakeNow += 10_000L
        val record = tracker.endRun(LongTaskStatus.COMPLETED)!!
        assertEquals("X".repeat(200), record.summary) // fullText 尾 200
    }

    // ═══ 截断与边界 ════════════════════════════════════════════

    @Test
    fun `goal truncated to four thousand chars and title to first line sixty`() = runTest {
        val tracker = newTracker(this)
        val longFirstLine = "标".repeat(80)
        val goal = longFirstLine + "\n第二行内容"
        tracker.beginRun(goal + "尾".repeat(5000), "ws-a", "工作区A", "DEEP", "BUILD")
        pumpMediumRun(tracker)
        fakeNow += 10_000L
        val record = tracker.endRun(LongTaskStatus.COMPLETED)!!

        assertEquals(LongTaskRecord.GOAL_MAX_CHARS, record.goal.length)
        assertEquals(LongTaskRecord.TITLE_MAX_CHARS, record.title.length)
        // 保头：goal 开头与 title 开头都是首行前缀
        assertTrue(record.goal.startsWith(longFirstLine.take(60)))
        assertEquals(longFirstLine.take(60), record.title)
    }

    @Test
    fun `blank goal falls back to default title`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("   \n  ", "ws-a", "工作区A", "DEEP", "BUILD")
        pumpMediumRun(tracker)
        fakeNow += 10_000L
        val record = tracker.endRun(LongTaskStatus.COMPLETED)!!
        assertEquals("未命名任务", record.title)
    }

    @Test
    fun `endRun without beginRun returns null and is idempotent`() = runTest {
        val tracker = newTracker(this)
        assertNull(tracker.endRun(LongTaskStatus.COMPLETED))

        tracker.beginRun("正常", "ws-a", "工作区A", "DEEP", "BUILD")
        pumpMediumRun(tracker)
        assertNotNull(tracker.endRun(LongTaskStatus.COMPLETED))
        assertNull(tracker.endRun(LongTaskStatus.COMPLETED)) // 二次收尾
    }

    @Test
    fun `events after endRun are ignored`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("收尾后事件", "ws-a", "工作区A", "DEEP", "BUILD")
        pumpMediumRun(tracker)
        fakeNow += 10_000L
        val record = tracker.endRun(LongTaskStatus.COMPLETED)!!
        // 晚到事件不计入任何新状态
        iterationWithTool(99).forEach { tracker.onEvent(it) }
        advanceUntilIdle()
        assertEquals(record, store.get(record.id))
    }

    @Test
    fun `noteTodos snapshots the latest renderable lines`() = runTest {
        val tracker = newTracker(this)
        tracker.beginRun("todo 任务", "ws-a", "工作区A", "DEEP", "BUILD")
        tracker.noteTodos(listOf("☐ 第一步", "☐ 第二步"))
        for (i in 1..4) {
            iterationWithTool(i).forEach { tracker.onEvent(it) }
        }
        tracker.noteTodos(listOf("☑ 第一步", "☐ 第二步", "☐ 第三步"))
        for (i in 5..9) {
            iterationWithTool(i).forEach { tracker.onEvent(it) }
        }
        fakeNow += 10_000L
        val record = tracker.endRun(LongTaskStatus.COMPLETED)!!
        assertEquals(listOf("☑ 第一步", "☐ 第二步", "☐ 第三步"), record.todoSnapshot)
    }
}
