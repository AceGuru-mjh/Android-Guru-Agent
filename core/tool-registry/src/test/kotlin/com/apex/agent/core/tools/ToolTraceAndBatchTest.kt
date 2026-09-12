package com.apex.agent.core.tools

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool System v3 — [ToolTraceRecorder] 与 [ToolBatchRunner] 测试。
 *
 * 追踪器：span 生命周期（begin/complete 只记一次）、容量环形驱逐、
 * 监听器分发、JSON 导出、按工具过滤、人类可读报告。
 *
 * 批量执行器：顺序执行、首错即停、NOT_EXECUTED 固定文案（Anthropic
 * computer-use 契约原文）、{n} 步间输出引用（整引用 + 模板内插值 +
 * 越界/失败引用的预校验拒绝）、整批超时预算。
 */
class ToolTraceRecorderTest {

    @Test
    fun `spans record outcome duration and attempt with monotonic call ids`() {
        val recorder = ToolTraceRecorder(capacity = 10)
        val handle = recorder.begin("web_fetch", """{"url":"http://x"}""")
        recorder.complete(handle)

        val spans = recorder.spans()
        assertEquals(1, spans.size)
        val span = spans.first()
        assertEquals("web_fetch", span.toolId)
        assertEquals(ToolTraceRecorder.Outcome.SUCCESS, span.outcome)
        assertEquals(1, span.attempt)
        assertTrue(span.durationMs >= 0)
        assertTrue(span.argsDigest.contains("ch/"))
        assertNull(span.errorSlug)
        assertTrue(span.callId >= 1)

        // 二次 complete 同一 handle —— 不产生第二个 span（幂等完成）。
        recorder.complete(handle)
        assertEquals(1, recorder.size())
    }

    @Test
    fun `failure and denial outcomes carry error slugs`() {
        val recorder = ToolTraceRecorder(capacity = 10)
        recorder.completeFailure(
            recorder.begin("shell_execute", "ls"),
            "permission_denied"
        )
        recorder.completeDenied(
            recorder.begin("app_uninstall", "{}"),
            "breaker"
        )
        val spans = recorder.spans()
        assertEquals(2, spans.size)
        // newest first：DENIED 在队头。
        assertEquals(ToolTraceRecorder.Outcome.DENIED, spans[0].outcome)
        assertEquals("breaker", spans[0].errorSlug)
        assertEquals(ToolTraceRecorder.Outcome.FAILED, spans[1].outcome)
        assertEquals("permission_denied", spans[1].errorSlug)
    }

    @Test
    fun `capacity evicts the oldest spans keeping the newest`() {
        val recorder = ToolTraceRecorder(capacity = 3)
        repeat(10) { index ->
            recorder.complete(recorder.begin("tool_$index", "{}"))
        }
        val spans = recorder.spans()
        assertEquals(3, spans.size)
        // newest first: tool_9, tool_8, tool_7。
        assertEquals(listOf("tool_9", "tool_8", "tool_7"), spans.map { it.toolId })
    }

    @Test
    fun `listeners receive every completed span and can be removed`() {
        val recorder = ToolTraceRecorder()
        val seen = mutableListOf<String>()
        val listener = ToolTraceRecorder.TraceListener { span -> seen += span.toolId }
        recorder.addListener(listener)
        recorder.complete(recorder.begin("a", "{}"))
        recorder.complete(recorder.begin("b", "{}"))
        recorder.removeListener(listener)
        recorder.complete(recorder.begin("c", "{}"))
        assertEquals(listOf("a", "b"), seen)
    }

    @Test
    fun `args are digested not stored raw - secrets stay out of traces`() {
        val recorder = ToolTraceRecorder()
        val handle = recorder.begin("http_request", """{"token":"SECRET_VALUE_12345"}""")
        recorder.complete(handle)
        val span = recorder.spans().first()
        // 摘要只保留长度/可打印字符统计 —— 原文绝不入 trace。
        assertFalse(span.argsDigest.contains("SECRET_VALUE_12345"))
        assertTrue(span.argsDigest.contains("ch"))
    }

