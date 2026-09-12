package com.apex.agent.platform.terminal.api

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.TerminalKey as IoTerminalKey
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.state.TerminalSemanticState

/**
 * T82 — the PR #60 "frozen public API" made REAL.
 *
 * For the entire T81/T82-r1 period, [Terminal]/[TerminalSession]/[JobHandle] (PR #60,
 * documented in `docs/terminal-api.md` as the shipped contract) had **zero
 * implementations** — the real agent surface was (and remains) [TerminalRuntime] + the
 * terminal.* tools. This adapter closes that documentation/reality split without
 * touching the Runtime: [TerminalSdk] is a thin, honest facade over the 9-operation
 * runtime, giving external SDK consumers the stable object-shaped contract.
 *
 * Semantics (frozen-contract rules — additive only, P60 callers keep working):
 *  - IDs: [SessionId]/[JobId] wrap the runtime's Long ids (opaque to the caller).
 *  - Observation cursors: the P60 contract's opaque `String` cursor = the runtime's
 *    monotonic byte cursor (Long) in decimal form. `null` = start from newest.
 *  - `await()` on a JobHandle waits for `PROCESS_EXITED` via the WaitEngine (event
 *    driven, no polling); the returned [JobResult] carries the REAL exit code
 *    (shell-marker protocol when enabled, else the runtime's completion semantics).
 *
 * Construction (production DI):
 * ```
 * val sdk = TerminalSdk(runtime)                       // default backend = "local"
 * val ubuntuSdk = TerminalSdk(runtime, "linux-ubuntu") // create → Ubuntu/PRoot sessions
 * ```
 */
class TerminalSdk(
    private val runtime: TerminalRuntime,
    /** Backend used by [createSession] when the request does not specify one via env override. */
    private val defaultBackendId: String = "local"
) : Terminal {

    override suspend fun createSession(request: SessionRequest): Result<TerminalSession> {
        // P60 SessionRequest has no backendId field (frozen contract — additive only).
        // Backend selection: explicit override key "APEX_BACKEND" in EnvironmentSpec,
        // else the SDK's [defaultBackendId].
        val backendId = request.environment.overrides[APEX_BACKEND_ENV]
            ?: defaultBackendId
        val r = runtime.create(
            cwd = request.workingDirectory ?: if (backendId == "local") "/sdcard" else "/workspace",
            rows = request.terminalSize.rows,
            cols = request.terminalSize.columns,
            env = request.environment.overrides,
            backendId = backendId
        )
        return r.map { cr ->
            TerminalSessionHandle(
                sdk = this,
                runtime = runtime,
                id = SessionId(cr.sessionId.toString())
            )
        }
    }

    override fun getSession(id: SessionId): TerminalSession? {
        // Runtime has no cheap "exists" probe; snapshot(SESSIONS) is the honest read-only path.
        val sessions = kotlinx.coroutines.runBlocking {
            runtime.snapshot(mode = TerminalRuntime.SnapshotMode.SESSIONS).getOrNull()?.sessions ?: emptyList()
        }
        return if (sessions.any { it.session.id.toString() == id.value }) {
            TerminalSessionHandle(sdk = this, runtime = runtime, id = id)
        } else null
    }

    override fun listSessions(): List<SessionSummary> {
        val sessions = kotlinx.coroutines.runBlocking {
            runtime.snapshot(mode = TerminalRuntime.SnapshotMode.SESSIONS).getOrNull()?.sessions ?: emptyList()
        }
        return sessions.map { s ->
            SessionSummary(
                id = SessionId(s.session.id.toString()),
                name = null,                        // P60 name — runtime has no session names (recorded gap)
                state = s.session.state.name,
                createdAt = s.session.createdAt
            )
        }
    }

    override suspend fun shutdown(): Result<Unit> = runtime.shutdown().map { }

    override fun capabilities(): TerminalCapabilities = TerminalCapabilities(
        supportsPty = true,
        supportsProcessGroups = true,
        supportsSignals = true,
        supportsResize = true,
        supportsReattach = false,                  // PTY fds are process-local (T81 §16 honest)
        supportsPersistence = true,
        supportsProcessTree = false,               // process2 spec unwired (documented)
        supportsReconciliation = true
    )

    override fun apiVersion(): String = TerminalApiVersion.versionString

    companion object {
        /** EnvironmentSpec override key selecting the execution backend for a session. */
        const val APEX_BACKEND_ENV = "APEX_BACKEND"
    }
}

