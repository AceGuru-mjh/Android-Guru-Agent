package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.api.TerminalMode
import com.apex.agent.platform.terminal.linux.*
import com.apex.agent.platform.terminal.runtime.*
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import com.apex.agent.platform.terminal.workspace.WorkspaceSnapshot
import com.apex.agent.platform.terminal.api.TerminalSize

// P68 real providers live in this same package (proot); no extra imports needed.
// RootfsProvider is in the linux package, already imported via linux.* above.

/**
 * PR #63: PRoot Userspace Backend.
 *
 * Implements P62 LinuxRuntime Contract via PRoot. NO Ubuntu/rootfs download/apt/dpkg.
 * Only PRoot binary execution + rootfs validation + mount planning + env building + PTY.
 *
 * Spec: PR #63 sections 1-74.
 */

// ─── Section 6/7: PRoot Binary Provider + Info ───
data class PRootBinaryInfo(
    val path: AbsolutePath,
    val version: PRootVersion?,
    val architecture: CpuArchitecture,
    val executable: Boolean
)

data class PRootVersion(val major: Int?, val minor: Int?, val patch: Int?)

interface PRootBinaryProvider {
    suspend fun locate(): Result<AbsolutePath>
    suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo>
}

// ─── Section 9/10/11: PRoot Command Builder ───
data class PRootLaunchRequest(
    val rootfs: RootfsDescriptor,
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: WorkspacePath? = null,
    val environment: Map<String, String> = emptyMap(),
    val binds: List<PRootBind> = emptyList(),
    val terminalMode: TerminalMode = TerminalMode.AUTO,
    val fakeRoot: Boolean = false,
    val killOnExit: Boolean = true
)

data class PRootBind(
    val hostPath: AbsolutePath,
    val guestPath: String,
    val readOnly: Boolean = false
)

data class PRootCommand(
    val executable: AbsolutePath,
    val arguments: List<String>
)

interface PRootCommandBuilder {
    fun build(request: PRootLaunchRequest, prootBinary: AbsolutePath, rootfsPath: AbsolutePath, workspacePath: AbsolutePath): PRootCommand
}

class PRootCommandBuilderImpl : PRootCommandBuilder {
    override fun build(
        request: PRootLaunchRequest,
        prootBinary: AbsolutePath,
        rootfsPath: AbsolutePath,
        workspacePath: AbsolutePath
    ): PRootCommand {
        val args = mutableListOf<String>()
        // Root
        args.add("-r")
        args.add(rootfsPath.value)
        // Fake root
        if (request.fakeRoot) args.add("-0")
        // Kill on exit
        if (request.killOnExit) args.add("--kill-on-exit")
        // Binds
        for (bind in request.binds) {
            args.add("-b")
            args.add("${bind.hostPath.value}:${bind.guestPath}" + if (bind.readOnly) ":0" else "")
        }
        // Workspace bind (always bind workspace to /workspace inside rootfs)
        args.add("-b")
        args.add("${workspacePath.value}:/workspace")
        // Working directory
        // P71 修正：-w 是 GUEST 路径。workspace 绑定在 guest /workspace ——
        // WorkspacePath("workspace:/foo") 必须映射为 "/workspace/foo"，
        // 而不是旧的 removePrefix 结果 "/foo"（那是 rootfs 相对路径，指向错位置）。
        // 无前缀的值按 guest 绝对路径原样使用（如 "/root"）。
        val guestCwd = request.workingDirectory?.let { toGuestPath(it.value) }
        if (guestCwd != null) {
            args.add("-w")
            args.add(guestCwd)
        }
        // Environment passthrough
        for ((key, value) in request.environment) {
            args.add("-E")
            args.add("$key=$value")
        }
        // Executable + arguments (after --)
        args.add("--")
        args.add(request.executable)
        args.addAll(request.arguments)
        return PRootCommand(executable = prootBinary, arguments = args)
    }

