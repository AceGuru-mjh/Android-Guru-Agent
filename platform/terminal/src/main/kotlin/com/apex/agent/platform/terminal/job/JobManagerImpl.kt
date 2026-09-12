package com.apex.agent.platform.terminal.job

import com.apex.agent.platform.terminal.session.SessionState
import com.apex.agent.platform.terminal.state.interactivePrograms

import com.apex.agent.platform.terminal.events.ExitCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBus
import com.apex.agent.platform.terminal.events.TerminalEventLog
import com.apex.agent.platform.terminal.io.InputKind
import com.apex.agent.platform.terminal.io.InputManager
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.protocol.ShellMarker
import com.apex.agent.platform.terminal.protocol.ShellMarkers
import com.apex.agent.platform.terminal.protocol.ShellMarkerTracker
import com.apex.agent.platform.terminal.session.SessionManagerImpl
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
 * Concrete JobManager. Tracks Job lifecycle + cursor marking + exit collection.
 *
 * Spec ref: ATR 2.0 Final Spec §6.3 / §10
 *
 *   - Does NOT write PTY directly (delegates InputManager).
 *   - Subscribes to EventBus for ProcessExited/WaitingInput events to advance Job state.
 *   - startCursor = session's current RingBuffer cursor at command-write time.
 *   - endCursor = cursor when ProcessExited observed.
 *
 * T81 (D-2)：超时统一走 [TimeoutController] 三级序列（SIGTERM → 宽限 → SIGKILL）。
 * 原实现私自 launch 裸 SIGKILL 发给进程组 —— native kill(-PGID) 连同 shell
 * 一起杀（一次 job 超时 = 整个 session 报废），且 timer 协程不可取消、与
 * cancel 路径叠加发信号。现在：
 *   - job 进入终态（含合成退出/取消/正常退出）自动撤销定时器；
 *   - 超时到期不再直接判死 —— SIGTERM 后命令若在宽限期内退出，shell 回到
 *     prompt 的合成退出路径会先把 job 推到终态并撤销定时器（SIGKILL 兑底
 *     不再发送）；仍存活才 SIGKILL + ProcessExited(TIMEOUT)。
 */
