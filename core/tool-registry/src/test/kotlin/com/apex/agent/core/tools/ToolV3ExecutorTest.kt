package com.apex.agent.core.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import com.apex.agent.core.tools.builtin.BaseTool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool System v3 — [EnhancedToolExecutor] 执行管线测试。
 *
 * 覆盖 v3 新增的横切能力在真实执行路径上的行为（不 mock 管线组件）：
 * - 策略超时（withTimeout → "Error: timeout" 且计入追踪/统计）；
 * - 瞬态失败自动重试（retrySafe 工具 + Retryable 分类 + 退避重放）；
 * - 非重试安全工具绝不盲重试；权限/参数错绝不重试；
 * - 熔断器短路（OPEN 期 fail-fast，探测恢复闭环）；
 * - 限流拒绝（fail-fast + 模型可读指引）；
 * - 追踪 span：attempt 编号、成败、denied 原因；
 * - 流式路径：普通工具包装为 Output+Complete、超时转 Error 事件。
 *
 * 与 DefaultToolExecutorStreamingTest（v2 回归）互补 —— 那边锁定 v2
 * 行为不变量，这边锁定 v3 增强。
 */
class ToolV3ExecutorTest {

    // ═══ fixtures ═══════════════════════════════════════════════════

    /**
     * 脚本化工具：预设结果序列（耗尽后返回最后预设/默认值），可注入
     * 挂起延迟模拟慢工具，统计调用次数与收到的参数。
     */
    private class ScriptedTool(
        override val id: String,
        private val results: List<String>,
        private val annotations: ToolAnnotations = ToolAnnotations.readOnly(),
        private val delayMs: Long = 0
    ) : AgentTool {
        override val name = id
        override val description = "scripted"
        override val parametersSchema = "{}"
        override val metadata = ToolMetadata(
            id = id, category = ToolCategory.UTILITY, risk = ToolRisk.LOW,
            tags = emptyList(), annotations = annotations
        )

        val calls = java.util.concurrent.atomic.AtomicInteger()
        val argumentsSeen = mutableListOf<String>()

        override suspend fun execute(arguments: String): String {
            calls.incrementAndGet()
            argumentsSeen += arguments
            if (delayMs > 0) delay(delayMs)
            val index = calls.get() - 1
            return if (index < results.size) results[index] else results.lastOrNull() ?: "OK"
        }
    }

    private fun executor(
        vararg tools: AgentTool,
        policyResolver: ToolRunPolicyResolver? = null,
        breaker: ToolCircuitBreaker? = null,
        rateLimiter: ToolRateLimiter? = null,
        tracer: ToolTraceRecorder? = null,
        tracker: ToolUsageTracker? = null,
        gate: ToolExecutionGate? = null
    ): EnhancedToolExecutor {
        val registry = DefaultToolRegistry()
        tools.forEach { registry.register(it) }
        return EnhancedToolExecutor(
            registry = registry,
            gate = gate,
            usageTracker = tracker,
            policyResolver = policyResolver,
            rateLimiter = rateLimiter,
            breaker = breaker,
            traceRecorder = tracer
        )
    }

    /** 固定策略解析器（测试注入，不依赖注解推断）。 */
    private class StaticPolicyResolver(private val policy: ToolRunPolicy) : ToolRunPolicyResolver {
        override fun resolve(tool: AgentTool): ToolRunPolicy = policy
    }

    // ═══ 超时 ═══════════════════════════════════════════════════════

    @Test
    fun `policy timeout bounds a hung tool and reports a structured timeout error`() = runTest {
        val tool = ScriptedTool("slow_read", listOf("late"), delayMs = 60_000)
        val executor = executor(
            tool,
            policyResolver = StaticPolicyResolver(ToolRunPolicy(timeoutMs = 100))
        )
        val result = executor.execute("slow_read", "{}")
        assertTrue(result, result.startsWith("Error: timeout"))
        assertTrue(result.contains("100ms"))
        // 挂死的调用被取消，没有晚到的成功结果混入。
        assertEquals(1, tool.calls.get())
    }

