package com.apex.agent.core.engine.task

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.PlanStep
import com.apex.agent.core.engine.RiskLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T76 — 上下文压缩兼容测试（任务书 §18J：压缩后不丢失 Task/Step/Execution 状态）。
 *
 * 用 [ScriptedAgentEngine] 精确构造"Plan 确认 → 步骤执行 → 中途压缩"事件序列，
 * 验证：
 * 1. 压缩边界（ContextCompressed 事件）触发任务状态**重注入**（contextInjector
 *    收到含任务进度/步骤状态的消息——N-9：Layer2 滑窗会替换中间消息，任务状态
 *    必须由 TaskRuntime 重新注入受保护位）；
 * 2. checkpoint 的 compressionCount / historyAnchor 更新（contextRef）；
 * 3. 压缩前后 Task/Step/Operation 状态完整保留（步骤进度不因压缩丢失）；
 * 4. 重注入消息内容包含"已完成步骤数 / 当前状态"（LLM 可据此续跑）。
 */
class CompressionCompatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: FileTaskStore
    private var runtime: TaskRuntime? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setup() {
        store = FileTaskStore(tmp.newFolder("taskstore"))
    }

    @After
    fun teardown() {
        runtime?.shutdown()
        runtime = null
        scope.cancel()
    }

    private fun planEvent(steps: Int): AgentEvent.PlanConfirmed = AgentEvent.PlanConfirmed(
        ExecutionPlan(
            goal = "压缩兼容任务",
            steps = (0 until steps).map {
                PlanStep(index = it, description = "步骤$it", toolName = "read_file", estimatedArgs = null)
            },
            estimatedToolCalls = steps,
            riskLevel = RiskLevel.LOW,
            reasoning = "test"
        )
    )

    @Test
    fun `compression boundary re-injects task state and preserves progress`() = runBlocking<Unit> {
        val events = listOf(
            AgentEvent.IterationStart(1),
            planEvent(steps = 3),
            // 步骤 0 完成 + 步骤 1 执行中
            AgentEvent.StepStart(0, "步骤0"),
            AgentEvent.ToolCallStart("t0", "read_file", "{}"),
            AgentEvent.ToolCallComplete("t0", "read_file", "{}", "out0", "out0", true, 10),
            AgentEvent.StepStart(1, "步骤1"),
            AgentEvent.ToolCallStart("t1", "read_file", "{}"),
            AgentEvent.ToolCallComplete("t1", "read_file", "{}", "out1", "out1", true, 10),
            // ═══ 压缩发生在步骤 1 完成后、步骤 2 开始前 ═══
            AgentEvent.ContextCompressed(
                beforeTokens = 100_000, afterTokens = 20_000, strategy = "SLIDING_WINDOW",
                summary = "前文已压缩", messagesRemoved = 40, messagesTruncated = 0
            ),
            // 压缩后继续：步骤 2
            AgentEvent.StepStart(2, "步骤2"),
            AgentEvent.ToolCallStart("t2", "read_file", "{}"),
            AgentEvent.ToolCallComplete("t2", "read_file", "{}", "out2", "out2", true, 10),
            AgentEvent.Complete("done", totalIterations = 3, totalToolCalls = 3, totalDurationMs = 100)
        )
        val engine = ScriptedAgentEngine(events)
        val injected = mutableListOf<String>()
        val rt = TaskRuntime(
            engine = engine, store = store, memory = null,
            configProvider = { TaskConfigSnapshot(mode = "PLAN") },
            contextInjector = { injected.add(it) },
            scope = scope
        ).also { runtime = it }

        val collected = mutableListOf<AgentEvent>()
        val job = launch(Dispatchers.Unconfined) { rt.execute(com.apex.agent.core.engine.UserInput.text("任务")).collect { collected.add(it) } }
        awaitTerminal()

        val task = rt.activeTask.value
        assertNotNull(task)
        assertEquals(TaskStatus.COMPLETED, task!!.status)

        // ═══ 1. 重注入被调用且内容包含任务状态 ═══
        assertEquals("compression boundary must trigger exactly one re-injection", 1, injected.size)
        val msg = injected[0]
        assertTrue("re-injection must carry task title", msg.contains("压缩兼容任务") || msg.contains("TASK STATE"))
        assertTrue("re-injection must carry step progress", msg.contains("进度"))

        // ═══ 2. checkpoint 压缩记录 ═══
        assertEquals(1, task.compressionCount)

        // ═══ 3. 压缩前后状态完整（步骤与操作状态不因压缩丢失）═══
        assertEquals(3, task.steps.size)
        // 全部步骤完成（步骤 2 在压缩后完成——状态保留跨越压缩边界）
        assertEquals(3, task.steps.count { it.status == StepStatus.DONE })
        assertEquals(3, task.completedSteps)
        // 3 个操作全部 SUCCEEDED（journal 完整）
        assertEquals(3, task.operations.size)
        assertTrue(task.operations.all { it.status == OperationStatus.SUCCEEDED })
        // 完成态终稿
        assertEquals(TaskStatus.COMPLETED, store.load(task.taskId)!!.status)
        job.cancel()
    }

    @Test
    fun `multiple compressions are all journaled with re-injections`() = runBlocking<Unit> {
        val events = listOf(
            AgentEvent.ContextCompressed(100, 50, "SLIDING_WINDOW", "s1", 5, 0),
            AgentEvent.ContextCompressed(80, 40, "LLM_SUMMARY", "s2", 4, 0),
            AgentEvent.ContextCompressed(60, 30, "SLIDING_WINDOW", "s3", 3, 0),
            AgentEvent.Complete("done", 1, 0, 10)
        )
        val engine = ScriptedAgentEngine(events)
        val injected = mutableListOf<String>()
        val rt = TaskRuntime(
            engine = engine, store = store, memory = null,
            configProvider = { TaskConfigSnapshot(mode = "BUILD") },
            contextInjector = { injected.add(it) },
            scope = scope
        ).also { runtime = it }

        val job = launch(Dispatchers.Unconfined) { rt.execute(com.apex.agent.core.engine.UserInput.text("多压缩")).collect { } }
        awaitTerminal()

        val task = rt.activeTask.value!!
        assertEquals(3, task.compressionCount)
        assertEquals("every compression boundary re-injects task state", 3, injected.size)
        job.cancel()
    }

    @Test
    fun `task survives crash right after compression - checkpoint carries compressed state`() = runBlocking<Unit> {
        // 崩溃现场：压缩边界落盘后死亡（无终态事件）
        val events = listOf(
            planEvent(steps = 2),
            AgentEvent.StepStart(0, "步骤0"),
            AgentEvent.ToolCallStart("t0", "read_file", "{}"),
            AgentEvent.ToolCallComplete("t0", "read_file", "{}", "out0", "out0", true, 10),
            AgentEvent.ContextCompressed(100_000, 20_000, "SLIDING_WINDOW", "压缩完成", 40, 0)
            // 模拟死亡：事件流到此为止（引擎不发 Complete）
        )
        // tailDelay：压缩事件后保持"执行中"状态（防事件流自然结束触发 finalize
        // 把现场改写 COMPLETED——模拟死亡必须死于执行中）
        val engine = ScriptedAgentEngine(events, tailDelayMs = 60_000)
        val rt = TaskRuntime(
            engine = engine, store = store, memory = null,
            configProvider = { TaskConfigSnapshot(mode = "PLAN") },
            contextInjector = { },
            scope = scope
        ).also { runtime = it }

        val job = launch(Dispatchers.Unconfined) { rt.execute(com.apex.agent.core.engine.UserInput.text("压缩后死亡")).collect { } }
        // 等压缩事件处理完
        awaitCondition { rt.activeTask.value?.compressionCount == 1 }

        // 模拟 SIGKILL
        job.cancel()
        rt.simulateCrash()
        runtime = null

        // 新 runtime 发现：压缩后的 checkpoint 完整（步骤进度未丢失）
        val rt2 = TaskRuntime(
            engine = ScriptedAgentEngine(listOf(AgentEvent.Complete("fin", 1, 0, 1))),
            store = store, memory = null,
            configProvider = { TaskConfigSnapshot(mode = "PLAN") },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ).also { runtime = it }
        val discovered = rt2.discoverRecoverableTasks()
        assertEquals(1, discovered.size)
        val recovered = discovered[0]
        assertEquals(1, recovered.compressionCount)
        // 死于步骤 0 执行中（无后续 StepStart/Complete）→ 压缩边界后步骤状态
        // 原样保留：步骤 0 RUNNING、步骤 1 PENDING（压缩不丢失执行中状态）
        assertEquals(1, recovered.steps.count { it.status == StepStatus.RUNNING })
        assertEquals(1, recovered.steps.count { it.status == StepStatus.PENDING })
        assertEquals(1, recovered.operations.count { it.status == OperationStatus.SUCCEEDED })
        // 继续恢复 → 完成
        val flow = rt2.resumeFromCrash(recovered.taskId)
        assertNotNull(flow)
        val job2 = launch(Dispatchers.Unconfined) { flow!!.collect { } }
        awaitCondition { rt2.activeTask.value?.status == TaskStatus.COMPLETED }
        job2.cancel()
    }

    // ═══ helpers ═══

    private fun awaitTerminal(timeoutMs: Long = 10_000) {
        awaitCondition(timeoutMs) {
            runtime?.activeTask?.value?.status.let { it == TaskStatus.COMPLETED || it == TaskStatus.FAILED }
        }
    }

    private fun awaitCondition(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met in ${timeoutMs}ms")
    }
}
