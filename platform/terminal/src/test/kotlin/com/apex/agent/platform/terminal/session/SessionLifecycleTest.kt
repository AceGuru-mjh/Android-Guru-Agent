package com.apex.agent.platform.terminal.session

import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import org.junit.Assert.*
import org.junit.Test

/**
 * Session Lifecycle 2.0 tests (Spec §31 PR #54).
 */
class SessionStateMachineTest {

    @Test fun `CREATED to STARTING valid`() = assertTrue(SessionStateMachine.isValid(SessionState.CREATED, SessionState.STARTING))
    @Test fun `STARTING to READY valid`() = assertTrue(SessionStateMachine.isValid(SessionState.STARTING, SessionState.READY))
    @Test fun `RUNNING to WAITING_INPUT valid`() = assertTrue(SessionStateMachine.isValid(SessionState.RUNNING, SessionState.WAITING_INPUT))
    @Test fun `RUNNING to STOPPING valid`() = assertTrue(SessionStateMachine.isValid(SessionState.RUNNING, SessionState.STOPPING))
    @Test fun `RUNNING to SUSPENDED valid`() = assertTrue(SessionStateMachine.isValid(SessionState.RUNNING, SessionState.SUSPENDED))
    @Test fun `SUSPENDED to RUNNING valid`() = assertTrue(SessionStateMachine.isValid(SessionState.SUSPENDED, SessionState.RUNNING))
    @Test fun `STOPPING to EXITED valid`() = assertTrue(SessionStateMachine.isValid(SessionState.STOPPING, SessionState.EXITED))
    @Test fun `any to LOST valid from RUNNING`() = assertTrue(SessionStateMachine.isValid(SessionState.RUNNING, SessionState.LOST))
    @Test fun `EXITED to CLOSED valid`() = assertTrue(SessionStateMachine.isValid(SessionState.EXITED, SessionState.CLOSED))
    @Test fun `LOST to CLOSED valid`() = assertTrue(SessionStateMachine.isValid(SessionState.LOST, SessionState.CLOSED))

    @Test fun `EXITED to RUNNING forbidden`() = assertFalse(SessionStateMachine.isValid(SessionState.EXITED, SessionState.RUNNING))
    @Test fun `LOST to RUNNING forbidden`() = assertFalse(SessionStateMachine.isValid(SessionState.LOST, SessionState.RUNNING))
    @Test fun `FAILED to RUNNING forbidden`() = assertFalse(SessionStateMachine.isValid(SessionState.FAILED, SessionState.RUNNING))
    @Test fun `STOPPING to STARTING forbidden`() = assertFalse(SessionStateMachine.isValid(SessionState.STOPPING, SessionState.STARTING))
    @Test fun `CLOSED to anything forbidden`() = assertFalse(SessionStateMachine.isValid(SessionState.CLOSED, SessionState.RUNNING))

    @Test fun `requireValid throws on illegal`() {
        assertThrows(IllegalStateException::class.java) {
            SessionStateMachine.requireValid(SessionState.EXITED, SessionState.RUNNING)
        }
    }

    @Test fun `aliveStates includes RUNNING and READY`() {
        assertTrue(SessionStateMachine.aliveStates.contains(SessionState.RUNNING))
        assertTrue(SessionStateMachine.aliveStates.contains(SessionState.READY))
        assertFalse(SessionStateMachine.aliveStates.contains(SessionState.EXITED))
    }

    @Test fun `deadStates includes EXITED LOST FAILED CLOSED`() {
        assertTrue(SessionStateMachine.deadStates.contains(SessionState.EXITED))
        assertTrue(SessionStateMachine.deadStates.contains(SessionState.LOST))
        assertTrue(SessionStateMachine.deadStates.contains(SessionState.CLOSED))
    }
}

class SessionExitReasonTest {
    @Test fun `all reasons exist`() {
        val reasons = SessionExitReason.values()
        assertTrue(reasons.any { it.name == "NORMAL" })
        assertTrue(reasons.any { it.name == "USER_REQUESTED" })
        assertTrue(reasons.any { it.name == "LOST" })
        assertTrue(reasons.any { it.name == "PROCESS_EXIT" })
        assertTrue(reasons.any { it.name == "PTY_FAILURE" })
        assertTrue(reasons.any { it.name == "UNKNOWN" })
    }
}

