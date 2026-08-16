package com.apex.agent.platform.terminal.state

import com.apex.agent.platform.terminal.io.InputControlState
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.job.JobState
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.screen.TerminalScreenState
import com.apex.agent.platform.terminal.session.SessionState

/**
 * Input-waiting detection confidence.
 *
 * Spec ref: ATR 2.0 Final Spec §29
 *
 *   NONE              — definitely not waiting (process actively producing output / idle shell with known prompt)
 *   POSSIBLE          — weak signals (process alive, no output for a while, cursor at end)
 *                       DOES NOT trigger Session→WAITING_INPUT (only updates InputState field)
 *   HIGH_CONFIDENCE   — strong signals (screen ends with "Continue? [Y/n]", "Password:", "Select option:",
 *                       or known interactive program: python/ssh/vim/top/adb shell)
 *                       TRIGGERS Session→WAITING_INPUT (S6) and Job→WAITING_INPUT (J2)
 *   UNKNOWN           — cannot determine (e.g. alternate screen TUI). NEVER downgrade to NONE.
 */
enum class InputState { NONE, POSSIBLE, HIGH_CONFIDENCE, UNKNOWN }

/**
 * Process snapshot (foreground process of the session).
 *
 * Spec ref: ATR 2.0 Final Spec §27
 *
 * Exit detection MUST use waitpid + nativeIsAlive + nativeGetExitCode.
 * FORBIDDEN: inferring process exit from "output stopped" (Spec §4.7 / §20).
 */
data class ProcessSnapshot(
    val pid: Int,
    val processName: String?,
    val foregroundProcess: Boolean,
    val running: Boolean,
    val exitCode: Int?,
    val startTime: Long,
    val finishTime: Long?
)

/** Session snapshot embedded in SemanticState. */
data class SessionSnapshot(
    val id: Long,
    val shell: String,
    val cwd: String,                    // may be "unknown" in v1 (Spec §28)
    val privilege: PrivilegeLevel,
    val state: SessionState,
    val pid: Int,
    val rows: Int,
    val cols: Int,
    val createdAt: Long,
    val lastExitCode: Int?,
    val cursor: Long
)

/** Job snapshot embedded in SemanticState. */
data class JobSnapshot(
    val id: Long,
    val sessionId: Long,
    val command: String,
    val owner: InputOwner,
    val background: Boolean,
    val state: JobState,
    val exitCode: Int?,
    val startedAt: Long,
    val finishedAt: Long?
)

/** Screen snapshot embedded in SemanticState (compact form; full state via terminal.observe SCREEN). */
data class ScreenSnapshot(
    val rows: Int,
    val cols: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val alternateScreen: Boolean,
    val title: String?
)

/** Input snapshot embedded in SemanticState. */
data class InputSnapshot(
    val state: InputState,
    val control: InputControlState
)

/**
 * Machine-readable aggregated Runtime state. The default SEMANTIC observation payload.
 *
 * Spec ref: ATR 2.0 Final Spec §26 / §30.1
 *
 * SemanticState subscribes to EventBus and updates INCREMENTALLY on events
 * (does NOT recompute everything on each observe() call).
 */
data class TerminalSemanticState(
    val session: SessionSnapshot,
    val process: ProcessSnapshot?,
    val screen: ScreenSnapshot,
    val input: InputSnapshot,
    val foregroundJob: JobSnapshot?,
    val backgroundJobs: List<JobSnapshot>
) {
    /**
     * Full screen state (only populated when explicitly requested via observe(SCREEN)).
     * Null in default SEMANTIC mode to keep token cost low.
     */
    val fullScreen: TerminalScreenState? get() = null
}
