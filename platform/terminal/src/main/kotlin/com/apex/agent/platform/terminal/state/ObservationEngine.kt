package com.apex.agent.platform.terminal.state

import com.apex.agent.platform.terminal.buffer.TerminalOutputBuffer
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventLog
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.screen.VirtualTerminal

/**
 * Formalized ObservationEngine. Encapsulates the 4 observe modes so the logic isn't inline
 * in TerminalRuntimeImpl.
 *
 * Spec ref: ATR 2.0 Final Spec §30 (ObservationEngine)
 *
 * Token-cost priority (Spec §30.5): SEMANTIC < EVENT < SCREEN < RAW.
 * Agent defaults to SEMANTIC + incremental EVENT.
 *
 * Modes:
 *   SEMANTIC — machine-readable state (session/job/input/cursor), NO raw output. Token-cheap.
 *   EVENT    — incremental events since afterCursor (capped at maxEvents).
 *   SCREEN   — parsed screen (renderedText + cursor + dims) for TUI (vim/top).
 *   RAW      — raw bytes since afterCursor (capped at maxBytes). Lowest priority; debug/recording.
 *
 * Cursor contract (Spec §13): previous.endCursor == next.startCursor. On overrun
 * (afterCursor < oldestCursor), return overrun=true + oldestCursor + empty bytes;
 * caller re-syncs using the returned `cursor` field.
 */
class ObservationEngine(
    private val eventLog: TerminalEventLog,
    private val ringBuffer: TerminalOutputBuffer,
    private val virtualTerminal: VirtualTerminal,
    private val semanticReducer: SemanticStateReducer
) {
    /**
     * Push-based screen state (Spec §41 — event-driven, NOT polling).
     * PtyOutputPump calls [refreshScreenState] after each VT feed; UI collects this Flow.
     * This replaces the old 50ms observe(SCREEN) polling loop.
     */
    private val _screenState = MutableStateFlow(virtualTerminal.snapshot())
    val screenState: StateFlow<com.apex.agent.platform.terminal.screen.TerminalScreenState> = _screenState.asStateFlow()

    /** Called by PtyOutputPump after feeding bytes to VT — pushes new screen snapshot. */
    fun refreshScreenState() {
        _screenState.value = virtualTerminal.snapshot()
    }

    /** Push-based semantic state (from SemanticStateReducer, already a StateFlow). */
    val semanticState: StateFlow<TerminalSemanticState> get() = semanticReducer.state

    /**
     * @param sessionId   target session
     * @param mode        one of SEMANTIC / EVENT / SCREEN / RAW
     * @param afterCursor for EVENT/RAW: return data after this cursor (use previous endCursor)
     * @param maxBytes    for RAW/SCREEN: max bytes to return
     * @param maxEvents   for EVENT: max events to return
     * @return ObservationResult with the mode-appropriate fields populated
     */
    suspend fun observe(
        sessionId: Long,
        mode: TerminalRuntime.ObserveMode,
        afterCursor: Long,
        maxBytes: Int,
        maxEvents: Int
    ): TerminalRuntime.ObserveResult {
        val currentCursor = ringBuffer.totalCursor
        return when (mode) {
            TerminalRuntime.ObserveMode.SEMANTIC -> {
                TerminalRuntime.ObserveResult(
                    mode = mode,
                    sessionId = sessionId,
                    cursor = currentCursor,
                    semantic = semanticReducer.snapshot()
                )
            }

            TerminalRuntime.ObserveMode.EVENT -> {
                val events = eventLog.query(sessionId, afterCursor, maxEvents)
                val endCursor = events.lastOrNull { it.cursor >= 0 }?.cursor ?: afterCursor
                TerminalRuntime.ObserveResult(
                    mode = mode,
                    sessionId = sessionId,
                    cursor = currentCursor,
                    startCursor = afterCursor,
                    endCursor = endCursor,
                    truncated = events.size >= maxEvents,
                    overrun = false,
                    events = events
                )
            }

            TerminalRuntime.ObserveMode.SCREEN -> {
                TerminalRuntime.ObserveResult(
                    mode = mode,
                    sessionId = sessionId,
                    cursor = currentCursor,
                    screen = virtualTerminal.snapshot()
                )
            }

            TerminalRuntime.ObserveMode.RAW -> {
                val slice = ringBuffer.getSince(afterCursor, maxBytes)
                TerminalRuntime.ObserveResult(
                    mode = mode,
                    sessionId = sessionId,
                    cursor = currentCursor,
                    startCursor = slice.startCursor,
                    endCursor = slice.endCursor,
                    truncated = slice.truncated,
                    overrun = slice.overrun,
                    oldestCursor = if (slice.overrun) ringBuffer.oldestCursor else null,
                    raw = String(slice.bytes, Charsets.UTF_8)
                )
            }
        }
    }
}
