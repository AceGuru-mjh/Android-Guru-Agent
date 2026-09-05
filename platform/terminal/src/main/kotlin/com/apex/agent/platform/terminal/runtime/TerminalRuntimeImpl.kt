package com.apex.agent.platform.terminal.runtime

import com.apex.agent.platform.terminal.buffer.TerminalOutputBuffer
import com.apex.agent.platform.terminal.errors.TerminalError
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBus
import com.apex.agent.platform.terminal.events.TerminalEventBusImpl
import com.apex.agent.platform.terminal.events.TerminalEventLog
import com.apex.agent.platform.terminal.events.TerminalEventLogImpl
import com.apex.agent.platform.terminal.events.ExitCause
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
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * Concrete TerminalRuntime. Wires together all subsystems and exposes the 9 Agent operations.
 *
 * Spec ref: ATR 2.0 Final Spec §6.1 / §33 / §34
 *
 * T73: create() 统一经 [ExecutionBackendRegistry] 路由 —— backendId="local" 走
 * LocalShellBackend（与旧硬编码 spawn 逐字节一致，ExecutionBackendGoldenTest 锁定），
 * backendId="linux-ubuntu" 走 LinuxPRootBackend（forkpty → proot → Ubuntu bash）。
 * 会话的后端元数据进 TerminalSession.backend 并持久化（SessionRecord schema v2）。
 *
 * Construction (Hilt in real repo; manual here):
 *   val runtime = TerminalRuntimeImpl(
 *       native = FakeNativePty() or JniNativePty(),
 *       policy = TerminalPolicyImpl(),
 *       backendRegistry = ExecutionBackendRegistry.of(LocalShellBackend(), linuxBackend),
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
    private val backendRegistry: ExecutionBackendRegistry = ExecutionBackendRegistry.of(LocalShellBackend()),
    private val virtualTerminalFactory: (Int, Int) -> VirtualTerminal = { r, c ->
        com.apex.agent.platform.terminal.screen.RealVirtualTerminal(r, c)
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * P70: dedicated scope for per-session output pumps + exit watchers (blocking
     * poll loops — IO-bound, hence IO dispatcher by default). Injectable so tests can
     * run pumps on an isolated dispatcher instead of the shared Dispatchers.IO pool,
     * which gets saturated by leftover pumps from earlier tests in the same JVM.
     * Production behavior is unchanged (same IO pool as before the refactor).
     */
    private val pumpScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Optional persistence (Spec §39). If set, auto-saves session metadata + enables recover(). */
    private val persistenceStore: SessionMetadataStore? = null,
    /**
     * T75: 会话 ↔ workspace 绑定钩子（LINUX 会话）。create 成功后 bind、
     * close 成功后 unbind —— LinuxWorkspaceManager 的活跃计数由此驱动
     * （delete 拒绝有活跃会话的 workspace）。生产 DI 注入；测试可传 fake。
     */
    private val workspaceBinder: com.apex.agent.platform.terminal.workspace.SessionWorkspaceBinder? = null
) : TerminalRuntime {

    private val recoveryService: RuntimeRecoveryService? = persistenceStore?.let {
        RuntimeRecoveryService(it, this, scope)
    }

    /** T81 §15：shutdown 幂等门（首次进入后 create/reject 新请求）。 */
    private val shutdownGate = java.util.concurrent.atomic.AtomicBoolean(false)
    private var shutdownResult: Result<TerminalRuntime.ShutdownResult>? = null

    private val eventLog: TerminalEventLog = TerminalEventLogImpl()
    private val eventBus: TerminalEventBus = TerminalEventBusImpl(eventLog, scope)
    private val waitEngine = WaitEngineImpl(eventBus, scope)
    internal val inputManager = InputManagerImpl(policy, native, eventLog, eventBus, scope)
    private val inputDetector = com.apex.agent.platform.terminal.state.InputWaitingDetector()
    // PR #51: process/timeout/cancellation controllers
    // ProcessController routes signal() through InputManager (policy + control-state + events);
    // the NATIVE layer delivers to the whole process group (kill(-PGID)).
    private val processController = com.apex.agent.platform.terminal.process.ProcessController(inputManager)
    private val timeoutController = com.apex.agent.platform.terminal.process.TimeoutController(inputManager)
    private val cancellationController = com.apex.agent.platform.terminal.process.JobCancellationController(
        inputManager, timeoutController,
        onCancelled = { sessionId, jobId ->
        // Job was cancelled by the agent → emit ProcessExited so wait(ProcessExited(jobId))
        // resolves with a non-RUNNING state (Control Plane contract).
        // T81 (D-2)：如实上报 —— 取消序列实际发送 SIGTERM（信号 15），合成事件
        // 不再谎报 SIGINT/130（原实现语义错位：信号类型与 exit code 不匹配）。
        scope.launch {
            val ev = TerminalEvent.ProcessExited(
                id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(),
                cursor = -1, jobId = jobId, pid = 0,
                exitCode = 143, signal = UnixSignal.SIGTERM, cause = ExitCause.USER_INTERRUPT
            )
            val eid = eventLog.append(ev)
            eventBus.emit(ev.copy(id = eid))
        }
        }
    )
    internal val sessionManager = SessionManagerImpl(
        native, eventLog, eventBus, waitEngine, inputManager, virtualTerminalFactory, policy,
        inputDetector, pumpScope  // P70: pumps + exit watchers on the injectable pump scope
    )
    private val jobManager = JobManagerImpl(sessionManager, inputManager, eventLog, eventBus, scope, timeoutController)

    init {
        // P70-4: wire the REAL sessionId(Long) → nativeSessionId(Int) mapping into the
        // write path. Previously InputManagerImpl guessed `sessionId.toInt()`, which
        // diverges from the native engine's process-wide id counter whenever the Runtime
        // is rebuilt in-process (Kotlin counter resets, native counter never does) —
        // writes/signals then landed on the WRONG native session.
        inputManager.nativeIdResolver = { sid -> sessionManager.assembly(sid)?.nativeSessionId }

        // T81 (D-5)：周期性 auto-save 接线（原 startAutoSave 生产零调用 —— 持久化
        // 只在 ProcessExited 事件时发生，长命令运行中 crash 丢全部中间状态）。
        // backend 字段经 TerminalSession 原样保留（不再从 SemanticState 重建丢字段）。
        recoveryService?.startAutoSave(
            intervalMs = 2000L,
            liveSessionsProvider = { sessionManager.list() },
            liveJobsProvider = { sid -> jobManager.listBySession(sid) },
            recentEventsProvider = { sid -> eventLog.tail(sid, 100) }
        )

        // TM1: wire a recent-output provider into the WaitEngine so OutputMatch.pattern
        // is tested against real PTY bytes (the last 4 KB from the per-session
        // RingBuffer). Without this, WaitEngineImpl.matchOutput returned true on ANY
        // OutputProduced event, making the pattern field dead (every wait(OutputMatch)
        // completed instantly). The RingBuffer is per-session, owned by SessionManager.
        waitEngine.recentOutputProvider = { sid ->
            sessionManager.assembly(sid)?.ringBuffer
                ?.latest(4096)?.bytes?.toString(Charsets.UTF_8) ?: ""
        }
    }

    // ───────── create ─────────
    override suspend fun create(
        shell: String, cwd: String, rows: Int, cols: Int,
        env: Map<String, String>, privilege: PrivilegeLevel,
        backendId: String, workspaceId: String?
    ): Result<CreateResult> {
        // T81 §15：shutdown 后拒绝新会话
        if (shutdownGate.get()) {
            return Result.failure(RuntimeException("TerminalError:UnsupportedOperation — runtime 已 shutdown"))
        }
        // T73: 后端路由。local 默认 —— 与历史行为一致（golden）；
        // linux-ubuntu —— 失败时给出可行动错误（引导 Agent 先装 rootfs）。
        val backend = backendRegistry.get(backendId)
            ?: return Result.failure(
                RuntimeException(
                    "TerminalError:BackendNotFound — '$backendId'（可用: " +
                        backendRegistry.list().joinToString(", ") { it.id } + "）"
                )
            )
        // T75: workspace 是 LINUX 后端概念 —— LOCAL 会话拒绝（显式报错优于静默忽略）。
        if (!workspaceId.isNullOrBlank() && backend.runtimeType != BackendRuntimeType.LINUX) {
            return Result.failure(
                IllegalArgumentException(
                    "TerminalError:InvalidInput — workspaceId 仅支持 LINUX 后端" +
                        "（当前 backend='${backend.id}'）。workspace 管理见 terminal.workspaces。"
                )
            )
        }
        when (val av = backend.availability()) {
            is BackendAvailability.Ready -> Unit
            is BackendAvailability.NeedsRootfs -> return Result.failure(
                RuntimeException(
                    "TerminalError:RootfsNotReady — backend '${backend.id}' 需要 Ubuntu rootfs" +
                        "（state=${av.state}）。先用 terminal.ubuntu.install 引导安装，再重试 create。"
                )
            )
            is BackendAvailability.Failed -> return Result.failure(
                RuntimeException(
                    "TerminalError:BackendFailed — backend '${backend.id}' 不可用: ${av.reason}"
                )
            )
        }
        val request = SessionSpawnRequest(
            shellHint = shell.takeIf { backendId == LocalShellBackend.ID },
            cwd = cwd, rows = rows, cols = cols, env = env, privilege = privilege,
            workspaceId = workspaceId
        )
        val spec = backend.prepare(request).getOrElse { e ->
            return Result.failure(RuntimeException("TerminalError:BackendPrepareFailed — ${e.message}", e))
        }
        val r = sessionManager.createFromSpec(spec, rows, cols, privilege)
        return r.map { s ->
            // T75: LINUX 会话创建成功 → 绑定 workspace（活跃计数；delete 门禁）
            spec.metadata.workspaceId?.let { wsId -> workspaceBinder?.bind(s.id, wsId) }
            // start a JobManager listener for this session
            startSessionListener(s.id)
            // Register the session's process group (v1: pgid == shell pid — forkpty makes the
            // PTY child a session + process-group leader, so PGID == PID). Signals routed via
            // ProcessController are then delivered to the whole group by the native layer.
            processController.registerGroup(s.id, null, s.pid)
            CreateResult(
                sessionId = s.id, pid = s.pid, shell = s.shell, cwd = s.initialCwd,
                rows = s.rows, cols = s.cols, privilege = s.privilege,
                state = s.state.name, cursor = s.cursor,
                backendId = backend.id,
                runtimeType = backend.runtimeType.name,
                rootfsId = s.backend?.rootfsId,
                guestCwd = s.backend?.guestCwd,
                workspaceId = s.backend?.workspaceId
            )
        }
    }

    // ───────── backends（T73：Agent 能力发现）─────────
    override suspend fun backends(): List<TerminalRuntime.BackendStatus> =
        backendRegistry.list().map { b ->
            when (val av = b.availability()) {
                is BackendAvailability.Ready -> TerminalRuntime.BackendStatus(
                    id = b.id, runtimeType = b.runtimeType.name,
                    available = true, state = "READY", detail = null
                )
                is BackendAvailability.NeedsRootfs -> TerminalRuntime.BackendStatus(
                    id = b.id, runtimeType = b.runtimeType.name,
                    available = false, state = "NEEDS_ROOTFS:${av.state}",
                    detail = "Ubuntu rootfs 未就绪 —— 调用 terminal.ubuntu.install 安装后重试"
                )
                is BackendAvailability.Failed -> TerminalRuntime.BackendStatus(
                    id = b.id, runtimeType = b.runtimeType.name,
                    available = false, state = "FAILED", detail = av.reason
                )
            }
        }

    // ───────── run ─────────
    override suspend fun run(
        sessionId: Long, command: String, owner: InputOwner,
        background: Boolean, timeoutMs: Long
    ): Result<RunResult> {
        // T81 §15：shutdown 后拒绝新 job
        if (shutdownGate.get()) {
            return Result.failure(RuntimeException("TerminalError:UnsupportedOperation — runtime 已 shutdown"))
        }
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
        val res: Result<com.apex.agent.platform.terminal.io.WriteResult> = when (kind) {
            WriteKind.RAW -> inputManager.writeRaw(sessionId, owner, text ?: "")
            WriteKind.LINE -> inputManager.sendLine(sessionId, owner, text ?: "")
            WriteKind.KEY -> inputManager.sendKey(sessionId, owner, key ?: TerminalKey.ENTER)
        }
        return res.map { wr ->
            // bytesWritten reflects the actual bytes written to the PTY (LINE appends '\n', RAW does not).
            WriteResult(
                written = true, bytesWritten = wr.bytesWritten,
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
        // 0. Validate dimensions (Spec §34.7). Zero or negative rows/cols are rejected
        //    BEFORE touching the native layer or VT.
        if (rows <= 0 || cols <= 0) {
            return Result.failure(RuntimeException("TerminalError:InvalidDimensions"))
        }
        // 1. Resize the native PTY FIRST (sends SIGWINCH to child). Spec §34.7 / §18.
        //    If native resize fails, VirtualTerminal MUST NOT be updated (correctness: avoid
        //    VT/kernel size mismatch that breaks vim/top/less).
        val nativeOk = native.nativeResize(a.nativeSessionId, rows, cols)
        if (!nativeOk) {
            return Result.failure(RuntimeException("TerminalError:UnsupportedOperation"))
        }
        // 2. Native OK → update VirtualTerminal to match.
        a.virtualTerminal.resize(rows, cols)
        // 3. Reflect the new size in the semantic reducer synchronously. In v1 the reducer is
        //    only driven by PTY output events, so a following observe(SEMANTIC) would otherwise
        //    still report the old dimensions. The broadcast below is for EVENT observers/logs.
        a.semanticReducer.onEvent(
            com.apex.agent.platform.terminal.events.TerminalEvent.ResizeChanged(
                id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(), cursor = -1,
                rows = rows, cols = cols
            )
        )
        // 4. Emit ResizeChanged event.
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

    // ───────── stop (Spec PR #54 §5) ─────────
    /** Stop running jobs but keep Session alive. ≠ close(). Idempotent. */
    override suspend fun stop(sessionId: Long): Result<TerminalRuntime.StopResult> {
        val a = sessionManager.assembly(sessionId) ?: return Result.failure(RuntimeException("TerminalError:SessionNotFound"))
        // Transition to STOPPING (graceful shutdown)
        sessionManager.transition(sessionId, SessionState.STOPPING)
        // Cancel all active jobs in this session
        val active = jobManager.activeJobs(sessionId)
        for (job in active) {
            cancellationController.cancel(sessionId, job.id)
        }
        kotlinx.coroutines.delay(100)  // brief grace
        // Transition back to READY (session still alive, jobs stopped)
        sessionManager.transition(sessionId, SessionState.READY)
        return Result.success(TerminalRuntime.StopResult(stopped = true, jobId = active.firstOrNull()?.id, finalState = SessionState.READY.name))
    }

    // ───────── close (Spec §34.9 + PR #54 §4 idempotent) ─────────
    override suspend fun close(sessionId: Long, force: Boolean): Result<CloseResult> {
        // PR #54 §4: idempotent — if already closed, return success
        val a = sessionManager.assembly(sessionId)
        if (a == null) {
            // Already closed (assembly removed) — return idempotent success
            return Result.success(CloseResult(closed = true, cause = "ALREADY_CLOSED", finalCursor = 0L))
        }
        val wasLost = a.session.state == SessionState.LOST || a.session.state == SessionState.BROKEN
        val wasBroken = a.session.state == SessionState.BROKEN
        // Cancel all jobs before closing (Spec §7: cancel → SIGTERM → wait → SIGKILL → close PTY)
        if (force) {
            val active = jobManager.activeJobs(sessionId)
            for (job in active) cancellationController.cancel(sessionId, job.id)
        }
        val r = sessionManager.close(sessionId, force)
        if (r.isSuccess) {
            processController.unregister(sessionId)
            // T75: 会话关闭 → 释放 workspace 活跃绑定（delete 门禁解除）
            workspaceBinder?.unbind(sessionId)
            // T81 (D-4)：只收敛本 session 的超时定时器 —— 原实现调
            // timeoutController.cancelAll()（全局），关 session A 会误杀
            // session B/C 的 job 超时定时器。
            timeoutController.cancelSession(sessionId)
            // T81：close 后同步删除持久化记录（§39 语义：CLOSED → 不再保留；
            // 原路径依赖 collector 收到 SessionClosed 时 autoSave，与 assembly
            // 移除存在竞态，CLOSED 终态可能从未落盘）。
            persistenceStore?.delete(sessionId)
        }
        return r.map {
            val cause = when {
                wasLost -> "LOST"
                wasBroken -> "BROKEN"
                else -> "USER"
            }
            CloseResult(closed = true, cause = cause, finalCursor = a.ringBuffer?.totalCursor ?: 0L)
        }
    }

    // ───────── internal ─────────

    /** Subscribe JobManager to a session's event stream (so it can advance Job states). */
    private fun startSessionListener(sessionId: Long) {
        scope.launch {
            // T81 (D-4)：SessionClosed 后终止 collect —— 原实现永久 collect 一个
            // 永不 complete 的 SharedFlow（bus 的 drop 只移除 map 条目，不影响
            // 已订阅的 collector），每个 session 泄漏一个协程。
            // 持久化终态在 close() 主路径同步处理（此处 assembly 可能已移除，
            // autoSave 是 no-op）；ProcessExited 的自动保存仍在事件到达时执行。
            eventBus.subscribe(sessionId, afterCursor = 0L)
                .takeWhile { ev -> ev !is TerminalEvent.SessionClosed }
                .collect { ev ->
                    jobManager.onEvent(ev)
                    // Auto-save on significant events (Spec §39)
                    if (persistenceStore != null && ev is TerminalEvent.ProcessExited) {
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

    // ───────── shutdown（T81 §15）─────────
    /**
     * 幂等收敛链：停新请求 → cancel 全部 job → close 全部 session（含 pump/bus/log
     * 清理）→ 停协程域 → nativeCloseAll 兕底（防 Kotlin/native id 失步残留）→
     * 停 autoSave + 持久化最终 flush。
     */
    override suspend fun shutdown(): Result<TerminalRuntime.ShutdownResult> {
        shutdownResult?.let { return it }
        if (!shutdownGate.compareAndSet(false, true)) {
            // 并发 shutdown：等待胜者完成（简化 —— 再次调用返回既有/默认结果）
            return shutdownResult ?: Result.success(TerminalRuntime.ShutdownResult(0, 0, true))
        }
        // 1. cancel 全部活跃 job（三级序列 SIGTERM→grace→SIGKILL）
        var cancelled = 0
        for (s in sessionManager.list()) {
            for (j in jobManager.activeJobs(s.id)) {
                cancellationController.cancel(s.id, j.id)
                cancelled++
            }
        }
        // 2. close 全部 session（force：内部 SIGHUP→TERM→KILL 有界序列 + pump 停止 +
        //    bus/log drop + 持久化记录删除）
        val sessions = sessionManager.list()
        var clean = true
        var closed = 0
        for (s in sessions) {
            val wasBroken = s.state == com.apex.agent.platform.terminal.session.SessionState.BROKEN ||
                s.state == com.apex.agent.platform.terminal.session.SessionState.LOST
            val r = close(s.id, force = true)
            if (r.isSuccess) closed++ else clean = false
            if (wasBroken) clean = false
        }
        // 3. 停止全部超时定时器 + autoSave
        timeoutController.cancelAll()
        recoveryService?.stopAutoSave()
        // 4. native 兕底：Kotlin 侧可能因历史 bug/重建失步残留 native session
        //    （closeAll 幂等，对已关 id 无副作用）。
        try { native.nativeCloseAll() } catch (_: Exception) {}
        // 5. 停协程域（listener/exit watcher/pump 域 —— SupervisorJob 的 cancel 是协作式）
        scope.cancel()
        pumpScope.cancel()
        val result = Result.success(TerminalRuntime.ShutdownResult(closed, cancelled, clean))
        shutdownResult = result
        return result
    }

    /** T81 测试钩子：事件总线只读视图（事件收敛回归测试用）。 */
    internal fun eventBusPublic(): TerminalEventBus = eventBus

    /** T81 测试钩子：SessionManager 只读视图（close 收敛回归测试用）。 */
    internal fun sessionManagerPublic(): SessionManagerImpl = sessionManager

    /** T81 测试钩子：事件日志计数（验证 close 后无事件再追加）。 */
    internal suspend fun eventLogCountPublic(sessionId: Long): Long = eventLog.count(sessionId)

    /** T81 测试钩子：job 状态查询（超时/取消收敛测试用）。 */
    internal suspend fun jobStatePublic(jobId: Long): String? = jobManager.get(jobId)?.state?.name

    /** T81 测试钩子：job 快照（终态字段验证用）。 */
    internal suspend fun jobPublic(jobId: Long): com.apex.agent.platform.terminal.job.TerminalJob? = jobManager.get(jobId)

    /** T81 测试钩子：native 适配器（shutdown 后无 native 残留验证用）。 */
    internal fun nativePublic(): com.apex.agent.platform.terminal.pty.NativePty = native
}
