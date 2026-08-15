package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.runtime.TerminalRuntime

/**
 * Agent tool: terminal.write
 *
 * Spec ref: ATR 2.0 Final Spec §34.5
 *
 * Write input to a Session. Owner is assigned automatically by Runtime (Agent tool calls → AGENT,
 * UI → USER); do NOT pass owner. Use kind=LINE to append newline, RAW for exact bytes, KEY for
 * special keys (Ctrl+C etc.). For interactive prompts detected by InputWaiting.
 *
 * JSON Schema (input):
 *   { sessionId: int, kind?: "RAW"|"LINE"|"KEY"=LINE, text?: string, key?: string }
 * JSON Schema (output):
 *   { written: bool, bytesWritten: int, cursor: int, inputOwner: "AGENT"|"USER"|"SYSTEM" }
 * Errors: SessionNotFound, SessionClosed, PermissionDenied, OwnerBusy, WriteFailed, InvalidInput
 *
 * KEY names: ENTER, TAB, BACKSPACE, ESC, CTRL_C, CTRL_D, CTRL_Z, ARROW_UP/DOWN/LEFT/RIGHT,
 *   HOME, END, DELETE, PAGE_UP, PAGE_DOWN, F1-F12
 */
class TerminalWriteTool(
    private val runtime: TerminalRuntime
) {
    val id: String = "terminal.write"
    val description: String = """
        Write input to a Session. Owner is assigned automatically by Runtime (Agent tool calls →
        AGENT, UI → USER); do NOT pass owner. Use kind=LINE to append newline, RAW for exact bytes,
        KEY for special keys (Ctrl+C etc.). For interactive prompts detected by InputWaiting.
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val key: TerminalKey? = input.key?.let { runCatching { TerminalKey.valueOf(it) }.getOrNull() }
        val result = runtime.write(
            sessionId = input.sessionId,
            owner = InputOwner.AGENT,   // auto-injected: Agent tool → AGENT
            kind = input.kind,
            text = input.text,
            key = key
        )
        return result.fold(
            onSuccess = { r -> Output(
                written = r.written, bytesWritten = r.bytesWritten,
                cursor = r.cursor, inputOwner = r.inputOwner.name
            ) },
            onFailure = { throw it }
        )
    }

    data class Input(
        val sessionId: Long,
        val kind: TerminalRuntime.WriteKind = TerminalRuntime.WriteKind.LINE,
        val text: String? = null,
        val key: String? = null
    )

    data class Output(
        val written: Boolean,
        val bytesWritten: Int,
        val cursor: Long,
        val inputOwner: String
    )
}
