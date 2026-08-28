package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.UserInput

/**
 * A68.1 — Task Lifecycle Events.
 *
 * A separate event channel from [com.apex.agent.core.engine.AgentEvent]:
 * `AgentEvent` carries streaming tokens / tool output chunks (high volume,
 * UI-rendered), while [TaskLifecycleEvent] carries discrete lifecycle
 * transitions (low volume, suitable for logging, metrics, UI timelines).
 *
 * Keeping the channels separate means:
 * - The existing `AgentEvent` sealed hierarchy stays frozen (no API break).
 * - Consumers that care only about high-level progress can subscribe to
 *   [TaskOrchestrator.lifecycleEvents] without filtering thousands of
 *   `ResponseChunk` events.
 * - A68.2 (Recovery/Loop Detection) and A68.3 (Parallel Execution) can
 *   emit their own lifecycle events here without touching `AgentEvent`.
 */
sealed interface TaskLifecycleEvent {

    /** Wall-clock timestamp (millis since epoch) when the event was emitted. */
    val timestampMs: Long

    /**
     * A new task has been started by [com.apex.agent.core.engine.AgentEngine.execute].
     * Always the first lifecycle event for a task; never emitted mid-stream.
     */
    data class Started(
        val input: UserInput,
        override val timestampMs: Long
    ) : TaskLifecycleEvent

    /**
     * The orchestrator transitioned from one [TaskState] to another.
     * Emitted for *every* state change, including no-op-looking
     * iterations (e.g. `Planning(iter=1) → Planning(iter=2)`).
     *
     * `from` may be null on the first transition out of [TaskState.Idle].
     */
    data class StateChanged(
        val from: TaskState?,
        val to: TaskState,
        override val timestampMs: Long
    ) : TaskLifecycleEvent

    /**
     * A tool call was scheduled (the LLM emitted a
     * [com.apex.agent.core.llm.ToolCall]; the orchestrator is about to
     * invoke [com.apex.agent.core.tools.ToolExecutor.executeStream]).
     */
    data class ToolCallScheduled(
        val callId: String,
        val toolName: String,
        val arguments: String,
        override val timestampMs: Long
    ) : TaskLifecycleEvent

    /**
     * A tool call finished — either successfully or with an error.
     * `success=false` covers both [com.apex.agent.core.tools.ToolStreamEvent.Error]
     * and thrown exceptions caught by the orchestrator.
     */
    data class ToolCallFinished(
        val callId: String,
        val toolName: String,
        val success: Boolean,
        val durationMs: Long,
        override val timestampMs: Long
    ) : TaskLifecycleEvent

    /**
     * The orchestrator's per-tool or task-level timeout fired.
     * `kind` distinguishes the two cases so A68.2 recovery logic can
     * treat them differently (per-tool timeout → retry; task timeout → abort).
     */
    data class Timeout(
        val kind: Kind,
        val callId: String?,
        override val timestampMs: Long
    ) : TaskLifecycleEvent {
        enum class Kind { PER_TOOL, TASK_LEVEL }
    }

    /**
     * The task was cancelled (via [com.apex.agent.core.engine.AgentEngine.abort]
     * or coroutine cancellation propagated into the orchestrator).
     */
    data class Cancelled(
        val reason: String,
        override val timestampMs: Long
    ) : TaskLifecycleEvent

    // ═══ A68.2 — Retry / loop detection / recovery ═══

    /**
     * A tool call attempt failed and will be retried (A68.2). Emitted
     * between the failed attempt and the backoff delay.
     */
    data class ToolCallRetried(
        val callId: String,
        val toolName: String,
        /** 1-based attempt that just failed. */
        val failedAttempt: Int,
        /** 1-based attempt that is about to run. */
        val nextAttempt: Int,
        /** Classification of the failure that triggered the retry. */
        val failureClass: FailureClass,
        /** Backoff delay before the next attempt. */
        val backoffMs: Long,
        override val timestampMs: Long
    ) : TaskLifecycleEvent

    /**
     * The loop detector fired (A68.2): the recent tool-call pattern is
     * repeating without progress. Usually followed by [RecoveryTriggered].
     */
    data class LoopDetected(
        val signal: LoopSignal,
        override val timestampMs: Long
    ) : TaskLifecycleEvent

    /**
     * A recovery prompt was injected into the conversation history (A68.2),
     * giving the LLM a structured chance to change strategy. `recoveryCount`
     * is the 1-based index of this recovery within the task.
     */
    data class RecoveryTriggered(
        val recoveryCount: Int,
        override val timestampMs: Long
    ) : TaskLifecycleEvent

    // ═══ A68.3 — Parallel execution ═══

    /**
     * A batch of tool calls executed through the dependency graph finished
     * (A68.3). Reports the partial-failure breakdown: how many calls
     * succeeded, failed, or were skipped because a dependency failed.
     */
    data class ParallelBatchFinished(
        val totalCalls: Int,
        val succeededCount: Int,
        val failedCount: Int,
        val skippedCount: Int,
        val totalAttempts: Int,
        val durationMs: Long,
        override val timestampMs: Long
    ) : TaskLifecycleEvent

    /**
     * The task reached a terminal [TaskState.Finished].
     * Always the last lifecycle event for a task.
     */
    data class Finished(
        val finalState: TaskState.Finished,
        override val timestampMs: Long
    ) : TaskLifecycleEvent
}