    /** workspace: 前缀路径 → guest /workspace 下路径；无前缀 → guest 绝对路径。 */
    internal fun toGuestPath(workspacePathValue: String): String {
        val prefix = "workspace:"
        return if (workspacePathValue.startsWith(prefix)) {
            val rest = workspacePathValue.removePrefix(prefix)
            if (rest.isEmpty() || rest == "/") "/workspace"
            else if (rest.startsWith("/")) "/workspace$rest"
            else "/workspace/$rest"
        } else {
            workspacePathValue
        }
    }
}

// ─── Section 13/14/15: Rootfs Validation ───
enum class RootfsValidationError {
    MISSING_ROOT, MISSING_BIN, MISSING_ETC, MISSING_USR, MISSING_HOME,
    NOT_DIRECTORY, NOT_READABLE, ARCHITECTURE_MISMATCH, CORRUPTED, UNKNOWN
}

data class RootfsValidation(
    val valid: Boolean,
    val architectureCompatible: Boolean,
    val hasRootDirectory: Boolean,
    val hasBin: Boolean,
    val hasEtc: Boolean,
    val hasUsr: Boolean,
    val hasHome: Boolean,
    val errors: List<RootfsValidationError>
)

interface RootfsValidator {
    suspend fun validate(rootfs: RootfsDescriptor): Result<RootfsValidation>
}

// ─── Section 17/18/19: Mount Planner ───
interface LinuxMountPlanner {
    fun plan(rootfs: RootfsDescriptor, workspace: WorkspaceSnapshot): LinuxMountPlan
}

data class LinuxMountPlan(val mounts: List<com.apex.agent.platform.terminal.linux.LinuxMount>)

class LinuxMountPlannerImpl : LinuxMountPlanner {
    override fun plan(rootfs: RootfsDescriptor, workspace: WorkspaceSnapshot): LinuxMountPlan {
        val mounts = mutableListOf<com.apex.agent.platform.terminal.linux.LinuxMount>()
        // RootFS is the base
        mounts.add(com.apex.agent.platform.terminal.linux.LinuxMount(
            source = rootfs.location?.value, target = WorkspacePath("workspace:/"),
            type = com.apex.agent.platform.terminal.linux.LinuxMountType.ROOTFS,
            readOnly = rootfs.readOnly
        ))
        // Workspace bind
        mounts.add(com.apex.agent.platform.terminal.linux.LinuxMount(
            source = workspace.root.value, target = WorkspacePath("workspace:/workspace"),
            type = com.apex.agent.platform.terminal.linux.LinuxMountType.BIND,
            readOnly = false
        ))
        // Home
        mounts.add(com.apex.agent.platform.terminal.linux.LinuxMount(
            source = null, target = WorkspacePath.home(),
            type = com.apex.agent.platform.terminal.linux.LinuxMountType.HOME,
            readOnly = false
        ))
        // Tmp
        mounts.add(com.apex.agent.platform.terminal.linux.LinuxMount(
            source = null, target = WorkspacePath.tmp(),
            type = com.apex.agent.platform.terminal.linux.LinuxMountType.TMP,
            readOnly = false
        ))
        return LinuxMountPlan(mounts)
    }
}

// ─── Section 22/23: Environment Builder ───
interface LinuxEnvironmentBuilder {
    fun build(runtime: LinuxRuntime, request: LinuxProcessRequest): Map<String, String>
}