class SessionLifecycleE2ETest {

    private fun newRuntime(): TerminalRuntimeImpl = TerminalRuntimeImpl(
        native = FakeNativePty(),
        policy = TerminalPolicyImpl(),
        virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
    )

    @Test fun `create session → READY state`() = kotlinx.coroutines.runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        assertEquals("READY", s.state)
    }

    @Test fun `close is idempotent — double close returns success`() = kotlinx.coroutines.runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        val r1 = rt.close(s.sessionId)
        assertTrue("first close should succeed", r1.isSuccess)
        // Second close — session assembly removed, should return idempotent success
        val r2 = rt.close(s.sessionId)
        assertTrue("second close should succeed (idempotent)", r2.isSuccess)
        assertEquals("ALREADY_CLOSED", r2.getOrThrow().cause)
    }

    @Test fun `stop stops jobs but session stays alive`() = kotlinx.coroutines.runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = rt.stop(s.sessionId)
        assertTrue("stop should succeed", r.isSuccess)
        // Session should still be usable (unlike close)
        val snap = rt.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrThrow()
        assertTrue("session should still exist after stop", snap.sessions.any { it.session.id == s.sessionId })
    }

    @Test fun `stop ≠ close — after stop session usable, after close not`() = kotlinx.coroutines.runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        rt.stop(s.sessionId)
        // Can still run after stop (session alive)
        val job = rt.run(s.sessionId, "echo after_stop", com.apex.agent.platform.terminal.io.InputOwner.AGENT).getOrThrow()
        assertNotNull(job)
        // After close — session gone
        rt.close(s.sessionId)
        val snap = rt.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrThrow()
        assertFalse("session should NOT exist after close", snap.sessions.any { it.session.id == s.sessionId })
    }

    @Test fun `multiple sessions are independent`() = kotlinx.coroutines.runBlocking {
        val rt = newRuntime()
        val s1 = rt.create().getOrThrow()
        val s2 = rt.create().getOrThrow()
        val s3 = rt.create().getOrThrow()
        assertNotEquals("session ids must differ", s1.sessionId, s2.sessionId)
        assertNotEquals(s2.sessionId, s3.sessionId)
        // Close s1 — s2 and s3 still alive
        rt.close(s1.sessionId)
        val snap = rt.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrThrow()
        assertFalse(snap.sessions.any { it.session.id == s1.sessionId })
        assertTrue(snap.sessions.any { it.session.id == s2.sessionId })
        assertTrue(snap.sessions.any { it.session.id == s3.sessionId })
    }

    @Test fun `job exit does not close session`() = kotlinx.coroutines.runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val job = rt.run(s.sessionId, "echo test", com.apex.agent.platform.terminal.io.InputOwner.AGENT).getOrThrow()
        rt.wait(s.sessionId, com.apex.agent.platform.terminal.wait.WaitCondition.ProcessExited(job.jobId), 5000)
        // Session should still be alive after job exits
        val snap = rt.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrThrow()
        assertTrue("session should survive job exit", snap.sessions.any { it.session.id == s.sessionId })
    }

    @Test fun `resource cleanup — create+close 20 times no leak`() = kotlinx.coroutines.runBlocking {
        val rt = newRuntime()
        val ids = mutableListOf<Long>()
        for (i in 1..20) {
            val s = rt.create().getOrThrow()
            ids.add(s.sessionId)
            rt.close(s.sessionId)
        }
        // After 20 create+close cycles, no sessions should remain
        val snap = rt.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrThrow()
        assertEquals("no leaked sessions", 0, snap.sessions.size)
    }

    @Test fun `recover returns empty on fresh runtime`() = kotlinx.coroutines.runBlocking {
        val rt = newRuntime()
        val recovered = rt.recover()
        assertTrue("fresh runtime recovers nothing", recovered.isEmpty())
    }

    @Test fun `reconcile marks dead PTY as LOST`() = kotlinx.coroutines.runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        // Simulate persisted session that no longer has a PTY
        val results = (rt.sessionManager as SessionManagerImpl).reconcile(listOf(99999L))
        assertTrue("should have result", results.isNotEmpty())
        assertEquals("dead PTY should be LOST", SessionState.LOST, results[0].actualState)
        assertFalse("LOST is not recoverable", results[0].recoverable)
    }
}
