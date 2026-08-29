package com.apex.agent.platform.terminal.runtime

import com.apex.agent.platform.terminal.errors.RuntimeResult
import com.apex.agent.platform.terminal.errors.TerminalError
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.screen.TerminalScreenState
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.platform.terminal.wait.WaitCondition
import kotlinx.coroutines.flow.Flow
import com.apex.agent.platform.terminal.wait.WaitResult

/**
 * The top-level Agent-Native Terminal Runtime facade.
 *
 * Spec ref: ATR 2.0 Final Spec §6.1 / §33 / §34
 *
 * This is the ONLY surface the Agent (and UI) should depend on. It exposes 9 operations:
 *
 *   terminal.create      — create a long-lived Session
 *   terminal.run         — start a Job (non-blocking, returns immediately)
 *   terminal.observe     — observe state/output (SEMANTIC / EVENT / SCREEN / RAW)
 *   terminal.wait        — block until a condition is met (EventBus-driven, no polling)
 *   terminal.write       — write input (raw / line / key)
 *   terminal.signal      — send a Unix signal
 *   terminal.resize      — resize the PTY (SIGWINCH)
 *   terminal.snapshot    — global snapshot for context recovery
 *   terminal.close       — close a Session (reap child, close fd)
 *
 * Agent tools see ONLY these. They do NOT see nativeRead / nativeWrite / TerminalManager /
 * RingBuffer / PtyOutputPump (Spec §33).
 *
 * Owner is AUTO-INJECTED by Runtime based on call origin. Agent tools CANNOT forge owner=USER
 * (Spec §14). The `owner` parameter on write/signal is set by the Runtime, not the tool caller.
 */
interface TerminalRuntime {

    // ───────── create ─────────
    // Spec §34.1 + T73: backendId 路由（ExecutionBackendRegistry）。
    // "local" = Android 本地 shell（默认，与历史行为一致）；"linux-ubuntu" =
    // PRoot + Ubuntu RootFS 会话（需 rootfs 已 provision，否则失败并携带引导信息）。
    suspend fun create(
        shell: String = "/system/bin/sh",
        cwd: String = "/sdcard",
        rows: Int = 24,
        cols: Int = 80,
        env: Map<String, String> = emptyMap(),
        privilege: PrivilegeLevel = PrivilegeLevel.NORMAL,
        backendId: String = "local",
        /**
         * T75: workspace id（仅 LINUX 后端；null/blank → "default"）。合法格式
         * （^[a-z0-9][a-z0-9_-]{0,63}$）的 id 懒创建。LOCAL + 非空 → InvalidInput。
         */
        workspaceId: String? = null
    ): RuntimeResult<CreateResult>

    data class CreateResult(
        val sessionId: Long,
        val pid: Int,
        val shell: String,
        val cwd: String,
        val rows: Int,
        val cols: Int,
        val privilege: PrivilegeLevel,
        val state: String,         // SessionState name
        val cursor: Long,
        // ── T73: 后端路由结果（Agent 可感知会话落在哪个执行环境）──
        val backendId: String = "local",
        val runtimeType: String = "ANDROID_LOCAL",   // BackendRuntimeType name
        val rootfsId: String? = null,                // LINUX: 已就绪 rootfs 的 id
        val guestCwd: String? = null,                // LINUX: guest 语义 cwd
        // ── T75: workspace（LINUX 会话的隔离文件区）──
        val workspaceId: String? = null              // LINUX: 会话绑定的 workspace id
    )

    // ───────── backends（T73：Agent 后端能力发现）─────────
    // Spec §33（Agent 只依赖 TerminalRuntime 门面）：列出所有已注册执行后端及
    // 其真实可用性。Agent 据此决定 create(backendId=…) 的目标，或在
    // NEEDS_ROOTFS 时先调 terminal.ubuntu.install 引导安装。
    suspend fun backends(): List<BackendStatus>

    /** 单个后端的可用性快照（T73）。 */
    data class BackendStatus(
        val id: String,
        val runtimeType: String,        // BackendRuntimeType.name: ANDROID_LOCAL | LINUX
        val available: Boolean,         // availability is Ready
        val state: String,              // READY | NEEDS_ROOTFS[:state] | FAILED
        val detail: String? = null      // 失败原因 / 引导提示
    )

    // ───────── run ─────────
    // Spec §34.2 — non-blocking, returns Job handle immediately.
    suspend fun run(
        sessionId: Long,
        command: String,
        owner: InputOwner,         // injected by Runtime, NOT by tool caller
        background: Boolean = false,
        timeoutMs: Long = 0L
    ): RuntimeResult<RunResult>

    data class RunResult(
        val jobId: Long,
        val sessionId: Long,
        val state: String,         // JobState name (RUNNING / WAITING_INPUT / FAILED)
        val startCursor: Long,     // pass as afterCursor to observe this job's output
        val owner: InputOwner,
        val background: Boolean
    )

    // ───────── observe ─────────
    // Spec §34.3 — the core perception API.
    suspend fun observe(
        sessionId: Long,
        mode: ObserveMode = ObserveMode.SEMANTIC,
        afterCursor: Long = 0,
        maxBytes: Int = 12000,
        maxEvents: Int = 200
    ): RuntimeResult<ObserveResult>

