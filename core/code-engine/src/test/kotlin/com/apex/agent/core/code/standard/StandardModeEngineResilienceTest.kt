package com.apex.agent.core.code.standard

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.llm.runtime.ModelRuntimeException
import com.apex.agent.core.llm.runtime.SingleClientModelRuntime
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolStreamEvent
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardModeEngine LLM 请求容错测试（P0：用户报障「coding 模式一出错就中断」）。
 *
 * 覆盖 runTurns 流式请求块的四条容错路径：
 * 1. 瞬时失败（ModelUnavailable / IOException …）→ ToolRetrySchedules 阶梯
 *    重试（2s/5s/10s/…；ladderExhausted 即停诚实上抛）；
 * 2. 请求级拒绝（ModelRequestRejected 400/404 类）→ **一次**去工具纯文本
 *    降级续跑（任务收尾而不是死掉）；降级再败才上抛；
 * 3. 阶梯穷尽 → 诚实 Error（任务收口不悬挂）；
 * 4. 已流出部分输出的失败 → 维持「诚实部分结果」（不重试，防重复 chunk）。
 *
 * 另含端到端清洗路由断言：点号/中文工具名以 provider 名进请求体，模型
 * 回显 provider 名经反查表路由回注册表 id 执行（胶囊事件分族一致）。
 *
 * 全部经注入 `retrySleeper` 记录器驱动（无真实 sleep，runTest 虚拟时钟）。
 */
class StandardModeEngineResilienceTest {

    // ═══ 测试基座 ═══

    /** 剧本帧：吐 chunk / 抛错 / 先吐 chunk 再抛错。 */
    private sealed interface LlmScript {
        data class Emit(val chunks: List<LlmStreamChunk>) : LlmScript
        data class Fail(val error: Exception) : LlmScript
        data class EmitThenFail(val chunks: List<LlmStreamChunk>, val error: Exception) : LlmScript
    }

    /** 剧本化 LlmClient：记录每次请求的 (messages, tools)，按剧本吐流或抛错。 */
    private class ScriptedLlm : LlmClient {
        val scripts = ArrayDeque<LlmScript>()
        val chatRequests = mutableListOf<Pair<List<LlmMessage>, List<ToolDefinition>>>()

        fun enqueue(vararg chunks: LlmStreamChunk) {
            scripts.addLast(LlmScript.Emit(chunks.toList()))
        }

        fun enqueueFailure(error: Exception) {
            scripts.addLast(LlmScript.Fail(error))
        }

