package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime

/**
 * Agent tool: terminal.close
 *
 * Spec ref: ATR 2.0 Final Spec §34.9
 *
 * Close a Session: sends SIGHUP to foreground, reaps child, closes master fd, frees resources.
 * All waiters receive SessionGone. Session state → CLOSED (terminal).
 *
 * JSON Schema (input):
 *   { sessionId: int, force?: bool=false }
 * JSON Schema (output):
 *   { closed: bool, cause: "USER"|"NORMAL"|"BROKEN", finalCursor: int }
 * Errors: SessionNotFound, SessionClosed
 */
class TerminalCloseTool(
    private val runtime: TerminalRuntime
) {
    val id: String = "terminal.close"
    val description: String = """
        Close a Session: sends SIGHUP to foreground, reaps child, closes master fd, frees resources.
        All waiters receive SessionGone. Session state → CLOSED (terminal).
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val result = runtime.close(input.sessionId, input.force)
        return result.fold(
            onSuccess = { r -> Output(closed = r.closed, cause = r.cause, finalCursor = r.finalCursor) },
            onFailure = { throw it }
        )
    }

    data class Input(val sessionId: Long, val force: Boolean = false)
    data class Output(val closed: Boolean, val cause: String, val finalCursor: Long)
}
