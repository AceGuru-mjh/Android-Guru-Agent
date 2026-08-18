package com.apex.agent.platform.terminal.linux

import com.apex.agent.platform.terminal.api.TerminalCapabilities
import com.apex.agent.platform.terminal.api.TerminalMode
import com.apex.agent.platform.terminal.runtime.RuntimeCapabilities
import com.apex.agent.platform.terminal.runtime.RuntimeId
import com.apex.agent.platform.terminal.runtime.RuntimeState
import com.apex.agent.platform.terminal.runtime.RuntimeHealth
import com.apex.agent.platform.terminal.runtime.ShellInfo
import com.apex.agent.platform.terminal.runtime.ShellProvider
import com.apex.agent.platform.terminal.runtime.RuntimeSnapshot
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeContext
import com.apex.agent.platform.terminal.runtime.RuntimeType
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspaceId
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import com.apex.agent.platform.terminal.api.TerminalSize

/**
 * PR #62: Linux Runtime Contract.
 *
 * Complete Linux userspace runtime contract. P62 does NOT implement Ubuntu/PRoot.
 * Only contracts + models + capabilities + lifecycle + backend boundary + tests.
 *
 * Spec: PR #62 sections 1-54.
 */

// ─── Section 4: CPU Architecture ───
enum class CpuArchitecture { ARM64, ARM32, X86_64, X86, RISCV64, UNKNOWN }

// ─── Section 5: Linux Distribution ───
enum class LinuxDistribution { UBUNTU, DEBIAN, ALPINE, ARCH, FEDORA, CUSTOM, UNKNOWN }

// ─── Section 6: Userspace Type ───
enum class LinuxUserspaceType { NATIVE, PROOT, CONTAINER, CHROOT, CUSTOM }

// ─── Section 7: Rootfs Type ───
enum class RootfsType { SYSTEM, DIRECTORY, ARCHIVE, IMAGE, VIRTUAL, UNKNOWN }

// ─── Section 3: Linux Runtime Info ───
data class LinuxRuntimeInfo(
    val architecture: CpuArchitecture,
    val kernelVersion: String?,
    val distribution: LinuxDistribution?,
    val distributionVersion: String?,
    val userspaceType: LinuxUserspaceType,
    val rootfsType: RootfsType,
    val isRoot: Boolean,
    val uid: Long?,
    val gid: Long?
)

// ─── Section 2: Linux Runtime ───
interface LinuxRuntime : TerminalRuntimeContext {
    fun filesystem(): LinuxFilesystem
    fun processProvider(): LinuxProcessProvider
    override fun shellProvider(): ShellProvider
    override fun environment(): LinuxEnvironment
    fun ptyProvider(): LinuxPtyProvider
    fun runtimeInfo(): LinuxRuntimeInfo
    fun supports(capability: LinuxCapability): Boolean
}

// ─── Section 40: Linux Capability ───
enum class LinuxCapability {
    EXECUTION, SHELL, PTY, FILESYSTEM, PROCESS_TREE, PROCESS_GROUPS, SIGNALS, RESIZE, REATTACH, ROOTFS, NETWORK, PACKAGE_MANAGER
}

// ─── Section 9: Linux User ───
data class LinuxUser(
    val uid: Long,
    val gid: Long,
    val username: String?,
    val home: WorkspacePath,
    val isRoot: Boolean
)

// ─── Section 8: Linux Environment ───
interface LinuxEnvironment : com.apex.agent.platform.terminal.runtime.RuntimeEnvironment {
    fun user(): LinuxUser
    fun homeDirectory(): WorkspacePath
    fun workingDirectory(): WorkspacePath
    fun shell(): ShellInfo
    fun pathEntries(): List<WorkspacePath>
}

