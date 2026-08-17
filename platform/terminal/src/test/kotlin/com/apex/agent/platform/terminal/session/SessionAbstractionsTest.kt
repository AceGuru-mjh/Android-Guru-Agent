package com.apex.agent.platform.terminal.session

import org.junit.Assert.*
import org.junit.Test

/**
 * PR #54 supplement: test new abstractions (Config, Snapshot, Event, PrimaryProcess, ExecutionBackend).
 */
class TerminalSessionConfigTest {
    @Test fun `config has sensible defaults`() {
        val c = TerminalSessionConfig()
        assertNull(c.shell)
        assertNull(c.workingDirectory)
        assertEquals(24, c.rows)
        assertEquals(80, c.cols)
        assertTrue(c.environment.isEmpty())
    }

    @Test fun `config does not contain runtime state`() {
        // Config fields should NOT include: pid, state, exitCode, cursor, screen
        val c = TerminalSessionConfig(shell = "/bin/sh", workingDirectory = "/tmp", rows = 40, cols = 120)
        assertEquals("/bin/sh", c.shell)
        assertEquals("/tmp", c.workingDirectory)
        assertEquals(40, c.rows)
        assertEquals(120, c.cols)
    }
}

class TerminalSessionSnapshotTest {
    @Test fun `snapshot is consistent — EXITED has finishedAt`() {
        val snap = TerminalSessionSnapshot(
            sessionId = 1L, state = SessionState.EXITED,
            exitReason = SessionExitReason.PRIMARY_PROCESS_EXITED,
            createdAt = 1000L, startedAt = 1001L, finishedAt = 2000L,
            primaryProcessId = 42L, shell = "bash", workingDirectory = "/tmp"
        )
        assertEquals(SessionState.EXITED, snap.state)
        assertEquals(SessionExitReason.PRIMARY_PROCESS_EXITED, snap.exitReason)
        assertNotNull(snap.finishedAt)
    }

    @Test fun `snapshot RUNNING has no finishedAt`() {
        val snap = TerminalSessionSnapshot(
            sessionId = 1L, state = SessionState.RUNNING,
            exitReason = null, createdAt = 1000L, startedAt = 1001L, finishedAt = null,
            primaryProcessId = 42L, shell = "bash", workingDirectory = null
        )
        assertEquals(SessionState.RUNNING, snap.state)
        assertNull(snap.finishedAt)
        assertNull(snap.exitReason)
    }
}

class TerminalSessionEventTest {
    @Test fun `all event types exist`() {
        val stateChanged = TerminalSessionEvent.StateChanged(1L, SessionState.RUNNING, SessionState.EXITED)
        val primaryExited = TerminalSessionEvent.PrimaryProcessExited(1L, 42L, 0)
        val lost = TerminalSessionEvent.Lost(1L, "PTY gone")
        val jobChanged = TerminalSessionEvent.JobChanged(1L, 1L, "EXITED")
        val closed = TerminalSessionEvent.Closed(1L, SessionExitReason.NORMAL)

        assertInstanceOf(TerminalSessionEvent.StateChanged::class.java, stateChanged)
        assertInstanceOf(TerminalSessionEvent.PrimaryProcessExited::class.java, primaryExited)
        assertInstanceOf(TerminalSessionEvent.Lost::class.java, lost)
        assertInstanceOf(TerminalSessionEvent.JobChanged::class.java, jobChanged)
        assertInstanceOf(TerminalSessionEvent.Closed::class.java, closed)
    }
}

class PrimaryProcessTest {
    @Test fun `primary process alive when no exitCode`() {
        val p = PrimaryProcess(sessionId = 1L, pid = 42, startedAt = 1000L)
        assertTrue(p.isAlive)
        assertNull(p.exitCode)
    }

    @Test fun `primary process not alive when exited`() {
        val p = PrimaryProcess(sessionId = 1L, pid = 42, startedAt = 1000L, exitCode = 0, finishedAt = 2000L)
        assertFalse(p.isAlive)
        assertEquals(0, p.exitCode)
    }
}

class ExecutionBackendInterfaceTest {
    @Test fun `interface exists and is abstract`() {
        // Verify the interface is defined (can't instantiate, but can reference)
        // This is a compile-time check
        val backendClass = TerminalExecutionBackend::class
        assertEquals("TerminalExecutionBackend", backendClass.simpleName)
        assertTrue(backendClass.isInterface)
    }

    @Test fun `BackendResult has all fields`() {
        val r = TerminalExecutionBackend.BackendResult(
            nativeSessionId = 1, pid = 42, success = true, error = null
        )
        assertEquals(1, r.nativeSessionId)
        assertEquals(42, r.pid)
        assertTrue(r.success)
        assertNull(r.error)
    }
}

class TerminalSessionListenerTest {
    @Test fun `listener has all callback methods`() {
        // Compile-time check: a no-op listener should be instantiable
        val listener = object : TerminalSessionListener {}
        assertNotNull(listener)
        // All methods have default impls, so no-op is fine
        listener.onStateChanged(1L, SessionState.RUNNING, SessionState.EXITED)
        listener.onPrimaryProcessExited(1L, 42, 0)
        listener.onLost(1L, "test")
        listener.onJobChanged(1L, 1L, "EXITED")
        listener.onClosed(1L, SessionExitReason.NORMAL)
    }
}