    @Test
    fun `zero timeout disables the deadline - legacy semantics preserved`() = runTest {
        // 虚拟时间下 delay(60000) 瞬时完成 → 工具成功。
        val tool = ScriptedTool("legacy_read", listOf("value"), delayMs = 60_000)
        val executor = executor(
            tool,
            policyResolver = StaticPolicyResolver(ToolRunPolicy(timeoutMs = 0))
        )
        assertEquals("value", executor.execute("legacy_read", "{}"))
    }

    // ═══ 重试 ═══════════════════════════════════════════════════════

    @Test
    fun `transient failure on a retry-safe tool is retried until success`() = runTest {
        val tool = ScriptedTool(
            "flaky_read",
            listOf("Error: execution failed: transient flake", "Error: timeout: x", "recovered value")
        )
        val executor = executor(
            tool,
            policyResolver = StaticPolicyResolver(
                ToolRunPolicy(timeoutMs = 0, maxRetries = 3, baseRetryDelayMs = 1)
            )
        )
        val result = executor.execute("flaky_read", "{}")
        assertEquals("recovered value", result)
        assertEquals(3, tool.calls.get())
    }

    @Test
    fun `non-retry-safe tools never auto-retry even on transient failures`() = runTest {
        val tool = ScriptedTool(
            "dangerous_write",
            listOf("Error: execution failed: transient flake"),
            annotations = ToolAnnotations.destructive()
        )
        val executor = executor(
            tool,
            policyResolver = StaticPolicyResolver(
                ToolRunPolicy(timeoutMs = 0, maxRetries = 3, baseRetryDelayMs = 1)
            )
        )
        val result = executor.execute("dangerous_write", "{}")
        assertTrue(result.startsWith("Error"))
        assertEquals("destructive payloads are never blind-retried", 1, tool.calls.get())
    }

    @Test
    fun `permission denials are terminal - retry budget is not burned`() = runTest {
        val tool = ScriptedTool(
            "gated_read",
            listOf("Error: permission denied: user said no")
        )
        val executor = executor(
            tool,
            policyResolver = StaticPolicyResolver(
                ToolRunPolicy(timeoutMs = 0, maxRetries = 3, baseRetryDelayMs = 1)
            )
        )
        val result = executor.execute("gated_read", "{}")
        assertTrue(result.startsWith("Error: permission denied"))
        assertEquals("terminal failures must not retry", 1, tool.calls.get())
    }

    @Test
    fun `exhausted retries return the last error verbatim`() = runTest {
        val tool = ScriptedTool(
            "always_failing",
            listOf("Error: execution failed: down", "Error: execution failed: down again")
        )
        val executor = executor(
            tool,
            policyResolver = StaticPolicyResolver(
                ToolRunPolicy(timeoutMs = 0, maxRetries = 1, baseRetryDelayMs = 1)
            )
        )
        val result = executor.execute("always_failing", "{}")
        assertEquals("Error: execution failed: down again", result)
        assertEquals(2, tool.calls.get())
    }

    // ═══ 熔断 ═══════════════════════════════════════════════════════

    @Test
    fun `breaker opens after repeated failures and short-circuits with guidance`() = runTest {
        val tool = ScriptedTool(
            "broken_tool",
            listOf("Error: execution failed: dead backend")
        )
        val breaker = ToolCircuitBreaker(failureThreshold = 2, openCooldownMs = 60_000)
        val executor = executor(
            tool,
            policyResolver = StaticPolicyResolver(ToolRunPolicy(timeoutMs = 0)),
            breaker = breaker
        )

        // 两次失败 → 熔断打开。
        assertTrue(executor.execute("broken_tool", "{}").startsWith("Error"))
        assertTrue(executor.execute("broken_tool", "{}").startsWith("Error"))
        assertEquals(ToolCircuitBreaker.State.OPEN, breaker.stateFor("broken_tool"))

        // 第三次调用 fail-fast：不再触达工具，错误里带指引。
        val shortCircuit = executor.execute("broken_tool", "{}")
        assertTrue(shortCircuit.contains("circuit breaker open"))
        assertTrue(shortCircuit.contains("dead backend"))
        assertTrue(shortCircuit.contains("Do not retry immediately"))
        assertEquals("short-circuit must not reach the tool", 2, tool.calls.get())
    }

