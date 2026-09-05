package com.apex.agent.platform.terminal.persistence

import com.apex.agent.platform.terminal.events.CloseCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBus
import com.apex.agent.platform.terminal.events.TerminalEventLog
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.job.JobState
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.session.SessionState
import com.apex.agent.platform.terminal.state.SemanticStateReducer
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Crash recovery service. On Runtime startup, loads persisted Session metadata and reconstructs
 * a best-effort view. PTY fds are process-local and CANNOT be reattached in v1, so a dead
 * session's state becomes BROKEN (never faked as alive — Spec §39).
 *
 * Spec ref: ATR 2.0 Final Spec §39 (Persistence / Recovery)
 *
 * Recovery flow (call [recover] once on app/Runtime startup):
 *   1. Load all SessionRecords from SessionMetadataStore.
 *   2. For each record:
 *      - If the pid is no longer alive (kill(pid,0) fails) → mark EXITED (process dead).
 *        The SemanticState is reconstructed from metadata; cursor = last persisted.
 *      - If the pid IS alive but we lost the fd → mark BROKEN (cannot reattach in v1).
 *        User must close + recreate.
 *      - Reconstruct the SemanticStateReducer from the record (so terminal.snapshot() returns
 *        the recovered session even though the PTY is gone).
 *   3. terminal.snapshot() now returns recovered sessions for Agent context rebuilding.
 *
 * Periodic save: [startAutoSave] launches a coroutine that saves all live sessions every
 * [intervalMs] (default 2s). [stopAutoSave] cancels it.
 *
 * The Runtime does NOT auto-recover on construction (keeps Runtime pure). The app wires this
 * service in Hilt and calls recover() after Runtime is created.
 */
