package com.apex.agent.platform.terminal.tools.legacy

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Legacy compat tool: terminal_list
 *
 * Spec ref: ATR 2.0 Final Spec §35
 *
 * Preserves the OLD contract: list active terminal sessions.
 *
 * Maps to: terminal.snapshot(mode=SESSIONS). Returns just the session ids (old shape).
 */
@Deprecated("ATR 2.0 compat alias — use the new terminal.observe/write/signal/snapshot/close API instead. Scheduled for removal in a future version.")
class LegacyListTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "terminal_list"
    override val name: String = id
    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "dummy": { "type": "integer", "description": "ignored — kept for symmetry" }
          }
        }
    """.trimIndent()
    override val description: String = """
        [COMPAT] List active terminal sessions (returns session ids). For full state use
        terminal.snapshot(mode=FULL). Kept for backward compat.
    """.trimIndent()

    suspend fun execute(input: Input = Input()): Output {
        val result = runtime.snapshot(mode = TerminalRuntime.SnapshotMode.SESSIONS)
        return result.fold(
            onSuccess = { r ->
                Output(
                    sessions = r.sessions.map { s ->
                        SessionInfo(
                            sessionId = s.session.id,
                            pid = s.session.pid,
                            shell = s.session.shell,
                            cwd = s.session.cwd,
                            state = s.session.state.name,
                            cursor = s.session.cursor
                        )
                    }
                )
            },
            onFailure = { throw it }
        )
    }

    override suspend fun invoke(arguments: String): String {
        val out = execute(Input())
        return buildJsonObject {
            put("sessions", buildJsonArray {
                for (s in out.sessions) {
                    add(buildJsonObject {
                        put("sessionId", s.sessionId)
                        put("pid", s.pid)
                        put("shell", s.shell)
                        put("cwd", s.cwd)
                        put("state", s.state)
                        put("cursor", s.cursor)
                    })
                }
            })
        }.toString()
    }

    data class Input(val dummy: Int = 0)   // old terminal_list took no args; keep Input for symmetry

    data class Output(val sessions: List<SessionInfo>)

    data class SessionInfo(
        val sessionId: Long,
        val pid: Int,
        val shell: String,
        val cwd: String,
        val state: String,
        val cursor: Long
    )
}
