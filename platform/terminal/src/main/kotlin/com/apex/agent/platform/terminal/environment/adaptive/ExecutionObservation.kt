package com.apex.agent.platform.terminal.environment.adaptive

import com.apex.agent.platform.terminal.environment.DeveloperCapability
import com.apex.agent.platform.terminal.environment.DependencySource

/**
 * PR #67 section 4: Execution Observation.
 *
 * Captures the observable result of a command that the Agent ran in the
 * Terminal. The adaptive resolver (§2) and diagnostic rules (§10) read these
 * observations to figure out WHY a command failed — instead of guessing from
 * project files alone (which is the P66 v1 behaviour).
 *
 * §37 Security: the command is structured as `List<String>` — the resolver
 * NEVER reconstructs a shell string and NEVER interpolates into a shell. The
 * observation is a passive record of what already happened; it does not
 * authorize any new shell execution.
 *
 * §24 Layer separation: this type lives in the adaptive layer and is produced
 * by the Terminal layer (via the loop's `executor` callback in
 * AdaptiveProvisionLoop). It is consumed by diagnostic rules but never reaches
 * LinuxPackageManager or apt.
 *
 * Spec: PR #67 sections 4, 24, 37.
 */

// ─── Section 4: Execution Observation ───
// Immutable record of one command's execution.
data class ExecutionObservation(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val startedAt: Long,
    val finishedAt: Long,
    val workspaceId: String
) {
    /** Convenience: time spent running the command, in milliseconds. */
    val durationMs: Long get() = (finishedAt - startedAt).coerceAtLeast(0L)

    /** Convenience: did the command exit cleanly (exit code 0)? */
    val isSuccess: Boolean get() = exitCode == 0
}

// ─── Section 4: Convenience wrapper combining observation + verdict ───
// Useful for callbacks where success is computed by the caller (e.g. exit code
// 0 may still mean "test failure" depending on the harness).
data class ExecutionOutcome(
    val observation: ExecutionObservation,
    val success: Boolean
) {
    val isSuccessByExitCode: Boolean get() = observation.exitCode == 0
}

// ─── Section 4: Helper constructors for tests + thin executors ───
object ExecutionObservations {
    /** Build a failed observation with the given stderr and zero-duration timing. */
    fun failed(
        command: List<String>,
        stderr: String,
        workspaceId: String = "ws-test",
        stdout: String = "",
        exitCode: Int = 127
    ): ExecutionObservation {
        val now = System.currentTimeMillis()
        return ExecutionObservation(
            command = command,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            startedAt = now,
            finishedAt = now,
            workspaceId = workspaceId
        )
    }

    /** Build a successful observation with the given stdout. */
    fun succeeded(
        command: List<String>,
        stdout: String = "",
        workspaceId: String = "ws-test",
        stderr: String = ""
    ): ExecutionObservation {
        val now = System.currentTimeMillis()
        return ExecutionObservation(
            command = command,
            exitCode = 0,
            stdout = stdout,
            stderr = stderr,
            startedAt = now,
            finishedAt = now,
            workspaceId = workspaceId
        )
    }
}
