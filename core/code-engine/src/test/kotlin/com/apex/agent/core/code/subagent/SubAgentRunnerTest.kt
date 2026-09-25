package com.apex.agent.core.code.subagent

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.UserInput
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #147 子代理 task 工具 —— 纯 JVM 单测。
 *
 * 用脚本化假引擎（直接发射固定 AgentEvent 序列）替换真实 ApexAgentEngine，
 * 聚焦 [SubAgentRunner] 的事件收集 / 失败语义 / 超时 / 并发闸门，以及
 * [CodeTaskTool] 的参数解析与输出格式化。不依赖任何 Android 类。
 */
class SubAgentRunnerTest {

    // ── 测试脚手架 ─────────────────────────────────────────────────

    /**
     * 脚本化假引擎：按序发射事件，可选在末尾挂起模拟长任务（配合 runTest
     * 虚拟时间验证超时路径）。记录收到的输入文本供委派链路断言。
     */
    private class FakeScriptEngine(
        private val events: List<AgentEvent>,
        private val tailDelayMs: Long = 0L,
        private val onExecute: (String) -> Unit = {}
    ) : AgentEngine {
        override fun execute(input: String): Flow<AgentEvent> = flow {
            onExecute(input)
            events.forEach { emit(it) }
            if (tailDelayMs > 0) delay(tailDelayMs)
        }

        override fun execute(input: UserInput): Flow<AgentEvent> = execute(input.text)
        override suspend fun abort() {}
        override fun submitUserInput(answer: String) {}
        override fun cancelUserInput() {}
    }

    /** 记录型工厂：捕获交给引擎的配置与引擎收到的输入。 */
    private class RecordingFactory(
        private val events: List<AgentEvent>,
        private val tailDelayMs: Long = 0L
    ) {
        val configs = mutableListOf<AgentConfig>()
        val inputs = mutableListOf<String>()
        val factory: (AgentConfig) -> AgentEngine = { cfg ->
            configs.add(cfg)
            FakeScriptEngine(events, tailDelayMs) { inputs.add(it) }
        }
    }

    /** 一次成功子代理运行的事件序列（含被覆盖的中间 chunk 与权威结论）。 */
    private fun successEvents(finalText: String): List<AgentEvent> = listOf(
        AgentEvent.IterationStart(1),
        AgentEvent.ThinkingChunk("先想想"),
        AgentEvent.ToolCallStart("call-1", "code_grep", "{}"),
        AgentEvent.ToolOutputChunk("call-1", "3 matches"),
        AgentEvent.ToolCallComplete(
            callId = "call-1",
            toolName = "code_grep",
            arguments = "{}",
            output = "3 matches",
            fullOutput = "3 matches",
            success = true,
            durationMs = 42
        ),
        AgentEvent.IterationStart(2),
        AgentEvent.ResponseChunk("中间过程废话"), // 应被 ResponseComplete 覆盖
        AgentEvent.ResponseComplete(finalText),
        AgentEvent.Complete(summary = "", totalIterations = 2, totalToolCalls = 1, totalDurationMs = 500)
    )

    private fun failureMessage(result: Result<SubAgentRunner.SubAgentResult>): String =
        result.exceptionOrNull()?.message ?: ""

    // ── SubAgentRunner：正常路径 ───────────────────────────────────

    @Test
    fun `normal run collects final output and stats`() = runTest {
        val rec = RecordingFactory(successEvents("找到了 3 处引用"))
        val runner = SubAgentRunner(rec.factory)

        val result = runner.run(
            SubAgentRunner.SubAgentType.EXPLORE,
            "找出所有调用 loginUser 的地方",
            "在工作区内检索 loginUser 的全部调用点并给出 path:line"
        )

        assertTrue(result.isSuccess)
        val r = result.getOrThrow()
        assertEquals("找到了 3 处引用", r.output)
        assertEquals(2, r.iterations)
        assertEquals(1, r.toolCalls)
        assertFalse(r.truncated)
        assertTrue(r.durationMs >= 0)
    }

