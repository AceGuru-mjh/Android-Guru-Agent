package com.apex.agent.core.engine.orchestrator

/**
 * A68.2 — Recovery / re-planning.
 *
 * When the loop detector fires (or a tool exhausts its retries), the
 * orchestrator injects a *recovery prompt* into the conversation history
 * before the next LLM call. The prompt states plainly what went wrong and
 * what the LLM must do differently — this is the "recovery strategy +
 * replanning" step of the A68.2 spec: instead of failing the task (or
 * looping forever), we give the model one structured chance to change
 * approach.
 *
 * The number of recoveries per task is bounded ([maxRecoveries]); once
 * exceeded the orchestrator fails the task — a model that keeps looping
 * after N explicit interventions is not going to converge.
 */
class RecoveryPlanner(
    /** Max recovery prompts injected per task before the task fails. */
    val maxRecoveries: Int = 3
) {
    private var recoveriesUsed = 0

    /** Recoveries consumed so far in this task (for tests / progress). */
    val recoveryCount: Int get() = recoveriesUsed

    /**
     * Whether another recovery is allowed. When false, the orchestrator
     * should fail the task with a "recovery budget exhausted" error.
     */
    fun canRecover(): Boolean = recoveriesUsed < maxRecoveries

    /**
     * Build the recovery prompt for a loop signal. Consumes one recovery
     * from the budget.
     */
    fun buildLoopRecoveryPrompt(signal: LoopSignal): String {
        recoveriesUsed++
        return when (signal) {
            is LoopSignal.Repetition -> """
                [ORCHESTRATOR RECOVERY NOTICE — iteration reset required]
                You have executed the SAME tool call ${signal.repetitions} times in a row:
                  tool: ${signal.toolName}
                  arguments: ${signal.arguments.take(500)}
                Repeating an identical call is unlikely to produce a different result.
                REQUIRED ACTION — choose ONE:
                1. Change the arguments or the tool so the next attempt differs meaningfully.
                2. Inspect the previous error output and address its root cause first.
                3. If the goal is unreachable as stated, explain the blocker and finish.
                Do NOT issue the identical call again.
            """.trimIndent() + "\n(recovery ${recoveriesUsed}/$maxRecoveries)"

            is LoopSignal.Oscillation -> """
                [ORCHESTRATOR RECOVERY NOTICE — iteration reset required]
                Your recent tool calls are oscillating in a repeating pattern
                (period ${signal.period}: ${signal.pattern.joinToString(" → ")}).
                This ping-pong behaviour makes no progress toward the goal.
                REQUIRED ACTION — choose ONE:
                1. Break the cycle: pick a different strategy or a different tool.
                2. Gather missing information with a read-only tool before acting again.
                3. If the goal is unreachable as stated, explain the blocker and finish.
            """.trimIndent() + "\n(recovery ${recoveriesUsed}/$maxRecoveries)"
        }
    }

    /**
     * Build a prompt appended to a ToolResult when a tool call exhausted
     * its retry budget, making the retry history explicit to the LLM.
     */
    fun buildRetryExhaustedNote(toolName: String, attempts: Int, failureClass: FailureClass): String =
        "[orchestrator] tool '$toolName' failed $attempts time(s) after retries " +
            "(classified as $failureClass). Consider a different approach or arguments."

    /** Reset for a new task. */
    fun reset() {
        recoveriesUsed = 0
    }
}
