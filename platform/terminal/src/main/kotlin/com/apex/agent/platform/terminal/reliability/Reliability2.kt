package com.apex.agent.platform.terminal.reliability

import kotlinx.coroutines.flow.Flow

/**
 * PR #59: Reliability & Recovery 2.0 (53-section Spec).
 *
 * Core principle: Terminal is a multi-resource distributed lifecycle system.
 * A component failure should NOT automatically cascade to all components.
 * Local fault isolation + recovery + eventual consistency.
 */

// ─── §1: Unified Failure Model ───
enum class Recoverability { RECOVERABLE, DEGRADED, TERMINAL, UNKNOWN }
enum class FailurePhase { STARTUP, RUNNING, SHUTDOWN, RECOVERY, UNKNOWN }

sealed interface TerminalFailure {
    val recoverability: Recoverability
    val phase: FailurePhase
    val cause: Throwable?
    val sessionId: Long?
    val jobId: Long?
    val operation: String?

    data class PtyFailure(
        override val sessionId: Long?, override val jobId: Long?,
        override val operation: String?, override val cause: Throwable?,
        override val recoverability: Recoverability = Recoverability.DEGRADED,
        override val phase: FailurePhase = FailurePhase.RUNNING
    ) : TerminalFailure

    data class ProcessFailure(
        override val sessionId: Long?, override val jobId: Long?,
        override val operation: String?, override val cause: Throwable?,
        override val recoverability: Recoverability = Recoverability.DEGRADED,
        override val phase: FailurePhase = FailurePhase.RUNNING
    ) : TerminalFailure

    data class IoFailure(
        override val sessionId: Long?, override val jobId: Long?,
        override val operation: String?, override val cause: Throwable?,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val phase: FailurePhase = FailurePhase.RUNNING
    ) : TerminalFailure

    data class SessionFailure(
        override val sessionId: Long?, override val jobId: Long?,
        override val operation: String?, override val cause: Throwable?,
        override val recoverability: Recoverability = Recoverability.TERMINAL,
        override val phase: FailurePhase = FailurePhase.RUNNING
    ) : TerminalFailure

    data class ObservationFailure(
        override val sessionId: Long?, override val jobId: Long?,
        override val operation: String?, override val cause: Throwable?,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val phase: FailurePhase = FailurePhase.RUNNING
    ) : TerminalFailure

    data class PersistenceFailure(
        override val sessionId: Long?, override val jobId: Long?,
        override val operation: String?, override val cause: Throwable?,
        override val recoverability: Recoverability = Recoverability.DEGRADED,
        override val phase: FailurePhase = FailurePhase.RUNNING
    ) : TerminalFailure

    data class ResourceFailure(
        override val sessionId: Long?, override val jobId: Long?,
        override val operation: String?, override val cause: Throwable?,
        override val recoverability: Recoverability = Recoverability.TERMINAL,
        override val phase: FailurePhase = FailurePhase.RUNNING
    ) : TerminalFailure

    data class RuntimeFailure(
        override val sessionId: Long?, override val jobId: Long?,
        override val operation: String?, override val cause: Throwable?,
        override val recoverability: Recoverability = Recoverability.TERMINAL,
        override val phase: FailurePhase = FailurePhase.UNKNOWN
    ) : TerminalFailure

    data class UnknownFailure(
        override val sessionId: Long?, override val jobId: Long?,
        override val operation: String?, override val cause: Throwable?,
        override val recoverability: Recoverability = Recoverability.UNKNOWN,
        override val phase: FailurePhase = FailurePhase.UNKNOWN
    ) : TerminalFailure
}

// ─── §3: Recovery Decision Engine ───
sealed interface RecoveryDecision {
    data object Retry : RecoveryDecision
    data object Reconcile : RecoveryDecision
    data object Degrade : RecoveryDecision
    data object Terminate : RecoveryDecision
    data object Ignore : RecoveryDecision
}

