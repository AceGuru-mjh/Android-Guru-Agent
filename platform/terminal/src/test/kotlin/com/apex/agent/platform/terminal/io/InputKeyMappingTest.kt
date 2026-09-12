package com.apex.agent.platform.terminal.io

import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBusImpl
import com.apex.agent.platform.terminal.events.TerminalEventLogImpl
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.pty.NativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T82 — 输入完整性回归（Termux 基线 §1.15/§1.16/§1.14）：
 * F1–F12 全量映射、DECCKM 感知方向键（SS3）、括号粘贴 2004 模式包裹。
 *
 * 路径：经 InputManagerImpl（策略/事件/单写者全链路）→ 捕获型 NativePty 记录
 * 真实写入字节 —— 断言的是生产写入路径的字节语义，不是私有函数。
 */
class InputKeyMappingTest {

    /** 透明捕获 wrapper —— 记录生产路径写入的原始字节。 */
    private class CapturingPty(val delegate: NativePty) : NativePty by delegate {
        val writes = mutableListOf<ByteArray>()
        override fun nativeWrite(sessionId: Int, bytes: ByteArray, offset: Int, len: Int): Int {
            writes.add(bytes.copyOfRange(offset, offset + len))
            return delegate.nativeWrite(sessionId, bytes, offset, len)
        }
    }

    private val capturing = CapturingPty(FakeNativePty())
    private val eventLog = TerminalEventLogImpl()
    private val input = InputManagerImpl(
        TerminalPolicyImpl(), capturing, eventLog,
        TerminalEventBusImpl(eventLog, CoroutineScope(SupervisorJob()))
    )

    init {
        capturing.delegate.nativeCreateSession("/system/bin/sh", "/sdcard", 24, 80, emptyArray())
        input.nativeIdResolver = { 1 }
    }

    private fun keyBytes(key: TerminalKey, appCursor: Boolean = false): ByteArray = runBlocking {
        input.vtModeProvider = { VtInputModes(applicationCursorKeys = appCursor, bracketedPaste = false) }
        capturing.writes.clear()
        input.sendKey(1L, InputOwner.AGENT, key).getOrThrow()
        capturing.writes.single()
    }

    private fun pasteBytes(text: String, bracketed: Boolean): ByteArray = runBlocking {
        input.vtModeProvider = { VtInputModes(applicationCursorKeys = false, bracketedPaste = bracketed) }
        capturing.writes.clear()
        input.sendPaste(1L, InputOwner.AGENT, text).getOrThrow()
        capturing.writes.single()
    }

    // ─── F 键（基线 §1.16：此前 no-op + warn）───

    @Test fun `F1-F4 map to SS3 sequences`() {
        assertArrayEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 'P'.code.toByte()), keyBytes(TerminalKey.F1))
        assertArrayEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 'Q'.code.toByte()), keyBytes(TerminalKey.F2))
        assertArrayEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 'R'.code.toByte()), keyBytes(TerminalKey.F3))
        assertArrayEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 'S'.code.toByte()), keyBytes(TerminalKey.F4))
    }

    @Test fun `F5-F12 map to CSI tilde sequences`() {
        for ((key, n) in listOf(
            TerminalKey.F5 to 15, TerminalKey.F6 to 17, TerminalKey.F7 to 18, TerminalKey.F8 to 19,
            TerminalKey.F9 to 20, TerminalKey.F10 to 21, TerminalKey.F11 to 23, TerminalKey.F12 to 24
        )) {
            assertArrayEquals("\u001b[$n~".toByteArray(), keyBytes(key))
        }
    }

    // ─── DECCKM（基线 §1.15：vim/less 应用光标模式只认 SS3）───

    @Test fun `arrows send CSI in normal mode and SS3 in application-cursor mode`() {
        assertArrayEquals(byteArrayOf(0x1B, 0x5B, 0x41), keyBytes(TerminalKey.ARROW_UP, appCursor = false))
        assertArrayEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 'A'.code.toByte()), keyBytes(TerminalKey.ARROW_UP, appCursor = true))
        assertArrayEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 'B'.code.toByte()), keyBytes(TerminalKey.ARROW_DOWN, appCursor = true))
        assertArrayEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 'C'.code.toByte()), keyBytes(TerminalKey.ARROW_RIGHT, appCursor = true))
        assertArrayEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 'D'.code.toByte()), keyBytes(TerminalKey.ARROW_LEFT, appCursor = true))
        assertArrayEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 'H'.code.toByte()), keyBytes(TerminalKey.HOME, appCursor = true))
        assertArrayEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 'F'.code.toByte()), keyBytes(TerminalKey.END, appCursor = true))
    }

    // ─── 括号粘贴（基线 §1.14）───

    @Test fun `paste wraps in 200 tilde markers when bracketed mode on`() {
        val on = pasteBytes("line1\nline2", bracketed = true)
        val expected = InputManagerImpl.PASTE_PREFIX + "line1\nline2".toByteArray() + InputManagerImpl.PASTE_SUFFIX
        assertArrayEquals(expected, on)
    }

    @Test fun `paste falls back to raw bytes without newline when bracketed mode off`() {
        assertArrayEquals("line1".toByteArray(), pasteBytes("line1", bracketed = false))
    }

    @Test fun `paste emits InputWritten with PASTE kind`() = runBlocking {
        input.vtModeProvider = { VtInputModes(bracketedPaste = true) }
        input.sendPaste(1L, InputOwner.AGENT, "y").getOrThrow()
        val last = eventLog.tail(1L, 8).lastOrNull { it is TerminalEvent.InputWritten }
        assertEquals(InputKind.PASTE, (last as TerminalEvent.InputWritten).kind)
    }

    @Test fun `foreground signal with no foreground job returns zero bytes`() = runBlocking {
        // fake 无 runningJob → nativeSignalForegroundGroup false → bytesWritten==0（诚实拒绝）
        val r = input.sendForegroundSignal(1L, InputOwner.AGENT, UnixSignal.SIGINT, null).getOrThrow()
        assertEquals(0, r.bytesWritten)
    }
}
