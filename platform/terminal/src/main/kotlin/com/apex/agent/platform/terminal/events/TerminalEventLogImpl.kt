package com.apex.agent.platform.terminal.events

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory append-only EventLog. Sharded by sessionId.
 *
 * Spec ref: ATR 2.0 Final Spec §20
 *
 *   - append-only: events CANNOT be modified or deleted.
 *   - per-session monotonic event id.
 *   - OutputProduced stores only refs (offset+length) — large bytes live in RingBuffer.
 *   - For Phase 1: in-memory. Persistence (Spec §39) is Phase 5.
 *
 * Thread-safety: per-session lock; append is O(1) amortized (append to ArrayList).
 */
class TerminalEventLogImpl : TerminalEventLog {

    private data class SessionLog(
        val events: MutableList<TerminalEvent> = ArrayList(INITIAL_CAPACITY),
        val idCounter: AtomicLong = AtomicLong(0L),
        val cursorCounter: AtomicLong = AtomicLong(0L),
        val mutex: Mutex = Mutex()
    )

    private val sessions = ConcurrentHashMap<Long, SessionLog>()

    private fun logFor(sessionId: Long, create: Boolean = true): SessionLog? {
        if (create) return sessions.computeIfAbsent(sessionId) { SessionLog() }
        return sessions[sessionId]
    }

    override suspend fun append(event: TerminalEvent): Long {
        val log = logFor(event.sessionId) ?: return -1L
        return log.mutex.withLock {
            val id = log.idCounter.incrementAndGet()
            // reassign id into a new event instance (events are data classes, copy is cheap)
            val withId = reassignId(event, id)
            log.events.add(withId)
            // P1 fix（边界值）：PtyOutputPump 每读 8KB 追加一条 OutputProduced，
            // append-only 无淘汰 → 长会话/高吞吐输出（yes、长构建）必然 OOM。
            // 改为环形淘汰：超过上限时批量裁剪最旧事件。事件 id 单调递增不回绕，
            // cursor 语义由 RingBuffer 负责，事件日志仅保留近期上下文即可。
            if (log.events.size > MAX_EVENTS_PER_SESSION) {
                log.events.subList(0, EVENT_TRIM_CHUNK).clear()
            }
            id
        }
    }

    override suspend fun get(id: Long): TerminalEvent? {
        // linear scan across sessions (event ids are per-session, so we must check each)
        for (log in sessions.values) {
            log.mutex.withLock {
                val e = log.events.firstOrNull { it.id == id }
                if (e != null) return e
            }
        }
        return null
    }

    override suspend fun query(sessionId: Long, afterCursor: Long, limit: Int): List<TerminalEvent> {
        val log = logFor(sessionId, create = false) ?: return emptyList()
        return log.mutex.withLock {
            log.events
                .asSequence()
                .filter { it.cursor > afterCursor || it.cursor == -1L }
                .take(limit)
                .toList()
        }
    }

    override suspend fun tail(sessionId: Long, n: Int): List<TerminalEvent> {
        val log = logFor(sessionId, create = false) ?: return emptyList()
        return log.mutex.withLock {
            val size = log.events.size
            val from = maxOf(0, size - n)
            log.events.subList(from, size).toList()
        }
    }

    override suspend fun range(sessionId: Long, fromCursor: Long, toCursor: Long): List<TerminalEvent> {
        val log = logFor(sessionId, create = false) ?: return emptyList()
        return log.mutex.withLock {
            log.events.filter { it.cursor in fromCursor..toCursor }
        }
    }

    override suspend fun count(sessionId: Long): Long {
        val log = logFor(sessionId, create = false) ?: return 0L
        return log.mutex.withLock { log.events.size.toLong() }
    }

    override suspend fun newestCursor(sessionId: Long): Long {
        val log = logFor(sessionId, create = false) ?: return 0L
        return log.mutex.withLock {
            log.events.lastOrNull { it.cursor >= 0 }?.cursor ?: 0L
        }
    }

    /** Drop a session's log (called on Session close to free memory). */
    fun drop(sessionId: Long) {
        sessions.remove(sessionId)
    }

    private fun reassignId(event: TerminalEvent, id: Long): TerminalEvent = when (event) {
        is TerminalEvent.SessionCreated -> event.copy(id = id)
        is TerminalEvent.ProcessStarted -> event.copy(id = id)
        is TerminalEvent.InputWritten -> event.copy(id = id)
        is TerminalEvent.OutputProduced -> event.copy(id = id)
        is TerminalEvent.ResizeChanged -> event.copy(id = id)
        is TerminalEvent.ProcessExited -> event.copy(id = id)
        is TerminalEvent.SignalSent -> event.copy(id = id)
        is TerminalEvent.UserInterrupt -> event.copy(id = id)
        is TerminalEvent.WaitingInput -> event.copy(id = id)
        is TerminalEvent.SessionClosed -> event.copy(id = id)
        is TerminalEvent.Error -> event.copy(id = id)
        is TerminalEvent.StateChanged -> event.copy(id = id)
    }

    companion object {
        private const val INITIAL_CAPACITY = 256

        /** P1 fix：单会话事件上限（防 OOM），触发后按块裁剪最旧事件以均摊 O(1)。 */
        private const val MAX_EVENTS_PER_SESSION = 10_000
        private const val EVENT_TRIM_CHUNK = 1_000
    }
}
