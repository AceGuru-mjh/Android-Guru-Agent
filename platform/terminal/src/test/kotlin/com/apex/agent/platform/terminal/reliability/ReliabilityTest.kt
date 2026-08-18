package com.apex.agent.platform.terminal.reliability

import org.junit.Assert.*
import org.junit.Test

class TerminalFailureTest {

    @Test fun `PtyFailure is DEGRADED by default`() {
        val f = TerminalFailure.PtyFailure(sessionId = 1, jobId = null, operation = "read", cause = null)
        assertEquals(Recoverability.DEGRADED, f.recoverability)
        assertEquals(FailurePhase.RUNNING, f.phase)
    }

    @Test fun `IoFailure is RECOVERABLE by default`() {
        val f = TerminalFailure.IoFailure(sessionId = 1, jobId = 1, operation = "write", cause = null)
        assertEquals(Recoverability.RECOVERABLE, f.recoverability)
    }

    @Test fun `SessionFailure is TERMINAL by default`() {
        val f = TerminalFailure.SessionFailure(sessionId = 1, jobId = null, operation = "create", cause = null)
        assertEquals(Recoverability.TERMINAL, f.recoverability)
    }

    @Test fun `ObservationFailure is RECOVERABLE`() {
        val f = TerminalFailure.ObservationFailure(sessionId = 1, jobId = 1, operation = "observe", cause = null)
        assertEquals(Recoverability.RECOVERABLE, f.recoverability)
    }

    @Test fun `PersistenceFailure is DEGRADED`() {
        val f = TerminalFailure.PersistenceFailure(sessionId = 1, jobId = null, operation = "save", cause = null)
        assertEquals(Recoverability.DEGRADED, f.recoverability)
    }

    @Test fun `all failure types can be instantiated`() {
        assertNotNull(TerminalFailure.PtyFailure(1, 1, "r", null))
        assertNotNull(TerminalFailure.ProcessFailure(1, 1, "r", null))
        assertNotNull(TerminalFailure.IoFailure(1, 1, "r", null))
        assertNotNull(TerminalFailure.SessionFailure(1, null, "r", null))
        assertNotNull(TerminalFailure.ObservationFailure(1, 1, "r", null))
        assertNotNull(TerminalFailure.PersistenceFailure(1, null, "r", null))
        assertNotNull(TerminalFailure.ResourceFailure(1, null, "r", null))
        assertNotNull(TerminalFailure.RuntimeFailure(1, null, "r", null))
        assertNotNull(TerminalFailure.UnknownFailure(1, null, "r", null))
    }
}

class RecoveryDecisionEngineTest {

    @Test fun `RECOVERABLE failure → Retry (within maxAttempts)`() {
        val f = TerminalFailure.IoFailure(1, 1, "write", null)
        assertEquals(RecoveryDecision.Retry, RecoveryDecisionEngine.decide(f, 0, 3))
        assertEquals(RecoveryDecision.Retry, RecoveryDecisionEngine.decide(f, 1, 3))
        assertEquals(RecoveryDecision.Retry, RecoveryDecisionEngine.decide(f, 2, 3))
    }

    @Test fun `RECOVERABLE failure → Degrade (at maxAttempts)`() {
        val f = TerminalFailure.IoFailure(1, 1, "write", null)
        assertEquals(RecoveryDecision.Degrade, RecoveryDecisionEngine.decide(f, 3, 3))
    }

    @Test fun `DEGRADED failure → Reconcile`() {
        val f = TerminalFailure.PtyFailure(1, 1, "read", null)
        assertEquals(RecoveryDecision.Reconcile, RecoveryDecisionEngine.decide(f, 0, 3))
    }

    @Test fun `TERMINAL failure → Terminate`() {
        val f = TerminalFailure.SessionFailure(1, null, "create", null)
        assertEquals(RecoveryDecision.Terminate, RecoveryDecisionEngine.decide(f, 0, 3))
    }

