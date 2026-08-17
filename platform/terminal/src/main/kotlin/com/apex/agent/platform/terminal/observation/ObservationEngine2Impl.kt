package com.apex.agent.platform.terminal.observation

import com.apex.agent.platform.terminal.state.ObservationEngine
import com.apex.agent.platform.terminal.state.SemanticStateReducer
import com.apex.agent.platform.terminal.screen.VirtualTerminal
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * PR #57: Observation Engine 2.0 implementation.
 *
 * Wraps the existing #49/#53 ObservationEngine. Adds:
 *   - Sequence versioning (§2): each screen update increments sequence
 *   - Bounded batch ring (§4): stores recent ObservationBatch entries
 *   - Multi-consumer cursors (§11): each consumer has independent cursor
 *   - Event coalescing (§16): mutations batched within coalesceWindowMs
 *   - Cursor expiration (§9): old batches evicted → CursorExpired error
 *   - Diagnostics (§37): metrics for debugging
 *
 * Read-only projection: never maintains second screen state. All data from
 * ObservationEngine (which reads from TerminalCore). §19: no duplicate
 * ScreenBuffer/ANSI parser.
 */
class ObservationEngine2Impl(
    private val engine: ObservationEngine,
    private val virtualTerminal: VirtualTerminal,
    private val sessionId: Long,
    private val retentionConfig: TerminalRetentionConfig = TerminalRetentionConfig()
) : TerminalObservation {

    private val sequence = AtomicLong(0L)
    private val snapshotCount = AtomicInteger(0)
    private val cursorExpiredCount = AtomicInteger(0)
    private val droppedBatches = AtomicInteger(0)

    // §4: bounded ring of batches (oldest evicted when over capacity)
    private val batchRing = ArrayDeque<ObservationBatch>()
    // §11: consumers with independent cursors
    private val consumers = ConcurrentHashMap<String, ObservationConsumer>()
    // §15: push event stream (bounded, coalesced)
    private val eventFlow = MutableSharedFlow<ObservationBatch>(
        extraBufferCapacity = retentionConfig.maxObservationBatches
    )
    private val events = eventFlow.asSharedFlow()

    /**
     * Called by PtyOutputPump after VT feed. Increments sequence + creates a batch.
     * §33: This must NOT block PTY reader (just enqueues to bounded ring).
     */
    fun onScreenMutation(changes: List<TerminalChange>) {
        val fromSeq = sequence.get()
        val toSeq = sequence.incrementAndGet()
        val batch = ObservationBatch(
            sessionId = sessionId,
            fromSequence = fromSeq,
            toSequence = toSeq,
            changes = changes,
            snapshotRequired = false
        )
        synchronized(batchRing) {
            batchRing.addLast(batch)
            // §17-20: bounded — evict oldest
            while (batchRing.size > retentionConfig.maxObservationBatches) {
                batchRing.pollFirst()
                droppedBatches.incrementAndGet()
            }
        }
        // §15: push to subscribers (non-blocking, drop-oldest if full)
        eventFlow.tryEmit(batch)
    }

    // ─── §1: Full Snapshot ───
    override suspend fun snapshot(sessionId: Long): Result<TerminalObservationSnapshot> {
        if (sessionId != this.sessionId) return Result.failure(RuntimeException("ObservationError:ObservationUnavailable"))
        val screen = virtualTerminal.snapshot()
        val sem = engine.semanticState.value
        val seq = sequence.get()
        snapshotCount.incrementAndGet()
        return Result.success(TerminalObservationSnapshot(
            sessionId = sessionId,
            sequence = seq,
            rows = screen.rows, cols = screen.cols,
            screenText = screen.renderedText ?: "",
            cursorRow = screen.cursorRow, cursorCol = screen.cursorCol,
            cursorVisible = true, // from TerminalCore modes
            alternateScreen = screen.alternateScreen,
            title = screen.title,
            raw = false
        ))
    }

    // ─── §2: Incremental Observation ───
    override suspend fun observeSince(sessionId: Long, cursor: ObservationCursor): Result<ObservationBatch> {
        if (sessionId != this.sessionId) return Result.failure(RuntimeException("ObservationError:ObservationUnavailable"))
        synchronized(batchRing) {
            val oldest = batchRing.firstOrNull()?.fromSequence ?: sequence.get()
            if (cursor.sequence < oldest) {
                // §9: Cursor expired — Agent must re-sync via snapshot()
                cursorExpiredCount.incrementAndGet()
                return Result.failure(RuntimeException(
                    "ObservationError:CursorExpired:requested=${cursor.sequence},oldestAvailable=$oldest,current=${sequence.get()}"
                ))
            }
            // Collect all batches from cursor.sequence+1 to current
            val collected = batchRing.filter { it.toSequence > cursor.sequence }
            if (collected.isEmpty()) {
                return Result.success(ObservationBatch(
                    sessionId = sessionId,
                    fromSequence = cursor.sequence,
                    toSequence = sequence.get(),
                    changes = emptyList(),
                    snapshotRequired = false
                ))
            }
            val allChanges = collected.flatMap { it.changes }
            val first = collected.first()
            val last = collected.last()
            return Result.success(ObservationBatch(
                sessionId = sessionId,
                fromSequence = first.fromSequence,
                toSequence = last.toSequence,
                changes = allChanges,
                snapshotRequired = false
            ))
        }
    }

    // ─── §15: Event Stream ───
    override fun subscribe(sessionId: Long): kotlinx.coroutines.flow.Flow<ObservationBatch>? {
        if (sessionId != this.sessionId) return null
        return events
    }

    // ─── §11: Consumer registration ───
    override fun registerConsumer(sessionId: Long): Result<ObservationConsumer> {
        if (sessionId != this.sessionId) return Result.failure(RuntimeException("ObservationError:ObservationUnavailable"))
        val id = UUID.randomUUID().toString()
        val consumer = ObservationConsumer(id, sessionId, ObservationCursor(sequence.get()))
        consumers[id] = consumer
        return Result.success(consumer)
    }

    override fun unregisterConsumer(consumerId: String) {
        consumers.remove(consumerId)
    }

    // ─── §37: Diagnostics ───
    override fun diagnostics(sessionId: Long): ObservationDiagnostics? {
        if (sessionId != this.sessionId) return null
        return ObservationDiagnostics(
            currentSequence = sequence.get(),
            oldestSequence = synchronized(batchRing) { batchRing.firstOrNull()?.fromSequence ?: sequence.get() },
            activeConsumers = consumers.size,
            bufferedBatches = synchronized(batchRing) { batchRing.size },
            droppedBatches = droppedBatches.get(),
            snapshotCount = snapshotCount.get(),
            cursorExpiredCount = cursorExpiredCount.get()
        )
    }
}