// ─── Section 11: Filesystem Capabilities ───
data class FilesystemCapabilities(
    val read: Boolean = true,
    val write: Boolean = true,
    val create: Boolean = true,
    val delete: Boolean = true,
    val execute: Boolean = true,
    val symbolicLinks: Boolean = true,
    val hardLinks: Boolean = false,
    val permissions: Boolean = false,
    val stat: Boolean = true
)

// ─── Section 10: Linux Filesystem ───
interface LinuxFilesystem {
    val capabilities: FilesystemCapabilities
    suspend fun exists(path: WorkspacePath): Boolean
    suspend fun isDirectory(path: WorkspacePath): Boolean
    suspend fun createDirectories(path: WorkspacePath): Result<Unit>
    suspend fun delete(path: WorkspacePath): Result<Unit>
    suspend fun size(path: WorkspacePath): Long?
    suspend fun resolve(path: WorkspacePath): AbsolutePath
}

// ─── Section 13: Linux Pid ───
@JvmInline value class LinuxPid(val value: Long)

// ─── Section 21: Linux Process State ───
enum class LinuxProcessState { CREATED, STARTING, RUNNING, STOPPING, EXITED, FAILED, UNKNOWN }

// ─── Section 23: Termination Mode ───
enum class TerminationMode { GRACEFUL, FORCE }

// ─── Section 22: Process Capabilities ───
data class ProcessCapabilities(
    val processGroups: Boolean = true,
    val signals: Boolean = true,
    val processTree: Boolean = false,
    val reattach: Boolean = false
)

// ─── Section 20: Linux Process Snapshot ───
data class LinuxProcessSnapshot(
    val pid: LinuxPid,
    val parentPid: LinuxPid?,
    val state: LinuxProcessState,
    val executable: String?,
    val commandLine: List<String>,
    val startTime: Long?,
    val isAlive: Boolean
)

// ─── Section 16: Linux Process Request ───
data class LinuxProcessRequest(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: WorkspacePath? = null,
    val environment: Map<String, String> = emptyMap(),
    val terminalMode: TerminalMode = TerminalMode.AUTO,
    val stdin: InputMode = InputMode.ENABLED
)

enum class InputMode { ENABLED, DISABLED, PIPED }

// ─── Section 6: Linux Exit Info ───
data class LinuxExitInfo(
    val exitCode: Int?,
    val signal: String?,
    val reason: String,
    val durationMs: Long
)

// ─── Section 14: Linux Process Handle ───
interface LinuxProcessHandle {
    val pid: LinuxPid
    suspend fun terminate(mode: TerminationMode = TerminationMode.GRACEFUL): Result<Unit>
    suspend fun snapshot(): Result<LinuxProcessSnapshot>
    suspend fun await(): Result<LinuxExitInfo>
}

// ─── Section 12: Linux Process Provider ───
interface LinuxProcessProvider {
    val capabilities: ProcessCapabilities
    suspend fun start(request: LinuxProcessRequest): Result<LinuxProcessHandle>
    suspend fun snapshot(pid: LinuxPid): Result<LinuxProcessSnapshot>
    suspend fun terminate(process: LinuxProcessHandle, mode: TerminationMode): Result<Unit>
    suspend fun wait(process: LinuxProcessHandle): Result<LinuxExitInfo>
}

// ─── Section 25: Linux PTY Session ───
interface LinuxPtySession {
    val process: LinuxProcessHandle
    suspend fun resize(size: TerminalSize): Result<Unit>
    suspend fun close(mode: com.apex.agent.platform.terminal.runtime.ShutdownMode = com.apex.agent.platform.terminal.runtime.ShutdownMode.GRACEFUL): Result<Unit>
}

// ─── Section 24: Linux PTY Request ───
data class LinuxPtyRequest(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: WorkspacePath? = null,
    val environment: Map<String, String> = emptyMap(),
    val rows: Int = 24,
    val cols: Int = 80
)

// ─── Section 24: Linux PTY Provider ───
interface LinuxPtyProvider {
    suspend fun create(request: LinuxPtyRequest): Result<LinuxPtySession>
}

