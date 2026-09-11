package com.apex.agent.core.tools

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * # Tool System v3 — Per-Tool Run Policy (timeout / retry / rate limit)
 *
 * Industry baseline (verified against MCP spec §security, LangGraph
 * ToolNode, AndroidWorld, Anthropic computer-use):
 *
 * - **Timeout**: the MCP spec makes client-side tool-call timeouts a MUST;
 *   a hung `http_request` on a dead network otherwise hangs the whole agent
 *   loop, because v1/v2 execute with no deadline at all.
 * - **Retry**: LangGraph's `wrap_tool_call` interceptor retries
 *   transient failures; AutoGPT classifies errors into retryable
 *   (input) vs terminal (execution). The executor retries *the same
 *   payload* — that is only safe for transient failures of retry-safe
 *   tools (see [ToolAnnotations.retrySafe]).
 * - **Rate limit**: a model stuck in a failure loop will happily call the
 *   same tool 50 times in a minute, burning tokens and battery. A
 *   per-tool token bucket caps the damage (the loop still fails, but
 *   cheaply and with an actionable message).
 *
 * A [ToolRunPolicy] bundles the three knobs for one tool id; a
 * [ToolRunPolicyResolver] decides the policy per tool (annotations-driven
 * defaults + explicit overrides), and [ToolRateLimiter] implements the
 * bucket. The executor ([EnhancedToolExecutor]) enforces all of it.
 *
 * @param timeoutMs wall-clock budget for ONE attempt (including gate and
 *   streaming collection). 0 disables the deadline (v1/v2 semantics —
 *   only opt-in via resolver override).
 * @param maxRetries extra attempts after the first failure of a transient
 *   error on a retry-safe tool. 0 = never retry.
 * @param baseRetryDelayMs first retry waits a jittered multiple of this;
 *   each subsequent retry doubles the base (exponential backoff with full
 *   jitter, AWS-style: delay = random(0 .. base * 2^attempt)).
 * @param rateLimitPerMinute max starts per tool per minute (null =
 *   unlimited). Calls above the limit are rejected fast with a
 *   rate-limit error, not queued.
 */
data class ToolRunPolicy(
    val timeoutMs: Long = 0L,
    val maxRetries: Int = 0,
    val baseRetryDelayMs: Long = 0L,
    val rateLimitPerMinute: Int? = null
) {

    /** Effective attempts including the first one (1 + maxRetries). */
    val totalAttempts: Int get() = 1 + maxRetries

    /**
     * Jittered backoff before attempt [attemptIndex] (0-based retry
     * number, i.e. the wait before the 2nd try is index 0).
     *
     * Full jitter ("equal jitter without the floor"): the cap doubles per
     * attempt, the actual delay is uniform in 0..cap. Full jitter avoids
     * the synchronized-retry thundering herd when several agent loops hit
     * the same recovering backend.
     */
    fun retryDelayMs(attemptIndex: Int, random: Random = Random.Default): Long {
        if (baseRetryDelayMs <= 0) return 0L
        val cappedAttempt = attemptIndex.coerceIn(0, 16)
        val cap = baseRetryDelayMs * (1L shl cappedAttempt)
        return (random.nextLong(cap + 1))
    }

    companion object {
        /** No timeout, no retry, no limit — exactly v1/v2 behaviour. */
        @JvmStatic
        fun legacy(): ToolRunPolicy = ToolRunPolicy()

        /** Sensible read-tool policy: quick timeout, one retry, generous limit. */
        @JvmStatic
        fun quickRead(): ToolRunPolicy = ToolRunPolicy(
            timeoutMs = 30_000L,
            maxRetries = 1,
            baseRetryDelayMs = 400L,
            rateLimitPerMinute = 120
        )

        /** Network/open-world policy: longer timeout, two retries, tighter limit. */
        @JvmStatic
        fun network(): ToolRunPolicy = ToolRunPolicy(
            timeoutMs = 90_000L,
            maxRetries = 2,
            baseRetryDelayMs = 800L,
            rateLimitPerMinute = 30
        )

        /** Mutating-tool policy: no blind retry, moderate timeout. */
        @JvmStatic
        fun mutating(): ToolRunPolicy = ToolRunPolicy(
            timeoutMs = 60_000L,
            maxRetries = 0
        )
    }
}

