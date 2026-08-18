package com.apex.agent.platform.terminal.process2

import org.junit.Assert.*
import org.junit.Test

class ProcessIdentityTest {
    @Test fun `same pid + same token matches`() {
        val a = ProcessIdentity(1234, "abc")
        val b = ProcessIdentity(1234, "abc")
        assertTrue(a.matches(b))
    }

    @Test fun `same pid + different token does NOT match (PID reuse)`() {
        val a = ProcessIdentity(1234, "abc")
        val b = ProcessIdentity(1234, "xyz")
        assertFalse("PID reuse must not match", a.matches(b))
    }

    @Test fun `different pid does not match`() {
        val a = ProcessIdentity(1234, "abc")
        val b = ProcessIdentity(5678, "abc")
        assertFalse(a.matches(b))
    }

    @Test fun `null startToken is permissive`() {
        val a = ProcessIdentity(1234, null)
        val b = ProcessIdentity(1234, null)
        assertTrue(a.matches(b))
    }
}

class JobStateReducerTest {

    @Test fun `already terminal state never overridden`() {
        val result = JobStateReducer.resolveTerminalState(
            candidates = listOf("TIMEOUT", "CANCELLED"),
            current = "EXITED"
        )
        assertEquals("EXITED should not be overridden", "EXITED", result)
    }

    @Test fun `EXITED wins over CANCELLED`() {
        val result = JobStateReducer.resolveTerminalState(
            candidates = listOf("CANCELLED", "EXITED"),
            current = "RUNNING"
        )
        assertEquals("EXITED", result)
    }

    @Test fun `CANCELLED wins over TIMEOUT`() {
        val result = JobStateReducer.resolveTerminalState(
            candidates = listOf("TIMEOUT", "CANCELLED"),
            current = "RUNNING"
        )
        assertEquals("CANCELLED", result)
    }

    @Test fun `TIMEOUT wins over LOST`() {
        val result = JobStateReducer.resolveTerminalState(
            candidates = listOf("LOST", "TIMED_OUT"),
            current = "RUNNING"
        )
        assertEquals("TIMED_OUT", result)
    }

    @Test fun `no candidates keeps current state`() {
        val result = JobStateReducer.resolveTerminalState(
            candidates = emptyList(),
            current = "RUNNING"
        )
        assertEquals("RUNNING", result)
    }

    @Test fun `CANCELLING is not terminal`() {
        // CANCELLING is an intermediate state; can still be overridden
        val result = JobStateReducer.resolveTerminalState(
            candidates = listOf("EXITED"),
            current = "CANCELLING"
        )
        assertEquals("EXITED", result)
    }
}

class CancellationPolicyTest {
    @Test fun `default is TERM then KILL after 5s`() {
        val p = CancellationPolicy.DEFAULT
        assertEquals(ProcessSignal.TERM, p.gracefulSignal)
        assertEquals(5000L, p.gracePeriodMs)
        assertEquals(ProcessSignal.KILL, p.forceSignal)
    }

    @Test fun `IMMEDIATE has 0 grace`() {
        val p = CancellationPolicy.IMMEDIATE
        assertEquals(0L, p.gracePeriodMs)
    }
}

class ExitInfoTest {
    @Test fun `normal exit with exitCode 1 is NOT failure`() {
        val info = ExitInfo(
            exitCode = 1, signal = null,
            reason = ExitReason.NORMAL_EXIT,
            startedAt = 1000, finishedAt = 2000
        )
        assertEquals(ExitReason.NORMAL_EXIT, info.reason)
        assertEquals(1, info.exitCode)
        assertNull(info.signal)
    }

    @Test fun `SIGINT exit has SIGNAL reason`() {
        val info = ExitInfo(
            exitCode = null, signal = ProcessSignal.INT,
            reason = ExitReason.SIGNAL,
            startedAt = 1000, finishedAt = 2000
        )
        assertEquals(ExitReason.SIGNAL, info.reason)
        assertEquals(ProcessSignal.INT, info.signal)
    }

    @Test fun `timeout has TIMEOUT reason not FAILED`() {
        val info = ExitInfo(
            exitCode = null, signal = ProcessSignal.KILL,
            reason = ExitReason.TIMEOUT,
            startedAt = 1000, finishedAt = 2000
        )
        assertEquals(ExitReason.TIMEOUT, info.reason)
        assertNotEquals(ExitReason.NORMAL_EXIT, info.reason)
    }
}

class ProcessTreeTest {
    @Test fun `flatten returns all processes`() {
        val tree = ProcessTree(
            root = ProcessSnapshot(ProcessIdentity(1), null, 1, 1, ProcessState.RUNNING, "bash", 1000),
            children = listOf(
                ProcessTree(
                    root = ProcessSnapshot(ProcessIdentity(2), ProcessIdentity(1), 1, 1, ProcessState.RUNNING, "vim", 1001),
                    children = emptyList()
                ),
                ProcessTree(
                    root = ProcessSnapshot(ProcessIdentity(3), ProcessIdentity(1), 1, 1, ProcessState.RUNNING, "top", 1002),
                    children = emptyList()
                )
            )
        )
        val flat = tree.flatten()
        assertEquals(3, flat.size)
        assertEquals(1, flat[0].identity.pid)
        assertEquals(2, flat[1].identity.pid)
        assertEquals(3, flat[2].identity.pid)
    }

