package com.apex.agent.platform.terminal.health

import com.apex.agent.platform.terminal.errors.LinuxErrorCode
import com.apex.agent.platform.terminal.network.LinuxNetworkProbe
import com.apex.agent.platform.terminal.pkg.UbuntuAptPackageManager
import com.apex.agent.platform.terminal.proot.LinuxPRootBackend
import com.apex.agent.platform.terminal.ubuntu.BootstrapState
import com.apex.agent.platform.terminal.ubuntu.RootfsHealthInspector
import com.apex.agent.platform.terminal.ubuntu.UbuntuBootstrapManager
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import java.io.File

/**
 * T76: Linux Environment Health —— 统一健康门面（6 维度）。
 *
 * 把分散的健康检查聚合为单一 [LinuxHealthReport]，让 Agent 一次 `terminal.linux.status`
 * 就拿到全貌（T76 §19 / §20），而非自己拼凑 rootfs/proot/network/apt/home/workspace。
 *
 * 6 维度：
 *  - rootfs：T72 [RootfsHealthInspector]（shell/apt/dpkg/resolver/arch 等 FAIL/WARN）
 *  - proot：[LinuxPRootBackend.availability]（binary + rootfs → Ready/NeedsRootfs/Failed）
 *  - network：[LinuxNetworkProbe.probeDnsOnly]（轻量；完整 diagnose 由专门工具触发）
 *  - apt：[UbuntuAptPackageManager.status]（apt-get/dpkg 可用性 + lock 状态）
 *  - home：[GuestUserHome] host 目录可写
 *  - workspace：[LinuxWorkspaceManager] default workspace 可解析
 *
 * 输出：每维 READY/DEGRADED/FAILED + code/message/repairable；overall 聚合。
 *
 * 性能：health check 全是轻量操作（文件存在性 + 短探针），不跑 apt update。
 * 完整网络 diagnose 由 `terminal.linux.network` 工具按需触发（避免每次 status 都
 * 跑 apt update）。
 */