class LinuxEnvironmentBuilderImpl : LinuxEnvironmentBuilder {
    override fun build(runtime: LinuxRuntime, request: LinuxProcessRequest): Map<String, String> {
        val env = mutableMapOf<String, String>()
        // Runtime base environment
        val runtimeEnv = runtime.environment()
        for ((key, value) in runtimeEnv.snapshot()) {
            env[key] = value
        }
        // Linux-specific PATH (not Android PATH)
        env["PATH"] = "/usr/local/bin:/usr/bin:/bin"
        // HOME = workspace home, not Android data dir
        env["HOME"] = runtimeEnv.homeDirectory().value.removePrefix("workspace:")
        // PWD = working directory
        if (request.workingDirectory != null) {
            env["PWD"] = request.workingDirectory.value.removePrefix("workspace:")
        }
        // TMPDIR
        env["TMPDIR"] = "/tmp"
        // SHELL
        val shell = runtimeEnv.shell()
        env["SHELL"] = shell.path
        // LANG/LC_ALL from request environment (not hardcoded)
        for ((key, value) in request.environment) {
            env[key] = value
        }
        return env
    }
}

// ─── Section 29: Process Mapping ───
data class ProcessMapping(
    val hostPid: LinuxPid?,
    val userspacePid: LinuxPid?,
    val process: LinuxProcessHandle
)

// ─── Section 43: Error Model ───
enum class PRootErrorCode {
    BINARY_NOT_FOUND, BINARY_NOT_EXECUTABLE, ARCHITECTURE_MISMATCH,
    ROOTFS_INVALID, ROOTFS_UNAVAILABLE, ROOTFS_ARCHITECTURE_MISMATCH,
    MOUNT_PLAN_INVALID, ENVIRONMENT_INVALID,
    PROCESS_LAUNCH_FAILED, PTY_CREATION_FAILED,
    STARTUP_TIMEOUT, USERSPACE_EXITED, USERSPACE_CRASHED,
    CANCELLED, UNKNOWN
}

data class PRootError(
    val code: PRootErrorCode,
    val message: String,
    val recoverable: Boolean = false
)

// ─── Section 4: Userspace Execution Backend ───
interface UserspaceExecutionBackend {
    val userspaceType: LinuxUserspaceType
    suspend fun validate(request: UserspaceLaunchRequest): Result<UserspaceValidation>
}

data class UserspaceValidation(
    val valid: Boolean,
    val rootfsValid: Boolean,
    val binaryValid: Boolean,
    val errors: List<String>
)

// ─── Section 41: Reconciler ───
interface UserspaceReconciler {
    suspend fun reconcile(runtime: LinuxRuntime): ReconciliationResult
}

data class ReconciliationResult(
    val runtimeHealthy: Boolean,
    val sessions: List<SessionReconciliation>
)

data class SessionReconciliation(
    val sessionId: String,
    val alive: Boolean,
    val action: String
)

