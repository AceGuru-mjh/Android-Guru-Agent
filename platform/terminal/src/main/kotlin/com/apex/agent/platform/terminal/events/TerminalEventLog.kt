package com.apex.agent.platform.terminal.events

/**
 * Append-only Terminal EventLog — the Runtime's single source of truth.
 *
 * Spec ref: ATR 2.0 Final Spec §20
 *
 * Properties:
 *   - append-only: events CANNOT be modified or deleted once appended.
 *   - sharded by sessionId.
 *   - globally ordered by output cursor (byte offset), NOT event id.
 *   - OutputProduced events store ONLY refs (offset+length) into RingBuffer; large payloads
 *     live in RingBuffer to avoid EventLog bloat.
 *
 * Persistence (Spec §39): metadata + the most recent N events are persisted to disk for
 * recovery. Large output is NOT persisted (RingBuffer is in-memory only in v1).
 */
interface TerminalEventLog {

    /** Append an event. Returns the assigned event id. Must not block the reader pump. */
    suspend fun append(event: TerminalEvent): Long

    /** Get a specific event by id. */
    suspend fun get(id: Long): TerminalEvent?

    /** Query events for a session, optionally after a cursor, limited to `limit`. */
    suspend fun query(
        sessionId: Long,
        afterCursor: Long = 0,
        limit: Int = 200
    ): List<TerminalEvent>

    /** Return the last `n` events for a session (any cursor). */
    suspend fun tail(sessionId: Long, n: Int): List<TerminalEvent>

    /** Return events in a [fromCursor, toCursor] range for a session. */
    suspend fun range(
        sessionId: Long,
        fromCursor: Long,
        toCursor: Long
    ): List<TerminalEvent>

    /** Total event count for a session. */
    suspend fun count(sessionId: Long): Long

    /** Newest cursor observed for a session (for snapshot/recovery). */
    suspend fun newestCursor(sessionId: Long): Long
}
