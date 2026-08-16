package com.apex.agent.platform.terminal.tools.legacy

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Legacy compat tool: terminal_close
 *
 * Spec ref: ATR 2.0 Final Spec §35
 *
 * Preserves the OLD contract: close a terminal session.
 *
 * Maps to: terminal.close(). Identical behavior; this is a thin alias.
 */
@Deprecated("ATR 2.0 compat alias — use the new terminal.observe/write/signal/snapshot/close API instead. Scheduled for removal in a future version.")
class LegacyCloseTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "terminal_close"
    override val name: String = id
    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "sessionId": { "type": "integer", "description": "Target session id" },
            "force":     { "type": "boolean", "description": "Force close ignoring in-flight ops", "default": false }
          },
          "required": ["sessionId"]
        }
    """.trimIndent()
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

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val sessionId = json["sessionId"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("TerminalError:InvalidInput — 'sessionId' (long) required")
        val force = json["force"]?.jsonPrimitive?.booleanOrNull ?: false
        return Json.encodeToString(execute(Input(sessionId, force)))
    }

    data class Input(
        val sessionId: Long,
        val force: Boolean = false
    )

    @Serializable
    data class Output(
        val closed: Boolean,
        val cause: String,
        val finalCursor: Long
    )
}
