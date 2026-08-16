package com.apex.agent.platform.terminal.tools.legacy

import com.apex.agent.platform.terminal.runtime.TerminalRuntime

/**
 * Legacy compat tool: terminal_close
 *
 * Spec ref: ATR 2.0 Final Spec §35
 *
 * Preserves the OLD contract: close a terminal session.
 *
 * Maps to: terminal.close(). Identical behavior; this is a thin alias.
 */
class LegacyCloseTool(
    private val runtime: TerminalRuntime
) {
    val id: String = "terminal_close"
    val description: String = """
        [COMPAT] Close a terminal session (reap child, close fd). Equivalent to terminal.close.
        Kept for backward compat.
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val result = runtime.close(input.sessionId, force = input.force)
        return result.fold(
            onSuccess = { r -> Output(closed = r.closed, cause = r.cause, finalCursor = r.finalCursor) },
            onFailure = { throw it }
        )
    }

    data class Input(
        val sessionId: Long,
        val force: Boolean = false
    )

    data class Output(
        val closed: Boolean,
        val cause: String,
        val finalCursor: Long
    )
}
