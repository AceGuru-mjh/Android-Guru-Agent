package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.logging.LogLevel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the observable [TaskState] / [TaskProgress] / [TaskLifecycleEvent]
 * streams of a task and every legal transition between them.
 *
 * Extracted from [DefaultTaskOrchestrator] — the orchestrator previously mixed
 * state ownership, ReAct-loop driving, batch execution and user-input gating
 * in one 1300+ line class. State-machine bookkeeping is now this class's
 * single responsibility:
 *
 *  - [_state] / [_progress] are the canonical source of truth, mutated ONLY
 *    via [transitionTo] / [updateProgress];
 *  - the state+progress read-modify-write is guarded by [stateLock] because
 *    A68.3 parallel workers transition concurrently — the suspending
 *    lifecycle emit happens OUTSIDE the lock (SharedFlow emit is
 *    thread-safe);
 *  - [TaskLifecycleEvent.StateChanged] is published after every transition,
 *    gated by the [emitLifecycleEvents] provider (dynamic — reads the current
 *    orchestrator config, which may be updated mid-task).
 *
 * ### Concurrency contract
 *
 * [transitionTo] and [updateProgress] may be called from any coroutine; the
 * [TaskState] write + [TaskProgress] refresh inside the lock are atomic
 * relative to each other. Emits are serialized by the SharedFlow itself.
 */
internal class TaskStateMachine(
    /**
     * Dynamic gate for lifecycle-event publication. Reads the live orchestrator
     * config (not a per-task snapshot) to preserve the original behaviour of
     * [DefaultTaskOrchestrator]: an `updateConfig` mid-task takes effect
     * immediately for lifecycle emission.
     */
    private val emitLifecycleEvents: () -> Boolean
) {

    private val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(TaskProgress.EMPTY)
    val progress: StateFlow<TaskProgress> = _progress.asStateFlow()

    private val _lifecycle = MutableSharedFlow<TaskLifecycleEvent>(
        replay = 0,
        extraBufferCapacity = 256
    )
    val lifecycleEvents: SharedFlow<TaskLifecycleEvent> = _lifecycle.asSharedFlow()

    /**
     * Guards the `_state`/`_progress` read-modify-write in [transitionTo]
     * against concurrent parallel workers. Lifecycle emits (which suspend)
     * happen OUTSIDE this lock.
     */
    private val stateLock = Any()

    /** Task start wall-clock time; used to derive [TaskProgress.elapsedMs]. */
    @Volatile
    var taskStartTimeMs: Long = 0L
        set(value) {
            field = value
        }

    val currentState: TaskState
        get() = _state.value

    val currentProgress: TaskProgress
        get() = _progress.value

    /** Atomically replace the state and refresh progress timing. */
    suspend fun transitionTo(newState: TaskState) {
        // A68.3: parallel workers transition concurrently — guard the
        // state+progress read-modify-write with a lock; the lifecycle emit
        // (suspends) happens OUTSIDE the lock.
        val (previous, nowMs) = synchronized(stateLock) {
            val previous = _state.value
            _state.value = newState
            // Update progress snapshot
            val nowMs = System.currentTimeMillis()
            val elapsed = if (taskStartTimeMs > 0L) nowMs - taskStartTimeMs else 0L
            _progress.value = _progress.value.copy(
                elapsedMs = elapsed,
                lastMeaningfulChangeMs = nowMs
            )
            previous to nowMs
        }
        // Emit lifecycle StateChanged event
        if (emitLifecycleEvents()) {
            try {
                _lifecycle.emit(
                    TaskLifecycleEvent.StateChanged(previous, newState, nowMs)
                )
            } catch (e: Throwable) {
                OrchestratorLog.log(LogLevel.WARN, "lifecycle StateChanged emit failed: ${e.message}")
            }
        }
    }

    /** Apply a progress transform under the same lock as [transitionTo]. */
    fun updateProgress(transform: (TaskProgress) -> TaskProgress) {
        synchronized(stateLock) {
            _progress.value = transform(_progress.value)
        }
    }

    /** Replace the progress value wholesale (task start / reset). */
    fun setProgress(progress: TaskProgress) {
        synchronized(stateLock) {
            _progress.value = progress
        }
    }

    /**
     * Emit a lifecycle event, swallowing backpressure errors (the SharedFlow
     * has extraBufferCapacity = 256, so emit should only suspend under
     * extreme backpressure — safe to log-and-continue).
     */
    suspend fun emitLifecycleSafe(event: TaskLifecycleEvent) {
        if (!emitLifecycleEvents()) return
        try {
            _lifecycle.emit(event)
        } catch (e: Throwable) {
            OrchestratorLog.log(LogLevel.WARN, "lifecycle emit failed: ${e.message}")
        }
    }

    /** Reset to idle for a fresh task / explicit [DefaultTaskOrchestrator.reset]. */
    fun reset() {
        synchronized(stateLock) {
            _state.value = TaskState.Idle
            _progress.value = TaskProgress.EMPTY
        }
    }
}
