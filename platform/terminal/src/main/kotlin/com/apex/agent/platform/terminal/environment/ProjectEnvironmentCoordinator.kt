package com.apex.agent.platform.terminal.environment

import com.apex.agent.platform.terminal.pkg.PackageSpec
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager

/**
 * P83 (T4): Ubuntu 开发环境闭环 —— Project → Analyzer → Ubuntu → Toolchain。
 *
 * 背景：`ProjectEnvironmentAnalyzer`（PR#66）与环境 profile registry 一直生产零接线
 * —— 分析结果没有消费者，Ubuntu 就绪后也不会按项目需要补装工具链。本协调器把
 * 已接线的生产组件（[UbuntuLifecycleCoordinator] / [LinuxCapabilityProbe] /
 * [LinuxPackageManager] / [LinuxWorkspaceManager]）串成一条可调用的链：
 *
 * ```
 * ensure(workspaceId):
 *   1. lifecycle.ensureReady()        ← Ubuntu rootfs + bootstrap（T82 单飞入口）
 *   2. workspaces.resolve(id)         ← workspace 数据目录
 *   3. analyzer.analyze(root)         ← 标记文件 → 语言 → EnvironmentRequirement（§7 纯检测）
 *   4. probe 每项 requirement          ← AVAILABLE / INSTALLABLE / BROKEN / UNKNOWN
 *   5. 缺失 → packages.install(批量)  ← 一次 apt install
 *   6. invalidate + 复测              ← 诚实汇报最终状态（不伪造成功）
 * ```
 *
 * 边界（§7 一致）：分析只读；安装只经 [LinuxPackageManager]（PackageOperationLock
 * 串行）；ENV: 伪需求（如 JAVA_HOME）只做 advisory 汇报，不在此处落地 —— 环境变量
 * 注入属于 EnvironmentManager/LaunchEnv 的职责。
 */
