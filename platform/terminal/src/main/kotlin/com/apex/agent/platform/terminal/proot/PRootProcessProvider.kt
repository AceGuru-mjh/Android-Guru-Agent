package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.api.TerminalMode
import com.apex.agent.platform.terminal.linux.*
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import java.util.concurrent.ConcurrentHashMap

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
    private val commandBuilder: PRootCommandBuilder = PRootCommandBuilderImpl(),
    private val fakeRoot: Boolean = true
) : LinuxProcessProvider {

    override val capabilities: ProcessCapabilities = ProcessCapabilities(
        processGroups = false,   // PRoot does not expose process-group control
        signals = true,          // destroy() / destroyForcibly() = SIGTERM/SIGKILL
        processTree = false,     // PRoot's --kill-on-exit handles tree cleanup
        reattach = false         // Cannot reattach to a PRoot session after disconnect
    )

    private val processes = ConcurrentHashMap<Long, PRootProcessHandle>()

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
            fakeRoot = fakeRoot
        )
        val command = commandBuilder.build(launchRequest, binaryPath, rootfsPath, workspacePath)

        // Spawn the real PRoot process via ProcessBuilder.
        val commandList = listOf(command.executable.value) + command.arguments
        val pb = ProcessBuilder(commandList)
        pb.redirectErrorStream(false)   // keep stdout/stderr separate for observation
        // G4（P71 修正）：host/guest env 严格分离。
        // guest 环境变量只经 argv 的 -E 传入 rootfs 命名空间（commandBuilder 已生成），
        // host ProcessBuilder env 不再混入 request.environment —— 旧实现把 guest 变量
        // 同时写进宿主 env，造成泄漏与语义混乱（如 HOME/PWD 指向 guest 路径却作用于宿主 proot）。
        // proot 自身需要的宿主变量（PROOT_TMP_DIR/LOADER/LD_LIBRARY_PATH/PATH）由
        // PRootHostEnvironment.hostEnv() 提供 —— 此处继承默认宿主 env 以兼容 JVM CI
        //（宿主 proot 依赖 PATH 找到 loader）；Android 生产路径一律走 forkpty，不经此处。

        return try {
            val process = pb.start()
            // G1（P71 修正）：真实宿主 pid（反射访问 —— Process.pid() 是 Java 9+/Android 34+ API，
            // 直接调用会破坏低版本 Android 编译；不可得时 -1，绝不伪造），替代旧的 10000 起步计数器 ——
            // 快照/信号/日志才能对应真实进程。
            val pid = LinuxPid(ProcessPidAccessor.pidOf(process))
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
