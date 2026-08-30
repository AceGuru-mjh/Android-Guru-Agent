package com.apex.agent.platform.terminal.network

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.pkg.LinuxPackageManager
import com.apex.agent.platform.terminal.pkg.PackageOperation
import com.apex.agent.platform.terminal.pkg.PackageOperationState
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import java.io.File

/**
 * T76: Linux Network Probe —— DNS / HTTP / HTTPS / APT_REPOSITORY 诊断。
 *
 * 不再把"网络失败"笼统报告成"Ubuntu 不可用"。Agent 拿到的是分维诊断，能据此
 * 决策（DNS 失败 → 修复 resolv.conf；TLS 失败 → 装 ca-certificates；HTTP 失败 →
 * 检查防火墙/代理；APT repo 失败 → 修 sources.list）。
 *
 * 探测策略（诚实优先，T76 §7）：
 *  - DNS：读 rootfs /etc/resolv.conf（host 侧文件直读，无需 proot）+ apt update 的
 *    真实解析行为（apt update 失败含 "Temporary failure resolving" → DNS failed）。
 *  - HTTP/HTTPS：apt update 是最真实的端到端网络探针（真实 DNS + TCP + TLS + apt
 *    协议）。Ubuntu Base 24.04 默认源是 HTTP（ports.ubuntu.com / archive.ubuntu.com）；
 *    若 sources 含 HTTPS，apt update 同时测 TLS。从 stderr 细分失败模式。
 *  - APT_REPOSITORY：apt update exit code + stderr。
 *
 * 不关闭 TLS verification（T76 §8）—— CA 缺失时如实报 NETWORK_TLS_FAILED，由
 * bootstrap 装 ca-certificates 修复。
 */
