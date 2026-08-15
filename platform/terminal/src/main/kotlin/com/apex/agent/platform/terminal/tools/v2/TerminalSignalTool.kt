package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.runtime.TerminalRuntime

/**
 * Agent tool: terminal.signal
 *
 * Spec ref: ATR 2.0 Final Spec §34.6
 *
 * Send a Unix signal to the Session's foreground process group. SIGINT (Ctrl+C), SIGTERM, SIGKILL.
 * USER-initiated SIGINT produces a UserInterrupt event so the Agent can distinguish user
 * cancellation from command failure.
 *
 * JSON Schema (input):
 *   { sessionId: int, signal: "SIGINT"|"SIGTERM"|"SIGKILL"|"SIGHUP"|"SIGQUIT", jobId?: int }
 * JSON Schema (output):
 *   { sent: bool, signal: string, targetJobId: int|null }
 * Errors: SessionNotFound, SessionClosed, PermissionDenied, ProcessExited, InvalidInput
 */
class TerminalSignalTool(
    private val runtime: TerminalRuntime
) {
    val id: String = "terminal.signal"
    val description: String = """
        Send a Unix signal to the Session's foreground process group. SIGINT (Ctrl+C), SIGTERM,
        SIGKILL. USER-initiated SIGINT produces a UserInterrupt event so the Agent can distinguish
        user cancellation from command failure.
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val signal: UnixSignal = runCatching { UnixSignal.valueOf(input.signal) }
            .getOrElse { throw IllegalArgumentException("TerminalError:InvalidInput — unknown signal ${input.signal}") }
        val result = runtime.signal(
            sessionId = input.sessionId,
            signal = signal,
            owner = InputOwner.AGENT,   // auto-injected
            jobId = input.jobId
        )
        return result.fold(
            onSuccess = { r -> Output(sent = r.sent, signal = r.signal.name, targetJobId = r.targetJobId) },
            onFailure = { throw it }
        )
    }

    data class Input(
        val sessionId: Long,
        val signal: String,
        val jobId: Long? = null
    )

    data class Output(
        val sent: Boolean,
        val signal: String,
        val targetJobId: Long?
    )
}