object RecoveryDecisionEngine {
    /**
     * Decide recovery action based on failure type + attempt count + recoverability.
     * §5: Retry must be bounded. §6: Storm protection after N failures.
     */
    fun decide(failure: TerminalFailure, attempt: Int, maxAttempts: Int): RecoveryDecision {
        if (attempt >= maxAttempts) return RecoveryDecision.Degrade
        return when (failure.recoverability) {
            Recoverability.RECOVERABLE -> {
                // §5: Retry only for transient errors (PTY temporarily unavailable)
                if (attempt < maxAttempts) RecoveryDecision.Retry else RecoveryDecision.Degrade
            }
            Recoverability.DEGRADED -> RecoveryDecision.Reconcile
            Recoverability.TERMINAL -> RecoveryDecision.Terminate
            Recoverability.UNKNOWN -> if (attempt < 2) RecoveryDecision.Retry else RecoveryDecision.Terminate
        }
    }
}

// ─── §4: Retry Policy (bounded, backoff, jitter) ───
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 100L,
    val maxDelayMs: Long = 5000L,
    val backoffMultiplier: Double = 2.0,
    val jitterMs: Long = 50L
) {
    companion object {
        val DEFAULT = RetryPolicy()
        val AGGRESSIVE = RetryPolicy(maxAttempts = 5, initialDelayMs = 50L, maxDelayMs = 1000L)
        val CONSERVATIVE = RetryPolicy(maxAttempts = 1, initialDelayMs = 500L, maxDelayMs = 10000L)
    }

    /** Compute delay for attempt N (0-indexed). §7: backoff + jitter. */
    fun delayFor(attempt: Int): Long {
        val base = (initialDelayMs * Math.pow(backoffMultiplier, attempt.toDouble())).toLong()
        val capped = minOf(base, maxDelayMs)
        val jitter = (Math.random() * jitterMs).toLong()
        return capped + jitter
    }
}

// ─── §6: Recovery Storm Protection ───
data class RecoveryAttemptLimiter(
    val maxRecoveryAttempts: Int = 5,
    val resetWindowMs: Long = 60_000L
) {
    private val attempts = mutableMapOf<Long, MutableList<Long>>()  // sessionId → timestamps
    private var stormDetected = mutableMapOf<Long, Boolean>()      // sessionId → storm flag

    /** Returns true if recovery should be blocked (storm detected). */
    fun shouldBlock(sessionId: Long): Boolean {
        val now = System.currentTimeMillis()
        val list = attempts.getOrPut(sessionId) { mutableListOf() }
        list.removeAll { now - it > resetWindowMs }
        if (stormDetected[sessionId] == true && list.size > maxRecoveryAttempts) return true
        list.add(now)
        if (list.size > maxRecoveryAttempts) {
            stormDetected[sessionId] = true
            return true
        }
        return false
    }

    fun reset(sessionId: Long) {
        attempts.remove(sessionId)
        stormDetected.remove(sessionId)
    }
}

// ─── §8/§12: Recovery Context + Events ───
data class RecoveryContext(
    val recoveryId: String,
    val sessionId: Long,
    val jobIds: List<Long>,
    val failure: TerminalFailure,
    val attempt: Int,
    val startedAt: Long
)

sealed interface RecoveryEvent {
    val recoveryId: String
    val sessionId: Long
    val timestamp: Long

    data class Started(override val recoveryId: String, override val sessionId: Long, override val timestamp: Long, val failure: TerminalFailure) : RecoveryEvent
    data class Attempt(override val recoveryId: String, override val sessionId: Long, override val timestamp: Long, val attempt: Int, val decision: RecoveryDecision) : RecoveryEvent
    data class Succeeded(override val recoveryId: String, override val sessionId: Long, override val timestamp: Long) : RecoveryEvent
    data class Degraded(override val recoveryId: String, override val sessionId: Long, override val timestamp: Long, val reason: String) : RecoveryEvent
    data class Failed(override val recoveryId: String, override val sessionId: Long, override val timestamp: Long, val reason: String) : RecoveryEvent
    data class Aborted(override val recoveryId: String, override val sessionId: Long, override val timestamp: Long, val reason: String) : RecoveryEvent
}