// ─── Section 47: PRoot Runtime (PR #68 — real providers wired in) ───
// P68: processProvider()/ptyProvider() now return REAL PRootProcessProvider /
// PRootPtyProvider that spawn actual PRoot processes via ProcessBuilder, when
// configured with a rootfsProvider + workspacePath. The Fake fallback remains
// only for the "not configured" state (old contract tests that don't provide
// rootfs). Production MUST provide rootfsProvider — see PRootRuntimeIntegrationTest.
class PRootRuntime(
    private val binaryProvider: PRootBinaryProvider,
    private val rootfsValidator: RootfsValidator,
    private val rootfsProvider: RootfsProvider? = null,
    private val workspacePath: AbsolutePath? = null,
    private val fakeRoot: Boolean = true,
    private val mountPlanner: LinuxMountPlanner = LinuxMountPlannerImpl(),
    private val envBuilder: LinuxEnvironmentBuilder = LinuxEnvironmentBuilderImpl(),
    private val commandBuilder: PRootCommandBuilder = PRootCommandBuilderImpl(),
    private val runtimeId: RuntimeId = RuntimeId("proot-${System.currentTimeMillis()}")
) : LinuxRuntime {

    override val id: RuntimeId = runtimeId
    override val type: RuntimeType = RuntimeType.LINUX
    private var _state: RuntimeState = RuntimeState.CREATED
    override val state: RuntimeState get() = _state
    private var _health: RuntimeHealth = RuntimeHealth.HEALTHY
    override val health: RuntimeHealth get() = _health

    private var binaryInfo: PRootBinaryInfo? = null
    private var binaryPath: AbsolutePath? = null
    private var rootfsDescriptor: RootfsDescriptor? = null

    // ── P68: real providers (null until initialize() configures them) ──
    private var realProcessProvider: PRootProcessProvider? = null
    private var realPtyProvider: PRootPtyProvider? = null
    private var realFilesystem: PRootFilesystem? = null
    private var realEnvironment: PRootEnvironment? = null
    private var realShellProvider: PRootShellProvider? = null

    override suspend fun initialize(): Result<Unit> {
        _state = RuntimeState.INITIALIZING
        val bPath = binaryProvider.locate().getOrElse {
            _state = RuntimeState.FAILED
            _health = RuntimeHealth.UNAVAILABLE
            return Result.failure(RuntimeException("PRootError:BINARY_NOT_FOUND"))
        }
        binaryInfo = binaryProvider.verify(bPath).getOrElse {
            _state = RuntimeState.FAILED
            _health = RuntimeHealth.UNAVAILABLE
            return Result.failure(RuntimeException("PRootError:BINARY_NOT_EXECUTABLE"))
        }
        binaryPath = bPath

        // ── P68: if rootfsProvider is configured, wire real providers ──
        if (rootfsProvider != null && workspacePath != null) {
            val rootfs = rootfsProvider.current()
            if (rootfs == null) {
                _state = RuntimeState.FAILED
                _health = RuntimeHealth.UNAVAILABLE
                return Result.failure(RuntimeException("PRootError:ROOTFS_UNAVAILABLE — no rootfs configured"))
            }
            val verification = rootfsProvider.verify(rootfs).getOrElse {
                _state = RuntimeState.FAILED
                _health = RuntimeHealth.UNAVAILABLE
                return Result.failure(RuntimeException("PRootError:ROOTFS_INVALID — verify failed"))
            }
            if (!verification.valid) {
                _state = RuntimeState.FAILED
                _health = RuntimeHealth.UNAVAILABLE
                return Result.failure(RuntimeException("PRootError:ROOTFS_INVALID — ${verification.issues}"))
            }
            rootfsDescriptor = rootfs
            val rootfsPath = rootfs.location
                ?: return Result.failure(RuntimeException("PRootError:ROOTFS_UNAVAILABLE — rootfs has no location"))

            // Construct real providers — these spawn actual PRoot processes.
            realFilesystem = PRootFilesystem(rootfsPath)
            realEnvironment = PRootEnvironment(rootfsPath)
            realShellProvider = PRootShellProvider(rootfsPath)
            realProcessProvider = PRootProcessProvider(
                binaryPath = bPath,
                rootfs = rootfs,
                rootfsPath = rootfsPath,
                workspacePath = workspacePath,
                commandBuilder = commandBuilder,
                fakeRoot = fakeRoot
            )
            realPtyProvider = PRootPtyProvider(realProcessProvider!!)
        }

        _state = RuntimeState.READY
        _health = RuntimeHealth.HEALTHY
        return Result.success(Unit)
    }

    override suspend fun shutdown(force: Boolean): Result<Unit> {
        _state = RuntimeState.SHUTTING_DOWN
        _health = RuntimeHealth.SHUTTING_DOWN
        // P68: real providers don't hold long-lived resources beyond the
        // processes they spawned (each Process is tracked by its handle).
        // --kill-on-exit in PRootCommandBuilder ensures child processes
        // inside the rootfs are cleaned up when the host PRoot process exits.
        _state = RuntimeState.CLOSED
        return Result.success(Unit)
    }

    override fun capabilities(): RuntimeCapabilities = RuntimeCapabilities(
        pty = true, processGroups = false, signals = true, resize = true,
        filesystem = true, shell = true, persistence = true, reattach = false
    )

    override fun snapshot(): RuntimeSnapshot = RuntimeSnapshot(
        id = id, type = type, state = _state, health = _health,
        capabilities = capabilities(), activeSessionCount = 0,
        workspaceIds = emptyList(), createdAt = System.currentTimeMillis()
    )

    // ── Environment: real when configured, fake fallback for unconfigured ──
    private val fallbackEnv = object : LinuxEnvironment {
        override fun user() = LinuxUser(0, 0, "root", WorkspacePath.home(), true)
        override fun homeDirectory() = WorkspacePath.home()
        override fun workingDirectory() = WorkspacePath.work()
        override fun shell() = ShellInfo("/bin/sh", "sh", null)
        override fun pathEntries() = listOf(WorkspacePath("workspace:/usr/bin"), WorkspacePath("workspace:/bin"))
        override fun path(): String? = "/proot/path"
        override fun get(name: String): String? = when (name) {
            "HOME" -> "/home/root"
            "PATH" -> "/usr/local/bin:/usr/bin:/bin"
            "SHELL" -> "/bin/sh"
            else -> null
        }
        override fun snapshot(): Map<String, String> = mapOf("HOME" to "/home/root", "PATH" to "/usr/local/bin:/usr/bin:/bin", "SHELL" to "/bin/sh")
    }
    override fun environment(): LinuxEnvironment = realEnvironment ?: fallbackEnv

    override fun shellProvider(): ShellProvider = realShellProvider ?: object : ShellProvider {
        override suspend fun defaultShell() = ShellInfo("/bin/sh", "sh", null)
        override suspend fun availableShells() = listOf(ShellInfo("/bin/sh", "sh", null))
    }

    override fun filesystem(): LinuxFilesystem = realFilesystem ?: object : LinuxFilesystem {
        override val capabilities = FilesystemCapabilities()
        override suspend fun exists(path: WorkspacePath) = false
        override suspend fun isDirectory(path: WorkspacePath) = false
        override suspend fun createDirectories(path: WorkspacePath) = Result.success(Unit)
        override suspend fun delete(path: WorkspacePath) = Result.success(Unit)
        override suspend fun size(path: WorkspacePath) = null
        override suspend fun resolve(path: WorkspacePath) = AbsolutePath("/proot" + path.value.removePrefix("workspace:"))
    }

    // P68: real process/PTY providers when configured. Fake fallback ONLY
    // for the "not configured" state (old contract tests / unconfigured runtime).
    // Production MUST provide rootfsProvider — see PRootRuntimeIntegrationTest.
    override fun processProvider(): LinuxProcessProvider =
        realProcessProvider ?: com.apex.agent.platform.terminal.linux.FakeLinuxProcessProvider()
    override fun ptyProvider(): LinuxPtyProvider =
        realPtyProvider ?: com.apex.agent.platform.terminal.linux.FakeLinuxPtyProvider()

    override fun runtimeInfo(): LinuxRuntimeInfo = LinuxRuntimeInfo(
        architecture = binaryInfo?.architecture ?: CpuArchitecture.UNKNOWN,
        kernelVersion = null, distribution = rootfsDescriptor?.distribution,
        distributionVersion = rootfsDescriptor?.version,
        userspaceType = LinuxUserspaceType.PROOT,
        rootfsType = rootfsDescriptor?.let { RootfsType.DIRECTORY } ?: RootfsType.UNKNOWN,
        isRoot = true, uid = 0, gid = 0
    )
    override fun supports(capability: LinuxCapability): Boolean = when (capability) {
        LinuxCapability.EXECUTION, LinuxCapability.SHELL, LinuxCapability.PTY,
        LinuxCapability.FILESYSTEM, LinuxCapability.SIGNALS, LinuxCapability.RESIZE -> true
        LinuxCapability.PROCESS_TREE, LinuxCapability.PROCESS_GROUPS,
        LinuxCapability.REATTACH, LinuxCapability.ROOTFS,
        LinuxCapability.NETWORK, LinuxCapability.PACKAGE_MANAGER -> false
    }
}
