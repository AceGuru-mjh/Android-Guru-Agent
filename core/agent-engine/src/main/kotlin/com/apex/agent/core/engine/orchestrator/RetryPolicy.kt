package com.apex.agent.core.engine.orchestrator

import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * A68.2 — Retry policy with exponential backoff, jitter and a task-level
 * retry budget.
 *
 * ### Semantics
 *
 * - **Per-tool attempts**: a single logical tool call is attempted at most
 *   `1 + maxRetries` times (initial attempt + up to [maxRetries] retries).
 * - **Failure classes**: only classes in [retryableClasses] are retried.
 *   Default is `{TRANSIENT, TIMEOUT}` — a bounded retry for timeouts,
 *   standard retry for network blips. PERMISSION/FATAL are never retried
 *   (retrying a denied or deterministic failure just burns time).
 * - **Backoff**: exponential — `initialBackoffMs * multiplier^(attempt-1)`,
 *   capped at [maxBackoffMs], with ±[jitterRatio] uniform jitter to avoid
 *   thundering-herd retries of parallel batches.
 * - **Retry budget**: [retryBudget] bounds the TOTAL number of retries
 *   across the whole task (not per call). Once exhausted, no call is
 *   retried anymore; failures fall through to the LLM as usual. This
 *   prevents a task from spending minutes in retry loops.
 *
 * Pure computation, no I/O, no clock — [backoffDelayMs] takes an optional
 * [Random] so tests can pin the jitter deterministically.
 */
data class RetryPolicy(
    /** Max RETRIES per logical call (attempts = 1 + this). */
    val maxRetries: Int = 2,
    /** First retry delay. */
    val initialBackoffMs: Long = 500L,
    /** Backoff growth factor per retry. */
    val backoffMultiplier: Double = 2.0,
    /** Ceiling for a single backoff delay. */
    val maxBackoffMs: Long = 8_000L,
    /** Jitter fraction: delay is scaled by (1 ± jitterRatio). 0.2 → ±20%. */
    val jitterRatio: Double = 0.2,
    /** Failure classes that qualify for a retry. */
    val retryableClasses: Set<FailureClass> = setOf(FailureClass.TRANSIENT, FailureClass.TIMEOUT),
    /** Total retries allowed across the entire task. */
    val retryBudget: Int = 6,
    /** Extra retry allowance for TIMEOUT specifically (timeouts are often one-off stalls). */
    val extraTimeoutRetries: Int = 0
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(initialBackoffMs >= 0) { "initialBackoffMs must be >= 0" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0" }
        require(maxBackoffMs >= initialBackoffMs) { "maxBackoffMs must be >= initialBackoffMs" }
        require(jitterRatio in 0.0..0.5) { "jitterRatio must be within [0.0, 0.5]" }
        require(retryBudget >= 0) { "retryBudget must be >= 0" }
    }

    /** Result of a should-retry query. */
    sealed interface RetryDecision {
        /** Retry after [delayMs]. */
        data class Retry(val delayMs: Long, val reason: String) : RetryDecision
        /** Do not retry — [reason] explains why (class not retryable / attempts exhausted / budget exhausted). */
        data class Stop(val reason: String) : RetryDecision
    }

    /**
     * Decide whether to retry after the [attempt]-th failure (1-based —
     * `attempt == 1` is the initial attempt failing).
     *
     * @param failureClass classification of the failure
     * @param attempt 1-based attempt number that just failed
     * @param retriesUsed total retries already consumed by the task (budget)
     */
    fun shouldRetry(
        failureClass: FailureClass,
        attempt: Int,
        retriesUsed: Int
    ): RetryDecision {
        if (failureClass !in retryableClasses) {
            return RetryDecision.Stop("failure class $failureClass is not retryable")
        }
        val maxRetriesForClass = maxRetries + if (failureClass == FailureClass.TIMEOUT) extraTimeoutRetries else 0
        if (attempt > maxRetriesForClass) {
            return RetryDecision.Stop("attempt $attempt exceeded max retries ($maxRetriesForClass)")
        }
        if (retriesUsed >= retryBudget) {
            return RetryDecision.Stop("task retry budget exhausted ($retriesUsed/$retryBudget)")
        }
        return RetryDecision.Retry(
            delayMs = backoffDelayMs(attempt),
            reason = "retryable failure ($failureClass), attempt $attempt"
        )
    }

    /**
     * Exponential backoff with jitter for the [attempt]-th retry:
     * `min(initial * multiplier^(attempt-1), max) * (1 ± jitterRatio)`.
     */
    fun backoffDelayMs(attempt: Int, random: Random = Random.Default): Long {
        val base = min(
            (initialBackoffMs * Math.pow(backoffMultiplier, (attempt - 1).coerceAtLeast(0).toDouble()))
                .roundToLong(),
            maxBackoffMs
        )
        if (base <= 0L || jitterRatio <= 0.0) return base
        val jitterSpan = base * jitterRatio
        val jittered = base - jitterSpan + random.nextDouble() * 2 * jitterSpan
        return jittered.roundToLong().coerceAtLeast(0L)
    }

    companion object {
        /** Production defaults (see class doc). */
        val DEFAULT = RetryPolicy()

        /** No retries at all — preserves A68.1 behaviour exactly. */
        val DISABLED = RetryPolicy(
            maxRetries = 0,
            retryBudget = 0
        )

        /** Fast retry profile for tests: no backoff, no jitter. */
        val FAST = RetryPolicy(
            maxRetries = 3,
            initialBackoffMs = 0L,
            maxBackoffMs = 0L,
            jitterRatio = 0.0,
            retryBudget = 12
        )
    }
}
