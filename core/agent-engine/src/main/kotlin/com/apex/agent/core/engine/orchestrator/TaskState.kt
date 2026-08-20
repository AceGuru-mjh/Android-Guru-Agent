package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.ExecutionSpec
import com.apex.agent.core.engine.InputType

/**
 * A68.1 — Task State Machine.
 *
 * Discrete lifecycle states for a single Agent task. The orchestrator owns
 * exactly one [TaskState] at a time, exposed via
 * [TaskOrchestrator.state] ([kotlinx.coroutines.flow.StateFlow]).
 *
 * Transitions are unidirectional once a task is [TaskState.Finished]; the
 * orchestrator never "revives" a finished task — a new task starts from
 * [TaskState.Idle].
 *
 * The shape intentionally mirrors the existing [com.apex.agent.core.engine.AgentEvent]
 * lifecycle so a UI reducer can derive state from events, but the canonical
 * source of truth is the orchestrator itself (state is set explicitly at
 * each transition, not inferred).
 */
sealed interface TaskState {

    /**
     * No task has been started yet (or the previous one was reset).
     * The orchestrator returns here only after a terminal state has been
     * observed by the consumer; it never auto-resets mid-stream.
     */
    data object Idle : TaskState

    /**
     * The Agent is currently planning/Thinking — calling the LLM and
     * streaming reasoning chunks before deciding the next action.
     * Holds the current 1-based iteration count.
     */
    data class Planning(
        val iteration: Int,
        val progress: TaskProgress
    ) : TaskState

    /**
     * The Agent has decided to invoke a tool and is waiting for the
     * [com.apex.agent.core.tools.ToolExecutor] to return.
     */
    data class Acting(
        val iteration: Int,
        val callId: String,
        val toolName: String,
        val progress: TaskProgress
    ) : TaskState

    /**
     * A tool returned (or streamed) output and the orchestrator is
     * feeding the result back into the conversation history before the
     * next Planning iteration.
     */
    data class Observing(
        val iteration: Int,
        val callId: String,
        val toolName: String,
        val success: Boolean,
        val progress: TaskProgress
    ) : TaskState

    /**
     * The Agent finished thinking/acting and is streaming the final
     * user-facing text response.
     */
    data class Responding(
        val iteration: Int,
        val progress: TaskProgress
    ) : TaskState

    /**
     * The Agent emitted [com.apex.agent.core.engine.AgentEvent.UserInputRequired]
     * and is parked, waiting for [com.apex.agent.core.engine.AgentEngine.submitUserInput]
     * or [com.apex.agent.core.engine.AgentEngine.cancelUserInput].
     */
    data class AwaitingUserInput(
        val prompt: String,
        val type: InputType,
        val progress: TaskProgress
    ) : TaskState

    /**
     * PLAN mode produced an [ExecutionPlan] and the orchestrator is
     * waiting for [com.apex.agent.core.engine.AgentEvent.PlanConfirmed]
     * (via the ViewModel's `submitPlanConfirmation`).
     */
    data class AwaitingPlanConfirmation(
        val plan: ExecutionPlan,
        val progress: TaskProgress
    ) : TaskState

    /**
     * SPEC mode produced an [ExecutionSpec] and the orchestrator is
     * waiting for [com.apex.agent.core.engine.AgentEvent.SpecConfirmed].
     */
    data class AwaitingSpecConfirmation(
        val spec: ExecutionSpec,
        val progress: TaskProgress
    ) : TaskState

    /**
     * Union of terminal states. Once a task reaches [Finished], the
     * orchestrator will not emit further [TaskState] changes until
     * [com.apex.agent.core.engine.AgentEngine.execute] is invoked again.
     */
    sealed interface Finished : TaskState {

        /**
         * Task completed normally with a final summary.
         */
        data class Completed(
            val summary: String,
            val totalIterations: Int,
            val totalToolCalls: Int,
            val elapsedMs: Long
        ) : Finished

        /**
         * Task failed irrecoverably. [partial] carries the best-known
         * progress snapshot at the moment of failure (may be null if the
         * failure happened before any progress was recorded).
         */
        data class Failed(
            val message: String,
            val partial: TaskProgress?
        ) : Finished

        /**
         * Task was aborted by the user via [com.apex.agent.core.engine.AgentEngine.abort]
         * or by the orchestrator's own timeout/cancellation policy.
         */
        data object Aborted : Finished
    }
}

/**
 * Convenience alias for the terminal union. Lets consumers write
 * `when (state) { is TaskState.Finished -> ... }` without enumerating
 * every terminal subtype.
 */
typealias TaskTerminalState = TaskState.Finished
