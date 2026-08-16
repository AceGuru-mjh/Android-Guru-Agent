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
 */
class TimeoutController(
    private val inputManager: InputManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val gracePeriodMs: Long = 5000L
) {
    private val timers = mutableMapOf<Long, Job>()  // jobId → timer coroutine

    /**
     * Start a timeout timer for a job. On expiry: SIGTERM → wait grace → SIGKILL.
     *
     * @param sessionId target session
     * @param jobId target job
     * @param timeoutMs total time before initiating shutdown
     * @param onTimeout callback when job is finally killed (state → TIMEOUT)
     */
    fun startTimeout(sessionId: Long, jobId: Long, timeoutMs: Long, onTimeout: () -> Unit) {
        cancelTimeout(jobId)  // replace any existing timer
        if (timeoutMs <= 0) return
        timers[jobId] = scope.launch {
            delay(timeoutMs)
            // Phase 1: graceful SIGTERM
            inputManager.sendSignal(sessionId, InputOwner.SYSTEM, UnixSignal.SIGTERM, jobId)
            // Phase 2: grace period
            delay(gracePeriodMs)
            // Phase 3: force SIGKILL
            inputManager.sendSignal(sessionId, InputOwner.SYSTEM, UnixSignal.SIGKILL, jobId)
            onTimeout()
        }
    }

    /** Cancel a timeout timer (e.g. job exited normally before timeout). */
    fun cancelTimeout(jobId: Long) {
        timers.remove(jobId)?.cancel()
    }

    /** Cancel all timers (on session close). */
    fun cancelAll() {
        timers.values.forEach { it.cancel() }
        timers.clear()
    }
}

/**
 * Job Cancellation (Spec PR #51 §5).
 *
 * Agent calls terminal.cancel(jobId) — internally does graceful stop → wait → force kill.
 * Agent doesn't need to manually signal(SIGTERM) then signal(SIGKILL).
 */
class JobCancellationController(
    private val inputManager: InputManager,
    private val timeoutController: TimeoutController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val gracePeriodMs: Long = 5000L
) {
    /**
     * Cancel a job: SIGTERM → grace → SIGKILL.
     *
     * @param sessionId target session
     * @param jobId target job
     * @return true if cancellation initiated
     */
    fun cancel(sessionId: Long, jobId: Long): Boolean {
        scope.launch {
            // 1. Graceful stop
            inputManager.sendSignal(sessionId, InputOwner.SYSTEM, UnixSignal.SIGTERM, jobId)
            // 2. Grace period
            delay(gracePeriodMs)
            // 3. Force kill
            inputManager.sendSignal(sessionId, InputOwner.SYSTEM, UnixSignal.SIGKILL, jobId)
        }
        return true
    }
}
