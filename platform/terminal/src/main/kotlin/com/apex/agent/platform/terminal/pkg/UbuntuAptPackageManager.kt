package com.apex.agent.platform.terminal.pkg

import com.apex.agent.platform.terminal.environment.LinuxEnvironmentManager
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.proot.PRootBind
import com.apex.agent.platform.terminal.proot.PRootBinaryProvider
import com.apex.agent.platform.terminal.proot.PRootCommand
import com.apex.agent.platform.terminal.proot.PRootCommandBuilder
import com.apex.agent.platform.terminal.proot.PRootCommandBuilderImpl
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.platform.terminal.proot.PRootLaunchRequest
import com.apex.agent.platform.terminal.proot.ProotExecutor
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * T76: UbuntuAptPackageManager —— 生产级 apt/dpkg 包管理器。
 *
 * 把 [LinuxPackageManager] 契约接到真实 Ubuntu rootfs 上的 apt-get/apt-cache/dpkg。
 * **所有 apt 执行经 [ProotExecutor]（PRoot + ProcessBuilder），绝不直接 Runtime.exec**
 * （T76 §3）—— 与交互式 terminal.create(backendId="linux-ubuntu") 共享同一 rootfs /
 * workspace / persistent home / package state，不存在两个互不知道状态的 Ubuntu 环境。
 *
 * 架构边界（T76 §3 / §47）：
 * ```
 * LinuxPackageManager.update/install/...
 *         ↓
 * UbuntuAptPackageManager
 *         ↓ ProotExecutor.executeBounded (有界输出)
 *         ↓ PRootCommandBuilder (argv 构造 + 注入防护)
 *         ↓ proot -r <rootfs> -b <workspace>:/workspace -b <home>:/root
 *         ↓   -E DEBIAN_FRONTEND=noninteractive ...
 *         ↓   -- apt-get install -y git
 *         ↓
 * Ubuntu RootFS (apt/dpkg database) ← 与 terminal session 共享
 * ```
 *
 * 并发控制（T76 §5）：
 *  - 写操作（update/install/remove/upgrade/repair）经 [PackageOperationLock] 串行化
 *    （进程内 Mutex + 跨实例 OS 文件锁）。
 *  - 读操作（search/info/isInstalled/installedVersion）无锁并发（apt-cache/dpkg-query
 *    只读，不触碰 dpkg database 写锁）。
 *
 * 取消语义（T76 §36）：
 *  - 协程取消 → [ensureActive] 抛 CancellationException → finally 释放锁 → 状态置
 *    CANCELLED。ProotExecutor 的 destroyForcibly 由其内部 timeout 处理；协程取消时
 *    已启动的 proot 进程会因 --kill-on-exit 在 proot 退出时被回收。
 *
 * 超时语义（T76 §37）：
 *  - 超时 → ProotExecutor destroyForcibly + timedOut=true → 状态置 TIMED_OUT（区别
 *    于 FAILED —— Agent 据此重试而非修复环境）。
 *
 * 有界输出（T76 §35）：apt 输出首-N + 尾-M 截断，默认 1 MB。
 */
