package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime

/**
 * Agent tool: terminal.resize
 *
 * Spec ref: ATR 2.0 Final Spec §34.7
 *
 * Resize the Session's PTY (sends SIGWINCH). Updates VirtualTerminal dimensions. Useful before
 * running TUI programs (vim/top) to fit screen.
 *
 * JSON Schema (input):
 *   { sessionId: int, rows: int, cols: int }
 * JSON Schema (output):
 *   { resized: bool, rows: int, cols: int }
 * Errors: SessionNotFound, SessionClosed, InvalidInput
 */
class TerminalResizeTool(
    private val runtime: TerminalRuntime
) {
    val id: String = "terminal.resize"
    val description: String = """
        Resize the Session's PTY (sends SIGWINCH). Updates VirtualTerminal dimensions. Useful
        before running TUI programs (vim/top) to fit screen.
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        require(input.rows >= 1 && input.cols >= 1) {
            "TerminalError:InvalidInput — rows and cols must be ≥ 1"
        }
        val result = runtime.resize(input.sessionId, input.rows, input.cols)
        return result.fold(
            onSuccess = { r -> Output(resized = r.resized, rows = r.rows, cols = r.cols) },
            onFailure = { throw it }
        )
    }

    data class Input(val sessionId: Long, val rows: Int, val cols: Int)
    data class Output(val resized: Boolean, val rows: Int, val cols: Int)
}
