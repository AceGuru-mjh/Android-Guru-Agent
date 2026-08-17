package com.apex.agent.platform.terminal.observation

import kotlinx.coroutines.flow.Flow

/**
 * PR #57: Observation 2.0 — Agent-native incremental terminal observation.
 *
 * Spec: Full Snapshot + Incremental Observation + Cursor + Expiration +
 * Multi-Consumer + Event Stream + Bounded Retention. Read-only projection
 * of TerminalCore (never maintains second screen state).
 */

// ─── §2: Observation version ───
/** Monotonic observation sequence. Independent from VT mutation count. */
data class ObservationCursor(val sequence: Long) : Comparable<ObservationCursor> {
    override fun compareTo(other: ObservationCursor): Int = sequence.compareTo(other.sequence)
    companion object { val INITIAL = ObservationCursor(0L) }
}

// ─── §5: Change types ───
sealed interface TerminalChange {
    data class CellsChanged(val row: Int, val startCol: Int, val endCol: Int, val text: String) : TerminalChange
    data class CursorChanged(val row: Int, val col: Int, val visible: Boolean) : TerminalChange
    data class ScreenResized(val rows: Int, val cols: Int) : TerminalChange
    data class TitleChanged(val title: String?) : TerminalChange
    data class ModeChanged(val alternateScreen: Boolean, val raw: Boolean) : TerminalChange
    data class ScrollChanged(val direction: ScrollDirection, val count: Int) : TerminalChange
    data class Cleared(val mode: ClearMode) : TerminalChange
    enum class ScrollDirection { UP, DOWN }
    enum class ClearMode { SCREEN, LINE, ALL }
}

// ─── §4: Observation Batch ───
/** Batch of changes from sequence A to B. Replayable: snapshotA + batch = snapshotB. */
data class ObservationBatch(
    val sessionId: Long,
    val fromSequence: Long,
    val toSequence: Long,
    val changes: List<TerminalChange>,
    val snapshotRequired: Boolean   // true if cursor expired, Agent must getSnapshot()
)

// ─── §1: Full Snapshot ───
/** Consistent point-in-time snapshot. All fields from same logical state point. */
data class TerminalObservationSnapshot(
    val sessionId: Long,
    val sequence: Long,
    val rows: Int,
    val cols: Int,
    val screenText: String,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val alternateScreen: Boolean,
    val title: String?,
    val raw: Boolean
)

// ─── §31: Observation API ───
interface TerminalObservation {
    /** §1: Full consistent snapshot. */
    suspend fun snapshot(sessionId: Long): Result<TerminalObservationSnapshot>

    /** §2: Incremental observation since cursor. Returns batch or cursor-expired error. */
    suspend fun observeSince(sessionId: Long, cursor: ObservationCursor): Result<ObservationBatch>

    /** §15: Subscribe to observation events (push, coalesced). */
    fun subscribe(sessionId: Long): Flow<ObservationBatch>?

    /** §11: Register a consumer with independent cursor. */
    fun registerConsumer(sessionId: Long): Result<ObservationConsumer>

    /** §12: Unregister consumer. */
    fun unregisterConsumer(consumerId: String)

    /** §37: Diagnostics. */
    fun diagnostics(sessionId: Long): ObservationDiagnostics?
}

/** §11: Consumer with independent cursor. */
data class ObservationConsumer(
    val consumerId: String,
    val sessionId: Long,
    var cursor: ObservationCursor
)

/** §37: Internal diagnostics. */
data class ObservationDiagnostics(
    val currentSequence: Long,
    val oldestSequence: Long,
    val activeConsumers: Int,
    val bufferedBatches: Int,
    val droppedBatches: Int,
    val snapshotCount: Int,
    val cursorExpiredCount: Int
)

// ─── §32: Observation errors ───
sealed class ObservationError(val code: String, val message: String) {
    data class CursorExpired(val requested: Long, val oldestAvailable: Long, val current: Long) :
        ObservationError("CursorExpired", "cursor $requested expired; oldest=$oldestAvailable current=$current")
    data object ObservationUnavailable : ObservationError("ObservationUnavailable", "session not found or observation not active")
    data object SnapshotTooLarge : ObservationError("SnapshotTooLarge", "snapshot exceeds memory budget")
    data object ObservationBackpressure : ObservationError("ObservationBackpressure", "observation buffer full")
    data object ConsumerNotFound : ObservationError("ConsumerNotFound", "consumer not registered")
    data object InvalidCursor : ObservationError("InvalidCursor", "cursor is invalid for this session")
    data object ObservationClosed : ObservationError("ObservationClosed", "session observation is closed")
}

// ─── §19: Retention config ───
data class TerminalRetentionConfig(
    val maxScrollbackLines: Int = 1000,
    val maxObservationBatches: Int = 500,
    val maxRawOutputBytes: Int = 256 * 1024,
    val coalesceWindowMs: Long = 50L
)
