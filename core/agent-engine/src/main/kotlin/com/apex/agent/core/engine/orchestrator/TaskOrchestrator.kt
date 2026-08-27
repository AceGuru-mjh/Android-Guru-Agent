package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.UserInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A68.1 — Unified Task Execution Orchestrator.
 *
 * A [TaskOrchestrator] sits ABOVE [AgentEngine] and provides:
 *
 * 1. An explicit **[TaskState] state machine** (the canonical lifecycle),
 *    exposed via [state]. The existing [AgentEngine] inferred state
 *    implicitly from emitted events; the orchestrator makes it first-class.
 *
 * 2. A **[TaskProgress] snapshot** exposed via [progress], updated on
 *    every meaningful transition. UIs can render
 *    "正在分析项目 → 正在读取文件 → 正在修改代码 → 正在验证" directly.
 *
 * 3. A separate **[TaskLifecycleEvent] channel** for discrete lifecycle
 *    events (low-volume, suitable for logging/metrics), keeping the
 *    existing [AgentEvent] sealed hierarchy frozen.
 *
 * 4. **[TaskOrchestratorConfig]** with per-tool / task-level timeout +
 *    tool-failure policy. The existing [AgentEngine] had no per-tool
 *    timeout and relied entirely on cooperative `isRunning` flag polling.
 *
 * The orchestrator **is** an [AgentEngine] (interface inheritance): it
 * can be wired into any existing consumer (e.g. Hilt's
 * `provideAgentEngine`) as a drop-in replacement. Concrete implementation:
 * [DefaultTaskOrchestrator].
 *
 * ### API compatibility
 *
 * - [execute], [abort], [submitUserInput], [cancelUserInput] delegate
 *   to the underlying [AgentEngine] (or run an equivalent loop for
 *   BUILD mode). Existing callers (ViewModels, services) need no changes.
 * - The orchestrator does NOT expose the extra methods that
 *   [com.apex.agent.core.engine.ApexAgentEngine] adds beyond the
 *   [AgentEngine] interface (`updateConfig`, `compressNow`, etc.) —
 *   those remain accessible only by down-casting to `ApexAgentEngine`,
 *   which is unchanged by A68.1.
 *
 * ### Scope (A68.1)
 *
 * Phase 1 (this) implements:
 * - State machine + progress + lifecycle events
 * - Per-tool and task-level timeouts
 * - Cancellation propagation (cooperative flag + coroutine cancellation)
 * - Basic error propagation (tool errors feed back to LLM; critical
 *   errors fail the task)
 *
 * Phase 2 (A68.2) will add:
 * - Retry policy / retry budget / backoff
 * - Failure classification (transient / timeout / fatal / permission)
 * - Loop detection (repeated tool calls, periodic action patterns)
 * - Recovery strategy + replanning
 *
 * Phase 3 (A68.3) will add:
 * - Dependency graph
 * - Parallel tool execution
 * - Partial failure + result aggregation
 */
interface TaskOrchestrator : AgentEngine {

    /**
     * Live task state. Initialized to [TaskState.Idle] on orchestrator
     * construction; transitions synchronously with internal decisions
     * (never inferred from event stream — the orchestrator is the source
     * of truth).
     *
     * After the task reaches [TaskState.Finished], this holds the terminal
     * state until the next [execute] call resets it to [TaskState.Idle].
     */
    val state: StateFlow<TaskState>

    /**
     * Live task progress snapshot. Updated on every meaningful transition
     * (iteration start, tool call start/finish, response chunk). Stays
     * frozen after [TaskState.Finished].
     */
    val progress: StateFlow<TaskProgress>

    /**
     * Discrete lifecycle events (low-volume). Subscribe for telemetry,
     * logging, or UI timelines. See [TaskLifecycleEvent] for the full
     * taxonomy.
     *
     * Note: this is a [SharedFlow] (replay = 0) — late subscribers do not
     * receive historical events, only events emitted after subscription.
     */
    val lifecycleEvents: SharedFlow<TaskLifecycleEvent>

    /**
     * Current orchestrator config. May be updated between tasks (not
     * mid-task — the running task keeps the config snapshot it was
     * started with).
     */
    val config: TaskOrchestratorConfig

    /**
     * Update the orchestrator config. Takes effect on the next [execute]
     * call; the currently running task (if any) keeps its snapshot.
     */
    fun updateConfig(config: TaskOrchestratorConfig)

    /**
     * Reset the orchestrator to [TaskState.Idle] + [TaskProgress.EMPTY]
     * without running a task. Useful for tests and for clearing a
     * terminal state after the UI has observed it.
     *
     * Has no effect on a running task — call [abort] first.
     */
    fun reset()
}
