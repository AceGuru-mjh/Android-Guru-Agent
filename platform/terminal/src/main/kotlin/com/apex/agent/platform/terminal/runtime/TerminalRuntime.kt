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
    // Spec §34.1
    suspend fun create(
        shell: String = "/system/bin/sh",
        cwd: String = "/sdcard",
        rows: Int = 24,
        cols: Int = 80,
        env: Map<String, String> = emptyMap(),
        privilege: PrivilegeLevel = PrivilegeLevel.NORMAL
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
        val cursor: Long
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
}

/** Convenience: wrap a TerminalError into a kotlin.Result failure. */
fun <T> terminalError(err: TerminalError): Result<T> =
    Result.failure(RuntimeException("TerminalError:${err.code}"))
