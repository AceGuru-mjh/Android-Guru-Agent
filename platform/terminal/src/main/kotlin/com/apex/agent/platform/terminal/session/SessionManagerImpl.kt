package com.apex.agent.platform.terminal.session

import com.apex.agent.platform.terminal.buffer.RingTerminalBuffer
import com.apex.agent.platform.terminal.errors.TerminalError
import com.apex.agent.platform.terminal.events.CloseCause
import com.apex.agent.platform.terminal.events.Confidence
import com.apex.agent.platform.terminal.events.ExitCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBus
import com.apex.agent.platform.terminal.events.TerminalEventLog
import com.apex.agent.platform.terminal.events.TerminalEventLogImpl
import com.apex.agent.platform.terminal.events.TerminalEventBusImpl
import com.apex.agent.platform.terminal.io.InputManagerImpl
import com.apex.agent.platform.terminal.io.PtyOutputPumpImpl
import com.apex.agent.platform.terminal.pty.NativePty
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.policy.TerminalCapability
import com.apex.agent.platform.terminal.policy.TerminalPolicy
import com.apex.agent.platform.terminal.runtime.SpawnSpec
import com.apex.agent.platform.terminal.screen.VirtualTerminal
import com.apex.agent.platform.terminal.state.SemanticStateReducer
import com.apex.agent.platform.terminal.wait.WaitEngineImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Concrete SessionManager. Owns the full per-session assembly.
 *
 * Spec ref: ATR 2.0 Final Spec §6.2 / §9 / §16 (concurrency: single reader / single writer / state actor)
 *
 * On create():
 *   1. allocate sessionId (monotonic Long) + nativeSessionId (Int from NativePty)
 *   2. forkpty via NativePty.nativeCreateSession
 *   3. assemble per-session deps: RingBuffer, EventLog shard, VirtualTerminal, SemanticStateReducer
 *   4. start PtyOutputPump (single reader coroutine)
 *   5. start exit watcher coroutine (polls nativeIsAlive / nativeWaitExit)
 *   6. emit SessionCreated event
 *
 * The nativeSessionId (Int) is stored alongside the Long sessionId because the Runtime API uses
 * Long ids (future-proof) while the JNI NativePty uses Int ids (legacy). Phase 1 maps them 1:1;
 * Phase 2 may decouple them.
 */
