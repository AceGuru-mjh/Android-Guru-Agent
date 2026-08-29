package com.apex.agent.platform.terminal.di

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.v2.TerminalBackendsTool
import com.apex.agent.platform.terminal.tools.v2.TerminalCloseTool
import com.apex.agent.platform.terminal.tools.v2.TerminalCreateTool
import com.apex.agent.platform.terminal.tools.v2.TerminalObserveTool
import com.apex.agent.platform.terminal.tools.v2.TerminalResizeTool
import com.apex.agent.platform.terminal.tools.v2.TerminalRunTool
import com.apex.agent.platform.terminal.tools.v2.TerminalSignalTool
import com.apex.agent.platform.terminal.tools.v2.TerminalSnapshotTool
import com.apex.agent.platform.terminal.tools.v2.TerminalUbuntuInstallTool
import com.apex.agent.platform.terminal.tools.v2.TerminalWaitTool
import com.apex.agent.platform.terminal.tools.v2.TerminalWorkspacesTool
import com.apex.agent.platform.terminal.tools.v2.TerminalWriteTool
import com.apex.agent.platform.terminal.tools.legacy.LegacyCloseTool
import com.apex.agent.platform.terminal.tools.legacy.LegacyExecTool
import com.apex.agent.platform.terminal.tools.legacy.LegacyListTool
import com.apex.agent.platform.terminal.tools.legacy.LegacyReadTool
import com.apex.agent.platform.terminal.tools.legacy.LegacySendTool
import com.apex.agent.platform.terminal.tools.legacy.LegacySignalTool

/**
 * Tool registration guide for `app/.../di/ToolModule.kt`.
 *
 * Spec ref: ATR 2.0 Final Spec §44.4 (ToolModule REWRITE) / §46 (terminal_exec id collision fix)
 *
 * This is a GUIDE — the real repo's ToolModule.kt is an Hilt module that registers AgentTools
 * into the ToolRegistry. Below is the target registration block (pseudocode using the repo's
 * `registry.register(...)` pattern).
 *
 * ════════════════════════════════════════════════════════════════════════
 * PHASE 3 TARGET REGISTRATION (replace the old block at ToolModule.kt L139 + L204-209)
 * ════════════════════════════════════════════════════════════════════════
 *
 *   // ── 9 new Agent Terminal tools (preferred) ──
 *   registry.register(TerminalCreateTool(runtime))
 *   registry.register(TerminalRunTool(runtime))
 *   registry.register(TerminalObserveTool(runtime))
 *   registry.register(TerminalWaitTool(runtime))
 *   registry.register(TerminalWriteTool(runtime))
 *   registry.register(TerminalSignalTool(runtime))
 *   registry.register(TerminalResizeTool(runtime))
 *   registry.register(TerminalSnapshotTool(runtime))
 *   registry.register(TerminalCloseTool(runtime))
 *
 *   // ── 6 legacy compat aliases (deprecated, kept for 1 version) ──
 *   registry.register(LegacyExecTool(runtime))      // id="terminal_exec"     → run+wait+observe
 *   registry.register(LegacyReadTool(runtime))      // id="terminal_read"      → observe(RAW)
 *   registry.register(LegacySendTool(runtime))      // id="terminal_send"      → write
 *   registry.register(LegacySignalTool(runtime))    // id="terminal_signal"    → signal
 *   registry.register(LegacyListTool(runtime))      // id="terminal_list"      → snapshot(SESSIONS)
 *   registry.register(LegacyCloseTool(runtime))     // id="terminal_close"     → close
 *
 *   // ── DELETED: StreamingTerminalExecTool (id collision with terminal_exec) ──
 *   // The old line 139 `registry.register(SafeAgentTool(StreamingTerminalExecTool(terminalManager)))`
 *   // is REMOVED. Streaming is now: terminal.run (returns jobId) + terminal.observe(afterCursor=...)
 *   // called repeatedly by the Agent. No separate streaming tool needed.
 *
 * ════════════════════════════════════════════════════════════════════════
 * terminal_exec ID COLLISION FIX (Spec §46)
 * ════════════════════════════════════════════════════════════════════════
 *
 * BEFORE (bug): two classes declared `id = "terminal_exec"`:
 *   - TerminalExecTool        (platform/terminal/.../tools/)         registered at L204
 *   - StreamingTerminalExecTool (app/.../tools/)                     registered at L139
 *   Whichever registered last won; behavior was non-deterministic across refactors.
 *
 * AFTER (fixed): exactly ONE class owns `id = "terminal_exec"`:
 *   - LegacyExecTool          (platform/terminal/.../tools/legacy/)  the compat alias
 *   - StreamingTerminalExecTool.kt is DELETED in Phase 4.
 *   - TerminalExecTool.kt (old non-streaming) is REPLACED by LegacyExecTool.
 *
 * The streaming use-case is served by the NEW non-blocking API:
 *   val job = terminal.run(sessionId, command)          // returns immediately
 *   terminal.observe(sessionId, RAW, afterCursor=job.startCursor)  // call repeatedly
 *
 * ════════════════════════════════════════════════════════════════════════
 * MIGRATION TIMELINE (Spec §45)
 * ════════════════════════════════════════════════════════════════════════
 *   Phase 3 (this): register 9 new + 6 legacy; Agent Engine can use either.
 *   Phase 4: mark 6 legacy as @Deprecated; delete StreamingTerminalExecTool.kt.
 *   Phase 5: remove 6 legacy entirely (or keep longer if external plugins depend on them).
 */