    @Test fun `UNKNOWN failure → Retry then Terminate`() {
        val f = TerminalFailure.UnknownFailure(1, null, "unknown", null)
        assertEquals(RecoveryDecision.Retry, RecoveryDecisionEngine.decide(f, 0, 3))
        assertEquals(RecoveryDecision.Retry, RecoveryDecisionEngine.decide(f, 1, 3))
        assertEquals(RecoveryDecision.Terminate, RecoveryDecisionEngine.decide(f, 2, 3))
    }
}

class RetryPolicyTest {

    @Test fun `delay increases with backoff`() {
        val p = RetryPolicy(maxAttempts = 5, initialDelayMs = 100, maxDelayMs = 10000, backoffMultiplier = 2.0, jitterMs = 0)
        val d0 = p.delayFor(0)
        val d1 = p.delayFor(1)
        val d2 = p.delayFor(2)
        assertEquals(100L, d0)
        assertEquals(200L, d1)
        assertEquals(400L, d2)
    }

    @Test fun `delay capped at maxDelay`() {
        val p = RetryPolicy(maxAttempts = 10, initialDelayMs = 100, maxDelayMs = 500, backoffMultiplier = 2.0, jitterMs = 0)
        val d10 = p.delayFor(10)
        assertTrue("delay should be capped at 500", d10 <= 500)
    }

    @Test fun `CONSERVATIVE has maxAttempts=1`() {
        assertEquals(1, RetryPolicy.CONSERVATIVE.maxAttempts)
    }

    @Test fun `AGGRESSIVE has maxAttempts=5`() {
        assertEquals(5, RetryPolicy.AGGRESSIVE.maxAttempts)
    }
}

class RecoveryAttemptLimiterTest {

    @Test fun `allows first N attempts`() {
        val limiter = RecoveryAttemptLimiter(maxRecoveryAttempts = 3, resetWindowMs = 60000)
        assertFalse(limiter.shouldBlock(1))
        assertFalse(limiter.shouldBlock(1))
        assertFalse(limiter.shouldBlock(1))
    }

    @Test fun `blocks after maxAttempts`() {
        val limiter = RecoveryAttemptLimiter(maxRecoveryAttempts = 3, resetWindowMs = 60000)
        limiter.shouldBlock(1)
        limiter.shouldBlock(1)
        limiter.shouldBlock(1)
        assertTrue("should block after 3 attempts", limiter.shouldBlock(1))
    }

    @Test fun `reset clears storm detection`() {
        val limiter = RecoveryAttemptLimiter(maxRecoveryAttempts = 2, resetWindowMs = 60000)
        limiter.shouldBlock(1)
        limiter.shouldBlock(1)
        assertTrue(limiter.shouldBlock(1))
        limiter.reset(1)
        assertFalse("reset should clear storm", limiter.shouldBlock(1))
    }

    @Test fun `different sessions are independent`() {
        val limiter = RecoveryAttemptLimiter(maxRecoveryAttempts = 1, resetWindowMs = 60000)
        assertFalse(limiter.shouldBlock(1))
        assertTrue(limiter.shouldBlock(1))
        assertFalse("session 2 should not be blocked", limiter.shouldBlock(2))
    }
}

class RecoveryCoordinatorTest {

    private fun newCoordinator(): RecoveryCoordinator = RecoveryCoordinator(
        retryPolicy = RetryPolicy(maxAttempts = 3, jitterMs = 0),
        stormLimiter = RecoveryAttemptLimiter(maxRecoveryAttempts = 10, resetWindowMs = 60000)
    )

    @Test fun `tryStartRecovery returns context`() {
        val c = newCoordinator()
        val f = TerminalFailure.PtyFailure(1, 1, "read", null)
        val ctx = c.tryStartRecovery(1, f)
        assertNotNull(ctx)
        assertTrue(ctx!!.recoveryId.startsWith("recovery_"))
    }

    @Test fun `second recovery for same session is blocked (dedup)`() {
        val c = newCoordinator()
        c.tryStartRecovery(1, TerminalFailure.PtyFailure(1, 1, "read", null))
        val second = c.tryStartRecovery(1, TerminalFailure.IoFailure(1, 1, "write", null))
        assertNull("should not start second recovery for same session", second)
    }

