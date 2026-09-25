package com.apex.agent.core.engine.plan

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
import com.apex.agent.core.llm.LlmMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #169 引擎级人控测试 —— 确认时传 enabledSteps 子集 → 仅执行启用步骤；
 * 旧两参签名（legacy PlanDecision）→ 全量执行。
 *
 * 时序要点（同 LlmContextTagIntegrationTest）：引擎**先 emit
 * PlanAwaitingConfirmation 再创建 deferred**，事件驱动提交必须让出一拍，
 * 否则 complete 落空 → awaitPlanConfirmationDecision 挂到 5 分钟超时。
 */
class PlanModeHumanControlTest {

    private val planJson = """
        {"goal":"g","steps":[
            {"index":0,"description":"s0"},
            {"index":1,"description":"s1"},
            {"index":2,"description":"s2"}],
         "estimated_tool_calls":3,"risk_level":"low","reasoning":"r"}
    """.trimIndent()

    private fun awaitCondition(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met in ${timeoutMs}ms")
    }

    @Test
    fun `confirming with enabledSteps subset executes only enabled steps`() = runBlocking<Unit> {
        val memory = FakeConversationMemory()
        val fake = FakeLlmClient(
            listOf(
                // Phase 1：计划生成（规划期只读，无工具调用）
                FakeLlmClient.ScriptedResponse.Ok(content = planJson),
                // Phase 4：仅启用的两步各一轮（锁定重编号 0、1）
                FakeLlmClient.ScriptedResponse.Ok(content = "done 0"),
                FakeLlmClient.ScriptedResponse.Ok(content = "done 1"),
                // Phase 5：反思
                FakeLlmClient.ScriptedResponse.Ok(content = "reflection")
            )
        )
        val engine = ApexAgentEngine(
            llmClient = fake,
            toolRegistry = FakeToolRegistry(emptyList()),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.PLAN, thinkingLevel = ThinkingLevel.NONE),
            memory = memory
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch(Dispatchers.Unconfined) {
            engine.execute(UserInput.text("计划任务")).collect { event ->
                events.add(event)
                if (event is AgentEvent.PlanAwaitingConfirmation) {
                    // 让出一拍保证 deferred 已注册，再提交「启用 s0/s2、声明顺序」
                    launch(Dispatchers.Default) {
                        delay(150)
                        engine.submitPlanConfirmation(true, enabledSteps = listOf(0, 2), order = null)
                    }
                }
            }
        }
        awaitCondition { events.any { it is AgentEvent.Complete } }
        job.cancel()

        // ── 仅执行启用步骤（s1 被跳过），锁定后重编号 0..1 ──
        val stepStarts = events.filterIsInstance<AgentEvent.StepStart>()
        assertEquals(listOf(0, 1), stepStarts.map { it.stepIndex })
        assertEquals(listOf("s0", "s2"), stepStarts.map { it.description })

        // ── PlanConfirmed 携带锁定计划（筛选后 2 步）──
        val confirmed = events.filterIsInstance<AgentEvent.PlanConfirmed>().single()
        assertEquals(listOf("s0", "s2"), confirmed.plan.steps.map { it.description })
        assertEquals(listOf(0, 1), confirmed.plan.steps.map { it.index })

        // ── 锁定播报已写入持久化记忆（ConversationMemory 自动持久化）──
        val persisted = memory.snapshot()
            .mapNotNull { (it as? LlmMessage.System)?.content }
        val lockMsg = persisted.firstOrNull { it.contains("计划已锁定") }
        assertTrue("锁定播报应持久化：$persisted", lockMsg != null)
        assertTrue(lockMsg!!.contains("2 步"))
    }

    @Test
    fun `legacy boolean confirmation executes all steps`() = runBlocking<Unit> {
        val memory = FakeConversationMemory()
        val fake = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(content = planJson),
                FakeLlmClient.ScriptedResponse.Ok(content = "done 0"),
                FakeLlmClient.ScriptedResponse.Ok(content = "done 1"),
                FakeLlmClient.ScriptedResponse.Ok(content = "done 2"),
                FakeLlmClient.ScriptedResponse.Ok(content = "reflection")
            )
        )
        val engine = ApexAgentEngine(
            llmClient = fake,
            toolRegistry = FakeToolRegistry(emptyList()),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.PLAN, thinkingLevel = ThinkingLevel.NONE),
            memory = memory
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch(Dispatchers.Unconfined) {
            engine.execute(UserInput.text("计划任务")).collect { event ->
                events.add(event)
                if (event is AgentEvent.PlanAwaitingConfirmation) {
                    launch(Dispatchers.Default) {
                        delay(150)
                        engine.submitPlanConfirmation(true) // 旧签名（ConfirmationSink 兼容路径）
                    }
                }
            }
        }
        awaitCondition { events.any { it is AgentEvent.Complete } }
        job.cancel()

        assertEquals(listOf("s0", "s1", "s2"), events.filterIsInstance<AgentEvent.StepStart>().map { it.description })
        val confirmed = events.filterIsInstance<AgentEvent.PlanConfirmed>().single()
        assertEquals(3, confirmed.plan.steps.size)
        // 旧路径无用户调整 → 播报不出现「用户调整」
        val lockMsg = memory.snapshot()
            .mapNotNull { (it as? LlmMessage.System)?.content }
            .first { it.contains("计划已锁定") }
        assertTrue(!lockMsg.contains("用户调整"))
    }

    @Test
    fun `rejection aborts without executing any step`() = runBlocking<Unit> {
        val fake = FakeLlmClient(
            listOf(FakeLlmClient.ScriptedResponse.Ok(content = planJson))
        )
        val engine = ApexAgentEngine(
            llmClient = fake,
            toolRegistry = FakeToolRegistry(emptyList()),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.PLAN, thinkingLevel = ThinkingLevel.NONE)
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch(Dispatchers.Unconfined) {
            engine.execute(UserInput.text("计划任务")).collect { event ->
                events.add(event)
                if (event is AgentEvent.PlanAwaitingConfirmation) {
                    launch(Dispatchers.Default) {
                        delay(150)
                        engine.submitPlanConfirmation(false, enabledSteps = listOf(0), order = null)
                    }
                }
            }
        }
        awaitCondition { events.any { it is AgentEvent.Complete } }
        job.cancel()

        assertTrue(events.contains(AgentEvent.Aborted))
        assertTrue(events.filterIsInstance<AgentEvent.StepStart>().isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.PlanConfirmed>().isEmpty())
    }
}
