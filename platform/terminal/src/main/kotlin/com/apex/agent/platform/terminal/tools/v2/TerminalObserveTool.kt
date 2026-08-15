package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime

/**
 * Agent tool: terminal.observe
 *
 * Spec ref: ATR 2.0 Final Spec §34.3 — the core perception API.
 *
 * Default mode=SEMANTIC returns machine-readable state (session/job/input/cursor) with NO raw
 * output — token-cheap.
 *   mode=EVENT   → incremental events since afterCursor
 *   mode=SCREEN  → parsed screen (for TUI like vim/top)
 *   mode=RAW     → raw bytes since afterCursor
 *
 * JSON Schema (input):
 *   { sessionId: int, mode?: "SEMANTIC"|"EVENT"|"SCREEN"|"RAW"=SEMANTIC,
 *     afterCursor?: int=0, maxBytes?: int=12000, maxEvents?: int=200 }
 * JSON Schema (output):
 *   { mode, sessionId, cursor, startCursor?, endCursor?, truncated, overrun, oldestCursor?,
 *     semantic?, events?, screen?, raw? }
 * Errors: SessionNotFound, SessionClosed, BufferOverrun, ReadFailed
 */
class TerminalObserveTool(
    private val runtime: TerminalRuntime
) {
    val id: String = "terminal.observe"
    val description: String = """
        Observe terminal state. Default mode=SEMANTIC returns machine-readable state (session/job/input/cursor)
        with NO raw output — token-cheap. mode=EVENT returns incremental events since afterCursor.
        mode=SCREEN returns parsed screen (for TUI like vim/top). mode=RAW returns raw bytes since afterCursor.
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val result = runtime.observe(
            sessionId = input.sessionId,
            mode = input.mode,
            afterCursor = input.afterCursor,
            maxBytes = input.maxBytes,
            maxEvents = input.maxEvents
        )
        return result.fold(
            onSuccess = { r ->
                Output(
                    mode = r.mode.name,
                    sessionId = r.sessionId,
                    cursor = r.cursor,
                    startCursor = r.startCursor,
                    endCursor = r.endCursor,
                    truncated = r.truncated,
                    overrun = r.overrun,
                    oldestCursor = r.oldestCursor,
                    semantic = r.semantic,           // serialized by tool layer (kotlinx.serialization)
                    events = r.events,
                    screen = r.screen,
                    raw = r.raw
                )
            },
            onFailure = { throw it }
        )
    }

    data class Input(
        val sessionId: Long,
        val mode: TerminalRuntime.ObserveMode = TerminalRuntime.ObserveMode.SEMANTIC,
        val afterCursor: Long = 0,
        val maxBytes: Int = 12000,
        val maxEvents: Int = 200
    )

    data class Output(
        val mode: String,
        val sessionId: Long,
        val cursor: Long,
        val startCursor: Long?,
        val endCursor: Long?,
        val truncated: Boolean,
        val overrun: Boolean,
        val oldestCursor: Long?,
        val semantic: Any?,              // TerminalSemanticState — serialized to JSON by tool layer
        val events: List<Any>?,          // List<TerminalEvent>
        val screen: Any?,                // TerminalScreenState
        val raw: String?
    )
}
