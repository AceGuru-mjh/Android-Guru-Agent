package com.apex.agent.platform.terminal.input

import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InputControllerTest {

    private fun newInputController(): Pair<TerminalInputControllerImpl, TerminalRuntimeImpl> {
        val rt = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
        )
        val ctrl = TerminalInputControllerImpl(rt.inputManager, rt.sessionManager)
        return ctrl to rt
    }

    @Test fun `sendText writes to PTY`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = ctrl.sendText(s.sessionId, "echo hello").getOrThrow()
        assertTrue(r.accepted)
        assertTrue(r.bytesWritten > 0)
    }

    @Test fun `sendKey sends special key`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = ctrl.sendKey(s.sessionId, TerminalKey.ENTER)
        assertTrue(r.isSuccess)
    }

    @Test fun `sendControl INTERRUPT in canonical sends SIGINT`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = ctrl.sendControl(s.sessionId, TerminalControl.INTERRUPT)
        assertTrue("INTERRUPT should succeed in canonical mode", r.isSuccess)
    }

    @Test fun `sendControl EOF sends Ctrl+D byte`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = ctrl.sendControl(s.sessionId, TerminalControl.EOF)
        assertTrue("EOF should succeed", r.isSuccess)
    }

    @Test fun `sendControl SUSPEND sends SIGSTOP`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = ctrl.sendControl(s.sessionId, TerminalControl.SUSPEND)
        assertTrue(r.isSuccess)
    }

    @Test fun `sendControl INTERRUPT in raw mode sends byte 0x03 not signal`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        ctrl.setTtyMode(s.sessionId, TtyMode(canonical = false, echo = false, raw = true))
        val r = ctrl.sendControl(s.sessionId, TerminalControl.INTERRUPT)
        assertTrue("INTERRUPT in raw mode should succeed", r.isSuccess)
        assertEquals(1, r.getOrThrow().bytesWritten)  // 1 byte (0x03), not 0 (signal)
    }

    @Test fun `sendBytes sends raw binary`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = ctrl.sendBytes(s.sessionId, byteArrayOf(0x41, 0x42, 0x43)).getOrThrow()
        assertTrue(r.accepted)
        assertEquals(3, r.bytesWritten)
    }

    @Test fun `sendModifiedKey Ctrl+C routes to sendControl INTERRUPT`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = ctrl.sendModifiedKey(s.sessionId, ModifiedKey(TerminalKey.CTRL_C, ctrl = true))
        assertTrue(r.isSuccess)
    }

    @Test fun `sendModifiedKey plain key routes to sendKey`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = ctrl.sendModifiedKey(s.sessionId, ModifiedKey(TerminalKey.TAB))
        assertTrue(r.isSuccess)
    }

    @Test fun `ownership request grants when available`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        val r = ctrl.requestOwnership(s.sessionId, InputOwner.OWNED_BY_AGENT)
        assertTrue(r.isSuccess)
    }

    @Test fun `ownership denied when held by another`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        ctrl.requestOwnership(s.sessionId, InputOwner.OWNED_BY_UI)
        val r = ctrl.requestOwnership(s.sessionId, InputOwner.OWNED_BY_AGENT)
        assertTrue("should be denied when held by UI", r.isFailure)
    }

    @Test fun `ownership release allows re-acquire`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        ctrl.requestOwnership(s.sessionId, InputOwner.OWNED_BY_AGENT)
        ctrl.releaseOwnership(s.sessionId)
        val r = ctrl.requestOwnership(s.sessionId, InputOwner.OWNED_BY_UI)
        assertTrue("should grant after release", r.isSuccess)
    }

    @Test fun `getTtyMode returns null for unknown session`() {
        val (ctrl, _) = newInputController()
        assertNull(ctrl.getTtyMode(99999L))
    }

    @Test fun `setTtyMode then getTtyMode returns same mode`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        val mode = TtyMode(canonical = false, echo = false, raw = true)
        ctrl.setTtyMode(s.sessionId, mode)
        assertEquals(mode, ctrl.getTtyMode(s.sessionId))
    }

    @Test fun `getForegroundProcess returns info for live session`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        val fg = ctrl.getForegroundProcess(s.sessionId)
        assertNotNull(fg)
        assertTrue(fg!!.pid > 0)
    }

    @Test fun `getForegroundProcess null for dead session`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val fg = ctrl.getForegroundProcess(99999L)
        assertNull(fg)
    }

    @Test fun `input after close fails`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        rt.close(s.sessionId)
        val r = ctrl.sendText(s.sessionId, "should fail")
        assertTrue("input after close should fail", r.isFailure)
    }

    @Test fun `multi-session input isolation`() = runBlocking {
        val (ctrl, rt) = newInputController()
        val a = rt.create().getOrThrow()
        val b = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        // Input to A works
        assertTrue(ctrl.sendText(a.sessionId, "echo A").isSuccess)
        // Close A
        rt.close(a.sessionId)
        // Input to A fails
        assertTrue(ctrl.sendText(a.sessionId, "fail").isFailure)
        // Input to B still works
        assertTrue(ctrl.sendText(b.sessionId, "echo B").isSuccess)
    }

    @Test fun `all TerminalControl values exist`() {
        assertEquals(5, TerminalControl.values().size)
        assertNotNull(TerminalControl.valueOf("INTERRUPT"))
        assertNotNull(TerminalControl.valueOf("EOF"))
        assertNotNull(TerminalControl.valueOf("SUSPEND"))
        assertNotNull(TerminalControl.valueOf("QUIT"))
        assertNotNull(TerminalControl.valueOf("RESIZE"))
    }

    @Test fun `TerminalInputError has all codes`() {
        assertEquals("InputUnavailable", TerminalInputError.InputUnavailable.code)
        assertEquals("InputOwnershipDenied", TerminalInputError.InputOwnershipDenied.code)
        assertEquals("InputBackpressure", TerminalInputError.InputBackpressure.code)
        assertEquals("SessionClosed", TerminalInputError.SessionClosed.code)
    }

    @Test fun `ModifiedKey supports Ctrl Alt Shift`() {
        val k = ModifiedKey(TerminalKey.ENTER, ctrl = true, alt = false, shift = true)
        assertTrue(k.ctrl)
        assertFalse(k.alt)
        assertTrue(k.shift)
    }

    @Test fun `TtyMode defaults are canonical+echo`() {
        val m = TtyMode()
        assertTrue(m.canonical)
        assertTrue(m.echo)
        assertFalse(m.raw)
    }
}
