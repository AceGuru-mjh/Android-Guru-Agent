package com.apex.agent.platform.terminal.process

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.io.InputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Timeout Lifecycle (Spec PR #51 §4).
 *
 * Instead of timer-expired → immediate SIGKILL, gives graceful shutdown:
 *
 *   RUNNING
 *      │ timeoutMs elapsed
 *      ▼
 *   STOPPING  ── SIGTERM ──▶ grace period (default 5s)
 *      │
 *      │ grace expired, still alive
 *      ▼
 *   SIGKILL ──▶ TIMEOUT
 *
 * T81 (D-2)：本控制器是 job 超时的统一入口（startTimeout 此前生产零调用，
 * JobManager 私自用裸 SIGKILL 杀全组 —— 已改回统一走这里）。
 * timers 为并发安全结构；新增 [cancelSession]（按 session 收敛，修复
 * close 任意 session 时 cancelAll 全局误杀其他 session 定时器的缺陷）。
 */
class TimeoutController(
    private val inputManager: InputManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val gracePeriodMs: Long = 5000L
) {
    private data class TimerEntry(val sessionId: Long, val job: Job)

    // T81：普通 mutableMapOf 无锁 → ConcurrentHashMap（timers 曾被多线程读写）。
    private val timers = java.util.concurrent.ConcurrentHashMap<Long, TimerEntry>()  // jobId → timer

    /**
     * Start a timeout timer for a job. On expiry: SIGTERM → wait grace → SIGKILL.
     * timer 协程被取消时（job 提前退出/正常完成）不再发任何信号。
     *
     * @param sessionId target session
     * @param jobId target job
     * @param timeoutMs total time before initiating shutdown
     * @param onTimeout callback when job is finally killed (state → TIMEOUT)
     */
    fun startTimeout(sessionId: Long, jobId: Long, timeoutMs: Long, onTimeout: () -> Unit) {
        cancelTimeout(jobId)  // replace any existing timer
        if (timeoutMs <= 0) return
        timers[jobId] = TimerEntry(sessionId, scope.launch {
            try {
                delay(timeoutMs)
                // Phase 1: graceful SIGTERM（发给进程组 —— native kill(-PGID)）
                inputManager.sendSignal(sessionId, InputOwner.SYSTEM, UnixSignal.SIGTERM, jobId)
                // Phase 2: grace period（timer 在此期间被取消 = job 已自行退出/被外部终止）
                delay(gracePeriodMs)
                // Phase 3: force SIGKILL
                inputManager.sendSignal(sessionId, InputOwner.SYSTEM, UnixSignal.SIGKILL, jobId)
                onTimeout()
            } finally {
                timers.remove(jobId)
            }
        })
    }

    /** Cancel a timeout timer (e.g. job exited normally before timeout). */
    fun cancelTimeout(jobId: Long) {
        timers.remove(jobId)?.job?.cancel()
    }

    /**
     * T81：取消一个 session 的全部定时器（session close / shutdown 路径）。
     * 修复：原 [cancelAll] 无 session 过滤，TerminalRuntimeImpl.close 关任意
     * session 都会误杀所有其他 session 的超时定时器。
     */
    fun cancelSession(sessionId: Long) {
        val iter = timers.entries.iterator()
        for (e in iter) {
            if (e.value.sessionId == sessionId) {
                e.value.job.cancel()
                iter.remove()
            }
        }
    }

    /** Cancel all timers (runtime shutdown only). */
    fun cancelAll() {
        timers.values.forEach { it.job.cancel() }
        timers.clear()
    }

    /** T81：活跃定时器数（诊断/测试）。 */
    fun activeTimers(): Int = timers.size
}

/**
 * Job Cancellation (Spec PR #51 §5).
 *
 * Agent calls terminal.cancel(jobId) — internally does graceful stop → wait → force kill.
 * Agent doesn't need to manually signal(SIGTERM) then signal(SIGKILL).
 *
 * T81 (D-2)：取消序列如实上报 —— 取消同时撤销该 job 的超时定时器（互不
 * 叠加发信号）。
 */
class JobCancellationController(
    private val inputManager: InputManager,
    private val timeoutController: TimeoutController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val gracePeriodMs: Long = 5000L,
    /** Called right after SIGTERM is delivered, so the job can be marked terminal. */
    private val onCancelled: (sessionId: Long, jobId: Long) -> Unit = { _, _ -> },
) {
    /**
     * Cancel a job: SIGTERM → grace → SIGKILL.
     *
     * @param sessionId target session
     * @param jobId target job
     * @return true if cancellation initiated
     */
    fun cancel(sessionId: Long, jobId: Long): Boolean {
        // T81 (D-2)：先撤销超时定时器 —— 避免 timeout 与 cancel 对同一 job
        // 叠加发信号（SIGTERM×2 + SIGKILL×2 竞态）。
        timeoutController.cancelTimeout(jobId)
        scope.launch {
            // 1. Graceful stop（发给进程组 —— native kill(-PGID)）
            inputManager.sendSignal(sessionId, InputOwner.SYSTEM, UnixSignal.SIGTERM, jobId)
            // Mark the job as cancelled immediately (agent decided to stop it).
            onCancelled(sessionId, jobId)
            // 2. Grace period（期间进程自行退出 = 正常路径）
            delay(gracePeriodMs)
            // 3. Force kill（兑底 —— 进程已死则无副作用）
            inputManager.sendSignal(sessionId, InputOwner.SYSTEM, UnixSignal.SIGKILL, jobId)
        }
        return true
    }
}
