package com.apex.agent.platform.terminal.ubuntu.lifecycle

import com.apex.agent.platform.terminal.ubuntu.ProvisioningResult
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisioner
import com.apex.agent.platform.terminal.ubuntu.RootfsTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T82: Ubuntu 产品级生命周期编排器（Product-Level Ubuntu Lifecycle Orchestrator）。
 *
 * ## 问题（T82 Phase 0 审计结论）
 * T72/T73/T75/T76/T81 已交付完整的 Ubuntu 安装基础设施（真实下载 + SHA-256 +
 * 原子解压 + bootstrap + apt + capability probe + repair），但产品层是断的：
 *   - ApexApp 启动不触发任何 Ubuntu 生命周期；
 *   - Terminal UI 的 `ensureSession()` 永远走 local backend；
 *   - DepCatalog 的 apt 命令被投进 Android shell（必然 command not found）；
 *   - Agent 需要**三次**工具调用（terminal.ubuntu.install → terminal.linux.bootstrap
 *     → terminal.linux.capabilities）才能把 Ubuntu 拉到可用，还要自己解读三套状态机。
 *
 * ## 本类职责（编排，不重复实现）
 * 把两阶段初始化合成一个产品级入口：
 *
 * ```text
 * NOT_INSTALLED → INSTALLING(rootfs) → ROOTFS_READY → BOOTSTRAPPING(apt) → READY
 *                                      ↘ FAILED(stage, retryable)  ↙ RECOVERING(reconcile/repair)
 * ```
 *
 * - [ensureReady]：幂等单飞编排 install → bootstrap → capability 快照；
 * - [warmUp]：App 启动恢复（reconcile + 状态派生，**绝不下载**）；
 * - [refreshState]：从底层实时派生（不复制 rootfs/bootstrap 状态语义）；
 * - [stateFlow]/[progressFlow]：UI/Agent 可订阅；
 * - [repair]：透传 EnvironmentRepairService（单轮 detect→repair→verify）。
 *
 * ## 设计约束（继承 T81 禁令）
 * - 状态**派生**而非复制：`LifecycleState.rootfsState/bootstrapState` 直接取底层
 *   组件的当前值；phase 是编排层合成视图（两阶段机器的乘积），单一事实源仍在
 *   RootfsProvisioner / UbuntuBootstrapManager。
 * - bootstrap/probe/repair 以函数端口注入（生产 DI 适配真实单例；JVM 测试注入
 *   fake）——不新建第二套 TerminalRuntime/Provisioner/PackageManager 抽象。
 * - 超时=IN_PROGRESS：下载断点续传（T72 Range）+ bootstrap stageEvidence 续跑，
 *   进度永不丢失；绝不靠吞异常伪造成功。
 * - CancellationException 透传（不吞协程取消）。
 */
