package com.apex.agent.platform.terminal.tools.legacy

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.runtime.TerminalRuntime

/**
 * Legacy compat tool: terminal_signal
 *
 * Spec ref: ATR 2.0 Final Spec §35
 *
 * Preserves the OLD contract: send a Unix signal to a session's foreground process.
 *
 * Maps to: terminal.signal(). Identical behavior; this is a thin alias.
 */
@Deprecated("ATR 2.0 compat alias — use the new terminal.observe/write/signal/snapshot/close API instead. Scheduled for removal in a future version.")
class LegacySignalTool(
    private val runtime: TerminalRuntime
) {
    val id: String = "terminal_signal"
    val description: String = """
        [COMPAT] Send a Unix signal (SIGINT/SIGTERM/SIGKILL) to the session's foreground process.
        Equivalent to terminal.signal. Kept for backward compat.
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val signal: UnixSignal = runCatching { UnixSignal.valueOf(input.signal) }
            .getOrElse { throw IllegalArgumentException("TerminalError:InvalidInput — unknown signal ${input.signal}") }
        val result = runtime.signal(
            sessionId = input.sessionId,
            signal = signal,
            owner = InputOwner.AGENT,
            jobId = null
        )
        return result.fold(
            onSuccess = { r -> Output(sent = r.sent, signal = r.signal.name) },
            onFailure = { throw it }
        )
    }

    data class Input(
        val sessionId: Long,
        val signal: String     // "SIGINT" | "SIGTERM" | "SIGKILL" | "SIGHUP" | "SIGQUIT"
    )

    data class Output(
        val sent: Boolean,
        val signal: String
    )
}
