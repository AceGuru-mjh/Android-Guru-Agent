package com.apex.agent.platform.terminal.runtime

import com.apex.agent.platform.terminal.buffer.TerminalOutputBuffer
import com.apex.agent.platform.terminal.errors.TerminalError
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBus
import com.apex.agent.platform.terminal.events.TerminalEventBusImpl
import com.apex.agent.platform.terminal.events.TerminalEventLog
import com.apex.agent.platform.terminal.events.TerminalEventLogImpl
import com.apex.agent.platform.terminal.io.InputManagerImpl
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.runtime.TerminalRuntime.CancelResult
import com.apex.agent.platform.terminal.runtime.TerminalRuntime.CloseResult
import com.apex.agent.platform.terminal.runtime.TerminalRuntime.CreateResult
import com.apex.agent.platform.terminal.runtime.TerminalRuntime.ObserveMode
import com.apex.agent.platform.terminal.runtime.TerminalRuntime.ObserveResult
import com.apex.agent.platform.terminal.runtime.TerminalRuntime.ResizeResult
import com.apex.agent.platform.terminal.runtime.TerminalRuntime.RunResult
import com.apex.agent.platform.terminal.runtime.TerminalRuntime.SignalResult
import com.apex.agent.platform.terminal.runtime.TerminalRuntime.SnapshotMode
import com.apex.agent.platform.terminal.runtime.TerminalRuntime.SnapshotResult
import com.apex.agent.platform.terminal.runtime.TerminalRuntime.WriteKind
import com.apex.agent.platform.terminal.runtime.TerminalRuntime.WriteResult
import com.apex.agent.platform.terminal.job.JobManagerImpl
import com.apex.agent.platform.terminal.pty.NativePty
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.policy.TerminalPolicy
import com.apex.agent.platform.terminal.screen.TerminalScreenState
import com.apex.agent.platform.terminal.persistence.RuntimeRecoveryService
import com.apex.agent.platform.terminal.persistence.SessionMetadataStore
import com.apex.agent.platform.terminal.screen.VirtualTerminal
import com.apex.agent.platform.terminal.session.SessionManagerImpl
import com.apex.agent.platform.terminal.session.SessionState
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.platform.terminal.wait.WaitCondition
import com.apex.agent.platform.terminal.wait.WaitEngineImpl
import com.apex.agent.platform.terminal.wait.WaitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Concrete TerminalRuntime. Wires together all subsystems and exposes the 9 Agent operations.
 *
 * Spec ref: ATR 2.0 Final Spec §6.1 / §33 / §34
 *
 * Construction (Hilt in real repo; manual here):
 *   val runtime = TerminalRuntimeImpl(
 *       native = FakeNativePty() or JniNativePty(),
 *       policy = TerminalPolicyImpl(),
 *       virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }   // Phase 2: real VT
 *   )
 *
 * Phase 2 status: VT upgraded to RealVirtualTerminal (VT100/ANSI parser); InputWaitingDetector
 * wired into PtyOutputPump; ObservationEngine + 9 Agent tools implemented.
 * Phase 1 status: IMPLEMENTED (replaces the Phase 0 NotImplementedError stub).
 * Native PTY is injected (FakeNativePty for tests; JniNativePty in production).
 */