class UbuntuLifecycleCoordinator(
    private val provisioner: RootfsProvisioner,
    /** bootstrap 端口（生产：UbuntuBootstrapManager.bootstrap 的适配）。 */
    private val bootstrapFn: suspend (force: Boolean, timeoutMs: Long) -> BootstrapStageResult,
    /** bootstrap 当前状态名端口（生产：UbuntuBootstrapManager.state().name）。 */
    private val bootstrapStateFn: suspend () -> String?,
    /** bootstrap 进度流端口（生产：UbuntuBootstrapManager.progress() 的适配）。 */
    private val bootstrapProgressFn: () -> Flow<BootstrapProgressEvent> = { emptyFlow() },
    /** capability 探测端口（生产：LinuxCapabilityProbe.probeAll() 的适配）。 */
    private val probeFn: suspend () -> List<CapabilityEntry> = { emptyList() },
    /** 自动修复端口（生产：EnvironmentRepairService.autoRepair() 的适配；null=未接线）。 */
    private val repairFn: (suspend () -> RepairOutcome)? = null,
    private val target: RootfsTarget,
    private val defaultTimeoutMs: Long = DEFAULT_ENSURE_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    // ─────────────────────────── 产品级状态机 ───────────────────────────

    /**
     * 编排层 phase（两阶段机器的合成视图）：
     * - [NOT_INSTALLED]：rootfs 未安装（底层 current() == null）；
     * - [INSTALLING]：rootfs 安装进行中（下载/校验/解压/激活）；
     * - [ROOTFS_READY]：rootfs 就绪，bootstrap 未完成（未开始/中断）；
     * - [BOOTSTRAPPING]：bootstrap 进行中（sources/network/apt-update/base-packages）；
     * - [READY]：rootfs + bootstrap 双就绪，附 capability 快照；
     * - [RECOVERING]：crash/损坏恢复中（reconcile/repair）；
     * - [FAILED]：最近一次 ensure 失败（[LifecycleState.failedStage] 定位）。
     */
    enum class Phase { NOT_INSTALLED, INSTALLING, ROOTFS_READY, BOOTSTRAPPING, READY, RECOVERING, FAILED }

    /** 失败发生的产品级阶段（Agent 据此决定 retry / repair / ask）。 */
    enum class Stage { INSTALL, BOOTSTRAP, PROBE, RECOVER, REPAIR }

    data class LifecycleState(
        val phase: Phase,
        /** ProvisioningState.name（实时派生，编排层不做判定）。 */
        val rootfsState: String?,
        /** BootstrapState.name（实时派生）。 */
        val bootstrapState: String?,
        val failedStage: String?,
        val lastError: String?,
        val retryable: Boolean,
        val lastReadyAt: Long?,
        /** READY 时的 capability 快照（派生缓存，非 READY 时为 null）。 */
        val capabilities: List<CapabilityEntry>?
    ) {
        val ready: Boolean get() = phase == Phase.READY
    }

    /** capability 单项（LinuxCapabilityProbe.CapabilityReport 的生命周期视图）。 */
    data class CapabilityEntry(
        val name: String,
        val status: String,
        val version: String? = null,
        val aptPackage: String? = null,
        val detail: String? = null
    )

    /** bootstrap 阶段归一化结果（UbuntuBootstrapManager.BootstrapResult 的生命周期视图）。 */
    enum class BootstrapOutcome { READY, ALREADY_READY, IN_PROGRESS, FAILED, CANCELLED, BUSY }

    data class BootstrapStageResult(
        val outcome: BootstrapOutcome,
        val state: String?,
        val failedStage: String? = null,
        val error: String? = null
    )

    /** bootstrap 进度事件（BootstrapProgress 的生命周期视图）。 */
    data class BootstrapProgressEvent(
        val stage: String,
        val message: String,
        val timestampMs: Long = 0L
    )

    /** EnvironmentRepairService.RepairReport 的生命周期视图。 */
    data class RepairOutcome(
        val actions: List<String>,
        val verifiedHealthy: Boolean,
        val detail: String? = null
    )

    // ─────────────────────────── ensureReady 结果 ───────────────────────────

    sealed interface EnsureResult {
        data class Ready(
            val durationMs: Long,
            val capabilities: List<CapabilityEntry>,
            val fromPhase: Phase,
            /** probe 阶段降级（环境 READY 但 capability 快照不可用）。 */
            val probeDegraded: Boolean,
            val probeError: String?
        ) : EnsureResult

        data class AlreadyReady(val capabilities: List<CapabilityEntry>) : EnsureResult
        data class InProgress(val phase: Phase, val message: String) : EnsureResult
        data class Failed(
            val stage: Stage,
            val message: String,
            val retryable: Boolean,
            val phase: Phase,
            val rootfsState: String?,
            val bootstrapState: String?
        ) : EnsureResult

        data class Cancelled(val phase: Phase) : EnsureResult
    }

    /** 统一进度事件（install + bootstrap 两流聚合）。 */
    data class LifecycleProgress(
        val stage: String,
        val message: String,
        val percent: Int = 0,
        val bytesTransferred: Long = 0,
        val bytesTotal: Long? = null,
        val timestampMs: Long
    )

    /** warmUp / crash 恢复报告。 */
    data class ReconciliationReport(
        val action: String,
        val staleStaging: Boolean,
        val orphanedTempFiles: List<String>,
        val phaseAfter: Phase
    )

    data class CancelOutcome(
        val cancelled: Boolean,
        val phase: Phase,
        val message: String
    )

    // ─────────────────────────── 状态 ───────────────────────────

    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        LifecycleState(
            phase = Phase.NOT_INSTALLED,
            rootfsState = null,
            bootstrapState = null,
            failedStage = null,
            lastError = null,
            retryable = false,
            lastReadyAt = null,
            capabilities = null
        )
    )

    /** 编排层状态流（UI/Agent 订阅；每次 phase 转移即时发射）。 */
    val stateFlow: StateFlow<LifecycleState> = _state.asStateFlow()

    @Volatile
    private var lastReadyAt: Long? = null

    @Volatile
    private var lastFailure: Pair<Stage, String>? = null

    // ─────────────────────────── 核心入口 ───────────────────────────

    /**
     * 幂等把 Ubuntu 拉到 READY（install → bootstrap → capability 快照）。
     *
     * - 单飞：并发调用只跑一次编排（Mutex），其余等待并共享结果；
     * - 幂等：底层已 READY → [EnsureResult.AlreadyReady]（不触碰底层）；
     * - 超时 → [EnsureResult.InProgress]：进度不丢（下载断点续传 + bootstrap
     *   evidence 续跑），再次调用续跑；
     * - [force]=true：绕过 READY 短路（版本迁移/修复用）。
     */
    suspend fun ensureReady(force: Boolean = false, timeoutMs: Long = defaultTimeoutMs): EnsureResult = mutex.withLock {
        // 快速路径：已 READY 且非 force —— 不触碰底层（秒回）。
        if (!force && _state.value.phase == Phase.READY) {
            return EnsureResult.AlreadyReady(_state.value.capabilities ?: emptyList())
        }
        val startedAt = clock()
        val fromPhase = _state.value.phase
        val result = withTimeoutOrNull(timeoutMs) { runEnsureSteps(force, startedAt, fromPhase) }
        result ?: EnsureResult.InProgress(
            phase = _state.value.phase,
            message = "仍在 ${_state.value.phase.name} 阶段 — 再次调用继续等待，进度不会丢失"
        )
    }

    private suspend fun runEnsureSteps(force: Boolean, startedAt: Long, fromPhase: Phase): EnsureResult {
        // ── Stage 1: rootfs（幂等判断由 provisioner 承担 —— 编排层不复制 rootfs 状态语义）──
        setPhase(Phase.INSTALLING)
        // 契约防御：provisioner 契约是返回 ProvisioningResult；抛异常属契约破坏 ——
        // 归一为结构化 Failed（信息保留，非吞错；与 BootstrapManager 的 bootstrap
        // 异常处理模式一致）。CancellationException（含超时/调用方取消）始终透传。
        val installResult = try {
            provisioner.install(target, force)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return markFailed(Stage.INSTALL, "install crashed: ${e.message}", retryable = true)
        }
        when (installResult) {
            is ProvisioningResult.Ready, is ProvisioningResult.AlreadyReady -> Unit
            is ProvisioningResult.Failed -> {
                return markFailed(
                    Stage.INSTALL,
                    "${installResult.error.code}: ${installResult.error.message}",
                    installResult.error.recoverable
                )
            }
            is ProvisioningResult.Cancelled -> {
                return EnsureResult.Cancelled(_state.value.phase)
            }
            is ProvisioningResult.Busy -> {
                return EnsureResult.InProgress(
                    phase = Phase.INSTALLING,
                    message = "另一 rootfs 安装正在进程内进行（${installResult.message}）— 稍后重试 ensure"
                )
            }
            else -> {
                // Removed/Invalidated 等非安装语义结果 —— 诚实上报为 install 阶段异常。
                return markFailed(Stage.INSTALL, "unexpected install result: $installResult", retryable = true)
            }
        }

        // ── Stage 2: bootstrap（sources → network → apt update → base packages）──
        setPhase(Phase.BOOTSTRAPPING)
        val br = try {
            bootstrapFn(force, defaultTimeoutMs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return markFailed(Stage.BOOTSTRAP, "bootstrap crashed: ${e.message}", retryable = true)
        }
        when (br.outcome) {
            BootstrapOutcome.READY, BootstrapOutcome.ALREADY_READY -> Unit
            BootstrapOutcome.IN_PROGRESS -> {
                return EnsureResult.InProgress(
                    phase = Phase.BOOTSTRAPPING,
                    message = "bootstrap 仍在进行（state=${br.state}）— 再次调用继续等待"
                )
            }
            BootstrapOutcome.FAILED -> {
                return markFailed(
                    Stage.BOOTSTRAP,
                    "${br.error ?: "bootstrap failed"}（failedStage=${br.failedStage}）",
                    retryable = true
                )
            }
            BootstrapOutcome.CANCELLED, BootstrapOutcome.BUSY -> {
                // 非终态 —— 上报进行中语义（诚实，不伪造失败）。
                return EnsureResult.InProgress(
                    phase = Phase.BOOTSTRAPPING,
                    message = "bootstrap ${br.outcome.name}（state=${br.state}）— ${br.error ?: "稍后重试 ensure"}"
                )
            }
        }

        // ── Stage 3: capability 快照（诊断性 —— 探测失败不否定 READY）──
        // Kotlin try/catch 的 definite assignment 规则禁止 val 双路径赋值 → var 局部。
        var caps: List<CapabilityEntry> = emptyList()
        var probeDegraded = false
        var probeError: String? = null
        try {
            caps = probeFn()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 环境已 READY；capability 快照是附加诊断（T81 §29：探测不可用 ≠ 环境不可用）。
            probeDegraded = true
            probeError = e.message ?: e::class.java.simpleName
        }

        lastReadyAt = clock()
        lastFailure = null
        setPhase(Phase.READY, capabilities = caps)
        return EnsureResult.Ready(
            durationMs = (clock() - startedAt).coerceAtLeast(0L),
            capabilities = caps,
            fromPhase = fromPhase,
            probeDegraded = probeDegraded,
            probeError = probeError
        )
    }

    /**
     * App 启动恢复入口：reconcile rootfs 现场 + 派生当前 phase。
     * **绝不下载/安装** —— 只做崩溃后的一致性收敛（stale staging 清理、孤儿 temp
     * 清理、metadata 修复）。产品语义：启动时"知道 Ubuntu 在不在/健康不健康"，
     * 但不替用户决定下载。
     */
    suspend fun warmUp(): ReconciliationReport {
        setPhase(Phase.RECOVERING)
        val rec = try {
            provisioner.reconcile()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // reconcile 失败不阻断状态派生（诚实记录，refreshState 兜底）。
            lastFailure = Stage.RECOVER to "reconcile failed: ${e.message}"
            null
        }
        refreshState()
        return ReconciliationReport(
            action = rec?.action?.name ?: "RECONCILE_FAILED",
            staleStaging = rec?.staleStaging ?: false,
            orphanedTempFiles = rec?.orphanedTempFiles ?: emptyList(),
            phaseAfter = _state.value.phase
        )
    }

    /**
     * 从底层实时派生 [LifecycleState]（单一事实源：RootfsProvisioner /
     * UbuntuBootstrapManager；编排层只合成 phase，不复制判定逻辑）。
     */
    suspend fun refreshState(): LifecycleState {
        val rootfs = try {
            provisioner.current()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            null
        }
        val rootfsStateName = try {
            provisioner.state().name
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            null
        }
        val bootState = try {
            bootstrapStateFn()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            null
        }
        val failure = lastFailure
        val phase = derivePhase(rootfs != null, rootfsStateName, bootState, _state.value.phase)
        val state = LifecycleState(
            phase = phase,
            rootfsState = rootfsStateName,
            bootstrapState = bootState,
            failedStage = failure?.first?.name,
            lastError = failure?.second,
            retryable = failure != null,
            lastReadyAt = lastReadyAt,
            capabilities = if (phase == Phase.READY) _state.value.capabilities else null
        )
        _state.value = state
        return state
    }

    /**
     * phase 合成规则（纯函数 —— 可矩阵测试）。
     * 输入是底层事实（rootfs 存在性 / ProvisioningState / BootstrapState），
     * 输出编排视图。RECOVERING 是过程态（仅 setPhase 与 refreshState 之间存在），
     * refreshState 一律派生事实终态 —— warmUp/repair 结束后不残留 RECOVERING。
     */
    internal fun derivePhase(
        hasRootfs: Boolean,
        rootfsStateName: String?,
        bootstrapStateName: String?,
        @Suppress("UNUSED_PARAMETER") previous: Phase
    ): Phase = when {
        !hasRootfs -> Phase.NOT_INSTALLED
        rootfsStateName in INSTALL_IN_PROGRESS_STATES -> Phase.INSTALLING
        bootstrapStateName == "READY" -> Phase.READY
        bootstrapStateName in BOOTSTRAP_IN_PROGRESS_STATES -> Phase.BOOTSTRAPPING
        bootstrapStateName == "FAILED" && previous == Phase.FAILED -> Phase.FAILED
        else -> Phase.ROOTFS_READY // rootfs 在，bootstrap NOT_STARTED/FAILED/中断
    }

    /**
     * 取消当前进行中的 rootfs 安装（install 阶段专用真取消 —— 下载字节保留，
     * 下次断点续传）。bootstrap 阶段的取消语义 = 取消调用协程（BootstrapManager
     * 内部处理 CancellationException）—— 此处如实报告不支持。
     */
    suspend fun cancelInstall(): CancelOutcome {
        val phase = _state.value.phase
        return when (phase) {
            Phase.INSTALLING -> {
                val r = try {
                    provisioner.cancel()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    return CancelOutcome(false, phase, "cancel failed: ${e.message}")
                }
                if (r.isSuccess) {
                    refreshState()
                    CancelOutcome(true, Phase.INSTALLING, "install cancelled — downloaded bytes preserved for resume")
                } else {
                    CancelOutcome(false, phase, "cancel rejected: ${r.exceptionOrNull()?.message}")
                }
            }
            else -> CancelOutcome(
                false,
                phase,
                "install not in progress (phase=$phase) — bootstrap cancellation is via coroutine cancel"
            )
        }
    }

    /**
     * 产品级修复：透传 EnvironmentRepairService（单轮 detect → repair → verify，
     * 不触发大下载；bootstrap 维度由该服务显式 SKIPPED —— 长流程走 ensureReady）。
     */
    suspend fun repair(): RepairOutcome {
        val fn = repairFn
            ?: return RepairOutcome(
                actions = emptyList(),
                verifiedHealthy = false,
                detail = "repair port not wired (no EnvironmentRepairService in DI graph)"
            )
        setPhase(Phase.RECOVERING)
        val outcome = try {
            fn()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return RepairOutcome(
                actions = emptyList(),
                verifiedHealthy = false,
                detail = "repair crashed: ${e.message}"
            )
        }
        if (outcome.verifiedHealthy) {
            lastFailure = null
            lastReadyAt = clock()
        } else {
            lastFailure = Stage.REPAIR to (outcome.detail ?: "repair did not converge to healthy")
        }
        refreshState()
        return outcome
    }

    /** 聚合进度流：install（ProvisioningProgress）+ bootstrap（BootstrapProgressEvent）。 */
    fun progressFlow(): Flow<LifecycleProgress> = kotlinx.coroutines.flow.merge(
        provisioner.progress().transform { p ->
            emit(
                LifecycleProgress(
                    stage = "install:${p.state.name}",
                    message = p.message,
                    percent = p.percent,
                    bytesTransferred = p.bytesTransferred,
                    bytesTotal = p.bytesTotal,
                    timestampMs = clock()
                )
            )
        },
        bootstrapProgressFn().transform { e ->
            emit(
                LifecycleProgress(
                    stage = "bootstrap:${e.stage}",
                    message = e.message,
                    timestampMs = if (e.timestampMs > 0) e.timestampMs else clock()
                )
            )
        }
    )

    // ─────────────────────────── 内部 ───────────────────────────

    private fun setPhase(phase: Phase, capabilities: List<CapabilityEntry>? = null) {
        val cur = _state.value
        _state.value = cur.copy(
            phase = phase,
            capabilities = if (phase == Phase.READY) capabilities ?: cur.capabilities else null,
            rootfsState = try {
                provisioner.state().name
            } catch (e: Exception) {
                cur.rootfsState
            },
            failedStage = null,
            lastError = null,
            retryable = false,
            lastReadyAt = lastReadyAt
        )
    }

    private suspend fun markFailed(stage: Stage, message: String, retryable: Boolean): EnsureResult.Failed {
        lastFailure = stage to message
        val rootfsStateName = try {
            provisioner.state().name
        } catch (e: Exception) {
            null
        }
        val bootState = try {
            bootstrapStateFn()
        } catch (e: Exception) {
            null
        }
        _state.value = _state.value.copy(
            phase = Phase.FAILED,
            rootfsState = rootfsStateName,
            bootstrapState = bootState,
            failedStage = stage.name,
            lastError = message,
            retryable = retryable,
            capabilities = null
        )
        return EnsureResult.Failed(
            stage = stage,
            message = message,
            retryable = retryable,
            phase = Phase.FAILED,
            rootfsState = rootfsStateName,
            bootstrapState = bootState
        )
    }

    companion object {
        /** install + bootstrap + probe 全链默认预算（首次安装 ~30MB 下载 + apt update）。 */
        const val DEFAULT_ENSURE_TIMEOUT_MS: Long = 900_000L

        /** ProvisioningState 的"安装进行中"集合（合成视图用）。 */
        private val INSTALL_IN_PROGRESS_STATES = setOf(
            "RESOLVING", "DOWNLOADING", "VERIFYING", "EXTRACTING",
            "VALIDATING", "CONFIGURING", "ACTIVATING"
        )

        /** BootstrapState 的"bootstrap 进行中"集合（合成视图用）。 */
        private val BOOTSTRAP_IN_PROGRESS_STATES = setOf(
            "CHECKING", "CONFIGURING", "NETWORK_CHECK", "APT_UPDATE", "BASE_PACKAGES"
        )
    }
}
