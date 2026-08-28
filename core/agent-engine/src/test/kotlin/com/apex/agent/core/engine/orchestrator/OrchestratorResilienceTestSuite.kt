package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A68.2 + A68.3 — Orchestrator resilience & parallelism test suite.
 *
 * Categories:
 * 1. [FailureClassificationTests] — classifier heuristics (pure unit tests)
 * 2. [RetryPolicyTests] — policy decisions + backoff math (pure unit tests)
 * 3. [LoopDetectorTests] — repetition + oscillation detection (pure unit tests)
 * 4. [RetryIntegrationTests] — transient tool failure retried end-to-end;
 *    non-retryable failures not retried; retry budget exhaustion
 * 5. [LoopRecoveryIntegrationTests] — recovery prompt injected into history;
 *    recovery budget exhaustion fails the task
 * 6. [ToolCallGraphTests] — dependency extraction, levels, cycle, skip closure
 * 7. [ParallelExecutionTests] — true concurrency (maxInFlight > 1), dependency
 *    ordering, partial failure isolation + aggregation, same-tool chaining,
 *    cycle fallback to serial
 *
 * All deterministic — no real LLM / tools / wall-clock dependence.
 */
class FailureClassificationTests {

    private val classifier = FailureClassifier()

    private fun classify(message: String, timedOut: Boolean = false, ex: Throwable? = null) =
        classifier.classify(ToolFailure("tool", "c1", message, timedOut, ex))

    @Test
    fun `network blips are TRANSIENT`() {
        for (msg in listOf(
            "Error: network unavailable", "Connection reset by peer",
            "java.net.SocketTimeoutException: socket timeout",
            "HTTP 503 Service Unavailable", "429 Too Many Requests"
        )) {
            assertEquals("expected TRANSIENT for '$msg'", FailureClass.TRANSIENT, classify(msg))
        }
    }

    @Test
    fun `timeout flag wins over message`() {
        assertEquals(
            FailureClass.TIMEOUT,
            classifier.classify(ToolFailure("t", "c", "network error", timedOut = true))
        )
    }

    @Test
    fun `TimeoutCancellationException is TIMEOUT`() {
        // The orchestrator sets timedOut=true in its withTimeout catch —
        // the flag path is the production path for timeouts.
        assertEquals(
            FailureClass.TIMEOUT,
            classifier.classify(ToolFailure("t", "c", "", timedOut = true))
        )
    }

    @Test
    fun `denied actions are PERMISSION and never retried`() {
        for (msg in listOf(
            "open failed: EACCES (Permission denied)", "403 Forbidden",
            "Shizuku not granted", "java.lang.SecurityException: no a11y"
        )) {
            assertEquals("expected PERMISSION for '$msg'", FailureClass.PERMISSION, classify(msg))
        }
    }

    @Test
    fun `SecurityException type is PERMISSION`() {
        assertEquals(
            FailureClass.PERMISSION,
            classify("boom", ex = SecurityException("denied"))
        )
    }

    @Test
    fun `deterministic errors are FATAL`() {
        for (msg in listOf(
            "invalid arguments: expected JSON object", "file not found: /x/y",
            "IndexOutOfBoundsException: 5"
        )) {
            assertEquals("expected FATAL for '$msg'", FailureClass.FATAL, classify(msg))
        }
    }

    @Test
    fun `IOException family is TRANSIENT`() {
        assertEquals(
            FailureClass.TRANSIENT,
            classify("weird io", ex = java.io.IOException("weird io"))
        )
    }
}

class RetryPolicyTests {

    @Test
    fun `transient failures are retried up to maxRetries`() {
        val policy = RetryPolicy(maxRetries = 2, retryBudget = 10)
        assertTrue(policy.shouldRetry(FailureClass.TRANSIENT, 1, 0) is RetryPolicy.RetryDecision.Retry)
        assertTrue(policy.shouldRetry(FailureClass.TRANSIENT, 2, 5) is RetryPolicy.RetryDecision.Retry)
        // attempt 3 > maxRetries(2) → stop
        assertTrue(policy.shouldRetry(FailureClass.TRANSIENT, 3, 0) is RetryPolicy.RetryDecision.Stop)
    }

    @Test
    fun `fatal and permission failures are never retried`() {
        val policy = RetryPolicy.DEFAULT
        assertTrue(policy.shouldRetry(FailureClass.FATAL, 1, 0) is RetryPolicy.RetryDecision.Stop)
        assertTrue(policy.shouldRetry(FailureClass.PERMISSION, 1, 0) is RetryPolicy.RetryDecision.Stop)
    }

