package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
// NOTE: `AgentTool` interface is provided by :core:tool-registry. We reference it by FQN-style
// assumption here; the real repo's package is `com.apex.agent.core.toolregistry.AgentTool`.
// This file is a SCAFFOLD — wire to the real AgentTool base in Phase 2.

/**
 * Agent tool: terminal.run
 *
 * Spec ref: ATR 2.0 Final Spec §34.2
 *
 * Runs a command in a Session. NON-BLOCKING: returns a Job handle immediately.
 * Use terminal.wait(PROCESS_EXITED, jobId) to block until done.
 * Use terminal.observe(afterCursor=startCursor) to read this job's output incrementally.
 *
 * Long-running / interactive / background commands are all supported.
 *
 * JSON Schema (input):
 *   { sessionId: int, command: string, background?: bool=false, timeoutMs?: int=0 }
 * JSON Schema (output):
 *   { jobId: int, sessionId: int, state: "RUNNING"|"WAITING_INPUT"|"FAILED",
 *     startCursor: int, owner: "AGENT"|"USER"|"SYSTEM", background: bool }
 * Errors: SessionNotFound, SessionClosed, PermissionDenied, WriteFailed, InvalidInput, OwnerBusy
 */
class TerminalRunTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "terminal.run"
    override val name: String = id
    override val description: String = """
        Run a command in a Session. Non-blocking: returns a Job immediately.
        Use terminal.wait(PROCESS_EXITED) to block until done, and terminal.observe(afterCursor=startCursor)
        to read incremental output. Long-running / interactive / background commands are all supported.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"sessionId":{"type":"integer"},"command":{"type":"string"},"background":{"type":"boolean","default":false},"timeoutMs":{"type":"integer","default":0}},"required":["sessionId","command"]}
""".trimIndent()

    suspend fun execute(input: Input): Output {
        // Owner is AUTO-INJECTED by Runtime based on call origin.
        // Agent tool calls → AGENT. This tool MUST NOT accept owner as a parameter (Spec §14).
        val result = runtime.run(
            sessionId = input.sessionId,
            command = input.command,
            owner = InputOwner.AGENT,
            background = input.background,
            timeoutMs = input.timeoutMs
        )
        return result.fold(
            onSuccess = { r ->
                Output(
                    jobId = r.jobId,
                    sessionId = r.sessionId,
                    state = r.state,
                    startCursor = r.startCursor,
                    owner = r.owner.name,
                    background = r.background
                )
            },
            onFailure = { throw it }
        )
    }

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val sessionId = json["sessionId"]?.jsonPrimitive?.content?.toLongOrNull() ?: throw IllegalArgumentException("sessionId required")
        val command = json["command"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("command required")
        val background = json["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val timeoutMs = json["timeoutMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val out = execute(Input(sessionId, command, background, timeoutMs))
        return buildJsonObject { put("jobId", JsonPrimitive(out.jobId)); put("sessionId", JsonPrimitive(out.sessionId)); put("state", JsonPrimitive(out.state)); put("startCursor", JsonPrimitive(out.startCursor)); put("owner", JsonPrimitive(out.owner)); put("background", JsonPrimitive(out.background)) }.toString()
    }

    data class Input(
        val sessionId: Long,
        val command: String,
        val background: Boolean = false,
        val timeoutMs: Long = 0L
    )

    data class Output(
        val jobId: Long,
        val sessionId: Long,
        val state: String,
        val startCursor: Long,
        val owner: String,
        val background: Boolean
    )
}
