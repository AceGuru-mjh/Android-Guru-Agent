package com.apex.agent.platform.terminal.persistence

import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.job.TerminalJob
import com.apex.agent.platform.terminal.session.TerminalSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists Session/Job metadata + recent events to disk for crash recovery.
 *
 * Spec ref: ATR 2.0 Final Spec §39 (Persistence / Recovery)
 *
 * What's persisted:
 *   - Session metadata (id, shell, cwd, pid, rows, cols, privilege, state, cursor, createdAt, lastExitCode)
 *   - Job metadata (id, sessionId, command, owner, background, startCursor, endCursor, state, exitCode, startedAt, finishedAt)
 *   - Recent events (last N per session — default 100)
 *
 * What's NOT persisted (v1):
 *   - RingBuffer output bytes (in-memory only)
 *   - Full EventLog (only recent N)
 *   - PTY master fd (impossible; fd is process-local)
 *
 * On recovery: dead PTY → state=BROKEN/EXITED; never faked alive (Spec §39).
 *
 * Storage: one JSON file per session at `<dir>/session-<id>.json`.
 */
class SessionMetadataStore(
    private val storageDir: File,
    private val maxRecentEvents: Int = 100
) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val mutex = Mutex()

    init { storageDir.mkdirs() }

    @Serializable
    data class SessionRecord(
        val id: Long, val shell: String, val initialCwd: String, val pid: Int,
        val rows: Int, val cols: Int, val privilege: String, val state: String,
        val createdAt: Long, val lastExitCode: Int?, val cursor: Long,
        val jobs: List<JobRecord>, val recentEvents: List<EventRecord>
    )

    @Serializable
    data class JobRecord(
        val id: Long, val sessionId: Long, val command: String, val owner: String,
        val background: Boolean, val startCursor: Long, val endCursor: Long?,
        val state: String, val exitCode: Int?, val signal: String?,
        val startedAt: Long, val finishedAt: Long?
    )

    @Serializable
    data class EventRecord(
        val id: Long, val type: String, val timestamp: Long, val cursor: Long, val summary: String
    )

    suspend fun save(session: TerminalSession, jobs: List<TerminalJob>, recentEvents: List<TerminalEvent>) = mutex.withLock {
        val record = SessionRecord(
            id = session.id, shell = session.shell, initialCwd = session.initialCwd,
            pid = session.pid, rows = session.rows, cols = session.cols,
            privilege = session.privilege.name, state = session.state.name,
            createdAt = session.createdAt, lastExitCode = session.lastExitCode, cursor = session.cursor,
            jobs = jobs.map { it.toRecord() },
            recentEvents = recentEvents.takeLast(maxRecentEvents).map { it.toRecord() }
        )
        File(storageDir, "session-${session.id}.json").writeText(json.encodeToString(record))
    }

    suspend fun loadAll(): List<SessionRecord> = mutex.withLock {
        storageDir.listFiles { f -> f.name.startsWith("session-") && f.name.endsWith(".json") }
            ?.map { json.decodeFromString<SessionRecord>(it.readText()) } ?: emptyList()
    }

    suspend fun load(sessionId: Long): SessionRecord? = mutex.withLock {
        val f = File(storageDir, "session-$sessionId.json")
        if (f.exists()) json.decodeFromString<SessionRecord>(f.readText()) else null
    }

    suspend fun delete(sessionId: Long) = mutex.withLock { File(storageDir, "session-$sessionId.json").delete() }
    suspend fun clear() = mutex.withLock { storageDir.listFiles()?.forEach { it.delete() } }

    private fun TerminalJob.toRecord() = JobRecord(
        id, sessionId, command, owner.name, background, startCursor, endCursor,
        state.name, exitCode, signal?.name, startedAt, finishedAt
    )

    private fun TerminalEvent.toRecord(): EventRecord {
        val type = when (this) {
            is TerminalEvent.SessionCreated -> "SessionCreated"
            is TerminalEvent.ProcessStarted -> "ProcessStarted"
            is TerminalEvent.InputWritten -> "InputWritten"
            is TerminalEvent.OutputProduced -> "OutputProduced"
            is TerminalEvent.ResizeChanged -> "ResizeChanged"
            is TerminalEvent.ProcessExited -> "ProcessExited"
            is TerminalEvent.SignalSent -> "SignalSent"
            is TerminalEvent.UserInterrupt -> "UserInterrupt"
            is TerminalEvent.WaitingInput -> "WaitingInput"
            is TerminalEvent.SessionClosed -> "SessionClosed"
            is TerminalEvent.Error -> "Error"
            is TerminalEvent.StateChanged -> "StateChanged"
        }
        val summary = when (this) {
            is TerminalEvent.SessionCreated -> "shell=$shell pid=$pid"
            is TerminalEvent.ProcessStarted -> "job=$jobId cmd=$command"
            is TerminalEvent.InputWritten -> "owner=$owner kind=$kind bytes=$byteCount"
            is TerminalEvent.OutputProduced -> "bytes=$startCursor..$endCursor"
            is TerminalEvent.ResizeChanged -> "${rows}x${cols}"
            is TerminalEvent.ProcessExited -> "job=$jobId exit=$exitCode cause=$cause"
            is TerminalEvent.SignalSent -> "$signal by $owner"
            is TerminalEvent.UserInterrupt -> "job=$jobId"
            is TerminalEvent.WaitingInput -> "job=$jobId conf=$confidence"
            is TerminalEvent.SessionClosed -> "cause=$cause"
            is TerminalEvent.Error -> "$code: $message"
            is TerminalEvent.StateChanged -> "$kind $targetId $from→$to"
        }
        return EventRecord(id, type, timestamp, cursor, summary)
    }
}
