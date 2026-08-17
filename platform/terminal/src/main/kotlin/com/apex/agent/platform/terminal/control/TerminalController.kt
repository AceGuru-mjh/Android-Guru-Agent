package com.apex.agent.platform.terminal.control

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.session.TerminalSessionConfig
import com.apex.agent.platform.terminal.session.TerminalSessionSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Terminal Control Plane 2.0 (Spec PR #55).
 *
 * Unified Agent-facing API. Agent does NOT touch PTY/Process/TerminalCore directly.
 * All operations go through this controller, which enforces:
 *   - Policy gate (§25: all Agent commands pass through CommandPolicy)
 *   - Session lifecycle (§27: create → execute → observe → wait → close)
 *   - No raw resource exposure (§21: no FD/Process/Stream/Scope leaks)
 *   - Multi-session isolation (§19: Session A ops don't affect Session B)
 *
 * Spec ref: PR #55 §1-§29.
 */
interface TerminalController {

    // ─── §4 Session Control ───
    suspend fun createSession(config: TerminalSessionConfig = TerminalSessionConfig()): Result<Long>
    suspend fun getSession(sessionId: Long): TerminalSessionSnapshot?
    suspend fun listSessions(): List<TerminalSessionSnapshot>
    suspend fun closeSession(sessionId: Long): Result<Unit>
    suspend fun closeAllSessions(): Result<Unit>

    // ─── §5/§7 Command / Job Control ───
    /** Non-blocking execute. Returns jobId. Agent uses wait()/observe() to track. */
    suspend fun execute(sessionId: Long, command: String, timeoutMs: Long = 0L): Result<Long>
    suspend fun getJob(sessionId: Long, jobId: Long): JobInfo?
    suspend fun listJobs(sessionId: Long): List<JobInfo>
    suspend fun wait(sessionId: Long, jobId: Long, timeoutMs: Long = 60_000L): Result<JobResult>
    suspend fun cancel(sessionId: Long, jobId: Long): Result<Unit>

    // ─── §9/§10 Input Control ───
    suspend fun write(sessionId: Long, data: ByteArray): Result<Unit>
    suspend fun sendText(sessionId: Long, text: String): Result<Unit> = write(sessionId, (text + "\n").toByteArray())
    suspend fun sendKey(sessionId: Long, key: TerminalKey): Result<Unit>
    suspend fun sendSignal(sessionId: Long, signal: UnixSignal): Result<Unit>
    suspend fun closeStdin(sessionId: Long): Result<Unit>

    // ─── §11 Resize ───
    suspend fun resize(sessionId: Long, rows: Int, cols: Int): Result<Unit>

    // ─── §12/§13 Observation ───
    suspend fun observe(sessionId: Long, mode: ObserveMode = ObserveMode.SEMANTIC, afterCursor: Long = 0L): Result<Observation>
    suspend fun getScreenText(sessionId: Long): Result<String>
    fun observeEvents(sessionId: Long): Flow<TerminalControlEvent>?

    // ─── §15 Job Result ───
    data class JobInfo(
        val jobId: Long, val sessionId: Long, val command: String,
        val state: String, val exitCode: Int?, val startedAt: Long, val finishedAt: Long?
    )

    data class JobResult(
        val jobId: Long, val exitCode: Int?, val state: String,
        val durationMs: Long, val startedAt: Long, val finishedAt: Long?
    )

    data class Observation(
        val mode: ObserveMode, val sessionId: Long, val cursor: Long,
        val startCursor: Long?, val endCursor: Long?,
        val truncated: Boolean, val overrun: Boolean, val availableFrom: Long?,
        val screenText: String?, val rawOutput: String?
    )

    enum class ObserveMode { SEMANTIC, SCREEN, RAW, EVENT }
}

/**
 * PR #55 §23: Unified event stream (bounded, no unbounded cache).
 */
sealed interface TerminalControlEvent {
    data class SessionStateChanged(val sessionId: Long, val from: String, val to: String) : TerminalControlEvent
    data class JobStateChanged(val sessionId: Long, val jobId: Long, val newState: String) : TerminalControlEvent
    data class OutputAvailable(val sessionId: Long, val cursor: Long) : TerminalControlEvent
    data class ProcessExited(val sessionId: Long, val jobId: Long?, val exitCode: Int?) : TerminalControlEvent
    data class Error(val sessionId: Long, val code: String, val message: String) : TerminalControlEvent
}

/**
 * PR #55 §14: Unified error model. Agent matches on error type, not exception strings.
 */
sealed class TerminalControlError(val code: String, val message: String) {
    data object SessionNotFound : TerminalControlError("SessionNotFound", "Session does not exist")
    data object SessionNotRunning : TerminalControlError("SessionNotRunning", "Session is not in RUNNING state")
    data object InvalidState : TerminalControlError("InvalidState", "Operation not valid in current session state")
    data object JobNotFound : TerminalControlError("JobNotFound", "Job does not exist")
    data object PtyClosed : TerminalControlError("PtyClosed", "PTY has been closed")
    data object PtyFailure : TerminalControlError("PtyFailure", "PTY I/O failure")
    data object Timeout : TerminalControlError("Timeout", "Operation timed out")
    data object InputRejected : TerminalControlError("InputRejected", "Input was rejected by policy")
    data object ResizeFailed : TerminalControlError("ResizeFailed", "PTY resize failed")
    data object PermissionDenied : TerminalControlError("PermissionDenied", "Command blocked by policy")
    data object InternalError : TerminalControlError("InternalError", "Unexpected runtime error")
}

/**
 * PR #55 §17: Cancellation semantics — cancel ≠ terminate ≠ kill.
 *
 *   cancel     → request stop (SIGTERM → grace → SIGKILL if needed)
 *   terminate  → graceful SIGTERM
 *   kill       → force SIGKILL
 *
 * Controller exposes cancel() which internally does the graceful lifecycle.
 * Agent doesn't manage signals manually.
 */
enum class CancellationMode { CANCEL, TERMINATE, KILL }