/** [TerminalSession] adapter — one instance per live session. */
internal class TerminalSessionHandle(
    private val sdk: TerminalSdk,
    private val runtime: TerminalRuntime,
    override val id: SessionId
) : TerminalSession {

    private val sessionId: Long get() = id.value.toLong()

    @Volatile
    private var cachedState: SessionLifecycleState = SessionLifecycleState.CREATED

    override val state: SessionLifecycleState
        get() {
            val snapshot = kotlinx.coroutines.runBlocking {
                runtime.snapshot(mode = TerminalRuntime.SnapshotMode.SESSIONS, sessionId = sessionId).getOrNull()
            }
            val s = snapshot?.sessions?.firstOrNull { it.session.id == sessionId } ?: return SessionLifecycleState.CLOSED
            return when (s.session.state) {
                com.apex.agent.platform.terminal.session.SessionState.CREATED,
                com.apex.agent.platform.terminal.session.SessionState.STARTING -> SessionLifecycleState.CREATED
                com.apex.agent.platform.terminal.session.SessionState.READY,
                com.apex.agent.platform.terminal.session.SessionState.RUNNING,
                com.apex.agent.platform.terminal.session.SessionState.WAITING_INPUT,
                com.apex.agent.platform.terminal.session.SessionState.INTERRUPTED,
                com.apex.agent.platform.terminal.session.SessionState.SUSPENDED -> SessionLifecycleState.RUNNING
                com.apex.agent.platform.terminal.session.SessionState.STOPPING -> SessionLifecycleState.STOPPING
                com.apex.agent.platform.terminal.session.SessionState.EXITED -> SessionLifecycleState.EXITED
                com.apex.agent.platform.terminal.session.SessionState.CLOSED -> SessionLifecycleState.CLOSED
                com.apex.agent.platform.terminal.session.SessionState.BROKEN,
                com.apex.agent.platform.terminal.session.SessionState.LOST,
                com.apex.agent.platform.terminal.session.SessionState.FAILED -> SessionLifecycleState.LOST
            }.also { cachedState = it }
        }

    override suspend fun execute(request: ExecutionRequest): Result<JobHandle> {
        val r = runtime.run(
            sessionId = sessionId,
            command = request.command,
            owner = InputOwner.AGENT,             // auto-injected (P60 §14: tools cannot forge USER)
            background = request.attachment == PtyAttachment.DETACHED,
            timeoutMs = request.timeoutMs ?: 0L
        )
        return r.map { rr -> JobHandleAdapter(runtime, SessionId(sessionId.toString()), JobId(rr.jobId.toString()), request) }
    }

    override suspend fun sendInput(input: TerminalInput): Result<Unit> {
        val r = when (input) {
            is TerminalInput.Text -> runtime.write(sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.LINE, text = input.value)
            is TerminalInput.Key -> runtime.write(sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.KEY, key = input.key.toIoKey())
            is TerminalInput.Bytes -> runtime.write(
                sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.RAW,
                text = String(input.value, Charsets.ISO_8859_1)  // byte-faithful round-trip (RAW path writes UTF-8 of the string; Bytes is the raw channel)
            )
        }
        return r.map { }
    }

    override suspend fun observe(request: ObservationRequest): Result<ObservationResult> {
        val afterCursor = request.cursor?.toLongOrNull() ?: 0L
        val r = runtime.observe(
            sessionId = sessionId,
            mode = TerminalRuntime.ObserveMode.RAW,
            afterCursor = afterCursor,
            maxBytes = request.maxBytes
        )
        return r.map { res ->
            val snap = snapshotScreen()
            if (res.overrun) {
                ObservationResult.CursorExpired(
                    snapshot = snap,
                    newCursor = res.oldestCursor?.toString() ?: res.cursor.toString()
                )
            } else {
                ObservationResult.Delta(
                    delta = TerminalDelta(
                        fromSequence = afterCursor,
                        toSequence = res.endCursor ?: res.cursor,
                        changes = listOf(
                            TerminalChange.CellsChanged(
                                row = snap.cursorRow,
                                startCol = 0, endCol = snap.columns,
                                text = res.raw ?: ""
                            )
                        )
                    ),
                    newCursor = (res.endCursor ?: res.cursor).toString()
                )
            }
        }
    }

    override suspend fun snapshot(): SessionSnapshot {
        val snap = kotlinx.coroutines.runBlocking {
            runtime.snapshot(mode = TerminalRuntime.SnapshotMode.SESSIONS, sessionId = sessionId).getOrNull()
        }
        val s: TerminalSemanticState = snap?.sessions?.firstOrNull { it.session.id == sessionId }
            ?: return closedSnapshot()
        val fg = s.foregroundJob
        return SessionSnapshot(
            sessionId = id,
            name = null,
            lifecycle = state,
            health = s.session.state.name,
            recovery = "NONE",
            terminal = terminalSnapshotFromSemantic(s),
            foregroundJob = fg?.let { jobSnapshot(it) },
            backgroundJobs = emptyList()
        )
    }

    /** P60 TerminalSnapshot from the compact SEMANTIC screen fields. */
    private fun terminalSnapshotFromSemantic(s: TerminalSemanticState): TerminalSnapshot = TerminalSnapshot(
        rows = s.screen.rows,
        columns = s.screen.cols,
        screenText = "",                      // compact form — full text via observe(SCREEN)
        cursorRow = s.screen.cursorRow,
        cursorColumn = s.screen.cursorCol,
        cursorVisible = true,
        alternateScreen = s.screen.alternateScreen,
        title = s.screen.title,
        sequence = s.session.cursor
    )

    private fun closedSnapshot(): SessionSnapshot = SessionSnapshot(
        sessionId = id, name = null, lifecycle = SessionLifecycleState.CLOSED,
        health = "CLOSED", recovery = "NONE",
        terminal = TerminalSnapshot(
            rows = 0, columns = 0, screenText = "", cursorRow = 0, cursorColumn = 0,
            cursorVisible = false, alternateScreen = false, title = null, sequence = 0
        ),
        foregroundJob = null, backgroundJobs = emptyList()
    )

    private suspend fun snapshotScreen(): TerminalSnapshot {
        val scr = runtime.observe(sessionId, mode = TerminalRuntime.ObserveMode.SCREEN).getOrNull()?.screen
            ?: return TerminalSnapshot(
                rows = 0, columns = 0, screenText = "", cursorRow = 0, cursorColumn = 0,
                cursorVisible = false, alternateScreen = false, title = null, sequence = 0
            )
        return TerminalSnapshot(
            rows = scr.rows,
            columns = scr.cols,
            screenText = scr.renderedText ?: "",
            cursorRow = scr.cursorRow,
            cursorColumn = scr.cursorCol,
            cursorVisible = true,
            alternateScreen = scr.alternateScreen,
            title = scr.title,
            sequence = runtime.observe(sessionId, mode = TerminalRuntime.ObserveMode.SEMANTIC).getOrNull()?.cursor ?: 0L
        )
    }

    private fun jobSnapshot(j: com.apex.agent.platform.terminal.state.JobSnapshot): JobSnapshot = JobSnapshot(
        id = JobId(j.id.toString()),
        sessionId = id,
        command = j.command,
        state = when (j.state) {
            com.apex.agent.platform.terminal.job.JobState.CREATED -> JobState.CREATED
            com.apex.agent.platform.terminal.job.JobState.RUNNING, com.apex.agent.platform.terminal.job.JobState.WAITING_INPUT -> JobState.RUNNING
            com.apex.agent.platform.terminal.job.JobState.EXITED -> JobState.EXITED
            com.apex.agent.platform.terminal.job.JobState.INTERRUPTED -> JobState.CANCELLED
            com.apex.agent.platform.terminal.job.JobState.TIMED_OUT -> JobState.TIMED_OUT
            com.apex.agent.platform.terminal.job.JobState.FAILED -> JobState.FAILED
            com.apex.agent.platform.terminal.job.JobState.UNKNOWN -> JobState.LOST
        },
        startedAt = j.startedAt,
        finishedAt = j.finishedAt,
        foreground = !j.background,
        attachment = if (j.background) PtyAttachment.DETACHED else PtyAttachment.ATTACHED,
        exitInfo = j.exitCode?.let { code ->
            ExitInfo(exitCode = code, signal = null, coreDumped = false, reason = ExitReason.NORMAL_EXIT)
        }
    )

    override suspend fun resize(size: TerminalSize): Result<Unit> =
        runtime.resize(sessionId, size.rows, size.columns).map { }

    override suspend fun stop(): Result<Unit> =
        runtime.stop(sessionId).map { }

    override suspend fun close(): Result<Unit> =
        runtime.close(sessionId, force = true).map { }
}