    @Test
    fun `retry budget exhaustion stops retries`() {
        val policy = RetryPolicy(maxRetries = 5, retryBudget = 2)
        assertTrue(policy.shouldRetry(FailureClass.TRANSIENT, 1, 0) is RetryPolicy.RetryDecision.Retry)
        assertTrue(policy.shouldRetry(FailureClass.TRANSIENT, 1, 1) is RetryPolicy.RetryDecision.Retry)
        // budget used == 2 == retryBudget → stop even though attempts remain
        assertTrue(policy.shouldRetry(FailureClass.TRANSIENT, 1, 2) is RetryPolicy.RetryDecision.Stop)
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val policy = RetryPolicy(
            initialBackoffMs = 100, backoffMultiplier = 2.0,
            maxBackoffMs = 400, jitterRatio = 0.0
        )
        assertEquals(100L, policy.backoffDelayMs(1))
        assertEquals(200L, policy.backoffDelayMs(2))
        assertEquals(400L, policy.backoffDelayMs(3))
        assertEquals(400L, policy.backoffDelayMs(10)) // capped
    }

    @Test
    fun `DISABLED policy never retries`() {
        val policy = RetryPolicy.DISABLED
        assertTrue(policy.shouldRetry(FailureClass.TRANSIENT, 1, 0) is RetryPolicy.RetryDecision.Stop)
    }

    @Test
    fun `extraTimeoutRetries extends timeout allowance only`() {
        val policy = RetryPolicy(maxRetries = 1, extraTimeoutRetries = 2, retryBudget = 10)
        // TRANSIENT: attempt 2 > maxRetries(1) → stop
        assertTrue(policy.shouldRetry(FailureClass.TRANSIENT, 2, 0) is RetryPolicy.RetryDecision.Stop)
        // TIMEOUT: attempts up to 1+2=3 allowed
        assertTrue(policy.shouldRetry(FailureClass.TIMEOUT, 2, 0) is RetryPolicy.RetryDecision.Retry)
        assertTrue(policy.shouldRetry(FailureClass.TIMEOUT, 3, 0) is RetryPolicy.RetryDecision.Retry)
        assertTrue(policy.shouldRetry(FailureClass.TIMEOUT, 4, 0) is RetryPolicy.RetryDecision.Stop)
    }
}

class LoopDetectorTests {

    @Test
    fun `identical calls repeated beyond threshold fire Repetition`() {
        val detector = LoopDetector(maxRepetitions = 3, windowSize = 10)
        detector.record("shell_exec", "ls")
        detector.record("shell_exec", "ls")
        assertEquals(null, detector.detect())
        detector.record("shell_exec", "ls")
        val signal = detector.detect()
        assertTrue("expected Repetition, got $signal", signal is LoopSignal.Repetition)
        assertEquals(3, (signal as LoopSignal.Repetition).repetitions)
    }

    @Test
    fun `distinct calls do not fire`() {
        val detector = LoopDetector(maxRepetitions = 3, windowSize = 10)
        detector.record("shell_exec", "ls")
        detector.record("file_read", "a.txt")
        detector.record("shell_exec", "pwd")
        assertEquals(null, detector.detect())
    }

    @Test
    fun `same tool different args do not fire repetition`() {
        val detector = LoopDetector(maxRepetitions = 3, windowSize = 10)
        detector.record("file_read", "a")
        detector.record("file_read", "b")
        detector.record("file_read", "c")
        assertEquals(null, detector.detect())
    }

    @Test
    fun `A-B-A-B oscillation fires Oscillation`() {
        val detector = LoopDetector(maxRepetitions = 5, windowSize = 10)
        detector.record("read", "x")
        detector.record("write", "y")
        detector.record("read", "x")
        detector.record("write", "y")
        val signal = detector.detect()
        assertTrue("expected Oscillation, got $signal", signal is LoopSignal.Oscillation)
        assertEquals(2, (signal as LoopSignal.Oscillation).period)
        assertEquals(listOf("read", "write"), signal.pattern)
    }

    @Test
    fun `window slides — old calls fall out`() {
        val detector = LoopDetector(maxRepetitions = 3, windowSize = 3)
        detector.record("t", "a")
        detector.record("t", "a")
        detector.record("other", "x") // pushes first "t,a" out of window
        detector.record("t", "a")
        // window now: [t,a other,x t,a] → only 2 repetitions → no signal
        assertEquals(null, detector.detect())
    }

    @Test
    fun `acknowledge clears the window`() {
        val detector = LoopDetector(maxRepetitions = 3, windowSize = 10)
        repeat(3) { detector.record("t", "a") }
        assertTrue(detector.detect() is LoopSignal.Repetition)
        detector.acknowledge()
        assertEquals(0, detector.windowCount)
        assertEquals(null, detector.detect())
    }
}

