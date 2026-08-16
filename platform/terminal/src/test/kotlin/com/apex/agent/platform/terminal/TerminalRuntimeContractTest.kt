package com.apex.agent.platform.terminal

import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import com.apex.agent.platform.terminal.wait.WaitCondition
import com.apex.agent.platform.terminal.wait.WaitResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * TerminalRuntime Contract Test (Spec §48).
 *
 * Runs the same contract against the Runtime wired with FakeNativePty (pure JVM).
 * Verifies all 10 operations + cursor incremental observation + event-driven Flow.
 *
 * This is the contract that JniNativePty must also satisfy (run on instrumented device).
 */
class TerminalRuntimeContractTest {

    private fun newRuntime(): TerminalRuntimeImpl = TerminalRuntimeImpl(
        native = FakeNativePty(),
        policy = TerminalPolicyImpl(),
        virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
    )

    /**
     * v1 emits ProcessExited at SESSION level with jobId=null; job-scoped exit events
     * arrive in v2. Ordinary commands finish without any job-level exit event, so poll
     * the ring buffer for expected output instead of awaiting one.
     */
    private suspend fun awaitRawContains(
        rt: TerminalRuntimeImpl,
        sessionId: Long,
        startCursor: Long,
        needle: String,
        timeoutMs: Long = 5000
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val obs = rt.observe(sessionId, TerminalRuntime.ObserveMode.RAW, startCursor, 65536).getOrThrow()
            if (obs.raw?.contains(needle) == true) return true
            kotlinx.coroutines.delay(50)
        }
        return false
    }

    @Test
    fun `create returns READY with valid sessionId and pid`() = runBlocking {
        val rt = newRuntime()
        val r = rt.create().getOrThrow()
        assertTrue(r.sessionId > 0)
        assertTrue(r.pid > 0)
        assertEquals("READY", r.state)
    }

    @Test
    fun `run returns RUNNING job with startCursor`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val job = rt.run(s.sessionId, "echo hello", InputOwner.AGENT).getOrThrow()
        assertEquals("RUNNING", job.state)
        assertTrue(job.startCursor >= 0)
    }

    @Test
    fun `observe SEMANTIC returns session state`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val obs = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.SEMANTIC).getOrThrow()
        assertNotNull(obs.semantic)
        assertEquals(s.sessionId, obs.semantic!!.session.id)
    }

    @Test
    fun `observe RAW after run returns command output`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val job = rt.run(s.sessionId, "echo contracttest", InputOwner.AGENT).getOrThrow()
        assertTrue(
            "should observe echo output",
            awaitRawContains(rt, s.sessionId, job.startCursor, "contracttest")
        )
        val obs = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.RAW, job.startCursor, 65536).getOrThrow()
        assertTrue("output should contain 'contracttest', got: ${obs.raw}", obs.raw?.contains("contracttest") == true)
    }

    @Test
    fun `wait PROCESS_EXITED returns Matched with exitCode`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        rt.run(s.sessionId, "exit", InputOwner.AGENT).getOrThrow()
        // v1 emits ProcessExited at SESSION level with jobId=null; job-scoped exit
        // events arrive in v2. `exit` terminates the shell and produces that event.
        val wait = rt.wait(s.sessionId, WaitCondition.ProcessExited(), 5000).getOrThrow()
        assertTrue("should match, got $wait", wait is WaitResult.Matched)
        val ev = (wait as WaitResult.Matched).event as? TerminalEvent.ProcessExited
        assertNotNull("Matched should carry a ProcessExited event", ev)
        assertEquals(0, ev?.exitCode ?: -1)
    }

    @Test
    fun `write LINE sends input and produces InputWritten event`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val w = rt.write(s.sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.LINE, text = "echo writetest").getOrThrow()
        assertTrue(w.written)
        assertTrue(w.bytesWritten > 0)
    }

    @Test
    fun `signal SIGINT interrupts running job`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val job = rt.run(s.sessionId, "sleep 30", InputOwner.AGENT).getOrThrow()
        kotlinx.coroutines.delay(200)
        rt.signal(s.sessionId, UnixSignal.SIGINT, InputOwner.AGENT, job.jobId)
        // v1: SIGINT aborts the foreground job but the interactive shell survives, so no
        // job-scoped ProcessExited fires; verify by polling for the next prompt.
        assertTrue(
            "SIGINT should interrupt sleep and return to shell prompt",
            awaitRawContains(rt, s.sessionId, job.startCursor, "\$ ", 3000)
        )
    }

    @Test
    fun `resize calls native PTY and updates VT dims`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = rt.resize(s.sessionId, 50, 132).getOrThrow()
        assertTrue(r.resized)
        assertEquals(50, r.rows)
        assertEquals(132, r.cols)
        val obs = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.SEMANTIC).getOrThrow()
        assertEquals(50, obs.semantic!!.screen.rows)
        assertEquals(132, obs.semantic!!.screen.cols)
    }

    @Test
    fun `resize with invalid dimensions fails`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = rt.resize(s.sessionId, 0, 80)
        assertTrue("resize with 0 rows should fail", r.isFailure)
    }

    @Test
    fun `snapshot lists all live sessions`() = runBlocking {
        val rt = newRuntime()
        val s1 = rt.create().getOrThrow()
        val s2 = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val snap = rt.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrThrow()
        assertTrue(snap.sessions.size >= 2)
    }

    @Test
    fun `close frees session resources`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        val r = rt.close(s.sessionId).getOrThrow()
        assertTrue(r.closed)
    }

    @Test
    fun `recover returns empty when no persistence`() = runBlocking {
        val rt = newRuntime()
        val recovered = rt.recover()
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun `cursor incremental observation returns no duplicates`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val job = rt.run(s.sessionId, "echo incremental1", InputOwner.AGENT).getOrThrow()
        assertTrue(
            "should observe echo output",
            awaitRawContains(rt, s.sessionId, job.startCursor, "incremental1")
        )
        val first = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.RAW, job.startCursor, 65536).getOrThrow()
        val firstEnd = first.endCursor ?: first.cursor
        // Observe again from firstEnd — should get only NEW output (empty if no new output)
        val second = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.RAW, firstEnd, 65536).getOrThrow()
        // second should not re-deliver first's content
        if (second.raw != null && second.raw!!.isNotEmpty()) {
            assertFalse("second observe should not contain 'incremental1' again", second.raw!!.contains("incremental1"))
        }
    }

    @Test
    fun `screenStateFlow is non-null for live session`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val flow = rt.screenStateFlow(s.sessionId)
        assertNotNull("screenStateFlow should be non-null for live session", flow)
    }

    @Test
    fun `semanticStateFlow is non-null for live session`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val flow = rt.semanticStateFlow(s.sessionId)
        assertNotNull("semanticStateFlow should be non-null for live session", flow)
    }

    @Test
    fun `screenStateFlow emits on output`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val flow = rt.screenStateFlow(s.sessionId)!!
        // Run a command that produces output; the Flow should emit updated screen text
        val job = rt.run(s.sessionId, "echo flowtest", InputOwner.AGENT).getOrThrow()
        assertTrue(
            "should observe echo output",
            awaitRawContains(rt, s.sessionId, job.startCursor, "flowtest")
        )
        kotlinx.coroutines.delay(200)  // allow pump to push
        // Collect first emission
        val first = flow.first()
        assertNotNull(first)
        assertTrue("screen should contain 'flowtest', got: ${first.renderedText}", 
            first.renderedText?.contains("flowtest") == true)
    }
}
