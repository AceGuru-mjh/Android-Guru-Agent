package com.apex.agent.platform.terminal.compat

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.wait.WaitCondition

/**
 * Compatibility facade that preserves the OLD TerminalManager API surface but delegates
 * internally to the new [TerminalRuntime].
 *
 * Spec ref: ATR 2.0 Final Spec §35 (compatibility layer) / §4.1 (settle-time MUST be deleted)
 *
 * CRITICAL CHANGE: the old `execute()` used `SETTLE_TIME_MS=300 / MAX_SETTLE_WAIT_MS=2000`
 * (output-silence → command complete). This is DELETED. The compat `execute()` now uses:
 *
 *     runtime.run(command, owner=AGENT)         → Job handle (startCursor)
 *     runtime.wait(PROCESS_EXITED, jobId, 120s) → Matched(ProcessExited) | Timeout
 *     runtime.observe(RAW, afterCursor=startCursor, maxBytes=...) → output
 *
 * So the old synchronous contract (return output + exitCode) is preserved, but the completion
 * detection is reliable (waitpid) rather than settle-time.
 *
 * Migration timeline (Spec §45):
 *   Phase 0-1: TerminalManager.kt unchanged (old behavior).
 *   Phase 2:   LegacyTerminalManager.kt created, delegates to Runtime (internal test only).
 *   Phase 3:   Old TerminalManager.kt replaced by this compat facade; old 6 tools delegate here.
 *   Phase 5:   Marked @Deprecated (kept for 1 version, then removed).
 */
@Deprecated("ATR 2.0 compat facade — migrate to TerminalRuntime directly. Scheduled for removal in a future version.", ReplaceWith("runtime", "com.apex.agent.platform.terminal.runtime.TerminalRuntime"))
class LegacyTerminalManager(
    private val runtime: TerminalRuntime
) {
    // ─── OLD API SURFACE (preserved signatures) ───

    /** Old: createSession() → sessionId. Now: runtime.create(). */
    suspend fun createSession(
        shell: String = "/system/bin/sh",
        cwd: String = "/sdcard",
        rows: Int = 24,
        cols: Int = 80
    ): Result<Long> = runtime.create(shell, cwd, rows, cols).map { it.sessionId }

    /**
     * Old: execute(sessionId, command, timeoutMs) → CommandResult(output, exitCode).
     *
     * NEW IMPLEMENTATION (no settle-time):
     *   1. runtime.run(sessionId, command, owner=AGENT) → startCursor + jobId
     *   2. runtime.wait(sessionId, PROCESS_EXITED(jobId), timeoutMs) → Matched | Timeout
     *   3. runtime.observe(sessionId, RAW, afterCursor=startCursor, maxBytes=65536) → output
     *   4. Return CommandResult(output, exitCode)
     */
    suspend fun execute(
        sessionId: Long,
        command: String,
        timeoutMs: Long = 120_000L
    ): Result<CommandResult> {
        val startMs = System.currentTimeMillis()
        // 1. run (non-blocking)
        val runResult = runtime.run(
            sessionId = sessionId,
            command = command,
            owner = InputOwner.AGENT,
            background = false,
            timeoutMs = timeoutMs
        )
        val run = runResult.getOrElse { return Result.failure(it) }

        // 2. wait for PROCESS_EXITED (reliable, waitpid-confirmed — NO settle-time)
        val waitResult = runtime.wait(
            sessionId = sessionId,
            condition = WaitCondition.ProcessExited(jobId = run.jobId),
            timeoutMs = timeoutMs
        )
        val wait = waitResult.getOrElse { return Result.failure(it) }
        val exitCode: Int = when (wait) {
            is WaitResult.Matched -> {
                val ev = wait.event
                if (ev is com.apex.agent.platform.terminal.events.TerminalEvent.ProcessExited)
                    ev.exitCode ?: -1
                else 0
            }
            is WaitResult.Timeout -> {
                // kill on timeout
                runtime.signal(sessionId, UnixSignal.SIGKILL, InputOwner.AGENT, run.jobId)
                return Result.success(
                    CommandResult(
                        output = "", exitCode = -1, truncated = false,
                        durationMs = wait.waitedMs
                    )
                )
            }
            is WaitResult.SessionGone -> {
                return Result.success(
                    CommandResult(output = "", exitCode = -1, truncated = false, durationMs = 0)
                )
            }
        }

        // 3. observe RAW output since the job's startCursor
        val obsResult = runtime.observe(
            sessionId = sessionId,
            mode = TerminalRuntime.ObserveMode.RAW,
            afterCursor = run.startCursor,
            maxBytes = 65536
        )
        val obs = obsResult.getOrElse { return Result.failure(it) }

        return Result.success(
            CommandResult(
                output = obs.raw ?: "",
                exitCode = exitCode,
                truncated = obs.truncated,
                durationMs = System.currentTimeMillis() - startMs
            )
        )
    }

    /** Old: sendRaw(sessionId, text). Now: runtime.write(RAW). */
    suspend fun sendRaw(sessionId: Long, text: String): Result<Unit> =
        runtime.write(sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.RAW, text = text).map { }

    /** Old: sendLine(sessionId, text). Now: runtime.write(LINE). */
    suspend fun sendLine(sessionId: Long, text: String): Result<Unit> =
        runtime.write(sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.LINE, text = text).map { }

    /** Old: sendKey(sessionId, key). Now: runtime.write(KEY). */
    suspend fun sendKey(sessionId: Long, key: TerminalKey): Result<Unit> =
        runtime.write(sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.KEY, key = key).map { }

    /** Old: sendSignal(sessionId, signal). Now: runtime.signal(). */
    suspend fun sendSignal(sessionId: Long, signal: UnixSignal): Result<Unit> =
        runtime.signal(sessionId, signal, InputOwner.AGENT).map { }

    /** Old: readOutput(sessionId, maxBytes). Now: runtime.observe(RAW). */
    suspend fun readOutput(sessionId: Long, maxBytes: Int = 65536): Result<String> =
        runtime.observe(sessionId, TerminalRuntime.ObserveMode.RAW, maxBytes = maxBytes).map { it.raw ?: "" }

    /** Old: listSessions(). Now: runtime.snapshot(SESSIONS). */
    suspend fun listSessions(): Result<List<Long>> =
        runtime.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).map { result ->
            result.sessions.map { it.session.id }
        }

    /** Old: closeSession(sessionId). Now: runtime.close(). */
    suspend fun closeSession(sessionId: Long): Result<Unit> =
        runtime.close(sessionId).map { }

    /** Old: resize(sessionId, rows, cols). Now: runtime.resize(). */
    suspend fun resize(sessionId: Long, rows: Int, cols: Int): Result<Unit> =
        runtime.resize(sessionId, rows, cols).map { }

    // ─── OLD RESULT TYPES (preserved) ───

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val truncated: Boolean,
        val durationMs: Long
    )
}
