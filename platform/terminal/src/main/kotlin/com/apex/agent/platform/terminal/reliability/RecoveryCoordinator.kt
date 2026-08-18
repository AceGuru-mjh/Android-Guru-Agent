package com.apex.agent.platform.terminal.reliability

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * PR #59 §10: Per-Session Recovery Coordinator.
 *
 * - §10: Per-session recovery lock (one active recovery per session)
 * - §8: RecoveryId deduplication (same failure → one recovery)
 * - §6: Storm protection (RecoveryAttemptLimiter)
 * - §9: Idempotent recover() calls
 * - §32: Shutdown/Recovery mutual exclusion
 * - §11: Recovery runs on independent scheduler, never blocks PTY reader
 */
class RecoveryCoordinator(
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    private val stormLimiter: RecoveryAttemptLimiter = RecoveryAttemptLimiter()
) {
    private val activeRecoveries = ConcurrentHashMap<Long, RecoveryContext>()
    private val recoveryLocks = ConcurrentHashMap<Long, Any>()
    private val metrics = RecoveryMetrics()
    private val eventFlow = MutableSharedFlow<RecoveryEvent>(extraBufferCapacity = 128)
    val events = eventFlow.asSharedFlow()

    /** §10: Try to start a recovery for a session. Returns null if locked or storm-blocked. */
    fun tryStartRecovery(
        sessionId: Long,
        failure: TerminalFailure,
        jobIds: List<Long> = emptyList()
    ): RecoveryContext? {
        // §6: Storm protection
        if (stormLimiter.shouldBlock(sessionId)) {
            metrics.recoveryLoopsPrevented++
            return null
        }

        // §10: Per-session lock — only one active recovery
        val lock = recoveryLocks.computeIfAbsent(sessionId) { Any() }
        synchronized(lock) {
            // §8: Dedup — if already recovering this session, don't start another
            if (activeRecoveries.containsKey(sessionId)) {
                return null
            }

            val ctx = RecoveryContext(
                recoveryId = "recovery_${UUID.randomUUID()}",
                sessionId = sessionId,
                jobIds = jobIds,
                failure = failure,
                attempt = 0,
                startedAt = System.currentTimeMillis()
            )
            activeRecoveries[sessionId] = ctx
            metrics.recoveryAttempts++

            eventFlow.tryEmit(RecoveryEvent.Started(
                recoveryId = ctx.recoveryId,
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
                failure = failure
            ))
            return ctx
        }
    }

    /** §3: Get next recovery decision for the current attempt. */
    fun nextDecision(ctx: RecoveryContext): RecoveryDecision {
        val decision = RecoveryDecisionEngine.decide(
            failure = ctx.failure,
            attempt = ctx.attempt,
            maxAttempts = retryPolicy.maxAttempts
        )
        eventFlow.tryEmit(RecoveryEvent.Attempt(
            recoveryId = ctx.recoveryId,
            sessionId = ctx.sessionId,
            timestamp = System.currentTimeMillis(),
            attempt = ctx.attempt,
            decision = decision
        ))
        return decision
    }

    /** §4: Get backoff delay for current attempt. */
    fun backoffDelay(ctx: RecoveryContext): Long = retryPolicy.delayFor(ctx.attempt)

    /** Mark recovery as succeeded. */
    fun markSucceeded(ctx: RecoveryContext) {
        activeRecoveries.remove(ctx.sessionId)
        stormLimiter.reset(ctx.sessionId)
        metrics.recoverySuccesses++
        eventFlow.tryEmit(RecoveryEvent.Succeeded(
            recoveryId = ctx.recoveryId,
            sessionId = ctx.sessionId,
            timestamp = System.currentTimeMillis()
        ))
    }

    /** Mark recovery as degraded (partial recovery, runtime continues in degraded mode). */
    fun markDegraded(ctx: RecoveryContext, reason: String) {
        activeRecoveries.remove(ctx.sessionId)
        metrics.recoveryFailures++
        eventFlow.tryEmit(RecoveryEvent.Degraded(
            recoveryId = ctx.recoveryId,
            sessionId = ctx.sessionId,
            timestamp = System.currentTimeMillis(),
            reason = reason
        ))
    }

    /** Mark recovery as failed (unrecoverable). */
    fun markFailed(ctx: RecoveryContext, reason: String) {
        activeRecoveries.remove(ctx.sessionId)
        metrics.recoveryFailures++
        eventFlow.tryEmit(RecoveryEvent.Failed(
            recoveryId = ctx.recoveryId,
            sessionId = ctx.sessionId,
            timestamp = System.currentTimeMillis(),
            reason = reason
        ))
    }

    /** §32: Abort active recovery (when session.close() is called during recovery). */
    fun abort(sessionId: Long, reason: String) {
        val ctx = activeRecoveries.remove(sessionId)
        if (ctx != null) {
            eventFlow.tryEmit(RecoveryEvent.Aborted(
                recoveryId = ctx.recoveryId,
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
                reason = reason
            ))
        }
    }

    /** Increment attempt count for existing recovery context. */
    fun incrementAttempt(ctx: RecoveryContext): RecoveryContext {
        val updated = ctx.copy(attempt = ctx.attempt + 1)
        activeRecoveries[ctx.sessionId] = updated
        return updated
    }

    fun hasActiveRecovery(sessionId: Long): Boolean = activeRecoveries.containsKey(sessionId)
    fun getActiveRecovery(sessionId: Long): RecoveryContext? = activeRecoveries[sessionId]
    fun getMetrics(): RecoveryMetrics = metrics

    /** §42: Diagnostic snapshot of active recoveries. */
    fun activeRecoveryContexts(): List<RecoveryContext> = activeRecoveries.values.toList()
}
