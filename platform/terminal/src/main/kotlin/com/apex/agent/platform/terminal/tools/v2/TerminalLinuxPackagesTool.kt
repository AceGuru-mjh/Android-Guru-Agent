package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.pkg.LinuxPackageManager
import com.apex.agent.platform.terminal.pkg.PackageInstallOptions
import com.apex.agent.platform.terminal.pkg.PackageRemoveOptions
import com.apex.agent.platform.terminal.pkg.PackageSpec
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T76: Agent tool — terminal.linux.packages
 *
 * 统一 package API（结构化参数，不让 Agent 直接拼 apt 命令）。经 UbuntuAptPackageManager
 * → ProotExecutor → PRoot → Ubuntu rootfs，与交互式 terminal session 共享同一 dpkg
 * database（不存在两个互相不知道状态的 Ubuntu 环境，T76 §3 / §24）。
 *
 * JSON (input):
 *   { action: "update"|"install"|"remove"|"upgrade"|"search"|"info"|"isInstalled"|"installed"|"status",
 *     packages?: ["git","python3"],       // install/remove/upgrade/isInstalled/info
 *     query?: "python",                   // search
 *     options?: { noInstallRecommends?, purge? } }
 * JSON (output):
 *   { ok, action, state: "SUCCEEDED"|"FAILED"|"CANCELLED"|"TIMED_OUT",
 *     exitCode?, installed?, removed?, upgraded?, alreadySatisfied?, failedPackages?,
 *     stdout?, stderr?, stdoutTruncated?, stderrTruncated?, durationMs?,
 *     results?, version?, installed_bool?, error?, message }
 *
 * 输出有界（首-N + 尾-M，默认 1 MB）—— 不让 apt 输出撑爆 Agent context（T76 §35）。
 */
