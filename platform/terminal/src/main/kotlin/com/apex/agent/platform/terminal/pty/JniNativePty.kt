package com.apex.agent.platform.terminal.pty

import com.apex.agent.platform.terminal.NativePtyJniBridge

/**
 * JNI 状态常量 —— 与 jni_bridge.cpp 的 PTY_READ_* 一一对应，勿改数值。
 * （单一事实来源：platform/terminal/src/main/cpp/pty_engine.h 的 PtyReadStatus）
 */
internal object PtyJniReadStatus {
    /** 读到数据（statusOut[2] 为字节数）。 */
    const val DATA = 0

    /** EAGAIN/EWOULDBLOCK —— 暂时无数据（idle）。PTY 与进程均正常。 */
    const val NO_DATA = 1

    /** 输出流结束（read()==0 或 EIO —— slave 已全部关闭）。 */
    const val EOF = 2

    /** 真实 read 错误（statusOut[1] 为 errno）。 */
    const val ERROR = 3

    /** sessionId 不存在（已 close 或从未创建）。 */
    const val SESSION_NOT_FOUND = 4
}

/**
 * JNI-backed NativePty adapter. Implements the [NativePty] interface by delegating to the
 * JNI bridge ([NativePtyJniBridge], real impl = `class NativePty` backed by
 * `libapex_terminal.so` via `jni_bridge.cpp`).
 *
 * Spec ref: ATR 2.0 Final Spec §2.2 / §2.3 / §44.1 (NativePty.kt EXTEND, not rewrite)
 *
 * P70 hardening — the read/write paths now use the BINARY-SAFE byte channels:
 *   - nativeWrite → NativePtyJniBridge.nativeWriteBytes   (bytes pass through verbatim,
 *     NO newline appended — LINE's "\n" is appended exactly once by TerminalInput.sendLine;
 *     fixes the LINE double-newline bug and the raw-write newline corruption)
 *   - nativeRead  → NativePtyJniBridge.nativeReadBytes    (byte[] + explicit status;
 *     NUL bytes and arbitrary binary are preserved, idle/EOF/error are distinguished —
 *     an idle read NEVER reports -1, which used to kill the PtyOutputPump)
 *
 * Legacy String JNI methods (nativeRead/nativeWrite/nativeWriteRaw) are no longer called
 * from production code (kept only for ABI compatibility, marked @Deprecated).
 */
class JniNativePty(
    /** The JNI bridge. Injected for testability; default creates the real JNI instance. */
    private val jni: NativePtyJniBridge = com.apex.agent.platform.terminal.NativePty()
) : NativePty {

    override fun nativeCreateSession(shell: String, cwd: String, rows: Int, cols: Int, env: Array<String>): Int {
        // Split env array ("KEY=VALUE") into keys + vals for the existing JNI signature.
        val keys: Array<String>? = if (env.isEmpty()) null else env.map { it.substringBefore('=') }.toTypedArray()
        val vals: Array<String>? = if (env.isEmpty()) null else env.map { it.substringAfter('=', "") }.toTypedArray()
        return jni.nativeCreateSession(shell, cwd, keys, vals, rows, cols)
    }

    override fun nativeWrite(sessionId: Int, bytes: ByteArray, offset: Int, len: Int): Int {
        // P70-2/P70-3: raw byte write. NO newline is appended here — LINE semantics
        // ("text + \n") are owned by TerminalInput.sendLine, which appends it exactly once.
        if (len == 0) return 0
        if (offset < 0 || len < 0 || offset + len > bytes.size) return -1
        return if (jni.nativeWriteBytes(sessionId, bytes, offset, len)) len else -1
    }

    override fun nativeWriteRaw(sessionId: Int, text: String): Int {
        // P70-2: route through the byte channel so \u0000 and multi-byte UTF-8 are
        // written exactly (the old jstring path mangled NUL as modified-UTF-8 C0 80).
        val bytes = text.toByteArray(Charsets.UTF_8)
        return nativeWrite(sessionId, bytes, 0, bytes.size)
    }

    override fun nativeRead(sessionId: Int, buffer: ByteArray, maxBytes: Int): Int {
        // P70-1: explicit status mapping — never guess.
        //   NO_DATA (idle)              → 0    (the old impl fell back to hasData()
        //                                        and reported -1 during idle windows,
        //                                        killing the PtyOutputPump)
        //   EOF / ERROR / NOT_FOUND     → -1   (stream ended or real error)
        //   DATA                        → byte count (binary-safe, NULs preserved)
        if (buffer.isEmpty()) return 0
        val cap = maxBytes.coerceIn(1, buffer.size)
        val status = IntArray(3)
        val data: ByteArray = try {
            jni.nativeReadBytes(sessionId, cap, status)
        } catch (e: Throwable) {
            return -1
        }
        return when (status[0]) {
            PtyJniReadStatus.DATA -> {
                val n = status[2]
                if (n <= 0) return 0
                // n ≤ cap ≤ buffer.size by contract; the copy guard is defensive only.
                val copyLen = minOf(n, buffer.size)
                System.arraycopy(data, 0, buffer, 0, copyLen)
                n
            }
            PtyJniReadStatus.NO_DATA -> 0
            PtyJniReadStatus.EOF,
            PtyJniReadStatus.ERROR,
            PtyJniReadStatus.SESSION_NOT_FOUND -> -1
            else -> -1
        }
    }

    override fun nativeHasData(sessionId: Int): Boolean = jni.nativeHasData(sessionId)

    override fun nativeWaitForData(sessionId: Int, timeoutMs: Long): Boolean {
        return jni.nativeWaitForData(sessionId, timeoutMs.toInt().coerceAtLeast(0))
    }

    override fun nativeSendSignal(sessionId: Int, signal: Int): Boolean {
        return jni.nativeSendSignal(sessionId, signal)
    }

    override fun nativeResize(sessionId: Int, rows: Int, cols: Int): Boolean {
        return try { jni.nativeResize(sessionId, rows, cols); true } catch (e: Exception) { false }
    }

    override fun nativeIsAlive(sessionId: Int): Boolean = jni.nativeIsAlive(sessionId)

    override fun nativeGetPid(sessionId: Int): Int = jni.nativeGetPid(sessionId)

    override fun nativeGetExitCode(sessionId: Int): Int = jni.nativeGetExitCode(sessionId)

    override fun nativeWaitExit(sessionId: Int, timeoutMs: Long): Int {
        // Poll nativeIsAlive + nativeGetExitCode (Spec §44.1 EXTEND — reliable exit).
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!jni.nativeIsAlive(sessionId)) return jni.nativeGetExitCode(sessionId)
            try { Thread.sleep(10) } catch (e: InterruptedException) { break }
        }
        return -1
    }

    override fun nativeCloseSession(sessionId: Int): Boolean {
        return try { jni.nativeCloseSession(sessionId); true } catch (e: Exception) { false }
    }

    override fun nativeCloseAll() {
        jni.nativeCloseAll()
    }

    override fun nativeActiveCount(): Int = jni.nativeActiveCount()

    override fun nativeListSessionIds(): IntArray = jni.nativeListSessionIds()
}
