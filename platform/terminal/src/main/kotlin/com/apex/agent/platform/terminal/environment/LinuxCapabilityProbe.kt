package com.apex.agent.platform.terminal.environment

import com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory
import com.apex.agent.platform.terminal.proot.PRootCommand
import com.apex.agent.platform.terminal.proot.PRootCommandBuilderImpl
import com.apex.agent.platform.terminal.proot.PRootLaunchRequest
import com.apex.agent.platform.terminal.proot.ProotExecutor
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import java.util.concurrent.ConcurrentHashMap

/**
 * T81 (D-7 / §29)：环境能力真实探测。
 *
 * 背景：EnvironmentModel 的 12 capability 模型 + profile + adaptive loop
 *（2000+ 行）生产零接线 —— EnvironmentSnapshot 无生产者（快照恒 EMPTY →
 * 一切判 MISSING）。Agent 只能通过 shell 猜测环境。
 *
 * 本探测经 [ProotExecutor] 在真实 rootfs 内执行 `which <cmd>` + `<cmd> --version`，
 * 产出结构化 CapabilityReport（状态 + 版本 + 可安装性）—— Agent 直接查询
 * terminal.linux.capabilities，不再猜。
 *
 * 状态语义（§29）：
 *  - AVAILABLE：which 命中且 --version 成功（version 提取自首行输出）
 *  - MISSING：which 未命中（包名已知 → INSTALLABLE）
 *  - BROKEN：which 命中但 --version 失败（半安装/ABI 损坏）
 *  - INSTALLABLE：MISSING 且 apt 包名已知（可引导 install）
 *  - UNKNOWN：探测本身失败（proot/rootfs 环境异常 —— 不与 MISSING 混淆）
 */
