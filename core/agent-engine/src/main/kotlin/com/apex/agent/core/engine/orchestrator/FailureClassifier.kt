package com.apex.agent.core.engine.orchestrator

/**
 * A68.2 — Failure classification.
 *
 * Classifies a failed tool call into a [FailureClass] so the retry policy
 * (see [RetryPolicy]) and the recovery planner can react appropriately:
 *
 * - [TRANSIENT] — likely to succeed on retry (network blips, 5xx, 429,
 *   connection reset, "temporarily unavailable").
 * - [TIMEOUT] — the tool exceeded its per-tool deadline. Often worth one
 *   bounded retry (a cold start / one-off stall), but not worth hammering.
 * - [PERMISSION] — the action was denied (privilege gate, Shizuku not
 *   granted, a11y disabled, file EACCES). Retrying the *same* call can
 *   never succeed; the correct reaction is to surface the reason to the
 *   LLM (or the user), not to retry.
 * - [FATAL] — deterministic failure (invalid arguments, JSON parse error,
 *   unknown tool, assertion-like errors). Retrying the same call is
 *   pointless; feed the error back to the LLM so it can change approach.
 *
 * Classification is heuristic (message + exception-type pattern matching).
 * It never throws — an unclassifiable failure is [FATAL] by default, which
 * is the conservative "don't retry, let the LLM replan" behaviour.
 */
enum class FailureClass {
    TRANSIENT,
    TIMEOUT,
    PERMISSION,
    FATAL
}

/** Everything the classifier needs about one failed attempt. */
data class ToolFailure(
    val toolName: String,
    val callId: String,
    /** Human-readable error text (message from ToolStreamEvent.Error, or exception message). */
    val errorMessage: String,
    /** True when the attempt was killed by the orchestrator's per-tool withTimeout. */
    val timedOut: Boolean = false,
    /** The exception thrown by the executor, if any (Error events have none). */
    val exception: Throwable? = null
)

/**
 * Heuristic failure classifier.
 *
 * Pure Kotlin, side-effect free — trivially unit-testable.
 */
class FailureClassifier {

    /**
     * Classify [failure] into a [FailureClass].
     *
     * Order of precedence:
     * 1. Timeout flag ([ToolFailure.timedOut] / TimeoutCancellationException) → [FailureClass.TIMEOUT]
     * 2. Permission-ish exception types → [FailureClass.PERMISSION]
     * 3. Permission-ish message patterns → [FailureClass.PERMISSION]
     * 4. **Rate-limit guard (P2 修复) → [FailureClass.FATAL]**：本应用
     *    执行器自身的限流拒绝（"Error: rate limited: …"）不重试 ——
     *    引擎退避上限 30s < 限流窗口 60s，重放同一调用只会再撞一次
     *    限流白烧预算；文案已要求模型换路/等冷却。
     * 5. Transient message patterns → [FailureClass.TRANSIENT]
     * 6. Transient-ish exception types (IOException family) → [FailureClass.TRANSIENT]
     * 7. Anything else → [FailureClass.FATAL]
     */
    fun classify(failure: ToolFailure): FailureClass {
        // 1. Timeout — explicit flag wins (set by the orchestrator's withTimeout catch),
        //    exception type as fallback (classifier may be used standalone in tests).
        if (failure.timedOut || failure.exception is kotlinx.coroutines.TimeoutCancellationException) {
            return FailureClass.TIMEOUT
        }

        // 2. Permission exception types (AccessControlException is a
        //    SecurityException subclass — one check covers both).
        val exception = failure.exception
        if (exception is SecurityException) {
            return FailureClass.PERMISSION
        }

        // 3–6. Message / exception-type heuristics (case-insensitive)
        val message = buildString {
            append(failure.errorMessage)
            exception?.let { append(" | ").append(it::class.java.simpleName) }
        }.lowercase()

        // 4. Rate-limit guard（先于 transient 匹配）—— 见方法 KDoc。
        if (RATE_LIMIT_GUARD_PATTERNS.any { message.contains(it) }) return FailureClass.FATAL

        if (PERMISSION_PATTERNS.any { message.contains(it) }) return FailureClass.PERMISSION
        if (TRANSIENT_PATTERNS.any { message.contains(it) }) return FailureClass.TRANSIENT

        if (exception is java.io.IOException) return FailureClass.TRANSIENT

        return FailureClass.FATAL
    }

    companion object {
        /**
         * Substrings (already lowercased) that indicate a retryable,
         * transient failure. Tuned for the Android/Agent context:
         * network stacks (OkHttp), LLM providers (429/503), binder hiccups.
         */
        internal val TRANSIENT_PATTERNS = listOf(
            // network
            "network", "connection reset", "connection refused", "connection closed",
            "broken pipe", "econnreset", "econnrefused", "ehostunreach", "enetunreach",
            "socket timeout", "timed out", "timeout", "temporary failure", "temporarily unavailable",
            "try again", "eagain",
            // HTTP-ish transient statuses
            "429", "too many requests", "502", "bad gateway", "503", "service unavailable",
            "504", "gateway timeout",
            // runtime hiccups
            "eintr", "interrupted", "rate limit", "rate-limit", "overloaded", "busy"
        )

        /** Substrings indicating the call was DENIED — retrying cannot help. */
        internal val PERMISSION_PATTERNS = listOf(
            "permission denied", "permission", "eacces", "eperm",
            "unauthorized", "401", "403", "forbidden", "access denied",
            "not granted", "shizuku", "privilege", "securityexception"
        )

        /**
         * P2 修复（限流守卫）：本应用执行器的限流拒绝签名 —— 与
         * EnhancedToolExecutor.rateLimitMessage 同源。命中即 FATAL：
         * 重放同一调用只会再撞限流（引擎退避上限 30s < 窗口 60s）。
         * 精确到 "error: rate limited" 前缀，避免误伤外部 API 的
         * "rate limit exceeded"（那类是 TRANSIENT，值得退避重试）。
         */
        internal val RATE_LIMIT_GUARD_PATTERNS = listOf(
            "error: rate limited"
        )
    }
}
