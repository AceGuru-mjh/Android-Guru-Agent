package com.apex.agent.core.tools.hook

import com.apex.agent.core.tools.EnhancedToolExecutor
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.GateDecision
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolCircuitBreaker
import com.apex.agent.core.tools.ToolExecutionGate
import com.apex.agent.core.tools.ToolExecutorBuilder
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRateLimiter
import com.apex.agent.core.tools.ToolRunPolicy
import com.apex.agent.core.tools.ToolRunPolicyResolver
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolStreamEvent
import com.apex.agent.core.tools.ToolTraceRecorder
import com.apex.agent.core.tools.ToolUsageTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Issue #165 — [EnhancedToolExecutor] 钩子插槽（beforeToolHooks /
 * afterToolHooks）在真实执行管线上的行为测试。
 *
 * 锁定四个不变量：
 * 1. PreToolUse 在 **gate 之后、schema 校验之前**——gate 拒绝时钩子不触发；
 *    改写后的参数仍要过 schema；
 * 2. Blocked 以 gate 拒绝的同款文案短路（`Error: permission denied: …`），
 *    工具不执行；
 * 3. PostToolUse 在工具真实执行结果产生后回调（成功 / "Error:" 结构化
 *    失败 / 异常转换文案三态），回调自身异常不吞执行结果；
 * 4. 不设置钩子时行为与接入前逐字节一致（对照测试，与 ToolV3ExecutorTest
 *    互补——那边锁定 v3 全栈，这边锁定「无钩子 = 无回归」）。
 *
 * 构造惯例跟随 ToolV3ExecutorTest（ScriptedTool / StaticPolicyResolver /
 * 直接构造 EnhancedToolExecutor）。
 */
class ExecutorHookTest {

    // ═══ fixtures ═══════════════════════════════════════════════════

