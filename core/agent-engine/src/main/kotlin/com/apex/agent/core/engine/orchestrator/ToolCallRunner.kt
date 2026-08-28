package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.withTimeout

/**
 * A68.2 — Per-attempt listener callbacks from [ToolCallRunner].
 *
 * Both callbacks default to no-ops; the orchestrator overrides them to
 * emit lifecycle events. They may be invoked from PARALLEL worker
 * coroutines (A68.3) — implementations must be thread-safe (the
 * orchestrator's SharedFlow emit is).
 */
interface AttemptListener {
    /** A failed attempt will be retried after [backoffMs]. */
    suspend fun onRetry(
        callId: String,
        toolName: String,
        failedAttempt: Int,
        nextAttempt: Int,
        failureClass: FailureClass,
        backoffMs: Long
    ) {
    }

    /** An attempt was killed by the per-tool timeout (may still be retried). */
    suspend fun onAttemptTimeout(callId: String, toolName: String) {
    }
}

/**
 * A68.2 — Runs ONE logical tool call to completion.
 *
 * Encapsulates the per-attempt mechanics shared by the serial and the
 * parallel (A68.3) execution paths:
 *
 * ```
 * attempt 1..N:
 *   withTimeout(toolTimeoutMs) { collect executeStream events }
 *       ├── ToolStreamEvent.Output   → AgentEvent.ToolOutputChunk + append
 *       ├── ToolStreamEvent.Progress → AgentEvent.ToolProgress
 *       ├── ToolStreamEvent.Complete → append (if nothing streamed)
 *       └── ToolStreamEvent.Error    → append + mark failure
 *   on failure → FailureClassifier.classify → RetryPolicy.shouldRetry
 *       ├── Retry → lifecycle callback + backoff delay → next attempt
 *       └── Stop  → final failed outcome (+ retry-exhausted note)
 * ```
 *
 * The runner does NOT emit [AgentEvent.ToolCallStart] / [AgentEvent.ToolCallComplete] —
 * those bracket the logical call and stay under the caller's control
 * (the serial path emits them inline; the parallel path routes them
 * through the shared event channel).
 *
 * [eventSink] must be safe to call sequentially from this runner (it is
 * the outer FlowCollector on the serial path, a channel-backed collector
 * per parallel worker).
 */
