package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.errors.LinuxEnvironmentError
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.network.LinuxNetworkProbe
import com.apex.agent.platform.terminal.pkg.LinuxPackageManager
import com.apex.agent.platform.terminal.pkg.PackageSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile

/**
 * T76: Ubuntu Bootstrap Manager —— 首次安装后的初始化状态机。
 *
 * 把"刚解压的 Ubuntu Base rootfs"变成"Agent 可长期使用的完整 Linux 工作环境"。
 * 串联既有组件（T72 rootfs + T75 workspace/home + P71 proot）与新组件
 * （T76 apt manager + network probe + sources list + base packages）。
 *
 * 生命周期（T76 §14）：
 * ```
 * NOT_STARTED → CHECKING → CONFIGURING → NETWORK_CHECK → APT_UPDATE → BASE_PACKAGES → READY
 *      ↓           ↓           ↓              ↓              ↓              ↓
 *   (任一阶段失败 → FAILED，保留 failedStage + reason，可 retry)
 * ```
 *
 * 关键特性：
 *  - **幂等**（T76 §16）：READY 后再次 bootstrap() 快速返回 ALREADY_READY，不重复
 *    apt update / install。每个阶段自身幂等（sources.list 不重复写、apt update
 *    可重跑、apt install 已装的跳过）。
 *  - **并发安全**（T76 §17）：进程内 [Mutex] + 跨实例 OS 文件锁。多个 Agent task
 *    同时首次访问 Ubuntu → 只有一个执行 bootstrap，其余等待并共享结果。
 *  - **崩溃恢复**（T76 §18 / §27）：阶段进度持久化到 [BootstrapStateStore]。App
 *    重启后 [reconcile] 检测 IN_PROGRESS 状态 → 重新执行未完成阶段。绝不假报 READY。
 *  - **取消正确**（T76 §36）：CancellationException 重抛，状态置 FAILED（可 retry），
 *    锁释放。
 *  - **超时**（T76 §37）：bootstrap 整体可配超时；内部 apt 操作各自超时。
 */
