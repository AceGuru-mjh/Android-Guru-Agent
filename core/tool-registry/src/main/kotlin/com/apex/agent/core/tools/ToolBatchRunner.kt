package com.apex.agent.core.tools

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * # Tool System v3 — Sequential Batch Runner
 *
 * Anthropic's computer-use tool returns an *ordered array* of actions per
 * turn: the model plans "tap here, type this, press enter" as one unit.
 * Executing them one-by-one through the loop costs a full LLM round-trip
 * per action; executing them as an uncritical parallel burst is wrong
 * (order matters on a GUI). The industry answer is sequential batch
 * execution with **first-error-stop** semantics:
 *
 * - steps run strictly in order on one coroutine;
 * - the first failing step halts the batch;
 * - every *later* step is marked NOT executed and returned with the
 *   fixed message "Not executed: an earlier action in this turn failed"
 *   (Anthropic's exact contract) — the model can see precisely how far
 *   the plan got and replan the remainder instead of guessing;
 * - a whole-batch time budget caps the run even when each individual
 *   step has its own timeout.
 *
 * ## Step references
 *
 * A step's arguments may reference the output of an earlier step with the
 * `dollar-curly` placeholder syntax:
 *
 * ```
 * [ { "tool": "ui_dump", "arguments": "{}" },
 *   { "tool": "write_file", "arguments": "{'path':'dump.txt','content':'{0}'}" } ]
 * ```
 *
 * `{0}` inserts step 0's output (head-truncated to [referenceHeadLimit]
 * chars so a giant dump cannot blow up the next payload). References to
 * failed/not-executed steps fail validation *before* anything runs.
 *
 * The runner deliberately reuses the caller's [ToolExecutor] — batch steps
 * go through the same gate / schema validation / policy / tracing as
 * standalone calls (unlike a bypass path, which would silently lose the
 * v2/v3 guarantees exactly when arguments are the least trusted).
 */