class ToolCallRunner(
    private val toolExecutor: ToolExecutor,
    private val classifier: FailureClassifier = FailureClassifier(),
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    private val retryBudget: RetryBudget = RetryBudget(retryPolicy.retryBudget)
) {

    /**
     * Execute [call] with timeout + retry. Returns the final outcome —
     * never throws except [CancellationException] for abort/cancel.
     */
    suspend fun run(
        call: ToolCall,
        toolTimeoutMs: Long,
        eventSink: suspend (AgentEvent) -> Unit,
        attemptListener: AttemptListener? = null
    ): ToolCallOutcome {
        val startMs = System.currentTimeMillis()
        var attempt = 0
        var lastFailure: ToolFailure? = null
        var lastClass: FailureClass? = null

        while (true) {
            attempt++
            val outputBuilder = StringBuilder()
            var success = true
            var failureException: Throwable? = null
            var timedOut = false

            try {
                if (toolTimeoutMs > 0L) {
                    withTimeout(toolTimeoutMs) {
                        collectStream(call, outputBuilder, eventSink) { success = it }
                    }
                } else {
                    collectStream(call, outputBuilder, eventSink) { success = it }
                }
            } catch (e: TimeoutCancellationException) {
                // Per-attempt timeout (or an enclosing timeout firing —
                // bounded either way; see A68.1 semantics preserved here).
                timedOut = true
                success = false
                outputBuilder.append("Error: tool '${call.name}' timed out after ${toolTimeoutMs}ms")
                attemptListener?.onAttemptTimeout(call.id, call.name)
            } catch (e: CancellationException) {
                throw e // abort / caller cancellation — never retry
            } catch (e: Throwable) {
                success = false
                failureException = e
                outputBuilder.append("Error: ${e.message ?: e::class.simpleName}")
            }

            if (success) {
                return ToolCallOutcome(
                    callId = call.id,
                    toolName = call.name,
                    arguments = call.arguments,
                    output = outputBuilder.toString(),
                    success = true,
                    durationMs = System.currentTimeMillis() - startMs,
                    attempts = attempt
                )
            }

            // Classify and decide on retry.
            lastFailure = ToolFailure(
                toolName = call.name,
                callId = call.id,
                errorMessage = outputBuilder.toString(),
                timedOut = timedOut,
                exception = failureException
            )
            lastClass = classifier.classify(lastFailure)

            val policyDecision = retryPolicy.shouldRetry(
                failureClass = lastClass!!,
                attempt = attempt,
                retriesUsed = retryBudget.usedCount
            )
            val decision: RetryPolicy.RetryDecision = if (
                policyDecision is RetryPolicy.RetryDecision.Retry &&
                // Atomic budget enforcement (parallel workers share the budget).
                !retryBudget.tryConsume()
            ) {
                RetryPolicy.RetryDecision.Stop("task retry budget exhausted")
            } else {
                policyDecision
            }

            if (decision is RetryPolicy.RetryDecision.Retry) {
                attemptListener?.onRetry(
                    callId = call.id,
                    toolName = call.name,
                    failedAttempt = attempt,
                    nextAttempt = attempt + 1,
                    failureClass = lastClass!!,
                    backoffMs = decision.delayMs
                )
                if (decision.delayMs > 0L) kotlinx.coroutines.delay(decision.delayMs)
                continue
            }

            // Final failure.
            val retryNote = if (attempt > 1) {
                "\n[orchestrator] retried ${attempt - 1} time(s); final failure class: $lastClass"
            } else null
            return ToolCallOutcome(
                callId = call.id,
                toolName = call.name,
                arguments = call.arguments,
                output = outputBuilder.toString() + (retryNote ?: ""),
                success = false,
                durationMs = System.currentTimeMillis() - startMs,
                attempts = attempt,
                failureClass = lastClass
            )
        }
    }

    /**
     * Collect one attempt's [ToolStreamEvent] flow. Mirrors A68.1's
     * `collectToolStream` semantics exactly (same event translation, same
     * output accumulation).
     */
    private suspend fun collectStream(
        call: ToolCall,
        outputBuilder: StringBuilder,
        eventSink: suspend (AgentEvent) -> Unit,
        successFlag: (Boolean) -> Unit
    ) {
        toolExecutor.executeStream(call.name, call.arguments).collect { ev ->
            when (ev) {
                is ToolStreamEvent.Output -> {
                    outputBuilder.append(ev.chunk)
                    eventSink(AgentEvent.ToolOutputChunk(call.id, ev.chunk))
                }
                is ToolStreamEvent.Progress -> {
                    eventSink(AgentEvent.ToolProgress(call.id, ev.percent, ev.message))
                }
                is ToolStreamEvent.Complete -> {
                    if (outputBuilder.isEmpty() && ev.output.isNotEmpty()) {
                        outputBuilder.append(ev.output)
                        eventSink(AgentEvent.ToolOutputChunk(call.id, ev.output))
                    }
                }
                is ToolStreamEvent.Error -> {
                    outputBuilder.append(ev.message)
                    eventSink(AgentEvent.ToolOutputChunk(call.id, ev.message))
                    successFlag(false)
                }
            }
        }
    }

    /** FlowCollector adapter that forwards emits to a suspend lambda. */
    class SinkCollector(
        private val sink: suspend (AgentEvent) -> Unit
    ) : FlowCollector<AgentEvent> {
        override suspend fun emit(value: AgentEvent) = sink(value)
    }

    companion object {
        /** Convenience factory: runner with a shared retry budget. */
        fun withDefaults(
            toolExecutor: ToolExecutor,
            retryPolicy: RetryPolicy = RetryPolicy.DEFAULT
        ): ToolCallRunner = ToolCallRunner(
            toolExecutor = toolExecutor,
            classifier = FailureClassifier(),
            retryPolicy = retryPolicy,
            retryBudget = RetryBudget(retryPolicy.retryBudget)
        )
    }
}
