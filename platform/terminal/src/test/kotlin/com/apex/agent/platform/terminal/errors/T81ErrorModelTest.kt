package com.apex.agent.platform.terminal.errors

import org.junit.Assert.*
import org.junit.Test

/**
 * T81 (D-9 / §42-43) — 统一错误模型回归：
 *  1. TerminalOperationException 携带结构化 code/retryable
 *  2. message 格式向后兼容（工具层既有前缀解析不变）
 *  3. asTerminalError 同时解析新类型与旧字符串异常
 */
class T81ErrorModelTest {

    @Test fun `typed exception carries structured code and retryable`() {
        val e = TerminalOperationException(TerminalError.SessionNotFound)
        assertEquals("SessionNotFound", e.code)
        assertEquals(false, e.retryable)
        assertEquals(TerminalError.SessionNotFound, e.error)
    }

    @Test fun `message format stays backward compatible`() {
        val e = TerminalOperationException(TerminalError.WriteFailed, "EPIPE on session 3")
        assertEquals("TerminalError:WriteFailed — EPIPE on session 3", e.message)
        // 旧解析约定：按前缀 + 代码提取
        assertTrue(e.message!!.startsWith("TerminalError:WriteFailed"))
    }

    @Test fun `typed exceptions parse via asTerminalError`() {
        val e = TerminalOperationException(TerminalError.Timeout)
        assertEquals(TerminalError.Timeout, e.asTerminalError())
    }

    @Test fun `legacy string exceptions still parse (compat path)`() {
        val e = RuntimeException("TerminalError:SessionNotFound")
        assertEquals(TerminalError.SessionNotFound, e.asTerminalError())
        val e2 = RuntimeException("TerminalError:WriteFailed — some detail")
        assertEquals(TerminalError.WriteFailed, e2.asTerminalError())
    }

    @Test fun `unknown messages return null (no fake mapping)`() {
        assertNull(RuntimeException("random failure").asTerminalError())
        assertNull(RuntimeException("").asTerminalError())
    }

    @Test fun `retryable flags differentiate agent decisions`() {
        // 可重试类：Timeout/BufferOverrun/CursorExpired/WriteFailed/ReadFailed/OwnerBusy
        assertTrue(TerminalError.Timeout.recoverable)
        assertTrue(TerminalError.BufferOverrun.recoverable)
        assertTrue(TerminalError.CursorExpired.recoverable)
        // 不可重试类：SessionNotFound/SessionClosed/PtyUnavailable/InvalidInput
        assertFalse(TerminalError.SessionNotFound.recoverable)
        assertFalse(TerminalError.SessionClosed.recoverable)
        assertFalse(TerminalError.InvalidInput.recoverable)
    }

    @Test fun `typed failure surfaces typed exception (runtime integration)`() = kotlinx.coroutines.runBlocking {
        val rt = com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl(
            native = com.apex.agent.platform.terminal.pty.FakeNativePty(),
            policy = com.apex.agent.platform.terminal.policy.TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> com.apex.agent.platform.terminal.screen.RealVirtualTerminal(r, c) }
        )
        val r = rt.observe(9999L)   // 不存在的 session
        assertTrue(r.isFailure)
        val e = r.exceptionOrNull()
        assertNotNull(e)
        // 新路径：类型化异常（code 可直接读取，不解析字符串）
        assertEquals("SessionNotFound", (e as TerminalOperationException).code)
        assertEquals(TerminalError.SessionNotFound, e.asTerminalError())
        // shutdown 路径
        rt.shutdown()
        val r2 = rt.create()
        assertTrue(r2.isFailure)
        assertEquals("UnsupportedOperation", (r2.exceptionOrNull() as TerminalOperationException).code)
    }
}