class TerminalLinuxPackagesTool(
    private val packageManager: LinuxPackageManager
) : TerminalTool {
    override val id: String = "terminal.linux.packages"
    override val name: String = id
    override val description: String = """
        Structured Ubuntu package management (wraps apt-get/dpkg via the same PRoot+rootfs
        stack as interactive terminal sessions — package state stays consistent). Actions:
        update (apt-get update), install, remove, upgrade, search, info, isInstalled,
        installed (list installed), status. Pass packages as a JSON array (never shell-inject).
        Output is bounded (first-N + last-M, default 1MB) to avoid blowing Agent context.
        On failure returns a structured error code (APT_LOCKED → retry later;
        NETWORK_DNS_FAILED → repair env; PACKAGE_NOT_FOUND → change request).
    """.trimIndent()

    override val parametersSchema: String = """
        {"type":"object","properties":{"action":{"type":"string","enum":["update","install","remove","upgrade","search","info","isInstalled","installed","status"]},"packages":{"type":"array","items":{"type":"string"}},"query":{"type":"string"},"options":{"type":"object","properties":{"noInstallRecommends":{"type":"boolean"},"purge":{"type":"boolean"}}}},"required":["action"]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return errorResult("InvalidInput", "arguments is not valid JSON")
        val action = json["action"]?.jsonPrimitive?.content
            ?: return errorResult("InvalidInput", "missing 'action'")

        return when (action) {
            "update" -> handleUpdate()
            "install" -> handleInstall(json)
            "remove" -> handleRemove(json)
            "upgrade" -> handleUpgrade(json)
            "search" -> handleSearch(json)
            "info" -> handleInfo(json)
            "isInstalled" -> handleIsInstalled(json)
            "installed" -> handleInstalled(json)
            "status" -> handleStatus()
            else -> errorResult("InvalidInput", "unknown action: $action")
        }
    }

    private suspend fun handleUpdate(): String {
        val op = packageManager.update()
        return opToJson(op, "update")
    }

    private suspend fun handleInstall(json: JsonObject): String {
        val packages = parsePackages(json) ?: return errorResult("InvalidInput", "missing 'packages' array")
        if (packages.isEmpty()) return errorResult("InvalidInput", "'packages' is empty")
        val opts = parseInstallOptions(json)
        val op = packageManager.install(packages, opts)
        return opToJson(op, "install")
    }

    private suspend fun handleRemove(json: JsonObject): String {
        val packages = parsePackages(json) ?: return errorResult("InvalidInput", "missing 'packages' array")
        if (packages.isEmpty()) return errorResult("InvalidInput", "'packages' is empty")
        val opts = parseRemoveOptions(json)
        val op = packageManager.remove(packages, opts)
        return opToJson(op, "remove")
    }

    private suspend fun handleUpgrade(json: JsonObject): String {
        val packages = parsePackages(json) ?: emptyList()
        val op = packageManager.upgrade(packages)
        return opToJson(op, "upgrade")
    }

    private suspend fun handleSearch(json: JsonObject): String {
        val query = json["query"]?.jsonPrimitive?.content
            ?: return errorResult("InvalidInput", "missing 'query'")
        val result = packageManager.search(query)
        return buildJsonObject {
            put("ok", true)
            put("action", "search")
            put("query", result.query)
            put("count", result.results.size)
            put("results", buildJsonArray {
                for (p in result.results) {
                    add(buildJsonObject {
                        put("name", p.name)
                        put("description", p.description ?: "")
                    })
                }
            })
        }.toString()
    }

    private suspend fun handleInfo(json: JsonObject): String {
        val name = json["packages"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
            ?: json["query"]?.jsonPrimitive?.content
            ?: return errorResult("InvalidInput", "missing package name (packages[0] or query)")
        val info = packageManager.info(name)
        return buildJsonObject {
            put("ok", true)
            put("action", "info")
            put("name", info.name)
            put("version", info.version ?: "")
            put("architecture", info.architecture ?: "")
            put("installed", info.installed)
            put("candidateVersion", info.candidateVersion ?: "")
            put("description", info.description ?: "")
            put("sizeBytes", info.sizeBytes?.toString() ?: "")
        }.toString()
    }

    private suspend fun handleIsInstalled(json: JsonObject): String {
        val name = json["packages"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
            ?: json["query"]?.jsonPrimitive?.content
            ?: return errorResult("InvalidInput", "missing package name")
        val installed = packageManager.isInstalled(name)
        val version = if (installed) packageManager.installedVersion(name) else null
        return buildJsonObject {
            put("ok", true)
            put("action", "isInstalled")
            put("name", name)
            put("installed", installed)
            put("version", version ?: "")
        }.toString()
    }

    private suspend fun handleInstalled(json: JsonObject): String {
        // 列出已装包：用 dpkg-query --list（经 packageManager.search 不合适；这里用 info 的轻量探针）
        // 简化：返回 status 的 brokenPackages（不完整但契约稳定；完整列表由 Agent 在 shell 内 dpkg -l 获取）
        val status = packageManager.status()
        return buildJsonObject {
            put("ok", true)
            put("action", "installed")
            put("available", status.available)
            put("manager", status.manager)
            put("brokenPackages", buildJsonArray { status.brokenPackages.forEach { add(JsonPrimitive(it)) } })
            put("message", "for full list, run `dpkg -l` inside terminal.create(backend=\"linux-ubuntu\")")
        }.toString()
    }

    private suspend fun handleStatus(): String {
        val status = packageManager.status()
        return buildJsonObject {
            put("ok", true)
            put("action", "status")
            put("available", status.available)
            put("manager", status.manager)
            put("version", status.version ?: "")
            put("databaseState", status.databaseState.name)
            put("lockState", status.lockState.name)
            put("metadataState", status.metadataState.name)
            put("brokenPackages", buildJsonArray { status.brokenPackages.forEach { add(JsonPrimitive(it)) } })
        }.toString()
    }

    // ──────────────────────────────────────────────────────────────────
    // 输出构造
    // ──────────────────────────────────────────────────────────────────

    private fun opToJson(
        op: com.apex.agent.platform.terminal.pkg.PackageOperation,
        action: String
    ): String {
        val result = op.result
        val error = op.error
        return buildJsonObject {
            put("ok", op.state == com.apex.agent.platform.terminal.pkg.PackageOperationState.SUCCEEDED)
            put("action", action)
            put("state", op.state.name)
            put("operationId", op.id)
            op.exitCode?.let { put("exitCode", it) }
            if (result != null) {
                put("installed", buildJsonArray { result.installed.forEach { add(JsonPrimitive(it)) } })
                put("removed", buildJsonArray { result.removed.forEach { add(JsonPrimitive(it)) } })
                put("upgraded", buildJsonArray { result.upgraded.forEach { add(JsonPrimitive(it)) } })
                put("alreadySatisfied", buildJsonArray { result.alreadySatisfied.forEach { add(JsonPrimitive(it)) } })
                put("failedPackages", buildJsonArray { result.failedPackages.forEach { add(JsonPrimitive(it)) } })
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("stdoutTruncated", result.stdoutTruncated)
                put("stderrTruncated", result.stderrTruncated)
                put("maxOutputBytes", result.maxOutputBytes)
                put("durationMs", result.durationMs)
            }
            if (error != null) {
                put("error", buildJsonObject {
                    put("code", error.code.name)
                    put("message", error.message)
                    put("recoverable", error.recoverable)
                })
            }
            put("message", when (op.state) {
                com.apex.agent.platform.terminal.pkg.PackageOperationState.SUCCEEDED -> "$action succeeded"
                com.apex.agent.platform.terminal.pkg.PackageOperationState.FAILED -> "$action failed: ${error?.message ?: "see stderr"}"
                com.apex.agent.platform.terminal.pkg.PackageOperationState.CANCELLED -> "$action cancelled"
                com.apex.agent.platform.terminal.pkg.PackageOperationState.TIMED_OUT -> "$action timed out — retry later"
                else -> "$action state: ${op.state}"
            })
        }.toString()
    }

    private fun errorResult(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

    // ──────────────────────────────────────────────────────────────────
    // 参数解析
    // ──────────────────────────────────────────────────────────────────

    private fun parsePackages(json: JsonObject): List<PackageSpec>? {
        val arr = json["packages"]?.jsonArray ?: return null
        return arr.mapNotNull { el ->
            val name = el.jsonPrimitive.content.trim()
            if (name.isNotEmpty()) PackageSpec(name) else null
        }
    }

    private fun parseInstallOptions(json: JsonObject): PackageInstallOptions {
        val opts = json["options"]?.jsonObject
        val noInstallRecommends = opts?.get("noInstallRecommends")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        return PackageInstallOptions(noInstallRecommends = noInstallRecommends)
    }

    private fun parseRemoveOptions(json: JsonObject): PackageRemoveOptions {
        val opts = json["options"]?.jsonObject
        val purge = opts?.get("purge")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        return PackageRemoveOptions(purge = purge)
    }
}
