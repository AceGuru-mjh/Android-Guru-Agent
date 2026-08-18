package com.apex.agent.platform.terminal.api

import kotlinx.coroutines.flow.Flow

/**
 * PR #60: Terminal Public API & Contract Freeze.
 *
 * This is the ONLY API Agent sees. Everything else (PTY, Process, TerminalCore,
 * ObservationEngine, SessionManager, JobManager, RecoveryCoordinator) is internal.
 *
 * Backend (Android/Termux/Ubuntu) implements ExecutionBackend — Agent never knows which.
 *
 * Freeze rule: after P60, can ADD optional fields/methods; MUST NOT change existing
 * semantics. Agent code written against P60 must work unchanged in P61+.
 *
 * Spec: PR #60 §1-§45.
 */

// ─── §22: API Version ───
object TerminalApiVersion {
    const val MAJOR = 1
    const val MINOR = 0
    val versionString: String get() = "$MAJOR.$MINOR"
}

// ─── §4/§5/§6: Type-safe IDs ───
@JvmInline value class SessionId(val value: String)
@JvmInline value class JobId(val value: String)

// ─── §3: Session Request ───
data class TerminalSize(val columns: Int = 80, val rows: Int = 24) {
    init { require(columns > 0 && rows > 0) { "columns and rows must be > 0" } }
    companion object { val DEFAULT = TerminalSize() }
}

data class EnvironmentSpec(
    val inheritParent: Boolean = true,
    val overrides: Map<String, String> = emptyMap(),
    val removals: Set<String> = emptySet()
) { companion object { val DEFAULT = EnvironmentSpec() } }

data class SessionRequest(
    val name: String? = null,
    val workingDirectory: String? = null,
    val environment: EnvironmentSpec = EnvironmentSpec.DEFAULT,
    val terminalSize: TerminalSize = TerminalSize.DEFAULT
)

// ─── §2: Terminal — Root Entry Point ───
interface Terminal {
    suspend fun createSession(request: SessionRequest = SessionRequest()): Result<TerminalSession>
    fun getSession(id: SessionId): TerminalSession?
    fun listSessions(): List<SessionSummary>
    suspend fun shutdown(): Result<Unit>
    fun capabilities(): TerminalCapabilities
    fun apiVersion(): String
}

// ─── §12: Session Summary (immutable) ───
data class SessionSummary(
    val id: SessionId,
    val name: String?,
    val state: String,
    val createdAt: Long
)

// ─── §2/§23: TerminalSession — Agent's workspace ───
interface TerminalSession {
    val id: SessionId
    val state: SessionLifecycleState

    suspend fun execute(request: ExecutionRequest): Result<JobHandle>
    suspend fun sendInput(input: TerminalInput): Result<Unit>
    suspend fun observe(request: ObservationRequest): Result<ObservationResult>
    suspend fun snapshot(): SessionSnapshot
    suspend fun resize(size: TerminalSize): Result<Unit>
    suspend fun stop(): Result<Unit>
    suspend fun close(): Result<Unit>  // §36: idempotent
}

enum class SessionLifecycleState { CREATED, RUNNING, STOPPING, EXITED, CLOSED, LOST }

// ─── §3/§16: Execution Request ───
data class ExecutionRequest(
    val command: String,
    val workingDirectory: String? = null,
    val environment: EnvironmentSpec = EnvironmentSpec.DEFAULT,
    val timeoutMs: Long? = null,
    val attachment: PtyAttachment = PtyAttachment.ATTACHED
)

enum class PtyAttachment { ATTACHED, DETACHED, NONE }

// ─── §4: JobHandle — Agent's execution handle ───
interface JobHandle {
    val id: JobId
    val state: JobState
    suspend fun cancel(): Result<Unit>  // §17: request stop (not "already stopped")
    suspend fun snapshot(): JobSnapshot
    suspend fun await(): Result<JobResult>  // §35: cancelling await ≠ cancelling Job
}

enum class JobState { CREATED, RUNNING, WAITING_INPUT, STOPPING, EXITED, CANCELLED, TIMED_OUT, FAILED, LOST }

// ─── §25/§26: Job Snapshot (immutable, no ProcessHandle) ───
data class JobSnapshot(
    val id: JobId,
    val sessionId: SessionId,
    val command: String,
    val state: JobState,
    val startedAt: Long,
    val finishedAt: Long?,
    val foreground: Boolean,
    val attachment: PtyAttachment,
    val exitInfo: ExitInfo?
)

data class ExitInfo(
    val exitCode: Int?,
    val signal: String?,
    val coreDumped: Boolean,
    val reason: ExitReason
)

enum class ExitReason { NORMAL_EXIT, SIGNAL, CANCELLED, TIMEOUT, START_FAILURE, RUNTIME_LOST, UNKNOWN }

// ─── §18: Job Result (immutable, with ObservationRange) ───
data class JobResult(
    val id: JobId,
    val sessionId: SessionId,
    val state: JobState,
    val exitInfo: ExitInfo?,
    val durationMs: Long,
    val startedAt: Long,
    val finishedAt: Long?,
    val observationRange: ObservationRange?
)