    @Test
    fun `chunks are fallback when response complete is missing`() = runTest {
        val rec = RecordingFactory(
            listOf(
                AgentEvent.ResponseChunk("结论A"),
                AgentEvent.ResponseChunk("结论B"),
                AgentEvent.Complete(summary = "", totalIterations = 1, totalToolCalls = 0, totalDurationMs = 10)
            )
        )
        val runner = SubAgentRunner(rec.factory)

        val result = runner.run(SubAgentRunner.SubAgentType.GENERAL, "任务", "指令")

        assertTrue(result.isSuccess)
        assertEquals("结论A结论B", result.getOrThrow().output)
    }

    @Test
    fun `subagent config follows issue spec`() = runTest {
        val rec = RecordingFactory(successEvents("ok"))
        val runner = SubAgentRunner(rec.factory)

        runner.run(SubAgentRunner.SubAgentType.EXPLORE, "探索任务", "找出入口").getOrThrow()

        assertEquals(1, rec.configs.size)
        val cfg = rec.configs[0]
        assertEquals(AgentMode.BUILD, cfg.mode)
        assertEquals(ThinkingLevel.LIGHT, cfg.thinkingLevel)
        assertEquals(15, cfg.maxIterations)
        assertEquals(4000, cfg.maxToolOutputLength)
        assertEquals(64_000, cfg.maxContextTokens)
        assertEquals(0.3f, cfg.temperature, 0.0001f)
        assertEquals(setOf("code_read", "code_grep", "code_glob"), cfg.allowedToolIds)
        assertTrue(cfg.additionalSystemContext.contains("explore"))
        assertTrue(cfg.additionalSystemContext.contains("探索任务"))
        // 完整指令作为子代理引擎的第一条用户消息
        assertEquals(1, rec.inputs.size)
        assertTrue(rec.inputs[0].contains("找出入口"))
    }

    @Test
    fun `explore research and general carry their own tool sets`() = runTest {
        val rec = RecordingFactory(successEvents("ok"))
        val runner = SubAgentRunner(rec.factory)

        runner.run(SubAgentRunner.SubAgentType.EXPLORE, "t", "p").getOrThrow()
        assertEquals(setOf("code_read", "code_grep", "code_glob"), rec.configs.last().allowedToolIds)

        runner.run(SubAgentRunner.SubAgentType.RESEARCH, "t", "p").getOrThrow()
        assertEquals(setOf("web_search", "web_fetch", "http_request"), rec.configs.last().allowedToolIds)

        runner.run(SubAgentRunner.SubAgentType.GENERAL, "t", "p").getOrThrow()
        assertTrue(rec.configs.last().allowedToolIds.isEmpty())
    }

    @Test
    fun `overlong output is capped with a notice line`() = runTest {
        val longText = "x".repeat(9000)
        val rec = RecordingFactory(
            listOf(
                AgentEvent.ResponseComplete(longText),
                AgentEvent.Complete(summary = "", totalIterations = 1, totalToolCalls = 0, totalDurationMs = 1)
            )
        )
        val runner = SubAgentRunner(rec.factory)

        val r = runner.run(SubAgentRunner.SubAgentType.EXPLORE, "t", "p").getOrThrow()

        assertTrue(r.truncated)
        assertTrue(r.output.length <= 8200)
        assertTrue(r.output.contains("已截断"))
        assertTrue(r.output.startsWith("x"))
    }

    // ── SubAgentRunner：失败语义 ───────────────────────────────────

