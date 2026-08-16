package com.apex.agent.platform.terminal.tools.legacy

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
) : TerminalTool {
    override val id: String = "terminal_signal"
    override val name: String = id
    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "sessionId": { "type": "integer", "description": "Target session id" },
            "signal":    { "type": "string",  "description": "SIGINT | SIGTERM | SIGKILL | SIGHUP | SIGQUIT" }
          },
          "required": ["sessionId", "signal"]
        }
    """.trimIndent()
    override val description: String = """
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

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val sessionId = json["sessionId"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw IllegalArgumentException("TerminalError:InvalidInput — 'sessionId' (long) required")
        val signal = json["signal"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("TerminalError:InvalidInput — 'signal' required")
        val out = execute(Input(sessionId, signal))
        return buildJsonObject {
            put("sent", JsonPrimitive(out.sent))
            put("signal", JsonPrimitive(out.signal))
        }.toString()
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