class SessionManagerImpl(
    private val native: NativePty,
    private val eventLog: TerminalEventLog,
    private val eventBus: TerminalEventBus,
    private val waitEngine: WaitEngineImpl,
    private val inputManager: InputManagerImpl,
    private val virtualTerminalFactory: (Int, Int) -> VirtualTerminal,
    private val policy: TerminalPolicy,
    private val inputDetector: com.apex.agent.platform.terminal.state.InputWaitingDetector? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : SessionManager {

    /** Per-session assembled state. */
    data class SessionAssembly(
        val session: TerminalSession,
        val nativeSessionId: Int,
        val ringBuffer: RingTerminalBuffer,
        val virtualTerminal: VirtualTerminal,
        val semanticReducer: SemanticStateReducer,
        val pump: PtyOutputPumpImpl,
        val observationEngine: com.apex.agent.platform.terminal.state.ObservationEngine
    )

    private val assemblies = ConcurrentHashMap<Long, SessionAssembly>()
    private val idCounter = AtomicLong(0)
    private val stateFlows = ConcurrentHashMap<Long, MutableStateFlow<SessionState>>()
    private val mutex = Mutex()

    override suspend fun create(
        shell: String, cwd: String, rows: Int, cols: Int,
        env: Map<String, String>, privilege: PrivilegeLevel
    ): Result<TerminalSession> = mutex.withLock {
        val sessionId = idCounter.incrementAndGet()
        // 1. forkpty（旧路径：单 shell + env 数组；与 LocalShellBackend.prepare
        //    产出的 argv 路径在 C++ 层逐字节等价 —— T73 统一路由后仅作兼容入口保留）
        val envArray = env.map { "${it.key}=${it.value}" }.toTypedArray()
        val nativeId = native.nativeCreateSession(shell, cwd, rows, cols, envArray)
        if (nativeId < 0) {
            return@withLock Result.failure(RuntimeException("TerminalError:PtyUnavailable"))
        }
        assembleAndStart(
            sessionId = sessionId, nativeId = nativeId,
            shell = shell, initialCwd = cwd,
            rows = rows, cols = cols, privilege = privilege,
            backend = null
        )
    }

    /**
     * T73: SpawnSpec 路径 —— TerminalRuntime.create(backendId=…) 的生产入口。
     * spawn 走 [NativePty.nativeCreateSessionArgv]（forkpty → execv(argv[0], argv)）。
     */
    override suspend fun createFromSpec(
        spec: SpawnSpec, rows: Int, cols: Int, privilege: PrivilegeLevel
    ): Result<TerminalSession> = mutex.withLock {
        val sessionId = idCounter.incrementAndGet()
        if (spec.argv.isEmpty()) {
            return@withLock Result.failure(RuntimeException("TerminalError:InvalidInput — empty argv"))
        }
        val nativeId = native.nativeCreateSessionArgv(
            spec.argv, spec.cwd, rows, cols, spec.env
        )
        if (nativeId < 0) {
            return@withLock Result.failure(RuntimeException("TerminalError:PtyUnavailable"))
        }
        // 展示语义：LINUX 会话 shell=/bin/bash、cwd=guest -w；LOCAL 与旧路径一致。
        val shellDisplay = spec.shellDisplay ?: spec.argv[0]
        val cwdDisplay = spec.cwdDisplay ?: spec.metadata.guestCwd ?: spec.cwd
        assembleAndStart(
            sessionId = sessionId, nativeId = nativeId,
            shell = shellDisplay, initialCwd = cwdDisplay,
            rows = rows, cols = cols, privilege = privilege,
            backend = spec.metadata
        )
    }

    /** 共享装配：deps 组装 → pump 启动 → READY → SessionCreated → exit watcher。 */
    private suspend fun assembleAndStart(
        sessionId: Long, nativeId: Int,
        shell: String, initialCwd: String,
        rows: Int, cols: Int, privilege: PrivilegeLevel,
        backend: com.apex.agent.platform.terminal.runtime.BackendSessionMetadata?
    ): Result<TerminalSession> {
        val pid = native.nativeGetPid(nativeId)
        // 2. assemble deps
        val ringBuffer = RingTerminalBuffer()
        val vt = virtualTerminalFactory(rows, cols)
        val reducer = SemanticStateReducer(
            sessionId = sessionId, shell = shell, initialCwd = initialCwd, privilege = privilege,
            pid = pid, rows = rows, cols = cols,
            // TM2: feed the reducer the session's recent PTY bytes (last 4 KB from the
            // RingBuffer) so ErrorClassifier.classify can apply its regex patterns.
            // Previously classify() was always called with recentOutput=null → every
            // pattern in ErrorClassifier was dead code in production.
            recentOutputProvider = { ringBuffer.latest(4096).bytes.toString(Charsets.UTF_8) }
        )
        val observationEngine = com.apex.agent.platform.terminal.state.ObservationEngine(
            eventLog, ringBuffer, vt, reducer
        )
        val pump = PtyOutputPumpImpl(
            sessionId = sessionId, nativeSessionId = nativeId, native = native,
            ringBuffer = ringBuffer, eventLog = eventLog, eventBus = eventBus,
            virtualTerminal = vt, semanticReducer = reducer, waitEngine = waitEngine,
            inputDetector = inputDetector,
            foregroundCommandProvider = { foregroundCommandFor(sessionId) },
            onOutput = { observationEngine.refreshScreenState() },  // push screen state (event-driven)
            scope = scope  // P70: shared session-manager scope (injectable in tests; pump.stop cancels only its own job)
        )
        val session = TerminalSession(
            id = sessionId, shell = shell, initialCwd = initialCwd, pid = pid,
            rows = rows, cols = cols, privilege = privilege, state = SessionState.STARTING,
            createdAt = System.currentTimeMillis(), lastExitCode = null, cursor = 0L,
            backend = backend
        )
        assemblies[sessionId] = SessionAssembly(session, nativeId, ringBuffer, vt, reducer, pump, observationEngine)
        stateFlows[sessionId] = MutableStateFlow(SessionState.STARTING)
        // 3. start pump
        pump.start()
        // 4. transition to READY (S2)
        transition(sessionId, SessionState.READY)
        // 5. emit SessionCreated
        val ev = TerminalEvent.SessionCreated(
            id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(), cursor = -1,
            shell = shell, cwd = initialCwd, pid = pid, rows = rows, cols = cols, privilege = privilege
        )
        val eid = eventLog.append(ev)
        eventBus.emit(ev.copy(id = eid))
        // 6. start exit watcher
        startExitWatcher(sessionId, nativeId)
        return Result.success(assemblies[sessionId]!!.session.copy(state = SessionState.READY))
    }

    override suspend fun get(id: Long): TerminalSession? {
        val a = assemblies[id] ?: return null
        return a.session.copy(
            state = stateFlows[id]?.value ?: a.session.state,
            cursor = a.ringBuffer.totalCursor,
            lastExitCode = a.semanticReducer.snapshot().session.lastExitCode
        )
    }

    override suspend fun list(): List<TerminalSession> {
        return assemblies.keys.mapNotNull { get(it) }.filter { it.state != SessionState.CLOSED }
    }

    override suspend fun close(id: Long, force: Boolean): Result<Unit> {
        val a = assemblies[id] ?: return Result.failure(RuntimeException("TerminalError:SessionNotFound"))
        if (stateFlows[id]?.value == SessionState.CLOSED) return Result.success(Unit)
        if (force) native.nativeSendSignal(a.nativeSessionId, 9)  // SIGKILL
        // stop pump
        a.pump.stop()
        // SIGHUP the shell
        native.nativeSendSignal(a.nativeSessionId, 1)
        // close native
        native.nativeCloseSession(a.nativeSessionId)
        transition(id, SessionState.CLOSED)
        val cause = if (stateFlows[id]?.value == SessionState.BROKEN) CloseCause.BROKEN else CloseCause.USER
        val ev = TerminalEvent.SessionClosed(
            id = 0, sessionId = id, timestamp = System.currentTimeMillis(), cursor = -1, cause = cause
        )
        val eid = eventLog.append(ev)
        eventBus.emit(ev.copy(id = eid))
        // cleanup
        inputManager.drop(id)
        waitEngine.drop(id)
        assemblies.remove(id)
        stateFlows.remove(id)
        return Result.success(Unit)
    }

    // PR #54 §5: stop jobs but keep Session alive
    override suspend fun stop(id: Long): Result<SessionState> = mutex.withLock {
        val a = assemblies[id] ?: return@withLock Result.failure(RuntimeException("TerminalError:SessionNotFound"))
        transition(id, SessionState.STOPPING)
        kotlinx.coroutines.delay(100)
        transition(id, SessionState.READY)
        Result.success(SessionState.READY)
    }

    // PR #54 §8/§19: reconcile persisted vs actual PTY state
    override suspend fun reconcile(persisted: List<Long>): List<SessionManager.ReconciliationResult> = mutex.withLock {
        persisted.map { sid ->
            val a = assemblies[sid]
            val actualState = if (a != null) {
                // PTY exists in runtime — check if alive
                if (native.nativeIsAlive(a.nativeSessionId)) a.session.state else SessionState.LOST
            } else {
                // PTY not in runtime — it's gone
                SessionState.LOST
            }
            val persistedState = a?.session?.state?.name ?: "UNKNOWN"
            if (actualState == SessionState.LOST && a != null) {
                transition(sid, SessionState.LOST)
            }
            SessionManager.ReconciliationResult(
                sessionId = sid,
                persistedState = persistedState,
                actualState = actualState,
                recoverable = actualState != SessionState.LOST && actualState != SessionState.FAILED
            )
        }
    }

    override fun observeState(id: Long): Flow<SessionState> =
        (stateFlows[id] ?: MutableStateFlow(SessionState.CLOSED)).asStateFlow().map { it }

    /** Internal: transition a session's state + emit StateChanged. */
    suspend fun transition(sessionId: Long, to: SessionState) {
        val flow = stateFlows[sessionId] ?: return
        val from = flow.value
        if (from == to) return
        flow.value = to
        assemblies[sessionId]?.let { a ->
            val ev = TerminalEvent.StateChanged(
                id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(), cursor = -1,
                kind = com.apex.agent.platform.terminal.events.StateKind.SESSION,
                targetId = sessionId, from = from.name, to = to.name
            )
            val eid = eventLog.append(ev)
            eventBus.emit(ev.copy(id = eid))
        }
    }

    /** Get the assembled deps for a session (used by Runtime/JobManager). */
    fun assembly(id: Long): SessionAssembly? = assemblies[id]

    /** PR #56: Get LIVE session state (from stateFlows, not stale assembly). */
    fun sessionState(id: Long): SessionState? = stateFlows[id]?.value

    /** Start a background coroutine that watches for shell process exit. */
    private fun startExitWatcher(sessionId: Long, nativeId: Int) {
        scope.launch {
            while (true) {
                val alive = native.nativeIsAlive(nativeId)
                if (!alive) {
                    val exit = native.nativeGetExitCode(nativeId)
                    val ev = TerminalEvent.ProcessExited(
                        id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(),
                        cursor = -1, jobId = null, pid = native.nativeGetPid(nativeId),
                        exitCode = exit, signal = null, cause = ExitCause.NORMAL
                    )
                    val eid = eventLog.append(ev)
                    eventBus.emit(ev.copy(id = eid))
                    transition(sessionId, SessionState.EXITED)
                    break
                }
                kotlinx.coroutines.delay(EXIT_POLL_MS)
            }
        }
    }

    /** Get the foreground job's command for a session (for InputWaitingDetector). */
    private fun foregroundCommandFor(sessionId: Long): String? {
        // The JobManager holds job state; SessionManager doesn't have direct access.
        // For Phase 2, we read from the SemanticStateReducer's snapshot (foregroundJob.command).
        val a = assemblies[sessionId] ?: return null
        return a.semanticReducer.snapshot().foregroundJob?.command
    }

    companion object {
        private const val EXIT_POLL_MS = 100L
    }
}