class LinuxEnvironmentHealth(
    private val rootfsProvisioner: com.apex.agent.platform.terminal.ubuntu.RootfsProvisioner,
    private val prootBackend: LinuxPRootBackend,
    private val networkProbe: LinuxNetworkProbe,
    private val aptManager: UbuntuAptPackageManager,
    private val bootstrapManager: UbuntuBootstrapManager,
    private val workspaceManager: LinuxWorkspaceManager,
    private val guestUserHome: GuestUserHome,
    private val rootfsHealthInspector: RootfsHealthInspector? = null
) {

    /** 维度状态。 */
    enum class HealthStatus { READY, DEGRADED, FAILED, UNKNOWN }

    /** 单维度检查结果。 */
    data class DimensionCheck(
        val name: String,
        val status: HealthStatus,
        val code: String,
        val message: String,
        val repairable: Boolean
    )

    /** 综合健康报告。 */
    data class LinuxHealthReport(
        val rootfs: DimensionCheck,
        val proot: DimensionCheck,
        val network: DimensionCheck,
        val apt: DimensionCheck,
        val home: DimensionCheck,
        val workspace: DimensionCheck,
        val bootstrap: DimensionCheck,
        val overall: HealthStatus,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val ready: Boolean get() = overall == HealthStatus.READY
        val summary: String
            get() = "overall=$overall rootfs=${rootfs.status} proot=${proot.status} " +
                "network=${network.status} apt=${apt.status} home=${home.status} " +
                "workspace=${workspace.status} bootstrap=${bootstrap.status}"
    }

    /** 完整健康检查（6 维度 + bootstrap）。 */
    suspend fun check(): LinuxHealthReport {
        val rootfs = checkRootfs()
        val proot = checkProot()
        val network = checkNetwork()
        val apt = checkApt()
        val home = checkHome()
        val workspace = checkWorkspace()
        val bootstrap = checkBootstrap()
        val overall = aggregate(rootfs, proot, network, apt, home, workspace, bootstrap)
        return LinuxHealthReport(rootfs, proot, network, apt, home, workspace, bootstrap, overall)
    }

    /** 轻量检查（跳过 apt status 探针 —— 用于频繁 status 轮询）。 */
    suspend fun quickCheck(): LinuxHealthReport {
        val rootfs = checkRootfs()
        val proot = checkProot()
        val network = checkNetwork()
        // apt / home / workspace 用文件存在性快速判断
        val apt = quickCheckApt()
        val home = checkHome()
        val workspace = checkWorkspace()
        val bootstrap = checkBootstrap()
        val overall = aggregate(rootfs, proot, network, apt, home, workspace, bootstrap)
        return LinuxHealthReport(rootfs, proot, network, apt, home, workspace, bootstrap, overall)
    }

    // ──────────────────────────────────────────────────────────────────
    // 各维度检查
    // ──────────────────────────────────────────────────────────────────

    private suspend fun checkRootfs(): DimensionCheck {
        val rootfs = rootfsProvisioner.current()
        if (rootfs == null || rootfs.location == null) {
            return DimensionCheck("rootfs", HealthStatus.FAILED, LinuxErrorCode.ROOTFS_NOT_READY.name, "no active rootfs", true)
        }
        val loc = rootfs.location!!
        val rootDir = File(loc.value)
        // 必需目录
        val required = listOf("bin", "etc", "usr", "var")
        val missing = required.filter { !File(rootDir, it).isDirectory }
        if (missing.isNotEmpty()) {
            return DimensionCheck("rootfs", HealthStatus.FAILED, LinuxErrorCode.ROOTFS_NOT_READY.name, "missing dirs: $missing", true)
        }
        // 详细健康检查（可选）
        val inspector = rootfsHealthInspector
        if (inspector != null) {
            val report = runCatching { inspector.inspect(rootDir) }.getOrNull()
            if (report != null && report.failures.isNotEmpty()) {
                val detail = report.failures.joinToString("; ") { "${it.name}: ${it.detail}" }
                return DimensionCheck("rootfs", HealthStatus.DEGRADED, "ROOTFS_HEALTH_WARN", detail, true)
            }
        }
        return DimensionCheck("rootfs", HealthStatus.READY, "OK", "rootfs ${rootfs.id} (${rootfs.version})", false)
    }

    private suspend fun checkProot(): DimensionCheck {
        return try {
            val avail = prootBackend.availability()
            when (avail) {
                is com.apex.agent.platform.terminal.runtime.BackendAvailability.Ready ->
                    DimensionCheck("proot", HealthStatus.READY, "OK", "proot backend ready", false)
                is com.apex.agent.platform.terminal.runtime.BackendAvailability.NeedsRootfs ->
                    DimensionCheck("proot", HealthStatus.DEGRADED, LinuxErrorCode.ROOTFS_NOT_READY.name, "proot ok but rootfs needed: ${avail.reason ?: ""}", true)
                is com.apex.agent.platform.terminal.runtime.BackendAvailability.Failed ->
                    DimensionCheck("proot", HealthStatus.FAILED, LinuxErrorCode.PROOT_UNAVAILABLE.name, avail.reason, false)
            }
        } catch (e: Exception) {
            DimensionCheck("proot", HealthStatus.FAILED, LinuxErrorCode.PROOT_UNAVAILABLE.name, "check error: ${e.message}", false)
        }
    }

    private suspend fun checkNetwork(): DimensionCheck {
        return try {
            val dns = networkProbe.probeDnsOnly()
            when (dns.status) {
                LinuxNetworkProbe.ProbeStatus.READY ->
                    DimensionCheck("network", HealthStatus.READY, "OK", dns.detail, false)
                LinuxNetworkProbe.ProbeStatus.DEGRADED ->
                    DimensionCheck("network", HealthStatus.DEGRADED, LinuxErrorCode.NETWORK_DNS_FAILED.name, dns.detail, true)
                LinuxNetworkProbe.ProbeStatus.FAILED ->
                    DimensionCheck("network", HealthStatus.FAILED, LinuxErrorCode.NETWORK_DNS_FAILED.name, dns.detail, true)
                LinuxNetworkProbe.ProbeStatus.UNKNOWN ->
                    DimensionCheck("network", HealthStatus.UNKNOWN, "UNKNOWN", dns.detail, false)
            }
        } catch (e: Exception) {
            DimensionCheck("network", HealthStatus.UNKNOWN, "UNKNOWN", "check error: ${e.message}", false)
        }
    }

    private suspend fun checkApt(): DimensionCheck {
        return try {
            val status = aptManager.status()
            when {
                !status.available ->
                    DimensionCheck("apt", HealthStatus.FAILED, LinuxErrorCode.APT_UNAVAILABLE.name, "apt unavailable: ${status.manager}", true)
                status.databaseState == com.apex.agent.platform.terminal.pkg.PackageDatabaseState.BROKEN ->
                    DimensionCheck("apt", HealthStatus.DEGRADED, LinuxErrorCode.APT_FAILED.name, "dpkg database broken", true)
                status.lockState == com.apex.agent.platform.terminal.pkg.PackageLockState.LOCKED ->
                    DimensionCheck("apt", HealthStatus.DEGRADED, LinuxErrorCode.APT_LOCKED.name, "apt lock held", false)
                else ->
                    DimensionCheck("apt", HealthStatus.READY, "OK", "apt ${status.version ?: ""}", false)
            }
        } catch (e: Exception) {
            DimensionCheck("apt", HealthStatus.UNKNOWN, "UNKNOWN", "check error: ${e.message}", false)
        }
    }

    private suspend fun quickCheckApt(): DimensionCheck {
        // 仅检查 apt-get 二进制存在（不跑 proot 探针）
        val rootfs = rootfsProvisioner.current()
        if (rootfs == null || rootfs.location == null) {
            return DimensionCheck("apt", HealthStatus.UNKNOWN, "UNKNOWN", "no rootfs", false)
        }
        val aptGet = File(rootfs.location!!.value, "usr/bin/apt-get")
        val dpkg = File(rootfs.location!!.value, "usr/bin/dpkg")
        return when {
            !aptGet.isFile -> DimensionCheck("apt", HealthStatus.FAILED, LinuxErrorCode.APT_UNAVAILABLE.name, "apt-get missing", true)
            !dpkg.isFile -> DimensionCheck("apt", HealthStatus.FAILED, LinuxErrorCode.APT_UNAVAILABLE.name, "dpkg missing", true)
            else -> DimensionCheck("apt", HealthStatus.READY, "OK", "apt binaries present (quick)", false)
        }
    }

    private fun checkHome(): DimensionCheck {
        return try {
            val homeDir = guestUserHome.hostDir()
            if (!homeDir.isDirectory) {
                DimensionCheck("home", HealthStatus.FAILED, LinuxErrorCode.HOME_UNAVAILABLE.name, "home dir missing", true)
            } else if (!homeDir.canWrite()) {
                DimensionCheck("home", HealthStatus.DEGRADED, LinuxErrorCode.HOME_UNAVAILABLE.name, "home dir not writable", true)
            } else {
                val bashrc = File(homeDir, ".bashrc")
                DimensionCheck("home", HealthStatus.READY, "OK", "home ready (${if (bashrc.exists()) ".bashrc present" else "no .bashrc yet"})", false)
            }
        } catch (e: Exception) {
            DimensionCheck("home", HealthStatus.FAILED, LinuxErrorCode.HOME_UNAVAILABLE.name, "check error: ${e.message}", true)
        }
    }

    private fun checkWorkspace(): DimensionCheck {
        return try {
            val resolveResult = workspaceManager.resolve(LinuxWorkspaceManager.DEFAULT_ID)
            val dir = resolveResult.getOrNull()
            if (dir == null) {
                DimensionCheck("workspace", HealthStatus.FAILED, LinuxErrorCode.WORKSPACE_UNAVAILABLE.name, "resolve failed: ${resolveResult.exceptionOrNull()?.message}", true)
            } else if (!dir.canWrite()) {
                DimensionCheck("workspace", HealthStatus.DEGRADED, LinuxErrorCode.WORKSPACE_UNAVAILABLE.name, "workspace not writable", true)
            } else {
                DimensionCheck("workspace", HealthStatus.READY, "OK", "workspace ${dir.absolutePath}", false)
            }
        } catch (e: Exception) {
            DimensionCheck("workspace", HealthStatus.FAILED, LinuxErrorCode.WORKSPACE_UNAVAILABLE.name, "check error: ${e.message}", true)
        }
    }

    private suspend fun checkBootstrap(): DimensionCheck {
        return try {
            val st = bootstrapManager.state()
            when (st) {
                BootstrapState.READY ->
                    DimensionCheck("bootstrap", HealthStatus.READY, "OK", "bootstrap READY", false)
                BootstrapState.NOT_STARTED ->
                    DimensionCheck("bootstrap", HealthStatus.UNKNOWN, "NOT_STARTED", "never bootstrapped", false)
                BootstrapState.FAILED ->
                    DimensionCheck("bootstrap", HealthStatus.DEGRADED, LinuxErrorCode.BOOTSTRAP_FAILED.name, "last bootstrap failed — retry", true)
                else ->
                    DimensionCheck("bootstrap", HealthStatus.DEGRADED, "IN_PROGRESS", "bootstrap in $st", true)
            }
        } catch (e: Exception) {
            DimensionCheck("bootstrap", HealthStatus.UNKNOWN, "UNKNOWN", "check error: ${e.message}", false)
        }
    }

    private fun aggregate(vararg dims: DimensionCheck): HealthStatus {
        val statuses = dims.map { it.status }
        return when {
            statuses.all { it == HealthStatus.READY } -> HealthStatus.READY
            statuses.any { it == HealthStatus.FAILED } -> HealthStatus.FAILED
            statuses.any { it == HealthStatus.DEGRADED } -> HealthStatus.DEGRADED
            else -> HealthStatus.UNKNOWN
        }
    }
}