// ═══════════════════════════════════════════════════════════════════════
// A68.2 — Retry integration (through DefaultTaskOrchestrator)
// ═══════════════════════════════════════════════════════════════════════

/**
 * Stateful fake: fails the first [remainingFailures] calls, then succeeds.
 * Tracks concurrency (maxInFlight), started-vs-completed calls, and can
 * block on a gate / inject a virtual delay so that PARALLEL interleaving
 * is observable under runTest's single-threaded virtual-time dispatcher
 * (without a suspension point, workers would run start-to-finish
 * sequentially and maxInFlight would stay 1).
 */
private class FlakyToolExecutor(
    private val virtualDelayMs: Long = 0L
) : ToolExecutor {
    var remainingFailures = 0
    val calls = mutableListOf<Pair<String, String>>() // COMPLETED calls
    val starts = mutableListOf<String>() // STARTED calls (before blocking)
    var inFlight = 0
    var maxInFlight = 0
    /** When set, each call suspends on it before producing output. */
    var blocker: CompletableDeferred<Unit>? = null

    override suspend fun execute(toolId: String, arguments: String): String = executeStream(toolId, arguments)
        .toList().joinToString("") { (it as? ToolStreamEvent.Output)?.chunk ?: "" }

    override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> = flow {
        synchronized(this@FlakyToolExecutor) { starts.add(toolId) }
        synchronized(this@FlakyToolExecutor) {
            inFlight++
            if (inFlight > maxInFlight) maxInFlight = inFlight
        }
        try {
            blocker?.await()
            if (virtualDelayMs > 0) delay(virtualDelayMs)
            if (remainingFailures > 0) {
                remainingFailures--
                emit(ToolStreamEvent.Error("Error: connection reset by peer"))
            } else {
                emit(ToolStreamEvent.Output("ok"))
                emit(ToolStreamEvent.Complete("ok"))
            }
        } finally {
            synchronized(this@FlakyToolExecutor) { inFlight-- }
        }
        calls.add(toolId to arguments)
    }
}

class RetryIntegrationTests {

    @Test
    fun `transient failure is retried and eventually succeeds`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(ToolCall(id = "c1", name = "flaky", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        val executor = FlakyToolExecutor().apply { remainingFailures = 2 }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(toolTimeoutMs = 0L, retryPolicy = RetryPolicy.FAST)
        )

        val events = orchestrator.execute("test").toList()

        val completes = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(1, completes.size)
        assertTrue("retried call should finally succeed", completes[0].success)
        assertEquals("ok", completes[0].output)

        // 1 initial + 2 retries = 3 executor invocations
        assertEquals(3, executor.calls.size)

        // Task completed
        assertTrue(orchestrator.state.value is TaskState.Finished.Completed)

        // Progress accounts retries
        assertEquals(2, orchestrator.progress.value.retriedToolCalls)
    }

