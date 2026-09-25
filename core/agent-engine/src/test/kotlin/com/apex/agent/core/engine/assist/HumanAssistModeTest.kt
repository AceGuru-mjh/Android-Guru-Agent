package com.apex.agent.core.engine.assist

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.InputType
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #168 引擎级 HUMAN_ASSIST 测试 —— 模型写了「方案A…方案B…」对比文本但没调
 * ask_user_choice 时，引擎自动拦截转人工决策闭环。
 *
 * 时序要点（同 PlanModeHumanControlTest）：引擎**先 emit
 * UserInputRequired 再挂起 awaitUserInput**，事件驱动提交必须让出一拍
 * （delay 150ms），否则 complete 落空 → 等待 5 分钟超时。
 */
class HumanAssistModeTest {

    /** 第 1 轮：模型摆出方案对比但没调 ask_user_choice（提示词没管住的现实场景）。 */
    private val decisionText = """
        我发现有两种处理方式：
        方案A：直接删除整个目录再重建，最快但会丢自定义配置。
        方案B：逐项迁移配置后再删除，慢但保数据。
        你想怎么处理？
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
    fun `decision point in response pauses for user choice then loop continues`() = runBlocking<Unit> {
        val memory = FakeConversationMemory()
        val fake = FakeLlmClient(
            listOf(
                // 第 1 轮：方案对比文本（纯文本响应 → 触发决策点拦截）
                FakeLlmClient.ScriptedResponse.Ok(content = decisionText),
                // 第 2 轮：拿到用户选择后的最终回答
                FakeLlmClient.ScriptedResponse.Ok(content = "已按你选择的方案处理完成。")
            )
        )
        val engine = ApexAgentEngine(
            llmClient = fake,
            toolRegistry = FakeToolRegistry(emptyList()),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.HUMAN_ASSIST, thinkingLevel = ThinkingLevel.NONE),
            memory = memory
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch(Dispatchers.Unconfined) {
            engine.execute(UserInput.text("清理配置目录")).collect { event ->
                events.add(event)
                if (event is AgentEvent.UserInputRequired) {
                    // 让出一拍保证 userInputDeferred 已注册，再提交用户选择
                    launch(Dispatchers.Default) {
                        delay(150)
                        engine.submitUserInput("B")
                    }
                }
            }
        }
        awaitCondition { events.any { it is AgentEvent.Complete } }
        job.cancel()

        // ── 1. UserInputRequired(CHOICE) 已发出，question 含两个选项编码 ──
        val required = events.filterIsInstance<AgentEvent.UserInputRequired>().single()
        assertEquals(InputType.CHOICE, required.type)
        assertTrue(required.prompt.contains("逐项迁移配置后再删除")) // 方案B label 在选项行中

        // ── 2. 用户选择已回填为 User 消息并持久化（下一轮 LLM 能看到）──
        val userFollowUps = memory.snapshot()
            .mapNotNull { (it as? LlmMessage.User)?.content }
            .filter { it != "清理配置目录" } // 排除原始输入
        val followUp = userFollowUps.singleOrNull()
        assertTrue(
            "用户选择应回填为「用户选择：B（…）——请按该选择继续」：$followUp",
            followUp != null && followUp.startsWith("用户选择：B") && followUp.endsWith("请按该选择继续")
        )
        // 草稿（方案对比文本）也已入历史，选择有上下文
        assertTrue(memory.snapshot().any { (it as? LlmMessage.Assistant)?.content == decisionText })

        // ── 3. 第 2 次请求携带了用户选择消息（决策真实进入上下文）──
        assertEquals(2, fake.callLog.size)
        val secondCallMessages = fake.callLog[1].first
        assertTrue(
            "第 2 轮请求应包含用户选择消息",
            secondCallMessages.any { it is LlmMessage.User && it.content.startsWith("用户选择：B") }
        )

        // ── 4. 循环继续并正常收尾：ResponseComplete = 第 2 轮文本 ──
        val complete = events.filterIsInstance<AgentEvent.ResponseComplete>().single()
        assertEquals("已按你选择的方案处理完成。", complete.fullText)
        assertTrue(events.any { it is AgentEvent.Complete })

        // ── 5. 两轮迭代（IterationStart ×2）──
        assertEquals(2, events.filterIsInstance<AgentEvent.IterationStart>().size)
    }

    @Test
    fun `response without decision point completes immediately`() = runBlocking<Unit> {
        val fake = FakeLlmClient(
            listOf(FakeLlmClient.ScriptedResponse.Ok(content = "目录已清理完毕，无残留文件。"))
        )
        val engine = ApexAgentEngine(
            llmClient = fake,
            toolRegistry = FakeToolRegistry(emptyList()),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.HUMAN_ASSIST, thinkingLevel = ThinkingLevel.NONE)
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch(Dispatchers.Unconfined) {
            engine.execute(UserInput.text("清理目录")).collect { events.add(it) }
        }
        awaitCondition { events.any { it is AgentEvent.Complete } }
        job.cancel()

        // 无决策点 → 不拦截：无 UserInputRequired，单轮直接完成
        assertTrue(events.none { it is AgentEvent.UserInputRequired })
        assertEquals(1, fake.callLog.size)
        assertEquals("目录已清理完毕，无残留文件。", events.filterIsInstance<AgentEvent.ResponseComplete>().single().fullText)
    }

    @Test
    fun `empty answer times out gracefully and draft becomes final response`() = runBlocking<Unit> {
        val fake = FakeLlmClient(
            listOf(FakeLlmClient.ScriptedResponse.Ok(content = decisionText))
        )
        val engine = ApexAgentEngine(
            llmClient = fake,
            toolRegistry = FakeToolRegistry(emptyList()),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.HUMAN_ASSIST, thinkingLevel = ThinkingLevel.NONE)
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch(Dispatchers.Unconfined) {
            engine.execute(UserInput.text("清理配置目录")).collect { event ->
                events.add(event)
                if (event is AgentEvent.UserInputRequired) {
                    launch(Dispatchers.Default) {
                        delay(150)
                        engine.cancelUserInput() // 用户取消 → 空答复
                    }
                }
            }
        }
        awaitCondition { events.any { it is AgentEvent.Complete } }
        job.cancel()

        // 事件发出过（UI 弹过选择菜单），但空答复 → 安全降级：不拦截，
        // 草稿作为最终回复收尾（绝不因拦截失败丢掉已生成的回复）。
        assertEquals(1, events.filterIsInstance<AgentEvent.UserInputRequired>().size)
        val complete = events.filterIsInstance<AgentEvent.ResponseComplete>().single()
        assertEquals(decisionText, complete.fullText)
        // 无追加 User 消息（用户选择未发生）
        assertTrue(events.none { it is AgentEvent.Error })
        assertFalse(events.any { it is AgentEvent.IterationStart && it.iteration == 2 })
    }

    @Test
    fun `build mode never intercepts decision-like text`() = runBlocking<Unit> {
        // 同样的方案对比文本在 BUILD 模式下不拦截（差异化只属于 HUMAN_ASSIST）
        val fake = FakeLlmClient(
            listOf(FakeLlmClient.ScriptedResponse.Ok(content = decisionText))
        )
        val engine = ApexAgentEngine(
            llmClient = fake,
            toolRegistry = FakeToolRegistry(emptyList()),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.BUILD, thinkingLevel = ThinkingLevel.NONE)
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch(Dispatchers.Unconfined) {
            engine.execute(UserInput.text("清理配置目录")).collect { events.add(it) }
        }
        awaitCondition { events.any { it is AgentEvent.Complete } }
        job.cancel()

        assertTrue(events.none { it is AgentEvent.UserInputRequired })
        assertEquals(decisionText, events.filterIsInstance<AgentEvent.ResponseComplete>().single().fullText)
    }
}
