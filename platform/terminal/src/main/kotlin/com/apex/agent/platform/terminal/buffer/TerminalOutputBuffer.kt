package com.apex.agent.platform.terminal.buffer

/**
 * Output byte chunk with monotonic cursor range.
 *
 * Spec ref: ATR 2.0 Final Spec §13 (Cursor model)
 *
 * INVARIANT: for consecutive chunks in the same session,
 *   previous.endCursor == next.startCursor
 *
 * @param sessionId   Owning session.
 * @param startCursor Byte offset of the first byte in this chunk (inclusive).
 * @param endCursor   Byte offset after the last byte (exclusive). endCursor - startCursor = bytes.size.
 * @param bytes       The raw PTY output bytes.
 */
data class OutputChunk(
    val sessionId: Long,
    val startCursor: Long,
    val endCursor: Long,
    val bytes: ByteArray
) {
    init {
        require(endCursor - startCursor == bytes.size.toLong()) {
            "cursor range (${startCursor}..${endCursor}) must equal bytes.size (${bytes.size})"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutputChunk) return false
        return sessionId == other.sessionId &&
            startCursor == other.startCursor &&
            endCursor == other.endCursor &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + startCursor.hashCode()
        result = 31 * result + endCursor.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * Result of a buffer read. May be partial (truncated) or failed (overrun).
 *
 * @param startCursor  First byte offset returned (inclusive).
 * @param endCursor    Last byte offset + 1 (exclusive). Pass as next `afterCursor`.
 * @param bytes        The bytes (may be empty if overrun).
 * @param truncated    true if `maxBytes` capped the result (more data available).
 * @param overrun      true if `afterCursor < oldestCursor` (data has been dropped).
 *                     In this case bytes is empty; re-sync using [newestCursor] / the session's
 *                     current cursor from SemanticState.
 */
data class OutputSlice(
    val startCursor: Long,
    val endCursor: Long,
    val bytes: ByteArray,
    val truncated: Boolean,
    val overrun: Boolean,
    /** PR #52 §5/§6: when overrun, the oldest still-available cursor (re-sync point). */
    val availableFrom: Long? = null
)

/**
 * Ring buffer for PTY output replay. Holds ONLY output bytes (no business logic).
 *
 * Spec ref: ATR 2.0 Final Spec §22
 *
 * Default capacity: 256 KB. Configurable: 64 KB / 256 KB / 1 MB / 4 MB.
 *
 * On overrun (afterCursor < oldestCursor): return overrun=true + oldestCursor + newestCursor,
 * NEVER silently drop data.
 */
interface TerminalOutputBuffer {

    /** Append a chunk. Advances totalCursor. May evict oldest bytes if over capacity. */
    fun append(chunk: OutputChunk)

    /**
     * Get all bytes since [cursor] (exclusive).
     * If cursor < oldestCursor → overrun=true, empty bytes, re-sync with [totalCursor].
     * If the result exceeds a sensible max, truncated=true (caller may paginate).
     */
    fun getSince(cursor: Long, maxBytes: Int = Int.MAX_VALUE): OutputSlice

    /** Get the most recent [maxBytes] bytes (regardless of cursor). */
    fun latest(maxBytes: Int): OutputSlice

    /** Byte offset after the newest byte (= next append's startCursor). */
    val totalCursor: Long

    /** Byte offset of the oldest retained byte. Queries before this return overrun. */
    val oldestCursor: Long

    /** Configured capacity in bytes. */
    val capacityBytes: Int

    /** Current retained byte count (≤ capacityBytes). */
    val retainedBytes: Int
}