/**
 * Decides the [ToolRunPolicy] for a prospective invocation.
 *
 * [DefaultToolRunPolicyResolver] resolves from annotations + id families,
 * with an explicit per-id override map winning over everything (the app
 * can pin `shell_execute` to a longer timeout without touching inference).
 */
interface ToolRunPolicyResolver {
    fun resolve(tool: AgentTool): ToolRunPolicy
}

/**
 * Annotation/id-driven default resolver.
 *
 * Resolution order:
 * 1. explicit [overrides] entry for the tool id (admin pin);
 * 2. `ask_user*` → no timeout (the user might take minutes to answer) and
 *    no retry (re-asking after a denial is pestering);
 * 3. open-world tools (web/MCP/GitHub) → [ToolRunPolicy.network];
 * 4. read-only / idempotent local tools → [ToolRunPolicy.quickRead];
 * 5. everything mutating → [ToolRunPolicy.mutating].
 */
class DefaultToolRunPolicyResolver(
    private val overrides: Map<String, ToolRunPolicy> = emptyMap()
) : ToolRunPolicyResolver {

    override fun resolve(tool: AgentTool): ToolRunPolicy {
        overrides[tool.id]?.let { return it }

        if (tool.id == "ask_user" || tool.id == "ask_user_choice") {
            return ToolRunPolicy(timeoutMs = 0L, maxRetries = 0)
        }

        val annotations = tool.metadata.annotations
        return when {
            annotations.openWorldHint -> ToolRunPolicy.network()
            annotations.retrySafe -> ToolRunPolicy.quickRead()
            else -> ToolRunPolicy.mutating()
        }
    }
}

/**
 * Failure classification for executor-level auto-retry.
 *
 * The same payload is re-sent on retry, so only failures that (a) can
 * resolve on their own AND (b) come from a retry-safe tool qualify.
 * Argument errors never qualify — the model must fix the payload; the
 * executor re-sending it verbatim just burns the rate budget.
 */
object RetryClassifier {

    /** Outcome of classifying one failed attempt. */
    sealed interface Verdict {
        /** Retry with the same payload is safe and may help. */
        object Retryable : Verdict {
            override fun toString(): String = "Retryable"
        }

        /** Do not retry; the result stands (model-facing error included). */
        object Terminal : Verdict {
            override fun toString(): String = "Terminal"
        }
    }

    /**
     * Classify a failure rendered through the v1 string protocol.
     *
     * Terminal prefixes (model must change *something*):
     * - `permission denied` — a human said no; retrying pesters.
     * - `sandbox_violation` — the path escaped; same payload escapes again.
     * - `invalid json` / `missing argument` / `invalid argument` /
     *   `not found` — payload is wrong or target absent.
     * - `cancelled` — the user aborted the loop.
     *
     * Retryable: everything else (`timeout`, `execution failed`, I/O
     * flakes…) — subject to [ToolAnnotations.retrySafe] on the tool.
     */
    fun classify(renderedFailure: String): Verdict {
        val text = renderedFailure.trim()
        val terminalPrefixes = listOf(
            "Error: permission denied",
            "Error: sandbox violation",
            "Error: invalid json",
            "Error: missing argument",
            "Error: invalid argument",
            "Error: not found",
            "Error: cancelled"
        )
        return if (terminalPrefixes.any { text.startsWith(it) }) {
            Verdict.Terminal
        } else {
            Verdict.Retryable
        }
    }
}

/**
 * Token-bucket rate limiter, one bucket per tool id.
 *
 * Properties (classic token bucket, refill-by-timestamp):
 * - capacity == rateLimitPerMinute (burst of up to one full minute's
 *   allowance, then sustained refill);
 * - refill is continuous (tokens-per-ms), so the bucket never "ticks";
 * - lock-free per-tool state (single compareAndSet loop on a boxed
 *   snapshot), safe under concurrent executor calls.
 *
 * Calls that would exceed the allowance are rejected — NOT queued — so a
 * runaway loop surfaces immediately as an error the model can read,
 * instead of silently piling up latency.
 */
