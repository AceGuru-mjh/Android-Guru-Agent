package com.apex.agent.platform.terminal.job

import com.apex.agent.platform.terminal.session.SessionState

import com.apex.agent.platform.terminal.events.ExitCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBus
import com.apex.agent.platform.terminal.events.TerminalEventLog
import com.apex.agent.platform.terminal.io.InputKind
import com.apex.agent.platform.terminal.io.InputManager
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
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
 */
class JobManagerImpl(
    private val sessionManager: SessionManagerImpl,
    private val inputManager: InputManager,
    private val eventLog: TerminalEventLog,
    private val eventBus: TerminalEventBus,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : JobManager {

    private val jobs = ConcurrentHashMap<Long, TerminalJob>()
    private val stateFlows = ConcurrentHashMap<Long, MutableStateFlow<JobState>>()
    private val idCounter = AtomicLong(0)
    private val mutex = Mutex()

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
                jobs[jid] = job.copy(
                    state = newState, exitCode = event.exitCode, signal = event.signal,
                    finishedAt = event.timestamp,
                    endCursor = sessionManager.assembly(job.sessionId)?.ringBuffer?.totalCursor
                )
            }
            is TerminalEvent.WaitingInput -> {
                val jid = event.jobId ?: return
                if (event.confidence == com.apex.agent.platform.terminal.events.Confidence.HIGH_CONFIDENCE) {
                    transition(jid, JobState.WAITING_INPUT)
                }
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
        stateFlows[jobId] = MutableStateFlow(JobState.CREATED)

        // Write the command (LINE mode appends \n)
        val writeResult = inputManager.sendLine(sessionId, owner, command)
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

        // Optional timeout watcher
        if (timeoutMs > 0) {
            scope.launch {
                kotlinx.coroutines.delay(timeoutMs)
                val cur = jobs[jobId] ?: return@launch
                if (cur.state == JobState.RUNNING || cur.state == JobState.WAITING_INPUT) {
                    inputManager.sendSignal(sessionId, owner, UnixSignal.SIGKILL, jobId)
                    val exEv = TerminalEvent.ProcessExited(
                        id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(),
                        cursor = -1, jobId = jobId, pid = a.session.pid,
                        exitCode = 137, signal = UnixSignal.SIGKILL, cause = ExitCause.TIMEOUT
                    )
                    val xid = eventLog.append(exEv)
                    eventBus.emit(exEv.copy(id = xid))
                }
            }
        }
        return Result.success(jobs[jobId]!!)
    }

    override suspend fun get(jobId: Long): TerminalJob? = jobs[jobId]

    override suspend fun listBySession(sessionId: Long): List<TerminalJob> =
        jobs.values.filter { it.sessionId == sessionId }

    override suspend fun activeJobs(sessionId: Long): List<TerminalJob> =
        jobs.values.filter { it.sessionId == sessionId && it.isRunning }

    override fun observeState(jobId: Long): Flow<JobState> =
        (stateFlows[jobId] ?: MutableStateFlow(JobState.UNKNOWN)).asStateFlow().map { it }

    private suspend fun transition(jobId: Long, to: JobState) {
        val flow = stateFlows[jobId] ?: return
        val from = flow.value
        if (from == to) return
        flow.value = to
        val job = jobs[jobId] ?: return
        val ev = TerminalEvent.StateChanged(
            id = 0, sessionId = job.sessionId, timestamp = System.currentTimeMillis(), cursor = -1,
            kind = com.apex.agent.platform.terminal.events.StateKind.JOB,
            targetId = jobId, from = from.name, to = to.name
        )
        val eid = eventLog.append(ev)
        eventBus.emit(ev.copy(id = eid))
    }
}