// ─── Section 19: Shell Execution Request ───
data class ShellExecutionRequest(
    val command: String,
    val workingDirectory: WorkspacePath? = null,
    val environment: Map<String, String> = emptyMap(),
    val terminalMode: TerminalMode = TerminalMode.AUTO
)

// ─── Section 19: Linux Shell Executor ───
interface LinuxShellExecutor {
    suspend fun execute(request: ShellExecutionRequest): Result<LinuxProcessHandle>
}

// ─── Section 28: Linux Mount Type ───
enum class LinuxMountType { ROOTFS, HOME, TMP, PROC, SYS, DEV, BIND, VIRTUAL, CUSTOM }

// ─── Section 27: Linux Mount ───
data class LinuxMount(
    val source: String?,
    val target: WorkspacePath,
    val type: LinuxMountType,
    val readOnly: Boolean
)

// ─── Section 27: Linux Mount Table ───
interface LinuxMountTable {
    fun entries(): List<LinuxMount>
}

// ─── Section 29: Rootfs Descriptor ───
data class RootfsDescriptor(
    val id: String,
    val distribution: LinuxDistribution,
    val version: String?,
    val architecture: CpuArchitecture,
    val location: AbsolutePath?,
    val sizeBytes: Long?,
    val checksum: String?,
    val readOnly: Boolean
)

// ─── Section 44: Rootfs State ───
enum class RootfsState { UNKNOWN, DISCOVERING, AVAILABLE, INVALID, CORRUPTED, REMOVING }

// ─── Section 30: Rootfs Verification ───
data class RootfsVerification(
    val valid: Boolean,
    val state: RootfsState,
    val issues: List<String>
)

// ─── Section 30: Rootfs Provider ───
interface RootfsProvider {
    suspend fun current(): RootfsDescriptor?
    suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification>
}

// ─── Section 37: Userspace Launcher ───
interface UserspaceLauncher {
    suspend fun launch(request: UserspaceLaunchRequest): Result<LinuxProcessHandle>
}

// ─── Section 38: Userspace Launch Request ───
data class UserspaceLaunchRequest(
    val rootfs: RootfsDescriptor,
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: WorkspacePath?,
    val environment: Map<String, String>,
    val terminalMode: TerminalMode
)

// ─── Section 32: Linux Runtime Failure ───
enum class LinuxRuntimeFailure {
    INVALID_CONFIGURATION, ROOTFS_UNAVAILABLE, ROOTFS_INVALID, FILESYSTEM_UNAVAILABLE,
    SHELL_UNAVAILABLE, PROCESS_PROVIDER_UNAVAILABLE, PTY_UNAVAILABLE,
    ENVIRONMENT_INVALID, ARCHITECTURE_UNSUPPORTED, UNKNOWN
}

// ─── Section 33: Linux Runtime Diagnostics ───
data class LinuxRuntimeDiagnostics(
    val runtimeId: RuntimeId,
    val rootfs: RootfsDescriptor?,
    val architecture: CpuArchitecture,
    val distribution: LinuxDistribution?,
    val userspaceType: LinuxUserspaceType,
    val shell: ShellInfo?,
    val capabilities: RuntimeCapabilities,
    val failures: List<LinuxRuntimeFailure>
)

// ─── Section 45: Linux Runtime Snapshot ───
data class LinuxRuntimeSnapshot(
    val runtime: RuntimeSnapshot,
    val info: LinuxRuntimeInfo,
    val rootfs: RootfsDescriptor?,
    val filesystemCapabilities: FilesystemCapabilities,
    val processCapabilities: ProcessCapabilities
)

// ─── Section 47: Runtime Configuration ───
data class RuntimeConfiguration(
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: WorkspacePath? = null,
    val persistence: Boolean = true
) { companion object { val DEFAULT = RuntimeConfiguration() } }