class ToolRateLimiter {

    private class Bucket(
        @Volatile var tokens: Double,
        @Volatile var lastRefillMs: Long
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Result of one permission request. */
    sealed interface Permission {
        /** Granted — proceed with the call. */
        object Granted : Permission {
            override fun toString(): String = "Granted"
        }

        /** Denied — [retryInMs] is when one token will be available. */
        data class Denied(val retryInMs: Long) : Permission
    }

    /**
     * Try to consume one token for [toolId] under [limitPerMinute].
     *
     * @param limitPerMinute null disables limiting for this tool.
     * @param nowMs injectable clock (tests).
     */
    fun tryAcquire(
        toolId: String,
        limitPerMinute: Int?,
        nowMs: Long = System.currentTimeMillis()
    ): Permission {
        if (limitPerMinute == null || limitPerMinute <= 0) return Permission.Granted

        val bucket = buckets.computeIfAbsent(toolId) {
            Bucket(tokens = limitPerMinute.toDouble(), lastRefillMs = nowMs)
        }

        while (true) {
            val nowTokens = bucket.tokens
            val nowStamp = bucket.lastRefillMs
            val elapsedMs = (nowMs - nowStamp).coerceAtLeast(0)
            val tokensPerMs = limitPerMinute / 60_000.0
            val refilled = (nowTokens + elapsedMs * tokensPerMs).coerceAtMost(limitPerMinute.toDouble())

            if (refilled >= 1.0) {
                if (updateBucket(bucket, nowTokens, nowStamp, refilled - 1.0, nowMs)) {
                    return Permission.Granted
                }
            } else {
                val missing = 1.0 - refilled
                val retryInMs = (missing / tokensPerMs).toLong() + 1
                return Permission.Denied(retryInMs)
            }
        }
    }

    /** Reset one tool's bucket (settings UI / test reset). */
    fun reset(toolId: String) {
        buckets.remove(toolId)
    }

    /** Reset all buckets. */
    fun resetAll() = buckets.clear()

    /** Snapshot of remaining tokens (diagnostics only — racy read). */
    fun remainingTokens(toolId: String): Double? = buckets[toolId]?.tokens

    /** Compare-and-swap style guarded update (synchronized on the bucket). */
    private fun updateBucket(
        bucket: Bucket,
        expectTokens: Double,
        expectStamp: Long,
        newTokens: Double,
        newStamp: Long
    ): Boolean = synchronized(bucket) {
        if (bucket.tokens == expectTokens && bucket.lastRefillMs == expectStamp) {
            bucket.tokens = newTokens
            bucket.lastRefillMs = newStamp
            true
        } else {
            false
        }
    }
}

/**
 * Suspends until at least one token is available or [maxWaitMs] elapses.
 *
 * Used by the batch runner where sequential steps SHOULD pace themselves
 * (the model asked for a batch; failing the batch on step 2's rate limit
 * is worse than waiting a second). The plain executor path uses
 * [ToolRateLimiter.tryAcquire] directly and fails fast instead.
 */
internal suspend fun ToolRateLimiter.awaitAcquire(
    toolId: String,
    limitPerMinute: Int?,
    maxWaitMs: Long,
    pollMs: Long = 50L
): ToolRateLimiter.Permission {
    if (limitPerMinute == null) return ToolRateLimiter.Permission.Granted
    val deadline = System.currentTimeMillis() + maxWaitMs
    var backoff = pollMs
    while (true) {
        when (val permission = tryAcquire(toolId, limitPerMinute)) {
            is ToolRateLimiter.Permission.Granted -> return permission
            is ToolRateLimiter.Permission.Denied -> {
                if (System.currentTimeMillis() + permission.retryInMs > deadline) {
                    return permission
                }
                delay(minOf(permission.retryInMs, backoff))
                backoff = (backoff * 2).coerceAtMost(1_000L)
            }
        }
    }
}
