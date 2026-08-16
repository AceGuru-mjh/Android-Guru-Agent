package com.apex.agent.platform.terminal.wait

import kotlinx.coroutines.flow.Flow

/**
 * Event-driven synchronization layer. Replaces all `sleep + read` polling loops.
 *
 * Spec ref: ATR 2.0 Final Spec §31
 *
 *   - Multiple concurrent waiters allowed; each gets an independent ConditionHandle.
 *   - Condition satisfied → immediately woken via EventBus push (NO polling).
 *   - Timeout MUST return [WaitResult.Timeout] (never hang).
 *   - Session entering CLOSED/BROKEN → all waiters receive [WaitResult.SessionGone].
 */
interface TerminalWaitEngine {

    /**
     * Block until [condition] is met or [timeoutMs] elapses.
     *
     * @return Matched / Timeout / SessionGone.
     */
    suspend fun await(
        sessionId: Long,
        condition: WaitCondition,
        timeoutMs: Long
    ): WaitResult

    /**
     * Register a condition without blocking; returns a Flow that emits the matched event.
     * Useful for streaming-style waits. Cancelling the flow unregisters the waiter.
     */
    fun register(sessionId: Long, condition: WaitCondition): Flow<WaitResult>

    /** Called by the EventBus dispatcher / PtyOutputPump on every event. Internal. */
    suspend fun onEvent(event: com.apex.agent.platform.terminal.events.TerminalEvent)
}