    @Test fun `different sessions can recover in parallel`() {
        val c = newCoordinator()
        val ctx1 = c.tryStartRecovery(1, TerminalFailure.PtyFailure(1, 1, "read", null))
        val ctx2 = c.tryStartRecovery(2, TerminalFailure.IoFailure(2, 2, "write", null))
        assertNotNull(ctx1)
        assertNotNull(ctx2)
    }

    @Test fun `markSucceeded clears active recovery`() {
        val c = newCoordinator()
        val ctx = c.tryStartRecovery(1, TerminalFailure.IoFailure(1, 1, "write", null))!!
        c.markSucceeded(ctx)
        assertFalse(c.hasActiveRecovery(1))
        assertEquals(1, c.getMetrics().recoverySuccesses)
    }

    @Test fun `markFailed clears active recovery`() {
        val c = newCoordinator()
        val ctx = c.tryStartRecovery(1, TerminalFailure.SessionFailure(1, null, "create", null))!!
        c.markFailed(ctx, "unrecoverable")
        assertFalse(c.hasActiveRecovery(1))
        assertEquals(1, c.getMetrics().recoveryFailures)
    }

    @Test fun `abort clears active recovery (shutdown/recovery mutex)`() {
        val c = newCoordinator()
        val ctx = c.tryStartRecovery(1, TerminalFailure.PtyFailure(1, 1, "read", null))!!
        c.abort(1, "session closed during recovery")
        assertFalse(c.hasActiveRecovery(1))
    }

    @Test fun `incrementAttempt increments context`() {
        val c = newCoordinator()
        val ctx = c.tryStartRecovery(1, TerminalFailure.IoFailure(1, 1, "write", null))!!
        assertEquals(0, ctx.attempt)
        val updated = c.incrementAttempt(ctx)
        assertEquals(1, updated.attempt)
    }

    @Test fun `storm protection blocks after max attempts`() {
        val limiter = RecoveryAttemptLimiter(maxRecoveryAttempts = 2, resetWindowMs = 60000)
        val c = RecoveryCoordinator(stormLimiter = limiter)
        // First 2 recoveries OK
        c.tryStartRecovery(1, TerminalFailure.IoFailure(1, 1, "w", null))?.let { c.markSucceeded(it) }
        c.tryStartRecovery(1, TerminalFailure.IoFailure(1, 1, "w", null))?.let { c.markSucceeded(it) }
        // 3rd should be storm-blocked
        val blocked = c.tryStartRecovery(1, TerminalFailure.IoFailure(1, 1, "w", null))
        assertNull("storm should block 3rd recovery", blocked)
        assertEquals(1, c.getMetrics().recoveryLoopsPrevented)
    }

    @Test fun `metrics track attempts, successes, failures`() {
        val c = newCoordinator()
        val ctx1 = c.tryStartRecovery(1, TerminalFailure.IoFailure(1, 1, "w", null))!!
        c.markSucceeded(ctx1)
        val ctx2 = c.tryStartRecovery(2, TerminalFailure.SessionFailure(2, null, "c", null))!!
        c.markFailed(ctx2, "terminal")
        val m = c.getMetrics()
        assertEquals(2, m.recoveryAttempts)
        assertEquals(1, m.recoverySuccesses)
        assertEquals(1, m.recoveryFailures)
    }
}

class ThreeDimensionalStateTest {

    @Test fun `RUNNING + DEGRADED + RECOVERING is valid combined state`() {
        val s = TerminalRuntimeState(
            lifecycle = LifecycleState.RUNNING,
            health = HealthState.DEGRADED,
            recovery = RecoveryState.RECOVERING
        )
        assertEquals(LifecycleState.RUNNING, s.lifecycle)
        assertEquals(HealthState.DEGRADED, s.health)
        assertEquals(RecoveryState.RECOVERING, s.recovery)
    }

    @Test fun `HEALTHY + NONE + RUNNING is normal state`() {
        val s = TerminalRuntimeState(
            lifecycle = LifecycleState.RUNNING,
            health = HealthState.HEALTHY,
            recovery = RecoveryState.NONE
        )
        assertEquals(HealthState.HEALTHY, s.health)
    }