// ─── §50/§51: Three-dimensional state model ───
enum class LifecycleState { CREATED, STARTING, RUNNING, STOPPING, EXITED, FAILED, CLOSED, LOST }
enum class HealthState { HEALTHY, DEGRADED, FAILED }
enum class RecoveryState { NONE, RECONCILING, RECOVERING, RECOVERED, UNRECOVERABLE }

/** §51: Combined state — Lifecycle + Health + Recovery (not a mega-enum). */
data class TerminalRuntimeState(
    val lifecycle: LifecycleState,
    val health: HealthState,
    val recovery: RecoveryState
)

// ─── §43/§44: Health + Metrics ───
data class HealthSnapshot(
    val state: HealthState,
    val activeSessions: Int,
    val activeJobs: Int,
    val activeRecoveries: Int,
    val resourceLeaksDetected: Boolean
)

data class RecoveryMetrics(
    var recoveryAttempts: Int = 0,
    var recoverySuccesses: Int = 0,
    var recoveryFailures: Int = 0,
    var recoveryLoopsPrevented: Int = 0,
    var ptyFailures: Int = 0,
    var ioFailures: Int = 0,
    var persistenceFailures: Int = 0,
    var resourceLeaks: Int = 0
)

// ─── §42: Diagnostic Snapshot ───
data class TerminalDiagnosticSnapshot(
    val sessions: List<Long>,
    val jobs: List<Long>,
    val activeRecoveries: List<RecoveryContext>,
    val resourceCounts: ResourceCounts,
    val persistenceState: String,
    val metrics: RecoveryMetrics
)

data class ResourceCounts(
    val ptys: Int,
    val watchers: Int,
    val timers: Int,
    val subscriptions: Int,
    val processHandles: Int
)

// ─── §4/§35: Resource Ownership + Registry ───
data class ResourceEntry(
    val resourceId: String,
    val resourceType: ResourceType,
    val ownerId: Long,      // sessionId or jobId
    val createdAt: Long,
    var releasedAt: Long? = null
) {
    val isReleased: Boolean get() = releasedAt != null
}

enum class ResourceType { PTY, JOB, PROCESS, WATCHER, TIMER, SUBSCRIPTION, PERSISTENCE_HANDLE, COROUTINE_SCOPE }

class ResourceRegistry {
    private val resources = mutableMapOf<String, ResourceEntry>()

    fun register(entry: ResourceEntry) { resources[entry.resourceId] = entry }
    fun release(resourceId: String) { resources[resourceId]?.releasedAt = System.currentTimeMillis() }
    fun get(resourceId: String): ResourceEntry? = resources[resourceId]
    fun getByOwner(ownerId: Long): List<ResourceEntry> = resources.values.filter { it.ownerId == ownerId && !it.isReleased }
    fun unreleasedCount(): Int = resources.values.count { !it.isReleased }
    fun clear() { resources.clear() }

    /** §29: Debug — check for leaks (createdCount vs releasedCount). */
    fun leakReport(): List<ResourceEntry> = resources.values.filter { !it.isReleased }
}

// ─── §5/§31: Cleanup Protocol ───
enum class CleanupStep {
    REQUEST_STOP,      // signal intent to close
    STOP_INPUT,         // stop accepting input
    STOP_SUBSCRIPTIONS, // stop observation subscriptions
    STOP_WATCHERS,      // cancel process watchers
    CANCEL_TIMERS,      // cancel timeout timers
    TERMINATE_JOBS,     // cancel/kill all jobs
    CLOSE_PTY,          // close PTY fd
    RELEASE_HANDLES,    // release process/group handles
    PERSIST_FINAL_STATE,// save final state
    DONE                // cleanup complete
}

data class CleanupResult(
    val steps: List<CleanupStep>,
    val completed: Boolean,
    val errors: List<String>
)
