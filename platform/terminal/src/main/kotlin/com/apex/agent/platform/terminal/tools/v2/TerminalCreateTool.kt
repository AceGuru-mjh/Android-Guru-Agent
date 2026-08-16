package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.runtime.TerminalRuntime

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
) {
    val id: String = "terminal.create"
    val description: String = """
        Create a long-lived terminal Session (PTY + shell). A Session is a workspace, not a single
        command. Returns sessionId for subsequent run/observe/wait/write calls.
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