data class ObservationRange(
    val startCursor: String,
    val endCursor: String?
)

// ─── §8: Input API (sealed, no raw byte construction) ───
sealed interface TerminalInput {
    data class Text(val value: String) : TerminalInput
    data class Key(val key: TerminalKey) : TerminalInput
    data class Bytes(val value: ByteArray) : TerminalInput  // §9: copy-on-boundary
}

enum class TerminalKey {
    ENTER, TAB, BACKSPACE, ESCAPE, SPACE, DELETE, INSERT,
    HOME, END, PAGE_UP, PAGE_DOWN,
    UP, DOWN, LEFT, RIGHT,
    CTRL_C, CTRL_D, CTRL_Z, CTRL_BACKSLASH,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
}

// ─── §5/§6: Observation API ───
data class ObservationRequest(
    val cursor: String? = null,    // §27: opaque cursor (not Long)
    val maxBytes: Int = 12000
)

sealed interface ObservationResult {
    data class Snapshot(val snapshot: TerminalSnapshot) : ObservationResult
    data class Delta(val delta: TerminalDelta, val newCursor: String) : ObservationResult
    data class CursorExpired(val snapshot: TerminalSnapshot, val newCursor: String) : ObservationResult
}

// ─── §10/§11: Terminal Snapshot (immutable) ───
data class TerminalSnapshot(
    val rows: Int,
    val columns: Int,
    val screenText: String,
    val cursorRow: Int,
    val cursorColumn: Int,
    val cursorVisible: Boolean,
    val alternateScreen: Boolean,
    val title: String?,
    val sequence: Long
)

data class TerminalDelta(
    val fromSequence: Long,
    val toSequence: Long,
    val changes: List<TerminalChange>
)

sealed interface TerminalChange {
    data class CellsChanged(val row: Int, val startCol: Int, val endCol: Int, val text: String) : TerminalChange
    data class CursorChanged(val row: Int, val col: Int, val visible: Boolean) : TerminalChange
    data class ScreenResized(val rows: Int, val cols: Int) : TerminalChange
    data class TitleChanged(val title: String?) : TerminalChange
    data class ModeChanged(val alternateScreen: Boolean) : TerminalChange
    data class ScrollChanged(val direction: String, val count: Int) : TerminalChange
    data class Cleared(val mode: String) : TerminalChange
}

// ─── §12: Session Snapshot (immutable, all dimensions) ───
data class SessionSnapshot(
    val sessionId: SessionId,
    val name: String?,
    val lifecycle: SessionLifecycleState,
    val health: String,
    val recovery: String,
    val terminal: TerminalSnapshot,
    val foregroundJob: JobSnapshot?,
    val backgroundJobs: List<JobSnapshot>
)

// ─── §13/§14: Error Model (stable codes, not message-based) ───
enum class TerminalErrorCode {
    SESSION_NOT_FOUND,
    SESSION_NOT_RUNNING,
    SESSION_ALREADY_CLOSED,
    JOB_NOT_FOUND,
    INVALID_REQUEST,
    INVALID_WORKING_DIRECTORY,
    TIMEOUT,
    CANCELLED,
    BACKEND_UNAVAILABLE,
    RESOURCE_UNAVAILABLE,
    RECOVERY_FAILED,
    CURSOR_EXPIRED,
    INVALID_CURSOR,
    UNSUPPORTED,
    PERMISSION_DENIED,
    INTERNAL_ERROR
}

data class TerminalError(
    val code: TerminalErrorCode,
    val message: String,
    val retryable: Boolean
)

// ─── §21: Capabilities ───
data class TerminalCapabilities(
    val supportsPty: Boolean = true,
    val supportsProcessGroups: Boolean = true,
    val supportsSignals: Boolean = true,
    val supportsResize: Boolean = true,
    val supportsReattach: Boolean = false,
    val supportsPersistence: Boolean = true,
    val supportsProcessTree: Boolean = false,
    val supportsReconciliation: Boolean = true
)

// ─── §42/§43: Backend Contract (internal, not exposed to Agent) ───
interface ExecutionBackend {
    val backendType: String
    fun capabilities(): TerminalCapabilities
    // Implementation by Android/Termux/Ubuntu backends — NOT in Public API
}

// ─── §28: Subscription (Disposable pattern) ───
interface Subscription {
    fun cancel()
    val isActive: Boolean
}

// ─── §40: Terminal API Contract Events (for subscribe) ───
sealed interface TerminalApiEvent {
    data class SessionStateChanged(val sessionId: SessionId, val from: String, val to: String) : TerminalApiEvent
    data class JobStateChanged(val sessionId: SessionId, val jobId: JobId, val newState: String) : TerminalApiEvent
    data class OutputAvailable(val sessionId: SessionId, val cursor: String) : TerminalApiEvent
    data class ProcessExited(val sessionId: SessionId, val jobId: JobId, val exitCode: Int?) : TerminalApiEvent
}
