package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: terminal.create
 *
 * Spec ref: ATR 2.0 Final Spec §34.1 + T73 backend routing
 *
 * Creates a long-lived Session (PTY + shell). A Session is a workspace, not a single command.
 * Returns sessionId for subsequent run/observe/wait/write calls.
 *
 * JSON Schema (input):
 *   { shell?: string="/system/bin/sh", cwd?: string="/sdcard", rows?: int=24, cols?: int=80,
 *     env?: object<string,string>, privilege?: "NORMAL"|"SHIZUKU"|"ROOT"=NORMAL,
 *     backend?: string="local", workspaceId?: string }   // T75: workspaceId 仅 linux-ubuntu
 * JSON Schema (output):
 *   { sessionId: int, pid: int, shell: string, cwd: string, rows: int, cols: int,
 *     privilege: string, state: "READY"|"STARTING"|"BROKEN", cursor: int,
 *     backendId: string, runtimeType: string, rootfsId?: string, guestCwd?: string,
 *     workspaceId?: string }
 * Errors: PtyUnavailable, InvalidInput, PermissionDenied, BackendNotFound,
 *         RootfsNotReady（先调 terminal.ubuntu.install）, BackendFailed,
 *         WorkspaceError:InvalidId（workspaceId 非法）
 */
class TerminalCreateTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "terminal.create"
    override val name: String = id
    override val description: String = """
        Create a long-lived terminal Session (PTY + shell). A Session is a workspace, not a single
        command. Returns sessionId for subsequent run/observe/wait/write calls.
        backend="local" runs the Android shell; backend="linux-ubuntu" runs Ubuntu 24.04 via PRoot
        (requires the rootfs to be installed — check terminal.backends, install via terminal.ubuntu.install).
        For linux-ubuntu, workspaceId selects an isolated file area bound to guest /workspace
        (persists across sessions; unknown valid ids are auto-created; default: "default" —
        manage via terminal.workspaces).
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"shell":{"type":"string","default":"/system/bin/sh","description":"Android shell path (local backend only; linux-ubuntu always uses /bin/bash)"},"cwd":{"type":"string","default":"/sdcard","description":"Initial working directory. linux-ubuntu: guest path (/workspace default; relative paths land under /workspace)"},"rows":{"type":"integer","default":24},"cols":{"type":"integer","default":80},"env":{"type":"object","additionalProperties":{"type":"string"},"description":"Extra environment variables (local: appended to defaults; linux-ubuntu: passed into the guest)"},"backend":{"type":"string","default":"local","enum":["local","linux-ubuntu"],"description":"Execution backend: local = Android shell, linux-ubuntu = Ubuntu 24.04 via PRoot"},"workspaceId":{"type":"string","description":"linux-ubuntu only: isolated workspace id bound to guest /workspace. Pattern: lowercase alphanumeric, may contain - and _, max 64 chars. Unknown valid ids are auto-created. Ignored (error) for local backend."}},"required":[]}
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val result = runtime.create(
            shell = input.shell,
            cwd = input.cwd,
            rows = input.rows,
            cols = input.cols,
            env = input.env,
            privilege = input.privilege,
            backendId = input.backend,
            workspaceId = input.workspaceId
        )
        return result.fold(
            onSuccess = { r -> Output(
                sessionId = r.sessionId, pid = r.pid, shell = r.shell, cwd = r.cwd,
                rows = r.rows, cols = r.cols, privilege = r.privilege.name,
                state = r.state, cursor = r.cursor,
                backendId = r.backendId, runtimeType = r.runtimeType,
                rootfsId = r.rootfsId, guestCwd = r.guestCwd,
                workspaceId = r.workspaceId
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
        val backend = json["backend"]?.jsonPrimitive?.content ?: "local"
        val workspaceId = json["workspaceId"]?.jsonPrimitive?.contentOrNull
        val env = json["env"]?.jsonObject?.entries?.associate {
            it.key to (it.value as? JsonPrimitive ?: JsonPrimitive("")).content
        } ?: emptyMap()
        val out = execute(Input(shell, cwd, rows, cols, env, PrivilegeLevel.NORMAL, backend, workspaceId))
        return buildJsonObject {
            put("sessionId", JsonPrimitive(out.sessionId)); put("pid", JsonPrimitive(out.pid))
            put("shell", JsonPrimitive(out.shell)); put("cwd", JsonPrimitive(out.cwd))
            put("state", JsonPrimitive(out.state)); put("cursor", JsonPrimitive(out.cursor))
            put("backendId", JsonPrimitive(out.backendId))
            put("runtimeType", JsonPrimitive(out.runtimeType))
            out.rootfsId?.let { put("rootfsId", JsonPrimitive(it)) }
            out.guestCwd?.let { put("guestCwd", JsonPrimitive(it)) }
            out.workspaceId?.let { put("workspaceId", JsonPrimitive(it)) }
        }.toString()
    }

    data class Input(
        val shell: String = "/system/bin/sh",
        val cwd: String = "/sdcard",
        val rows: Int = 24,
        val cols: Int = 80,
        val env: Map<String, String> = emptyMap(),
        val privilege: PrivilegeLevel = PrivilegeLevel.NORMAL,
        val backend: String = "local",
        val workspaceId: String? = null
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
        val cursor: Long,
        val backendId: String = "local",
        val runtimeType: String = "ANDROID_LOCAL",
        val rootfsId: String? = null,
        val guestCwd: String? = null,
        val workspaceId: String? = null
    )
}
