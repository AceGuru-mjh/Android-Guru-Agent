package com.apex.agent.platform.terminal.control

import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import com.apex.agent.platform.terminal.io.UnixSignal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * PR #55 Control Plane tests (Spec §29 DoD).
 */
class ControlPlaneTest {

    private fun newController(): TerminalControllerImpl = TerminalControllerImpl(
        runtime = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
        )
    )

    @Test fun `createSession returns valid sessionId`() = runBlocking {
        val ctrl = newController()
        val id = ctrl.createSession().getOrThrow()
        assertTrue(id > 0)
    }

    @Test fun `getSession returns snapshot with state`() = runBlocking {
        val ctrl = newController()
        val id = ctrl.createSession().getOrThrow()
        val snap = ctrl.getSession(id)
        assertNotNull(snap)
        assertEquals(id, snap!!.sessionId)
    }

    @Test fun `listSessions shows all active sessions`() = runBlocking {
        val ctrl = newController()
        ctrl.createSession()
        ctrl.createSession()
        val list = ctrl.listSessions()
        assertTrue("should list >=2 sessions", list.size >= 2)
    }

    @Test fun `closeSession removes session`() = runBlocking {
        val ctrl = newController()
        val id = ctrl.createSession().getOrThrow()
        ctrl.closeSession(id)
        assertNull("session should be gone after close", ctrl.getSession(id))
    }

    @Test fun `execute returns jobId (non-blocking)`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        kotlinx.coroutines.delay(100)
        val jobId = ctrl.execute(sid, "echo test").getOrThrow()
        assertTrue("jobId should be > 0", jobId > 0)
    }

    @Test fun `wait returns JobResult after process exits`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        kotlinx.coroutines.delay(100)
        val jobId = ctrl.execute(sid, "echo waittest").getOrThrow()
        val result = ctrl.wait(sid, jobId, 5000).getOrThrow()
        assertEquals("EXITED", result.state)
    }

    @Test fun `cancel stops job gracefully`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        kotlinx.coroutines.delay(100)
        val jobId = ctrl.execute(sid, "sleep 30").getOrThrow()
        kotlinx.coroutines.delay(200)
        ctrl.cancel(sid, jobId)
        val result = ctrl.wait(sid, jobId, 5000).getOrThrow()
        // After cancel, job should exit (not still RUNNING)
        assertNotEquals("RUNNING", result.state)
    }

    @Test fun `policy gate blocks dangerous command`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        val r = ctrl.execute(sid, "shutdown")
        assertTrue("shutdown should be blocked by policy", r.isFailure)
    }

    @Test fun `policy gate allows safe command`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = ctrl.execute(sid, "echo safe")
        assertTrue("echo should pass policy", r.isSuccess)
    }

    @Test fun `write sends raw bytes to PTY`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = ctrl.write(sid, "echo writetest".toByteArray())
        assertTrue("write should succeed", r.isSuccess)
    }

    @Test fun `sendKey sends special key`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = ctrl.sendKey(sid, com.apex.agent.platform.terminal.io.TerminalKey.ENTER)
        assertTrue(r.isSuccess)
    }

    @Test fun `sendSignal sends Unix signal`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = ctrl.sendSignal(sid, UnixSignal.SIGINT)
        assertTrue(r.isSuccess)
    }

    @Test fun `resize updates terminal dimensions`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        val r = ctrl.resize(sid, 40, 120)
        assertTrue("resize should succeed", r.isSuccess)
        val snap = ctrl.getSession(sid)
        assertNotNull(snap)
    }

    @Test fun `observe SEMANTIC returns session state`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        kotlinx.coroutines.delay(100)
        val obs = ctrl.observe(sid, TerminalController.ObserveMode.SEMANTIC).getOrThrow()
        assertEquals(sid, obs.sessionId)
        assertTrue(obs.cursor >= 0)
    }

    @Test fun `observe SCREEN returns screen text`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        kotlinx.coroutines.delay(100)
        val obs = ctrl.observe(sid, TerminalController.ObserveMode.SCREEN).getOrThrow()
        assertNotNull(obs.screenText)
    }

    @Test fun `observe RAW returns raw output`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        kotlinx.coroutines.delay(100)
        val job = ctrl.execute(sid, "echo rawtest").getOrThrow()
        ctrl.wait(sid, job, 5000)
        val obs = ctrl.observe(sid, TerminalController.ObserveMode.RAW, afterCursor = 0L).getOrThrow()
        assertNotNull(obs.rawOutput)
    }

    @Test fun `getScreenText returns rendered text`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        kotlinx.coroutines.delay(100)
        val text = ctrl.getScreenText(sid).getOrThrow()
        assertNotNull(text)
    }

    @Test fun `multi-session isolation — close A doesn't affect B`() = runBlocking {
        val ctrl = newController()
        val a = ctrl.createSession().getOrThrow()
        val b = ctrl.createSession().getOrThrow()
        ctrl.closeSession(a)
        assertNull("A should be gone", ctrl.getSession(a))
        assertNotNull("B should still exist", ctrl.getSession(b))
    }

    @Test fun `closeAllSessions clears everything`() = runBlocking {
        val ctrl = newController()
        ctrl.createSession()
        ctrl.createSession()
        ctrl.createSession()
        ctrl.closeAllSessions()
        assertEquals("all sessions should be gone", 0, ctrl.listSessions().size)
    }

    @Test fun `observeEvents returns non-null flow`() = runBlocking {
        val ctrl = newController()
        val sid = ctrl.createSession().getOrThrow()
        val flow = ctrl.observeEvents(sid)
        assertNotNull("event flow should be non-null", flow)
    }

    @Test fun `TerminalControlError has all codes`() {
        assertEquals("SessionNotFound", TerminalControlError.SessionNotFound.code)
        assertEquals("JobNotFound", TerminalControlError.JobNotFound.code)
        assertEquals("Timeout", TerminalControlError.Timeout.code)
        assertEquals("PermissionDenied", TerminalControlError.PermissionDenied.code)
        assertEquals("PtyClosed", TerminalControlError.PtyClosed.code)
    }
}
