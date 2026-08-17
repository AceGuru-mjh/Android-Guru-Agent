package com.apex.agent.platform.terminal.process2

import com.apex.agent.platform.terminal.io.UnixSignal

/**
 * PR #58: Job & Process Management 2.0 — Process Control Plane.
 *
 * Job ≠ Process ≠ ProcessGroup ≠ PID. Spec §0: "禁止任何类通过 PID 推断 Job 身份".
 */

// ─── §2: Process Identity (PID reuse protection) ───
data class ProcessIdentity(
    val pid: Int,
    val startToken: String? = null   // process start time / unique id (if backend supports)
) {
    /** Two identities match only if BOTH pid AND startToken match (§16: PID reuse). */
    fun matches(other: ProcessIdentity): Boolean =
        pid == other.pid && startToken == other.startToken
}

// ─── §3: ProcessHandle — abstract handle, not java.lang.Process ───
interface ProcessHandle {
    val identity: ProcessIdentity
    fun pid(): Int
    fun isAlive(): Boolean
    fun signal(signal: ProcessSignal)
    fun destroy()
    fun waitFor(): ExitInfo
}

// ─── §4: ProcessGroupHandle ───
interface ProcessGroupHandle {
    val pgid: Int
    fun signal(signal: ProcessSignal)
    fun isAlive(): Boolean
    fun members(): List<ProcessIdentity>
}

// ─── §5: Unified Signal API ───
enum class ProcessSignal(val number: Int) {
    HUP(1), INT(2), QUIT(3), TERM(15), KILL(9), STOP(19), CONT(18)
}

// ─── §6: Signal Target ───
sealed interface SignalTarget {
    data class Process(val identity: ProcessIdentity) : SignalTarget
    data class ProcessGroup(val pgid: Int) : SignalTarget
}

// ─── §7: Cancellation Policy ───
data class CancellationPolicy(
    val gracefulSignal: ProcessSignal = ProcessSignal.TERM,
    val gracePeriodMs: Long = 5000L,
    val forceSignal: ProcessSignal = ProcessSignal.KILL
) {
    companion object {
        val DEFAULT = CancellationPolicy()
        val IMMEDIATE = CancellationPolicy(gracePeriodMs = 0)
    }
}

// ─── §11/§12: ExitInfo + ExitReason (independent from JobState) ───
enum class ExitReason {
    NORMAL_EXIT,      // process exited (any exitCode, including non-zero)
    SIGNAL,           // killed by signal
    CANCELLED,        // cancelled by Agent/User
    TIMEOUT,          // timed out
    START_FAILURE,    // failed to start
    RUNTIME_LOST,     // backend lost track
    UNKNOWN
}

data class ExitInfo(
    val exitCode: Int?,
    val signal: ProcessSignal?,
    val coreDumped: Boolean = false,
    val reason: ExitReason,
    val startedAt: Long,
    val finishedAt: Long
)

// ─── §5: Process State (independent from JobState) ───
enum class ProcessState {
    RUNNING, SLEEPING, STOPPED, ZOMBIE, EXITED, UNKNOWN
}

// ─── §37: Process Snapshot ───
data class ProcessSnapshot(
    val identity: ProcessIdentity,
    val parent: ProcessIdentity?,
    val pgid: Int?,
    val sid: Int?,
    val state: ProcessState,
    val command: String?,
    val startTime: Long?
)

// ─── §3: Process Tree (with cycle protection + depth limit) ───
data class ProcessTree(
    val root: ProcessSnapshot,
    val children: List<ProcessTree>   // bounded by maxDepth (§15/§16)
) {
    fun flatten(): List<ProcessSnapshot> {
        val out = mutableListOf<ProcessSnapshot>()
        fun walk(t: ProcessTree, depth: Int) {
            if (depth > 64) return  // §16: depth limit
            out.add(t.root)
            t.children.forEach { walk(it, depth + 1) }
        }
        walk(this, 0)
        return out
    }
}

// ─── §18/§19: Orphan / Detached Policy ───
enum class OrphanPolicy {
    TERMINATE,     // kill orphaned children
    DETACH,        // let them survive
    UNKNOWN        // can't determine
}

data class ProcessOwnership(
    val jobId: Long,
    val managed: Boolean,
    val detached: Boolean,
    val orphanPolicy: OrphanPolicy = OrphanPolicy.UNKNOWN
)

// ─── §24/§25: PTY Attachment (independent from JobMode) ───
enum class PtyAttachment { ATTACHED, DETACHED, NONE }
enum class JobMode { FOREGROUND, BACKGROUND }

// ─── §27/§28: Job Result (with ObservationRange, NO output duplication) ───
data class ObservationRange(
    val sessionId: Long,
    val startSequence: Long,
    val endSequence: Long?
)

data class JobResult(
    val jobId: Long,
    val sessionId: Long,
    val state: String,        // JobState name
    val exitInfo: ExitInfo?,
    val observationRange: ObservationRange?
)

// ─── §39: Job Events (ordered, bounded, NOT screen mutations) ───
sealed interface JobEvent {
    data class Started(val jobId: Long, val sessionId: Long, val command: String) : JobEvent
    data class StateChanged(val jobId: Long, val from: String, val to: String) : JobEvent
    data class ProcessAdded(val jobId: Long, val identity: ProcessIdentity) : JobEvent
    data class ProcessExited(val jobId: Long, val identity: ProcessIdentity, val exitInfo: ExitInfo) : JobEvent
    data class CancellationRequested(val jobId: Long, val policy: CancellationPolicy) : JobEvent
    data class Timeout(val jobId: Long) : JobEvent
    data class Finished(val jobId: Long, val result: JobResult) : JobEvent
}

// ─── §42: Job State Reducer — single entry point for state transitions ───
object JobStateReducer {
    /**
     * Determines the final terminal state when multiple races occur simultaneously (§9/§15/§42/§43).
     * Priority: EXITED > CANCELLED > TIMEOUT > LOST > FAILED.
     * Once terminal, cannot be overridden (§10: irreversible).
     */
    fun resolveTerminalState(
        candidates: List<String>,
        current: String
    ): String {
        // Already terminal? Never override (§10).
        val terminalStates = setOf("EXITED", "INTERRUPTED", "TIMED_OUT", "FAILED", "CANCELLED", "LOST")
        if (current in terminalStates) return current

        // Pick highest-priority candidate (§9: exit race resolution)
        val priority = listOf("EXITED", "CANCELLED", "TIMED_OUT", "LOST", "FAILED")
        for (state in priority) {
            if (state in candidates) return state
        }
        return current
    }
}

// ─── §47/§48: Backend Capabilities ───
data class ProcessCapabilities(
    val supportsProcessGroups: Boolean = true,
    val supportsSignals: Boolean = true,
    val supportsProcessTree: Boolean = false,
    val supportsProcessStartIdentity: Boolean = false,
    val supportsReconciliation: Boolean = true
)

// ─── §21/§29: Job Registry (bounded) ───
data class JobRegistryEntry(
    val jobId: Long,
    val sessionId: Long,
    val rootPid: Int,
    val rootPgid: Int,
    val command: String,
    val state: String,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val exitInfo: ExitInfo?,
    val observationRange: ObservationRange?
) {
    val isTerminal: Boolean get() = state in setOf("EXITED", "INTERRUPTED", "TIMED_OUT", "FAILED", "CANCELLED", "LOST")
}
