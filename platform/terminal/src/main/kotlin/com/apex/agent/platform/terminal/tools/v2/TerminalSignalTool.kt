package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

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
) : TerminalTool {
    override val id: String = "terminal.signal"
    override val name: String = id
    override val description: String = """
        Send a Unix signal to the Session's foreground process group. SIGINT (Ctrl+C), SIGTERM,
        SIGKILL. USER-initiated SIGINT produces a UserInterrupt event so the Agent can distinguish
        user cancellation from command failure.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"sessionId":{"type":"integer"},"signal":{"type":"string"},"jobId":{"type":"integer"}},"required":["sessionId","signal"]}
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

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val sessionId = json["sessionId"]?.jsonPrimitive?.content?.toLongOrNull() ?: throw IllegalArgumentException("sessionId required")
        val signal = runCatching { UnixSignal.valueOf(json["signal"]?.jsonPrimitive?.content ?: "SIGINT") }.getOrNull() ?: throw IllegalArgumentException("invalid signal")
        val jobId = json["jobId"]?.jsonPrimitive?.content?.toLongOrNull()
        val out = execute(Input(sessionId, signal, jobId))
        return buildJsonObject { put("sent", JsonPrimitive(out.sent)); put("signal", JsonPrimitive(out.signal)); put("targetJobId", if (out.targetJobId != null) JsonPrimitive(out.targetJobId) else JsonPrimitive("null")) }.toString()
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