    enum class ObserveMode { SEMANTIC, EVENT, SCREEN, RAW }

    data class ObserveResult(
        val mode: ObserveMode,
        val sessionId: Long,
        val cursor: Long,                  // newest cursor; pass as next afterCursor
        val startCursor: Long? = null,     // EVENT/RAW: start of returned range
        val endCursor: Long? = null,       // EVENT/RAW: end (= next afterCursor)
        val truncated: Boolean = false,
        val overrun: Boolean = false,
        val oldestCursor: Long? = null,
        val semantic: TerminalSemanticState? = null,   // mode=SEMANTIC
        val events: List<TerminalEvent>? = null,        // mode=EVENT
        val screen: TerminalScreenState? = null,        // mode=SCREEN
        val raw: String? = null                          // mode=RAW (utf-8 or base64)
    )

    // ───────── wait ─────────
    // Spec §34.4 — EventBus-driven, no polling.
    suspend fun wait(
        sessionId: Long,
        condition: WaitCondition,
        timeoutMs: Long = 60_000L
    ): RuntimeResult<WaitResult>

    // ───────── write ─────────
    // Spec §34.5 — owner injected by Runtime.
    suspend fun write(
        sessionId: Long,
        owner: InputOwner,         // injected by Runtime
        kind: WriteKind = WriteKind.LINE,
        text: String? = null,
        key: TerminalKey? = null
    ): RuntimeResult<WriteResult>

    enum class WriteKind { RAW, LINE, KEY }

    data class WriteResult(
        val written: Boolean,
        val bytesWritten: Int,
        val cursor: Long,
        val inputOwner: InputOwner
    )

    // ───────── signal ─────────
    // Spec §34.6
    suspend fun signal(
        sessionId: Long,
        signal: UnixSignal,
        owner: InputOwner,         // injected by Runtime
        jobId: Long? = null
    ): RuntimeResult<SignalResult>

    data class SignalResult(
        val sent: Boolean,
        val signal: UnixSignal,
        val targetJobId: Long?
    )

    // ───────── cancel (Spec PR #51 §5) ─────────
    /** Cancel a job: graceful SIGTERM → grace period → SIGKILL. Agent doesn't manage signals manually. */
    suspend fun cancel(
        sessionId: Long,
        jobId: Long
    ): RuntimeResult<CancelResult>

    data class CancelResult(
        val cancelled: Boolean,
        val jobId: Long,
        val finalState: String   // TIMED_OUT / INTERRUPTED / EXITED
    )

    // ───────── resize ─────────
    // Spec §34.7
    suspend fun resize(
        sessionId: Long,
        rows: Int,
        cols: Int
    ): RuntimeResult<ResizeResult>

    data class ResizeResult(val resized: Boolean, val rows: Int, val cols: Int)

    // ───────── snapshot ─────────
    // Spec §34.8 — global recovery entry.
    suspend fun snapshot(
        mode: SnapshotMode = SnapshotMode.FULL,
        sessionId: Long? = null,
        recentEvents: Int = 50,
        recentOutputBytes: Int = 4096
    ): RuntimeResult<SnapshotResult>

    enum class SnapshotMode { FULL, SESSIONS }

    data class SnapshotResult(
        val sessions: List<TerminalSemanticState>,
        val globalCursor: Long,
        val recentEvents: List<TerminalEvent>,
        val recentOutput: String
    )

    // ───────── stop (Spec PR #54 §5) ─────────
    /** Stop running jobs (cancel + SIGTERM→grace→SIGKILL) but keep Session alive. ≠ close(). */
    suspend fun stop(sessionId: Long): RuntimeResult<StopResult>

    data class StopResult(val stopped: Boolean, val jobId: Long?, val finalState: String)

    // ───────── close ─────────
    // Spec §34.9
    suspend fun close(
        sessionId: Long,
        force: Boolean = false
    ): RuntimeResult<CloseResult>

    data class CloseResult(
        val closed: Boolean,
        val cause: String,         // USER / NORMAL / BROKEN
        val finalCursor: Long
    )

    // ───────── push-based observation Flows (Spec §41 — event-driven, NOT polling) ─────────
    /** Push-based screen state for a session. Emits on every VT update. Null if session not found. */
    fun screenStateFlow(sessionId: Long): Flow<com.apex.agent.platform.terminal.screen.TerminalScreenState>?

    /** Push-based semantic state for a session. Emits on every state change. Null if session not found. */
    fun semanticStateFlow(sessionId: Long): Flow<com.apex.agent.platform.terminal.state.TerminalSemanticState>?

    // ───────── recover ─────────
    // Spec §39 — crash recovery. Call once on startup. Returns recovered session ids.
    // Dead PTY sessions appear as EXITED/BROKEN (never faked alive).
    suspend fun recover(): List<Long>

    /** Read-only SemanticState for a recovered session (from persisted metadata). */
    suspend fun recoveredSnapshot(sessionId: Long): com.apex.agent.platform.terminal.state.TerminalSemanticState?
}

/** Convenience: wrap a TerminalError into a kotlin.Result failure. */
fun <T> terminalError(err: TerminalError): Result<T> =
    Result.failure(RuntimeException("TerminalError:${err.code}"))