class ProjectEnvironmentCoordinator(
    private val analyzer: ProjectEnvironmentAnalyzer,
    private val probe: LinuxCapabilityProbe,
    private val packageManager: com.apex.agent.platform.terminal.pkg.LinuxPackageManager,
    private val lifecycle: UbuntuLifecycleCoordinator,
    private val workspaces: LinuxWorkspaceManager
) {

    // ─────────────────────────── 报告模型 ───────────────────────────

    /** 单个 requirement 的最终状态（ensure 后的复测结果）。 */
    enum class RequirementState {
        READY,            // probe AVAILABLE 且版本约束满足
        VERSION_MISMATCH, // AVAILABLE 但版本约束不满足
        INSTALLED,        // 本次 ensure 补装成功（复测 AVAILABLE）
        STILL_MISSING,    // 补装后复测仍不可用
        INSTALL_FAILED,   // apt install 失败
        ENV_ADVISORY,     // ENV: 伪需求（如 JAVA_HOME）—— advisory，不安装
        UNKNOWN           // 探测本身失败（环境异常 ≠ 未安装）
    }

    data class RequirementStatus(
        val id: String,
        val displayName: String,
        val state: RequirementState,
        val version: String? = null,
        val versionConstraint: String? = null,
        val packages: List<String> = emptyList(),
        val detail: String? = null
    )

    data class ProjectEnvironmentReport(
        val workspaceId: String,
        val workspaceRoot: String?,
        val lifecyclePhase: String,
        val detectedLanguages: Set<String>,
        val lockfiles: List<String>,
        val requirements: List<RequirementStatus>,
        /** 本次 ensure 实际交给 apt 的包（analyze 时为空）。 */
        val installedPackages: List<String>,
        val durationMs: Long,
        val detail: String? = null
    ) {
        val allReady: Boolean
            get() = requirements.all {
                it.state == RequirementState.READY ||
                    it.state == RequirementState.INSTALLED ||
                    it.state == RequirementState.ENV_ADVISORY
            }
    }

    // ─────────────────────────── analyze（只读，不装任何东西） ───────────────────────────

    suspend fun analyze(workspaceId: String? = null): ProjectEnvironmentReport {
        val t0 = System.currentTimeMillis()
        val wsId = normalizeWorkspaceId(workspaceId)
        val phase = lifecycle.refreshState().phase.name
        val dir = workspaces.resolve(wsId).getOrNull()
        val analysis = if (dir != null) {
            analyzer.analyze(dir.absolutePath)
        } else {
            ProjectAnalysis(wsId, emptySet(), emptyList(), emptyList())
        }
        val statuses = analysis.requirements.map { checkRequirement(it) }
        return ProjectEnvironmentReport(
            workspaceId = wsId,
            workspaceRoot = dir?.absolutePath,
            lifecyclePhase = phase,
            detectedLanguages = analysis.detectedLanguages,
            lockfiles = analysis.lockfiles,
            requirements = statuses,
            installedPackages = emptyList(),
            durationMs = System.currentTimeMillis() - t0,
            detail = if (dir == null) "WorkspaceError:ResolveFailed — 无法解析 workspace '$wsId'" else null
        )
    }

    // ─────────────────────────── ensure（分析 + 缺失补装 + 复测） ───────────────────────────

    suspend fun ensure(
        workspaceId: String? = null,
        installMissing: Boolean = true,
        timeoutMs: Long = 900_000L
    ): ProjectEnvironmentReport {
        val t0 = System.currentTimeMillis()
        val wsId = normalizeWorkspaceId(workspaceId)

        // 1. Ubuntu 生命周期（T82 单飞：rootfs install → bootstrap → capability probe）。
        val ensureResult = lifecycle.ensureReady(timeoutMs = timeoutMs)
        val phase = lifecycle.refreshState().phase.name
        val lifecycleFailure = (ensureResult as? UbuntuLifecycleCoordinator.EnsureResult.Failed)
        if (lifecycleFailure != null) {
            return ProjectEnvironmentReport(
                workspaceId = wsId, workspaceRoot = null, lifecyclePhase = phase,
                detectedLanguages = emptySet(), lockfiles = emptyList(),
                requirements = emptyList(), installedPackages = emptyList(),
                durationMs = System.currentTimeMillis() - t0,
                detail = "UbuntuError:${lifecycleFailure.stage} — ${lifecycleFailure.message}" +
                    if (lifecycleFailure.retryable) "（可重试）" else ""
            )
        }

        // 2. workspace 数据目录（懒创建）。
        val dir = workspaces.resolve(wsId).getOrNull()
            ?: return ProjectEnvironmentReport(
                workspaceId = wsId, workspaceRoot = null, lifecyclePhase = phase,
                detectedLanguages = emptySet(), lockfiles = emptyList(),
                requirements = emptyList(), installedPackages = emptyList(),
                durationMs = System.currentTimeMillis() - t0,
                detail = "WorkspaceError:ResolveFailed — 无法解析 workspace '$wsId'"
            )

        // 3. 项目分析（§7：纯标记文件扫描）。
        val analysis = analyzer.analyze(dir.absolutePath)
        var statuses = analysis.requirements.map { checkRequirement(it) }

        // 4. 缺失补装：一批 apt install（PackageOperationLock 在 manager 内部串行）。
        val installedPackages = mutableListOf<String>()
        if (installMissing) {
            val toInstall = statuses.flatMap { status ->
                status.packages.filter { pkg ->
                    status.state == RequirementState.STILL_MISSING ||
                        status.state == RequirementState.VERSION_MISMATCH ||
                        status.state == RequirementState.UNKNOWN ||
                        status.state == RequirementState.INSTALL_FAILED
                }
            }.distinct()
            if (toInstall.isNotEmpty()) {
                val op = packageManager.install(toInstall.map { PackageSpec(it) })
                val ok = op.state == com.apex.agent.platform.terminal.pkg.PackageOperationState.SUCCEEDED
                installedPackages += toInstall
                if (!ok) {
                    // 安装失败也要复测（apt 可能部分成功 —— 逐包汇报真实状态）
                    probe.invalidate()
                    statuses = statuses.map { s ->
                        if (s.state == RequirementState.STILL_MISSING ||
                            s.state == RequirementState.VERSION_MISMATCH ||
                            s.state == RequirementState.UNKNOWN ||
                            s.state == RequirementState.INSTALL_FAILED
                        ) {
                            s.copy(state = RequirementState.INSTALL_FAILED,
                                detail = "apt install failed: ${op.error?.message ?: "exit=${op.exitCode}"}")
                        } else s
                    }
                } else {
                    probe.invalidate()
                }
            }
        }

        // 5. 复测（诚实终态：STILL_MISSING → 复测；此前 READY 的也轻校验缓存内不重跑）。
        val recheck = statuses.map { status ->
            when (status.state) {
                RequirementState.STILL_MISSING,
                RequirementState.VERSION_MISMATCH,
                RequirementState.UNKNOWN,
                RequirementState.INSTALL_FAILED -> {
                    val req = analysis.requirements.firstOrNull { it.id == status.id }
                    val re = req?.let { checkRequirement(it) } ?: status
                    if (re.state == RequirementState.READY && status.packages.any { it in installedPackages }) {
                        re.copy(state = RequirementState.INSTALLED)
                    } else if (re.state == RequirementState.STILL_MISSING) {
                        re.copy(state = RequirementState.STILL_MISSING,
                            detail = "install 已执行但复测仍不可用: ${re.detail ?: ""}")
                    } else {
                        re
                    }
                }
                else -> status
            }
        }

        return ProjectEnvironmentReport(
            workspaceId = wsId,
            workspaceRoot = dir.absolutePath,
            lifecyclePhase = phase,
            detectedLanguages = analysis.detectedLanguages,
            lockfiles = analysis.lockfiles,
            requirements = recheck,
            installedPackages = installedPackages.toList(),
            durationMs = System.currentTimeMillis() - t0,
            detail = when (ensureResult) {
                is UbuntuLifecycleCoordinator.EnsureResult.InProgress ->
                    "lifecycle: InProgress(${ensureResult.phase}) — ensure 在超时窗口内未完成，再次调用可续"
                else -> null
            }
        )
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /** workspace id 归一化（null/blank → default；复用 manager 的合法 id 规则）。 */
    private fun normalizeWorkspaceId(id: String?): String =
        id?.trim()?.takeIf { it.isNotEmpty() } ?: LinuxWorkspaceManager.DEFAULT_ID

    /**
     * 检查单个 requirement：probe（已知能力）→ AVAILABLE/INSTALLABLE/BROKEN/UNKNOWN；
     * 版本约束复用 [VersionConstraint.satisfies]。ENV: 伪需求 → advisory。
     */
    private suspend fun checkRequirement(req: EnvironmentRequirement): RequirementStatus {
        val packages = req.packages.map { it.name }
        // ENV: 伪需求（§20 JAVA_HOME）—— advisory，不安装。
        if (req.detection.command.startsWith("ENV:")) {
            return RequirementStatus(
                id = req.id, displayName = req.displayName,
                state = RequirementState.ENV_ADVISORY,
                packages = packages,
                versionConstraint = req.versionConstraint?.toString(),
                detail = "env advisory: ${req.detection.command.removePrefix("ENV:")}"
            )
        }
        val cmd = req.detection.command
        val known = cmd.lowercase() in LinuxCapabilityProbe.CAPABILITY_SPECS.keys
        if (!known) {
            // probe 表没有的能力（如 g++/pkg-config）→ apt 安装态查询（isInstalled 走 dpkg）。
            val installed = runCatching {
                packages.isNotEmpty() && packages.all { packageManager.isInstalled(it) }
            }.getOrDefault(false)
            return RequirementStatus(
                id = req.id, displayName = req.displayName,
                state = if (installed) RequirementState.READY else RequirementState.STILL_MISSING,
                packages = packages, versionConstraint = req.versionConstraint?.toString(),
                detail = if (installed) "verified via dpkg (not probeable)" else "not installed"
            )
        }
        val report = probe.probe(cmd)
        return when (report.status) {
            LinuxCapabilityProbe.Status.AVAILABLE -> {
                val ok = req.versionConstraint?.satisfies(report.version ?: "") ?: true
                RequirementStatus(
                    id = req.id, displayName = req.displayName,
                    state = if (ok) RequirementState.READY else RequirementState.VERSION_MISMATCH,
                    version = report.version,
                    versionConstraint = req.versionConstraint?.toString(),
                    packages = packages,
                    detail = if (ok) null else "version ${report.version} fails constraint ${req.versionConstraint}"
                )
            }
            LinuxCapabilityProbe.Status.MISSING, LinuxCapabilityProbe.Status.INSTALLABLE -> RequirementStatus(
                id = req.id, displayName = req.displayName,
                state = RequirementState.STILL_MISSING,
                packages = packages, versionConstraint = req.versionConstraint?.toString(),
                detail = report.detail ?: "not found in PATH"
            )
            LinuxCapabilityProbe.Status.BROKEN -> RequirementStatus(
                id = req.id, displayName = req.displayName,
                state = RequirementState.STILL_MISSING,
                packages = packages, versionConstraint = req.versionConstraint?.toString(),
                detail = "broken install: ${report.detail}"
            )
            LinuxCapabilityProbe.Status.UNKNOWN -> RequirementStatus(
                id = req.id, displayName = req.displayName,
                state = RequirementState.UNKNOWN,
                packages = packages, versionConstraint = req.versionConstraint?.toString(),
                detail = report.detail ?: "probe failed"
            )
        }
    }
}