    @Test
    fun `a successful call closes an open breaker through the probe`() = runTest {
        // 低冷却窗口：失败一次开闸，冷却过后探测成功 → 闸闭合。
        val fastBreaker = ToolCircuitBreaker(failureThreshold = 1, openCooldownMs = 1)
        val executor = executor(
            ScriptedTool("recovering", listOf("Error: execution failed: flake", "OK")),
            policyResolver = StaticPolicyResolver(ToolRunPolicy(timeoutMs = 0)),
            breaker = fastBreaker
        )
        assertTrue(executor.execute("recovering", "{}").startsWith("Error"))
        assertEquals(ToolCircuitBreaker.State.OPEN, fastBreaker.stateFor("recovering"))

        Thread.sleep(5) // 冷却 1ms 已过 → HALF_OPEN 探测。
        assertEquals("OK", executor.execute("recovering", "{}"))
        assertEquals(ToolCircuitBreaker.State.CLOSED, fastBreaker.stateFor("recovering"))
    }

    // ═══ 限流 ═══════════════════════════════════════════════════════

    @Test
    fun `rate limit rejects calls above the per-minute allowance with a fix hint`() = runTest {
        val tool = ScriptedTool("spammable_read", listOf("OK"))
        val executor = executor(
            tool,
            policyResolver = StaticPolicyResolver(
                ToolRunPolicy(timeoutMs = 0, rateLimitPerMinute = 2)
            )
        )
        assertEquals("OK", executor.execute("spammable_read", "{}"))
        assertEquals("OK", executor.execute("spammable_read", "{}"))
        val third = executor.execute("spammable_read", "{}")
        assertTrue(third.startsWith("Error"))
        assertTrue(third.contains("rate limit"))
        assertTrue(third.contains("spammable_read"))
        assertTrue(third.contains("Stop repeating"))
        // 被限流的调用不触达工具。
        assertEquals(2, tool.calls.get())
    }

    // ═══ 追踪 ═══════════════════════════════════════════════════════

    @Test
    fun `tracer records one span per attempt with retry attempt numbers`() = runTest {
        val tool = ScriptedTool(
            "traced_flaky",
            listOf("Error: execution failed: flake", "second try OK")
        )
        val tracer = ToolTraceRecorder()
        val executor = executor(
            tool,
            policyResolver = StaticPolicyResolver(
                ToolRunPolicy(timeoutMs = 0, maxRetries = 1, baseRetryDelayMs = 1)
            ),
            tracer = tracer
        )
        assertEquals("second try OK", executor.execute("traced_flaky", "{}"))

        val spans = tracer.spansFor("traced_flaky")
        assertEquals(2, spans.size)
        assertEquals(ToolTraceRecorder.Outcome.FAILED, spans[1].outcome) // newest first
        assertEquals(1, spans[1].attempt)
        assertEquals(2, spans[0].attempt)
        assertEquals(ToolTraceRecorder.Outcome.SUCCESS, spans[0].outcome)
    }

    @Test
    fun `usage tracker counts retries as one logical invocation`() = runTest {
        val tool = ScriptedTool(
            "tracked_flaky",
            listOf("Error: execution failed: flake", "recovered")
        )
        val tracker = ToolUsageTracker()
        val executor = executor(
            tool,
            policyResolver = StaticPolicyResolver(
                ToolRunPolicy(timeoutMs = 0, maxRetries = 1, baseRetryDelayMs = 1)
            ),
            tracker = tracker
        )
        executor.execute("tracked_flaky", "{}")
        val stat = tracker.statFor("tracked_flaky")
        assertNotNull(stat)
        assertEquals(1, stat!!.invocations)
        assertEquals(1, stat.successes)
        assertEquals(0, stat.failures)
    }

    // ═══ 门控与校验（v2 不变量在 v3 管线上仍成立）════════════════════