object ToolRegistrationGuide {

    /** Construct all 9 new tools (call once during Hilt init). */
    fun newTools(rt: TerminalRuntime) = listOf(
        TerminalCreateTool(rt),
        TerminalRunTool(rt),
        TerminalObserveTool(rt),
        TerminalWaitTool(rt),
        TerminalWriteTool(rt),
        TerminalSignalTool(rt),
        TerminalResizeTool(rt),
        TerminalSnapshotTool(rt),
        TerminalCloseTool(rt)
    )

    /**
     * T73: 后端能力发现 + Ubuntu rootfs 安装引导 —— Agent 自主进入 Ubuntu 的两个入口。
     * 构造需要 RootfsProvisioner/RootfsTarget（app TerminalModule 提供），
     * 因此不进 newTools(rt)，由 ToolModule 直接注册。
     */
    fun backendTools(rt: TerminalRuntime, provisioner: com.apex.agent.platform.terminal.ubuntu.RootfsProvisioner, target: com.apex.agent.platform.terminal.ubuntu.RootfsTarget) = listOf(
        TerminalBackendsTool(rt),
        TerminalUbuntuInstallTool(provisioner, target)
    )

    /**
     * T75: workspace 管理（list/create/inspect/delete）。构造需要 LinuxWorkspaceManager
     * （app TerminalModule 提供），由 ToolModule 直接注册。
     */
    fun workspaceTools(workspaces: com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager) = listOf(
        TerminalWorkspacesTool(workspaces)
    )

    /** Construct all 6 legacy compat aliases (call once during Hilt init). */
    fun legacyTools(rt: TerminalRuntime) = listOf(
        LegacyExecTool(rt),
        LegacyReadTool(rt),
        LegacySendTool(rt),
        LegacySignalTool(rt),
        LegacyListTool(rt),
        LegacyCloseTool(rt)
    )

    /** Tool id → spec section, for documentation / introspection. */
    val TOOL_SPEC_MAP: Map<String, String> = mapOf(
        "terminal.create" to "§34.1 + T73 backend routing",
        "terminal.run" to "§34.2",
        "terminal.observe" to "§34.3",
        "terminal.wait" to "§34.4",
        "terminal.write" to "§34.5",
        "terminal.signal" to "§34.6",
        "terminal.resize" to "§34.7",
        "terminal.snapshot" to "§34.8",
        "terminal.close" to "§34.9",
        "terminal.backends" to "T73（后端能力发现：availability 三态）",
        "terminal.ubuntu.install" to "T73（Ubuntu rootfs 安装引导）",
        "terminal.workspaces" to "T75（workspace 管理：隔离文件区生命周期）",
        "terminal_exec" to "§35 (compat → run+wait+observe)",
        "terminal_read" to "§35 (compat → observe RAW)",
        "terminal_send" to "§35 (compat → write)",
        "terminal_signal" to "§35 (compat → signal)",
        "terminal_list" to "§35 (compat → snapshot SESSIONS)",
        "terminal_close" to "§35 (compat → close)"
    )
}