        fun enqueuePartialThenFailure(vararg chunks: LlmStreamChunk) {
            // 先吐部分输出再抛错（诚实部分结果路径的触发形态）
            scripts.addLast(
                LlmScript.EmitThenFail(
                    chunks.toList(),
                    ModelRuntimeException.ModelUnavailable("mid-stream drop", profileId = "test-profile")
                )
            )
        }

        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): LlmResponse = LlmResponse(content = "")

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<LlmStreamChunk> {
            chatRequests += messages to tools
            val script = scripts.removeFirstOrNull()
                ?: return flow { emit(LlmStreamChunk(content = "(script 缺失)", isFinish = true)) }
            return when (script) {
                is LlmScript.Emit -> flow { script.chunks.forEach { emit(it) } }
                is LlmScript.Fail -> flow { throw script.error }
                is LlmScript.EmitThenFail -> flow {
                    script.chunks.forEach { emit(it) }
                    throw script.error
                }
            }
        }
    }

    private class FakeExecutor : ToolExecutor {
        val executed = mutableListOf<Pair<String, String>>()

        override suspend fun execute(toolId: String, arguments: String): String =
            "exec:$toolId"

        override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> {
            executed += toolId to arguments
            return flow {
                emit(ToolStreamEvent.Output("out:$toolId"))
                emit(ToolStreamEvent.Complete("out:$toolId"))
            }
        }
    }

    private class FakeTool(override val id: String) : AgentTool {
        override val name: String get() = id
        override val description: String get() = "fake $id"
        override val parametersSchema: String =
            """{"type":"object","properties":{},"required":[]}"""
        override suspend fun execute(arguments: String): String = "ok"
    }

    private fun engine(
        llm: ScriptedLlm,
        executor: FakeExecutor,
        delays: MutableList<Long>,
        tools: List<String> = listOf("code_read", "code_edit", "code_write")
    ): StandardModeEngine {
        val registry = DefaultToolRegistry().apply { tools.forEach { register(FakeTool(it)) } }
        return StandardModeEngine(
            runtime = SingleClientModelRuntime(llm),
            toolRegistry = registry,
            toolExecutor = executor,
            // 注入记录器：无真实 sleep，断言阶梯序列
            retrySleeper = { ms -> delays.add(ms) }
        )
    }

    // ═══ 1. 瞬时失败：阶梯重试后成功 ═══

    @Test
    fun `transient failures are ladder-retried then task completes`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueueFailure(unavailable("5xx blip"))
        llm.enqueueFailure(unavailable("conn refused"))
        llm.enqueue(LlmStreamChunk(content = "恢复完成", isFinish = true))

        val delays = mutableListOf<Long>()
        val engine = engine(llm, FakeExecutor(), delays)
        val events = engine.execute("q").toList()

        // 两次失败 → 2s / 5s 两次阶梯等待 → 第 3 次请求成功
        assertEquals(listOf(2_000L, 5_000L), delays)
        assertEquals(3, llm.chatRequests.size)
        // 重试重发完整工具面（同请求体）
        assertTrue(llm.chatRequests.all { it.second.isNotEmpty() })

        // UI 可见性：每档重试一条调度通知（attempt 递增 / 延迟对应阶梯级）
        val retries = events.filterIsInstance<AgentEvent.LlmRetryScheduled>()
        assertEquals(2, retries.size)
        assertEquals(1, retries[0].attempt)
        assertEquals(2_000L, retries[0].delayMs)
        assertEquals(2, retries[1].attempt)
        assertEquals(5_000L, retries[1].delayMs)

        // 任务正常收官：重试不消耗回合预算（1 回合）
        val response = events.filterIsInstance<AgentEvent.ResponseComplete>().single()
        assertEquals("恢复完成", response.fullText)
        assertEquals(1, events.filterIsInstance<AgentEvent.Complete>().single().totalIterations)
        assertTrue(events.none { it is AgentEvent.Error })
    }

    @Test
    fun `io exception is treated as transient and retried`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueueFailure(IOException("connection reset"))
        llm.enqueue(LlmStreamChunk(content = "ok", isFinish = true))

        val delays = mutableListOf<Long>()
        val engine = engine(llm, FakeExecutor(), delays)
        val events = engine.execute("q").toList()

        // 纯 IOException（SingleClient 回退路径未经分类器）同样进阶梯
        assertEquals(listOf(2_000L), delays)
        assertEquals(2, llm.chatRequests.size)
        assertTrue(events.filterIsInstance<AgentEvent.ResponseComplete>().isNotEmpty())
    }

    // ═══ 2. 请求级拒绝：一次去工具降级续跑 ═══

    @Test
    fun `request rejection degrades once to text-only and completes`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueueFailure(
            ModelRuntimeException.ModelRequestRejected(
                "请求被拒绝 (400): Invalid 'tools[14].function.name'", profileId = "test-profile"
            )
        )
        llm.enqueue(LlmStreamChunk(content = "已用纯文本方式继续完成", isFinish = true))

        val delays = mutableListOf<Long>()
        val engine = engine(llm, FakeExecutor(), delays)
        val events = engine.execute("q").toList()

        // 两次请求：第一次带工具被拒 → 降级重发 tools=空 → 成功收尾
        assertEquals(2, llm.chatRequests.size)
        assertTrue("首次请求携带工具面", llm.chatRequests[0].second.isNotEmpty())
        assertTrue("降级重发为纯文本（无工具）", llm.chatRequests[1].second.isEmpty())

        // 降级不是重试：零延迟，不发 LlmRetryScheduled 的等待语义（delayMs=0）
        assertTrue(delays.isEmpty())
        val notices = events.filterIsInstance<AgentEvent.LlmRetryScheduled>()
        assertEquals(1, notices.size)
        assertEquals(0L, notices.single().delayMs)
        assertTrue(notices.single().reason.contains("降级"))

        // 消息列表不动（降级不污染会话：两次请求 messages 一致）
        assertEquals(llm.chatRequests[0].first, llm.chatRequests[1].first)

        // 任务继续收尾而不是死掉
        val response = events.filterIsInstance<AgentEvent.ResponseComplete>().single()
        assertEquals("已用纯文本方式继续完成", response.fullText)
        assertTrue(events.none { it is AgentEvent.Error })
    }

    @Test
    fun `degraded retry failing again surfaces honest error`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueueFailure(
            ModelRuntimeException.ModelRequestRejected("请求被拒绝 (400)", profileId = "test-profile")
        )
        llm.enqueueFailure(
            ModelRuntimeException.ModelRequestRejected("请求被拒绝 (400)", profileId = "test-profile")
        )

        val delays = mutableListOf<Long>()
        val engine = engine(llm, FakeExecutor(), delays)
        val events = engine.execute("q").toList()

        // 降级（tools=空）也失败 → 诚实上抛（Error 事件收口，不再重试）
        assertEquals(2, llm.chatRequests.size)
        assertTrue(llm.chatRequests[1].second.isEmpty())
        assertTrue(delays.isEmpty())
        val error = events.filterIsInstance<AgentEvent.Error>().single()
        assertTrue(error.message.contains("400"))
        assertTrue(events.none { it is AgentEvent.ResponseComplete })
    }

    // ═══ 3. 阶梯穷尽：诚实错误（任务收口不悬挂） ═══

    @Test
    fun `persistent unavailability exhausts ladder and surfaces honest error`() = runTest {
        val llm = ScriptedLlm()
        // 7 次请求（初始 1 + 阶梯 6）：末级标记 ladderExhausted(6) 为真即停
        repeat(7) { llm.enqueueFailure(unavailable("down #$it")) }

        val delays = mutableListOf<Long>()
        val engine = engine(llm, FakeExecutor(), delays)
        val events = engine.execute("q").toList()

        // 2s/5s/10s/20s/40s/80s 六档耗尽 → 第 7 次失败后不再等待、诚实上抛
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L), delays)
        assertEquals(7, llm.chatRequests.size)
        assertEquals(6, events.filterIsInstance<AgentEvent.LlmRetryScheduled>().size)
        val error = events.filterIsInstance<AgentEvent.Error>().single()
        assertTrue(error.message.contains("down #6"))
        // 收口完整（Error 后 Complete 仍发，任务不悬挂）
        assertTrue(events.filterIsInstance<AgentEvent.Complete>().isNotEmpty())
    }

    // ═══ 4. 部分输出：诚实部分结果（不重试） ═══

    @Test
    fun `partial output failure keeps honest partial result without retry`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueuePartialThenFailure(LlmStreamChunk(content = "已经输出了一半"))

        val delays = mutableListOf<Long>()
        val engine = engine(llm, FakeExecutor(), delays)
        val events = engine.execute("q").toList()

        // 已发射的 chunk 不能因重试而重复：零重试、单次请求
        assertTrue(delays.isEmpty())
        assertEquals(1, llm.chatRequests.size)
        assertTrue(events.none { it is AgentEvent.LlmRetryScheduled })
        val response = events.filterIsInstance<AgentEvent.ResponseComplete>().single()
        assertTrue(response.fullText.contains("已经输出了一半"))
        assertTrue(response.fullText.contains("请求中断"))
    }

    // ═══ 5. 端到端清洗路由（Part 1 集成） ═══

    @Test
    fun `dotted and unicode names are sanitized in request and routed back`() = runTest {
        val llm = ScriptedLlm()
        // 模型按 tools 数组里的 provider 名调用（"mcp" = 中文 MCP 工具的清洗名）
        llm.enqueue(
            LlmStreamChunk(toolCalls = listOf(ToolCall(id = "c1", name = "mcp", arguments = "{}")))
        )
        llm.enqueue(LlmStreamChunk(content = "查到了天气", isFinish = true))

        val delays = mutableListOf<Long>()
        val executor = FakeExecutor()
        val engine = engine(
            llm, executor, delays,
            tools = listOf("terminal.exec", "mcp__天气服务__查天气", "code_read")
        )
        val events = engine.execute("q").toList()

        // 请求体：点号/中文名 → provider 合法名（严格端点不再 400）
        val requestToolNames = llm.chatRequests.first().second.map { it.name }
        val providerLegal = Regex("^[a-zA-Z0-9_-]{1,64}$")
        requestToolNames.forEach {
            assertTrue("request tool name not provider-safe: '$it'", providerLegal.matches(it))
        }
        assertTrue(requestToolNames.contains("terminal_exec"))
        assertTrue(requestToolNames.contains("mcp"))
        assertFalseRawNames(requestToolNames)

        // 执行路由：回显名 "mcp" → 注册表 id（事件与执行同口径，胶囊分族一致）
        val start = events.filterIsInstance<AgentEvent.ToolCallStart>().single()
        assertEquals("mcp__天气服务__查天气", start.toolName)
        assertEquals(1, executor.executed.count { it.first == "mcp__天气服务__查天气" })
        val complete = events.filterIsInstance<AgentEvent.ToolCallComplete>().single()
        assertTrue(complete.success)
        assertTrue(events.none { it is AgentEvent.Error })
    }

    /** 请求体里不得出现原始脏名（点号 / 非 ASCII）。 */
    private fun assertFalseRawNames(names: List<String>) {
        assertTrue(names.none { it.contains('.') })
        assertTrue(names.none { name -> name.any { it.code > 127 } })
    }

    private fun unavailable(message: String): ModelRuntimeException.ModelUnavailable =
        ModelRuntimeException.ModelUnavailable(message, profileId = "test-profile")
}
