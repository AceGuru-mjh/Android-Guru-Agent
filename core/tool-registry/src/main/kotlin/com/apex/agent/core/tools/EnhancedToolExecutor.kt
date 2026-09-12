package com.apex.agent.core.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * # Tool System v3 — Enhanced Execution Pipeline
 *
 * `DefaultToolExecutor` (v2) wires lookup → gate → schema → run → stats.
 * v3 wraps the same spine with the production hardening every mature
 * agent runtime ships (verified across MCP spec, LangGraph ToolNode,
 * Anthropic computer-use, AndroidWorld):
 *
 * ```
 * lookup ─▶ gate ─▶ schema ─▶ rate-limit ─▶ breaker ─▶ policy(timeout)
 *   │                                              │          │
 *   │ trace span per attempt ◀─────────────────────┴──────────┘
 *   ▼
 * run ─▶ [transient failure & retrySafe & attempts left]
 *          └─▶ jittered backoff ─▶ retry (new span, breaker informed)
 * ```
 *
 * Every stage degrades to v2 behaviour when its component is absent
 * (null policy resolver ⇒ no timeout/retry; null breaker ⇒ no
 * short-circuit; null tracer ⇒ no spans) — the same opt-in philosophy v2
 * used for gate/tracker, so existing DI graphs and tests keep working
 * while the app adopts the new layers one by one.
 *
 * **Retry safety**: auto-retry re-sends the same payload, so it is
 * restricted to tools whose [ToolAnnotations.retrySafe] holds AND whose
 * failure classifies as transient ([RetryClassifier]). Retrying a
 * permission denial pesters the user; re-running a failed destructive
 * step risks double-deleting; both are terminal by construction.
 *
 * **Streaming**: [executeStream] gets timeout + breaker + tracing but NOT
 * auto-retry — a stream that already emitted output cannot be replayed
 * without duplicating side effects the model saw. (Anthropic's batch
 * runner has the same asymmetry: it never re-runs a partially executed
 * action array.)
 */
