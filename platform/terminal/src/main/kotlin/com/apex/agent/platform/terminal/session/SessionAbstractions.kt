package com.apex.agent.platform.terminal.session

/**
 * PR #54 §4: Session creation config.
 * Config ≠ Runtime State. Does NOT contain PID/state/exitCode/cursor/screen.
 */
data class TerminalSessionConfig(
    val shell: String? = null,
    val workingDirectory: String? = null,
    val rows: Int = 24,
    val cols: Int = 80,
    val environment: Map<String, String> = emptyMap()
)

/**
 * PR #54 §12: Consistent session snapshot (single point-in-time capture).
 * No internal contradictions (e.g. state=EXITED + primaryProcess=RUNNING).
 */
data class TerminalSessionSnapshot(
    val sessionId: Long,
    val state: SessionState,
    val exitReason: SessionExitReason?,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val primaryProcessId: Long?,
    val shell: String?,
    val workingDirectory: String?
)

/**
 * PR #54 §26: Session-level events (NOT screen mutations — those stay in TerminalCore).
 */
sealed interface TerminalSessionEvent {
    data class StateChanged(val sessionId: Long, val from: SessionState, val to: SessionState) : TerminalSessionEvent
    data class PrimaryProcessExited(val sessionId: Long, val pid: Long, val exitCode: Int?) : TerminalSessionEvent
    data class Lost(val sessionId: Long, val reason: String) : TerminalSessionEvent
    data class JobChanged(val sessionId: Long, val jobId: Long, val newState: String) : TerminalSessionEvent
    data class Closed(val sessionId: Long, val exitReason: SessionExitReason?) : TerminalSessionEvent
}

/**
 * PR #54 §6: Primary Process — the shell. When it exits, Session → EXITED.
 * Tracked separately from Agent-run Jobs (git/python/etc).
 */
data class PrimaryProcess(
    val sessionId: Long,
    val pid: Int,
    val startedAt: Long,
    var exitCode: Int? = null,
    var finishedAt: Long? = null
) {
    val isAlive: Boolean get() = exitCode == null
}

/**
 * PR #54 §31: ExecutionBackend abstraction.
 * Reserved for future Android / Termux / Ubuntu-PRoot backends.
 * #54 only defines the interface; no implementation (no PRoot, no Ubuntu).
 *
 * Future:
 *   TerminalSession → ExecutionBackend
 *   ├── AndroidBackend   (current: forkpty via JNI)
 *   ├── TermuxBackend    (Termux proot)
 *   └── ProotLinuxBackend (Ubuntu via PRoot)
 */
interface TerminalExecutionBackend {
    /** Create a PTY process. Returns native session id + pid. */
    fun createPty(shell: String, cwd: String, rows: Int, cols: Int, env: Map<String, String>): BackendResult

    data class BackendResult(
        val nativeSessionId: Int,
        val pid: Int,
        val success: Boolean,
        val error: String? = null
    )

    /** Read from PTY (non-blocking). */
    fun read(nativeSessionId: Int, maxBytes: Int): ByteArray

    /** Write to PTY. */
    fun write(nativeSessionId: Int, bytes: ByteArray): Boolean

    /** Send signal. */
    fun sendSignal(nativeSessionId: Int, signal: Int): Boolean

    /** Resize PTY. */
    fun resize(nativeSessionId: Int, rows: Int, cols: Int): Boolean

    /** Check if process alive. */
    fun isAlive(nativeSessionId: Int): Boolean

    /** Get exit code. */
    fun getExitCode(nativeSessionId: Int): Int

    /** Close PTY + reap child. */
    fun close(nativeSessionId: Int)

    /** Backend type (for logging/debugging). */
    val backendType: String
}

/**
 * PR #54 §22: Session lifecycle listener — auto-unregistered on close().
 */
interface TerminalSessionListener {
    fun onStateChanged(sessionId: Long, from: SessionState, to: SessionState) {}
    fun onPrimaryProcessExited(sessionId: Long, pid: Int, exitCode: Int?) {}
    fun onLost(sessionId: Long, reason: String) {}
    fun onJobChanged(sessionId: Long, jobId: Long, newState: String) {}
    fun onClosed(sessionId: Long, exitReason: SessionExitReason?) {}
}