    @Test
    fun `schema validation still rejects bad arguments before the tool runs`() = runTest {
        val tool = object : BaseTool(
            id = "validated_tool",
            name = "Validated",
            description = "test",
            declaredSchema = toolSchema {
                string("path", required = true)
            }
        ) {
            override suspend fun executeStructured(arguments: String): ToolResult =
                ToolResult.ok("ran with $arguments")
        }
        val executor = executor(tool)
        val rejected = executor.execute("validated_tool", """{"wrong": 1}""")
        assertTrue(rejected.startsWith("Error: invalid argument"))
        assertTrue(rejected.contains("path"))
        val accepted = executor.execute("validated_tool", """{"path": "a.txt"}""")
        assertTrue(accepted.startsWith("ran with"))
    }

    @Test
    fun `unknown tool still produces the v2 suggestion error`() = runTest {
        val executor = executor(ScriptedTool("read_file", listOf("OK")))
        val error = executor.execute("read_fil", "{}")
        assertTrue(error.startsWith("Error: Tool 'read_fil' not found"))
        assertTrue(error.contains("read_file"))
    }

    // ═══ 流式路径 ═════════════════════════════════════════════════════

    @Test
    fun `executeStream wraps a plain tool as Output plus Complete`() = runTest {
        val executor = executor(ScriptedTool("plain_tool", listOf("payload")))
        val events = executor.executeStream("plain_tool", "{}").toList()
        assertEquals(2, events.size)
        assertTrue(events[0] is ToolStreamEvent.Output)
        assertEquals("payload", (events[0] as ToolStreamEvent.Output).chunk)
        assertTrue(events[1] is ToolStreamEvent.Complete)
    }

    @Test
    fun `executeStream turns a policy timeout into an Error event`() = runTest {
        val tool = ScriptedTool("stream_slow", listOf("late"), delayMs = 60_000)
        val executor = executor(
            tool,
            policyResolver = StaticPolicyResolver(ToolRunPolicy(timeoutMs = 100))
        )
        val events = executor.executeStream("stream_slow", "{}").toList()
        assertEquals(1, events.size)
        val error = events[0] as ToolStreamEvent.Error
        assertTrue(error.message.startsWith("Error: timeout"))
    }

    @Test
    fun `executeStream forwards streaming tool events verbatim`() = runTest {
        val streaming = object : StreamingAgentTool {
            override val id = "streaming_tool"
            override val name = id
            override val description = "streaming"
            override val parametersSchema = "{}"

            override fun executeStream(arguments: String) = kotlinx.coroutines.flow.flow {
                emit(ToolStreamEvent.Output("part-1"))
                emit(ToolStreamEvent.Output("part-2"))
                emit(ToolStreamEvent.Complete("part-1part-2"))
            }

            override suspend fun execute(arguments: String): String = "part-1part-2"
        }
        val executor = executor(streaming)
        val events = executor.executeStream("streaming_tool", "{}").toList()
        assertEquals(3, events.size)
        assertTrue(events[2] is ToolStreamEvent.Complete)
    }

    // ═══ Builder 装配 ═════════════════════════════════════════════════

    @Test
    fun `builder assembles the full v3 stack and bare builders degrade to v2 behaviour`() = runTest {
        val registry = DefaultToolRegistry()
        registry.register(ScriptedTool("built_tool", listOf("OK")))

        // 完整 v3 栈。
        val full = ToolExecutorBuilder(registry)
            .gate(ToolEnvironmentGate(ToolEnvironmentState()))
            .usageTracker(ToolUsageTracker())
            .policyResolver(StaticPolicyResolver(ToolRunPolicy(timeoutMs = 5_000)))
            .rateLimiter(ToolRateLimiter())
            .breaker(ToolCircuitBreaker())
            .tracer(ToolTraceRecorder())
            .build()
        assertEquals("OK", full.execute("built_tool", "{}"))

        // 裸 builder：无任何 v3 组件 —— 行为等同 v2 默认。
        val bare = ToolExecutorBuilder(registry).build()
        assertEquals("OK", bare.execute("built_tool", "{}"))

        // 组件真正接上（tracer 有 span 记录）。
        val tracer = ToolTraceRecorder()
        val traced = ToolExecutorBuilder(registry).tracer(tracer).build()
        traced.execute("built_tool", "{}")
        assertEquals(1, tracer.spansFor("built_tool").size)
    }

}
