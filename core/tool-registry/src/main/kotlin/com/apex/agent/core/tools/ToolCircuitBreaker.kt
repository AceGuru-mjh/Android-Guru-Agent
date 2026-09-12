package com.apex.agent.core.tools

import java.util.concurrent.ConcurrentHashMap

/**
 * # Tool System v3 — Per-Tool Circuit Breaker
 *
 * The rate limiter caps *volume*; the breaker caps *futility*. When a tool
 * fails [failureThreshold] times in a row, further calls are short-circuited
 * for [openCooldownMs] with an actionable message instead of executing —
 * the model stops paying (tokens, battery, wall-clock) for a tool that is
 * demonstrably down, and the user sees "clipboard is failing; the breaker
 * will retry it in 12s" instead of 40 identical error turns.
 *
 * State machine (classic release-independent breaker, Hystrix-shaped):
 *
 * ```
 *   CLOSED ──failureThreshold consecutive failures──▶ OPEN
 *      │                                              │
 *      ▲                                     cooldown elapses
 *      │                                              │
 *      └──probe succeeds── HALF_OPEN ──probe fails───┘
 *                             │
 *                             └──(only one probe at a time)
 * ```
 *
 * - **CLOSED**: normal pass-through; failures counted, successes reset the
 *   counter.
 * - **OPEN**: all calls denied fast. The Deny reason carries the tool's
 *   last error plus the cooldown remaining, so the model can decide to
 *   use a different tool (its error is a *strategy* instruction, matching
 *   how [ToolPermissionManager] phrases denials).
 * - **HALF_OPEN**: after cooldown, exactly one call is let through as a
 *   probe. Success closes the breaker; failure re-opens it with the full
 *   cooldown (exponential widening: each re-open doubles the cooldown up
 *   to [maxCooldownMs], because a tool that failed twice in a row needs
 *   longer to recover than one that failed once).
 *
 * Per-tool state; thread-safe (ConcurrentHashMap of immutable snapshot
 * states swapped atomically); no timers — transitions are evaluated
 * lazily on [check]/[recordSuccess]/[recordFailure], so the breaker costs
 * nothing while idle.
 *
 * @param failureThreshold consecutive failures before opening.
 * @param openCooldownMs initial open duration before the half-open probe.
 * @param maxCooldownMs ceiling for the widening cooldown.
 */