class UbuntuAptPackageManager(
    private val executor: ProotExecutor,
    private val binaryProvider: PRootBinaryProvider,
    private val rootfsProvider: RootfsProvider,
    private val userHome: GuestUserHome,
    private val hostEnv: PRootHostEnvironment,
    private val workspaces: LinuxWorkspaceManager,
    private val environment: LinuxEnvironmentManager,
    private val lock: PackageOperationLock,
    private val commandBuilder: PRootCommandBuilder = PRootCommandBuilderImpl(),
    private val coordinator: PackageOperationCoordinator = PackageOperationCoordinator(),
    /** apt 操作默认超时（apt update/install 可能较慢）。 */
    private val defaultTimeoutMs: Long = DEFAULT_APT_TIMEOUT_MS,
    /** 输出上限（首 512KB + 尾 512KB）。 */
    private val maxOutputBytes: Long = ProotExecutor.DEFAULT_MAX_OUTPUT_BYTES
) : LinuxPackageManager {

    private val _events = MutableSharedFlow<PackageOperationEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override fun operations(): Flow<PackageOperationEvent> = _events.asSharedFlow()

    // ──────────────────────────────────────────────────────────────────
    // status
    // ──────────────────────────────────────────────────────────────────

    override suspend fun status(): PackageManagerStatus {
        val rootfs = rootfsProvider.current()
            ?: return unavailable("no active rootfs")
        // 探测 apt-get + dpkg 存在性
        val aptProbe = runAptRead(rootfs, listOf("apt-get", "--version"), timeoutMs = 10_000)
        val dpkgProbe = runAptRead(rootfs, listOf("dpkg", "--version"), timeoutMs = 10_000)
        val aptOk = aptProbe != null && aptProbe.exitCode == 0
        val dpkgOk = dpkgProbe != null && dpkgProbe.exitCode == 0
        val available = aptOk && dpkgOk
        val version = aptProbe?.stdout?.lineSequence()?.firstOrNull()?.trim()
        return PackageManagerStatus(
            available = available,
            manager = if (aptOk) "apt-get" else "none",
            version = version,
            databaseState = if (dpkgOk) PackageDatabaseState.HEALTHY else PackageDatabaseState.BROKEN,
            lockState = if (lock.isLocked()) PackageLockState.LOCKED else PackageLockState.FREE,
            metadataState = PackageMetadataState.UNKNOWN,
            brokenPackages = emptyList()
        )
    }

    private fun unavailable(reason: String) = PackageManagerStatus(
        available = false, manager = "none", version = null,
        databaseState = PackageDatabaseState.UNKNOWN,
        lockState = PackageLockState.UNKNOWN,
        metadataState = PackageMetadataState.UNKNOWN,
        brokenPackages = emptyList()
    )

    // ──────────────────────────────────────────────────────────────────
    // update
    // ──────────────────────────────────────────────────────────────────

    override suspend fun update(options: PackageUpdateOptions): PackageOperation {
        val opId = newOpId()
        return runWriteOp(
            opId, PackageOperationType.UPDATE, emptyList(),
            AptCommandBuilder().buildUpdate(), timeoutMs = defaultTimeoutMs
        ) { exec, _ ->
            val installed = emptyList<String>()
            val failed = parseFailedPackages(exec.stderr)
            PackageOperationResult(
                installed = installed,
                durationMs = exec.durationMs,
                exitCode = exec.exitCode,
                operationId = opId,
                state = if (exec.ok) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = exec.stdout,
                stderr = exec.stderr,
                stdoutTruncated = exec.stdoutTruncated,
                stderrTruncated = exec.stderrTruncated,
                maxOutputBytes = maxOutputBytes,
                failedPackages = failed
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // install
    // ──────────────────────────────────────────────────────────────────

    override suspend fun install(
        packages: List<PackageSpec>,
        options: PackageInstallOptions
    ): PackageOperation {
        require(packages.isNotEmpty()) { "PackageError:EmptyInstall — 至少指定一个包" }
        val opId = newOpId()
        val argv = AptCommandBuilder().buildInstall(packages, options)
        return runWriteOp(opId, PackageOperationType.INSTALL, packages, argv, defaultTimeoutMs) { exec, _ ->
            val installed = parseInstalledPackages(exec.stdout)
            val failed = parseFailedPackages(exec.stderr)
            val notFound = parseNotFoundPackages(exec.stderr)
            val alreadySatisfied = packages.map { it.name }.filter { name ->
                name !in installed && name !in failed && name !in notFound
            }
            PackageOperationResult(
                installed = installed,
                alreadySatisfied = alreadySatisfied,
                durationMs = exec.durationMs,
                exitCode = exec.exitCode,
                operationId = opId,
                state = if (exec.ok) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = exec.stdout,
                stderr = exec.stderr,
                stdoutTruncated = exec.stdoutTruncated,
                stderrTruncated = exec.stderrTruncated,
                maxOutputBytes = maxOutputBytes,
                failedPackages = failed + notFound
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // remove
    // ──────────────────────────────────────────────────────────────────

    override suspend fun remove(
        packages: List<PackageSpec>,
        options: PackageRemoveOptions
    ): PackageOperation {
        require(packages.isNotEmpty()) { "PackageError:EmptyRemove — 至少指定一个包" }
        val opId = newOpId()
        val argv = AptCommandBuilder().buildRemove(packages, options)
        return runWriteOp(opId, PackageOperationType.REMOVE, packages, argv, defaultTimeoutMs) { exec, _ ->
            PackageOperationResult(
                removed = packages.map { it.name },
                durationMs = exec.durationMs,
                exitCode = exec.exitCode,
                operationId = opId,
                state = if (exec.ok) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = exec.stdout,
                stderr = exec.stderr,
                stdoutTruncated = exec.stdoutTruncated,
                stderrTruncated = exec.stderrTruncated,
                maxOutputBytes = maxOutputBytes,
                failedPackages = if (exec.ok) emptyList() else packages.map { it.name }
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // upgrade
    // ──────────────────────────────────────────────────────────────────

    override suspend fun upgrade(
        packages: List<PackageSpec>,
        options: PackageUpgradeOptions
    ): PackageOperation {
        val opId = newOpId()
        val argv = AptCommandBuilder().buildUpgrade(packages)
        return runWriteOp(opId, PackageOperationType.UPGRADE, packages, argv, defaultTimeoutMs) { exec, _ ->
            val upgraded = parseInstalledPackages(exec.stdout)  // "Setting up" 行同样标记升级
            PackageOperationResult(
                upgraded = upgraded,
                durationMs = exec.durationMs,
                exitCode = exec.exitCode,
                operationId = opId,
                state = if (exec.ok) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = exec.stdout,
                stderr = exec.stderr,
                stdoutTruncated = exec.stdoutTruncated,
                stderrTruncated = exec.stderrTruncated,
                maxOutputBytes = maxOutputBytes,
                failedPackages = parseFailedPackages(exec.stderr)
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // search (read-only, no lock)
    // ──────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): PackageSearchResult {
        val rootfs = rootfsProvider.current()
            ?: return PackageSearchResult(query, emptyList())
        val argv = AptCommandBuilder().buildSearch(query)
        val exec = runAptRead(rootfs, argv, timeoutMs = 30_000)
            ?: return PackageSearchResult(query, emptyList())
        val results = exec.stdout.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split(" - ", limit = 2)
                PackageInfo(
                    name = parts[0].trim(),
                    version = null,
                    architecture = null,
                    installed = false,
                    candidateVersion = null,
                    description = parts.getOrNull(1)?.trim(),
                    sizeBytes = null
                )
            }.toList()
        return PackageSearchResult(query, results)
    }

    // ──────────────────────────────────────────────────────────────────
    // info (read-only)
    // ──────────────────────────────────────────────────────────────────

    override suspend fun info(packageName: String): PackageInfo {
        val rootfs = rootfsProvider.current()
            ?: return emptyInfo(packageName)
        val argv = AptCommandBuilder().buildInfo(packageName)
        val exec = runAptRead(rootfs, argv, timeoutMs = 30_000)
            ?: return emptyInfo(packageName)
        if (exec.exitCode != 0) return emptyInfo(packageName)
        val text = exec.stdout
        val version = regexFind(text, "Version: (.+)")
        val arch = regexFind(text, "Architecture: (.+)")
        val desc = regexFind(text, "Description: (.+)")
        val size = regexFind(text, "Size: (\\d+)")?.toLongOrNull()
        val installed = isInstalled(packageName)
        return PackageInfo(
            name = packageName,
            version = version,
            architecture = arch,
            installed = installed,
            candidateVersion = version,
            description = desc,
            sizeBytes = size
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // isInstalled / installedVersion (read-only)
    // ──────────────────────────────────────────────────────────────────

    override suspend fun isInstalled(packageName: String): Boolean {
        val rootfs = rootfsProvider.current() ?: return false
        val argv = AptCommandBuilder().buildIsInstalled(packageName)
        val exec = runAptRead(rootfs, argv, timeoutMs = 10_000) ?: return false
        return exec.exitCode == 0 && exec.stdout.contains("install ok installed")
    }

    override suspend fun installedVersion(packageName: String): String? {
        val rootfs = rootfsProvider.current() ?: return null
        // dpkg-query -W -f=${Version} <pkg>
        val argv = listOf("dpkg-query", "-W", "-f=\${Version}", packageName)
        val exec = runAptRead(rootfs, argv, timeoutMs = 10_000) ?: return null
        return if (exec.exitCode == 0) exec.stdout.trim().takeIf { it.isNotEmpty() } else null
    }

    // ──────────────────────────────────────────────────────────────────
    // repair (dpkg --configure -a)
    // ──────────────────────────────────────────────────────────────────

    override suspend fun repair(): PackageOperation {
        val opId = newOpId()
        val argv = listOf("dpkg", "--configure", "-a")
        return runWriteOp(opId, PackageOperationType.REPAIR, emptyList(), argv, defaultTimeoutMs) { exec, _ ->
            PackageOperationResult(
                durationMs = exec.durationMs,
                exitCode = exec.exitCode,
                operationId = opId,
                state = if (exec.ok) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = exec.stdout,
                stderr = exec.stderr,
                stdoutTruncated = exec.stdoutTruncated,
                stderrTruncated = exec.stderrTruncated,
                maxOutputBytes = maxOutputBytes
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 核心：写操作执行框架（lock + env + execute + error mapping + cancel/timeout）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 写操作统一框架：
     *  1. 状态置 QUEUED → RUNNING，emit 事件
     *  2. 获取 [PackageOperationLock]（串行化 apt 写）
     *  3. 构造 proot argv（apt env + 既有 rootfs/workspace/home bind）
     *  4. [ProotExecutor.executeBounded]（有界输出 + 超时强杀）
     *  5. 协程取消 → CancellationException 重抛 → finally 释放锁 + 置 CANCELLED
     *  6. 超时 → 置 TIMED_OUT
     *  7. 失败 → 按 exit code / stderr 映射 [PackageErrorCode]
     */
    private suspend fun runWriteOp(
        opId: String,
        type: PackageOperationType,
        packages: List<PackageSpec>,
        aptArgv: List<String>,
        timeoutMs: Long,
        buildResult: (exec: com.apex.agent.platform.terminal.proot.BoundedExecution, rootfs: RootfsDescriptor) -> PackageOperationResult
    ): PackageOperation {
        val startedAt = System.currentTimeMillis()
        emit(PackageOperationEvent.StateChanged(opId, PackageOperationState.QUEUED, PackageOperationState.RUNNING))
        val rootfs = rootfsProvider.current()
        if (rootfs == null || rootfs.location == null) {
            val err = PackageOperationError(PackageErrorCode.ROOTFS_NOT_READY, "no active rootfs", false)
            emit(PackageOperationEvent.Failed(opId, err))
            return failedOp(opId, type, packages, startedAt, err)
        }
        if (!coordinator.tryAcquireWrite(opId)) {
            val err = PackageOperationError(PackageErrorCode.APT_LOCKED, "another apt operation in progress in-process", true)
            emit(PackageOperationEvent.Failed(opId, err))
            return failedOp(opId, type, packages, startedAt, err)
        }
        try {
            return lock.withLock(rootfs) {
                currentCoroutineContext().ensureActive()
                emit(PackageOperationEvent.Progress(opId, "EXECUTE", aptArgv.joinToString(" ")))
                val exec = runAptBounded(rootfs, aptArgv, timeoutMs)
                currentCoroutineContext().ensureActive()
                val result = buildResult(exec, rootfs)
                val finalState = when {
                    exec.timedOut -> PackageOperationState.TIMED_OUT
                    exec.ok -> PackageOperationState.SUCCEEDED
                    else -> PackageOperationState.FAILED
                }
                val finalResult = result.copy(state = finalState)
                if (finalState == PackageOperationState.SUCCEEDED) {
                    emit(PackageOperationEvent.Completed(opId, finalResult))
                } else {
                    val err = mapExecToError(exec, aptArgv)
                    emit(PackageOperationEvent.Failed(opId, err))
                }
                opOf(opId, type, packages, startedAt, finalState, finalResult, if (finalState == PackageOperationState.SUCCEEDED) null else mapExecToError(exec, aptArgv))
            }
        } catch (ce: CancellationException) {
            emit(PackageOperationEvent.StateChanged(opId, PackageOperationState.RUNNING, PackageOperationState.CANCELLED))
            val err = PackageOperationError(PackageErrorCode.CANCELLED, "operation cancelled", false)
            return cancelledOp(opId, type, packages, startedAt, err)
        } catch (e: Exception) {
            val err = mapExceptionToError(e, aptArgv)
            emit(PackageOperationEvent.Failed(opId, err))
            return failedOp(opId, type, packages, startedAt, err)
        } finally {
            coordinator.releaseWrite(opId)
        }
    }

    /** 经 ProotExecutor 执行一个 apt 子命令（写操作路径，有界输出）。 */
    private suspend fun runAptBounded(
        rootfs: RootfsDescriptor,
        aptArgv: List<String>,
        timeoutMs: Long
    ): com.apex.agent.platform.terminal.proot.BoundedExecution {
        val command = buildProotCommand(rootfs, aptArgv, environment.aptGuestEnv())
        // ProotExecutor.executeBounded 是同步阻塞（ProcessBuilder.waitFor）——
        // 包在 withContext(Dispatchers.IO) 内以免阻塞协程调度器。但本类无 Dispatchers
        // 依赖（保持纯 JVM 可测试）；调用方（TerminalRuntime IO 协程）已处于 IO 上下文。
        return executor.executeBounded(command, timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes)
    }

    /** 读操作路径（无锁，短超时）。返回 null = rootfs 不可用。 */
    private suspend fun runAptRead(
        rootfs: RootfsDescriptor,
        aptArgv: List<String>,
        timeoutMs: Long
    ): com.apex.agent.platform.terminal.proot.BoundedExecution? {
        return try {
            val command = buildProotCommand(rootfs, aptArgv, environment.aptGuestEnv())
            executor.executeBounded(command, timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 构造 proot PRootCommand：apt 子命令作为 guest executable + argv。
     * 复用 [PRootCommandBuilder] 的注入防护（TM6：guestPath/env value 校验）。
     */
    private suspend fun buildProotCommand(
        rootfs: RootfsDescriptor,
        aptArgv: List<String>,
        guestEnv: Map<String, String>
    ): PRootCommand {
        val binaryPath = binaryProvider.locate().getOrElse { e ->
            throw RuntimeException("AptError:PROOT_UNAVAILABLE — ${e.message}", e)
        }
        binaryProvider.verify(binaryPath).getOrElse { e ->
            throw RuntimeException("AptError:PROOT_VERIFY_FAILED — ${e.message}", e)
        }
        val rootfsPath = rootfs.location
            ?: throw RuntimeException("AptError:ROOTFS_NOT_READY — rootfs 无 location")
        // workspace bind：用 default workspace（与 terminal session 共享状态 —— T76 §3）
        val workspaceDir = workspaces.resolve(LinuxWorkspaceManager.DEFAULT_ID).getOrElse { e ->
            throw RuntimeException("AptError:WORKSPACE_UNAVAILABLE — ${e.message}", e)
        }
        // persistent home bind（与 terminal session 共享 /root）
        val homeDir = userHome.ensureReady(File(rootfsPath.value)).getOrElse { e ->
            throw RuntimeException("AptError:HOME_UNAVAILABLE — ${e.message}", e)
        }
        // host env 准备（proot 自身需要）
        hostEnv.prepare().getOrElse { e ->
            throw RuntimeException("AptError:PROOT_HOST_ENV — ${e.message}", e)
        }
        val executable = aptArgv.first()
        val arguments = aptArgv.drop(1)
        val launch = PRootLaunchRequest(
            rootfs = rootfs,
            executable = executable,
            arguments = arguments,
            workingDirectory = WorkspacePath(GUEST_APT_CWD),
            environment = guestEnv,
            binds = listOf(PRootBind(AbsolutePath(homeDir.absolutePath), GuestUserHome.GUEST_PATH)),
            terminalMode = com.apex.agent.platform.terminal.api.TerminalMode.AUTO,
            fakeRoot = true,
            killOnExit = true
        )
        return commandBuilder.build(launch, binaryPath, rootfsPath, AbsolutePath(workspaceDir.absolutePath))
    }

    // ──────────────────────────────────────────────────────────────────
    // 错误映射 + 输出解析
    // ──────────────────────────────────────────────────────────────────

    private fun mapExecToError(
        exec: com.apex.agent.platform.terminal.proot.BoundedExecution,
        aptArgv: List<String>
    ): PackageOperationError {
        if (exec.timedOut) {
            return PackageOperationError(PackageErrorCode.TIMEOUT, "apt timed out after ${exec.durationMs}ms", recoverable = true)
        }
        val stderr = exec.stderr
        return when {
            stderr.contains("Unable to locate package") ->
                PackageOperationError(PackageErrorCode.PACKAGE_NOT_FOUND, stderr.trim(), recoverable = false)
            stderr.contains("Could not get lock") || stderr.contains("lock") ->
                PackageOperationError(PackageErrorCode.APT_LOCKED, stderr.trim(), recoverable = true)
            stderr.contains("dpkg was interrupted") || stderr.contains("dpkg returned an error") ->
                PackageOperationError(PackageErrorCode.DPKG_INTERRUPTED, stderr.trim(), recoverable = true)
            stderr.contains("broken packages") || stderr.contains("Unmet dependencies") ->
                PackageOperationError(PackageErrorCode.DEPENDENCY_CONFLICT, stderr.trim(), recoverable = false)
            stderr.contains("No space left") ->
                PackageOperationError(PackageErrorCode.DISK_FULL, stderr.trim(), recoverable = false)
            stderr.contains("Connection failed") || stderr.contains("Temporary failure") ->
                PackageOperationError(PackageErrorCode.NETWORK_DNS_FAILED, stderr.trim(), recoverable = true)
            stderr.contains("Certificate verification failed") || stderr.contains("TLS") ->
                PackageOperationError(PackageErrorCode.NETWORK_TLS_FAILED, stderr.trim(), recoverable = false)
            exec.exitCode == 100 ->
                PackageOperationError(PackageErrorCode.APT_FAILED, "apt-get exit 100: ${stderr.trim()}", recoverable = true)
            else ->
                PackageOperationError(PackageErrorCode.UNKNOWN, "exit=${exec.exitCode}: ${stderr.trim().take(500)}", recoverable = false)
        }
    }

    private fun mapExceptionToError(e: Throwable, aptArgv: List<String>): PackageOperationError {
        val msg = e.message ?: ""
        return when {
            msg.contains("PROOT_UNAVAILABLE") -> PackageOperationError(PackageErrorCode.PROOT_UNAVAILABLE, msg, false)
            msg.contains("ROOTFS_NOT_READY") -> PackageOperationError(PackageErrorCode.ROOTFS_NOT_READY, msg, false)
            msg.contains("WORKSPACE_UNAVAILABLE") -> PackageOperationError(PackageErrorCode.WORKSPACE_UNAVAILABLE, msg, true)
            msg.contains("HOME_UNAVAILABLE") -> PackageOperationError(PackageErrorCode.HOME_UNAVAILABLE, msg, true)
            else -> PackageOperationError(PackageErrorCode.UNKNOWN, "${e.javaClass.simpleName}: $msg", false)
        }
    }

    /** 解析 apt-get install 输出的 "Setting up <pkg>" / "Unpacking <pkg>" 行。 */
    private fun parseInstalledPackages(stdout: String): List<String> {
        val installed = mutableListOf<String>()
        val settingUp = Regex("Setting up (\\S+)").findAll(stdout)
        for (m in settingUp) {
            installed.add(m.groupValues[1])
        }
        return installed.distinct()
    }

    /** 解析 stderr 中失败的包名（E: / dpkg error processing package X）。 */
    private fun parseFailedPackages(stderr: String): List<String> {
        val failed = mutableListOf<String>()
        // "error processing package X (--configure)"
        Regex("error processing package (\\S+)").findAll(stderr).forEach { failed.add(it.groupValues[1]) }
        // "E: Sub-process returned an error code" 无包名 —— 忽略
        return failed.distinct()
    }

    /** 解析 "E: Unable to locate package X"。 */
    private fun parseNotFoundPackages(stderr: String): List<String> {
        return Regex("Unable to locate package (\\S+)").findAll(stderr)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    private fun regexFind(text: String, pattern: String): String? {
        val m = Regex(pattern).find(text)
        return m?.groupValues?.getOrNull(1)?.trim()
    }

    private fun emptyInfo(name: String) = PackageInfo(
        name = name, version = null, architecture = null,
        installed = false, candidateVersion = null, description = null, sizeBytes = null
    )

    // ──────────────────────────────────────────────────────────────────
    // op 构造 + 事件
    // ──────────────────────────────────────────────────────────────────

    private fun newOpId(): String = "apt-${UUID.randomUUID().toString().take(8)}"

    private fun emit(event: PackageOperationEvent) {
        _events.tryEmit(event)
    }

    private fun opOf(
        opId: String, type: PackageOperationType, packages: List<PackageSpec>,
        startedAt: Long, state: PackageOperationState,
        result: PackageOperationResult?, error: PackageOperationError?
    ): PackageOperation {
        return PackageOperation(
            id = opId, type = type, state = state,
            requestedPackages = packages,
            startedAt = startedAt, finishedAt = System.currentTimeMillis(),
            exitCode = result?.exitCode, result = result, error = error
        )
    }

    private fun failedOp(
        opId: String, type: PackageOperationType, packages: List<PackageSpec>,
        startedAt: Long, error: PackageOperationError
    ): PackageOperation = opOf(
        opId, type, packages, startedAt, PackageOperationState.FAILED,
        result = null, error = error
    )

    private fun cancelledOp(
        opId: String, type: PackageOperationType, packages: List<PackageSpec>,
        startedAt: Long, error: PackageOperationError
    ): PackageOperation = opOf(
        opId, type, packages, startedAt, PackageOperationState.CANCELLED,
        result = null, error = error
    )

    companion object {
        /** apt 操作默认超时（apt update/install 在慢网络下可能数分钟）。 */
        const val DEFAULT_APT_TIMEOUT_MS: Long = 180_000L
        /** apt 操作的 guest cwd（/root —— 持久 home，可写）。 */
        const val GUEST_APT_CWD = "/root"
    }
}
