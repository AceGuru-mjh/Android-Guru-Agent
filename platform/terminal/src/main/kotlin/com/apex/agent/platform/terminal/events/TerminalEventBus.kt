package com.apex.agent.platform.terminal.events

import kotlinx.coroutines.flow.Flow

/**
 * Multi-subscriber event broadcast. Each subscriber maintains an INDEPENDENT cursor.
 *
 * Spec ref: ATR 2.0 Final Spec §21 / §23
 *
 *   UI       cursor=1000
 *   Agent    cursor=1300
 *   Recorder cursor=800
 *
 * No subscriber can advance another subscriber's cursor. This is the basis for
 * multi-consumer observation (Spec §23).
 *
 * Backpressure: BUFFERED + DROP_OLDEST_WITH_MARKER. On overrun, insert an Error(BUFFER_OVERRUN)
 * marker event rather than silently dropping.
 */
interface TerminalEventBus {

    /**
     * Subscribe to events for a session, starting AFTER the given cursor.
     * The returned Flow is cold; collecting it registers the subscriber.
     * Cancelling the collection unregisters the subscriber.
     *
     * Each subscriber gets its own cursor advancement; does NOT affect other subscribers.
     */
    fun subscribe(sessionId: Long, afterCursor: Long = 0): Flow<TerminalEvent>

    /** Emit an event to all matching subscribers. Must NOT block the emitter (use a dedicated dispatcher). */
    suspend fun emit(event: TerminalEvent)

    /** Current subscriber count for a session (for diagnostics / DoD §52: ≥ 8 concurrent). */
    fun subscriberCount(sessionId: Long): Int
}
