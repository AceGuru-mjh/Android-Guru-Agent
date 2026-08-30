package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.network.LinuxNetworkProbe
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T76: Agent tool — terminal.linux.network
 *
 * 分维网络诊断：DNS / HTTP / HTTPS / APT_REPOSITORY。
 * 不再笼统报"网络失败"——Agent 据此精准决策（DNS→修 resolv.conf；TLS→装
 * ca-certificates；HTTP→查防火墙；APT repo→修 sources.list）。
 *
 * JSON (input):  { action?: "diagnose"|"dns"="diagnose" }
 *   - diagnose: 完整诊断（跑 apt-get update 作为端到端探针，较重）
 *   - dns: 仅读 resolv.conf 配置（轻量，不跑 apt）
 * JSON (output):
 *   { dns:{status,detail}, http:{status,detail}, https:{status,detail},
 *     aptRepository:{status,detail}, overall, ready, timestamp, message }
 */
class TerminalLinuxNetworkTool(
    private val networkProbe: LinuxNetworkProbe
) : TerminalTool {
    override val id: String = "terminal.linux.network"
    override val name: String = id
    override val description: String = """
        Per-dimension network diagnostics for the Ubuntu environment: DNS / HTTP / HTTPS /
        APT_REPOSITORY. Returns READY/DEGRADED/FAILED/UNKNOWN per dimension plus an overall
        rollup. Use this to pinpoint why apt update fails (DNS misconfig vs TLS/CA missing vs
        firewall vs bad sources.list). The full 'diagnose' runs apt-get update as an honest
        end-to-end probe; 'dns' is a lightweight resolv.conf-only check.
    """.trimIndent()

    override val parametersSchema: String = """
        {"type":"object","properties":{"action":{"type":"string","enum":["diagnose","dns"],"default":"diagnose"}},"required":[]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
        val action = json?.get("action")?.jsonPrimitive?.content ?: "diagnose"

        if (action == "dns") {
            val dns = networkProbe.probeDnsOnly()
            return buildJsonObject {
                put("dns", buildProbeJson(dns))
                put("overall", dns.status.name)
                put("ready", dns.status == LinuxNetworkProbe.ProbeStatus.READY)
                put("message", "DNS-only probe (resolv.conf config check)")
            }.toString()
        }

        val diag = networkProbe.diagnose()
        return buildJsonObject {
            put("dns", buildProbeJson(diag.dns))
            put("http", buildProbeJson(diag.http))
            put("https", buildProbeJson(diag.https))
            put("aptRepository", buildProbeJson(diag.aptRepository))
            put("overall", diag.overall.name)
            put("ready", diag.ready)
            put("timestamp", diag.timestamp.toString())
            put("message", if (diag.ready) "network fully functional" else "network issues detected — see per-dimension details")
        }.toString()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putProbe(
        name: String, p: LinuxNetworkProbe.ProbeResult
    ) {
        put(name, buildJsonObject {
            put("status", p.status.name)
            put("detail", p.detail)
        })
    }

    private fun buildProbeJson(p: LinuxNetworkProbe.ProbeResult) = buildJsonObject {
        put("status", p.status.name)
        put("detail", p.detail)
    }
}
