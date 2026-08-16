package com.apex.agent.platform.terminal.tools.legacy

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Legacy compat tool: terminal_send
 *
 * Spec ref: ATR 2.0 Final Spec §35
 *
 * Preserves the OLD contract: send input (text or special key) to a session.
 *
 * Maps to: terminal.write(kind=LINE|KEY). Owner auto-injected as AGENT.
 * Old terminal_send accepted either `text` or `key` — this compat version does the same.
 */
@Deprecated("ATR 2.0 compat alias — use the new terminal.observe/write/signal/snapshot/close API instead. Scheduled for removal in a future version.")
class LegacySendTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "terminal_send"
    override val name: String = id
    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "sessionId": { "type": "integer", "description": "Target session id" },
            "text": { "type": "string", "description": "Text to send (a line or raw bytes)" },
            "key":  { "type": "string", "description": "Special key, e.g. CTRL_C, ENTER" },
            "raw":  { "type": "boolean", "description": "If true, text is raw bytes (no newline)", "default": false }
          },
          "required": ["sessionId"]
        }
    """.trimIndent()
    override val description: String = """
        [COMPAT] Send input (text line or special key) to a session. For new code prefer
        terminal.write (explicit RAW/LINE/KEY kind). Kept for backward compat.
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val kind: TerminalRuntime.WriteKind
        val text: String?
        val key: TerminalKey?
        when {
            input.key != null -> {
                kind = TerminalRuntime.WriteKind.KEY
                text = null
                key = runCatching { TerminalKey.valueOf(input.key) }.getOrElse {
                    throw IllegalArgumentException("TerminalError:InvalidInput — unknown key ${input.key}")
                }
            }
            input.text != null -> {
                kind = if (input.raw == true) TerminalRuntime.WriteKind.RAW else TerminalRuntime.WriteKind.LINE
                text = input.text
                key = null
            }
            else -> throw IllegalArgumentException("TerminalError:InvalidInput — either text or key required")
        }
        val result = runtime.write(
            sessionId = input.sessionId,
            owner = InputOwner.AGENT,
            kind = kind, text = text, key = key
        )
        return result.fold(
            onSuccess = { r -> Output(written = r.written, bytesWritten = r.bytesWritten, cursor = r.cursor) },
            onFailure = { throw it }
        )
    }

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val sessionId = json["sessionId"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw IllegalArgumentException("TerminalError:InvalidInput — 'sessionId' (long) required")
        val text = json["text"]?.jsonPrimitive?.content
        val key = json["key"]?.jsonPrimitive?.content
        val raw = json["raw"]?.jsonPrimitive?.content == "true"
        val out = execute(Input(sessionId, text, key, raw))
        return buildJsonObject {
            put("written", JsonPrimitive(out.written))
            put("bytesWritten", JsonPrimitive(out.bytesWritten))
            put("cursor", JsonPrimitive(out.cursor))
        }.toString()
    }

    data class Input(
        val sessionId: Long,
        val text: String? = null,
        val key: String? = null,        // e.g. "CTRL_C", "ENTER"
        val raw: Boolean? = false       // if true, text is raw bytes (no newline)
    )

    data class Output(
        val written: Boolean,
        val bytesWritten: Int,
        val cursor: Long
    )
}
