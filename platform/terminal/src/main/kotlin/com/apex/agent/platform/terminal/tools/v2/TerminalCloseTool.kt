package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

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
) : TerminalTool {
    override val id: String = "terminal.close"
    override val name: String = id
    override val description: String = """
        Close a Session: sends SIGHUP to foreground, reaps child, closes master fd, frees resources.
        All waiters receive SessionGone. Session state → CLOSED (terminal).
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"sessionId":{"type":"integer"},"force":{"type":"boolean","default":false}},"required":["sessionId"]}
""".trimIndent()

    suspend fun execute(input: Input): Output {
        val result = runtime.close(input.sessionId, input.force)
        return result.fold(
            onSuccess = { r -> Output(closed = r.closed, cause = r.cause, finalCursor = r.finalCursor) },
            onFailure = { throw it }
        )
    }

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val sessionId = json["sessionId"]?.jsonPrimitive?.content?.toLongOrNull() ?: throw IllegalArgumentException("sessionId required")
        val force = json["force"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val out = execute(Input(sessionId, force))
        return buildJsonObject { put("closed", JsonPrimitive(out.closed)); put("cause", JsonPrimitive(out.cause)); put("finalCursor", JsonPrimitive(out.finalCursor)) }.toString()
    }

    data class Input(val sessionId: Long, val force: Boolean = false)
    data class Output(val closed: Boolean, val cause: String, val finalCursor: Long)
}
