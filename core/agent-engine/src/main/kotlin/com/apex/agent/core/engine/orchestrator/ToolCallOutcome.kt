package com.apex.agent.core.engine.orchestrator

/**
 * A68.2/A68.3 — Result model for one logical tool call.
 *
 * A "logical" call may span multiple ATTEMPTS (A68.2 retries): [attempts]
 * counts them, [failureClass] is set iff the call ultimately failed.
 */
data class ToolCallOutcome(
    val callId: String,
    val toolName: String,
    val arguments: String,
    val output: String,
    val success: Boolean,
    val durationMs: Long,
    /** 1-based number of attempts actually executed (≥1). */
    val attempts: Int = 1,
    /** Classification of the FINAL failure (null on success / skip). */
    val failureClass: FailureClass? = null,
    /** True when the call was never executed because a dependency failed. */
    val skipped: Boolean = false,
    /** Why it was skipped (dependency callId), when [skipped]. */
    val skipReason: String? = null
)

/**
 * A68.3 — Aggregated result of a batch of tool calls executed through the
 * dependency graph. This is the "partial failure + result aggregation"
 * piece: the batch neither fails wholesale nor silently drops failures —
 * it reports exactly which calls succeeded, failed and were skipped.
 */
class ParallelBatchResult(val outcomes: List<ToolCallOutcome>) {

    val totalCalls: Int get() = outcomes.size
    val succeeded: List<ToolCallOutcome> get() = outcomes.filter { it.success && !it.skipped }
    val failed: List<ToolCallOutcome> get() = outcomes.filter { !it.success && !it.skipped }
    val skipped: List<ToolCallOutcome> get() = outcomes.filter { it.skipped }

    val succeededCount: Int get() = succeeded.size
    val failedCount: Int get() = failed.size
    val skippedCount: Int get() = skipped.size

    val allSucceeded: Boolean get() = failedCount == 0 && skippedCount == 0
    val hasPartialFailure: Boolean get() = failedCount > 0 || skippedCount > 0

    /** Total attempts across all calls (≥ totalCalls when retries happened). */
    val totalAttempts: Int get() = outcomes.sumOf { it.attempts }

    fun outcomeFor(callId: String): ToolCallOutcome? = outcomes.firstOrNull { it.callId == callId }

    /**
     * Compact one-line summary for logs / lifecycle events, e.g.
     * `batch: 4 calls → 2 ok, 1 failed, 1 skipped (attempts=5, 142ms)`.
     */
    fun summaryLine(durationMs: Long): String =
        "batch: $totalCalls calls → $succeededCount ok, $failedCount failed, " +
            "$skippedCount skipped (attempts=$totalAttempts, ${durationMs}ms)"
}

/**
 * A68.2 — Task-wide retry budget.
 *
 * The RetryPolicy bounds retries PER CALL; this budget bounds retries for
 * the WHOLE TASK, so a task with 20 flaky calls can't spend 20×maxRetries
 * backoff cycles. Single-threaded accounting is enough: the BUILD loop and
 * its parallel workers mutate it only from within the task's coroutine
 * scope, and increments are atomic anyway (simple Int under a lock-free
 * assumption of read-modify-write from a single coroutine... but parallel
 * workers DO call it concurrently, hence @Volatile + synchronized).
 */
class RetryBudget(private val maxRetries: Int) {
    @Volatile
    private var used: Int = 0

    val usedCount: Int get() = used
    val remaining: Int get() = (maxRetries - used).coerceAtLeast(0)
    val exhausted: Boolean get() = used >= maxRetries

    @Synchronized
    fun tryConsume(): Boolean {
        if (used >= maxRetries) return false
        used++
        return true
    }

    fun reset() {
        used = 0
    }
}
