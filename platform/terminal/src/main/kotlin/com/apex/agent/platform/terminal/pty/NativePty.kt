package com.apex.agent.platform.terminal.pty

/**
 * Abstraction over the JNI-backed NativePty. The real Android implementation lives in
 * `platform/terminal/.../NativePty.kt` and delegates to `libapex_terminal.so` (forkpty etc.).
 *
 * Spec ref: ATR 2.0 Final Spec §2.2 / §2.3 / §44.1
 *
 * Why an interface? So the Runtime depends on the abstraction, not the concrete JNI class.
 * This makes the entire Runtime testable in pure JVM (no Android, no NDK) via [FakeNativePty].
 * The real repo's Hilt module binds `NativePty` → `JniNativePty`; tests bind → `FakeNativePty`.
 *
 * Method signatures mirror the existing 14 `external fun`s in NativePty.kt so the JNI
 * implementation is a trivial adapter. Phase 1 adds [nativeWaitExit] for reliable exit detection
 * (Spec §44.1 EXTEND: "nativeWaitExit(pid, timeoutMs) — reliable exit").
 */
interface NativePty {

    /** Create a PTY session running [shell] in [cwd]. Returns the session id (≥1) or -1 on failure. */
    fun nativeCreateSession(shell: String, cwd: String, rows: Int, cols: Int, env: Array<String>): Int

    /** Write raw bytes to the session's master fd. Returns bytes written, or -1 on error. */
    fun nativeWrite(sessionId: Int, bytes: ByteArray, offset: Int, len: Int): Int

    /** Convenience: write a UTF-8 string (no newline appended). Returns bytes written. */
    fun nativeWriteRaw(sessionId: Int, text: String): Int

    /**
     * Read up to [maxBytes] from the session's master fd. NON-BLOCKING.
     *
     * P70-1 状态语义（实现必须严格遵守，禁止用 hasData() 等二次猜测）：
     *   - `> 0` — 读到的字节数（二进制安全：NUL 与任意字节原样保留，P70-2）。
     *   - `0`   — 暂时无数据（EAGAIN/EWOULDBLOCK —— idle）。PTY 与进程均正常，
     *             绝不能报告 -1（旧实现正是这样导致 PtyOutputPump 提前自杀）。
     *   - `-1`  — 输出流结束（EOF/EIO —— slave 已关闭）或真实读错误或会话不存在。
     *             EOF ≠ 进程退出：进程存活状态只由 [nativeIsAlive]/[nativeWaitExit] 决定。
     *
     * IMPORTANT: this is the ONLY reader entry point. PtyOutputPump is the sole caller (Spec §14).
     */
    fun nativeRead(sessionId: Int, buffer: ByteArray, maxBytes: Int): Int

    /** Non-blocking poll: is there data available to read right now? */
    fun nativeHasData(sessionId: Int): Boolean

    /** Block until data is available or [timeoutMs] elapses. Returns true if data available. */
    fun nativeWaitForData(sessionId: Int, timeoutMs: Long): Boolean

    /** Send a Unix signal (number) to the session's foreground process group. */
    fun nativeSendSignal(sessionId: Int, signal: Int): Boolean

    /** Resize the PTY (sends SIGWINCH to the child). */
    fun nativeResize(sessionId: Int, rows: Int, cols: Int): Boolean

    /** Is the child process still alive? (Uses kill(pid, 0) internally.) */
    fun nativeIsAlive(sessionId: Int): Boolean

    /** Get the child pid for a session. Returns -1 if unknown / closed. */
    fun nativeGetPid(sessionId: Int): Int

    /**
     * Get the exit code of the child. Returns -1 if still alive, or the exit code (0-255) if exited.
     * For signal-killed processes, returns 128 + signalNumber.
     */
    fun nativeGetExitCode(sessionId: Int): Int

    /**
     * Block until the child exits or [timeoutMs] elapses. Returns the exit code, or -1 on timeout.
     * Spec §44.1 EXTEND: reliable exit detection (replaces settle-time inference).
     */
    fun nativeWaitExit(sessionId: Int, timeoutMs: Long): Int

    /** Close a specific session: reap child, close master fd. Idempotent. */
    fun nativeCloseSession(sessionId: Int): Boolean

    /** Close all sessions (used on Runtime shutdown). */
    fun nativeCloseAll()

    /** Current active (non-closed) session count. */
    fun nativeActiveCount(): Int

    /** List active session ids. */
    fun nativeListSessionIds(): IntArray
}
