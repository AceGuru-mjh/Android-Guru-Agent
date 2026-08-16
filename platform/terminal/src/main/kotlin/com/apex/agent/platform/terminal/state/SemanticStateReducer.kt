package com.apex.agent.platform.terminal.state

import com.apex.agent.platform.terminal.events.CloseCause
import com.apex.agent.platform.terminal.events.Confidence
import com.apex.agent.platform.terminal.events.ExitCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputControlState
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.job.JobState
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Incremental SemanticState reducer. Subscribes to EventBus and updates the aggregated
 * snapshot on each event (does NOT recompute everything on observe()).
 *
 * Spec ref: ATR 2.0 Final Spec §26 / §30.1
 *
 * One reducer per Session. Holds the current TerminalSemanticState as a StateFlow.
 */
class SemanticStateReducer(
    private val sessionId: Long,
    private val shell: String,
    private val initialCwd: String,
    private val privilege: PrivilegeLevel,
    private val pid: Int,
    private val rows: Int,
    private val cols: Int,
    private val createdAt: Long = System.currentTimeMillis()
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<TerminalSemanticState> = _state.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, JobSnapshot>()  // jobId → snapshot
    private var foregroundJobId: Long? = null

    private fun initialState(): TerminalSemanticState = TerminalSemanticState(
        session = SessionSnapshot(
            id = sessionId, shell = shell, cwd = initialCwd, privilege = privilege,
            state = SessionState.CREATED, pid = pid, rows = rows, cols = cols,
            createdAt = createdAt, lastExitCode = null, cursor = 0L
        ),
        process = ProcessSnapshot(
            pid = pid, processName = null, foregroundProcess = true,
            running = false, exitCode = null, startTime = createdAt, finishTime = null
        ),
        screen = ScreenSnapshot(rows, cols, cursorRow = 0, cursorCol = 0,
            alternateScreen = false, title = null),
        input = InputSnapshot(InputState.NONE, InputControlState.FREE),
        foregroundJob = null,
        backgroundJobs = emptyList()
    )

    /** Apply an event incrementally. Called by the EventBus subscriber coroutine. */
    suspend fun onEvent(event: TerminalEvent) = mutex.withLock {
        val s = _state.value
        _state.value = when (event) {
            is TerminalEvent.SessionCreated -> s.copy(
                session = s.session.copy(state = SessionState.READY),
                process = s.process?.copy(running = true)
            )
            is TerminalEvent.ProcessStarted -> {
                val snap = JobSnapshot(
                    id = event.jobId, sessionId = sessionId, command = event.command,
                    owner = event.owner, background = event.background,
                    state = JobState.RUNNING, exitCode = null,
                    startedAt = event.timestamp, finishedAt = null
                )
                jobs[event.jobId] = snap
                if (!event.background) foregroundJobId = event.jobId
                s.copy(
                    session = s.session.copy(state = if (event.background) s.session.state else SessionState.RUNNING),
                    foregroundJob = if (event.background) s.foregroundJob else snap,
                    backgroundJobs = if (event.background) s.backgroundJobs + snap else s.backgroundJobs
                )
            }
            is TerminalEvent.OutputProduced -> s.copy(
                session = s.session.copy(cursor = event.endCursor),
                input = s.input.copy(state = InputState.NONE)
            )
            is TerminalEvent.InputWritten -> s.copy(
                input = s.input.copy(state = InputState.NONE)
            )
            is TerminalEvent.WaitingInput -> {
                val newState = if (event.confidence == Confidence.HIGH_CONFIDENCE) InputState.HIGH_CONFIDENCE
                else if (event.confidence == Confidence.POSSIBLE) InputState.POSSIBLE
                else if (event.confidence == Confidence.UNKNOWN) InputState.UNKNOWN
                else InputState.NONE
                s.copy(
                    session = if (event.confidence == Confidence.HIGH_CONFIDENCE)
                        s.session.copy(state = SessionState.WAITING_INPUT) else s.session,
                    input = s.input.copy(state = newState)
                )
            }
            is TerminalEvent.SignalSent -> {
                if (event.signal == UnixSignal.SIGINT && event.owner == InputOwner.USER) {
                    s.copy(session = s.session.copy(state = SessionState.INTERRUPTED))
                } else s
            }
            is TerminalEvent.UserInterrupt -> s.copy(
                session = s.session.copy(state = SessionState.INTERRUPTED)
            )
            is TerminalEvent.ProcessExited -> {
                if (event.jobId != null) {
                    val job = jobs[event.jobId]
                    if (job != null) {
                        val newState = when (event.cause) {
                            ExitCause.NORMAL -> JobState.EXITED
                            ExitCause.USER_INTERRUPT, ExitCause.SIGNAL -> JobState.INTERRUPTED
                            ExitCause.TIMEOUT -> JobState.TIMED_OUT
                            ExitCause.BROKEN -> JobState.FAILED
                        }
                        val updated = job.copy(state = newState, exitCode = event.exitCode, finishedAt = event.timestamp)
                        jobs[event.jobId] = updated
                        val fg = if (event.jobId == foregroundJobId) updated else s.foregroundJob
                        val bg = s.backgroundJobs.map { if (it.id == event.jobId) updated else it }
                        s.copy(
                            session = s.session.copy(
                                state = if (event.jobId == foregroundJobId) SessionState.READY else s.session.state,
                                lastExitCode = event.exitCode
                            ),
                            foregroundJob = fg,
                            backgroundJobs = bg
                        )
                    } else s
                } else {
                    // shell itself exited
                    s.copy(
                        session = s.session.copy(state = SessionState.EXITED, lastExitCode = event.exitCode),
                        process = s.process?.copy(running = false, exitCode = event.exitCode, finishTime = event.timestamp)
                    )
                }
            }
            is TerminalEvent.ResizeChanged -> s.copy(
                session = s.session.copy(rows = event.rows, cols = event.cols),
                screen = s.screen.copy(rows = event.rows, cols = event.cols)
            )
            is TerminalEvent.SessionClosed -> s.copy(
                session = s.session.copy(state = when (event.cause) {
                    CloseCause.USER, CloseCause.NORMAL -> SessionState.CLOSED
                    CloseCause.BROKEN -> SessionState.BROKEN
                })
            )
            is TerminalEvent.Error -> {
                if (event.code == "PtyUnavailable") {
                    s.copy(session = s.session.copy(state = SessionState.BROKEN))
                } else s
            }
            is TerminalEvent.StateChanged -> s  // audit only
        }
    }

    /** Update InputControlState (called by InputManager on takeover/release). */
    suspend fun updateInputControl(ic: InputControlState) = mutex.withLock {
        _state.value = _state.value.copy(input = _state.value.input.copy(control = ic))
    }

    /** Snapshot for observe(SEMANTIC). */
    fun snapshot(): TerminalSemanticState = _state.value
}