    /** 脚本化工具：预设结果序列 + 调用计数 + 参数记录（可观测改写是否生效）。 */
    private class ScriptedTool(
        override val id: String,
        private val results: List<String>,
        private val annotations: ToolAnnotations = ToolAnnotations.readOnly(),
        private val delayMs: Long = 0,
        private val throwOnFirstCall: Throwable? = null
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
            argumentsSeen += arguments
            throwOnFirstCall?.let { if (calls.get() == 0) throw it }
            calls.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            val index = calls.get() - 1
            return if (index < results.size) results[index] else results.lastOrNull() ?: "OK"
        }
    }

    /** 固定策略解析器（测试注入）。 */
    private class StaticPolicyResolver(private val policy: ToolRunPolicy) : ToolRunPolicyResolver {
        override fun resolve(tool: AgentTool): ToolRunPolicy = policy
    }

    /** 固定拒绝门（PreToolUse 必须在它之后，用它验证插入点）。 */
    private fun denyGate(reason: String): ToolExecutionGate =
        object : ToolExecutionGate {
            override suspend fun check(tool: AgentTool, arguments: String): GateDecision =
                GateDecision.Deny(reason)
        }

    /** PreToolUse 钩子记录器。 */
    private class BeforeRecorder(
        private val dispatch: suspend (String, ToolArguments) -> HookDispatchResult?
    ) {
        val fired = java.util.concurrent.atomic.AtomicInteger()
        val toolIds = mutableListOf<String>()
        val argsSeen = mutableListOf<String>()
        val runner: suspend (String, ToolArguments) -> HookDispatchResult? = { toolId, args ->
            fired.incrementAndGet()
            toolIds += toolId
            argsSeen += args.raw
            dispatch(toolId, args)
        }
    }

    /** PostToolUse 钩子记录器。 */
    private class AfterRecorder {
        val events = mutableListOf<PostRecord>()
        val runner: suspend (String, ToolArguments, String, Boolean, Long) -> Unit =
            { toolId, args, result, isError, durationMs ->
                events += PostRecord(toolId, args.raw, result, isError, durationMs)
            }
    }

    private data class PostRecord(
        val toolId: String,
        val args: String,
        val result: String,
        val isError: Boolean,
        val durationMs: Long
    )

    private fun executor(
        vararg tools: AgentTool,
        policyResolver: ToolRunPolicyResolver? = null,
        gate: ToolExecutionGate? = null,
        before: (suspend (String, ToolArguments) -> HookDispatchResult?)? = null,
        after: (suspend (String, ToolArguments, String, Boolean, Long) -> Unit)? = null
    ): EnhancedToolExecutor {
        val registry = DefaultToolRegistry()
        tools.forEach { registry.register(it) }
        return EnhancedToolExecutor(
            registry = registry,
            gate = gate,
            policyResolver = policyResolver,
            beforeToolHooks = before,
            afterToolHooks = after
        )
    }

    private fun blockedDispatch(reason: String): suspend (String, ToolArguments) -> HookDispatchResult? =
        { _, _ -> HookDispatchResult(blocked = true, blockReason = reason, firedCount = 1) }

    private fun modifiedDispatch(newArgs: String): suspend (String, ToolArguments) -> HookDispatchResult? =
        { _, _ ->
            HookDispatchResult(
                modifiedArgs = ToolArguments.parseOrNull(newArgs),
                firedCount = 1
            )
        }

    // ═══ PreToolUse：拦截 ═════════════════════════════════════════════

    @Test
    fun `before hook block denies execution with gate-style message`() = runTest {
        val tool = ScriptedTool("gated_write", listOf("should never run"))
        val before = BeforeRecorder(blockedDispatch("user hook says no"))
        val executor = executor(tool, before = before.runner)

        val result = executor.execute("gated_write", """{"path": "a.txt"}""")

        assertTrue(result, result.startsWith("Error: permission denied"))
        assertTrue(result.contains("user hook says no"))
        assertEquals("钩子拦截后工具不执行", 0, tool.calls.get())
        assertEquals("钩子确实触发了一次", 1, before.fired.get())
    }

    @Test
    fun `before hook runs after the gate - gate denial skips hooks`() = runTest {
        val tool = ScriptedTool("denied_read", listOf("never"))
        val before = BeforeRecorder { _, _ -> null }
        val executor = executor(
            tool,
            gate = denyGate("gate says no"),
            before = before.runner
        )

        val result = executor.execute("denied_read", "{}")
        assertTrue(result.startsWith("Error: permission denied"))
        assertTrue(result.contains("gate says no"))
        assertEquals("gate 拒绝时 PreToolUse 不触发（插在 gate 之后）", 0, before.fired.get())
    }

    // ═══ PreToolUse：改写 ═══════════════════════════════════════════

    @Test
    fun `before hook modification replaces the payload the tool receives`() = runTest {
        val tool = ScriptedTool("read_file", listOf("content"))
        val executor = executor(
            tool,
            before = modifiedDispatch("""{"path": "rewritten.txt", "limit": 10}""")
        )

        val result = executor.execute("read_file", """{"path": "original.txt"}""")
        assertEquals("content", result)
        assertEquals(listOf("""{"path": "rewritten.txt", "limit": 10}"""), tool.argumentsSeen)
    }

    @Test
    fun `modified args still go through schema validation`() = runTest {
        // 声明式 schema：required path（宽松导入 v1 JSON 渲染串）。
        val tool = object : AgentTool {
            override val id = "validated_read"
            override val name = id
            override val description = "test"
            override val parametersSchema =
                """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
            override suspend fun execute(arguments: String) = "ran"
        }
        // 钩子把合法参数改写成缺 path 的参数 → schema 必须拒绝。
        val executor = executor(tool, before = modifiedDispatch("""{"limit": 5}"""))

        val result = executor.execute("validated_read", """{"path": "a.txt"}""")
        assertTrue(result, result.startsWith("Error: invalid argument"))
        assertTrue(result.contains("path"))
    }

    @Test
    fun `pre-tool-use fires once per logical call even across retries`() = runTest {
        // retrySafe + 瞬态失败 → 两次尝试；钩子只触发一次，重试沿用改写后的载荷。
        val tool = ScriptedTool(
            "flaky_rewrite",
            listOf("Error: execution failed: transient flake", "recovered"),
            annotations = ToolAnnotations.readOnly()
        )
        val before = BeforeRecorder(modifiedDispatch("""{"v": 2}"""))
        val executor = executor(
            tool,
            policyResolver = StaticPolicyResolver(
                ToolRunPolicy(timeoutMs = 0, maxRetries = 1, baseRetryDelayMs = 1)
            ),
            before = before.runner
        )

        val result = executor.execute("flaky_rewrite", """{"v": 1}""")
        assertEquals("recovered", result)
        assertEquals(2, tool.calls.get())
        assertEquals("钩子按逻辑调用只触发一次", 1, before.fired.get())
        assertEquals(
            "两次尝试都用改写后的载荷",
            listOf("""{"v": 2}""", """{"v": 2}"""),
            tool.argumentsSeen
        )
    }

    // ═══ PostToolUse：三态回调 ═══════════════════════════════════════

    @Test
    fun `after hook receives success results with isError false`() = runTest {
        val tool = ScriptedTool("ok_tool", listOf("payload"))
        val after = AfterRecorder()
        val executor = executor(tool, after = after.runner)

        executor.execute("ok_tool", """{"q": 1}""")

        assertEquals(1, after.events.size)
        val record = after.events[0]
        assertEquals("ok_tool", record.toolId)
        assertEquals("""{"q": 1}""", record.args)
        assertEquals("payload", record.result)
        assertFalse(record.isError)
        assertTrue("durationMs 照算", record.durationMs >= 0)
    }

    @Test
    fun `after hook receives error-string results with isError true`() = runTest {
        val tool = ScriptedTool("failing_tool", listOf("Error: execution failed: down"))
        val after = AfterRecorder()
        val executor = executor(tool, after = after.runner)

        val result = executor.execute("failing_tool", "{}")
        assertTrue(result.startsWith("Error"))
        assertEquals(1, after.events.size)
        assertTrue(after.events[0].isError)
        assertEquals("Error: execution failed: down", after.events[0].result)
    }

    @Test
    fun `after hook receives exception-path results with isError true`() = runTest {
        val tool = ScriptedTool(
            "crashing_tool",
            listOf("never"),
            throwOnFirstCall = IOException("disk on fire")
        )
        val after = AfterRecorder()
        val executor = executor(tool, after = after.runner)

        val result = executor.execute("crashing_tool", "{}")
        assertTrue(result, result.startsWith("Error: execution failed"))
        assertTrue(result.contains("disk on fire"))
        assertEquals("异常路径也回调", 1, after.events.size)
        assertTrue(after.events[0].isError)
        assertTrue(after.events[0].result.contains("disk on fire"))
    }

    @Test
    fun `after hook failure is isolated and never eats the execution result`() = runTest {
        val tool = ScriptedTool("protected_tool", listOf("real result"))
        val executor = executor(
            tool,
            after = { _, _, _, _, _ -> throw IllegalStateException("hook exploded") }
        )

        val result = executor.execute("protected_tool", "{}")
        assertEquals("real result", result)
        assertNotNull("异常现场保留在诊断口", executor.lastAfterHookError)
        assertEquals("hook exploded", executor.lastAfterHookError!!.message)
    }

    @Test
    fun `after hook is not called when pre-check fails`() = runTest {
        val tool = ScriptedTool("blocked_tool", listOf("never"))
        val after = AfterRecorder()
        val executor = executor(
            tool,
            before = blockedDispatch("no way"),
            after = after.runner
        )

        val result = executor.execute("blocked_tool", "{}")
        assertTrue(result.startsWith("Error: permission denied"))
        assertEquals("前置检查失败（钩子拦截）不算执行结果", 0, after.events.size)
    }

    // ═══ 无钩子对照（零回归）══════════════════════════════════════════

    @Test
    fun `no hooks wired keeps behaviour byte-identical to v3`() = runTest {
        val tool = ScriptedTool("plain_tool", listOf("OK"))
        val tracer = ToolTraceRecorder()
        val tracker = ToolUsageTracker()

        // 完整 v3 栈 + 无钩子：成功路径、追踪、统计与接入前一致。
        val registry = DefaultToolRegistry()
        registry.register(tool)
        val full = ToolExecutorBuilder(registry)
            .usageTracker(tracker)
            .policyResolver(StaticPolicyResolver(ToolRunPolicy(timeoutMs = 5_000)))
            .rateLimiter(ToolRateLimiter())
            .breaker(ToolCircuitBreaker())
            .tracer(tracer)
            .build()
        assertEquals("OK", full.execute("plain_tool", "{}"))
        assertEquals(1, tracer.spansFor("plain_tool").size)
        assertEquals(1, tracker.statFor("plain_tool")!!.successes)
        assertNull("无钩子回调异常", (full as EnhancedToolExecutor).lastAfterHookError)

        // 门控拒绝路径的文案不受钩子接线影响。
        val gated = executor(
            ScriptedTool("gated", listOf("never")),
            gate = denyGate("gate reason")
        )
        val denied = gated.execute("gated", "{}")
        assertEquals("Error: permission denied: gate reason", denied)
    }

    // ═══ 流式路径 ═════════════════════════════════════════════════════

    @Test
    fun `executeStream emits a single error event when before hook blocks`() = runTest {
        val tool = ScriptedTool("stream_blocked", listOf("never"))
        val executor = executor(tool, before = blockedDispatch("stream no"))

        val events = executor.executeStream("stream_blocked", "{}").toList()
        assertEquals(1, events.size)
        val error = events[0] as ToolStreamEvent.Error
        assertTrue(error.message.startsWith("Error: permission denied"))
        assertTrue(error.message.contains("stream no"))
        assertEquals(0, tool.calls.get())
    }

    @Test
    fun `executeStream passes modified args to the tool`() = runTest {
        val tool = ScriptedTool("stream_mod", listOf("payload"))
        val executor = executor(tool, before = modifiedDispatch("""{"m": true}"""))

        val events = executor.executeStream("stream_mod", """{"m": false}""").toList()
        assertEquals(2, events.size)
        assertTrue(events[1] is ToolStreamEvent.Complete)
        assertEquals(listOf("""{"m": true}"""), tool.argumentsSeen)
    }

    @Test
    fun `executeStream after hook observes the terminal complete event`() = runTest {
        val tool = ScriptedTool("stream_ok", listOf("payload"))
        val after = AfterRecorder()
        val executor = executor(tool, after = after.runner)

        executor.executeStream("stream_ok", "{}").toList()

        assertEquals(1, after.events.size)
        assertEquals("payload", after.events[0].result)
        assertFalse(after.events[0].isError)
    }

    @Test
    fun `executeStream after hook observes structured error strings`() = runTest {
        val tool = ScriptedTool("stream_fail", listOf("Error: execution failed: down"))
        val after = AfterRecorder()
        val executor = executor(tool, after = after.runner)

        executor.executeStream("stream_fail", "{}").toList()

        assertEquals(1, after.events.size)
        assertTrue(after.events[0].isError)
        assertTrue(after.events[0].result.startsWith("Error"))
    }

    @Test
    fun `executeStream after hook observes the exception path`() = runTest {
        val tool = ScriptedTool(
            "stream_crash",
            listOf("never"),
            throwOnFirstCall = IOException("stream io boom")
        )
        val after = AfterRecorder()
        val executor = executor(tool, after = after.runner)

        val events = executor.executeStream("stream_crash", "{}").toList()
        assertEquals(1, events.size)
        assertTrue(events[0] is ToolStreamEvent.Error)
        assertEquals(1, after.events.size)
        assertTrue(after.events[0].isError)
        assertTrue(after.events[0].result.contains("stream io boom"))
    }

    // ═══ Builder 装配 ═════════════════════════════════════════════════

    @Test
    fun `builder slots wire both hooks end to end`() = runTest {
        val registry = DefaultToolRegistry()
        registry.register(ScriptedTool("built_tool", listOf("OK")))
        val after = AfterRecorder()

        val executor = ToolExecutorBuilder(registry)
            .beforeToolHooks(modifiedDispatch("""{"via": "builder"}"""))
            .afterToolHooks(after.runner)
            .build()

        assertEquals("OK", executor.execute("built_tool", """{"via": "ctor"}"""))
        assertEquals(1, after.events.size)
        assertFalse(after.events[0].isError)
    }
}