    @Test
    fun `spansFor filters by tool and render includes a histogram`() {
        val recorder = ToolTraceRecorder()
        recorder.complete(recorder.begin("a", "{}"))
        recorder.completeFailure(recorder.begin("a", "{}"), "timeout")
        recorder.complete(recorder.begin("b", "{}"))
        assertEquals(2, recorder.spansFor("a").size)
        assertEquals(1, recorder.spansFor("b").size)
        assertEquals(0, recorder.spansFor("c").size)

        val report = recorder.render()
        assertTrue(report.contains("ok=2"))
        assertTrue(report.contains("fail=1"))
        assertTrue(report.contains("denied=0"))
    }

    @Test
    fun `toJson exports the newest spans as structured objects`() {
        val recorder = ToolTraceRecorder()
        recorder.complete(recorder.begin("web_search", """{"q":"kotlin"}"""))
        val json = recorder.toJson()
        assertEquals(1, json.size)
        val first = json.first().toString()
        assertTrue(first.contains("web_search"))
        assertTrue(first.contains("success"))
        assertTrue(first.contains("callId"))
    }
}

class ToolBatchRunnerTest {

    // ═══ fixtures ═══════════════════════════════════════════════════

    /** 脚本化执行器：按调用序返回预设结果并记录 (tool, arguments)。 */
    private class ScriptedExecutor(
        private val results: List<String>,
        val calls: MutableList<Pair<String, String>> = mutableListOf()
    ) : ToolExecutor {
        var index = 0

        override suspend fun execute(toolId: String, arguments: String): String {
            calls += toolId to arguments
            return if (index < results.size) results[index++] else "OK"
        }

        override fun executeStream(toolId: String, arguments: String) = flow {
            emit(ToolStreamEvent.Output(execute(toolId, arguments)))
        }
    }

    private fun steps(vararg pairs: Pair<String, String>): List<ToolBatchRunner.Step> =
        pairs.map { ToolBatchRunner.Step(it.first, it.second) }

    // ═══ 顺序执行与首错即停 ═════════════════════════════════════════

    @Test
    fun `all steps succeed in order and result is complete`() = runTest {
        val executor = ScriptedExecutor(listOf("alpha", "beta", "gamma"))
        val result = ToolBatchRunner(executor).run(
            steps(
                "read_file" to "{}",
                "search_files" to "{}",
                "write_file" to "{}"
            )
        )
        assertTrue(result.isComplete)
        assertNull(result.haltedAtIndex)
        assertEquals(3, result.outcomes.count { it is ToolBatchRunner.StepOutcome.Executed })
        assertEquals(0, result.outcomes.count { it is ToolBatchRunner.StepOutcome.NotExecuted })
    }

    @Test
    fun `first error halts the batch and later steps report the fixed marker`() = runTest {
        val executor = ScriptedExecutor(listOf("alpha", "Error: not found: no such file"))
        val result = ToolBatchRunner(executor).run(
            steps(
                "read_file" to "{}",
                "search_files" to "{}",
                "write_file" to "{}"
            )
        )
        assertFalse(result.isComplete)
        assertEquals(1, result.haltedAtIndex)
        assertEquals(1, result.outcomes.count { it is ToolBatchRunner.StepOutcome.Failed })
        // 未执行步骤统一返回 Anthropic 契约原文。
        val skipped = result.outcomes[2]
        assertTrue(skipped is ToolBatchRunner.StepOutcome.NotExecuted)
        assertEquals(
            "Not executed: an earlier action in this turn failed.",
            (skipped as ToolBatchRunner.StepOutcome.NotExecuted).reason
        )
        // 第三个工具从未被调用。
        assertEquals(2, executor.calls.size)
    }

    @Test
    fun `rendered result numbers every step and preserves error prefixes`() = runTest {
        val executor = ScriptedExecutor(listOf("out0", "Error: timeout: too slow"))
        val result = ToolBatchRunner(executor).run(
            steps("a" to "{}", "b" to "{}", "c" to "{}")
        )
        val text = result.render()
        assertTrue(text.contains("batch result (3 steps, halted)"))
        assertTrue(text.contains("[0] a → ok"))
        assertTrue(text.contains("[1] b → FAILED"))
        assertTrue(text.contains("Error: timeout: too slow"))
        assertTrue(text.contains("Not executed: an earlier action in this turn failed."))
    }

