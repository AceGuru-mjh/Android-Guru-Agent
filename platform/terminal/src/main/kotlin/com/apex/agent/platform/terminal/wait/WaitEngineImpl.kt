package com.apex.agent.platform.terminal.wait

import com.apex.agent.platform.terminal.events.CloseCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Event-driven WaitEngine. Replaces all `sleep + read` polling loops.
 *
 * Spec ref: ATR 2.0 Final Spec §31
 *
 *   - Multiple concurrent waiters allowed; each gets an independent handle.
 *   - Condition satisfied → immediately woken via EventBus push (NO polling).
 *   - Timeout MUST return [WaitResult.Timeout] (never hang).
 *   - Session CLOSED/BROKEN → all waiters receive [WaitResult.SessionGone].
 *
 * Implementation: subscribes to EventBus once per session (lazy), dispatches events to all
 * registered waiters via a per-session waiter list + mutex.
 */
class WaitEngineImpl(
    private val bus: TerminalEventBus,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : TerminalWaitEngine {

    private data class Waiter(
        val id: String,
        val sessionId: Long,
        val condition: WaitCondition,
        // called on each event; returns true if matched (waiter then removed + completed)
        val matcher: (TerminalEvent) -> MatchResult
    )

    private data class MatchResult(val matched: Boolean, val event: TerminalEvent? = null)

    private val waiters = ConcurrentHashMap<Long, MutableList<Waiter>>()
    private val locks = ConcurrentHashMap<Long, Mutex>()

    private fun lockFor(sessionId: Long): Mutex =
        locks.computeIfAbsent(sessionId) { Mutex() }

    private fun matchEvent(condition: WaitCondition, event: TerminalEvent): MatchResult {
        val matched = when (condition) {
            is WaitCondition.ProcessStarted -> event is TerminalEvent.ProcessStarted &&
                (condition.jobId == null || event.jobId == condition.jobId)
            is WaitCondition.ProcessExited -> event is TerminalEvent.ProcessExited &&
                (condition.jobId == null || event.jobId == condition.jobId)
            WaitCondition.UserInterrupt -> event is TerminalEvent.UserInterrupt
            WaitCondition.InputRequired -> event is TerminalEvent.WaitingInput &&
                event.confidence == com.apex.agent.platform.terminal.events.Confidence.HIGH_CONFIDENCE
            WaitCondition.SessionClosed -> event is TerminalEvent.SessionClosed
            WaitCondition.Error -> event is TerminalEvent.Error
            is WaitCondition.OutputMatch -> event is TerminalEvent.OutputProduced &&
                matchOutput(condition, event)
            WaitCondition.ScreenChanged -> event is TerminalEvent.OutputProduced  // coarse
            WaitCondition.PromptDetected -> event is TerminalEvent.OutputProduced  // TODO v2: prompt regex
            is WaitCondition.IdleFor -> false  // IdleFor handled separately by timer; not event-matched
        }
        return if (matched) MatchResult(true, event) else MatchResult(false)
    }

    private fun matchOutput(c: WaitCondition.OutputMatch, e: TerminalEvent.OutputProduced): Boolean {
        // NOTE: OutputProduced carries cursor refs, not bytes (Spec §19/§20).
        // For Phase 1 we match against the cursor delta as a proxy; full byte matching
        // requires RingBuffer access (inject in Phase 2). This is a known v1 limitation.
        // Real matching will be done by the ObservationEngine layer.
        return e.endCursor > e.startCursor  // any output produced
    }

    override suspend fun await(sessionId: Long, condition: WaitCondition, timeoutMs: Long): WaitResult {
        // Collect from bus until matched, session closed, or timeout.
        // first{} returns the REAL event that satisfied the predicate so callers can
        // inspect it (e.g. exitCode on ProcessExited), not a synthetic stand-in.
        // The bus guarantees no event is lost across the replay→live transition (see
        // TerminalEventBusImpl.subscribe), so this will not miss the synthesized
        // ProcessExited emitted when the shell returns to its idle prompt.
        val result = withTimeoutOrNull(timeoutMs) {
            val ev = bus.subscribe(sessionId, afterCursor = 0L).first { e ->
                val m = matchEvent(condition, e)
                m.matched || e is TerminalEvent.SessionClosed
            }
            val m = matchEvent(condition, ev)
            when {
                m.matched -> WaitEngineOutcome.Matched(m.event ?: ev)
                ev is TerminalEvent.SessionClosed -> WaitEngineOutcome.SessionGone(ev.cause)
                else -> WaitEngineOutcome.Timeout
            }
        } ?: WaitEngineOutcome.Timeout

        return when (result) {
            is WaitEngineOutcome.Matched -> WaitResult.Matched(event = result.event)
            is WaitEngineOutcome.SessionGone -> WaitResult.SessionGone(cause = result.cause)
            WaitEngineOutcome.Timeout -> WaitResult.Timeout(waitedMs = timeoutMs)
        }
    }

    override fun register(sessionId: Long, condition: WaitCondition): Flow<WaitResult> = flow {
        // Streaming variant: emit each match as a separate WaitResult.Matched.
        bus.subscribe(sessionId, afterCursor = 0L).collect { ev ->
            val m = matchEvent(condition, ev)
            if (m.matched && m.event != null) emit(WaitResult.Matched(m.event))
            if (ev is TerminalEvent.SessionClosed) {
                emit(WaitResult.SessionGone(ev.cause))
                return@collect
            }
        }
    }

    /** Called by PtyOutputPump / EventBus dispatcher on every event (internal hook). */
    override suspend fun onEvent(event: TerminalEvent) {
        val list = waiters[event.sessionId] ?: return
        // No-op: actual matching happens in the subscriber flow per waiter.
        // This hook exists for future optimizations (e.g. direct channel dispatch).
    }

    /** Drop all waiters for a session (called on Session close). */
    fun drop(sessionId: Long) {
        waiters.remove(sessionId)
        locks.remove(sessionId)
    }

    private sealed class WaitEngineOutcome {
        data class Matched(val event: TerminalEvent) : WaitEngineOutcome()
        data class SessionGone(val cause: CloseCause) : WaitEngineOutcome()
        object Timeout : WaitEngineOutcome()
    }
}