class LinuxCapabilityProbe(
    private val contextFactory: LinuxExecutionContextFactory,
    private val executor: ProotExecutor,
    private val commandBuilder: PRootCommandBuilderImpl = PRootCommandBuilderImpl(),
    /** 探测超时（每个命令）。 */
    private val probeTimeoutMs: Long = 10_000L,
    /** 结果缓存 TTL（能力安装/移除后应 invalidate —— [invalidate]）。 */
    private val ttlMs: Long = 5 * 60_000L,
    /** T81：可注入执行钩子（测试替换；default 转发给 [executor]）。 */
    private val execFn: suspend (PRootCommand) -> com.apex.agent.platform.terminal.proot.BoundedExecution =
        { cmd -> executor.executeBounded(cmd, timeoutMs = probeTimeoutMs, maxOutputBytes = 64 * 1024L) }
) {

    enum class Status { AVAILABLE, MISSING, BROKEN, INSTALLABLE, UNKNOWN }

    data class CapabilityReport(
        val capability: String,
        val status: Status,
        val version: String? = null,
        /** 该能力对应的 apt 包（INSTALLABLE 修复路径；null = 未知包）。 */
        val aptPackage: String? = null,
        val detail: String? = null
    )

    private data class CacheEntry(val report: CapabilityReport, val at: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * 探测一个能力（带 TTL 缓存）。探测失败返回 UNKNOWN（含结构化 detail），
     * 绝不静默映射为 MISSING（环境异常 ≠ 未安装）。
     */
    suspend fun probe(capability: String): CapabilityReport {
        val spec = CAPABILITY_SPECS[capability.lowercase()]
            ?: return CapabilityReport(capability, Status.UNKNOWN, detail = "unknown capability — supported: ${CAPABILITY_SPECS.keys.sorted()}")
        cache[spec.probeCommand]?.let { e ->
            if (System.currentTimeMillis() - e.at < ttlMs) return e.report
        }
        val report = probeUncached(capability, spec)
        cache[spec.probeCommand] = CacheEntry(report, System.currentTimeMillis())
        return report
    }

    /** 探测全部已知能力。 */
    suspend fun probeAll(): List<CapabilityReport> =
        CAPABILITY_SPECS.keys.sorted().map { probe(it) }

    /** 能力变更后清缓存（apt install/remove 成功后调用）。 */
    fun invalidate() = cache.clear()

    private suspend fun probeUncached(capability: String, spec: CapabilitySpec): CapabilityReport {
        val ctx = contextFactory.resolve().getOrElse { e ->
            return CapabilityReport(capability, Status.UNKNOWN, aptPackage = spec.aptPackage,
                detail = "environment unavailable: ${e.message}")
        }
        // 1. which <cmd> —— 存在性
        val which = runGuest(ctx, "which", spec.probeCommand)
        if (which == null) {
            return CapabilityReport(capability, Status.UNKNOWN, aptPackage = spec.aptPackage,
                detail = "probe failed (proot/exec error)")
        }
        if (which.exitCode != 0) {
            // 不存在 —— MISSING / INSTALLABLE
            val status = if (spec.aptPackage != null) Status.INSTALLABLE else Status.MISSING
            return CapabilityReport(capability, status, aptPackage = spec.aptPackage,
                detail = "not found in PATH")
        }
        // 2. <cmd> --version —— 可运行性（BROKEN 检测）
        val ver = runGuest(ctx, spec.probeCommand, "--version")
        if (ver == null) {
            return CapabilityReport(capability, Status.UNKNOWN, aptPackage = spec.aptPackage,
                detail = "version probe failed (proot/exec error)")
        }
        return if (ver.exitCode == 0) {
            val version = ver.stdout.lineSequence().firstOrNull { it.isNotBlank() }
                ?.substringAfterLast(' ')?.trim()?.takeIf { it.isNotEmpty() }
            CapabilityReport(capability, Status.AVAILABLE, version = version, aptPackage = spec.aptPackage)
        } else {
            CapabilityReport(capability, Status.BROKEN, aptPackage = spec.aptPackage,
                detail = "found but --version failed (exit ${ver.exitCode}): ${ver.stderr.take(200)}")
        }
    }

    private suspend fun runGuest(
        ctx: com.apex.agent.platform.terminal.proot.LinuxExecutionContext,
        vararg argv: String
    ): com.apex.agent.platform.terminal.proot.BoundedExecution? {
        return try {
            val launch = PRootLaunchRequest(
                rootfs = ctx.rootfs,
                executable = argv.first(),
                arguments = argv.drop(1),
                workingDirectory = WorkspacePath("/root"),
                environment = ctx.aptGuestEnv,   // 非交互探测（TERM=dumb）
                binds = listOf(ctx.homeBind),
                terminalMode = com.apex.agent.platform.terminal.api.TerminalMode.AUTO,
                fakeRoot = true,
                killOnExit = true
            )
            val command: PRootCommand = commandBuilder.build(
                launch,
                AbsolutePath(ctx.prootBinary.absolutePath),
                AbsolutePath(ctx.rootfsDir.absolutePath),
                AbsolutePath(ctx.workspaceDir.absolutePath)
            )
            execFn(command)
        } catch (e: Exception) {
            null
        }
    }

    data class CapabilitySpec(
        val probeCommand: String,
        val aptPackage: String?
    )

    companion object {
        /**
         * 能力表：probe 命令 → apt 包（INSTALLABLE 判定依据 —— 包名来自
         * Ubuntu noble 的标准包名）。
         */
        val CAPABILITY_SPECS: Map<String, CapabilitySpec> = mapOf(
            "bash" to CapabilitySpec("bash", "bash"),
            "git" to CapabilitySpec("git", "git"),
            "python3" to CapabilitySpec("python3", "python3"),
            "pip" to CapabilitySpec("pip3", "python3-pip"),
            "node" to CapabilitySpec("node", "nodejs"),
            "npm" to CapabilitySpec("npm", "npm"),
            "java" to CapabilitySpec("java", "default-jdk-headless"),
            "javac" to CapabilitySpec("javac", "default-jdk-headless"),
            "clang" to CapabilitySpec("clang", "clang"),
            "gcc" to CapabilitySpec("gcc", "gcc"),
            "make" to CapabilitySpec("make", "make"),
            "cmake" to CapabilitySpec("cmake", "cmake"),
            "cargo" to CapabilitySpec("cargo", "cargo"),
            "rustc" to CapabilitySpec("rustc", "rustc"),
            "go" to CapabilitySpec("go", "golang-go")
        )
    }
}
