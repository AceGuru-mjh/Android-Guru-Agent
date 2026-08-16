package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Agent tool: terminal.create
 *
 * Spec ref: ATR 2.0 Final Spec §34.1
 *
 * Creates a long-lived Session (PTY + shell). A Session is a workspace, not a single command.
 * Returns sessionId for subsequent run/observe/wait/write calls.
 *
 * JSON Schema (input):
 *   { shell?: string="/system/bin/sh", cwd?: string="/sdcard", rows?: int=24, cols?: int=80,
 *     env?: object<string,string>, privilege?: "NORMAL"|"SHIZUKU"|"ROOT"=NORMAL }
 * JSON Schema (output):
 *   { sessionId: int, pid: int, shell: string, cwd: string, rows: int, cols: int,
 *     privilege: string, state: "READY"|"STARTING"|"BROKEN", cursor: int }
 * Errors: PtyUnavailable, InvalidInput, PermissionDenied
 *
 * Phase 2 status: IMPLEMENTED (internal test only — NOT registered to ToolRegistry yet,
 * per Spec §45 Phase 2 "新 9 工具实现，但不注册到 ToolRegistry").
 */
class TerminalCreateTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "terminal.create"
    override val name: String = id
    override val description: String = """
        Create a long-lived terminal Session (PTY + shell). A Session is a workspace, not a single
        command. Returns sessionId for subsequent run/observe/wait/write calls.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"shell":{"type":"string","default":"/system/bin/sh"},"cwd":{"type":"string","default":"/sdcard"},"rows":{"type":"integer","default":24},"cols":{"type":"integer","default":80}},"required":[]}
""".trimIndent()

    suspend fun execute(input: Input): Output {
        val result = runtime.create(
            shell = input.shell,
            cwd = input.cwd,
            rows = input.rows,
            cols = input.cols,
            env = input.env,
            privilege = input.privilege
        )
        return result.fold(
            onSuccess = { r -> Output(
                sessionId = r.sessionId, pid = r.pid, shell = r.shell, cwd = r.cwd,
                rows = r.rows, cols = r.cols, privilege = r.privilege.name,
                state = r.state, cursor = r.cursor
            ) },
            onFailure = { throw it }
        )
    }

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val shell = json["shell"]?.jsonPrimitive?.content ?: "/system/bin/sh"
        val cwd = json["cwd"]?.jsonPrimitive?.content ?: "/sdcard"
        val rows = json["rows"]?.jsonPrimitive?.content?.toIntOrNull() ?: 24
        val cols = json["cols"]?.jsonPrimitive?.content?.toIntOrNull() ?: 80
        val out = execute(Input(shell, cwd, rows, cols))
        return buildJsonObject { put("sessionId", JsonPrimitive(out.sessionId)); put("pid", JsonPrimitive(out.pid)); put("shell", JsonPrimitive(out.shell)); put("cwd", JsonPrimitive(out.cwd)); put("state", JsonPrimitive(out.state)); put("cursor", JsonPrimitive(out.cursor)) }.toString()
    }

    data class Input(
        val shell: String = "/system/bin/sh",
        val cwd: String = "/sdcard",
        val rows: Int = 24,
        val cols: Int = 80,
        val env: Map<String, String> = emptyMap(),
        val privilege: PrivilegeLevel = PrivilegeLevel.NORMAL
    )

    data class Output(
        val sessionId: Long,
        val pid: Int,
        val shell: String,
        val cwd: String,
        val rows: Int,
        val cols: Int,
        val privilege: String,
        val state: String,
        val cursor: Long
    )
}