    @Test
    fun `error event fails the run`() = runTest {
        val rec = RecordingFactory(
            listOf(
                AgentEvent.ResponseChunk("部分输出"),
                AgentEvent.Error("模型超载"),
                AgentEvent.Complete(summary = "", totalIterations = 1, totalToolCalls = 0, totalDurationMs = 10)
            )
        )
        val runner = SubAgentRunner(rec.factory)

        val result = runner.run(SubAgentRunner.SubAgentType.EXPLORE, "t", "p")

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("模型超载"))
    }

    @Test
    fun `aborted event fails the run`() = runTest {
        val rec = RecordingFactory(
            listOf(
                AgentEvent.Aborted,
                AgentEvent.Complete(summary = "", totalIterations = 1, totalToolCalls = 0, totalDurationMs = 10)
            )
        )
        val runner = SubAgentRunner(rec.factory)

        val result = runner.run(SubAgentRunner.SubAgentType.GENERAL, "t", "p")

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("取消"))
    }

    @Test
    fun `empty output fails the run`() = runTest {
        val rec = RecordingFactory(
            listOf(AgentEvent.Complete(summary = "", totalIterations = 1, totalToolCalls = 0, totalDurationMs = 10))
        )
        val runner = SubAgentRunner(rec.factory)

        val result = runner.run(SubAgentRunner.SubAgentType.EXPLORE, "t", "p")

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("未返回任何内容"))
    }

    @Test
    fun `blank description or prompt fails fast without engine creation`() = runTest {
        val rec = RecordingFactory(successEvents("ok"))
        val runner = SubAgentRunner(rec.factory)

        assertTrue(runner.run(SubAgentRunner.SubAgentType.EXPLORE, "   ", "p").isFailure)
        assertTrue(runner.run(SubAgentRunner.SubAgentType.EXPLORE, "d", "").isFailure)
        // 快速失败：工厂不应被触达
        assertTrue(rec.configs.isEmpty())
    }

    @Test
    fun `engine factory throw is folded into failure`() = runTest {
        val runner = SubAgentRunner(engineFactory = { error("DI 缺失：llmClient 未初始化") })

        val result = runner.run(SubAgentRunner.SubAgentType.EXPLORE, "t", "p")

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("DI 缺失"))
    }

    // ── SubAgentRunner：超时 ───────────────────────────────────────

    @Test
    fun `timeout without output fails`() = runTest {
        val rec = RecordingFactory(emptyList(), tailDelayMs = 60_000)
        val runner = SubAgentRunner(rec.factory, timeoutMs = 100)

        val result = runner.run(SubAgentRunner.SubAgentType.EXPLORE, "t", "p")

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("超时"))
    }

    @Test
    fun `timeout with partial output returns truncated result`() = runTest {
        val rec = RecordingFactory(
            listOf(AgentEvent.ResponseChunk("部分结论")),
            tailDelayMs = 60_000
        )
        val runner = SubAgentRunner(rec.factory, timeoutMs = 100)

        val result = runner.run(SubAgentRunner.SubAgentType.RESEARCH, "t", "p")

        assertTrue(result.isSuccess)
        val r = result.getOrThrow()
        assertEquals("部分结论", r.output)
        assertTrue(r.truncated)
    }

    // ── SubAgentRunner：并发闸门 ───────────────────────────────────

    @Test
    fun `semaphore caps concurrent subagents at maxConcurrent`() = runTest {
        val active = AtomicInteger()
        val maxSeen = AtomicInteger()
        val factory: (AgentConfig) -> AgentEngine = {
            object : AgentEngine {
                override fun execute(input: String): Flow<AgentEvent> = flow {
                    val now = active.incrementAndGet()
                    maxSeen.updateAndGet { m -> maxOf(m, now) }
                    try {
                        emit(AgentEvent.ResponseChunk("结论"))
                        delay(1_000)
                        emit(AgentEvent.ResponseComplete("结论"))
                        emit(AgentEvent.Complete(summary = "", totalIterations = 1, totalToolCalls = 0, totalDurationMs = 1_000))
                    } finally {
                        active.decrementAndGet()
                    }
                }

                override fun execute(input: UserInput): Flow<AgentEvent> = execute(input.text)
                override suspend fun abort() {}
                override fun submitUserInput(answer: String) {}
                override fun cancelUserInput() {}
            }
        }
        val runner = SubAgentRunner(factory, maxConcurrent = 2, timeoutMs = 60_000)

        val results = (1..4).map { index ->
            async { runner.run(SubAgentRunner.SubAgentType.EXPLORE, "任务$index", "指令$index") }
        }.awaitAll()

        assertEquals(2, maxSeen.get())
        assertTrue(results.all { it.isSuccess })
    }

    // ── CodeTaskTool ───────────────────────────────────────────────

    @Test
    fun `tool delegates to runner with default explore type`() = runTest {
        val rec = RecordingFactory(successEvents("找到了 3 处引用"))
        val tool = CodeTaskTool(SubAgentRunner(rec.factory))

        val output = tool.execute(
            """{"description":"找出所有调用 loginUser 的地方","prompt":"在工作区内检索 loginUser 的全部调用点并给出 path:line"}"""
        )

        assertTrue(output.contains("子代理 explore 完成"))
        assertTrue(output.contains("2 轮迭代"))
        assertTrue(output.contains("1 次工具调用"))
        assertTrue(output.contains("找到了 3 处引用"))
        // 委派链路：工厂收到 explore 工具集配置；引擎收到自包含指令
        assertEquals(setOf("code_read", "code_grep", "code_glob"), rec.configs.single().allowedToolIds)
        assertTrue(rec.inputs.single().contains("loginUser"))
    }

    @Test
    fun `tool accepts explicit research type`() = runTest {
        val rec = RecordingFactory(successEvents("结论"))
        val tool = CodeTaskTool(SubAgentRunner(rec.factory))

        val output = tool.execute(
            """{"description":"调研挂起函数支持","prompt":"查一下主流网络库对挂起函数的支持现状","subagent_type":"research"}"""
        )

        assertTrue(output.contains("子代理 research 完成"))
        assertEquals(setOf("web_search", "web_fetch", "http_request"), rec.configs.single().allowedToolIds)
    }

    @Test
    fun `tool general type uses default core plan`() = runTest {
        val rec = RecordingFactory(successEvents("结论"))
        val tool = CodeTaskTool(SubAgentRunner(rec.factory))

        val output = tool.execute("""{"description":"d","prompt":"p","subagent_type":"general"}""")

        assertTrue(output.contains("子代理 general 完成"))
        assertTrue(rec.configs.single().allowedToolIds.isEmpty())
    }

    @Test
    fun `missing required argument renders error prefix`() = runTest {
        val rec = RecordingFactory(successEvents("结论"))
        val tool = CodeTaskTool(SubAgentRunner(rec.factory))

        val output = tool.execute("""{"prompt":"只有指令没有描述"}""")

        assertTrue(output.startsWith("Error:"))
        // 参数校验失败不应触达子代理
        assertTrue(rec.configs.isEmpty())
    }

    @Test
    fun `invalid json renders error prefix`() = runTest {
        val tool = CodeTaskTool(SubAgentRunner(RecordingFactory(successEvents("x")).factory))

        assertTrue(tool.execute("not-json").startsWith("Error:"))
    }

    @Test
    fun `unknown subagent type renders error prefix`() = runTest {
        val rec = RecordingFactory(successEvents("结论"))
        val tool = CodeTaskTool(SubAgentRunner(rec.factory))

        val output = tool.execute("""{"description":"d","prompt":"p","subagent_type":"hacker"}""")

        assertTrue(output.startsWith("Error:"))
        assertTrue(output.contains("subagent_type"))
        assertTrue(rec.configs.isEmpty())
    }

    @Test
    fun `runner failure is surfaced as error with reason`() = runTest {
        val rec = RecordingFactory(
            listOf(
                AgentEvent.Error("网关 502"),
                AgentEvent.Complete(summary = "", totalIterations = 1, totalToolCalls = 0, totalDurationMs = 1)
            )
        )
        val tool = CodeTaskTool(SubAgentRunner(rec.factory))

        val output = tool.execute("""{"description":"d","prompt":"p"}""")

        assertTrue(output.startsWith("Error:"))
        assertTrue(output.contains("网关 502"))
    }
}
