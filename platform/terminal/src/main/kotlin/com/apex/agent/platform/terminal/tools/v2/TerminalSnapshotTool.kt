package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add

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
) : TerminalTool {
    override val id: String = "terminal.snapshot"
    override val name: String = id
    override val description: String = """
        Return a global snapshot of all Sessions + recent events + recent output. Primary entry
        for Agent context recovery after engine restart. Also used to list sessions (mode=SESSIONS).
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"mode":{"type":"string","default":"SESSIONS"},"sessionId":{"type":"integer"},"recentEvents":{"type":"integer","default":50},"recentOutputBytes":{"type":"integer","default":4096}},"required":[]}
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

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val mode = runCatching { TerminalRuntime.SnapshotMode.valueOf(json["mode"]?.jsonPrimitive?.content ?: "SESSIONS") }.getOrDefault(TerminalRuntime.SnapshotMode.SESSIONS)
        val sessionId = json["sessionId"]?.jsonPrimitive?.content?.toLongOrNull()
        val recentEvents = json["recentEvents"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50
        val recentOutputBytes = json["recentOutputBytes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4096
        val out = execute(Input(mode, sessionId, recentEvents, recentOutputBytes))
        return buildJsonObject {
            put("globalCursor", JsonPrimitive(out.globalCursor))
            put("sessionCount", JsonPrimitive(out.sessions.size))
            // serialize session summaries
            val sessionsArray = kotlinx.serialization.json.JsonArray(out.sessions.map { sem ->
                buildJsonObject {
                    put("sessionId", JsonPrimitive(sem.session.id))
                    put("state", JsonPrimitive(sem.session.state.name))
                    put("shell", JsonPrimitive(sem.session.shell))
                    put("cwd", JsonPrimitive(sem.session.cwd))
                    put("cursor", JsonPrimitive(sem.session.cursor))
                    sem.session.lastExitCode?.let { put("lastExitCode", JsonPrimitive(it)) }
                    sem.foregroundJob?.let { j ->
                        put("fgJobId", JsonPrimitive(j.id))
                        put("fgJobState", JsonPrimitive(j.state.name))
                        put("fgJobCommand", JsonPrimitive(j.command))
                    }
                }
            })
            put("sessions", sessionsArray)
            put("recentOutput", JsonPrimitive(out.recentOutput))
        }.toString()
    }

    data class Input(
        val mode: TerminalRuntime.SnapshotMode = TerminalRuntime.SnapshotMode.FULL,
        val sessionId: Long? = null,
        val recentEvents: Int = 50,
        val recentOutputBytes: Int = 4096
    )

    data class Output(
        val sessions: List<TerminalSemanticState>,  // List<TerminalSemanticState>
        val globalCursor: Long,
        val recentEvents: List<Any>,       // List<TerminalEvent>
        val recentOutput: String
    )
}
