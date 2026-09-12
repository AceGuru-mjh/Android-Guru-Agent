package com.apex.agent.core.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * # Tool System v2 — Usage Statistics
 *
 * The registry knows *which* tools exist; nothing knows *how they behave*.
 * [ToolUsageTracker] fills that gap: the executor records every invocation
 * (start timestamp, success/failure, duration) and the tracker aggregates
 * per-tool statistics that answer real operational questions:
 *
 * - Which tools does the model actually use? (prune candidates for prompt
 *   budget)
 * - Which tools fail most? (regression radar — a spike after a change is a
 *   broken tool, not a flaky model)
 * - How slow is a tool in practice? (timeout tuning)
 * - What was the last error per tool? (first thing to show the user)
 *
 * Cost model: two atomic increments per invocation + one map read. The
 * [report] rendering is only built on demand.
 *
 * Thread-safety: all mutable state is lock-free (ConcurrentHashMap +
 * atomics). The per-invocation handle returned by [begin] is safe to hand
 * to another coroutine/thread for the completion call.
 */

/**
 * Aggregated statistics for one tool id. All fields are point-in-time
 * snapshots read under the tracker's internal structures — values are
 * self-consistent per field, but fields may be from adjacent updates.
 */
class ToolUsageStat(
    val toolId: String,
    val invocations: Int,
    val successes: Int,
    val failures: Int,
    val totalDurationMs: Long,
    val lastError: String?,
    val lastUsedAtEpochMs: Long
) {
    /** Success rate in 0..1 (0 when never invoked). */
    val successRate: Double
        get() = if (invocations == 0) 0.0 else successes.toDouble() / invocations

    /** Mean duration in ms (0 when never invoked). */
    val meanDurationMs: Double
        get() = if (invocations == 0) 0.0 else totalDurationMs.toDouble() / invocations

    /** One-line human-readable summary. */
    fun summary(): String = buildString {
        append(toolId).append(": ")
        append(invocations).append(" calls, ")
        append(successes).append(" ok, ")
        append(failures).append(" failed")
        if (invocations > 0) {
            append(", ").append("%.0f".format(meanDurationMs)).append("ms avg")
            append(String.format(" (成功率 %.0f%%)", successRate * 100))
        }
        lastError?.let { append(" | last: ").append(it.take(80)) }
    }
}

/**
 * Per-invocation measurement handle. Returned by [ToolUsageTracker.begin],
 * completed with [ToolUsageTracker.success] or [ToolUsageTracker.failure].
 */
class ToolInvocation internal constructor(
    val toolId: String,
    internal val startedAtNano: Long,
    internal val startedAtEpochMs: Long
)

/**
 * Lock-free per-tool usage statistics recorder.
 *
 * @param maxRecentErrors retained "last error" strings per tool (only the
 *   most recent is exposed; the cap bounds memory for pathological tools).
 */
class ToolUsageTracker(
    private val maxRecentErrors: Int = 5
) {
    private class Counters {
        val invocations = AtomicInteger()
        val successes = AtomicInteger()
        val failures = AtomicInteger()
        val totalDurationNanos = AtomicLong()
        val lastUsedAtEpochMs = AtomicLong()
        val lastError = ConcurrentHashMap.newKeySet<String>()
    }

    private val perTool = ConcurrentHashMap<String, Counters>()

    /**
     * Mark the start of an invocation. Record the returned handle and pass
     * it to [success]/[failure] when the call completes (either way).
     */
    fun begin(toolId: String): ToolInvocation {
        val now = System.nanoTime()
        counters(toolId).invocations.incrementAndGet()
        counters(toolId).lastUsedAtEpochMs.set(System.currentTimeMillis())
        return ToolInvocation(toolId, now, System.currentTimeMillis())
    }

    /** Record a successful completion (no-op when the handle is null). */
    fun success(invocation: ToolInvocation?) {
        if (invocation == null) return
        val c = counters(invocation.toolId)
        c.successes.incrementAndGet()
        c.totalDurationNanos.addAndGet(System.nanoTime() - invocation.startedAtNano)
    }

    /** Record a failed completion ([error] is kept as the tool's last error). */
    fun failure(invocation: ToolInvocation?, error: String?) {
        if (invocation == null) return
        val c = counters(invocation.toolId)
        c.failures.incrementAndGet()
        c.totalDurationNanos.addAndGet(System.nanoTime() - invocation.startedAtNano)
        if (!error.isNullOrBlank()) {
            if (c.lastError.size >= maxRecentErrors) {
                // Cap reached — drop the set's arbitrary content and re-seed
                // with the newest error. Only the newest is read anyway.
                c.lastError.clear()
            }
            c.lastError.add(error.take(MAX_ERROR_TEXT))
        }
    }

    /** Aggregate snapshot for one tool (null when never invoked). */
    fun statFor(toolId: String): ToolUsageStat? {
        val c = perTool[toolId] ?: return null
        return snapshot(toolId, c)
    }

    /** Aggregates for all invoked tools, most-invoked first. */
    fun stats(): List<ToolUsageStat> =
        perTool.entries.map { (id, c) -> snapshot(id, c) }
            .sortedByDescending { it.invocations }

    /**
     * Human-readable multi-line report (debug/settings screen). Tools that
     * were never invoked are simply absent.
     */
    fun report(): String {
        val all = stats()
        if (all.isEmpty()) return "no tool invocations recorded"
        return buildString {
            appendLine("Tool usage (${all.size} tools invoked):")
            all.forEach { appendLine("- ${it.summary()}") }
        }
    }

    /** Total invocations across all tools. */
    fun totalInvocations(): Int =
        perTool.values.sumOf { it.invocations.get() }

    /** Forget everything (test reset / session boundary). */
    fun reset() = perTool.clear()

    private fun counters(toolId: String): Counters =
        perTool.computeIfAbsent(toolId) { Counters() }

    private fun snapshot(id: String, c: Counters): ToolUsageStat = ToolUsageStat(
        toolId = id,
        invocations = c.invocations.get(),
        successes = c.successes.get(),
        failures = c.failures.get(),
        totalDurationMs = c.totalDurationNanos.get() / 1_000_000,
        lastError = c.lastError.firstOrNull(),
        lastUsedAtEpochMs = c.lastUsedAtEpochMs.get()
    )

    private companion object {
        const val MAX_ERROR_TEXT = 200
    }
}
