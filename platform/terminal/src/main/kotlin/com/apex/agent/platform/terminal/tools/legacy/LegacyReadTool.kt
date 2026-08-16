package com.apex.agent.platform.terminal.tools.legacy

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime

/**
 * Legacy compat tool: terminal_read
 *
 * Spec ref: ATR 2.0 Final Spec §35
 *
 * Preserves the OLD contract: read pending output from a session (best-effort, non-blocking).
 *
 * Maps to: terminal.observe(mode=RAW). The new observe() is strictly better (cursor-based,
 * incremental, no duplicates) but terminal_read keeps the old simple "give me what's there" API.
 *
 * NOTE: old terminal_read had no cursor concept — it returned whatever the manager had buffered.
 * This compat version uses afterCursor=0 internally which means it returns ALL retained output
 * (up to maxBytes). For incremental reads, Agent should use terminal.observe(afterCursor=...).
 */
@Deprecated("ATR 2.0 compat alias — use the new terminal.observe/write/signal/snapshot/close API instead. Scheduled for removal in a future version.")
class LegacyReadTool(
    private val runtime: TerminalRuntime
) {
    val id: String = "terminal_read"
    val description: String = """
        [COMPAT] Read pending output from a session (non-blocking, returns all retained output
        up to maxBytes). For incremental cursor-based reads, prefer terminal.observe(mode=RAW,
        afterCursor=...). Kept for backward compat.
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val result = runtime.observe(
            sessionId = input.sessionId,
            mode = TerminalRuntime.ObserveMode.RAW,
            afterCursor = input.afterCursor ?: 0L,
            maxBytes = input.maxBytes
        )
        return result.fold(
            onSuccess = { r -> Output(
                output = r.raw ?: "",
                cursor = r.cursor,
                truncated = r.truncated,
                overrun = r.overrun
            ) },
            onFailure = { throw it }
        )
    }

    data class Input(
        val sessionId: Long,
        val maxBytes: Int = 65536,
        val afterCursor: Long? = null   // optional; null = from start (old behavior)
    )

    data class Output(
        val output: String,
        val cursor: Long,
        val truncated: Boolean,
        val overrun: Boolean
    )
}