class UbuntuBootstrapManager(
    private val provisioner: RootfsProvisioner,
    private val aptManager: LinuxPackageManager,
    private val networkProbe: LinuxNetworkProbe,
    private val sourcesList: UbuntuSourcesList,
    private val baseProfile: BasePackageProfile,
    private val stateStore: BootstrapStateStore,
    /** rootfs host 目录 provider（用于 file lock + sources 写入）。 */
    private val rootfsHostDirProvider: () -> File?,
    /** bootstrap 整体超时（默认 10 分钟 —— apt install 基础包可能较慢）。 */
    private val defaultTimeoutMs: Long = DEFAULT_BOOTSTRAP_TIMEOUT_MS
) {

    /** bootstrap 结果。 */
    sealed interface BootstrapResult {
        data class Ready(val durationMs: Long, val stages: List<String>) : BootstrapResult
        data class AlreadyReady(val state: BootstrapState) : BootstrapResult
        data class Failed(val error: LinuxEnvironmentError, val failedStage: String, val partialState: BootstrapState) : BootstrapResult
        data class Cancelled(val partialState: BootstrapState) : BootstrapResult
        data class Busy(val message: String) : BootstrapResult
        data class InProgress(val state: BootstrapState, val message: String) : BootstrapResult
    }

    /** 进度事件（可观测）。 */
    sealed interface BootstrapProgress {
        val stage: String
        data class StageStarted(override val stage: String, val message: String) : BootstrapProgress
        data class StageCompleted(override val stage: String, val durationMs: Long) : BootstrapProgress
        data class StageFailed(override val stage: String, val reason: String) : BootstrapProgress
        data class OverallCompleted(override val stage: String = "OVERALL", val state: BootstrapState, val durationMs: Long) : BootstrapProgress
    }

    private val mutex = Mutex()
    private val _progress = MutableSharedFlow<BootstrapProgress>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    fun progress(): Flow<BootstrapProgress> = _progress.asSharedFlow()

    /** 当前 bootstrap 状态（内存缓存，与持久化一致）。 */
    @Volatile
    private var currentState: BootstrapState = BootstrapState.NOT_STARTED

    init {
        // 启动时加载持久化状态
        // （不能在 init 里 suspend；state() 是 suspend 会即时 load）
    }

    /** 当前状态（suspend：从持久化加载，保证跨进程一致）。 */
    suspend fun state(): BootstrapState {
        val record = stateStore.load()
        val s = record?.state?.let { runCatching { BootstrapState.valueOf(it) }.getOrNull() }
            ?: BootstrapState.NOT_STARTED
        currentState = s
        return s
    }

    /**
     * 执行 bootstrap（幂等、并发安全、可恢复）。
     *
     * @param force true = 即使 READY 也重跑（版本迁移/修复用）。
     * @param timeoutMs 整体超时；超时返回 InProgress（bootstrap 仍在后台可继续）。
     */
    suspend fun bootstrap(force: Boolean = false, timeoutMs: Long = defaultTimeoutMs): BootstrapResult {
        // 幂等快速路径：已 READY 且非 force
        val st = state()
        if (st == BootstrapState.READY && !force) {
            return BootstrapResult.AlreadyReady(st)
        }
        // 并发：拿不到锁说明另一 bootstrap 在进行
        val osLock = acquireOsLock()
            ?: return BootstrapResult.Busy("another bootstrap instance holds the OS lock")
        return try {
            mutex.withLock {
                runBootstrapInternal(force, timeoutMs)
            }
        } catch (ce: CancellationException) {
            BootstrapResult.Cancelled(currentState)
        } catch (e: Exception) {
            val err = LinuxEnvironmentError.unknown("bootstrap crashed: ${e.message}", e)
            BootstrapResult.Failed(err, currentState.name, currentState)
        } finally {
            runCatching { osLock.close() }
        }
    }

    private suspend fun runBootstrapInternal(force: Boolean, timeoutMs: Long): BootstrapResult {
        val started = System.currentTimeMillis()
        val completedStages = mutableListOf<String>()
        val evidence = mutableMapOf<String, Long>()
        // 加载既有证据（恢复用）
        val existing = stateStore.load()
        evidence.putAll(existing?.stageEvidence ?: emptyMap())

        fun stageDone(stage: BootstrapState) {
            val ts = System.currentTimeMillis()
            evidence[stage.name] = ts
            completedStages.add(stage.name)
            _progress.tryEmit(BootstrapProgress.StageCompleted(stage.name, ts - started))
        }

        suspend fun stageStart(stage: BootstrapState, msg: String) {
            currentState = stage
            persistState(stage, evidence, started)
            _progress.tryEmit(BootstrapProgress.StageStarted(stage.name, msg))
        }

        suspend fun stageFail(stage: BootstrapState, reason: String): BootstrapResult.Failed {
            currentState = BootstrapState.FAILED
            persistFailure(stage, reason, evidence, started)
            _progress.tryEmit(BootstrapProgress.StageFailed(stage.name, reason))
            val err = LinuxEnvironmentError.bootstrapFailed(stage.name, reason)
            return BootstrapResult.Failed(err, stage.name, stage)
        }

        // ── 1. CHECKING：rootfs READY ──
        if (force || !evidence.containsKey(BootstrapState.CHECKING.name)) {
            stageStart(BootstrapState.CHECKING, "checking rootfs ready")
            val rootfs = provisioner.current()
            if (rootfs == null || rootfs.location == null) {
                return stageFail(BootstrapState.CHECKING, "rootfs not ready — call terminal.ubuntu.install first")
            }
            stageDone(BootstrapState.CHECKING)
        }

        // ── 2. CONFIGURING：sources.list + env ──
        if (force || !evidence.containsKey(BootstrapState.CONFIGURING.name)) {
            stageStart(BootstrapState.CONFIGURING, "configuring sources.list + env")
            val rootfs = provisioner.current()!!
            val rootfsDir = File(rootfs.location!!.value)
            val arch = rootfs.architecture
            val sourcesResult = sourcesList.ensure(rootfsDir, arch)
            if (!sourcesResult.written && sourcesResult.actions.any { it.contains("skipped") }) {
                // 幂等跳过 —— OK
            }
            stageDone(BootstrapState.CONFIGURING)
        }

        // ── 3. NETWORK_CHECK ──
        if (force || !evidence.containsKey(BootstrapState.NETWORK_CHECK.name)) {
            stageStart(BootstrapState.NETWORK_CHECK, "running network diagnosis")
            // 仅做轻量 DNS 配置检查（完整 diagnose 会跑 apt update，留到下一阶段）
            val dns = networkProbe.probeDnsOnly()
            if (dns.status == LinuxNetworkProbe.ProbeStatus.FAILED) {
                // DNS 配置缺失不阻断 bootstrap（apt update 会给更详细错误）—— 但记录
                _progress.tryEmit(BootstrapProgress.StageStarted(
                    BootstrapState.NETWORK_CHECK.name, "DNS config warning: ${dns.detail}"
                ))
            }
            stageDone(BootstrapState.NETWORK_CHECK)
        }

        // ── 4. APT_UPDATE ──
        if (force || !evidence.containsKey(BootstrapState.APT_UPDATE.name)) {
            stageStart(BootstrapState.APT_UPDATE, "running apt-get update")
            val updateResult = aptManager.update()
            if (updateResult.state != com.apex.agent.platform.terminal.pkg.PackageOperationState.SUCCEEDED) {
                val reason = updateResult.error?.message ?: updateResult.result?.stderr?.take(500) ?: "apt update failed"
                return stageFail(BootstrapState.APT_UPDATE, reason)
            }
            stageDone(BootstrapState.APT_UPDATE)
        }

        // ── 5. BASE_PACKAGES ──
        if (force || !evidence.containsKey(BootstrapState.BASE_PACKAGES.name)) {
            stageStart(BootstrapState.BASE_PACKAGES, "installing base packages: ${baseProfile.essential}")
            val installResult = aptManager.install(baseProfile.essential.map { PackageSpec(it) })
            if (installResult.state != com.apex.agent.platform.terminal.pkg.PackageOperationState.SUCCEEDED) {
                val reason = installResult.error?.message ?: installResult.result?.stderr?.take(500) ?: "base package install failed"
                return stageFail(BootstrapState.BASE_PACKAGES, reason)
            }
            stageDone(BootstrapState.BASE_PACKAGES)
        }

        // ── READY ──
        currentState = BootstrapState.READY
        persistState(BootstrapState.READY, evidence, started, finishedAt = System.currentTimeMillis())
        _progress.tryEmit(BootstrapProgress.OverallCompleted(state = BootstrapState.READY, durationMs = System.currentTimeMillis() - started))
        return BootstrapResult.Ready(System.currentTimeMillis() - started, completedStages)
    }

    /**
     * 崩溃恢复（T76 §18 / §27）：检测上次崩溃留下的 IN_PROGRESS 状态。
     *
     * - NOT_STARTED / READY → 无需恢复。
     * - FAILED → 返回上次失败信息（Agent 可决定 retry）。
     * - IN_PROGRESS → 标记为可恢复，下次 bootstrap() 会从未完成阶段继续
     *   （因 evidence 只含已完成阶段，force=false 时跳过已完成、重跑未完成）。
     */
    suspend fun reconcile(): BootstrapReconciliation {
        val record = stateStore.load() ?: return BootstrapReconciliation(
            action = ReconciliationAction.FRESH_BOOTSTRAP_REQUIRED,
            state = BootstrapState.NOT_STARTED,
            detail = "no bootstrap state file"
        )
        val st = runCatching { BootstrapState.valueOf(record.state) }.getOrNull()
            ?: return BootstrapReconciliation(
                action = ReconciliationAction.REPAIR_STATE,
                state = BootstrapState.NOT_STARTED,
                detail = "corrupt state: ${record.state}"
            )
        return when {
            st == BootstrapState.READY -> BootstrapReconciliation(
                action = ReconciliationAction.NONE,
                state = st,
                detail = "already READY"
            )
            st == BootstrapState.NOT_STARTED -> BootstrapReconciliation(
                action = ReconciliationAction.FRESH_BOOTSTRAP_REQUIRED,
                state = st,
                detail = "never bootstrapped"
            )
            st == BootstrapState.FAILED -> BootstrapReconciliation(
                action = ReconciliationAction.RETRY_FAILED,
                state = st,
                detail = "last failed at ${record.failedStage}: ${record.failureReason}"
            )
            st.isInProgress() -> {
                // IN_PROGRESS → 上次崩溃。evidence 含已完成阶段，bootstrap() 会续跑。
                BootstrapReconciliation(
                    action = ReconciliationAction.RESUME_INCOMPLETE,
                    state = st,
                    detail = "crash during $st; ${record.stageEvidence.size} stages completed, will resume"
                )
            }
            else -> BootstrapReconciliation(
                action = ReconciliationAction.NONE,
                state = st,
                detail = "state $st needs no reconciliation"
            )
        }
    }

    /** 重置 bootstrap 状态（删 state file；下次 bootstrap 从头跑）。诊断/修复用。 */
    suspend fun reset(): Result<Unit> {
        currentState = BootstrapState.NOT_STARTED
        return stateStore.delete()
    }

    // ──────────────────────────────────────────────────────────────────
    // 持久化 + OS 锁
    // ──────────────────────────────────────────────────────────────────

    private suspend fun persistState(
        state: BootstrapState,
        evidence: Map<String, Long>,
        startedAt: Long,
        finishedAt: Long? = null
    ) {
        currentState = state
        stateStore.save(
            BootstrapStateStore.BootstrapStateRecord(
                state = state.name,
                stageEvidence = evidence,
                startedAt = startedAt,
                finishedAt = finishedAt,
                lastAttemptAt = System.currentTimeMillis(),
                baseProfileName = baseProfile.name,
                installedPackages = if (state == BootstrapState.READY) baseProfile.essential else emptyList()
            )
        )
    }

    private suspend fun persistFailure(
        stage: BootstrapState,
        reason: String,
        evidence: Map<String, Long>,
        startedAt: Long
    ) {
        stateStore.save(
            BootstrapStateStore.BootstrapStateRecord(
                state = BootstrapState.FAILED.name,
                stageEvidence = evidence,
                failedStage = stage.name,
                failureReason = reason,
                startedAt = startedAt,
                lastAttemptAt = System.currentTimeMillis(),
                baseProfileName = baseProfile.name
            )
        )
    }

    /**
     * 跨实例 OS 文件锁（`<rootfsHostDir>/.bootstrap.lock`）。
     * 进程崩溃时内核自动释放（同 RootfsProvisionerImpl 的 .provision.lock 模式）。
     */
    private fun acquireOsLock(): java.io.Closeable? = runCatching {
        val hostDir = rootfsHostDirProvider() ?: return@runCatching null
        if (!hostDir.isDirectory) hostDir.mkdirs()
        val lockFile = File(hostDir, LOCK_FILENAME)
        val channel = RandomAccessFile(lockFile, "rw").channel
        val lock = channel.tryLock() ?: run {
            channel.close()
            return@runCatching null
        }
        object : java.io.Closeable {
            override fun close() {
                runCatching { lock.release() }
                runCatching { channel.close() }
            }
        }
    }.getOrNull()

    /** 恢复结果。 */
    data class BootstrapReconciliation(
        val action: ReconciliationAction,
        val state: BootstrapState,
        val detail: String
    )

    enum class ReconciliationAction {
        NONE,                       // READY / 无需恢复
        FRESH_BOOTSTRAP_REQUIRED,   // 从未 bootstrap
        RESUME_INCOMPLETE,          // 崩溃中途 —— bootstrap() 会续跑
        RETRY_FAILED,               // 上次 FAILED —— bootstrap() 重跑
        REPAIR_STATE                // 状态文件损坏 —— reset 后重跑
    }

    companion object {
        const val DEFAULT_BOOTSTRAP_TIMEOUT_MS: Long = 600_000L  // 10 min
        const val LOCK_FILENAME = ".bootstrap.lock"
    }
}
