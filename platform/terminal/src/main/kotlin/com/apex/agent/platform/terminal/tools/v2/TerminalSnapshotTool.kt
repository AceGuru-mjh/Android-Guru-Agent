package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime

/**
 * Agent tool: terminal.snapshot
 *
 * Spec ref: ATR 2.0 Final Spec §34.8
 *
 * Return a global snapshot of all Sessions + recent events + recent output. Primary entry for
 * Agent context recovery after engine restart. Also used to list sessions (mode=SESSIONS).
 *
 * JSON Schema (input):
 *   { mode?: "FULL"|"SESSIONS"=FULL, sessionId?: int, recentEvents?: int=50, recentOutputBytes?: int=4096 }
 * JSON Schema (output):
 *   { sessions: array<object>, globalCursor: int, recentEvents: array<object>, recentOutput: string }
 * Errors: SessionNotFound
 */
class TerminalSnapshotTool(
    private val runtime: TerminalRuntime
) {
    val id: String = "terminal.snapshot"
    val description: String = """
        Return a global snapshot of all Sessions + recent events + recent output. Primary entry
        for Agent context recovery after engine restart. Also used to list sessions (mode=SESSIONS).
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val result = runtime.snapshot(
            mode = input.mode,
            sessionId = input.sessionId,
            recentEvents = input.recentEvents,
            recentOutputBytes = input.recentOutputBytes
        )
        return result.fold(
            onSuccess = { r -> Output(
                sessions = r.sessions, globalCursor = r.globalCursor,
                recentEvents = r.recentEvents, recentOutput = r.recentOutput
            ) },
            onFailure = { throw it }
        )
    }

    data class Input(
        val mode: TerminalRuntime.SnapshotMode = TerminalRuntime.SnapshotMode.FULL,
        val sessionId: Long? = null,
        val recentEvents: Int = 50,
        val recentOutputBytes: Int = 4096
    )

    data class Output(
        val sessions: List<Any>,           // List<TerminalSemanticState>
        val globalCursor: Long,
        val recentEvents: List<Any>,       // List<TerminalEvent>
        val recentOutput: String
    )
}
