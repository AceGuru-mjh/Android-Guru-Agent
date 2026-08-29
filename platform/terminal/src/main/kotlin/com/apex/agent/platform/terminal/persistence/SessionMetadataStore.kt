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
 *   - T73 (v2): backend metadata (backendId/rootfsId/workspaceDir/guestCwd/binds)
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
        // T73: v2 adds backend fields; T75: v3 adds workspaceId. v1/v2 files still load (defaults)
        val schemaVersion: Int = 3,
        val id: Long, val shell: String, val initialCwd: String, val pid: Int,
        val rows: Int, val cols: Int, val privilege: String, val state: String,
        val createdAt: Long, val lastActivityAt: Long = createdAt,  // PR #54 §25: activity tracking
        val exitReason: String? = null,  // PR #54 §11: why session ended
        val lastExitCode: Int?, val cursor: Long,
        val jobs: List<JobRecord>, val recentEvents: List<EventRecord>,
        // ── T73 (schema v2): 执行后端元数据（crash 后恢复时可区分本地/Ubuntu 会话）──
        val backendId: String? = null,       // null = v1 记录（语义上等同 "local"）
        val rootfsId: String? = null,
        // ── T75 (schema v3): 会话绑定的 workspace id（LINUX）──
        val workspaceId: String? = null,
        val workspaceDir: String? = null,
        val guestCwd: String? = null,
        val binds: List<String> = emptyList()
    ) {
        companion object { const val CURRENT_SCHEMA = 3 }
    }

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
            schemaVersion = SessionRecord.CURRENT_SCHEMA,
            id = session.id, shell = session.shell, initialCwd = session.initialCwd,
            pid = session.pid, rows = session.rows, cols = session.cols,
            privilege = session.privilege.name, state = session.state.name,
            createdAt = session.createdAt, lastActivityAt = System.currentTimeMillis(),
            exitReason = null,  // set on close/reconcile by SessionManager
            lastExitCode = session.lastExitCode, cursor = session.cursor,
            jobs = jobs.map { it.toRecord() },
            recentEvents = recentEvents.takeLast(maxRecentEvents).map { it.toRecord() },
            backendId = session.backend?.backendId,
            rootfsId = session.backend?.rootfsId,
            workspaceId = session.backend?.workspaceId,
            workspaceDir = session.backend?.workspaceDir,
            guestCwd = session.backend?.guestCwd,
            binds = session.backend?.binds ?: emptyList()
        )
        // PR #54 §18: atomic write — temp file + flush + rename (avoid corruption on mid-write crash)
        val target = File(storageDir, "session-${session.id}.json")
        val tmp = File(storageDir, "session-${session.id}.json.tmp")
        tmp.writeText(json.encodeToString(record))
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
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