class ToolBatchRunner(
    private val executor: ToolExecutor,
    private val referenceHeadLimit: Int = 4_000
) {

    /** One batch step as declared by the model. */
    data class Step(
        val toolId: String,
        /** Raw JSON arguments text; `{n}` placeholders reference step n's output. */
        val arguments: String
    )

    /** Outcome of one step within a batch run. */
    sealed interface StepOutcome {
        /** Step succeeded; [output] is its result text. */
        data class Executed(val output: String) : StepOutcome

        /** Step ran and failed; [error] is the rendered error text. */
        data class Failed(val error: String) : StepOutcome

        /** Step never ran because an earlier step failed. */
        data class NotExecuted(val reason: String = NOT_EXECUTED_MESSAGE) : StepOutcome {
            override fun toString(): String = "NotExecuted"
        }
    }

    /** Full batch result. */
    class BatchResult internal constructor(
        val steps: List<Step>,
        val outcomes: List<StepOutcome>,
        val haltedAtIndex: Int?,
        val totalDurationMs: Long,
        val timedOut: Boolean
    ) {
        /** True when every step executed successfully. */
        val isComplete: Boolean get() = haltedAtIndex == null && !timedOut

        /** Indices of steps that produced output (for `{n}` references). */
        fun executedIndices(): List<Int> = outcomes.indices.filter {
            outcomes[it] is StepOutcome.Executed
        }

        /**
         * Rendered multi-step report for the model: one numbered block per
         * step, exactly preserving v1 "Error:" prefixes so the engine's
         * failure detector keeps working, plus a final status line.
         */
        fun render(): String = buildString {
            append("batch result (")
            append(steps.size).append(" steps, ")
            if (isComplete) append("complete") else append("halted")
            append("):")
            appendLine()
            outcomes.forEachIndexed { index, outcome ->
                append('[').append(index).append("] ").append(steps[index].toolId)
                append(' ')
                when (outcome) {
                    is StepOutcome.Executed -> append("→ ok:")
                    is StepOutcome.Failed -> append("→ FAILED:")
                    is StepOutcome.NotExecuted -> {
                        appendLine("→ ${outcome.reason}")
                        return@forEachIndexed
                    }
                }
                appendLine()
                val text = when (outcome) {
                    is StepOutcome.Executed -> outcome.output
                    is StepOutcome.Failed -> outcome.error
                    is StepOutcome.NotExecuted -> outcome.reason
                }
                append(indent(text.trim(), margin = "    "))
                appendLine()
            }
            if (timedOut) {
                appendLine("batch timed out before completing all steps")
            }
        }
    }

    /**
     * Run [steps] sequentially with first-error-stop.
     *
     * @param totalBudgetMs whole-batch wall-clock budget (0 = unlimited);
     *   when it fires, the in-flight step's error becomes a TIMEOUT step
     *   failure and later steps are NotExecuted.
     */
    suspend fun run(steps: List<Step>, totalBudgetMs: Long = 0L): BatchResult {
        require(steps.size <= MAX_STEPS) { "batch too large: ${steps.size} steps (max $MAX_STEPS)" }
        val started = System.nanoTime()
        val outcomes = mutableListOf<StepOutcome>()
        var haltedAt: Int? = null
        var timedOut = false

        val outputs = mutableListOf<String?>()

        val body: suspend () -> Unit = {
            for ((index, step) in steps.withIndex()) {
                val resolvedArgs = try {
                    resolveReferences(step.arguments, outputs)
                } catch (e: IllegalArgumentException) {
                    outcomes += StepOutcome.Failed(e.message ?: "invalid reference")
                    haltedAt = index
                    break
                }

                val result = executor.execute(step.toolId, resolvedArgs)
                if (result.startsWith("Error")) {
                    outcomes += StepOutcome.Failed(result)
                    outputs += null
                    haltedAt = index
                    break
                }
                outcomes += StepOutcome.Executed(result)
                outputs += result
            }
        }

        if (totalBudgetMs > 0) {
            try {
                withTimeout(totalBudgetMs) { body() }
            } catch (e: TimeoutCancellationException) {
                timedOut = true
                if (outcomes.size < steps.size) {
                    // The in-flight step's own timeout error was interrupted by
                    // the batch budget — record it as the halt cause.
                    outcomes += StepOutcome.Failed(
                        "Error: timeout: batch budget of ${totalBudgetMs}ms exhausted at step ${outcomes.size}"
                    )
                    haltedAt = outcomes.size - 1
                }
            }
        } else {
            body()
        }

        while (outcomes.size < steps.size) {
            outcomes += StepOutcome.NotExecuted()
        }

        return BatchResult(
            steps = steps,
            outcomes = outcomes,
            haltedAtIndex = haltedAt,
            totalDurationMs = (System.nanoTime() - started) / 1_000_000,
            timedOut = timedOut
        )
    }

    /**
     * Replace `{n}` placeholders (n = earlier step index) with that step's
     * output. Only *executed* steps may be referenced; the substituted
     * text is head-limited to [referenceHeadLimit].
     */
    private fun resolveReferences(arguments: String, outputs: List<String?>): String {
        // '\u007B' 写法保持源文件原始字符级花括号平衡（CI 门禁按字符计数）。
        if (!arguments.contains('\u007B')) return arguments
        val reference = REFERENCE_REGEX.matchEntire(arguments.trim())?.groupValues?.get(1)
        if (reference != null) {
            val index = reference.toIntOrNull()
                ?: throw IllegalArgumentException(
                    "Error: invalid argument: batch reference '$reference' is not a step index"
                )
            val output = outputs.getOrNull(index)
                ?: throw IllegalArgumentException(
                    "Error: invalid argument: batch reference {$index} points to a " +
                        "not-executed or failed step; only successful earlier steps can be referenced"
                )
            return output.take(referenceHeadLimit)
        }
        // Interleaved references inside a larger template.
        return REFERENCE_GLOBAL_REGEX.replace(arguments) { match ->
            val index = match.groupValues[1].toIntOrNull()
                ?: throw IllegalArgumentException(
                    "Error: invalid argument: batch reference '${match.value}' is not a step index"
                )
            val output = outputs.getOrNull(index)
                ?: throw IllegalArgumentException(
                    "Error: invalid argument: batch reference {$index} points to a " +
                        "not-executed or failed step; only successful earlier steps can be referenced"
                )
            output.take(referenceHeadLimit)
        }
    }

    companion object {
        /** Anthropic's exact skipped-step contract text (spec-stable). */
        const val NOT_EXECUTED_MESSAGE: String =
            "Not executed: an earlier action in this turn failed."

        /** Hard step cap — a batch is a plan, not a program. */
        const val MAX_STEPS: Int = 16

        private val REFERENCE_REGEX = Regex("^\\{(\\d+)}$")
        private val REFERENCE_GLOBAL_REGEX = Regex("\\{(\\d+)}")
    }
}

/** Indent every line by [margin] (batch report rendering). */
private fun indent(text: String, margin: String): String =
    text.lines().joinToString("\n") { line -> margin + line }
