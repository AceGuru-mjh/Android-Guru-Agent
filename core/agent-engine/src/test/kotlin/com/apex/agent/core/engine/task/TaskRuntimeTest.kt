package com.apex.agent.core.engine.task

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.engine.orchestrator.FakeConversationMemory
import com.apex.agent.core.engine.orchestrator.FakeLlmClient
import com.apex.agent.core.engine.orchestrator.FakeToolExecutor
import com.apex.agent.core.engine.orchestrator.FakeToolRegistry
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * T76 — TaskRuntime 全链测试（任务书 §18 C/D/F/G/H + 幂等）。
 *
 * 策略（任务书 §23）：**确定性模拟进程死亡**——第一个 TaskRuntime 实例
 * 执行到中途，直接抛弃实例（不调任何收尾方法，模拟进程被杀）；
 * 第二个 TaskRuntime 实例共享同一 store 目录 + memory，模拟重启后发现
 * 与恢复。**真实 FileTaskStore（临时目录）**，非 Fake 存储。
 *
 * 引擎链路全真实：ApexAgentEngine + FakeLlmClient（脚本化响应）+
 * FakeToolExecutor（脚本化工具）+ FakeConversationMemory（内存但持久
 * 语义与 SharedPrefs 实现一致：跨实例保持）。
 */
class TaskRuntimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var storeDir: java.io.File
    private lateinit var store: FileTaskStore
    private lateinit var memory: FakeConversationMemory
    private var runtime: TaskRuntime? = null

    @Before
    fun setup() {
        storeDir = tmp.newFolder("taskstore")
        store = FileTaskStore(storeDir)
        memory = FakeConversationMemory()
    }

    @After
    fun teardown() {
        runtime?.shutdown()
        runtime = null
    }

    // ═══ 测试基建 ═══

    private fun newEngine(
        llm: FakeLlmClient,
        tools: FakeToolExecutor
    ): ApexAgentEngine = ApexAgentEngine(
        llmClient = llm,
        toolRegistry = FakeToolRegistry(emptyList()),
        toolExecutor = tools,
        config = AgentConfig(
            mode = AgentMode.BUILD,
            thinkingLevel = ThinkingLevel.NONE,
            maxIterations = 8
        ),
        memory = memory
    )

    private fun newRuntime(
        engine: ApexAgentEngine,
        scope: CoroutineScope
    ): TaskRuntime = TaskRuntime(
        engine = engine,
        store = store,
        memory = memory,
        configProvider = {
            TaskConfigSnapshot(mode = "BUILD", maxIterations = 8)
        },
        scope = scope
    ).also { runtime = it }

    /**
     * 收集事件直到执行流自然关闭（带超时防挂起）。
     *
     * 关键：必须等到 flow 完成而非仅看到 Complete 事件——TaskRuntime 的终态
     * 落盘 + claim 释放在 mirror collector 的 finally（finalizeStream）中执行，
     * 而 tap.close()（触发 flow 完成）发生在 finalizeStream 之后。提前在
     * Complete 事件上 cancel 会让后续 retry/二次 execute 与 finalizeStream 竞态
     * （claim 未释放 → "rejects concurrent execution"，或终态未落盘 → store 落
     * RUNNING）。等待 flow 关闭即保证 finalizeStream 已完成。
     */
    private fun collectUntilTerminal(flow: kotlinx.coroutines.flow.Flow<AgentEvent>): List<AgentEvent> =
        runBlocking {
            val events = CopyOnWriteArrayList<AgentEvent>()
            withTimeout(15_000L) {
                flow.collect { event -> events.add(event) }
            }
            assertTrue(
                "execution should reach terminal event, last was ${events.lastOrNull()}",
                events.any { it is AgentEvent.Complete || it is AgentEvent.Aborted }
            )
            events.toList()
        }

    private fun waitTerminal(status: TaskStatus, timeoutMs: Long = 10_000): AgentTask {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runtime!!.activeTask.value?.takeIf { it.status == status }?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("task never reached $status; was ${runtime!!.activeTask.value?.status}")
    }

    // ═══ C: Checkpoint 生命周期边界落盘 ═══

    @Test
    fun `tool call boundaries are journaled to checkpoint`() {
        val llm = FakeLlmClient(
            listOf(
                // 轮1：调 read_file
                FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("call_1", "read_file", """{"path":"a"}"""))),
                // 轮2：纯文本收尾
                FakeLlmClient.ScriptedResponse.Ok(content = "done")
            )
        )
        val tools = FakeToolExecutor().apply { registerSuccess("read_file", "content-a") }
        val engine = newEngine(llm, tools)
        val rt = newRuntime(engine, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        collectUntilTerminal(rt.execute(UserInput.text("读取 a 文件")))

        val task = waitTerminal(TaskStatus.COMPLETED)
        assertEquals(1, task.operations.size)
        val op = task.operations[0]
        assertEquals("call_1", op.llmCallId)
        assertEquals("read_file", op.toolName)
        assertEquals(OperationStatus.SUCCEEDED, op.status)
        assertEquals(ToolIdempotencyClass.READ_ONLY, op.idempotency)
        assertEquals("content-a", op.outputDigest)

        // store 落盘验证（跨实例读回）
        assertEquals(TaskStatus.COMPLETED, store.load(task.taskId)!!.status)
        // 状态机全程合法：COMPLETED 终态 + completedAt 戳
        assertTrue(task.completedAt > 0)
    }

    @Test
    fun `failed tool operation is journaled as FAILED`() {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("call_1", "shell_execute", "{}"))),
                FakeLlmClient.ScriptedResponse.Ok(content = "recovered")
            )
        )
        val tools = FakeToolExecutor().apply {
            register("shell_execute", FakeToolExecutor.ScriptedTool.Events(listOf(ToolStreamEvent.Error("boom"))))
        }
        val engine = newEngine(llm, tools)
        val rt = newRuntime(engine, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        collectUntilTerminal(rt.execute(UserInput.text("跑个命令")))

        val task = waitTerminal(TaskStatus.COMPLETED)
        assertEquals(1, task.operations.size)
        assertEquals(OperationStatus.FAILED, task.operations[0].status)
        assertEquals(ToolIdempotencyClass.UNKNOWN, task.operations[0].idempotency)
    }

    // ═══ D: Pause → Resume 持久语义 ═══

    @Test
    fun `pause lands PAUSED and resume continues from checkpoint`() = runBlocking<Unit> {
        // 3 轮脚本：工具 → 工具 → 文本。工具慢速执行（Delay）制造可捕获的暂停窗口。
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("c1", "read_file", "{}"))),
                FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("c2", "read_file", "{}"))),
                FakeLlmClient.ScriptedResponse.Ok(content = "all done"),
                // resume 轮次：续跑到完成
                FakeLlmClient.ScriptedResponse.Ok(content = "resumed and finished")
            )
        )
        val tools = FakeToolExecutor().apply {
            register("read_file", FakeToolExecutor.ScriptedTool.Delay(delayMs = 400))
        }
        val engine = newEngine(llm, tools)
        val rt = newRuntime(engine, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        val events = CopyOnWriteArrayList<AgentEvent>()
        val job = launch(Dispatchers.Unconfined) { rt.execute(UserInput.text("两步任务")).collect { events.add(it) } }
        // 等 c2 进入 RUNNING（Delay 工具执行中——确保第二轮迭代已启动且 pause
        // 不落在迭代边界导致第二轮被 abort 短路）
        waitCondition { rt.activeTask.value?.operations?.any { it.llmCallId == "c2" && it.status == OperationStatus.RUNNING } == true }

        val paused = rt.pause()
        job.cancel()
        assertTrue(paused)
        val task = waitTerminal(TaskStatus.PAUSED)
        assertEquals(2, task.operations.size)
        assertEquals(2, task.operations.count { it.status == OperationStatus.SUCCEEDED })

        // 磁盘上是 PAUSED（重启后语义正确）
        assertEquals(TaskStatus.PAUSED, store.load(task.taskId)!!.status)

        // Resume：返回新流，任务续跑（journal 序号接续）
        val resumedFlow = rt.resume()
        assertNotNull(resumedFlow)
        collectUntilTerminal(resumedFlow!!)
        val done = waitTerminal(TaskStatus.COMPLETED)
        // journal 不丢：2 个旧操作 + resume 期间无新工具（直接文本收尾）
        assertTrue(done.operations.size >= 2)
        assertEquals(2, done.operations.count { it.status == OperationStatus.SUCCEEDED })
        // 状态迁移链 PAUSED → RUNNING → COMPLETED 合法完成
        assertEquals(TaskStatus.COMPLETED, store.load(done.taskId)!!.status)
    }

    // ═══ G: Cancel 终态 + 重启不自愈 ═══

    @Test
    fun `cancel lands CANCELLED terminal and is NOT auto-resumed on discovery`() = runBlocking<Unit> {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("c1", "read_file", "{}"))),
                FakeLlmClient.ScriptedResponse.Ok(content = "done")
            )
        )
        val tools = FakeToolExecutor().apply {
            register("read_file", FakeToolExecutor.ScriptedTool.Delay(delayMs = 400))
        }
        val engine = newEngine(llm, tools)
        val rt = newRuntime(engine, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        val job = launch(Dispatchers.Unconfined) { rt.execute(UserInput.text("将被取消")).collect { } }
        waitCondition { rt.activeTask.value?.operations?.any { it.status == OperationStatus.RUNNING } == true }

        val cancelled = rt.cancel()
        job.cancel()
        assertTrue(cancelled)
        waitTerminal(TaskStatus.CANCELLED)
        assertEquals(TaskStatus.CANCELLED, store.load(rt.activeTask.value!!.taskId)!!.status)

        // 重启模拟：新 runtime 实例发现——CANCELLED 不在恢复列表
        val rt2 = newRuntime(engine, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val discovered = rt2.discoverRecoverableTasks()
        assertTrue(discovered.isEmpty()) // CANCELLED 绝不自动继续（与 PAUSED 的区别）
        assertEquals(0, rt2.loadTaskHistory().count { it.isActive })
    }

    // ═══ F: 崩溃恢复（模拟进程死亡）═══

    @Test
    fun `crash recovery discovers interrupted task and continues`() = runBlocking<Unit> {
        // 阶段1：第一个 runtime 跑到工具执行中（journal: c1 SUCCEEDED），然后"死亡"——
        // 不调 abort/pause，直接 shutdown scope（丢弃收尾），store 留下 RUNNING 现场。
        val llm1 = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("c1", "read_file", "{}"))),
                FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("c2", "write_file", "{}"))) // 死于此后
            )
        )
        val tools1 = FakeToolExecutor().apply {
            registerSuccess("read_file", "ok")
            // write_file 慢速执行：进程死于其执行中（journal c2 RUNNING + history 悬空）
            register("write_file", FakeToolExecutor.ScriptedTool.Delay(delayMs = 60_000))
        }
        val engine1 = newEngine(llm1, tools1)
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rt1 = newRuntime(engine1, scope1)
        val job1 = launch(Dispatchers.Unconfined) { rt1.execute(UserInput.text("长任务")).collect { } }
        waitCondition {
            val ops = rt1.activeTask.value?.operations
            ops?.any { it.llmCallId == "c1" && it.status == OperationStatus.SUCCEEDED } == true &&
                ops.any { it.llmCallId == "c2" && it.status == OperationStatus.RUNNING }
        }

        // ═══ 模拟进程死亡：短路 finalize 的专用钩子（真实 SIGKILL 无 finally）═══
        job1.cancel()
        rt1.simulateCrash()
        runtime = null // teardown 不再 shutdown 已死实例

        // 阶段2："重启"——同目录 store + 同 memory，新实例发现恢复
        val llm2 = FakeLlmClient(
            listOf(FakeLlmClient.ScriptedResponse.Ok(content = "recovered and finished"))
        )
        val engine2 = newEngine(llm2, FakeToolExecutor())
        val rt2 = newRuntime(engine2, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val recovered = rt2.discoverRecoverableTasks()

        assertEquals(1, recovered.size)
        val crashed = recovered[0]
        assertEquals(TaskStatus.RECOVERING, crashed.status)
        assertEquals("长任务", crashed.userInput)
        // c1 已成功：journal 保留
        assertTrue(crashed.operations.any { it.llmCallId == "c1" && it.status == OperationStatus.SUCCEEDED })

        // 用户选择继续 → 恢复执行 → 完成
        val flow = rt2.resumeFromCrash(crashed.taskId)
        assertNotNull(flow)
        collectUntilTerminal(flow!!)
        val done = waitTerminal(TaskStatus.COMPLETED)
        // 幂等核心验收：c1 成功操作绝不被重放（tools2 未注册 read_file，重放即报错失败）
        assertEquals(1, done.operations.count { it.status == OperationStatus.SUCCEEDED })
        assertEquals(TaskStatus.COMPLETED, store.load(done.taskId)!!.status)
    }

    @Test
    fun `crash mid-tool leaves RUNNING op which becomes UNKNOWN recovery input`() = runBlocking<Unit> {
        // 死在工具执行中：journal 有 c1 RUNNING（未收到 ToolCallComplete）
        val llm1 = FakeLlmClient(
            listOf(FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("c1", "read_file", "{}"))))
        )
        val tools1 = FakeToolExecutor().apply {
            register("read_file", FakeToolExecutor.ScriptedTool.Delay(delayMs = 60_000))
        }
        val engine1 = newEngine(llm1, tools1)
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rt1 = newRuntime(engine1, scope1)
        val job1 = launch(Dispatchers.Unconfined) { rt1.execute(UserInput.text("工具中死亡")).collect { } }
        // 等 ToolCallStart 落盘（journal 有 RUNNING 态 c1），不等 Complete
        waitCondition { rt1.activeTask.value?.operations?.any { it.llmCallId == "c1" && it.status == OperationStatus.RUNNING } == true }

        job1.cancel()
        rt1.simulateCrash()
        runtime = null

        val engine2 = newEngine(FakeLlmClient(listOf(FakeLlmClient.ScriptedResponse.Ok(content = "fin"))), FakeToolExecutor())
        val rt2 = newRuntime(engine2, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val recovered = rt2.discoverRecoverableTasks()

        assertEquals(1, recovered.size)
        // 恢复扫描后：RUNNING → 保留原状（RecoveryPolicy 计划时按 UNKNOWN 语义处理）
        val plan = RecoveryPolicy.planForTask(
            recovered[0],
            classify = { toolPolicyId -> ToolExecutionPolicy().classify(toolPolicyId) },
            isUserInteraction = { false }
        )
        assertEquals(1, plan.unknownOperations.size)
        // read_file 是 READ_ONLY → RETRY 决策
        assertEquals(1, plan.retryOperations.size)
        assertFalse(plan.highRisk)
    }

    // ═══ H: 并发 resume 互斥 ═══

    @Test
    fun `concurrent resumes - only one succeeds`() = runBlocking<Unit> {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("c1", "read_file", "{}"))),
                FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("c2", "read_file", "{}"))),
                FakeLlmClient.ScriptedResponse.Ok(content = "done"),
                FakeLlmClient.ScriptedResponse.Ok(content = "resumed")
            )
        )
        val tools = FakeToolExecutor().apply {
            register("read_file", FakeToolExecutor.ScriptedTool.Delay(delayMs = 600))
        }
        val engine = newEngine(llm, tools)
        val rt = newRuntime(engine, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        val job = launch(Dispatchers.Unconfined) { rt.execute(UserInput.text("暂停后并发恢复")).collect { } }
        waitCondition { rt.activeTask.value?.operations?.any { it.llmCallId == "c2" && it.status == OperationStatus.RUNNING } == true }
        rt.pause()
        job.cancel()
        waitTerminal(TaskStatus.PAUSED)

        // 并发两次 resume：CAS 互斥只允许一个拿到执行流
        val first = rt.resume()
        val second = rt.resume()
        assertNotNull(first)
        assertNull("second concurrent resume must be rejected", second)

        collectUntilTerminal(first!!)
        waitTerminal(TaskStatus.COMPLETED)
    }

    @Test
    fun `concurrent execute attempts - second is rejected`() = runBlocking<Unit> {
        val llm = FakeLlmClient(
            listOf(FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("c1", "read_file", "{}"))))
        )
        val tools = FakeToolExecutor().apply {
            register("read_file", FakeToolExecutor.ScriptedTool.Delay(delayMs = 800))
        }
        val engine = newEngine(llm, tools)
        val rt = newRuntime(engine, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        val first = rt.execute(UserInput.text("第一个"))
        // 第一次执行在跑（工具 Delay 中）→ 第二次 execute 必须被互斥拒绝
        val second = try {
            rt.execute(UserInput.text("第二个"))
            null // 不应到达
        } catch (e: IllegalStateException) {
            "rejected"
        }
        assertEquals("rejected", second)
        collectUntilTerminal(first)
        waitTerminal(TaskStatus.COMPLETED)
    }

    // ═══ Retry 语义（上限 + 状态链）═══

    @Test
    fun `retry failed task increments count and respects limit`() = runBlocking<Unit> {
        // LLM 恒抛错 → 引擎 emit Error(recoverable=false) → FAILED。
        // 3 个 Throw：初始执行 + 2 次重试各消耗一个（脚本耗尽会返回空响应→
        // recoverable=true→正常收尾→COMPLETED，覆盖不到 FAILED 语义）。
        val llm = FakeLlmClient(
            (1..3).map { FakeLlmClient.ScriptedResponse.Throw(RuntimeException("provider down")) }
        )
        val engine = newEngine(llm, FakeToolExecutor())
        val rt = TaskRuntime(
            engine = engine, store = store, memory = memory,
            configProvider = { TaskConfigSnapshot(mode = "BUILD") },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            retryLimit = 2
        ).also { runtime = it }

        collectUntilTerminal(rt.execute(UserInput.text("会失败")))
        val failed = waitTerminal(TaskStatus.FAILED)
        assertEquals("provider down", failed.error)

        // 重试1：RETRYING → RUNNING（LLM 仍失败 → FAILED，retryCount=1）
        val r1 = rt.retry()
        assertNotNull(r1)
        collectUntilTerminal(r1!!)
        val failed1 = waitTerminal(TaskStatus.FAILED)
        assertEquals(1, failed1.retryCount)

        // 重试2：达到上限（retryLimit=2）→ 第三次拒绝
        val r2 = rt.retry()
        assertNotNull(r2)
        collectUntilTerminal(r2!!)
        waitTerminal(TaskStatus.FAILED)

        val r3 = rt.retry()
        assertNull("retry beyond limit must be rejected", r3)
        assertEquals(2, rt.activeTask.value?.retryCount)
    }

    // ═══ 失败任务的迁移合法性（FAILED→CANCELLED 放弃路径）═══

    @Test
    fun `abandoning failed task transitions to CANCELLED`() = runBlocking<Unit> {
        val llm = FakeLlmClient(listOf(FakeLlmClient.ScriptedResponse.Throw(RuntimeException("fatal"))))
        val engine = newEngine(llm, FakeToolExecutor())
        val rt = newRuntime(engine, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        collectUntilTerminal(rt.execute(UserInput.text("失败后放弃")))
        waitTerminal(TaskStatus.FAILED)

        assertTrue(rt.cancel())
        waitTerminal(TaskStatus.CANCELLED)
        // FAILED → CANCELLED 合法（放弃路径）
        assertEquals(TaskStatus.CANCELLED, store.load(rt.activeTask.value!!.taskId)!!.status)
    }

    // ═══ PAUSED 重启后发现（用户决定，不自动续跑）═══

    @Test
    fun `paused task survives restart discovery without auto-resume`() = runBlocking<Unit> {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("c1", "read_file", "{}"))),
                FakeLlmClient.ScriptedResponse.Ok(toolCalls = listOf(ToolCall("c2", "read_file", "{}"))),
                FakeLlmClient.ScriptedResponse.Ok(content = "done"),
                FakeLlmClient.ScriptedResponse.Ok(content = "resumed-finish")
            )
        )
        val tools = FakeToolExecutor().apply {
            register("read_file", FakeToolExecutor.ScriptedTool.Delay(delayMs = 400))
        }
        val engine = newEngine(llm, tools)
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rt1 = newRuntime(engine, scope1)
        val job = launch(Dispatchers.Unconfined) { rt1.execute(UserInput.text("暂停跨重启")).collect { } }
        waitCondition { rt1.activeTask.value?.operations?.any { it.llmCallId == "c2" && it.status == OperationStatus.RUNNING } == true }
        rt1.pause()
        job.cancel()
        waitTerminal(TaskStatus.PAUSED)
        runtime = null // pause 已自然收尾；不 kill scope（PAUSED 现场已落盘）

        // 重启：发现 PAUSED 任务（保持 PAUSED，不自动续跑）
        val rt2 = newRuntime(engine, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val discovered = rt2.discoverRecoverableTasks()
        assertEquals(1, discovered.size)
        assertEquals(TaskStatus.PAUSED, discovered[0].status)
        // 无执行流被启动（claiming 空闲）——用户必须显式 resume
        val flow = rt2.resume()
        assertNotNull("user-initiated resume works", flow)
        collectUntilTerminal(flow!!)
        waitTerminal(TaskStatus.COMPLETED)
    }

    // ═══ 悬空修补集成：崩溃后 memory 历史被修补 ═══

    @Test
    fun `dangling toolCall in memory is repaired during recovery discovery`() = runBlocking<Unit> {
        // 构造"死亡现场"：memory 里有 Assistant(c1,c2) + ToolResult(c1)——c2 悬空
        memory.apply {
            save(
                listOf(
                    com.apex.agent.core.llm.LlmMessage.User("start"),
                    com.apex.agent.core.llm.LlmMessage.Assistant(
                        "calling",
                        listOf(ToolCall("c1", "read_file", "{}"), ToolCall("c2", "write_file", "{}"))
                    ),
                    com.apex.agent.core.llm.LlmMessage.ToolResult("c1", "ok")
                )
            )
        }
        // store 里有 RUNNING 态任务（模拟崩溃现场）
        val crashed = AgentTask(
            taskId = "task-crash-0001", title = "t", userInput = "恢复任务", mode = "BUILD",
            status = TaskStatus.RUNNING, createdAt = 1L
        )
        store.save(crashed)

        val engine = newEngine(FakeLlmClient(listOf(FakeLlmClient.ScriptedResponse.Ok(content = "fin"))), FakeToolExecutor())
        val rt = newRuntime(engine, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val discovered = rt.discoverRecoverableTasks()

        assertEquals(1, discovered.size)
        // memory 修补完成：c2 有了配对 ToolResult（OpenAI 配对不变量恢复）
        val history = memory.load()
        val repaired = DanglingToolCallRepair.repair(history)
        assertFalse(repaired.hasRepairs) // 修补已幂等完成
        assertTrue(history.any { it is com.apex.agent.core.llm.LlmMessage.ToolResult && it.toolCallId == "c2" })
    }

    // ═══ 辅助 ═══

    private fun waitCondition(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }
}
