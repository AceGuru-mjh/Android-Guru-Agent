package com.apex.agent.platform.terminal.process2

import kotlinx.coroutines.flow.Flow

/**
 * PR #58 §20: JobManager 2.0 — unified process control API.
 *
 * Job ≠ Process. JobManager manages Agent execution units (Jobs).
 * Process/ProcessGroup management is delegated to ExecutionBackend.
 */
interface JobManager2 {

    // ─── §20: Job lifecycle ───
    suspend fun create(sessionId: Long, command: String, mode: JobMode = JobMode.FOREGROUND): Result<Long>
    suspend fun get(jobId: Long): JobRegistryEntry?
    suspend fun list(sessionId: Long): List<JobRegistryEntry>
    suspend fun listAll(): List<JobRegistryEntry>

    // ─── §20/§7/§8: Cancellation (idempotent) ───
    suspend fun cancel(jobId: Long, policy: CancellationPolicy = CancellationPolicy.DEFAULT): Result<Unit>
    suspend fun kill(jobId: Long): Result<Unit>  // force SIGKILL immediately

    // ─── §3: Process Tree ───
    suspend fun getProcessTree(jobId: Long): ProcessTree?

    // ─── §6: Exit Info ───
    suspend fun getExitInfo(jobId: Long): ExitInfo?

    // ─── §27: Job Result (with ObservationRange) ───
    suspend fun getJobResult(jobId: Long): JobResult?

    // ─── §9: Timeout ───
    suspend fun setTimeout(jobId: Long, timeoutMs: Long, policy: CancellationPolicy = CancellationPolicy.DEFAULT): Result<Unit>

    // ─── §31: Reconciliation ───
    suspend fun reconcile(jobs: List<Long>): List<JobReconciliationResult>

    // ─── §39: Job Events (bounded Flow) ───
    fun jobEvents(sessionId: Long): Flow<JobEvent>?

    // ─── §47: Capabilities ───
    fun capabilities(): ProcessCapabilities

    data class JobReconciliationResult(
        val jobId: Long,
        val persistedState: String,
        val actualState: String,  // LOST if PID reuse detected
        val recoverable: Boolean
    )
}

/**
 * §21/§29: Bounded Job Registry. Prevents unbounded memory growth.
 */
class BoundedJobRegistry(
    private val maxCompletedJobs: Int = 500
) {
    private val active = mutableMapOf<Long, JobRegistryEntry>()
    private val completed = ArrayDeque<JobRegistryEntry>()

    fun add(entry: JobRegistryEntry) {
        if (entry.isTerminal) {
            completed.addLast(entry)
            while (completed.size > maxCompletedJobs) completed.pollFirst()
            active.remove(entry.jobId)
        } else {
            active[entry.jobId] = entry
        }
    }

    fun get(jobId: Long): JobRegistryEntry? =
        active[jobId] ?: completed.firstOrNull { it.jobId == jobId }

    fun list(sessionId: Long): List<JobRegistryEntry> =
        (active.values + completed.toList()).filter { it.sessionId == sessionId }

    fun listAll(): List<JobRegistryEntry> = active.values.toList() + completed.toList()

    fun remove(jobId: Long) {
        active.remove(jobId)
        completed.removeAll { it.jobId == jobId }
    }

    fun activeCount(): Int = active.size
    fun completedCount(): Int = completed.size
}