class TerminalRuntimeImpl(
    private val native: NativePty,
    private val policy: TerminalPolicy,
    private val virtualTerminalFactory: (Int, Int) -> VirtualTerminal = { r, c ->
        com.apex.agent.platform.terminal.screen.RealVirtualTerminal(r, c)
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Optional persistence (Spec §39). If set, auto-saves session metadata + enables recover(). */
    private val persistenceStore: SessionMetadataStore? = null
) : TerminalRuntime {

    private val recoveryService: RuntimeRecoveryService? = persistenceStore?.let {
        RuntimeRecoveryService(it, this, scope)
    }

    private val eventLog: TerminalEventLog = TerminalEventLogImpl()
    private val eventBus: TerminalEventBus = TerminalEventBusImpl(eventLog, scope)
    private val waitEngine = WaitEngineImpl(eventBus, scope)
    private val inputManager = InputManagerImpl(policy, native, eventLog, eventBus, scope)
    private val inputDetector = com.apex.agent.platform.terminal.state.InputWaitingDetector()
    // PR #51: process/timeout/cancellation controllers
    // ProcessController routes signal() through InputManager (policy + control-state + events);
    // the NATIVE layer delivers to the whole process group (kill(-PGID)).
    private val processController = com.apex.agent.platform.terminal.process.ProcessController(inputManager)
    private val timeoutController = com.apex.agent.platform.terminal.process.TimeoutController(inputManager)
    private val cancellationController = com.apex.agent.platform.terminal.process.JobCancellationController(inputManager, timeoutController)
    private val sessionManager = SessionManagerImpl(
        native, eventLog, eventBus, waitEngine, inputManager, virtualTerminalFactory, policy,
        inputDetector, scope
    )
    private val jobManager = JobManagerImpl(sessionManager, inputManager, eventLog, eventBus, scope)

    init {
        // Wire per-session event dispatch to JobManager + SemanticStateReducer.
        // (SessionManagerImpl already emits to EventBus; JobManager subscribes lazily per session
        //  via the Runtime's sessionCreated hook below.)
    }

    // ───────── create ─────────
    override suspend fun create(
        shell: String, cwd: String, rows: Int, cols: Int,
        env: Map<String, String>, privilege: PrivilegeLevel
    ): Result<CreateResult> {
        val r = sessionManager.create(shell, cwd, rows, cols, env, privilege)
        return r.map { s ->
            // start a JobManager listener for this session
            startSessionListener(s.id)
            // Register the session's process group (v1: pgid == shell pid — forkpty makes the
            // PTY child a session + process-group leader, so PGID == PID). Signals routed via
            // ProcessController are then delivered to the whole group by the native layer.
            processController.registerGroup(s.id, null, s.pid)
            CreateResult(
                sessionId = s.id, pid = s.pid, shell = s.shell, cwd = s.initialCwd,
                rows = s.rows, cols = s.cols, privilege = s.privilege,
                state = s.state.name, cursor = s.cursor
            )
        }
    }

    // ───────── run ─────────
    override suspend fun run(
        sessionId: Long, command: String, owner: InputOwner,
        background: Boolean, timeoutMs: Long
    ): Result<RunResult> {
        val r = jobManager.startJob(sessionId, command, owner, background, timeoutMs)
        return r.map { j ->
            RunResult(
                jobId = j.id, sessionId = j.sessionId, state = j.state.name,
                startCursor = j.startCursor, owner = j.owner, background = j.background
            )
        }
    }

    // ───────── observe ─────────
    override suspend fun observe(
        sessionId: Long, mode: ObserveMode, afterCursor: Long,
        maxBytes: Int, maxEvents: Int
    ): Result<ObserveResult> {
        val a = sessionManager.assembly(sessionId)
            ?: return Result.failure(RuntimeException("TerminalError:SessionNotFound"))
        // Delegate to per-session ObservationEngine (Spec §30). Runtime is orchestration only.
        val engine = a.observationEngine
        return Result.success(
            engine.observe(sessionId, mode, afterCursor, maxBytes, maxEvents)
        )
    }

    // ───────── wait ─────────
    override suspend fun wait(
        sessionId: Long, condition: WaitCondition, timeoutMs: Long
    ): Result<WaitResult> {
        if (sessionManager.assembly(sessionId) == null) {
            return Result.failure(RuntimeException("TerminalError:SessionNotFound"))
        }
        val r = waitEngine.await(sessionId, condition, timeoutMs)
        return Result.success(r)
    }

    // ───────── write ─────────
    override suspend fun write(
        sessionId: Long, owner: InputOwner, kind: WriteKind,
        text: String?, key: TerminalKey?
    ): Result<WriteResult> {
        if (sessionManager.assembly(sessionId) == null) {
            return Result.failure(RuntimeException("TerminalError:SessionNotFound"))
        }
        val res: Result<Unit> = when (kind) {
            WriteKind.RAW -> inputManager.writeRaw(sessionId, owner, text ?: "")
            WriteKind.LINE -> inputManager.sendLine(sessionId, owner, text ?: "")
            WriteKind.KEY -> inputManager.sendKey(sessionId, owner, key ?: TerminalKey.ENTER)
        }
        return res.map {
            val payload = text ?: ""
            val bytes = if (payload.isEmpty()) 0 else payload.toByteArray(Charsets.UTF_8).size
            WriteResult(
                written = true, bytesWritten = bytes,
                cursor = sessionManager.assembly(sessionId)!!.ringBuffer.totalCursor,
                inputOwner = owner
            )
        }
    }

    // ───────── signal ─────────
    override suspend fun signal(
        sessionId: Long, signal: UnixSignal, owner: InputOwner, jobId: Long?
    ): Result<SignalResult> {
        if (sessionManager.assembly(sessionId) == null) {
            return Result.failure(RuntimeException("TerminalError:SessionNotFound"))
        }
        // Route through ProcessController: records the session's process group and delegates
        // to InputManager (policy + control-state + SignalSent event). The native layer
        // delivers the signal to the WHOLE process group (kill(-PGID)), not just the shell.
        val res = processController.signalGroup(sessionId, owner, signal, jobId)
        return res.map { SignalResult(sent = true, signal = signal, targetJobId = jobId) }
    }

    // ───────── cancel (Spec PR #51 §5) ─────────
    override suspend fun cancel(sessionId: Long, jobId: Long): Result<CancelResult> {
        if (sessionManager.assembly(sessionId) == null) {
            return Result.failure(RuntimeException("TerminalError:SessionNotFound"))
        }
        cancellationController.cancel(sessionId, jobId)
        // Wait briefly for the cancellation to take effect
        kotlinx.coroutines.delay(100)
        val job = jobManager.get(jobId)
        val finalState = job?.state?.name ?: "UNKNOWN"
        return Result.success(CancelResult(cancelled = true, jobId = jobId, finalState = finalState))
    }

    // ───────── resize ─────────
    override suspend fun resize(sessionId: Long, rows: Int, cols: Int): Result<ResizeResult> {
        val a = sessionManager.assembly(sessionId)
            ?: return Result.failure(RuntimeException("TerminalError:SessionNotFound"))
        // 1. Resize the native PTY FIRST (sends SIGWINCH to child). Spec §34.7 / §18.
        //    If native resize fails, VirtualTerminal MUST NOT be updated (correctness: avoid
        //    VT/kernel size mismatch that breaks vim/top/less).
        val nativeOk = native.nativeResize(a.nativeSessionId, rows, cols)
        if (!nativeOk) {
            return Result.failure(RuntimeException("TerminalError:UnsupportedOperation"))
        }
        // 2. Native OK → update VirtualTerminal to match.
        a.virtualTerminal.resize(rows, cols)
        // 3. Emit ResizeChanged event.
        val ev = com.apex.agent.platform.terminal.events.TerminalEvent.ResizeChanged(
            id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(), cursor = -1,
            rows = rows, cols = cols
        )
        val eid = eventLog.append(ev)
        eventBus.emit(ev.copy(id = eid))
        return Result.success(ResizeResult(resized = true, rows = rows, cols = cols))
    }

    // ───────── snapshot ─────────
    override suspend fun snapshot(
        mode: SnapshotMode, sessionId: Long?,
        recentEvents: Int, recentOutputBytes: Int
    ): Result<SnapshotResult> {
        val sessions = sessionManager.list()
        val filtered = sessionId?.let { id -> sessions.filter { it.id == id } } ?: sessions
        val semantics = filtered.mapNotNull { s ->
            sessionManager.assembly(s.id)?.semanticReducer?.snapshot()
        }
        val globalCursor = semantics.maxOfOrNull { it.session.cursor } ?: 0L
        val recent = if (sessionId != null) eventLog.tail(sessionId, recentEvents)
                     else eventLog.let { log -> filtered.flatMap { log.tail(it.id, recentEvents / maxOf(1, filtered.size)) } }
        val recentOut = filtered.joinToString("") { s ->
            sessionManager.assembly(s.id)?.ringBuffer?.latest(recentOutputBytes / maxOf(1, filtered.size))
                ?.bytes?.toString(Charsets.UTF_8) ?: ""
        }
        return Result.success(
            SnapshotResult(
                sessions = semantics, globalCursor = globalCursor,
                recentEvents = recent, recentOutput = recentOut
            )
        )
    }

    // ───────── close ─────────
    override suspend fun close(sessionId: Long, force: Boolean): Result<CloseResult> {
        val a = sessionManager.assembly(sessionId)
        val wasBroken = a != null && a.session.state == SessionState.BROKEN
        val r = sessionManager.close(sessionId, force)
        if (r.isSuccess) processController.unregister(sessionId)
        return r.map {
            CloseResult(
                closed = true,
                cause = if (wasBroken) "BROKEN" else "USER",
                finalCursor = a?.ringBuffer?.totalCursor ?: 0L
            )
        }
    }

    // ───────── internal ─────────

    /** Subscribe JobManager to a session's event stream (so it can advance Job states). */
    private fun startSessionListener(sessionId: Long) {
        scope.launch {
            eventBus.subscribe(sessionId, afterCursor = 0L).collect { ev ->
                jobManager.onEvent(ev)
                // Auto-save on significant events (Spec §39)
                if (persistenceStore != null && (ev is TerminalEvent.ProcessExited || ev is TerminalEvent.SessionClosed)) {
                    autoSaveSession(sessionId)
                }
            }
        }
    }

    /** Persist current session state (Spec §39 auto-save). */
    private suspend fun autoSaveSession(sessionId: Long) {
        val store = persistenceStore ?: return
        val a = sessionManager.assembly(sessionId) ?: return
        val s = a.semanticReducer.snapshot()
        val jobs = jobManager.listBySession(sessionId)
        val events = eventLog.tail(sessionId, 100)
        val session = com.apex.agent.platform.terminal.session.TerminalSession(
            id = s.session.id, shell = s.session.shell, initialCwd = s.session.cwd,
            pid = s.session.pid, rows = s.session.rows, cols = s.session.cols,
            privilege = s.session.privilege, state = s.session.state,
            createdAt = s.session.createdAt, lastExitCode = s.session.lastExitCode,
            cursor = s.session.cursor
        )
        store.save(session, jobs, events)
        if (s.session.state == SessionState.CLOSED) store.delete(sessionId)
    }

    // ───────── push-based observation Flows (Spec §41 — event-driven, NOT polling) ─────────
    override fun screenStateFlow(sessionId: Long): kotlinx.coroutines.flow.Flow<com.apex.agent.platform.terminal.screen.TerminalScreenState>? {
        val a = sessionManager.assembly(sessionId) ?: return null
        return a.observationEngine.screenState
    }

    override fun semanticStateFlow(sessionId: Long): kotlinx.coroutines.flow.Flow<com.apex.agent.platform.terminal.state.TerminalSemanticState>? {
        val a = sessionManager.assembly(sessionId) ?: return null
        return a.observationEngine.semanticState
    }

    /**
     * Recover persisted sessions on startup (Spec §39).
     * Returns recovered session ids (now visible via snapshot()).
     * PTY fds cannot be reattached in v1; recovered sessions are EXITED/BROKEN (read-only).
     */
    override suspend fun recover(): List<Long> = recoveryService?.recover() ?: emptyList()

    /** Get a recovered session's last-known SemanticState (read-only, from persisted metadata). */
    override suspend fun recoveredSnapshot(sessionId: Long): com.apex.agent.platform.terminal.state.TerminalSemanticState? =
        recoveryService?.recoveredSnapshot(sessionId)
}
