package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.environment.LinuxCapabilityProbe
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T81 (D-7 / §29/§52)：terminal.linux.capabilities —— Agent 查询 Ubuntu 环境
 * 真实能力（不再 shell 猜测）。
 *
 * 输入 JSON（均可选）：
 *   {"capability": "python3"}     — 单个能力（缺省 all）
 *   {"refresh": true}             — 绕过 TTL 缓存强制重探
 *
 * 输出：结构化 CapabilityReport 列表（status: AVAILABLE/MISSING/BROKEN/
 * INSTALLABLE/UNKNOWN + version + aptPackage + detail）。
 */
class TerminalLinuxCapabilitiesTool(
    private val probe: LinuxCapabilityProbe
) : TerminalTool {

    override val id: String = "terminal.linux.capabilities"
    override val name: String = id
    override val parametersSchema: String = """
        {"type":"object","properties":{"capability":{"type":"string","description":"Single capability to probe (default: all)"},"refresh":{"type":"boolean","default":false,"description":"Bypass the TTL cache and re-probe"}},"required":[]}
    """.trimIndent()

    override val description: String = """
        Query REAL Linux environment capabilities (bash/git/python3/pip/node/npm/
        java/javac/clang/gcc/make/cmake/cargo/rustc/go) inside the Ubuntu rootfs.
        Each capability returns a structured status:
          AVAILABLE   — command found, --version works (version included)
          MISSING     — not in PATH (no known apt package)
          INSTALLABLE — not installed, but an apt package exists (aptPackage included)
          BROKEN      — found but fails to run (half-installed / ABI mismatch)
          UNKNOWN     — probe itself failed (proot/rootfs environment error)
        Arguments (JSON): {"capability": "python3", "refresh": false}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()

        val capability = json?.get("capability")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val refresh = json?.get("refresh")?.jsonPrimitive?.content == "true"

        if (refresh) probe.invalidate()

        val reports = if (capability != null) listOf(probe.probe(capability)) else probe.probeAll()

        return buildJsonObject {
            put("ok", true)
            put("count", reports.size)
            put("capabilities", buildJsonArray {
                reports.forEach { r ->
                    add(buildJsonObject {
                        put("capability", r.capability)
                        put("status", r.status.name)
                        r.version?.let { put("version", it) }
                        r.aptPackage?.let { put("aptPackage", it) }
                        r.detail?.let { put("detail", it.take(300)) }
                    })
                }
            })
        }.toString()
    }
}

/**
 * T81 (D-7 / §30/§52)：terminal.linux.repair —— 单轮自动修复编排
 * （detect → repair（每维度一次）→ verify；无循环）。
 */
class TerminalLinuxRepairTool(
    private val repairService: com.apex.agent.platform.terminal.health.EnvironmentRepairService
) : TerminalTool {

    override val id: String = "terminal.linux.repair"
    override val name: String = id
    override val parametersSchema: String = """
        {"type":"object","properties":{},"required":[]}
    """.trimIndent()

    override val description: String = """
        Run ONE bounded auto-repair round on the Linux environment:
        detect (health check) → repair each repairable dimension once
        (rootfs → provisioner.repair; apt → dpkg --configure -a) → verify.
        No loops, no retry storms — call again explicitly if needed.
        Returns the repair actions taken and the post-repair health report.
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val report = repairService.autoRepair()
        return buildJsonObject {
            put("ok", true)
            put("repairedCount", report.repaired.size)
            put("verifiedHealthy", report.verifiedHealthy)
            put("actions", buildJsonArray {
                report.repaired.forEach { a ->
                    add(buildJsonObject {
                        put("dimension", a.dimension)
                        put("action", a.action)
                        put("outcome", a.outcome)
                        a.detail?.let { put("detail", it.take(300)) }
                    })
                }
            })
            put("overall", report.verification?.overall?.name ?: "UNKNOWN")
        }.toString()
    }
}
