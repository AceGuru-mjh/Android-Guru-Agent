package com.apex.agent.platform.terminal.health

import com.apex.agent.platform.terminal.ubuntu.RootfsProvisioner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * T81 (D-7 / §30)：自动修复编排 —— detect → diagnose → repair → verify。
 *
 * 背景：repair 能力散落三处（provisioner.repair / aptManager.repair(dpkg
 * --configure -a) / EnvironmentRepairPlanner 只产计划不执行），无统一调度者；
 * LinuxEnvironmentHealth 的 repairable 只是标志位。
 *
 * 本服务把三者编排为**单轮、有界**的修复流程：
 *   detect（health check）→ repair（按维度分派一次）→ verify（复查）
 * 每个维度最多 repair 一次 + verify 一次 —— **无循环、无重试风暴**
 *（§30 禁止无限 repair loop；需要多轮时由 Agent 显式再次调用）。
 */
class EnvironmentRepairService(
    private val health: LinuxEnvironmentHealth,
    private val aptManager: com.apex.agent.platform.terminal.pkg.LinuxPackageManager,
    private val provisioner: RootfsProvisioner,
    /** 能力探测（可选 —— 注入时支持 capability 维度修复）。 */
    private val capabilityProbe: com.apex.agent.platform.terminal.environment.LinuxCapabilityProbe? = null
) {
    data class RepairAction(
        val dimension: String,
        val action: String,          // repair 调用描述
        val outcome: String,         // SUCCESS / FAILED / SKIPPED
        val detail: String? = null
    )

    data class RepairReport(
        val repaired: List<RepairAction>,
        val verifiedHealthy: Boolean,   // 修复后复查 overall == READY
        val verification: LinuxEnvironmentHealth.LinuxHealthReport?
    )

    private val mutex = Mutex()

    /**
     * 单轮自动修复（幂等安全 —— 并发调用串行执行）。
     * 流程：health check → 对每个可修复维度执行对应 repair（各一次）→ 复查。
     */
    suspend fun autoRepair(): RepairReport = mutex.withLock {
        val actions = mutableListOf<RepairAction>()
        val before = health.check()
        val dimensions = listOf(before.rootfs, before.proot, before.network, before.apt,
            before.home, before.workspace, before.bootstrap)

        // 分派修复（每维度一次）：
        //  rootfs 维度（CORRUPTED/INVALID/REMOVED）→ provisioner.repair()
        //  apt 维度（dpkg interrupted/locked）→ aptManager.repair()（dpkg --configure -a）
        //  bootstrap 维度（FAILED/INCOMPLETE）→ SKIPPED（bootstrap 是显式长流程，
        //    Agent 应调 terminal.linux.bootstrap —— 自动 repair 不触发大下载）
        for (dim in dimensions) {
            if (!dim.repairable) continue
            when (dim.name) {
                "rootfs" -> {
                    val r = runCatching { provisioner.repair() }
                    actions.add(RepairAction("rootfs", "provisioner.repair()",
                        if (r.isSuccess && r.getOrNull()?.let { it is com.apex.agent.platform.terminal.ubuntu.ProvisioningResult.Ready || it is com.apex.agent.platform.terminal.ubuntu.ProvisioningResult.AlreadyReady } == true) "SUCCESS" else "FAILED",
                        r.getOrNull().toString()))
                }
                "apt" -> {
                    val r = runCatching { aptManager.repair() }
                    actions.add(RepairAction("apt", "aptManager.repair() (dpkg --configure -a)",
                        if (r.isSuccess && r.getOrNull()?.state == com.apex.agent.platform.terminal.pkg.PackageOperationState.SUCCEEDED) "SUCCESS" else "FAILED",
                        r.getOrNull()?.error?.message))
                }
                else -> {
                    actions.add(RepairAction(dim.name, "auto-repair not applicable", "SKIPPED",
                        "dimension '${dim.name}' requires explicit agent action"))
                }
            }
        }

        if (actions.isEmpty()) {
            return@withLock RepairReport(emptyList(), before.ready, before)
        }

        // verify：复查（一次）
        val after = runCatching { health.check() }.getOrNull()
        RepairReport(
            repaired = actions,
            verifiedHealthy = after?.ready == true,
            verification = after
        )
    }
}