class ToolCircuitBreaker(
    private val failureThreshold: Int = 5,
    private val openCooldownMs: Long = 15_000L,
    private val maxCooldownMs: Long = 120_000L
) {

    /** Breaker state for one tool id. */
    enum class State { CLOSED, OPEN, HALF_OPEN }

    /** Outcome of a pre-flight breaker check. */
    sealed interface BreakerVerdict {
        /** Call may proceed. [isProbe] marks the single half-open trial. */
        data class Allow(val isProbe: Boolean) : BreakerVerdict

        /**
         * Call is short-circuited. [retryInMs] until the probe window;
         * [lastError] is the failing call that opened the breaker.
         */
        data class Deny(val retryInMs: Long, val lastError: String?) : BreakerVerdict
    }

    private class BreakerState(
        @Volatile var state: State = State.CLOSED,
        @Volatile var consecutiveFailures: Int = 0,
        @Volatile var openedAtMs: Long = 0L,
        @Volatile var cooldownMs: Long = 0L,
        @Volatile var lastError: String? = null,
        @Volatile var probeInFlight: Boolean = false
    )

    private val breakers = ConcurrentHashMap<String, BreakerState>()

    /**
     * Pre-flight check for a call to [toolId].
     *
     * A HALF_OPEN verdict with [BreakerVerdict.Allow.isProbe] reserves the
     * probe slot; the caller MUST then call [recordSuccess] or
     * [recordFailure] to release it (the enhanced executor does; if a
     * caller forgets, [probeTimeoutMs] in a later [check] re-arms it).
     */
    fun check(toolId: String, nowMs: Long = System.currentTimeMillis()): BreakerVerdict {
        val breaker = breakers[toolId] ?: return BreakerVerdict.Allow(isProbe = false)

        synchronized(breaker) {
            when (breaker.state) {
                State.CLOSED -> return BreakerVerdict.Allow(isProbe = false)

                State.OPEN -> {
                    val elapsed = nowMs - breaker.openedAtMs
                    if (elapsed >= breaker.cooldownMs) {
                        breaker.state = State.HALF_OPEN
                        breaker.probeInFlight = true
                        return BreakerVerdict.Allow(isProbe = true)
                    }
                    return BreakerVerdict.Deny(
                        retryInMs = breaker.cooldownMs - elapsed,
                        lastError = breaker.lastError
                    )
                }

                State.HALF_OPEN -> {
                    // A previous probe never completed (caller crashed or
                    // forgot to report). Re-arm it after a grace period so
                    // the breaker cannot wedge in HALF_OPEN forever.
                    if (breaker.probeInFlight) {
                        val probeStuckMs = nowMs - breaker.openedAtMs
                        if (probeStuckMs < breaker.cooldownMs * 2 + PROBE_GRACE_MS) {
                            return BreakerVerdict.Deny(
                                retryInMs = 1_000L,
                                lastError = breaker.lastError
                            )
                        }
                        breaker.probeInFlight = true
                        breaker.openedAtMs = nowMs
                    }
                    breaker.probeInFlight = true
                    return BreakerVerdict.Allow(isProbe = true)
                }
            }
        }
    }

    /** Record a successful call (closes an open/half-open breaker). */
    fun recordSuccess(toolId: String, nowMs: Long = System.currentTimeMillis()) {
        val breaker = breakers[toolId] ?: return
        synchronized(breaker) {
            breaker.state = State.CLOSED
            breaker.consecutiveFailures = 0
            breaker.lastError = null
            breaker.probeInFlight = false
        }
    }

    /**
     * Record a failed call. From CLOSED with the threshold reached it
     * opens the breaker; from HALF_OPEN it re-opens with a widened
     * cooldown.
     */
    fun recordFailure(toolId: String, error: String?, nowMs: Long = System.currentTimeMillis()) {
        val breaker = breakers.computeIfAbsent(toolId) { BreakerState() }
        synchronized(breaker) {
            breaker.consecutiveFailures += 1
            if (error != null) breaker.lastError = error.take(MAX_ERROR_TEXT)
            when (breaker.state) {
                State.CLOSED -> {
                    if (breaker.consecutiveFailures >= failureThreshold) {
                        openBreaker(breaker, widening = false, nowMs = nowMs)
                    }
                }
                State.HALF_OPEN -> openBreaker(breaker, widening = true, nowMs = nowMs)
                State.OPEN -> Unit
            }
        }
    }

    /** Force a tool's breaker to CLOSED (settings UI / test reset). */
    fun reset(toolId: String) {
        breakers.remove(toolId)
    }

    /** Force every breaker to CLOSED (new session / test reset). */
    fun resetAll() = breakers.clear()

    /** Snapshot of one tool's breaker (diagnostics / settings UI). */
    fun stateFor(toolId: String): State = breakers[toolId]?.state ?: State.CLOSED

    /** True when the tool is currently denied by an open breaker. */
    fun isOpen(toolId: String): Boolean = stateFor(toolId) == State.OPEN

    /** Consecutive failure count (0 when healthy / unknown). */
    fun failureCount(toolId: String): Int =
        breakers[toolId]?.consecutiveFailures ?: 0

    /**
     * Human-readable one-liner per tool with a non-closed breaker, for the
     * diagnostics report:
     * `clipboard: OPEN (3 failures, retry in 8s, last: Error: …)`.
     */
    fun report(): String {
        val open = breakers.entries
            .filter { it.value.state != State.CLOSED }
            .sortedBy { it.key }
        if (open.isEmpty()) return "all tool breakers closed"
        return open.joinToString("\n") { (id, b) ->
            when (b.state) {
                State.OPEN -> {
                    val retryIn = ((b.openedAtMs + b.cooldownMs) - System.currentTimeMillis())
                        .coerceAtLeast(0) / 1000
                    "$id: OPEN (${b.consecutiveFailures} failures, probe in ${retryIn}s" +
                        (b.lastError?.let { ", last: ${it.take(60)}" } ?: "") + ")"
                }
                State.HALF_OPEN -> "$id: HALF_OPEN (probe in flight)"
                State.CLOSED -> "$id: closed"
            }
        }
    }

    private fun openBreaker(breaker: BreakerState, widening: Boolean, nowMs: Long) {
        breaker.state = State.OPEN
        breaker.openedAtMs = nowMs
        breaker.cooldownMs = if (widening) {
            (breaker.cooldownMs * 2).coerceAtMost(maxCooldownMs).coerceAtLeast(openCooldownMs)
        } else {
            openCooldownMs
        }
        breaker.probeInFlight = false
    }

    private companion object {
        const val MAX_ERROR_TEXT = 160
        const val PROBE_GRACE_MS = 10_000L
    }
}