open class LinuxNetworkProbe(
    private val rootfsProvider: RootfsProvider,
    private val aptManager: LinuxPackageManager,
    /** 探测用的目标 host（架构相关：arm64 → ports.ubuntu.com，amd64 → archive.ubuntu.com）。 */
    private val probeHostProvider: () -> String = { "archive.ubuntu.com" }
) {
    /** 单维探测状态。 */
    enum class ProbeStatus { READY, DEGRADED, FAILED, UNKNOWN }

    /** 单维探测结果。 */
    data class ProbeResult(
        val status: ProbeStatus,
        val detail: String,
        val durationMs: Long = 0
    )

    /** 综合诊断。 */
    data class NetworkDiagnosis(
        val dns: ProbeResult,
        val http: ProbeResult,
        val https: ProbeResult,
        val aptRepository: ProbeResult,
        val overall: ProbeStatus,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val ready: Boolean get() = overall == ProbeStatus.READY
    }

    /**
     * 运行完整网络诊断（DNS + HTTP + HTTPS + APT_REPOSITORY）。
     *
     * 内部调用 [UbuntuAptPackageManager.update]（apt-get update）作为端到端探针；
     * apt update 是真实网络 + apt 协议的最诚实测试。从结果 + stderr 细分各维状态。
     */
    suspend fun diagnose(): NetworkDiagnosis {
        val rootfs = rootfsProvider.current()
        if (rootfs == null || rootfs.location == null) {
            val fail = ProbeResult(ProbeStatus.FAILED, "no active rootfs")
            return NetworkDiagnosis(fail, fail, fail, fail, ProbeStatus.FAILED)
        }

        // ── 1. DNS 配置（host 侧直读 resolv.conf）──
        val resolvConf = File(rootfs.location.value, "etc/resolv.conf")
        val dnsConfig = parseDnsConfig(resolvConf)
        val dnsConfigStatus = if (dnsConfig.nameservers.isEmpty()) {
            ProbeResult(ProbeStatus.FAILED, "resolv.conf has no nameservers")
        } else {
            ProbeResult(ProbeStatus.READY, "nameservers: ${dnsConfig.nameservers.joinToString(",")}")
        }

        // ── 2. apt-get update（端到端网络探针）──
        val aptResult = aptManager.update()
        val stderr = aptResult.result?.stderr ?: ""
        val aptOk = aptResult.state == PackageOperationState.SUCCEEDED

        // ── 3. 从 apt 结果细分 DNS / HTTP / HTTPS / APT_REPO ──
        val dnsReal = classifyDns(aptOk, stderr, dnsConfigStatus)
        val http = classifyHttp(aptOk, stderr, rootfs)
        val https = classifyHttps(aptOk, stderr, rootfs)
        val aptRepo = classifyAptRepo(aptOk, stderr, aptResult)

        val overall = aggregateOverall(dnsReal, http, https, aptRepo)
        return NetworkDiagnosis(
            dns = dnsReal,
            http = http,
            https = https,
            aptRepository = aptRepo,
            overall = overall
        )
    }

    /** 仅探测 DNS（轻量：读 resolv.conf，不跑 apt update）。用于快速 status。 */
    open suspend fun probeDnsOnly(): ProbeResult {
        val rootfs = rootfsProvider.current() ?: return ProbeResult(ProbeStatus.FAILED, "no rootfs")
        val loc = rootfs.location ?: return ProbeResult(ProbeStatus.FAILED, "no rootfs location")
        val resolvConf = File(loc.value, "etc/resolv.conf")
        val cfg = parseDnsConfig(resolvConf)
        return if (cfg.nameservers.isEmpty()) {
            ProbeResult(ProbeStatus.FAILED, "resolv.conf has no nameservers")
        } else {
            ProbeResult(ProbeStatus.READY, "nameservers: ${cfg.nameservers.joinToString(",")}")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 分类器
    // ──────────────────────────────────────────────────────────────────

    private fun classifyDns(
        aptOk: Boolean,
        stderr: String,
        dnsConfig: ProbeResult
    ): ProbeResult {
        if (aptOk) return ProbeResult(ProbeStatus.READY, "DNS resolution working (apt update succeeded)")
        val dnsFailed = stderr.contains("Temporary failure resolving") ||
            stderr.contains("Could not resolve") ||
            stderr.contains("Name or service not known")
        return when {
            dnsFailed -> ProbeResult(ProbeStatus.FAILED, "DNS resolution failed: ${firstErrorLine(stderr)}")
            dnsConfig.status == ProbeStatus.FAILED -> dnsConfig
            else -> ProbeResult(ProbeStatus.UNKNOWN, "apt update failed but no DNS error detected")
        }
    }

    private fun classifyHttp(
        aptOk: Boolean,
        stderr: String,
        rootfs: RootfsDescriptor
    ): ProbeResult {
        if (aptOk) return ProbeResult(ProbeStatus.READY, "HTTP connectivity working")
        val httpFailed = stderr.contains("Connection failed") ||
            stderr.contains("Connection refused") ||
            stderr.contains("Connection timed out") ||
            stderr.contains("Could not connect") ||
            stderr.contains("Network is unreachable")
        val tlsError = stderr.contains("Certificate verification failed") ||
            stderr.contains("TLS") ||
            stderr.contains("SSL")
        return when {
            tlsError -> ProbeResult(ProbeStatus.UNKNOWN, "TLS error detected (see HTTPS dimension)")
            httpFailed -> ProbeResult(ProbeStatus.FAILED, "HTTP connectivity failed: ${firstErrorLine(stderr)}")
            else -> ProbeResult(ProbeStatus.UNKNOWN, "apt update failed, HTTP cause inconclusive")
        }
    }

    private fun classifyHttps(
        aptOk: Boolean,
        stderr: String,
        rootfs: RootfsDescriptor
    ): ProbeResult {
        val loc = rootfs.location ?: return ProbeResult(ProbeStatus.UNKNOWN, "no rootfs location")
        val sourcesUseHttps = sourcesUseHttps(loc)
        val caPresent = File(loc.value, "etc/ssl/certs/ca-certificates.crt").let { it.isFile && it.length() > 0 }
        val tlsError = stderr.contains("Certificate verification failed") ||
            stderr.contains("TLS") || stderr.contains("SSL")
        return when {
            aptOk && sourcesUseHttps -> ProbeResult(ProbeStatus.READY, "HTTPS working (apt update over https succeeded)")
            aptOk && !sourcesUseHttps -> ProbeResult(ProbeStatus.UNKNOWN, "no https sources configured; HTTPS not tested")
            tlsError && !caPresent -> ProbeResult(ProbeStatus.FAILED, "TLS failed + CA bundle absent — apt install ca-certificates required")
            tlsError -> ProbeResult(ProbeStatus.FAILED, "TLS handshake failed: ${firstErrorLine(stderr)}")
            !caPresent -> ProbeResult(ProbeStatus.DEGRADED, "CA bundle absent — https will fail until ca-certificates installed")
            else -> ProbeResult(ProbeStatus.UNKNOWN, "HTTPS state inconclusive")
        }
    }

    private fun classifyAptRepo(
        aptOk: Boolean,
        stderr: String,
        aptResult: PackageOperation
    ): ProbeResult {
        if (aptOk) return ProbeResult(ProbeStatus.READY, "apt repository reachable (apt update succeeded)")
        val repoError = stderr.contains("Repository") || stderr.contains("Release file")
        return when {
            repoError -> ProbeResult(ProbeStatus.FAILED, "apt repository error: ${firstErrorLine(stderr)}")
            aptResult.state == PackageOperationState.TIMED_OUT -> ProbeResult(ProbeStatus.DEGRADED, "apt update timed out — repository may be slow or unreachable")
            else -> ProbeResult(ProbeStatus.FAILED, "apt update failed: ${firstErrorLine(stderr)}")
        }
    }

    private fun aggregateOverall(
        dns: ProbeResult, http: ProbeResult, https: ProbeResult, aptRepo: ProbeResult
    ): ProbeStatus {
        val statuses = listOf(dns.status, http.status, https.status, aptRepo.status)
        return when {
            statuses.all { it == ProbeStatus.READY } -> ProbeStatus.READY
            statuses.any { it == ProbeStatus.FAILED } -> ProbeStatus.FAILED
            statuses.any { it == ProbeStatus.DEGRADED } -> ProbeStatus.DEGRADED
            else -> ProbeStatus.UNKNOWN
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 辅助
    // ──────────────────────────────────────────────────────────────────

    data class DnsConfig(val nameservers: List<String>)

    private fun parseDnsConfig(resolvConf: File): DnsConfig {
        if (!resolvConf.isFile) return DnsConfig(emptyList())
        val servers = resolvConf.readLines()
            .map { it.trim() }
            .filter { it.startsWith("nameserver") }
            .map { it.removePrefix("nameserver").trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        return DnsConfig(servers)
    }

    private fun sourcesUseHttps(rootfsLoc: AbsolutePath): Boolean {
        val root = File(rootfsLoc.value)
        val sourcesList = File(root, "etc/apt/sources.list")
        val sourcesListD = File(root, "etc/apt/sources.list.d")
        if (sourcesList.isFile && sourcesList.readText().contains("https://")) return true
        if (sourcesListD.isDirectory) {
            sourcesListD.listFiles()?.forEach { f ->
                if (f.isFile && f.readText().contains("https://")) return true
            }
        }
        return false
    }

    private fun firstErrorLine(stderr: String): String {
        return stderr.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("E:") || it.startsWith("Err:") || it.startsWith("W:") }
            .firstOrNull() ?: stderr.trim().lineSequence().firstOrNull() ?: "(no detail)"
    }

    companion object {
        /** 架构 → 默认探测 host。arm64 → ports.ubuntu.com；其他 → archive.ubuntu.com。 */
        fun defaultProbeHostFor(arch: CpuArchitecture): String = when (arch) {
            CpuArchitecture.ARM64, CpuArchitecture.ARM32 -> "ports.ubuntu.com"
            else -> "archive.ubuntu.com"
        }
    }
}