/** [JobHandle] adapter. */
internal class JobHandleAdapter(
    private val runtime: TerminalRuntime,
    private val sessionId: SessionId,
    override val id: JobId,
    private val request: ExecutionRequest
) : JobHandle {

    private val sid: Long get() = sessionId.value.toLong()
    private val jid: Long get() = id.value.toLong()

    override val state: JobState
        get() = kotlinx.coroutines.runBlocking {
            runtime.snapshot(mode = TerminalRuntime.SnapshotMode.SESSIONS, sessionId = sid).getOrNull()
                ?.sessions?.firstOrNull { it.session.id == sid }?.foregroundJob
        }?.let { j ->
            if (j.id.toString() == id.value) mapJobState(j.state) else JobState.RUNNING
        } ?: JobState.LOST

    override suspend fun cancel(): Result<Unit> =
        runtime.cancel(sid, jid).map { }

    override suspend fun snapshot(): JobSnapshot {
        val s = kotlinx.coroutines.runBlocking {
            runtime.snapshot(mode = TerminalRuntime.SnapshotMode.SESSIONS, sessionId = sid).getOrNull()
        }
        val j = s?.sessions?.firstOrNull { it.session.id == sid }?.foregroundJob
            ?: return JobSnapshot(
                id = id, sessionId = sessionId, command = request.command,
                state = JobState.LOST, startedAt = 0L, finishedAt = null,
                foreground = true, attachment = request.attachment, exitInfo = null
            )
        return JobSnapshot(
            id = id,
            sessionId = sessionId,
            command = j.command,
            state = mapJobState(j.state),
            startedAt = j.startedAt,
            finishedAt = j.finishedAt,
            foreground = !j.background,
            attachment = request.attachment,
            exitInfo = j.exitCode?.let { ExitInfo(exitCode = it, signal = null, coreDumped = false, reason = ExitReason.NORMAL_EXIT) }
        )
    }

    /** Wait for PROCESS_EXITED (event-driven WaitEngine). Cancelling this await ≠ cancelling the job. */
    override suspend fun await(): Result<JobResult> {
        val r = runtime.wait(
            sid,
            com.apex.agent.platform.terminal.wait.WaitCondition.ProcessExited(jid),
            timeoutMs = request.timeoutMs ?: 300_000L
        )
        return r.map { wr ->
            val snap = snapshot()
            JobResult(
                id = id,
                sessionId = sessionId,
                state = snap.state,
                exitInfo = snap.exitInfo,
                durationMs = (snap.finishedAt ?: System.currentTimeMillis()) - snap.startedAt,
                startedAt = snap.startedAt,
                finishedAt = snap.finishedAt,
                observationRange = ObservationRange(
                    startCursor = "0",
                    endCursor = null
                )
            )
        }
    }

    private fun mapJobState(s: com.apex.agent.platform.terminal.job.JobState): JobState = when (s) {
        com.apex.agent.platform.terminal.job.JobState.CREATED -> JobState.CREATED
        com.apex.agent.platform.terminal.job.JobState.RUNNING, com.apex.agent.platform.terminal.job.JobState.WAITING_INPUT -> JobState.RUNNING
        com.apex.agent.platform.terminal.job.JobState.EXITED -> JobState.EXITED
        com.apex.agent.platform.terminal.job.JobState.INTERRUPTED -> JobState.CANCELLED
        com.apex.agent.platform.terminal.job.JobState.TIMED_OUT -> JobState.TIMED_OUT
        com.apex.agent.platform.terminal.job.JobState.FAILED -> JobState.FAILED
        com.apex.agent.platform.terminal.job.JobState.UNKNOWN -> JobState.LOST
    }
}

/** api.TerminalKey → io.TerminalKey (same names). */
private fun TerminalKey.toIoKey(): IoTerminalKey =
    runCatching { IoTerminalKey.valueOf(name) }.getOrDefault(IoTerminalKey.ENTER)

/** Unix signal by name (helper for SDK consumers driving signals through the runtime). */
internal fun signalByName(name: String): UnixSignal? =
    runCatching { UnixSignal.valueOf(name) }.getOrNull()