    // ═══ {n} 步间引用 ═══════════════════════════════════════════════

    @Test
    fun `whole-body reference pipes the earlier output verbatim`() = runTest {
        val executor = ScriptedExecutor(emptyList())
        val runner = ToolBatchRunner(executor)
        runner.run(
            steps(
                "read_file" to "{}",            // returns "OK" (default tail)
                "write_file" to "{0}"
            )
        )
        // 第二步的 arguments = 第一步的输出原文。
        assertEquals("OK", executor.calls[1].second)
    }

    @Test
    fun `template reference interpolates inside a larger payload`() = runTest {
        val executor = ScriptedExecutor(listOf("CONTENT_BODY"))
        val runner = ToolBatchRunner(executor)
        runner.run(
            steps(
                "read_file" to "{}",
                "write_file" to """{"content": "{0} and more"}"""
            )
        )
        assertEquals(
            """{"content": "CONTENT_BODY and more"}""",
            executor.calls[1].second
        )
    }

    @Test
    fun `reference to a failed or out-of-range step fails before anything runs`() = runTest {
        // 步骤 0 失败 → 批次首错即停，步骤 1 从未进入解析（NOT_EXECUTED）。
        val executor = ScriptedExecutor(listOf("Error: execution failed: boom"))
        val result = ToolBatchRunner(executor).run(
            steps(
                "a" to "{}",
                "b" to "{0}"
            )
        )
        assertFalse(result.isComplete)
        assertTrue(result.outcomes[0] is ToolBatchRunner.StepOutcome.Failed)
        assertTrue(result.outcomes[1] is ToolBatchRunner.StepOutcome.NotExecuted)

        // 前向/越界引用在执行前即拒绝（调用计数为零）。
        val executor2 = ScriptedExecutor(emptyList())
        val result2 = ToolBatchRunner(executor2).run(steps("a" to "{5}"))
        val failed = result2.outcomes[0]
        assertTrue(failed is ToolBatchRunner.StepOutcome.Failed)
        assertTrue(
            (failed as ToolBatchRunner.StepOutcome.Failed).error.contains("not-executed or failed step")
        )
        assertEquals(0, executor2.calls.size)
    }

    @Test
    fun `reference content is head-limited to the configured budget`() = runTest {
        val executor = ScriptedExecutor(listOf("x".repeat(100)))
        val runner = ToolBatchRunner(executor, referenceHeadLimit = 10)
        runner.run(
            steps(
                "a" to "{}",
                "b" to "{0}"
            )
        )
        assertEquals(10, executor.calls[1].second.length)
    }

    // ═══ 整批预算与边界 ═════════════════════════════════════════════

    @Test
    fun `batch budget timeout marks the in-flight step and skips the rest`() = runTest {
        // 每步都挂起超过预算 —— 挂起通过真实 delay 实现。
        val slowExecutor = object : ToolExecutor {
            val calls = java.util.concurrent.atomic.AtomicInteger()
            override suspend fun execute(toolId: String, arguments: String): String {
                calls.incrementAndGet()
                kotlinx.coroutines.delay(10_000)
                return "OK"
            }

            override fun executeStream(toolId: String, arguments: String) = flow {
                emit(ToolStreamEvent.Output(execute(toolId, arguments)))
            }
        }
        val result = ToolBatchRunner(slowExecutor).run(
            steps("a" to "{}", "b" to "{}", "c" to "{}"),
            totalBudgetMs = 50
        )
        assertTrue(result.timedOut)
        assertFalse(result.isComplete)
        assertEquals(1, slowExecutor.calls.get()) // 只有第一步真正跑过
        assertTrue(result.outcomes.count { it is ToolBatchRunner.StepOutcome.NotExecuted } >= 1)
        val text = result.render()
        assertTrue(text.contains("batch timed out"))
    }

    @Test
    fun `step count is capped and oversized batches are rejected upfront`() {
        val oversized = (1..17).map { ToolBatchRunner.Step("t$it", "{}") }
        try {
            kotlinx.coroutines.runBlocking { ToolBatchRunner(ScriptedExecutor(emptyList())).run(oversized) }
            org.junit.Assert.fail("expected IllegalArgumentException for 17 steps")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("max 16"))
        }
    }
}