    @Test fun `CLOSED + FAILED + UNRECOVERABLE is terminal`() {
        val s = TerminalRuntimeState(
            lifecycle = LifecycleState.CLOSED,
            health = HealthState.FAILED,
            recovery = RecoveryState.UNRECOVERABLE
        )
        assertEquals(LifecycleState.CLOSED, s.lifecycle)
    }
}

class ResourceRegistryTest {

    @Test fun `register and release`() {
        val reg = ResourceRegistry()
        val e = ResourceEntry("r1", ResourceType.PTY, 1, System.currentTimeMillis())
        reg.register(e)
        assertFalse(reg.get("r1")!!.isReleased)
        reg.release("r1")
        assertTrue(reg.get("r1")!!.isReleased)
    }

    @Test fun `getByOwner returns unreleased resources`() {
        val reg = ResourceRegistry()
        reg.register(ResourceEntry("r1", ResourceType.PTY, 1, 0))
        reg.register(ResourceEntry("r2", ResourceType.JOB, 1, 0))
        reg.register(ResourceEntry("r3", ResourceType.PTY, 2, 0))
        val owned = reg.getByOwner(1)
        assertEquals(2, owned.size)
    }

    @Test fun `leakReport shows unreleased resources`() {
        val reg = ResourceRegistry()
        reg.register(ResourceEntry("r1", ResourceType.TIMER, 1, 0))
        reg.register(ResourceEntry("r2", ResourceType.WATCHER, 1, 0))
        reg.release("r1")
        val leaks = reg.leakReport()
        assertEquals(1, leaks.size)
        assertEquals("r2", leaks[0].resourceId)
    }

    @Test fun `unreleasedCount tracks active resources`() {
        val reg = ResourceRegistry()
        reg.register(ResourceEntry("r1", ResourceType.PTY, 1, 0))
        reg.register(ResourceEntry("r2", ResourceType.JOB, 1, 0))
        assertEquals(2, reg.unreleasedCount())
        reg.release("r1")
        assertEquals(1, reg.unreleasedCount())
    }
}

class CleanupProtocolTest {

    @Test fun `CleanupStep has 10 steps in order`() {
        val steps = CleanupStep.values()
        assertEquals(10, steps.size)
        assertEquals(CleanupStep.REQUEST_STOP, steps[0])
        assertEquals(CleanupStep.DONE, steps[9])
    }

    @Test fun `CleanupResult tracks completion`() {
        val result = CleanupResult(
            steps = listOf(CleanupStep.REQUEST_STOP, CleanupStep.STOP_INPUT, CleanupStep.DONE),
            completed = true,
            errors = emptyList()
        )
        assertTrue(result.completed)
        assertEquals(3, result.steps.size)
    }
}

class RecoveryEventTest {

    @Test fun `all event types can be instantiated`() {
        val f = TerminalFailure.IoFailure(1, 1, "w", null)
        assertNotNull(RecoveryEvent.Started("r1", 1, 0, f))
        assertNotNull(RecoveryEvent.Attempt("r1", 1, 0, 0, RecoveryDecision.Retry))
        assertNotNull(RecoveryEvent.Succeeded("r1", 1, 0))
        assertNotNull(RecoveryEvent.Degraded("r1", 1, 0, "partial"))
        assertNotNull(RecoveryEvent.Failed("r1", 1, 0, "terminal"))
        assertNotNull(RecoveryEvent.Aborted("r1", 1, 0, "shutdown"))
    }
}

class HealthSnapshotTest {

    @Test fun `health snapshot has all fields`() {
        val h = HealthSnapshot(
            state = HealthState.DEGRADED,
            activeSessions = 3,
            activeJobs = 5,
            activeRecoveries = 1,
            resourceLeaksDetected = false
        )
        assertEquals(HealthState.DEGRADED, h.state)
        assertEquals(3, h.activeSessions)
        assertEquals(5, h.activeJobs)
        assertFalse(h.resourceLeaksDetected)
    }
}
