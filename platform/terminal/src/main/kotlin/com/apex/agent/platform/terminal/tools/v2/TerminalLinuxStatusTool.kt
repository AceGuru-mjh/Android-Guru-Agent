package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.health.LinuxEnvironmentHealth
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T76: Agent tool — terminal.linux.status
 *
 * 返回 Linux 环境的统一健康快照（6 维度 + bootstrap + overall）。
 * Agent 用此判断 Ubuntu 是否可用、哪一维度降级、是否需要 bootstrap/repair。
 *
 * JSON (input):  { quick?: bool=false }
 *   quick=true 跳过 apt proot 探针（仅文件存在性）—— 用于频繁轮询。
 * JSON (output):
 *   { rootfs, proot, network, apt, home, workspace, bootstrap, overall,
 *     summary, ready, timestamp, dimensions: { name:{status,code,message,repairable} } }
 */
class TerminalLinuxStatusTool(
    private val health: LinuxEnvironmentHealth
) : TerminalTool {
    override val id: String = "terminal.linux.status"
    override val name: String = id
    override val description: String = """
        Unified health snapshot of the Ubuntu Linux environment (6 dimensions: rootfs, proot,
        network, apt, home, workspace + bootstrap state). Returns READY/DEGRADED/FAILED per
        dimension plus an overall rollup. Use this to check whether the linux-ubuntu backend
        is usable and which subsystem (if any) needs bootstrap/repair. Lightweight by default
        (no apt-get update); pass quick=true for an even faster file-existence-only check.
    """.trimIndent()

    override val parametersSchema: String = """
        {"type":"object","properties":{"quick":{"type":"boolean","default":false,"description":"Skip apt proot probe (file-existence only) for fast polling"}},"required":[]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
        val quick = json?.get("quick")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val report = if (quick) health.quickCheck() else health.check()
        return buildJsonObject {
            put("rootfs", report.rootfs.status.name)
            put("proot", report.proot.status.name)
            put("network", report.network.status.name)
            put("apt", report.apt.status.name)
            put("home", report.home.status.name)
            put("workspace", report.workspace.status.name)
            put("bootstrap", report.bootstrap.status.name)
            put("overall", report.overall.name)
            put("ready", report.ready)
            put("summary", report.summary)
            put("timestamp", report.timestamp.toString())
            put("dimensions", buildDimensionsJson(report))
        }.toString()
    }

    private fun buildDimensionsJson(report: LinuxEnvironmentHealth.LinuxHealthReport): JsonObject {
        return buildJsonObject {
            putDimension("rootfs", report.rootfs)
            putDimension("proot", report.proot)
            putDimension("network", report.network)
            putDimension("apt", report.apt)
            putDimension("home", report.home)
            putDimension("workspace", report.workspace)
            putDimension("bootstrap", report.bootstrap)
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putDimension(
        name: String, dim: LinuxEnvironmentHealth.DimensionCheck
    ) {
        put(name, buildJsonObject {
            put("status", dim.status.name)
            put("code", dim.code)
            put("message", dim.message)
            put("repairable", dim.repairable)
        })
    }
}
