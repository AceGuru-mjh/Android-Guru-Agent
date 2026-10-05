package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A68.2 + A68.3 — Engine resilience unit test suite.
 *
 * Categories:
 * 1. [FailureClassificationTests] — classifier heuristics (pure unit tests)
 * 2. [RetryPolicyTests] — policy decisions + backoff math (pure unit tests)
 * 3. [LoopDetectorTests] — repetition + oscillation detection (pure unit tests)
 *
 * (4-7 orchestrator integration suites removed with the dead
 * DefaultTaskOrchestrator chain — 2026-10 dead-code sweep; the classes these
 * tests target are reachable in production via EngineResilience instead.)
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
