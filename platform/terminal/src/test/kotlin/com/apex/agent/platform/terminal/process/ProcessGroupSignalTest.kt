package com.apex.agent.platform.terminal.process

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import com.apex.agent.platform.terminal.wait.WaitCondition
import com.apex.agent.platform.terminal.wait.WaitResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Process-Group signal delivery tests (PR #51 §1 — review item #2).
 *
 * Proves the RUNTIME CONTRACT: `terminal.cancel()` / `terminal.signal()` terminate the WHOLE
 * process group — shell + child + grandchild — not just the shell PID.
 *
 * [FakeNativePty] models the process-group semantics that the REAL native layer implements via
 * `kill(-PGID)` (see `PtySession::killProcessGroup`): a session-based send terminates the shell
 * and every member of its group. `simulateGroupChildren` stands in for the real process tree of
 * e.g. `sh -c 'sleep 60 & wait'` (child `sh` + grandchild `sleep` in the session's group).
 *
 * Real on-device verification (requires a device/emulator, not runnable in JVM unit tests):
 *   1. open terminal → type `sh -c 'sleep 60 & wait'`
 *   2. call terminal.cancel() for the running job
 *   3. `ps -ef` must show neither `sh -c sleep...` nor `sleep 60`
 * (PGID == shell pid because forkpty() makes the PTY child a session + process-group leader.)
 */
class ProcessGroupSignalTest {

    private fun newRuntime(): Pair<TerminalRuntime, FakeNativePty> {
        val native = FakeNativePty()
        val rt: TerminalRuntime = TerminalRuntimeImpl(
            native = native,
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
        )
        return rt to native
    }

    @Test
    fun `cancel terminates shell child and grandchild`() = runBlocking {
        val (rt, native) = newRuntime()
        val s = rt.create().getOrThrow()
        val nativeId = native.nativeListSessionIds().single()

        // Simulate `sh -c 'sleep 60 & wait'`: the job's shell (child) and `sleep` (grandchild)
        // belong to the session's process group.
        val childPid = 41001
        val grandchildPid = 41002
        native.simulateGroupChildren(nativeId, listOf(childPid, grandchildPid))

        val job = rt.run(s.sessionId, "sleep 30", InputOwner.AGENT).getOrThrow()

        // Review scenario: terminal.cancel() must kill the whole group, not just the shell PID.
        rt.cancel(s.sessionId, job.jobId).getOrThrow()
        // cancel() sends SIGTERM on a background coroutine — synchronize on the session's shell exit.
        // (v1 emits ProcessExited at session level with jobId=null; job-scoped exit events land in v2.)
        val wait = rt.wait(s.sessionId, WaitCondition.ProcessExited(), 5000).getOrThrow()
        assertTrue("shell should exit after cancel, got $wait", wait is WaitResult.Matched)

        assertFalse("shell must be terminated", native.isSimulatedAlive(nativeId, s.pid))
        assertFalse("child must be terminated", native.isSimulatedAlive(nativeId, childPid))
        assertFalse("grandchild must be terminated", native.isSimulatedAlive(nativeId, grandchildPid))
    }

    @Test
    fun `signal SIGTERM to session reaches whole group not just shell`() = runBlocking {
        val (rt, native) = newRuntime()
        val s = rt.create().getOrThrow()
        val nativeId = native.nativeListSessionIds().single()

        val childPid = 41011
        val grandchildPid = 41012
        native.simulateGroupChildren(nativeId, listOf(childPid, grandchildPid))

        val job = rt.run(s.sessionId, "sleep 30", InputOwner.AGENT).getOrThrow()
        rt.signal(s.sessionId, UnixSignal.SIGTERM, InputOwner.AGENT, job.jobId).getOrThrow()

        assertFalse("shell must be terminated by group signal", native.isSimulatedAlive(nativeId, s.pid))
        assertFalse("child must be terminated by group signal", native.isSimulatedAlive(nativeId, childPid))
        assertFalse("grandchild must be terminated by group signal", native.isSimulatedAlive(nativeId, grandchildPid))
    }

    @Test
    fun `signal SIGINT interrupts job but keeps interactive shell alive`() = runBlocking {
        val (rt, native) = newRuntime()
        val s = rt.create().getOrThrow()
        val nativeId = native.nativeListSessionIds().single()

        val childPid = 41021
        native.simulateGroupChildren(nativeId, listOf(childPid))

        val job = rt.run(s.sessionId, "sleep 30", InputOwner.AGENT).getOrThrow()
        rt.signal(s.sessionId, UnixSignal.SIGINT, InputOwner.AGENT, job.jobId).getOrThrow()

        // SIGINT is delivered to the foreground job group; the interactive shell survives
        // (matches real PTY: Ctrl+C interrupts the job, shell keeps running).
        assertTrue("interactive shell must survive SIGINT", native.isSimulatedAlive(nativeId, s.pid))
        assertFalse("child must be terminated", native.isSimulatedAlive(nativeId, childPid))
    }
}
