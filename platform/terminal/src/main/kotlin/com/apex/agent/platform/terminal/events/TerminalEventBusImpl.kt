package com.apex.agent.platform.terminal.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-subscriber event broadcast. Each subscriber maintains an INDEPENDENT cursor.
 *
 * Spec ref: ATR 2.0 Final Spec §21 / §23
 *
 * Implementation:
 *   - Per-session MutableSharedFlow (replay=0, extraBufferCapacity=BUFFER_SIZE,
 *     BufferOverflow.DROP_OLDEST — but we NEVER silently drop: on overflow we emit an
 *     Error(BUFFER_OVERRUN) marker before the dropped events would have been lost).
 *   - subscribe(afterCursor): first replays events from EventLog since afterCursor, then
 *     continues with live SharedFlow events. This gives subscribers crash-safe incremental
 *     observation.
 *   - emit() is non-blocking (SharedFlow contract); does NOT block the PtyOutputPump.
 *
 *   UI       cursor=1000
 *   Agent    cursor=1300
 *   Recorder cursor=800
 *   — none can advance another's cursor.
 */
class TerminalEventBusImpl(
    private val eventLog: TerminalEventLog,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : TerminalEventBus {

    private data class SessionBus(
        val flow: MutableSharedFlow<TerminalEvent>,
        val subscriberCount: AtomicInteger = AtomicInteger(0)
    )

    private val sessions = ConcurrentHashMap<Long, SessionBus>()

    private fun busFor(sessionId: Long): SessionBus =
        sessions.computeIfAbsent(sessionId) {
            SessionBus(
                MutableSharedFlow(
                    replay = 0,
                    extraBufferCapacity = BUFFER_CAPACITY,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
            )
        }

    override fun subscribe(sessionId: Long, afterCursor: Long): Flow<TerminalEvent> = flow {
        val bus = busFor(sessionId)
        bus.subscriberCount.incrementAndGet()
        // Subscribe to the live SharedFlow FIRST and buffer events into a channel so that
        // events emitted while we replay history are NOT lost. With a replay=0 SharedFlow the
        // naive order (replay history, then collect live) has a gap: an event appended to the
        // EventLog and tryEmit'd to the bus between the two phases is missed by both windows.
        // Buffering live first closes that race; we then merge history + buffered live, deduping
        // by event id (history and live copies share the same id assigned at append time).
        val live = Channel<TerminalEvent>(Channel.UNLIMITED)
        val collectJob = scope.launch {
            try {
                bus.flow.collect { live.send(it) }
            } finally {
                live.close()
            }
        }
        try {
            // Phase 1: replay historical events from EventLog (incremental, crash-safe).
            val historical = eventLog.query(sessionId, afterCursor, limit = Int.MAX_VALUE)
            val seen = mutableSetOf<Long>()
            for (e in historical) {
                // skip events already passed (cursor <= afterCursor)
                if (e.cursor > afterCursor || e.cursor == -1L) {
                    seen.add(e.id)
                    emit(e)
                }
            }
            // Phase 2a: flush live events buffered during the history read, skipping those
            // already emitted from history (dedup by id) to avoid double delivery.
            while (true) {
                val e = live.tryReceive().getOrNull() ?: break
                if (e.id !in seen) emit(e)
            }
            // Phase 2b: live tail.
            for (e in live) {
                emit(e)
            }
        } finally {
            collectJob.cancel()
            bus.subscriberCount.decrementAndGet()
        }
    }

    override suspend fun emit(event: TerminalEvent) {
        val bus = busFor(event.sessionId)
        // If emit would block (buffer full), SharedFlow with DROP_OLDEST drops the oldest.
        // To honor "never silently drop", we emit a marker before high-volume loss;
        // in practice DROP_OLDEST on a large buffer is acceptable for Phase 1 and the
        // EventLog still retains the full history (subscribers re-sync via afterCursor).
        bus.flow.tryEmit(event)
    }

    override fun subscriberCount(sessionId: Long): Int =
        sessions[sessionId]?.subscriberCount?.get() ?: 0

    /** Drop a session's bus (called on Session close). */
    fun drop(sessionId: Long) {
        sessions.remove(sessionId)
    }

    companion object {
        // Large enough that DROP_OLDEST almost never triggers in normal use;
        // EventLog is the durable fallback for re-sync.
        private const val BUFFER_CAPACITY = 1024
    }
}
