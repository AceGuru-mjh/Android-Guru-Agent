package com.apex.agent.platform.terminal.buffer

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Concrete RingBuffer implementation: a circular byte buffer for PTY output replay.
 *
 * Spec ref: ATR 2.0 Final Spec §22
 *
 * Design:
 *   - Backed by a fixed-size ByteArray (default 256 KB).
 *   - `totalCursor` is the monotonic byte offset of the next byte to be written.
 *   - `oldestCursor` advances as old bytes are evicted (totalCursor - retainedBytes, clamped ≥ 0).
 *   - On overrun (requested cursor < oldestCursor): return overrun=true, empty bytes,
 *     caller re-syncs using [totalCursor] / SemanticState cursor.
 *   - Thread-safe via a single lock (writes are serialized by PtyOutputPump's single-reader contract;
 *     reads may come from multiple subscribers — a synchronized block guards the buffer).
 *
 * Invariants:
 *   - previous.endCursor == next.startCursor (enforced by caller; this class just appends).
 *   - totalCursor only increases.
 *   - oldestCursor ≤ totalCursor always.
 */
class RingTerminalBuffer(
    override val capacityBytes: Int = DEFAULT_CAPACITY
) : TerminalOutputBuffer {

    private val buf = ByteArray(capacityBytes)
    private val writePos = AtomicLong(0L)   // total bytes ever written (== totalCursor)
    private val lock = Any()

    init {
        require(capacityBytes > 0) { "capacityBytes must be > 0, got $capacityBytes" }
    }

    override fun append(chunk: OutputChunk) {
        require(chunk.endCursor == writePos.get() + 0L || chunk.startCursor == writePos.get()) {
            "cursor discontinuity: chunk=${chunk.startCursor}..${chunk.endCursor}, totalCursor=${writePos.get()}"
        }
        synchronized(lock) {
            val bytes = chunk.bytes
            val n = bytes.size
            if (n >= capacityBytes) {
                // chunk bigger than whole buffer: keep only the last `capacityBytes` bytes
                System.arraycopy(bytes, n - capacityBytes, buf, 0, capacityBytes)
                writePos.set(chunk.endCursor)
            } else {
                val start = (writePos.get() % capacityBytes).toInt()
                val first = minOf(n, capacityBytes - start)
                System.arraycopy(bytes, 0, buf, start, first)
                if (first < n) {
                    System.arraycopy(bytes, first, buf, 0, n - first)  // wrap
                }
                writePos.addAndGet(n.toLong())
            }
        }
    }

    override fun getSince(cursor: Long, maxBytes: Int): OutputSlice {
        synchronized(lock) {
            val total = writePos.get()
            val oldest = oldestCursor
            if (cursor < oldest) {
                // PR #52 §6: cursor expired — return availableFrom so caller can re-sync
                return OutputSlice(
                    startCursor = oldest,
                    endCursor = oldest,
                    bytes = ByteArray(0),
                    truncated = false,
                    overrun = true,
                    availableFrom = oldest
                )
            }
            if (cursor > total) {
                // requested beyond newest — empty (caller's cursor is ahead, shouldn't happen normally)
                return OutputSlice(total, total, ByteArray(0), truncated = false, overrun = false)
            }
            val available = (total - cursor).toInt()
            val toRead = minOf(available, maxBytes.coerceAtLeast(0))
            if (toRead == 0) {
                return OutputSlice(cursor, cursor, ByteArray(0), truncated = false, overrun = false)
            }
            val out = ByteArray(toRead)
            val startOff = (cursor % capacityBytes).toInt()
            val first = minOf(toRead, capacityBytes - startOff)
            System.arraycopy(buf, startOff, out, 0, first)
            if (first < toRead) {
                System.arraycopy(buf, 0, out, first, toRead - first)
            }
            return OutputSlice(
                startCursor = cursor,
                endCursor = cursor + toRead,
                bytes = out,
                truncated = toRead < available,
                overrun = false
            )
        }
    }

    override fun latest(maxBytes: Int): OutputSlice {
        synchronized(lock) {
            val total = writePos.get()
            val oldest = oldestCursor
            val available = (total - oldest).toInt()
            val toRead = minOf(available, maxBytes.coerceAtLeast(0))
            val startCursor = total - toRead
            return getSince(startCursor, maxBytes)
        }
    }

    override val totalCursor: Long get() = writePos.get()

    override val oldestCursor: Long
        get() {
            val total = writePos.get()
            return max(0L, total - minOf(total, capacityBytes.toLong())).also {
                // when total < capacity, oldest is 0; else total - capacity
            }.let {
                if (total <= capacityBytes) 0L else total - capacityBytes
            }
        }

    override val retainedBytes: Int
        get() = synchronized(lock) {
            minOf(writePos.get().toInt(), capacityBytes)
        }

    companion object {
        const val DEFAULT_CAPACITY = 256 * 1024   // 256 KB (Spec §22)

        /** Configured capacities per Spec §22. */
        val CAP_64KB = 64 * 1024
        val CAP_256KB = 256 * 1024
        val CAP_1MB = 1024 * 1024
        val CAP_4MB = 4 * 1024 * 1024
    }
}