class RuntimeRecoveryService(
    private val store: SessionMetadataStore,
    private val runtime: TerminalRuntime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val isPidAlive: (Int) -> Boolean = { pid ->
        // T81 (D-5)：/proc/<pid> 存在性检查 —— 原实现 fork `kill -0` 子进程：
        // Android 上 toybox kill 可用但每 session 一次 fork 开销大，且在 fork
        // 被禁的 seccomp 环境直接抛异常。/proc 读在 Android/Linux 均可用、零 fork。
        if (pid <= 0) false else File("/proc/$pid").exists()
    }
) {
    private var autoSaveJob: kotlinx.coroutines.Job? = null

    /**
     * Load persisted sessions and reconstruct their SemanticState view.
     * Returns the list of recovered session ids (now visible via terminal.snapshot).
     *
     * T81 (D-5)：恢复语义的真实现 ——
     *  - 持久化状态为 RUNNING/WAITING_INPUT/CREATED 的 job 在恢复视图中收敛为
     *    INTERRUPTED（进程已死，不可能还在运行 —— 不伪造 RUNNING，Spec §39）；
     *  - 恢复的记录保留在 store（recoveredSnapshot 可读），进程死亡事实仅影响
     *    视图状态，不篡改原始记录；
     *  - CLOSED 记录在恢复后删除（终态，无需保留）。
     *
     * NOTE: this does NOT re-open PTYs. Recovered sessions have state EXITED or BROKEN.
     */
    suspend fun recover(): List<Long> {
        val records = store.loadAll()
        val recovered = mutableListOf<Long>()
        for (rec in records) {
            if (rec.state == SessionState.CLOSED.name) {
                // 终态记录：恢复视图无需保留
                store.delete(rec.id)
                continue
            }
            val alive = isPidAlive(rec.pid)
            // alive 但 fd 已丢 → BROKEN；!alive → EXITED。不伪造 RUNNING。
            recovered.add(rec.id)
        }
        return recovered
    }

    /** 恢复视图中的 job 状态映射：活跃态 → INTERRUPTED（crash 中断）。 */
    private fun recoveredJobState(raw: String): JobState {
        val parsed = runCatching { JobState.valueOf(raw) }.getOrDefault(JobState.UNKNOWN)
        return if (parsed == JobState.RUNNING || parsed == JobState.WAITING_INPUT || parsed == JobState.CREATED) {
            JobState.INTERRUPTED
        } else parsed
    }

    /**
     * Get a recovered session's last-known SemanticState (read-only, from persisted metadata).
     * Returns null if no record exists.
     * T81 (D-5)：job 活跃态收敛为 INTERRUPTED（不伪造 RUNNING）。
     */
    suspend fun recoveredSnapshot(sessionId: Long): TerminalSemanticState? {
        val rec = store.load(sessionId) ?: return null
        val alive = isPidAlive(rec.pid)
        val state = when {
            rec.state == SessionState.CLOSED.name -> SessionState.CLOSED
            !alive -> SessionState.EXITED
            else -> SessionState.BROKEN
        }
        // Build a minimal SemanticState from the record (no live process/screen).
        return TerminalSemanticState(
            session = com.apex.agent.platform.terminal.state.SessionSnapshot(
                id = rec.id, shell = rec.shell, cwd = rec.initialCwd,
                privilege = runCatching { PrivilegeLevel.valueOf(rec.privilege) }.getOrDefault(PrivilegeLevel.NORMAL),
                state = state, pid = rec.pid, rows = rec.rows, cols = rec.cols,
                createdAt = rec.createdAt, lastExitCode = rec.lastExitCode, cursor = rec.cursor
            ),
            process = null,   // process is dead/unreachable
            screen = com.apex.agent.platform.terminal.state.ScreenSnapshot(
                rows = rec.rows, cols = rec.cols, cursorRow = 0, cursorCol = 0,
                alternateScreen = false, title = null
            ),
            input = com.apex.agent.platform.terminal.state.InputSnapshot(
                com.apex.agent.platform.terminal.state.InputState.UNKNOWN,
                com.apex.agent.platform.terminal.io.InputControlState.FREE
            ),
            foregroundJob = rec.jobs.lastOrNull { !it.background }?.let {
                com.apex.agent.platform.terminal.state.JobSnapshot(
                    id = it.id, sessionId = it.sessionId, command = it.command,
                    owner = runCatching { com.apex.agent.platform.terminal.io.InputOwner.valueOf(it.owner) }
                        .getOrDefault(InputOwner.SYSTEM),
                    background = it.background, state = recoveredJobState(it.state),
                    exitCode = it.exitCode, startedAt = it.startedAt, finishedAt = it.finishedAt
                )
            },
            backgroundJobs = rec.jobs.filter { it.background }.map {
                com.apex.agent.platform.terminal.state.JobSnapshot(
                    id = it.id, sessionId = it.sessionId, command = it.command,
                    owner = runCatching { com.apex.agent.platform.terminal.io.InputOwner.valueOf(it.owner) }
                        .getOrDefault(InputOwner.SYSTEM),
                    background = true, state = recoveredJobState(it.state),
                    exitCode = it.exitCode, startedAt = it.startedAt, finishedAt = it.finishedAt
                )
            }
        )
    }

    /**
     * Start periodic auto-save of all live sessions.
     * T81 (D-5)：单次保存异常不再杀死循环（原实现一次 IO 失败 = 静默停摆持久化）。
     * T81：liveSessionsProvider 直接返回 TerminalSession（含 backend 字段 ——
     * 原从 SemanticState 重建会丢失 backend，crash 后 LINUX 会话降级成无后端）。
     */
    fun startAutoSave(
        intervalMs: Long = 2000L,
        liveSessionsProvider: suspend () -> List<com.apex.agent.platform.terminal.session.TerminalSession>,
        liveJobsProvider: suspend (Long) -> List<com.apex.agent.platform.terminal.job.TerminalJob>,
        recentEventsProvider: suspend (Long) -> List<TerminalEvent>
    ) {
        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(intervalMs)
                for (session in liveSessionsProvider()) {
                    try {
                        val jobs = liveJobsProvider(session.id)
                        val events = recentEventsProvider(session.id)
                        store.save(session = session, jobs = jobs, recentEvents = events)
                    } catch (e: Exception) {
                        // 单 session 保存失败不杀循环（下一周期重试）。
                    }
                }
            }
        }
    }

    /** Stop auto-save. */
    fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }
}