class JobManagerImpl(
    private val sessionManager: SessionManagerImpl,
    private val inputManager: InputManager,
    private val eventLog: TerminalEventLog,
    private val eventBus: TerminalEventBus,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** T81 (D-2)：超时统一控制器（三级序列 + 按 session/job 撤销）。 */
    private val timeoutController: com.apex.agent.platform.terminal.process.TimeoutController? = null,
    /**
     * T82 — Shell Marker Protocol（真实退出码）。默认 false 保持历史测试行为；
     * 生产 DI 显式开启（TerminalModule 传 true）。开启后：
     *   - startJob 包装命令（OSC 633 marker 携带 jobId + 真实 $?）；
     *   - marker 到达 → 确定性 ProcessExited（先于 prompt 重绘）；
     *   - prompt 启发式仅作 fallback，且优先从 tracker 解析真实退出码。
     */
    private val enableShellMarkers: Boolean = false
) : JobManager {

    private val jobs = ConcurrentHashMap<Long, TerminalJob>()

    /** T81 (D-2)：job 终态集合（J11 —— 不可逆，进入即撤销超时定时器）。 */
    private val TERMINAL_JOB_STATES = setOf(
        JobState.EXITED, JobState.INTERRUPTED, JobState.TIMED_OUT, JobState.FAILED, JobState.UNKNOWN
    )
    private val stateFlows = ConcurrentHashMap<Long, MutableStateFlow<JobState>>()
    private val idCounter = AtomicLong(0)
    private val mutex = Mutex()
    /** Most recently started non-terminal job per session (foreground job). */
    private val foregroundJobIdBySession = ConcurrentHashMap<Long, Long>()
    /** T82：per-session shell marker 记录（fallback 退出码解析）。 */
    private val markerTrackers = ConcurrentHashMap<Long, ShellMarkerTracker>()

    private fun trackerFor(sessionId: Long): ShellMarkerTracker =
        markerTrackers.getOrPut(sessionId) { ShellMarkerTracker() }

    init {
        // Subscribe to all session events to advance Job states.
        // (Per-session subscription would be cleaner; Phase 1 uses a global listener that filters.)
        scope.launch {
            // The bus is per-session; Runtime wires each new session's bus subscription to a
            // JobManager listener. For Phase 1 simplicity we expose onEvent() for the Runtime
            // to dispatch events here.
        }
    }

    /** Called by Runtime on each event for the relevant session. */
    suspend fun onEvent(event: TerminalEvent) {
        when (event) {
            is TerminalEvent.ProcessExited -> {
                val jid = event.jobId ?: return
                val job = jobs[jid] ?: return
                val newState = when (event.cause) {
                    ExitCause.NORMAL -> JobState.EXITED
                    ExitCause.USER_INTERRUPT, ExitCause.SIGNAL -> JobState.INTERRUPTED
                    ExitCause.TIMEOUT -> JobState.TIMED_OUT
                    ExitCause.BROKEN -> JobState.FAILED
                }
                transition(jid, newState)
                if (foregroundJobIdBySession[event.sessionId] == jid) {
                    foregroundJobIdBySession.remove(event.sessionId)
                }
                jobs[jid] = job.copy(
                    state = newState, exitCode = event.exitCode, signal = event.signal,
                    finishedAt = event.timestamp,
                    endCursor = sessionManager.assembly(job.sessionId)?.ringBuffer?.totalCursor
                )
            }
            is TerminalEvent.WaitingInput -> {
                val jid = event.jobId
                if (jid != null) {
                    if (event.confidence == com.apex.agent.platform.terminal.events.Confidence.HIGH_CONFIDENCE) {
                        transition(jid, JobState.WAITING_INPUT)
                    }
                    return
                }
                // event.jobId == null → shell returned to its top-level prompt (idle). If there is
                // a RUNNING foreground job whose command is NOT an interactive REPL, the command has
                // completed → emit ProcessExited so wait(ProcessExited(jobId)) resolves (Control
                // Plane contract). This is the reliable completion signal (the pump already fires
                // WaitingInput on HIGH_CONFIDENCE for "$ "/"# " prompts) and avoids mis-firing on
                // interactive REPL prompts (python/ssh/vim) which also report waiting-input.
                if (event.confidence == com.apex.agent.platform.terminal.events.Confidence.HIGH_CONFIDENCE) {
                    val fid = foregroundJobIdBySession[event.sessionId] ?: return
                    val job = jobs[fid] ?: return
                    if (job.state != JobState.RUNNING) return
                    val base = job.command.trim().substringBefore(' ').substringAfterLast('/')
                    if (base in interactivePrograms) return
                    // T82：优先用 marker 携带的真实退出码（marker 路径通常先到并已推进终态；
                    // 这里是事件乱序时的兑底 —— 不再无条件假设 0）。
                    val exitCode = if (enableShellMarkers) {
                        markerTrackers[event.sessionId]?.resolve(fid, job.startCursor)?.exitCode ?: 0
                    } else 0
                    emitProcessExited(fid, event.sessionId, ExitCause.NORMAL, exitCode)
                }
            }
            is TerminalEvent.SessionClosed -> {
                // T82：会话终局清理 marker tracker（有界内存，不随 session 泄漏）。
                markerTrackers.remove(event.sessionId)
            }
            is TerminalEvent.InputWritten -> {
                // If a WAITING_INPUT job received input, move back to RUNNING
                // (we don't know which job; the Runtime associates via the most recent foreground job)
            }
            else -> {}
        }
    }

    override suspend fun startJob(
        sessionId: Long, command: String, owner: InputOwner,
        background: Boolean, timeoutMs: Long
    ): Result<TerminalJob> {
        val a = sessionManager.assembly(sessionId)
            ?: return Result.failure(RuntimeException("TerminalError:SessionNotFound"))
        if (a.session.state == SessionState.CLOSED || a.session.state == SessionState.BROKEN) {
            return Result.failure(RuntimeException("TerminalError:SessionClosed"))
        }
        val jobId = idCounter.incrementAndGet()
        val startCursor = a.ringBuffer.totalCursor
        val job = TerminalJob(
            id = jobId, sessionId = sessionId, command = command, owner = owner,
            background = background, startCursor = startCursor, endCursor = null,
            state = JobState.CREATED, exitCode = null, signal = null,
            startedAt = System.currentTimeMillis(), finishedAt = null
        )
        jobs[jobId] = job
        foregroundJobIdBySession[sessionId] = jobId
        stateFlows[jobId] = MutableStateFlow(JobState.CREATED)

        // T82 — Shell Marker Protocol：包装命令（printf 的 \033/\007 八进制形式
        // 在 bash 与 mksh 皆可移植；行内只有可打印 ASCII，readline 不会破坏）。
        // 若 marker 到达（命令真实结束后），onShellMarker 以真实退出码推进终态 ——
        // 这取代了「prompt 出现 ⇒ exitCode=0」的合成路径成为主完成信号。
        val lineToSend = if (enableShellMarkers) ShellMarkers.wrapCommand(jobId, command) else command

        // Write the command (LINE mode appends \n)
        // T82：marker 开启时写入的是包装行 —— 策略检查基准传 **原命令**
        //（printf 插桩不是 Agent 意图，不应触发 complex-command 拒绝）。
        val writeResult = inputManager.sendLine(sessionId, owner, lineToSend, policyCommand = command)
        if (writeResult.isFailure) {
            transition(jobId, JobState.FAILED)
            return Result.failure(RuntimeException("TerminalError:WriteFailed"))
        }
        // J1: CREATED → RUNNING
        transition(jobId, JobState.RUNNING)
        val ev = TerminalEvent.ProcessStarted(
            id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(),
            cursor = startCursor, jobId = jobId, command = command, owner = owner,
            background = background, pid = a.session.pid
        )
        val eid = eventLog.append(ev)
        eventBus.emit(ev.copy(id = eid))

        // Optional timeout watcher —— T81 (D-2)：统一走 TimeoutController 三级序列
        //（SIGTERM → 宽限 → SIGKILL），取代原裸 SIGKILL 杀全组（连 shell 一起杀）。
        if (timeoutMs > 0 && timeoutController != null) {
            timeoutController.startTimeout(sessionId, jobId, timeoutMs) {
                // SIGKILL 兑底已发出（或命令已在宽限期内退出且定时器被撤销）。
                // 终态推进：仍处非终态才标记 TIMED_OUT（与并发合成退出竞争时以先到者为准）。
                scope.launch {
                    val cur = jobs[jobId] ?: return@launch
                    if (!cur.isTerminal) {
                        emitProcessExited(jobId, sessionId, ExitCause.TIMEOUT, 137)
                    }
                }
            }
        } else if (timeoutMs > 0) {
            // 兼容路径（无 TimeoutController 注入，仅测试构造）：保留旧行为但不再杀全组后立即谎报 —
            // 发 SIGKILL 后交由 exit watcher/合成退出推进终态。
            scope.launch {
                kotlinx.coroutines.delay(timeoutMs)
                val cur = jobs[jobId] ?: return@launch
                if (!cur.isTerminal) {
                    inputManager.sendSignal(sessionId, owner, UnixSignal.SIGKILL, jobId)
                    emitProcessExited(jobId, sessionId, ExitCause.TIMEOUT, 137)
                }
            }
        }
        return Result.success(jobs[jobId]!!)
    }

    /**
     * T82 — Shell Marker Protocol 入口（Runtime 的 session listener 在
     * OutputProduced 事件后从 RingBuffer 解析 marker 并回调此处）。
     *
     * marker 是**确定性完成信号**：它由 `cmd; printf … $?` 中的 printf 发出，
     * 必然在命令真实结束之后、prompt 重绘之前到达。job 仍在 RUNNING 时
     * 立即以真实退出码 emit ProcessExited（WaitEngine/观察者由此解除等待）。
     * 若 job 已终态（超时/取消先到）则只记录不推进 —— 先到者为准。
     */
    suspend fun onShellMarker(sessionId: Long, marker: ShellMarker) {
        trackerFor(sessionId).record(marker)
        val job = jobs[marker.jobId] ?: return
        if (job.state != JobState.RUNNING) return
        // 确定性终态推进：不经「listener 事件往返」（runtime listener 是异步协程 ——
        // 事件驱动足够通知 WaitEngine/观察者，但 job 状态读取方需要同步可见）。
        transition(marker.jobId, JobState.EXITED)
        jobs[marker.jobId]?.let { cur ->
            jobs[marker.jobId] = cur.copy(
            exitCode = marker.exitCode, signal = null,
            finishedAt = System.currentTimeMillis(),
                endCursor = sessionManager.assembly(sessionId)?.ringBuffer?.totalCursor
            )
        }
        if (foregroundJobIdBySession[sessionId] == marker.jobId) {
            foregroundJobIdBySession.remove(sessionId)
        }
        // 事件通知（WaitEngine/观察者/persistence autoSave —— 与 onEvent 路径幂等收敛）。
        emitProcessExited(marker.jobId, sessionId, ExitCause.NORMAL, marker.exitCode)
    }

    override suspend fun get(jobId: Long): TerminalJob? = jobs[jobId]

    override suspend fun listBySession(sessionId: Long): List<TerminalJob> =
        jobs.values.filter { it.sessionId == sessionId }

    override suspend fun activeJobs(sessionId: Long): List<TerminalJob> =
        jobs.values.filter { it.sessionId == sessionId && it.isRunning }

    override fun foregroundJobId(sessionId: Long): Long? = foregroundJobIdBySession[sessionId]

    /**
     * Emit a ProcessExited event for a job (used when the shell returns to idle and the
     * foreground command has completed). Routed through EventLog + EventBus so both the
     * WaitEngine and this manager's own listener observe it.
     */
    private suspend fun emitProcessExited(jobId: Long, sessionId: Long, cause: ExitCause, exitCode: Int?) {
        val ev = TerminalEvent.ProcessExited(
            id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(),
            cursor = -1, jobId = jobId, pid = 0, exitCode = exitCode, signal = null, cause = cause
        )
        val eid = eventLog.append(ev)
        eventBus.emit(ev.copy(id = eid))
    }

    override fun observeState(jobId: Long): Flow<JobState> =
        (stateFlows[jobId] ?: MutableStateFlow(JobState.UNKNOWN)).asStateFlow().map { it }

    private suspend fun transition(jobId: Long, to: JobState) {
        val flow = stateFlows[jobId] ?: return
        val from = flow.value
        if (from == to) return
        flow.value = to
        // Keep the jobs map in sync with the state flow; otherwise get/startJob would keep
        // returning the stale CREATED state forever (onEvent's ProcessExited branch already
        // updates jobs[jid] manually — this makes transition the single source of truth).
        val job = jobs[jobId] ?: return
        jobs[jobId] = job.copy(state = to)
        // T81 (D-2)：终态撤销超时定时器 —— job 已完成/取消/失败，定时器若继续跑
        // 会在宽限期后发 SIGKILL 到进程组（杀 shell）+ 重复发终态事件。
        if (TERMINAL_JOB_STATES.contains(to)) {
            timeoutController?.cancelTimeout(jobId)
        }
        val ev = TerminalEvent.StateChanged(
            id = 0, sessionId = job.sessionId, timestamp = System.currentTimeMillis(), cursor = -1,
            kind = com.apex.agent.platform.terminal.events.StateKind.JOB,
            targetId = jobId, from = from.name, to = to.name
        )
        val eid = eventLog.append(ev)
        eventBus.emit(ev.copy(id = eid))
    }
}
