package com.apex.agent.platform.terminal.linux

import com.apex.agent.platform.terminal.api.TerminalMode
import com.apex.agent.platform.terminal.api.TerminalSize
import com.apex.agent.platform.terminal.runtime.*
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * PR #62 Section 49: Fake Linux Runtime for Contract testing.
 *
 * Does NOT start any Linux, PRoot, or Ubuntu. Pure JVM simulation.
 * Enables testing Session/Job/ExecutionBackend/Observation/Recovery
 * without real process/PTY/filesystem.
 */
class FakeLinuxRuntime(
    private val runtimeId: RuntimeId = RuntimeId("fake-rt-${System.currentTimeMillis()}")
) : LinuxRuntime {

    override val id: RuntimeId = runtimeId
    override val type: RuntimeType = RuntimeType.LINUX
    private var _state: RuntimeState = RuntimeState.CREATED
    override val state: RuntimeState get() = _state
    override val health: RuntimeHealth = RuntimeHealth.HEALTHY

    private val fakeFs = FakeLinuxFilesystem()
    private val fakeProc = FakeLinuxProcessProvider()
    private val fakePty = FakeLinuxPtyProvider()
    private val fakeEnv = FakeLinuxEnvironment()

    override suspend fun initialize(): Result<Unit> {
        _state = RuntimeState.INITIALIZING
        delay(10)
        _state = RuntimeState.READY
        return Result.success(Unit)
    }

    override suspend fun shutdown(force: Boolean): Result<Unit> {
        _state = RuntimeState.SHUTTING_DOWN
        delay(10)
        _state = RuntimeState.CLOSED
        return Result.success(Unit)
    }

    override fun capabilities(): RuntimeCapabilities = RuntimeCapabilities()
    override fun snapshot(): RuntimeSnapshot = RuntimeSnapshot(
        id = id, type = type, state = _state, health = health,
        capabilities = capabilities(), activeSessionCount = 0,
        workspaceIds = emptyList(), createdAt = System.currentTimeMillis()
    )

    override fun environment(): LinuxEnvironment = fakeEnv
    override fun shellProvider(): ShellProvider = FakeShellProvider()
    override fun filesystem(): LinuxFilesystem = fakeFs
    override fun processProvider(): LinuxProcessProvider = fakeProc
    override fun ptyProvider(): LinuxPtyProvider = fakePty

    override fun runtimeInfo(): LinuxRuntimeInfo = LinuxRuntimeInfo(
        architecture = CpuArchitecture.ARM64,
        kernelVersion = "fake-kernel-1.0",
        distribution = LinuxDistribution.UBUNTU,
        distributionVersion = "24.04-fake",
        userspaceType = LinuxUserspaceType.NATIVE,
        rootfsType = RootfsType.VIRTUAL,
        isRoot = false,
        uid = 1000,
        gid = 1000
    )

    override fun supports(capability: LinuxCapability): Boolean = when (capability) {
        LinuxCapability.EXECUTION, LinuxCapability.SHELL, LinuxCapability.PTY,
        LinuxCapability.FILESYSTEM, LinuxCapability.SIGNALS, LinuxCapability.RESIZE -> true
        LinuxCapability.PROCESS_TREE, LinuxCapability.PROCESS_GROUPS -> true
        LinuxCapability.REATTACH, LinuxCapability.ROOTFS,
        LinuxCapability.NETWORK, LinuxCapability.PACKAGE_MANAGER -> false
    }
}

class FakeLinuxEnvironment : LinuxEnvironment {
    override fun user(): LinuxUser = LinuxUser(
        uid = 1000, gid = 1000, username = "agent",
        home = WorkspacePath.home(), isRoot = false
    )
    override fun homeDirectory(): WorkspacePath = WorkspacePath.home()
    override fun workingDirectory(): WorkspacePath = WorkspacePath.work()
    override fun shell(): ShellInfo = ShellInfo("/bin/bash", "bash", "5.1-fake")
    override fun pathEntries(): List<WorkspacePath> = listOf(
        WorkspacePath("workspace:/usr/bin"), WorkspacePath("workspace:/usr/local/bin")
    )
    override fun get(name: String): String? = when (name) {
        "HOME" -> "/home/agent"
        "PATH" -> "/usr/bin:/usr/local/bin"
        "SHELL" -> "/bin/bash"
        else -> null
    }
    override fun snapshot(): Map<String, String> = mapOf(
        "HOME" to "/home/agent", "PATH" to "/usr/bin:/usr/local/bin", "SHELL" to "/bin/bash"
    )
}

class FakeLinuxFilesystem : LinuxFilesystem {
    override val capabilities: FilesystemCapabilities = FilesystemCapabilities()
    private val dirs = mutableSetOf<String>()
    private val files = mutableMapOf<String, ByteArray>()

