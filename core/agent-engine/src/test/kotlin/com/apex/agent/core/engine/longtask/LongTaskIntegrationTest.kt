package com.apex.agent.core.engine.longtask

import com.apex.agent.core.engine.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
 * 长任务中心端到端全链路集成测试（v1.2「长任务复制顶级优化」）。
 *
 * 与 [LongTaskTrackerTest] / [TaskCopyEngineTest] 的分工：那些是单类单元测试，
 * 本文件把 **真实 [LongTaskStore]（TemporaryFolder 磁盘）+ 脚本化 [AgentEvent]
 * 驱动的 [LongTaskTracker] + [TaskCopyEngine] + [LongTaskTemplates] +
 * [LongTaskDiff]** 串成一条完整数据流，验证「事件流 → 聚合 → 落库 → 复制 →
 * 续跑 → 再留档 → 对比」的闭环语义在真实组件协作下依然成立：
 *
 *  - 场景 A：EPIC 任务全生命周期（70 迭代 × 双工具 + 流式回复 + todo 快照 →
 *    FAILED 收尾 → 入库记录全字段断言 + 跨实例磁盘重载复核）；
 *  - 场景 B：复制（带检查点上下文 + 换档 ULTRACODE）→ 从最后检查点续跑 →
 *    新运行再留档 → forkChain 三节点复制链（源 → 副本 → 续跑）；
 *  - 场景 C：源与副本两条记录的 [LongTaskDiff.compare] 结构化差异 +
 *    [LongTaskDiff.renderText] 中文渲染（文件交集/差集/迭代差/时长差/状态对比）；
 *  - 场景 D：连续两个短任务都静默丢弃（库与磁盘双层面零残留）；
 *  - 场景 E：未收尾任务被新 run 顶替 → 自动 ABORTED 入库（够格前提），
 *    接棒任务独立计数不泄漏；
 *  - 场景 F：双工作区隔离——list(wsId) 只见各自记录，全局列表按 updatedAt
 *    新到旧排序，阻塞快照与挂起读口径一致；
 *  - 场景 G：模板实例化启动——稳定 id 入库、goal 占位符替换、复制转正为
 *    可跑的非模板记录。
 *
 * 时钟全部注入假钟（检查点时间分支可确定驱动）；persistScope 直接用 runTest
 * 的 TestScope，advanceUntilIdle 确定地等 fire-and-forget 落库完成。
 * 纯 JUnit4 + runTest + TemporaryFolder，无 mock 框架。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LongTaskIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var store: LongTaskStore
    private lateinit var copyEngine: TaskCopyEngine

    /** 假钟：测试手动推进（beginRun / 检查点 / durationMs 都取它）。 */
    private var fakeNow = 1_000_000L

    @Before
    fun setup() {
        baseDir = tmp.newFolder("longtask")
        store = LongTaskStore(baseDir)
        copyEngine = TaskCopyEngine(store)
    }

    /** 场景 A/B/C 共用的 EPIC 任务参数（断言期望值全部由此推导）。 */
    private companion object {
        const val WS_ID = "ws-epic-1a2b"
        const val WS_NAME = "史诗工作区"
        const val EPIC_TITLE = "史诗级重构：把核心模块全部迁移到新架构"
        const val EPIC_GOAL = "史诗级重构：把核心模块全部迁移到新架构\n涉及数据层、网络层与 UI 层，全部模块逐一替换并验证"
        const val EPIC_ERROR = "编译失败：Mod10 模块引用未解析"
        const val EPIC_ITERATIONS = 70
        const val EPIC_DURATION_MS = 700_000L

        /** 续跑模拟的时长（MEDIUM 门槛之上，供场景 C 时长差断言）。 */
        const val RESUME_DURATION_MS = 120_000L

        /** 续跑里 code_edit 轮换触碰的文件（前两个与源记录重叠，最后一个是新文件）。 */
        val RESUME_FILES = listOf("src/Mod3.kt", "src/Mod7.kt", "src/NewModule.kt")
    }

    private fun newTracker(scope: CoroutineScope) = LongTaskTracker(store, scope) { fakeNow }

    // ═════════════════ 脚本化事件泵 ═════════════════

    /**
     * 一轮「迭代 + 单工具」事件对（IterationStart + ToolCallStart + 完成）。
     * 默认 code_edit 成功编辑 src/Main.kt——与既有 [LongTaskTrackerTest] 的
     * iterationWithTool 同构，供短泵复用。
     */
    private fun toolPair(
        tracker: LongTaskTracker,
        index: Int,
        toolName: String = "code_edit",
        arguments: String = """{"path":"src/Main.kt"}""",
        success: Boolean = true
    ) {
        tracker.onEvent(AgentEvent.IterationStart(index))
        tracker.onEvent(AgentEvent.ToolCallStart("call-$index", toolName, arguments))
        tracker.onEvent(
            AgentEvent.ToolCallComplete(
                callId = "call-$index",
                toolName = toolName,
                arguments = arguments,
                output = "ok",
                success = success,
                durationMs = 50L
            )
        )
    }

    /**
     * 场景 A 的 EPIC 事件泵：70 迭代 × 2 工具（code_edit 轮换 12 个模块文件 +
     * code_read 只读不进 filesTouched），每 10 轮穿插一条流式助手播报
     * （ResponseChunk → ResponseComplete），每 7 轮刷新 todo 快照；末尾追加
     * 一条 255 字符的最终长消息（尾环只保最后 200 → summary 断言依据）。
     *
     * 时钟全程静止 → 检查点只由迭代驱动（每 5 轮在首个完成型事件上触发）。
     */
    private fun pumpEpicRun(tracker: LongTaskTracker) {
        for (i in 1..EPIC_ITERATIONS) {
            tracker.onEvent(AgentEvent.IterationStart(i))
            // 工具一：code_edit 成功，i % 12 在 1..70 内覆盖 0..11 全部 12 个文件
            val editPath = "src/Mod${i % 12}.kt"
            val editArgs = """{"path":"$editPath","new_text":"change-$i"}"""
            tracker.onEvent(AgentEvent.ToolCallStart("edit-$i", "code_edit", editArgs))
            tracker.onEvent(
                AgentEvent.ToolCallComplete(
                    callId = "edit-$i",
                    toolName = "code_edit",
                    arguments = editArgs,
                    output = "edited",
                    success = true,
                    durationMs = 30L
                )
            )
            // 工具二：code_read（即使带 path 也不进 filesTouched——只有编辑类工具计入）
            val readArgs = """{"path":"src/Main.kt"}"""
            tracker.onEvent(AgentEvent.ToolCallStart("read-$i", "code_read", readArgs))
            tracker.onEvent(
                AgentEvent.ToolCallComplete(
                    callId = "read-$i",
                    toolName = "code_read",
                    arguments = readArgs,
                    output = "content",
                    success = true,
                    durationMs = 10L
                )
            )
            // 每 10 轮一条完整助手播报（末轮 70 留给结尾的长消息，不在这里发）
            if (i % 10 == 0 && i != EPIC_ITERATIONS) {
                tracker.onEvent(AgentEvent.ResponseChunk("第 $i 轮进展："))
                tracker.onEvent(AgentEvent.ResponseChunk("已处理模块 Mod${i % 12}。"))
                tracker.onEvent(AgentEvent.ResponseComplete("第 $i 轮进展：已处理模块 Mod${i % 12}。"))
            }
            // 每 7 轮刷新 todo（第 70 轮的快照是记录最终 todoSnapshot）
            if (i % 7 == 0) {
                tracker.noteTodos(listOf("☑ 阶段一", "☐ 阶段二", "☐ 阶段三（第 $i 轮更新）"))
            }
        }
        // 最终长消息：5 + 150 + 100 = 255 字符 → 尾环删头 55 保尾 200
        // （"最终结论：" 5 字符 + 甲×150 的前 50 字符被删 → 剩甲×100 + 乙×100）
        val finalMessage = "最终结论：" + "甲".repeat(150) + "乙".repeat(100)
        tracker.onEvent(AgentEvent.ResponseChunk("最终结论：" + "甲".repeat(150)))
        tracker.onEvent(AgentEvent.ResponseChunk("乙".repeat(100)))
        tracker.onEvent(AgentEvent.ResponseComplete(finalMessage))
    }

    /**
     * 场景 A 的完整执行：beginRun（DEEP 档）→ EPIC 泵 → 推进假钟 700s →
     * FAILED 收尾 → 等 fire-and-forget 落库 → 返回**已持久化**的记录。
     * 场景 B/C 复用它作为复制源（挂起：回读走 store.get）。
     */
    private suspend fun epicRecord(scope: TestScope): LongTaskRecord {
        val tracker = newTracker(scope)
        tracker.beginRun(EPIC_GOAL, WS_ID, WS_NAME, "DEEP", "BUILD")
        pumpEpicRun(tracker)
        fakeNow += EPIC_DURATION_MS
        val record = tracker.endRun(LongTaskStatus.FAILED, errorMessage = EPIC_ERROR)
        assertNotNull("70 迭代 × 140 工具调用必然是长任务", record)
        scope.advanceUntilIdle()
        return store.get(record!!.id)!!
    }

    /**
     * 场景 B/C 的续跑模拟：以续跑提示词为新用户消息（VM 的真实接线方式），
     * 9 迭代 × 2 工具（code_edit 轮换 Mod3 → Mod7 → NewModule 三文件 +
     * code_read），时长推进 120s 后以 COMPLETED + 显式摘要收尾。
     */
    private fun pumpResumeRun(scope: TestScope, goal: String): LongTaskRecord {
        fakeNow += 20_000L
        val tracker = newTracker(scope)
        tracker.beginRun(goal, WS_ID, WS_NAME, "ULTRACODE", "BUILD")
        for (i in 1..9) {
            val path = when {
                i <= 3 -> RESUME_FILES[0]
                i <= 6 -> RESUME_FILES[1]
                else -> RESUME_FILES[2]
            }
            val editArgs = """{"path":"$path","new_text":"resume-$i"}"""
            tracker.onEvent(AgentEvent.IterationStart(i))
            tracker.onEvent(AgentEvent.ToolCallStart("r-edit-$i", "code_edit", editArgs))
            tracker.onEvent(
                AgentEvent.ToolCallComplete(
                    callId = "r-edit-$i",
                    toolName = "code_edit",
                    arguments = editArgs,
                    output = "edited",
                    success = true,
                    durationMs = 20L
                )
            )
            val readArgs = """{"path":"src/Main.kt"}"""
            tracker.onEvent(AgentEvent.ToolCallStart("r-read-$i", "code_read", readArgs))
            tracker.onEvent(
                AgentEvent.ToolCallComplete(
                    callId = "r-read-$i",
                    toolName = "code_read",
                    arguments = readArgs,
                    output = "content",
                    success = true,
                    durationMs = 5L
                )
            )
        }
        fakeNow += RESUME_DURATION_MS
        val record = tracker.endRun(LongTaskStatus.COMPLETED, summary = "续跑完成：新架构迁移收尾。")
        assertNotNull("9 迭代 ≥ 8 达 MEDIUM 门槛", record)
        scope.advanceUntilIdle()
        return record!!
    }

    // ═════════════════ 场景 A ═════════════════

    /**
     * 场景 A · EPIC 任务全生命周期：事件流聚合 → FAILED 收尾 → 入库记录
     * 全字段断言（EPIC 规模复核 / 检查点 10 个封顶且最后一个在第 70 轮 /
     * filesTouched 去重排序 / toolsUsed 计数 / summary 来自 200 字符尾环），
     * 最后用**新 Store 实例**重扫磁盘复核（证明真实落盘而非仅内存缓存）。
     */
    @Test
    fun `场景A_EPIC任务全生命周期_事件流聚合到落库全字段断言`() = runTest {
        val tracker = newTracker(this)
        val beginAt = fakeNow
        tracker.beginRun(EPIC_GOAL, WS_ID, WS_NAME, "DEEP", "BUILD")
        pumpEpicRun(tracker)
        fakeNow += EPIC_DURATION_MS
        val record = tracker.endRun(LongTaskStatus.FAILED, errorMessage = EPIC_ERROR)
        assertNotNull(record)
        record!!

        // —— 记录本体：目标与档位元数据 ——
        assertEquals(EPIC_TITLE, record.title) // title = goal 首行
        assertEquals(EPIC_GOAL, record.goal) // 远小于 4000 不截断
        assertEquals(WS_ID, record.workspaceId)
        assertEquals(WS_NAME, record.workspaceName)
        assertEquals("DEEP", record.thinkingLevel)
        assertEquals("BUILD", record.agentMode)
        assertEquals(LongTaskStatus.FAILED, record.status)
        assertEquals(EPIC_ERROR, record.errorMessage)
        assertEquals(beginAt, record.createdAt)
        assertEquals(beginAt + EPIC_DURATION_MS, record.endedAt)
        assertEquals(beginAt + EPIC_DURATION_MS, record.updatedAt)
        assertEquals(EPIC_DURATION_MS, record.durationMs)

        // —— 聚合计数：70 迭代 × 每轮 2 工具 ——
        assertEquals(EPIC_ITERATIONS, record.iterations)
        assertEquals(EPIC_ITERATIONS * 2, record.toolCalls)
        assertEquals(
            mapOf("code_edit" to EPIC_ITERATIONS, "code_read" to EPIC_ITERATIONS),
            record.toolsUsed
        )
        // filesTouched：code_edit 的 12 个轮换文件（去重 + 字典序），
        // code_read 的 src/Main.kt 不计入
        val expectedFiles = (0..11).map { "src/Mod$it.kt" }.sorted()
        assertEquals(expectedFiles, record.filesTouched)

        // —— EPIC 规模复核：记录聚合信号回灌判定器仍是史诗档 ——
        assertEquals(
            LongTaskMagnitude.EPIC,
            LongTaskDetector.magnitude(
                LongTaskDetector.Signals(
                    iterations = record.iterations,
                    toolCalls = record.toolCalls,
                    durationMs = record.durationMs,
                    filesTouched = record.filesTouched.size
                )
            )
        )

        // —— 检查点：每 5 轮一拍共 14 个 → 封顶 10 保最新（25..70）——
        assertEquals(LongTaskRecord.CHECKPOINTS_MAX, record.checkpoints.size)
        assertEquals((25..70 step 5).toList(), record.checkpoints.map { it.atIteration })
        val lastCp = record.checkpoints.last()
        assertEquals(70, lastCp.atIteration)
        // 第 70 轮首个完成型事件（code_edit 完成）时拍照：工具计数 2×70−1
        assertEquals(EPIC_ITERATIONS * 2 - 1, lastCp.toolCallCount)
        assertEquals(12, lastCp.filesTouchedCount)
        assertTrue(lastCp.recentExchange.any { it.contains("工具 code_edit ✓") })
        assertTrue(
            "检查点对话摘要行数必须钳制在 RECENT_EXCHANGE_MAX 内",
            lastCp.recentExchange.size <= LongTaskCheckpoint.RECENT_EXCHANGE_MAX
        )
        // 第 70 轮检查点先于当轮 noteTodos 拍照 → todoDigest 是第 63 轮的内容
        assertEquals(listOf("☑ 阶段一", "☐ 阶段二", "☐ 阶段三（第 63 轮更新）"), lastCp.todoDigest)
        val firstCp = record.checkpoints.first()
        assertEquals(25, firstCp.atIteration)
        assertEquals(2 * 25 - 1, firstCp.toolCallCount)

        // —— todo 快照取最后一次 noteTodos（第 70 轮）——
        assertEquals(listOf("☑ 阶段一", "☐ 阶段二", "☐ 阶段三（第 70 轮更新）"), record.todoSnapshot)

        // —— summary 来自 200 字符尾环缓冲（255 字符最终消息删头 55 保尾 200）——
        assertEquals("甲".repeat(100) + "乙".repeat(100), record.summary)

        // —— 落库复核：内存缓存一致 + 跨实例磁盘重扫一致 ——
        advanceUntilIdle()
        assertEquals(record, store.get(record.id))
        assertEquals(1, store.list().size)
        val reloaded = LongTaskStore(baseDir)
        assertEquals("新 Store 实例必须从磁盘扫回同一记录", record, reloaded.get(record.id))
    }

    // ═════════════════ 场景 B ═════════════════

    /**
     * 场景 B · 复制 → 续跑 → 再留档 → 复制链：
     * 1. 复制源（FAILED/70 迭代）为带检查点上下文 + 换档 ULTRACODE 的副本；
     * 2. buildResumePrompt 从副本最后检查点（第 70 轮）组装续跑指令并断言文本结构；
     * 3. 以续跑指令为新 goal 模拟一次真实续跑（COMPLETED 收尾再留档）；
     * 4. 把续跑记录的血缘回写到副本（Tracker 无 parentTaskId 通道，属 VM 层
     *    接线职责——见本测试类 KDoc 与 worklog 契约疑点记录），forkChain
     *    断言三节点链「源 → 副本 → 续跑」旧到新排序。
     */
    @Test
    fun `场景B_复制换档_检查点续跑_三节点复制链`() = runTest {
        val source = epicRecord(this)

        // —— 复制：带检查点上下文 + 换档 ULTRACODE（v1.2 旗舰用法）——
        val copy = copyEngine.copy(
            source.id,
            LongTaskCopyOptions(includeCheckpoints = true, thinkingLevelOverride = "ULTRACODE")
        ).getOrThrow()
        assertFalse(copy.id == source.id)
        assertEquals(source.id, copy.parentTaskId)
        assertEquals(LongTaskStatus.RUNNING, copy.status)
        assertNull(copy.endedAt)
        assertEquals(0, copy.iterations)
        assertEquals(0, copy.toolCalls)
        assertEquals(0L, copy.durationMs)
        assertEquals(0, copy.copyCount)
        assertEquals(source.goal, copy.goal)
        assertEquals("ULTRACODE", copy.thinkingLevel) // 换档生效
        assertEquals(source.workspaceId, copy.workspaceId)
        assertEquals(source.checkpoints, copy.checkpoints) // 检查点随行（10 个）
        assertEquals(source.filesTouched, copy.filesTouched)
        assertEquals(source.todoSnapshot, copy.todoSnapshot)
        assertEquals("${source.title}（副本 1）", copy.title)
        assertFalse(copy.isTemplate)
        // 源计数 +1；副本已入库
        assertEquals(1, store.get(source.id)!!.copyCount)
        assertEquals(copy, store.get(copy.id))

        // —— 续跑指令：从副本最后检查点（第 70 轮）——
        val resumePrompt = copyEngine.buildResumePrompt(copy)
        assertTrue(resumePrompt.startsWith("续跑任务：${copy.title}"))
        assertTrue(resumePrompt.contains(source.goal))
        assertTrue(resumePrompt.contains("## 进度快照（第 70 轮检查点）"))
        assertTrue(resumePrompt.contains("已进行：70 次迭代 · 139 次工具调用 · 已触碰 12 个文件"))
        assertTrue(resumePrompt.contains("最近进展"))
        assertTrue(resumePrompt.contains("工具 code_edit ✓")) // 检查点 recentExchange 行
        assertTrue(resumePrompt.contains("待办状态"))
        assertTrue(resumePrompt.contains("第 63 轮更新")) // 检查点 todoDigest
        assertTrue(resumePrompt.contains("已完成项不要重做")) // 续跑收尾指令
        assertTrue("续跑指令超长会截断保头，这里素材有限必须完整", resumePrompt.length <= 3000)

        // —— 模拟续跑：新 tracker 以续跑指令为 goal ——
        val resumed = pumpResumeRun(this, resumePrompt)
        assertEquals(LongTaskStatus.COMPLETED, resumed.status)
        assertEquals("续跑完成：新架构迁移收尾。", resumed.summary) // 显式摘要优先
        assertEquals(9, resumed.iterations)
        assertEquals(18, resumed.toolCalls)
        assertEquals(RESUME_DURATION_MS, resumed.durationMs)
        assertEquals(resumePrompt, resumed.goal) // 续跑指令成为新记录的目标锚点
        assertEquals("续跑任务：${copy.title}", resumed.title) // goal 首行即标题
        assertEquals("ULTRACODE", resumed.thinkingLevel)
        assertEquals(RESUME_FILES, resumed.filesTouched)
        assertEquals(resumed, store.get(resumed.id)) // 续跑记录已独立留档

        // —— 血缘回写（VM 层接线）：Tracker 产出的记录不带 parent，
        //    复制链的完整性由调用方把续跑记录指回副本 ——
        val linked = store.get(resumed.id)!!.copy(parentTaskId = copy.id)
        store.upsert(linked)
        advanceUntilIdle()

        // —— 三节点复制链：源 → 副本 → 续跑（旧到新）——
        val chain = copyEngine.forkChain(resumed.id)
        assertEquals(listOf(source.id, copy.id, resumed.id), chain.map { it.id })
        assertEquals(
            listOf(LongTaskStatus.FAILED, LongTaskStatus.RUNNING, LongTaskStatus.COMPLETED),
            chain.map { it.status }
        )
        // 中间节点与根节点各自的链视角
        assertEquals(listOf(source.id, copy.id), copyEngine.forkChain(copy.id).map { it.id })
        assertEquals(listOf(source.id), copyEngine.forkChain(source.id).map { it.id })
    }

    // ═════════════════ 场景 C ═════════════════

    /**
     * 场景 C · 复制链对比：源（FAILED/DEEP/70 迭代）与副本的续跑执行记录
     * （COMPLETED/ULTRACODE/9 迭代）做 [LongTaskDiff.compare]——结构化差异
     * 断言（文件交集/差集、迭代、工具、时长、状态、档位）+
     * [LongTaskDiff.renderText] 中文渲染断言；附带对照「源 vs 刚复制的零统计
     * 副本」证明副本起点归零语义在 diff 下的形态。
     */
    @Test
    fun `场景C_源与副本运行对比_结构化差异与中文渲染`() = runTest {
        val source = epicRecord(this)
        val copy = copyEngine.copy(
            source.id,
            LongTaskCopyOptions(includeCheckpoints = true, thinkingLevelOverride = "ULTRACODE")
        ).getOrThrow()
        val resumed = pumpResumeRun(this, copyEngine.buildResumePrompt(copy))

        // —— 结构化差异 ——
        val diff = LongTaskDiff.compare(source, resumed)
        val expectedOnlyA = ((0..11).map { "src/Mod$it.kt" }.toSet() - RESUME_FILES.toSet()).sorted()
        assertEquals(expectedOnlyA, diff.filesOnlyInA)
        assertEquals(listOf("src/NewModule.kt"), diff.filesOnlyInB)
        assertEquals(2, diff.filesInBoth)
        assertEquals(70, diff.iterationsA)
        assertEquals(9, diff.iterationsB)
        assertEquals(140, diff.toolCallsA)
        assertEquals(18, diff.toolCallsB)
        assertEquals(EPIC_DURATION_MS, diff.durationMsA)
        assertEquals(RESUME_DURATION_MS, diff.durationMsB)
        assertEquals(LongTaskStatus.FAILED, diff.statusA)
        assertEquals(LongTaskStatus.COMPLETED, diff.statusB)
        assertEquals("DEEP", diff.thinkingA)
        assertEquals("ULTRACODE", diff.thinkingB)

        // —— 中文渲染：标题行 + 六个对比维度 + 文件段落 ——
        val text = LongTaskDiff.renderText(diff, source.title, resumed.title)
        assertTrue(text.startsWith("【运行对比】${source.title} vs ${resumed.title}"))
        assertTrue(text.contains("思考档位：DEEP → ULTRACODE"))
        assertTrue(text.contains("状态：FAILED → COMPLETED"))
        // 整数差值：9 − 70 = −61（渲染用真减号 −）；18 − 140 = −122
        assertTrue(text.contains("迭代次数：70 → 9（−61）"))
        assertTrue(text.contains("工具调用：140 → 18（−122）"))
        // 时长：700s = 11分40秒；120s = 2分0秒；差 −580s = −9分40秒
        assertTrue(text.contains("耗时：11分40秒 → 2分0秒（−9分40秒）"))
        assertTrue(text.contains("共同改动：2 个文件"))
        // 差集段落：仅 A 10 个（含 Mod10 字典序在 Mod2 前）、仅 B 1 个
        assertTrue(text.contains("仅 A 改动（10 个）："))
        assertTrue(text.contains("  - src/Mod10.kt"))
        assertTrue(text.contains("仅 B 改动（1 个）："))
        assertTrue(text.contains("  - src/NewModule.kt"))
        // 交集文件只计数不列名 → Mod3/Mod7 不出现在渲染文本里
        assertFalse(text.contains("src/Mod3.kt"))
        assertFalse(text.contains("src/Mod7.kt"))

        // —— 对照：源 vs 刚复制的副本（统计归零形态）——
        val rawDiff = LongTaskDiff.compare(source, copy)
        assertEquals(0, rawDiff.iterationsB)
        assertEquals(0, rawDiff.toolCallsB)
        assertEquals(0L, rawDiff.durationMsB)
        assertEquals(LongTaskStatus.RUNNING, rawDiff.statusB)
        assertEquals(12, rawDiff.filesInBoth) // 默认 includeFilesList 携带改动集
        val rawText = LongTaskDiff.renderText(rawDiff, source.title, copy.title)
        assertTrue(rawText.contains("迭代次数：70 → 0（−70）"))
        assertTrue(rawText.contains("状态：FAILED → RUNNING"))
    }

    // ═════════════════ 场景 D ═════════════════

    /**
     * 场景 D · 短任务静默丢弃：连续两个四维全部低于 MEDIUM 门槛的短任务，
     * endRun 均返回 null；advanceUntilIdle 后**内存列表、阻塞快照、磁盘
     * records 目录**三个层面都零残留（fire-and-forget 什么也没投递）。
     */
    @Test
    fun `场景D_连续两个短任务均静默丢弃_库与磁盘皆空`() = runTest {
        val tracker = newTracker(this)

        // 短任务一：4 迭代 / 4 工具 / 5 秒 / 1 文件——四维全部低于门槛
        tracker.beginRun("短任务一：改个标题", "ws-a", "工作区A", "LIGHT", "BUILD")
        for (i in 1..4) {
            toolPair(tracker, i)
        }
        fakeNow += 5_000L
        assertNull("四维全低的运行必须返回 null", tracker.endRun(LongTaskStatus.COMPLETED))

        // 短任务二：4 迭代 / 4 工具 / 8 秒 / 1 文件——同样不入库
        tracker.beginRun("短任务二：再改个文案", "ws-a", "工作区A", "LIGHT", "BUILD")
        for (i in 1..4) {
            toolPair(tracker, i)
        }
        fakeNow += 8_000L
        assertNull(tracker.endRun(LongTaskStatus.COMPLETED))

        // 库与磁盘双层面零残留
        advanceUntilIdle()
        assertTrue(store.list().isEmpty())
        assertTrue(store.snapshotBlocking().isEmpty())
        val jsonFiles = baseDir.resolve("records")
            .listFiles { file -> file.name.endsWith(".json") }
        assertTrue("磁盘上不应有任何记录文件", jsonFiles == null || jsonFiles.isEmpty())
    }

    // ═════════════════ 场景 E ═════════════════

    /**
     * 场景 E · 中止恢复：任务 A 进行中（9 迭代够格）未收尾，直接 beginRun
     * 任务 B → A 自动按 ABORTED 走完整规模判定入库（errorMessage 为 null）；
     * B 独立推进并完成留档——A 的记录不被 B 的聚合污染。
     */
    @Test
    fun `场景E_未收尾任务被新run顶替_自动ABORTED入库且新任务独立`() = runTest {
        val tracker = newTracker(this)

        // 任务 A：9 迭代单工具编辑 src/Abort.kt（9 ≥ 8 → 够格留档），不收尾
        tracker.beginRun("被打断的长任务", "ws-a", "工作区A", "DEEP", "BUILD")
        for (i in 1..9) {
            toolPair(tracker, i, arguments = """{"path":"src/Abort.kt"}""")
        }

        // 未 endRun 直接开新 run：A 自动 ABORTED 收尾
        tracker.beginRun("接棒的新任务", "ws-a", "工作区A", "STANDARD", "BUILD")
        advanceUntilIdle()
        val persisted = store.list()
        assertEquals(1, persisted.size)
        val aborted = persisted[0]
        assertEquals(LongTaskStatus.ABORTED, aborted.status)
        assertEquals("被打断的长任务", aborted.goal)
        assertEquals(9, aborted.iterations)
        assertEquals(9, aborted.toolCalls)
        assertEquals(listOf("src/Abort.kt"), aborted.filesTouched)
        assertNull("自动收尾不带错误信息", aborted.errorMessage)

        // 任务 B 独立推进：8 迭代（≥ 8 够格）→ COMPLETED 留档
        for (i in 1..8) {
            toolPair(tracker, i, arguments = """{"path":"src/Next.kt"}""")
        }
        fakeNow += 10_000L
        val bRecord = tracker.endRun(LongTaskStatus.COMPLETED)
        assertNotNull(bRecord)
        bRecord!!
        assertEquals("接棒的新任务", bRecord.goal)
        assertEquals(8, bRecord.iterations)
        assertEquals(listOf("src/Next.kt"), bRecord.filesTouched)

        advanceUntilIdle()
        assertEquals(2, store.list().size)
        // A 的记录不被 B 污染（聚合状态互不泄漏）
        assertEquals(9, store.get(aborted.id)!!.iterations)
        assertEquals(LongTaskStatus.ABORTED, store.get(aborted.id)!!.status)
    }

    // ═════════════════ 场景 F ═════════════════

    /**
     * 场景 F · 工作区隔离与排序：同一 tracker 串行在两个工作区各跑一个长
     * 任务（模拟用户切换工作区）——list(wsId) 只见各自的记录且状态正确，
     * list() 全局按 updatedAt 新到旧排序，list(未知工作区) 为空，阻塞快照
     * snapshotBlocking 与挂起读 list 口径一致。
     */
    @Test
    fun `场景F_工作区隔离_list按工作区裁剪_全局按新到旧排序`() = runTest {
        val tracker = newTracker(this)

        // 工作区一的长任务：9 迭代 × 9 个不同文件，10s 后 COMPLETED
        tracker.beginRun("工作区一的长任务", "ws-one", "工作区一", "DEEP", "BUILD")
        for (i in 1..9) {
            toolPair(tracker, i, arguments = """{"path":"one/F$i.kt"}""")
        }
        fakeNow += 10_000L
        assertNotNull(tracker.endRun(LongTaskStatus.COMPLETED))

        // 工作区二的长任务：9 迭代，20s 后 FAILED（updatedAt 更新）
        tracker.beginRun("工作区二的长任务", "ws-two", "工作区二", "STANDARD", "BUILD")
        for (i in 1..9) {
            toolPair(tracker, i, arguments = """{"path":"two/G$i.kt"}""")
        }
        fakeNow += 20_000L
        assertNotNull(tracker.endRun(LongTaskStatus.FAILED, errorMessage = "工作区二编译失败"))

        advanceUntilIdle()

        // 全局列表：updatedAt 降序（工作区二的新 → 工作区一的旧）
        val all = store.list()
        assertEquals(2, all.size)
        assertEquals(listOf("工作区二的长任务", "工作区一的长任务"), all.map { it.goal })

        // 工作区过滤：只见各自的记录
        val onlyOne = store.list("ws-one")
        assertEquals(1, onlyOne.size)
        assertEquals("ws-one", onlyOne[0].workspaceId)
        assertEquals("工作区一的长任务", onlyOne[0].goal)
        assertEquals(LongTaskStatus.COMPLETED, onlyOne[0].status)

        val onlyTwo = store.list("ws-two")
        assertEquals(1, onlyTwo.size)
        assertEquals("ws-two", onlyTwo[0].workspaceId)
        assertEquals("工作区二的长任务", onlyTwo[0].goal)
        assertEquals(LongTaskStatus.FAILED, onlyTwo[0].status)

        // 未知工作区 → 空；阻塞快照与挂起读一致
        assertTrue(store.list("ws-ghost").isEmpty())
        assertEquals(onlyOne.map { it.id }, store.snapshotBlocking("ws-one").map { it.id })
        assertEquals(all.map { it.id }, store.snapshotBlocking().map { it.id })
    }

    // ═════════════════ 场景 G ═════════════════

    /**
     * 场景 G · 模板实例化启动：instantiate 产出稳定 id（template-前缀 +
     * key + workspaceId）的 RUNNING 模板记录，goal 的百分之 s 占位符已替换
     * 为工作区名、todo 快照即模板骨架（源码语义——骨架随记录入库）；入库后
     * copy 转正为 isTemplate=false 的可跑记录（模板复制语义）。
     */
    @Test
    fun `场景G_模板实例化启动_稳定id入库_复制转正为可跑记录`() = runTest {
        val tpl = LongTaskTemplates.byKey("refactor")
        assertNotNull(tpl)
        tpl!!
        val wsId = "ws-tpl-a1b2"
        val wsName = "模板演示区"

        // —— 实例化：全字段语义 ——
        val record = LongTaskTemplates.instantiate(tpl, wsId, wsName)
        assertEquals("template-refactor-$wsId", record.id) // 稳定 id 格式
        assertTrue(record.isTemplate)
        assertEquals(LongTaskStatus.RUNNING, record.status)
        assertEquals(tpl.recommendedThinkingLevel, record.thinkingLevel)
        assertEquals("BUILD", record.agentMode) // 编码任务默认模式
        assertEquals(tpl.titleZh, record.title)
        // goal 已实例化：占位符全部替换为工作区名（replace 而非 format）
        assertEquals(tpl.goalTemplate.replace("%s", wsName), record.goal)
        assertTrue(record.goal.contains(wsName))
        assertFalse(record.goal.contains("%s"))
        // todo 骨架随记录入库（VM 直接渲染成 todo 面板初稿）
        assertEquals(tpl.todoSkeleton, record.todoSnapshot)
        assertEquals(tpl.tags, record.tags)
        // 运行统计归零的起点形态
        assertNull(record.endedAt)
        assertEquals(0, record.iterations)
        assertEquals(0, record.toolCalls)
        assertEquals(0L, record.durationMs)
        assertTrue(record.filesTouched.isEmpty())
        assertTrue(record.checkpoints.isEmpty())
        assertEquals(record.createdAt, record.updatedAt)

        // —— 入库 + 稳定 id 幂等（同工作区重复实例化是覆盖不是堆积）——
        store.upsert(record)
        advanceUntilIdle()
        assertEquals(record, store.get(record.id))
        val again = LongTaskTemplates.instantiate(tpl, wsId, wsName)
        assertEquals(record.id, again.id)
        // 语义字段等值（仅时间戳允许漂移）
        assertEquals(record.copy(createdAt = again.createdAt, updatedAt = again.updatedAt), again)
        assertEquals(1, store.list(wsId).size) // 仍是同一条记录

        // 不同工作区 → 不同 id（模板菜单在每个工作区各有一份起点）
        val other = LongTaskTemplates.instantiate(tpl, "ws-tpl-zz9", wsName)
        assertFalse(other.id == record.id)

        // —— 复制转正：模板 → 副本 isTemplate=false（可跑的真实运行起点）——
        val copy = copyEngine.copy(record.id, LongTaskCopyOptions()).getOrThrow()
        assertFalse(copy.isTemplate)
        assertEquals(record.id, copy.parentTaskId)
        assertEquals(record.goal, copy.goal) // goal 原样沿用（重跑语义锚点）
        assertEquals(tpl.todoSkeleton, copy.todoSnapshot) // includeTodos 默认开
        assertEquals("系统性重构（副本 1）", copy.title)
        assertEquals(tpl.recommendedThinkingLevel, copy.thinkingLevel) // 无覆盖则沿用推荐档
        assertEquals(1, store.get(record.id)!!.copyCount) // 模板作为复制源的计数
        // 模板与副本都在该工作区列表里
        assertEquals(2, store.list(wsId).size)
    }
}