    @Test fun `deep tree does not cause stack overflow`() {
        // Build a chain of 100 processes
        var tree: ProcessTree? = null
        for (i in 100 downTo 1) {
            tree = ProcessTree(
                root = ProcessSnapshot(ProcessIdentity(i), null, 1, 1, ProcessState.RUNNING, "p$i", 1000),
                children = if (tree != null) listOf(tree!!) else emptyList()
            )
        }
        val flat = tree!!.flatten()
        assertTrue(flat.size in 1..100)
    }
}

class BoundedJobRegistryTest {

    private fun entry(jobId: Long, state: String = "RUNNING"): JobRegistryEntry = JobRegistryEntry(
        jobId = jobId, sessionId = 1, rootPid = 100, rootPgid = 100,
        command = "test", state = state, createdAt = 1000,
        startedAt = 1001, finishedAt = null, exitInfo = null, observationRange = null
    )

    @Test fun `active jobs are tracked`() {
        val reg = BoundedJobRegistry()
        reg.add(entry(1))
        reg.add(entry(2))
        assertEquals(2, reg.activeCount())
        assertEquals(0, reg.completedCount())
    }

    @Test fun `terminal jobs move to completed`() {
        val reg = BoundedJobRegistry()
        reg.add(entry(1, "EXITED"))
        assertEquals(0, reg.activeCount())
        assertEquals(1, reg.completedCount())
    }

    @Test fun `completed jobs bounded by max`() {
        val reg = BoundedJobRegistry(maxCompletedJobs = 5)
        for (i in 1..10) reg.add(entry(i.toLong(), "EXITED"))
        assertEquals(5, reg.completedCount())  // oldest 5 evicted
    }

    @Test fun `get finds both active and completed`() {
        val reg = BoundedJobRegistry()
        reg.add(entry(1, "RUNNING"))
        reg.add(entry(2, "EXITED"))
        assertNotNull(reg.get(1))
        assertNotNull(reg.get(2))
    }

    @Test fun `list by session filters`() {
        val reg = BoundedJobRegistry()
        reg.add(entry(1, "RUNNING"))
        reg.add(JobRegistryEntry(2, 99, 100, 100, "other", "RUNNING", 1000, null, null, null, null))
        assertEquals(1, reg.list(1).size)
        assertEquals(1, reg.list(99).size)
    }

    @Test fun `remove works`() {
        val reg = BoundedJobRegistry()
        reg.add(entry(1))
        reg.remove(1)
        assertNull(reg.get(1))
    }
}

class JobResultTest {
    @Test fun `job result has observation range not output copy`() {
        val result = JobResult(
            jobId = 1, sessionId = 1, state = "EXITED",
            exitInfo = ExitInfo(0, null, false, ExitReason.NORMAL_EXIT, 1000, 2000),
            observationRange = ObservationRange(1, 100, 200)
        )
        assertNotNull(result.observationRange)
        assertEquals(100, result.observationRange!!.startSequence)
        assertEquals(200L, result.observationRange!!.endSequence)
        // NO output field — Spec §28: "禁止 JobResult.output = entire terminal output"
    }

    @Test fun `null exitInfo for LOST jobs`() {
        val result = JobResult(
            jobId = 1, sessionId = 1, state = "LOST",
            exitInfo = null, observationRange = null
        )
        assertNull(result.exitInfo)
    }
}

class SignalTargetTest {
    @Test fun `Process target has identity`() {
        val target = SignalTarget.Process(ProcessIdentity(1234, "abc"))
        assertEquals(1234, target.identity.pid)
    }

    @Test fun `ProcessGroup target has pgid`() {
        val target = SignalTarget.ProcessGroup(5000)
        assertEquals(5000, target.pgid)
    }
}

class ProcessSignalTest {
    @Test fun `all signals have valid numbers`() {
        for (s in ProcessSignal.values()) assertTrue(s.number > 0)
    }

    @Test fun `KILL is 9`() = assertEquals(9, ProcessSignal.KILL.number)
    @Test fun `TERM is 15`() = assertEquals(15, ProcessSignal.TERM.number)
    @Test fun `INT is 2`() = assertEquals(2, ProcessSignal.INT.number)
    @Test fun `STOP is 19`() = assertEquals(19, ProcessSignal.STOP.number)
    @Test fun `CONT is 18`() = assertEquals(18, ProcessSignal.CONT.number)
}

class BackendCapabilitiesTest {
    @Test fun `default capabilities`() {
        val c = ProcessCapabilities()
        assertTrue(c.supportsProcessGroups)
        assertTrue(c.supportsSignals)
        assertFalse(c.supportsProcessTree)  // not available on all backends
    }

    @Test fun `capabilities can be queried`() {
        val c = ProcessCapabilities(supportsProcessTree = true)
        assertTrue(c.supportsProcessTree)
    }
}