    override suspend fun exists(path: WorkspacePath): Boolean =
        dirs.contains(path.value) || files.containsKey(path.value)

    override suspend fun isDirectory(path: WorkspacePath): Boolean = dirs.contains(path.value)
    override suspend fun createDirectories(path: WorkspacePath): Result<Unit> {
        dirs.add(path.value); return Result.success(Unit)
    }
    override suspend fun delete(path: WorkspacePath): Result<Unit> {
        dirs.remove(path.value); files.remove(path.value); return Result.success(Unit)
    }
    override suspend fun size(path: WorkspacePath): Long? = files[path.value]?.size?.toLong()
    override suspend fun resolve(path: WorkspacePath): AbsolutePath =
        AbsolutePath("/fake" + path.value.removePrefix("workspace:"))
}

class FakeLinuxProcessProvider : LinuxProcessProvider {
    override val capabilities: ProcessCapabilities = ProcessCapabilities()
    private val pidCounter = AtomicLong(1000)
    private val processes = ConcurrentHashMap<Long, FakeLinuxProcessHandle>()

    override suspend fun start(request: LinuxProcessRequest): Result<LinuxProcessHandle> {
        val pid = LinuxPid(pidCounter.incrementAndGet())
        val handle = FakeLinuxProcessHandle(pid, request.executable, request.arguments)
        processes[pid.value] = handle
        return Result.success(handle)
    }

    override suspend fun snapshot(pid: LinuxPid): Result<LinuxProcessSnapshot> {
        val handle = processes[pid.value]
            ?: return Result.failure(RuntimeException("process not found"))
        return Result.success(handle.fakeSnapshot())
    }

    override suspend fun terminate(process: LinuxProcessHandle, mode: TerminationMode): Result<Unit> {
        if (process is FakeLinuxProcessHandle) process.terminate()
        return Result.success(Unit)
    }

    override suspend fun wait(process: LinuxProcessHandle): Result<LinuxExitInfo> {
        if (process is FakeLinuxProcessHandle) {
            return Result.success(process.awaitExit())
        }
        return Result.failure(RuntimeException("unknown process"))
    }
}

class FakeLinuxProcessHandle(
    override val pid: LinuxPid,
    private val executable: String,
    private val arguments: List<String>
) : LinuxProcessHandle {
    private var _state: LinuxProcessState = LinuxProcessState.RUNNING
    private var _exitCode: Int? = null
    private val startTime: Long = System.currentTimeMillis()

    fun fakeSnapshot(): LinuxProcessSnapshot = LinuxProcessSnapshot(
        pid = pid, parentPid = null, state = _state,
        executable = executable, commandLine = listOf(executable) + arguments,
        startTime = startTime, isAlive = _state == LinuxProcessState.RUNNING
    )

    fun terminate() {
        _state = LinuxProcessState.EXITED
        _exitCode = 0
    }

    fun awaitExit(): LinuxExitInfo {
        _state = LinuxProcessState.EXITED
        _exitCode = _exitCode ?: 0
        return LinuxExitInfo(
            exitCode = _exitCode, signal = null,
            reason = "NORMAL_EXIT", durationMs = System.currentTimeMillis() - startTime
        )
    }

    override suspend fun terminate(mode: TerminationMode): Result<Unit> {
        terminate(); return Result.success(Unit)
    }
    override suspend fun snapshot(): Result<LinuxProcessSnapshot> = Result.success(fakeSnapshot())
    override suspend fun await(): Result<LinuxExitInfo> = Result.success(awaitExit())
}

class FakeLinuxPtyProvider : LinuxPtyProvider {
    private val idCounter = AtomicLong(0)
    override suspend fun create(request: LinuxPtyRequest): Result<LinuxPtySession> {
        val procHandle = FakeLinuxProcessHandle(
            LinuxPid(idCounter.incrementAndGet()),
            request.executable, request.arguments
        )
        return Result.success(FakeLinuxPtySession(procHandle))
    }
}

class FakeLinuxPtySession(override val process: LinuxProcessHandle) : LinuxPtySession {
    override suspend fun resize(size: TerminalSize): Result<Unit> = Result.success(Unit)
    override suspend fun close(mode: ShutdownMode): Result<Unit> = Result.success(Unit)
}

class FakeShellProvider : ShellProvider {
    override suspend fun defaultShell(): ShellInfo = ShellInfo("/bin/bash", "bash", "5.1-fake")
    override suspend fun availableShells(): List<ShellInfo> = listOf(
        ShellInfo("/bin/bash", "bash", "5.1-fake"),
        ShellInfo("/bin/sh", "sh", null)
    )
}