class EnhancedToolExecutor(
    private val registry: ToolRegistry,
    private val gate: ToolExecutionGate? = null,
    private val usageTracker: ToolUsageTracker? = null,
    private val schemaValidation: Boolean = true,
    private val policyResolver: ToolRunPolicyResolver? = null,
    private val rateLimiter: ToolRateLimiter? = null,
    private val breaker: ToolCircuitBreaker? = null,
    private val traceRecorder: ToolTraceRecorder? = null
) : ToolExecutor {

    private val pipeline = ToolExecutionPipeline(registry, gate, schemaValidation)
    private val internalRateLimiter = rateLimiter ?: ToolRateLimiter()

    override suspend fun execute(toolId: String, arguments: String): String {
        val tool = registry.getTool(toolId)
            ?: return ToolExecutionPipeline.notFoundMessage(toolId, registry)

        val policy = policyResolver?.resolve(tool) ?: ToolRunPolicy.legacy()
        val annotations = tool.metadata.annotations

        // ── Rate limit (fail fast — a runaway loop must not queue up) ──
        rateLimitOrReturn(toolId, policy)?.let { denied ->
            recordSideChannelDenial(toolId, arguments, denied, "rate_limited")
            return denied
        }

        // ── Circuit breaker ──
        val verdict = breaker?.check(toolId)
        if (verdict is ToolCircuitBreaker.BreakerVerdict.Deny) {
            val message = breakerDenyMessage(toolId, verdict)
            recordSideChannelDenial(toolId, arguments, message, "breaker")
            return message
        }

        // ── Attempt loop. Gate + schema re-run on every attempt: a denial
        //    granted between attempts must stop the loop, not slip through.
        //    (Pre-check failure is terminal — argument errors cannot be
        //    fixed by re-sending the same payload.) ──
        val invocation = usageTracker?.begin(toolId)
        var lastResult: String? = null
        var attempt = 0

        while (attempt < policy.totalAttempts) {
            val span = traceRecorder?.begin(toolId, arguments, attempt = attempt + 1)

            val pre = pipeline.preCheck(toolId, arguments)
            if (pre is ToolExecutionPipeline.PreCheck.Failed) {
                traceRecorder?.completeFailure(span, errorSlugOf(pre.message))
                usageTracker?.failure(invocation, pre.message)
                return pre.message
            }
            val readyTool = (pre as ToolExecutionPipeline.PreCheck.Ready).tool

            val result = try {
                if (policy.timeoutMs > 0) {
                    withTimeout(policy.timeoutMs) { readyTool.execute(arguments) }
                } else {
                    readyTool.execute(arguments)
                }
            } catch (e: TimeoutCancellationException) {
                // NOTE: TimeoutCancellationException extends
                // CancellationException — this catch MUST stay first, or
                // the generic cancel branch below would swallow the
                // tool-timeout and rethrow it as user cancellation.
                timeoutResult(policy.timeoutMs)
            } catch (e: CancellationException) {
                traceRecorder?.completeFailure(span, "cancelled")
                usageTracker?.failure(invocation, "cancelled")
                throw e
            } catch (e: IOException) {
                ioFailureResult(toolId, e)
            } catch (e: SecurityException) {
                securityResult(toolId, e)
            } catch (e: Throwable) {
                crashResult(toolId, e)
            }

            lastResult = result

            if (!result.startsWith("Error")) {
                traceRecorder?.complete(span)
                usageTracker?.success(invocation)
                breaker?.recordSuccess(toolId)
                return result
            }

            // Failed attempt: classify, trace, inform breaker.
            traceRecorder?.completeFailure(span, errorSlugOf(result))
            breaker?.recordFailure(toolId, result)

            val retryable = annotations.retrySafe &&
                RetryClassifier.classify(result) == RetryClassifier.Verdict.Retryable
            if (!retryable || attempt + 1 >= policy.totalAttempts) {
                usageTracker?.failure(invocation, result)
                return result
            }

            val delayMs = policy.retryDelayMs(attempt)
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            attempt += 1
        }

        // Unreachable in practice — the loop returns on its last attempt.
        usageTracker?.failure(invocation, lastResult)
        return lastResult ?: "Error: execution failed: $toolId"
    }

    override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> = flow {
        val tool = registry.getTool(toolId)
            ?: run {
                emit(ToolStreamEvent.Error(ToolExecutionPipeline.notFoundMessage(toolId, registry)))
                return@flow
            }

        val policy = policyResolver?.resolve(tool) ?: ToolRunPolicy.legacy()

        rateLimitOrReturn(toolId, policy)?.let { denied ->
            emit(ToolStreamEvent.Error(denied))
            return@flow
        }

        val verdict = breaker?.check(toolId)
        if (verdict is ToolCircuitBreaker.BreakerVerdict.Deny) {
            emit(ToolStreamEvent.Error(breakerDenyMessage(toolId, verdict)))
            return@flow
        }

        // Capture the flow's emit as a plain suspend function so the
        // timeout wrapper can call it without extension-receiver tricks.
        val emitFn: suspend (ToolStreamEvent) -> Unit = { event -> emit(event) }
        val span = traceRecorder?.begin(toolId, arguments)
        val invocation = usageTracker?.begin(toolId)

        try {
            if (policy.timeoutMs > 0) {
                withTimeout(policy.timeoutMs) {
                    emitStream(emitFn, toolId, tool, arguments, invocation)
                }
            } else {
                emitStream(emitFn, toolId, tool, arguments, invocation)
            }
            traceRecorder?.complete(span)
        } catch (e: TimeoutCancellationException) {
            val message = timeoutResult(policy.timeoutMs)
            traceRecorder?.completeFailure(span, "timeout")
            breaker?.recordFailure(toolId, message)
            usageTracker?.failure(invocation, message)
            emit(ToolStreamEvent.Error(message))
        } catch (e: CancellationException) {
            traceRecorder?.completeFailure(span, "cancelled")
            usageTracker?.failure(invocation, "cancelled")
            throw e
        } catch (e: IOException) {
            val message = ioFailureResult(toolId, e)
            traceRecorder?.completeFailure(span, "io")
            breaker?.recordFailure(toolId, message)
            usageTracker?.failure(invocation, message)
            emit(ToolStreamEvent.Error(message))
        } catch (e: SecurityException) {
            val message = securityResult(toolId, e)
            traceRecorder?.completeFailure(span, "permission")
            usageTracker?.failure(invocation, message)
            emit(ToolStreamEvent.Error(message))
        } catch (e: Throwable) {
            val message = crashResult(toolId, e)
            traceRecorder?.completeFailure(span, errorSlugOf(message))
            breaker?.recordFailure(toolId, message)
            usageTracker?.failure(invocation, message)
            emit(ToolStreamEvent.Error(message))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Collect a tool's stream events, forwarding each to [emitFn]. Records
     * usage/breaker outcome from the terminal event, mirroring the plain
     * path's accounting (structured tools' "Error:" strings count).
     */
    private suspend fun emitStream(
        emitFn: suspend (ToolStreamEvent) -> Unit,
        toolId: String,
        tool: AgentTool,
        arguments: String,
        invocation: ToolInvocation?
    ) {
        if (tool is StreamingAgentTool) {
            var sawError = false
            var lastError: String? = null
            tool.executeStream(arguments).collect { event ->
                if (event is ToolStreamEvent.Error) {
                    sawError = true
                    lastError = event.message
                }
                emitFn(event)
            }
            if (invocation != null) {
                if (sawError) {
                    usageTracker?.failure(invocation, lastError)
                    breaker?.recordFailure(toolId, lastError)
                } else {
                    usageTracker?.success(invocation)
                    breaker?.recordSuccess(toolId)
                }
            }
        } else {
            val result = tool.execute(arguments)
            if (invocation != null) {
                if (result.startsWith("Error")) {
                    usageTracker?.failure(invocation, result)
                    breaker?.recordFailure(toolId, result)
                } else {
                    usageTracker?.success(invocation)
                    breaker?.recordSuccess(toolId)
                }
            }
            if (result.startsWith("Error")) {
                emitFn(ToolStreamEvent.Error(result))
            } else {
                if (result.isNotEmpty()) emitFn(ToolStreamEvent.Output(result))
                emitFn(ToolStreamEvent.Complete(result))
            }
        }
    }

    /** Consume one rate-limit token; null when granted, error text when denied. */
    private fun rateLimitOrReturn(toolId: String, policy: ToolRunPolicy): String? {
        if (policy.rateLimitPerMinute == null) return null
        return when (
            val permission = internalRateLimiter.tryAcquire(toolId, policy.rateLimitPerMinute)
        ) {
            is ToolRateLimiter.Permission.Granted -> null
            is ToolRateLimiter.Permission.Denied ->
                rateLimitMessage(toolId, policy, permission.retryInMs)
        }
    }

    /** Trace + count a denial that happened before any attempt ran. */
    private fun recordSideChannelDenial(
        toolId: String,
        arguments: String,
        message: String,
        reason: String
    ) {
        traceRecorder?.let { tracer ->
            val handle = tracer.begin(toolId, arguments)
            tracer.completeDenied(handle, reason)
        }
        usageTracker?.begin(toolId)?.let { invocation ->
            usageTracker.failure(invocation, message)
        }
    }

    private fun rateLimitMessage(toolId: String, policy: ToolRunPolicy, retryInMs: Long): String =
        "Error: invalid argument: rate limit for '$toolId' is ${policy.rateLimitPerMinute}/min; " +
            "next call possible in ${((retryInMs + 999) / 1000)}s. Stop repeating the same call — " +
            "inspect previous results and change your approach."

    private fun breakerDenyMessage(
        toolId: String,
        verdict: ToolCircuitBreaker.BreakerVerdict.Deny
    ): String = buildString {
        append("Error: execution failed: '").append(toolId).append("' is temporarily blocked ")
        append("after repeated failures (circuit breaker open, probe in ")
        append(((verdict.retryInMs + 999) / 1000)).append("s)")
        verdict.lastError?.let { append("; last error: ").append(it.take(120)) }
        append(". Do not retry immediately — use a different tool or wait, " +
            "and tell the user what failed.")
    }

    private fun timeoutResult(timeoutMs: Long): String =
        "Error: timeout: tool call exceeded ${timeoutMs}ms budget"

    private fun ioFailureResult(toolId: String, e: IOException): String =
        "Error: execution failed: I/O error in '$toolId': ${e.message ?: e::class.simpleName}"

    private fun securityResult(toolId: String, e: SecurityException): String =
        "Error: permission denied: ${e.message ?: toolId}"

    private fun crashResult(toolId: String, e: Throwable): String =
        "Error: execution failed: ${e::class.simpleName ?: "exception"} in '$toolId'" +
            ": ${e.message ?: "no message"}"

    private fun errorSlugOf(rendered: String): String? {
        if (!rendered.startsWith("Error")) return null
        val rest = rendered.removePrefix("Error:").trim()
        val slug = rest.substringBefore(':').lowercase().replace(' ', '_')
        return slug.takeIf { it.isNotBlank() && it.length <= 24 }
    }
}

/**
 * Fluent assembly for the v3 executor — keeps v2's wiring expressible
 * while making the new layers one-liners:
 *
 * ```
 * val executor = ToolExecutorBuilder(registry)
 *     .gate(riskAwareToolGate)
 *     .usageTracker(tracker)
 *     .policyResolver(DefaultToolRunPolicyResolver(mapOf(
 *         "shell_execute" to ToolRunPolicy(timeoutMs = 120_000)
 *     )))
 *     .breaker(ToolCircuitBreaker())
 *     .tracer(ToolTraceRecorder(capacity = 300))
 *     .build()
 * ```
 *
 * The builder always produces an [EnhancedToolExecutor]; with no v3
 * components attached its behaviour matches the v2 defaults (no timeout,
 * no retry, no breaker, no trace).
 */
class ToolExecutorBuilder(
    private val registry: ToolRegistry
) {
    private var gate: ToolExecutionGate? = null
    private var usageTracker: ToolUsageTracker? = null
    private var schemaValidation: Boolean = true
    private var policyResolver: ToolRunPolicyResolver? = null
    private var rateLimiter: ToolRateLimiter? = null
    private var breaker: ToolCircuitBreaker? = null
    private var tracer: ToolTraceRecorder? = null

    fun gate(gate: ToolExecutionGate) = apply { this.gate = gate }

    fun usageTracker(tracker: ToolUsageTracker) = apply { this.usageTracker = tracker }

    fun schemaValidation(enabled: Boolean) = apply { this.schemaValidation = enabled }

    fun policyResolver(resolver: ToolRunPolicyResolver) = apply { this.policyResolver = resolver }

    fun rateLimiter(limiter: ToolRateLimiter) = apply { this.rateLimiter = limiter }

    fun breaker(breaker: ToolCircuitBreaker) = apply { this.breaker = breaker }

    fun tracer(tracer: ToolTraceRecorder) = apply { this.tracer = tracer }

    fun build(): ToolExecutor = EnhancedToolExecutor(
        registry = registry,
        gate = gate,
        usageTracker = usageTracker,
        schemaValidation = schemaValidation,
        policyResolver = policyResolver,
        rateLimiter = rateLimiter,
        breaker = breaker,
        traceRecorder = tracer
    )
}
