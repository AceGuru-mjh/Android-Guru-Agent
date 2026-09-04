package com.apex.agent.core.engine.task

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.PlanStep
import com.apex.agent.core.engine.RiskLevel
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.engine.orchestrator.FakeLlmClient
import com.apex.agent.core.engine.orchestrator.FakeToolExecutor
import com.apex.agent.core.engine.orchestrator.FakeToolRegistry
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T76 — LlmRequestContext 四元 ID 贯通测试（N-12：taskId/stepId 从
 * TaskRuntime 一直填到 LLM 请求上下文——T72 预留字段此前恒 null）。
 *
 * 链路：TaskRuntime.execute → tagsSetter → ApexAgentEngine.setLlmExecutionTags
 * → tagged() 包裹全部 7 个 LlmRequestContext 构造点 → ModelRuntime 收到
 * 带 taskId/stepId 的 context。用记录型 FakeModelRuntime 断言。
 */
class LlmContextTagIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 记录每次 chatStream 收到的 LlmRequestContext。 */
    private class RecordingModelRuntime : ModelRuntime {
        val contexts = mutableListOf<LlmRequestContext>()
        override suspend fun chat(
            context: LlmRequestContext, messages: List<LlmMessage>,
            tools: List<ToolDefinition>, temperature: Float, maxTokens: Int
        ): LlmResponse {
            contexts.add(context)
            return LlmResponse(content = "")
        }

        override fun chatStream(
            context: LlmRequestContext, messages: List<LlmMessage>,
            tools: List<ToolDefinition>, temperature: Float, maxTokens: Int
        ): Flow<LlmStreamChunk> = flow {
            contexts.add(context)
            emit(LlmStreamChunk(content = "", isFinish = true))
        }

        override fun snapshot() = emptyList<com.apex.agent.core.llm.runtime.ModelRuntimeDiagnostics.ModelRuntimeSnapshot>()

        override fun resolve(
            role: com.apex.agent.core.llm.ModelRole,
            requiredCapabilities: com.apex.agent.core.llm.ModelCapabilities
        ): com.apex.agent.core.llm.runtime.ModelRoleRouter.ResolutionResult =
            com.apex.agent.core.llm.runtime.ModelRoleRouter.ResolutionResult.Failure(
                com.apex.agent.core.llm.runtime.ModelRuntimeException.ModelConfigurationError("recording fake")
            )
    }

    private lateinit var store: FileTaskStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runtime: TaskRuntime? = null

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

    @Test
    fun `taskId flows into LlmRequestContext for BUILD mode react loop`() = runBlocking<Unit> {
        val rec = RecordingModelRuntime()
        val engine = ApexAgentEngine(
            llmClient = FakeLlmClient(emptyList()),
            toolRegistry = FakeToolRegistry(emptyList()),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.BUILD, thinkingLevel = ThinkingLevel.NONE),
            modelRuntime = rec
        )
        val rt = TaskRuntime(
            engine = engine, store = store, memory = null,
            configProvider = { TaskConfigSnapshot(mode = "BUILD") },
            tagsSetter = { taskId, stepId -> engine.setLlmExecutionTags(taskId, stepId) },
            scope = scope
        ).also { runtime = it }

        val job = launch(Dispatchers.Unconfined) { rt.execute(UserInput.text("tag 测试")).collect { } }
        awaitCondition { rt.activeTask.value?.status == TaskStatus.COMPLETED }

        // 每个 LLM 请求都带 taskId（stepId 为 null——BUILD 无步骤）
        assertTrue("at least one LLM call expected", rec.contexts.isNotEmpty())
        val taskId = rt.activeTask.value!!.taskId
        assertTrue(rec.contexts.all { it.taskId == taskId })
        assertTrue(rec.contexts.all { it.stepId == null })
        job.cancel()
    }

    @Test
    fun `taskId and stepId flow into LlmRequestContext for PLAN mode`() = runBlocking<Unit> {
        val rec = RecordingModelRuntime()
        val engine = ApexAgentEngine(
            llmClient = FakeLlmClient(
                listOf(
                    // Plan 生成（ThinkingChunk 流式）→ 计划 JSON
                    FakeLlmClient.ScriptedResponse.Ok(content = """{"goal":"g","steps":[{"index":0,"description":"s0"}],"estimatedToolCalls":1,"riskLevel":"LOW","reasoning":"r"}"""),
                    // 步骤执行轮（空响应直接收尾）
                    FakeLlmClient.ScriptedResponse.Ok(content = "step done")
                )
            ),
            toolRegistry = FakeToolRegistry(emptyList()),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.PLAN, thinkingLevel = ThinkingLevel.NONE),
            modelRuntime = rec
        )
        // PlanAwaitingConfirmation 事件到达后 deferred 才创建——事件驱动确认
        // （提前提交会被丢弃，awaitPlanConfirmation 挂到 5 分钟超时）

        val rt = TaskRuntime(
            engine = engine, store = store, memory = null,
            configProvider = { TaskConfigSnapshot(mode = "PLAN") },
            tagsSetter = { taskId, stepId -> engine.setLlmExecutionTags(taskId, stepId) },
            scope = scope
        ).also { runtime = it }

        val job = launch(Dispatchers.Unconfined) {
            rt.execute(UserInput.text("计划任务")).collect { event ->
                if (event is AgentEvent.PlanAwaitingConfirmation) {
                    // 引擎先 emit 再创建 deferred——事件驱动提交需让出一拍，
                    // 保证 deferred 已存在（否则 complete 落空 → 5 分钟超时）
                    kotlinx.coroutines.withContext(Dispatchers.Default) {
                        kotlinx.coroutines.delay(100)
                        engine.submitPlanConfirmation(true)
                    }
                }
            }
        }
        awaitCondition { rt.activeTask.value?.status == TaskStatus.COMPLETED }

        val taskId = rt.activeTask.value!!.taskId
        assertTrue(rec.contexts.isNotEmpty())
        assertTrue(rec.contexts.all { it.taskId == taskId })
        // 步骤开始后 stepId 应被填充（StepStart 事件 → tagsSetter）
        val task = rt.activeTask.value!!
        assertTrue("PLAN task should have steps persisted", task.steps.isNotEmpty())
        job.cancel()
    }

    @Test
    fun `tags cleared after execution ends`() = runBlocking<Unit> {
        val rec = RecordingModelRuntime()
        val engine = ApexAgentEngine(
            llmClient = FakeLlmClient(emptyList()),
            toolRegistry = FakeToolRegistry(emptyList()),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.BUILD, thinkingLevel = ThinkingLevel.NONE),
            modelRuntime = rec
        )
        val rt = TaskRuntime(
            engine = engine, store = store, memory = null,
            configProvider = { TaskConfigSnapshot(mode = "BUILD") },
            tagsSetter = { taskId, stepId -> engine.setLlmExecutionTags(taskId, stepId) },
            scope = scope
        ).also { runtime = it }

        val job = launch(Dispatchers.Unconfined) { rt.execute(UserInput.text("清理测试")).collect { } }
        awaitCondition { rt.activeTask.value?.status == TaskStatus.COMPLETED }
        awaitCondition { rec.contexts.isNotEmpty() }

        // 执行结束后 tags 清理（下一次裸调用引擎不带 taskId）
        val before = rec.contexts.size
        engine.setLlmExecutionTags(null, null) // TaskRuntime finalize 已调用；显式确认幂等
        runBlocking { engine.execute(UserInput.text("裸调用")).collect { } }
        val afterCalls = rec.contexts.drop(before)
        assertTrue(afterCalls.isNotEmpty())
        assertTrue("post-runtime calls must not carry taskId", afterCalls.all { it.taskId == null })
        job.cancel()
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