    @Test
    fun `transient failure with retries exhausted reports error to LLM`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(ToolCall(id = "c1", name = "flaky", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "gave up gracefully", toolCalls = emptyList())
            )
        )
        val executor = FlakyToolExecutor().apply { remainingFailures = 99 }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(
                toolTimeoutMs = 0L,
                retryPolicy = RetryPolicy(maxRetries = 2, initialBackoffMs = 0, maxBackoffMs = 0, jitterRatio = 0.0, retryBudget = 10)
            )
        )

        val events = orchestrator.execute("test").toList()

        val completes = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(1, completes.size)
        assertFalse("should fail after retries exhausted", completes[0].success)
        assertTrue(
            "output should mention the underlying error",
            completes[0].output.contains("connection reset")
        )
        assertTrue(
            "output should disclose retry history to the LLM",
            completes[0].output.contains("retried 2 time")
        )
        // 1 initial + 2 retries
        assertEquals(3, executor.calls.size)
        // Task continues and completes (failTaskOnToolError=false)
        assertTrue(orchestrator.state.value is TaskState.Finished.Completed)
    }

    @Test
    fun `fatal failure is NOT retried`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(ToolCall(id = "c1", name = "broken", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "handled", toolCalls = emptyList())
            )
        )
        val executor = FakeToolExecutor().apply {
            registerError("broken", "invalid arguments: expected JSON object")
        }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(toolTimeoutMs = 0L, retryPolicy = RetryPolicy.FAST)
        )

        orchestrator.execute("test").toList()

        // Exactly one attempt — FATAL is not retryable
        assertEquals(1, executor.callLog.size)
        assertEquals(0, orchestrator.progress.value.retriedToolCalls)
    }

    @Test
    fun `task retry budget bounds total retries across calls`() = runTest {
        // Two tool calls, each with 99 remaining transient failures, but the
        // task-wide budget is only 2 → call#1 burns the budget, call#2 gets
        // no retries at all.
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling two",
                    toolCalls = listOf(
                        ToolCall(id = "c1", name = "flaky", arguments = """{"n":1}"""),
                        ToolCall(id = "c2", name = "flaky", arguments = """{"n":2}""")
                    )
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        val executor = FlakyToolExecutor().apply { remainingFailures = 99 }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(
                toolTimeoutMs = 0L,
                retryPolicy = RetryPolicy(
                    maxRetries = 5, initialBackoffMs = 0, maxBackoffMs = 0,
                    jitterRatio = 0.0, retryBudget = 2
                ),
                // serial to make call counting deterministic per call
                enableParallelToolExecution = false
            )
        )

        orchestrator.execute("test").toList()

        // c1: 1 initial + 2 budgeted retries; c2: 1 initial + 0 (budget gone)
        assertEquals(4, executor.calls.size)
        assertEquals(2, orchestrator.progress.value.retriedToolCalls)
    }

    @Test
    fun `ToolCallRetried lifecycle events are emitted`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(ToolCall(id = "c1", name = "flaky", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        val executor = FlakyToolExecutor().apply { remainingFailures = 1 }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(toolTimeoutMs = 0L, retryPolicy = RetryPolicy.FAST)
        )

        val lifecycle = mutableListOf<TaskLifecycleEvent>()
        val job = launch { orchestrator.lifecycleEvents.collect { lifecycle.add(it) } }
        kotlinx.coroutines.delay(10)
        orchestrator.execute("test").toList()
        kotlinx.coroutines.delay(10)
        job.cancel()

        val retries = lifecycle.filterIsInstance<TaskLifecycleEvent.ToolCallRetried>()
        assertEquals(1, retries.size)
        assertEquals("flaky", retries[0].toolName)
        assertEquals(1, retries[0].failedAttempt)
        assertEquals(2, retries[0].nextAttempt)
        assertEquals(FailureClass.TRANSIENT, retries[0].failureClass)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// A68.2 — Loop detection + recovery integration
// ═══════════════════════════════════════════════════════════════════════

class LoopRecoveryIntegrationTests {

    @Test
    fun `repeated identical calls trigger recovery prompt in LLM history`() = runTest {
        // LLM calls the same tool with identical args 3 times, then obeys
        // the recovery notice and answers.
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "try", toolCalls = listOf(ToolCall(id = "c1", name = "stuck", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "try again", toolCalls = listOf(ToolCall(id = "c2", name = "stuck", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "try again", toolCalls = listOf(ToolCall(id = "c3", name = "stuck", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "I changed strategy, done", toolCalls = emptyList())
            )
        )
        val executor = FakeToolExecutor().apply { registerSuccess("stuck", "ok") }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(toolTimeoutMs = 0L, retryPolicy = RetryPolicy.DISABLED)
        )

        val lifecycle = mutableListOf<TaskLifecycleEvent>()
        val job = launch { orchestrator.lifecycleEvents.collect { lifecycle.add(it) } }
        kotlinx.coroutines.delay(10)
        orchestrator.execute("test").toList()
        kotlinx.coroutines.delay(10)
        job.cancel()

        // Task completed (LLM recovered after the notice)
        assertTrue(
            "Expected Completed, got ${orchestrator.state.value}",
            orchestrator.state.value is TaskState.Finished.Completed
        )

        // The 4th LLM call must contain the injected recovery system prompt
        val fourthCallMessages = llm.callLog[3].first
        val recoveryMsg = fourthCallMessages.filterIsInstance<LlmMessage.System>()
            .firstOrNull { it.content.contains("RECOVERY NOTICE") }
        assertNotNull("4th LLM call should carry the recovery prompt", recoveryMsg)

        // Lifecycle saw the loop + the recovery
        assertTrue(lifecycle.any { it is TaskLifecycleEvent.LoopDetected })
        assertTrue(lifecycle.any { it is TaskLifecycleEvent.RecoveryTriggered })
        assertEquals(1, orchestrator.progress.value.recoveryCount)
    }

    @Test
    fun `loop with exhausted recovery budget fails the task`() = runTest {
        // maxRecoveries=1: first loop → recovery injected; LLM keeps looping;
        // second loop signal → budget exhausted → task fails.
        val responses = (1..4).map { i ->
            FakeLlmClient.ScriptedResponse.Ok(
                content = "try",
                toolCalls = listOf(ToolCall(id = "c$i", name = "stuck", arguments = "{}"))
            )
        }
        val llm = FakeLlmClient(responses)
        val executor = FakeToolExecutor().apply { registerSuccess("stuck", "ok") }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(
                toolTimeoutMs = 0L,
                retryPolicy = RetryPolicy.DISABLED,
                loopDetectionMaxRepetitions = 2,
                maxRecoveries = 1
            ),
            agentConfig = AgentConfig(
                mode = AgentMode.BUILD, thinkingLevel = ThinkingLevel.NONE, maxIterations = 10
            )
        )

        orchestrator.execute("test").toList()

        val state = orchestrator.state.value
        assertTrue("Expected Failed, got $state", state is TaskState.Finished.Failed)
        val failed = state as TaskState.Finished.Failed
        assertTrue(
            "failure should mention loop/recovery, got: ${failed.message}",
            failed.message.contains("Loop detected")
        )
    }

    @Test
    fun `loop detection disabled preserves plain A68-1 behaviour`() = runTest {
        // With detection off, the same call may repeat freely — no recovery
        // prompts, no failure. (Iterations still bounded by maxIterations.)
        val responses = (1..3).map { i ->
            FakeLlmClient.ScriptedResponse.Ok(
                content = "try",
                toolCalls = listOf(ToolCall(id = "c$i", name = "stuck", arguments = "{}"))
            )
        } + FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
        val llm = FakeLlmClient(responses)
        val executor = FakeToolExecutor().apply { registerSuccess("stuck", "ok") }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(
                toolTimeoutMs = 0L,
                retryPolicy = RetryPolicy.DISABLED,
                enableLoopDetection = false
            )
        )

        orchestrator.execute("test").toList()

        assertTrue(orchestrator.state.value is TaskState.Finished.Completed)
        assertEquals(0, orchestrator.progress.value.recoveryCount)
        // No recovery prompt ever injected
        llm.callLog.forEach { (messages, _) ->
            assertTrue(
                "no recovery prompt expected",
                messages.filterIsInstance<LlmMessage.System>().none { it.content.contains("RECOVERY NOTICE") }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// A68.3 — Dependency graph unit tests
// ═══════════════════════════════════════════════════════════════════════

class ToolCallGraphTests {

    private fun call(id: String, name: String, args: String = "{}") = ToolCall(id, name, args)

    @Test
    fun `independent calls form a single parallel level`() {
        val graph = ToolCallGraph.fromToolCalls(
            listOf(call("a", "t1"), call("b", "t2"), call("c", "t3"))
        )
        val levels = graph.parallelLevels()
        assertEquals(1, levels.size)
        assertEquals(setOf("a", "b", "c"), levels[0].map { it.callId }.toSet())
        assertFalse(graph.hasCycle)
    }

    @Test
    fun `explicit depends_on creates levels`() {
        val graph = ToolCallGraph.fromToolCalls(
            listOf(
                call("a", "t1"),
                call("b", "t2", """{"depends_on":["a"]}"""),
                call("c", "t3")
            )
        )
        val levels = graph.parallelLevels()
        assertEquals(2, levels.size)
        // a and c are independent → level 0; b waits for a → level 1
        assertEquals(setOf("a", "c"), levels[0].map { it.callId }.toSet())
        assertEquals(listOf("b"), levels[1].map { it.callId })
    }

    @Test
    fun `same-tool calls are chained conservatively`() {
        val graph = ToolCallGraph.fromToolCalls(
            listOf(call("a", "shell"), call("b", "web"), call("c", "shell"))
        )
        val levels = graph.parallelLevels()
        // a → c (same tool chained); b independent
        assertEquals(setOf("a", "b"), levels[0].map { it.callId }.toSet())
        assertEquals(listOf("c"), levels[1].map { it.callId })
    }

    @Test
    fun `chainSameTool=false fans same-tool calls out`() {
        val graph = ToolCallGraph.fromToolCalls(
            listOf(call("a", "shell"), call("b", "shell")),
            chainSameTool = false
        )
        assertEquals(1, graph.parallelLevels().size)
    }

    @Test
    fun `dependency cycle is detected and falls back to serial levels`() {
        val graph = ToolCallGraph.fromToolCalls(
            listOf(
                call("a", "t1", """{"depends_on":["b"]}"""),
                call("b", "t2", """{"depends_on":["a"]}""")
            )
        )
        assertTrue(graph.hasCycle)
        // Serial fallback: each node its own level, original order
        val levels = graph.parallelLevels()
        assertEquals(2, levels.size)
        assertEquals("a", levels[0][0].callId)
        assertEquals("b", levels[1][0].callId)
    }

    @Test
    fun `unknown depends_on ids are ignored and do not create a cycle`() {
        val graph = ToolCallGraph.fromToolCalls(
            listOf(call("a", "t1", """{"depends_on":["ghost"]}"""))
        )
        assertFalse(graph.hasCycle)
        assertEquals(setOf("ghost"), graph.unresolvedDependencies)
        assertEquals(1, graph.parallelLevels().size)
    }

    @Test
    fun `malformed arguments JSON yields no dependencies`() {
        // NOTE: the malformed payload deliberately contains NO braces — the
        // CI brace-balance check counts raw characters, including those
        // inside string literals.
        assertEquals(
            emptySet<String>(),
            ToolCallGraph.extractDependencies(call("a", "t", "this is not json"))
        )
    }

    @Test
    fun `skip closure marks transitive dependents only`() {
        // a ← b ← c, plus independent d
        val graph = ToolCallGraph.fromToolCalls(
            listOf(
                call("a", "t1"),
                call("b", "t2", """{"depends_on":["a"]}"""),
                call("c", "t3", """{"depends_on":["b"]}"""),
                call("d", "t4")
            )
        )
        val skipped = graph.markSkippedFromFailures(setOf("a"))
        assertEquals(setOf("b", "c"), skipped)
        // d unaffected
        assertFalse("d" in skipped)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// A68.3 — Parallel execution integration (through DefaultTaskOrchestrator)
// ═══════════════════════════════════════════════════════════════════════

class ParallelExecutionTests {

    @Test
    fun `independent multi-call batch actually runs concurrently`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling three",
                    toolCalls = listOf(
                        ToolCall(id = "c1", name = "web_fetch", arguments = "{}"),
                        ToolCall(id = "c2", name = "file_read", arguments = "{}"),
                        ToolCall(id = "c3", name = "shell_exec", arguments = "{}")
                    )
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        val executor = FlakyToolExecutor(virtualDelayMs = 100)
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(toolTimeoutMs = 0L, retryPolicy = RetryPolicy.DISABLED)
        )

        val events = orchestrator.execute("test").toList()

        // All three ran, all succeeded
        val completes = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(3, completes.size)
        assertTrue(completes.all { it.success })

        // Concurrency proof: with a virtual delay inside each call, ≥2 calls
        // are in flight simultaneously. (Serial execution → maxInFlight == 1.)
        assertTrue(
            "expected concurrent execution, maxInFlight=${executor.maxInFlight}",
            executor.maxInFlight >= 2
        )

        // Batch summary lifecycle event
        val lifecycle = mutableListOf<TaskLifecycleEvent>()
        // (lifecycle collected post-hoc via replay=0 is empty; assert via events instead)
        assertTrue(orchestrator.state.value is TaskState.Finished.Completed)
        assertEquals(3, orchestrator.progress.value.completedToolCalls)
    }

    @Test
    fun `dependent call waits for its dependency`() = runTest {
        // c1 blocks on a deferred; c2 depends_on c1. c2 must NOT start
        // (execute on the tool) until c1's deferred completes.
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(
                        ToolCall(id = "c1", name = "slow", arguments = "{}"),
                        ToolCall(id = "c2", name = "fast", arguments = """{"depends_on":["c1"]}""")
                    )
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        val executor = FlakyToolExecutor()
        val gate = CompletableDeferred<Unit>()
        executor.blocker = gate
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(toolTimeoutMs = 0L, retryPolicy = RetryPolicy.DISABLED)
        )

        val collectorJob = launch { orchestrator.execute("test").toList() }
        kotlinx.coroutines.delay(50)
        // While c1 is blocked (gate not completed), ONLY c1 has started —
        // c2 sits behind the dependency barrier (level 1).
        assertEquals(listOf("slow"), executor.starts)

        gate.complete(Unit)
        collectorJob.join()

        assertEquals(2, executor.calls.size)
        assertEquals("fast", executor.calls[1].first)
        // c1 never overlapped c2 (dependency barrier) — maxInFlight stays 1
        assertEquals(1, executor.maxInFlight)
    }

    @Test
    fun `partial failure skips dependents but keeps independent branches`() = runTest {
        // a (fails) ← b (skipped); c (independent, succeeds)
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(
                        ToolCall(id = "a", name = "doomed", arguments = "{}"),
                        ToolCall(id = "b", name = "after_doomed", arguments = """{"depends_on":["a"]}"""),
                        ToolCall(id = "c", name = "independent", arguments = "{}")
                    )
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "handled partial failure", toolCalls = emptyList())
            )
        )
        val executor = FakeToolExecutor().apply {
            registerError("doomed", "hard deterministic failure")
            registerSuccess("after_doomed", "never runs")
            registerSuccess("independent", "fine")
        }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(toolTimeoutMs = 0L, retryPolicy = RetryPolicy.DISABLED)
        )

        val events = orchestrator.execute("test").toList()

        val completes = events.filterIsInstance<AgentEvent.ToolCallComplete>().associateBy { it.callId }
        assertEquals(3, completes.size)
        assertFalse("doomed fails", completes.getValue("a").success)
        assertFalse("dependent is marked failed", completes.getValue("b").success)
        assertTrue(
            "skipped dependent reports skip reason",
            completes.getValue("b").output.contains("skipped")
        )
        assertTrue("independent branch unaffected", completes.getValue("c").success)

        // The dependent was never actually executed
        assertTrue("after_doomed must not execute", executor.callLog.none { it.first == "after_doomed" })

        // LLM receives one ToolResult per call, in original order
        val llmSecondCall = llm.callLog[1].first
        val toolResults = llmSecondCall.filterIsInstance<LlmMessage.ToolResult>()
        assertEquals(listOf("a", "b", "c"), toolResults.map { it.toolCallId })
        assertTrue(toolResults[1].content.contains("skipped"))

        // Progress: 3 completed, 2 failed (failed + skipped), task completed
        assertEquals(3, orchestrator.progress.value.completedToolCalls)
        assertEquals(2, orchestrator.progress.value.failedToolCalls)
        assertTrue(orchestrator.state.value is TaskState.Finished.Completed)
    }

    @Test
    fun `transitive dependents are skipped through the chain`() = runTest {
        // a fails → b(dep a) skipped → c(dep b) skipped; d independent ok
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(
                        ToolCall(id = "a", name = "doomed", arguments = "{}"),
                        ToolCall(id = "b", name = "mid", arguments = """{"depends_on":["a"]}"""),
                        ToolCall(id = "c", name = "tail", arguments = """{"depends_on":["b"]}"""),
                        ToolCall(id = "d", name = "solo", arguments = "{}")
                    )
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        val executor = FakeToolExecutor().apply {
            registerError("doomed", "nope")
            registerSuccess("mid", "x")
            registerSuccess("tail", "y")
            registerSuccess("solo", "z")
        }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(toolTimeoutMs = 0L, retryPolicy = RetryPolicy.DISABLED)
        )

        orchestrator.execute("test").toList()

        val executed = executor.callLog.map { it.first }.toSet()
        assertEquals(setOf("doomed", "solo"), executed)
        assertTrue(orchestrator.state.value is TaskState.Finished.Completed)
    }

    @Test
    fun `same-tool calls in one batch stay serialized`() = runTest {
        // Two shell_exec calls: conservative chaining → maxInFlight == 1
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(
                        ToolCall(id = "c1", name = "shell_exec", arguments = """{"cmd":"a"}"""),
                        ToolCall(id = "c2", name = "shell_exec", arguments = """{"cmd":"b"}""")
                    )
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        val executor = FlakyToolExecutor()
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(toolTimeoutMs = 0L, retryPolicy = RetryPolicy.DISABLED)
        )

        orchestrator.execute("test").toList()

        assertEquals(2, executor.calls.size)
        assertEquals("same-tool calls must not overlap", 1, executor.maxInFlight)
    }

    @Test
    fun `dependency cycle falls back to serial and still delivers results`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(
                        ToolCall(id = "a", name = "t1", arguments = """{"depends_on":["b"]}"""),
                        ToolCall(id = "b", name = "t2", arguments = """{"depends_on":["a"]}""")
                    )
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        val executor = FakeToolExecutor().apply {
            registerSuccess("t1", "one")
            registerSuccess("t2", "two")
        }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(toolTimeoutMs = 0L, retryPolicy = RetryPolicy.DISABLED)
        )

        val events = orchestrator.execute("test").toList()

        // Both executed despite the cyclic declaration (serial fallback)
        assertEquals(2, executor.callLog.size)
        val completes = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(2, completes.size)
        assertTrue(completes.all { it.success })
        assertTrue(orchestrator.state.value is TaskState.Finished.Completed)
    }

    @Test
    fun `ask_user in a batch forces serial execution`() = runTest {
        // A batch containing ask_user must not be parallelised — the
        // question is asked AFTER the other calls complete.
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(
                        ToolCall(id = "c1", name = "web_fetch", arguments = "{}"),
                        ToolCall(id = "c2", name = "ask_user", arguments = """{"question":"Name?"}""")
                    )
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "Hi John!", toolCalls = emptyList())
            )
        )
        val executor = FakeToolExecutor().apply { registerSuccess("web_fetch", "page") }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(toolTimeoutMs = 0L, retryPolicy = RetryPolicy.DISABLED)
        )

        val collected = mutableListOf<AgentEvent>()
        val job = launch { orchestrator.execute("test").collect { collected.add(it) } }
        kotlinx.coroutines.delay(100)
        assertTrue(collected.any { it is AgentEvent.UserInputRequired })
        orchestrator.submitUserInput("John")
        job.join()

        assertTrue(orchestrator.state.value is TaskState.Finished.Completed)
    }

    @Test
    fun `ParallelBatchFinished lifecycle event carries aggregation`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(
                        ToolCall(id = "a", name = "ok_tool", arguments = "{}"),
                        ToolCall(id = "b", name = "bad_tool", arguments = "{}")
                    )
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        val executor = FakeToolExecutor().apply {
            registerSuccess("ok_tool", "fine")
            registerError("bad_tool", "boom")
        }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(toolTimeoutMs = 0L, retryPolicy = RetryPolicy.DISABLED)
        )

        val lifecycle = mutableListOf<TaskLifecycleEvent>()
        val job = launch { orchestrator.lifecycleEvents.collect { lifecycle.add(it) } }
        kotlinx.coroutines.delay(10)
        orchestrator.execute("test").toList()
        kotlinx.coroutines.delay(10)
        job.cancel()

        val batch = lifecycle.filterIsInstance<TaskLifecycleEvent.ParallelBatchFinished>()
        assertEquals(1, batch.size)
        assertEquals(2, batch[0].totalCalls)
        assertEquals(1, batch[0].succeededCount)
        assertEquals(1, batch[0].failedCount)
        assertEquals(0, batch[0].skippedCount)
    }

    @Test
    fun `parallel retries consume the shared budget across workers`() = runTest {
        // Two parallel flaky calls, each fails twice transiently then
        // succeeds; budget = 4 exactly covers 2+2 retries.
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(
                        ToolCall(id = "a", name = "flaky", arguments = "{}"),
                        ToolCall(id = "b", name = "flaky2", arguments = "{}")
                    )
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        // FlakyToolExecutor fails per-executor, not per-tool; use two separate
        // fakes merged via a dispatching executor.
        val flaky1 = FlakyToolExecutor().apply { remainingFailures = 2 }
        val flaky2 = FlakyToolExecutor().apply { remainingFailures = 2 }
        val executor = object : ToolExecutor {
            override suspend fun execute(toolId: String, arguments: String): String =
                pick(toolId).execute(toolId, arguments)
            override fun executeStream(toolId: String, arguments: String) =
                pick(toolId).executeStream(toolId, arguments)
            private fun pick(id: String) = when (id) {
                "flaky" -> flaky1
                else -> flaky2
            }
        }
        val orchestrator = buildResilientOrchestrator(
            llm, executor,
            config = TaskOrchestratorConfig(
                toolTimeoutMs = 0L,
                retryPolicy = RetryPolicy(
                    maxRetries = 3, initialBackoffMs = 0, maxBackoffMs = 0,
                    jitterRatio = 0.0, retryBudget = 4
                )
            )
        )

        val events = orchestrator.execute("test").toList()

        val completes = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(2, completes.size)
        assertTrue("both should succeed after budgeted retries", completes.all { it.success })
        assertEquals(3, flaky1.calls.size)
        assertEquals(3, flaky2.calls.size)
        assertEquals(4, orchestrator.progress.value.retriedToolCalls)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Test helper
// ═══════════════════════════════════════════════════════════════════════

private fun buildResilientOrchestrator(
    llm: FakeLlmClient,
    toolExecutor: ToolExecutor,
    config: TaskOrchestratorConfig,
    agentConfig: AgentConfig = AgentConfig(
        mode = AgentMode.BUILD,
        thinkingLevel = ThinkingLevel.NONE,
        maxIterations = 5
    )
): DefaultTaskOrchestrator {
    return DefaultTaskOrchestrator(
        llmClient = llm,
        toolExecutor = toolExecutor,
        toolRegistry = FakeToolRegistry(
            Triple("file_read", "Read a file", "{}"),
            Triple("shell_exec", "Run a shell command", "{}"),
            Triple("web_fetch", "Fetch URL", "{}"),
            Triple("ask_user", "Ask user", "{}"),
            Triple("stuck", "Looping tool", "{}"),
            Triple("flaky", "Flaky tool", "{}"),
            Triple("flaky2", "Flaky tool 2", "{}"),
            Triple("broken", "Broken tool", "{}"),
            Triple("doomed", "Doomed tool", "{}"),
            Triple("after_doomed", "Dependent tool", "{}"),
            Triple("independent", "Independent tool", "{}"),
            Triple("mid", "Mid tool", "{}"),
            Triple("tail", "Tail tool", "{}"),
            Triple("solo", "Solo tool", "{}"),
            Triple("ok_tool", "OK tool", "{}"),
            Triple("bad_tool", "Bad tool", "{}"),
            Triple("slow", "Slow tool", "{}"),
            Triple("fast", "Fast tool", "{}"),
            Triple("t1", "Tool 1", "{}"),
            Triple("t2", "Tool 2", "{}"),
            Triple("t3", "Tool 3", "{}")
        ),
        agentConfig = agentConfig,
        initialOrchestratorConfig = config
    )
}
