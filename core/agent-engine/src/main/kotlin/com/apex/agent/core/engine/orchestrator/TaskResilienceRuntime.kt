package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.tools.ToolExecutor

/**
 * Per-task fault-tolerance runtime (A68.2) — one instance per [TaskOrchestrator.execute]
 * call, built from the task's config snapshot.
 *
 * Extracted from [DefaultTaskOrchestrator] as a standalone named type so the
 * composition (runner + loop detector + recovery planner) and the ownership
 * rule (recreated per task, null outside a task) are explicit.
 *
 * - [runner]: single tool-call execution core — per-attempt timeout, failure
 *   classification, retry decision, backoff delay.
 * - [loopDetector]: repetition / oscillation detection over the emitted call
 *   sequence.
 * - [recoveryPlanner]: builds recovery prompts when [loopDetector] fires,
 *   bounded by the per-task recovery budget.
 */
internal class TaskResilienceRuntime(
    val runner: ToolCallRunner,
    val loopDetector: LoopDetector,
    val recoveryPlanner: RecoveryPlanner
) {
    companion object {
        /**
         * Build the runtime from the task's config snapshot. Called once at
         * the start of every [TaskOrchestrator.execute].
         *
         * @param toolExecutor the executor owned by the orchestrator itself
         *   (NOT part of [TaskOrchestratorConfig] — it is a constructor
         *   dependency of the orchestrator, shared across tasks)
         */
        fun fromConfig(
            cfg: TaskOrchestratorConfig,
            toolExecutor: ToolExecutor
        ): TaskResilienceRuntime =
            TaskResilienceRuntime(
                runner = ToolCallRunner(
                    toolExecutor = toolExecutor,
                    classifier = FailureClassifier(),
                    retryPolicy = cfg.retryPolicy,
                    retryBudget = RetryBudget(cfg.retryPolicy.retryBudget)
                ),
                loopDetector = LoopDetector(
                    maxRepetitions = cfg.loopDetectionMaxRepetitions,
                    windowSize = cfg.loopDetectionWindow
                ),
                recoveryPlanner = RecoveryPlanner(maxRecoveries = cfg.maxRecoveries)
            )
    }
}
