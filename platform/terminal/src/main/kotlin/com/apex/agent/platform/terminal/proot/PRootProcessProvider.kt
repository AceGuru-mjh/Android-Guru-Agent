package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.api.TerminalMode
import com.apex.agent.platform.terminal.linux.*
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * PR #68: Real PRoot Process Provider.
 *
 * Spawns REAL PRoot processes via java.lang.ProcessBuilder. NOT a fake —
 * on CI this runs the actual `proot` binary against a real rootfs. On
 * Android the same code path works (JVM ProcessBuilder is available).
 *
 * The PRootCommandBuilder produces a structured List<String> (executable +
 * arguments, no shell interpolation). ProcessBuilder consumes that list
 * directly — no shell wrapping, no injection surface (§37 security).
 *
 * Spec: PR #68 — Real Linux Runtime. Replaces the FakeLinuxProcessProvider
 * stub that PRootRuntime previously returned.
 */
class PRootProcessProvider(
    private val binaryPath: AbsolutePath,
    private val rootfs: RootfsDescriptor,
    private val rootfsPath: AbsolutePath,
    private val workspacePath: AbsolutePath,
    private val commandBuilder: PRootCommandBuilder = PRootCommandBuilderImpl()
) : LinuxProcessProvider {

    override val capabilities: ProcessCapabilities = ProcessCapabilities(
        processGroups = false,   // PRoot does not expose process-group control
        signals = true,          // destroy() / destroyForcibly() = SIGTERM/SIGKILL
        processTree = false,     // PRoot's --kill-on-exit handles tree cleanup
        reattach = false         // Cannot reattach to a PRoot session after disconnect
    )

    private val processes = ConcurrentHashMap<Long, PRootProcessHandle>()
    private val pidCounter = AtomicLong(10000)

    override suspend fun start(request: LinuxProcessRequest): Result<LinuxProcessHandle> {
        if (rootfs.location == null) {
            return Result.failure(RuntimeException("PRootError:ROOTFS_UNAVAILABLE — rootfs has no location"))
        }

        // Build the structured PRoot command (no shell string).
        val launchRequest = PRootLaunchRequest(
            rootfs = rootfs,
            executable = request.executable,
            arguments = request.arguments,
            workingDirectory = request.workingDirectory,
            environment = request.environment,
            terminalMode = request.terminalMode,
            killOnExit = true,
            fakeRoot = true   // fake-root so apt/dpkg later work without real root
        )
        val command = commandBuilder.build(launchRequest, binaryPath, rootfsPath, workspacePath)

        // Spawn the real PRoot process via ProcessBuilder.
        val commandList = listOf(command.executable.value) + command.arguments
        val pb = ProcessBuilder(commandList)
        pb.redirectErrorStream(false)   // keep stdout/stderr separate for observation
        // Pass through the request environment to the PRoot process itself
        // (PRoot's -E flag injects these INTO the rootfs namespace).
        pb.environment().clear()
        pb.environment().putAll(request.environment)

        return try {
            val process = pb.start()
            val pid = LinuxPid(pidCounter.incrementAndGet())
            val handle = PRootProcessHandle(pid, process, request.executable, request.arguments)
            processes[pid.value] = handle
            Result.success(handle)
        } catch (e: Exception) {
            Result.failure(RuntimeException("PRootError:PROCESS_LAUNCH_FAILED — ${e.message}", e))
        }
    }

    override suspend fun snapshot(pid: LinuxPid): Result<LinuxProcessSnapshot> {
        val handle = processes[pid.value]
            ?: return Result.failure(RuntimeException("process ${pid.value} not found"))
        return handle.snapshot()
    }

    override suspend fun terminate(process: LinuxProcessHandle, mode: TerminationMode): Result<Unit> {
        if (process !is PRootProcessHandle) {
            return Result.failure(RuntimeException("not a PRoot process handle"))
        }
        return process.terminate(mode)
    }

    override suspend fun wait(process: LinuxProcessHandle): Result<LinuxExitInfo> {
        if (process !is PRootProcessHandle) {
            return Result.failure(RuntimeException("not a PRoot process handle"))
        }
        return process.await()
    }
}

/**
 * Real LinuxProcessHandle wrapping a java.lang.Process.
 *
 * - terminate(GRACEFUL) → Process.destroy()    (SIGTERM equivalent)
 * - terminate(FORCE)    → Process.destroyForcibly()  (SIGKILL equivalent)
 * - snapshot()          → reads isAlive + Process.info()
 * - await()             → Process.waitFor() (blocking)
 *
 * I/O streams (stdout/stderr/stdin) are exposed for the PTY provider to
 * pump into the observation pipeline.
 */
class PRootProcessHandle(
    override val pid: LinuxPid,
    private val process: java.lang.Process,
    private val executableName: String,
    private val argumentsList: List<String>
) : LinuxProcessHandle {

    private val startTime: Long = System.currentTimeMillis()

    override suspend fun terminate(mode: TerminationMode): Result<Unit> = runCatching {
        when (mode) {
            TerminationMode.GRACEFUL -> process.destroy()
            TerminationMode.FORCE -> process.destroyForcibly()
        }
    }

    override suspend fun snapshot(): Result<LinuxProcessSnapshot> = runCatching {
        val alive = process.isAlive
        val state = if (alive) LinuxProcessState.RUNNING
                    else LinuxProcessState.EXITED
        LinuxProcessSnapshot(
            pid = pid,
            parentPid = null,
            state = state,
            executable = executableName,
            commandLine = listOf(executableName) + argumentsList,
            startTime = startTime,
            isAlive = alive
        )
    }

    override suspend fun await(): Result<LinuxExitInfo> = runCatching {
        val exitCode = process.waitFor()
        val duration = System.currentTimeMillis() - startTime
        LinuxExitInfo(
            exitCode = exitCode,
            signal = null,
            reason = if (exitCode == 0) "NORMAL_EXIT" else "NONZERO_EXIT($exitCode)",
            durationMs = duration
        )
    }

    // ─── I/O access for the PTY provider / observation pump ───
    /** Stream TO the process's stdin (write side). */
    fun processStdin() = process.outputStream
    /** Stream FROM the process's stdout (read side). */
    fun processStdout() = process.inputStream
    /** Stream FROM the process's stderr (read side). */
    fun processStderr() = process.errorStream
}
