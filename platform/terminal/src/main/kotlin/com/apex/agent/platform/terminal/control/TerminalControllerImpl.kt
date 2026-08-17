package com.apex.agent.platform.terminal.control

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.policy.CommandParser
import com.apex.agent.platform.terminal.policy.CommandPolicy
import com.apex.agent.platform.terminal.policy.CommandPolicyDecision
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.session.TerminalSessionConfig
import com.apex.agent.platform.terminal.session.TerminalSessionSnapshot
import com.apex.agent.platform.terminal.wait.WaitCondition
import com.apex.agent.platform.terminal.wait.WaitResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * TerminalController implementation — delegates to TerminalRuntime, adds:
 *   - Policy gate (§25): all execute() calls pass through CommandPolicy before PTY
 *   - Unified error model (§14): returns Result<T>, errors map to TerminalControlError
 *   - Event stream (§23): bounded SharedFlow for Agent subscription
 *   - No raw resource exposure (§21): Agent gets sessionId/jobId, never FD/Process
 *
 * Spec ref: PR #55 §1-§29.
 */
class TerminalControllerImpl(
    private val runtime: TerminalRuntime,
    private val commandPolicy: CommandPolicy = CommandPolicy()
) : TerminalController {

    private val eventBus = MutableSharedFlow<TerminalControlEvent>(extraBufferCapacity = 256)
    private val events = eventBus.asSharedFlow()

    // ─── Session Control ───
    override suspend fun createSession(config: TerminalSessionConfig): Result<Long> {
        val r = runtime.create(
            shell = config.shell ?: "/system/bin/sh",
            cwd = config.workingDirectory ?: "/sdcard",
            rows = config.rows, cols = config.cols, env = config.environment
        )
        return r.map { it.sessionId }
    }

    override suspend fun getSession(sessionId: Long): TerminalSessionSnapshot? {
        val snap = runtime.snapshot(TerminalRuntime.SnapshotMode.FULL, sessionId).getOrNull() ?: return null
        val s = snap.sessions.firstOrNull { it.session.id == sessionId } ?: return null
        return TerminalSessionSnapshot(
            sessionId = s.session.id, state = s.session.state,
            exitReason = null,  // set on close
            createdAt = s.session.createdAt, startedAt = s.session.createdAt, finishedAt = null,
            primaryProcessId = s.session.pid?.toLong(), shell = s.session.shell,
            workingDirectory = s.session.cwd
        )
    }

    override suspend fun listSessions(): List<TerminalSessionSnapshot> {
        val snap = runtime.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrNull() ?: return emptyList()
        return snap.sessions.map { s ->
            TerminalSessionSnapshot(
                sessionId = s.session.id, state = s.session.state, exitReason = null,
                createdAt = s.session.createdAt, startedAt = s.session.createdAt, finishedAt = null,
                primaryProcessId = s.session.pid?.toLong(), shell = s.session.shell,
                workingDirectory = s.session.cwd
            )
        }
    }

    override suspend fun closeSession(sessionId: Long): Result<Unit> = runtime.close(sessionId).map { }
    override suspend fun closeAllSessions(): Result<Unit> {
        val sessions = listSessions()
        for (s in sessions) runtime.close(s.sessionId)
        return Result.success(Unit)
    }

    // ─── Command / Job Control (with Policy Gate §25) ───
    override suspend fun execute(sessionId: Long, command: String, timeoutMs: Long): Result<Long> {
        // §25: Policy gate — all Agent commands checked before PTY write
        val parsed = CommandParser.parse(command)
        val decision = commandPolicy.check(parsed)
        if (decision != CommandPolicyDecision.ALLOW) {
            eventBus.tryEmit(TerminalControlEvent.Error(sessionId, "PermissionDenied", "Command blocked by policy"))
            return Result.failure(RuntimeException("TerminalControlError:PermissionDenied"))
        }
        val r = runtime.run(sessionId, command, InputOwner.AGENT, background = false, timeoutMs = timeoutMs)
        return r.map { it.jobId }
    }

    override suspend fun getJob(sessionId: Long, jobId: Long): TerminalController.JobInfo? {
        val snap = runtime.snapshot(TerminalRuntime.SnapshotMode.FULL, sessionId).getOrNull() ?: return null
        val s = snap.sessions.firstOrNull { it.session.id == sessionId } ?: return null
        val fg = s.foregroundJob
        if (fg?.id == jobId) return TerminalController.JobInfo(jobId, sessionId, fg.command, fg.state.name, fg.exitCode, fg.startedAt, fg.finishedAt)
        return null
    }

    override suspend fun listJobs(sessionId: Long): List<TerminalController.JobInfo> {
        val snap = runtime.snapshot(TerminalRuntime.SnapshotMode.FULL, sessionId).getOrNull() ?: return emptyList()
        val s = snap.sessions.firstOrNull { it.session.id == sessionId } ?: return emptyList()
        val jobs = mutableListOf<TerminalController.JobInfo>()
        s.foregroundJob?.let { jobs.add(TerminalController.JobInfo(it.id, sessionId, it.command, it.state.name, it.exitCode, it.startedAt, it.finishedAt)) }
        s.backgroundJobs.forEach { jobs.add(TerminalController.JobInfo(it.id, sessionId, it.command, it.state.name, it.exitCode, it.startedAt, it.finishedAt)) }
        return jobs
    }

    override suspend fun wait(sessionId: Long, jobId: Long, timeoutMs: Long): Result<TerminalController.JobResult> {
        val waitResult = runtime.wait(sessionId, WaitCondition.ProcessExited(jobId), timeoutMs)
        val w = waitResult.getOrElse { return Result.failure(it) }
        return when (w) {
            is WaitResult.Matched -> {
                val ev = w.event
                val exitCode = if (ev is com.apex.agent.platform.terminal.events.TerminalEvent.ProcessExited) ev.exitCode else null
                Result.success(TerminalController.JobResult(jobId, exitCode, "EXITED", 0, 0, System.currentTimeMillis()))
            }
            is WaitResult.Timeout -> Result.failure(RuntimeException("TerminalControlError:Timeout"))
            is WaitResult.SessionGone -> Result.failure(RuntimeException("TerminalControlError:SessionNotFound"))
        }
    }

    override suspend fun cancel(sessionId: Long, jobId: Long): Result<Unit> {
        // §17: cancel = graceful (SIGTERM → grace → SIGKILL via Runtime's cancellation controller)
        return runtime.cancel(sessionId, jobId).map { }
    }

    // ─── Input Control ───
    override suspend fun write(sessionId: Long, data: ByteArray): Result<Unit> =
        runtime.write(sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.RAW, text = String(data, Charsets.UTF_8)).map { }

    override suspend fun sendKey(sessionId: Long, key: TerminalKey): Result<Unit> =
        runtime.write(sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.KEY, key = key).map { }

    override suspend fun sendSignal(sessionId: Long, signal: UnixSignal): Result<Unit> =
        runtime.signal(sessionId, signal, InputOwner.AGENT).map { }

    override suspend fun closeStdin(sessionId: Long): Result<Unit> =
        runtime.write(sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.KEY, key = TerminalKey.CTRL_D).map { }

    // ─── Resize ───
    override suspend fun resize(sessionId: Long, rows: Int, cols: Int): Result<Unit> =
        runtime.resize(sessionId, rows, cols).map { }

    // ─── Observation ───
    override suspend fun observe(sessionId: Long, mode: TerminalController.ObserveMode, afterCursor: Long): Result<TerminalController.Observation> {
        val rtMode = when (mode) {
            TerminalController.ObserveMode.SEMANTIC -> TerminalRuntime.ObserveMode.SEMANTIC
            TerminalController.ObserveMode.SCREEN -> TerminalRuntime.ObserveMode.SCREEN
            TerminalController.ObserveMode.RAW -> TerminalRuntime.ObserveMode.RAW
            TerminalController.ObserveMode.EVENT -> TerminalRuntime.ObserveMode.EVENT
        }
        val r = runtime.observe(sessionId, rtMode, afterCursor, 12000, 200)
        return r.map { obs ->
            TerminalController.Observation(
                mode = mode, sessionId = sessionId, cursor = obs.cursor,
                startCursor = obs.startCursor, endCursor = obs.endCursor,
                truncated = obs.truncated, overrun = obs.overrun,
                availableFrom = obs.oldestCursor,
                screenText = obs.screen?.renderedText, rawOutput = obs.raw
            )
        }
    }

    override suspend fun getScreenText(sessionId: Long): Result<String> =
        observe(sessionId, TerminalController.ObserveMode.SCREEN).map { it.screenText ?: "" }

    override fun observeEvents(sessionId: Long): kotlinx.coroutines.flow.Flow<TerminalControlEvent> = events
}
