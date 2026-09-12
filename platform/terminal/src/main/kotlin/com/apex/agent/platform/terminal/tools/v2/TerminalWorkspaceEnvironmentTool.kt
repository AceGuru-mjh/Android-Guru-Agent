package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.environment.ProjectEnvironmentCoordinator
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: terminal.workspace.environment — P83（Ubuntu 开发环境闭环）
 *
 * Project → Analyzer → Ubuntu → Toolchain 的入口：
 *   - analyze  只读：workspace 标记文件扫描（python/node/jdk/cpp/rust/go profile）→
 *              每项工具链的真实能力状态（probe / dpkg），不装任何东西。
 *   - ensure   闭环：Ubuntu 生命周期 ensureReady → 项目分析 → 缺失工具链批量 apt
 *              install → 复测汇报。适合“把当前 workspace 变成该项目可构建环境”。
 *
 * JSON Schema (input):  { action: "analyze"|"ensure", workspaceId?: string,
 *                         installMissing?: boolean }
 * JSON Schema (output): { workspaceId, workspaceRoot?, lifecyclePhase, detectedLanguages,
 *                        lockfiles, requirements: [ { id, displayName, state, version?,
 *                        versionConstraint?, packages, detail? } ], installedPackages,
 *                        durationMs, allReady, detail? }
 *
 * Errors: WorkspaceError:ResolveFailed（ensure 汇报到 detail，不抛异常——报告仍返回）
 */
class TerminalWorkspaceEnvironmentTool(
    private val coordinator: ProjectEnvironmentCoordinator
) : TerminalTool {
    override val id: String = "terminal.workspace.environment"
    override val name: String = id
    override val description: String = """
        Project-aware Ubuntu developer environment. Analyzes a workspace's marker files
        (requirements.txt/package.json/build.gradle.kts/Cargo.toml/go.mod/CMakeLists/...)
        to detect the project's languages, then reports — and with action=ensure installs —
        the missing toolchains (python3/pip, node/npm, default-jdk, gcc/g++/make/cmake,
        rustc/cargo, golang) inside the Ubuntu rootfs. ensure first makes the Ubuntu
        lifecycle READY (rootfs + bootstrap), then batch-installs missing packages and
        re-verifies each requirement honestly. Actions: analyze (read-only status),
        ensure (install missing toolchains for this project).
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"action":{"type":"string","enum":["analyze","ensure"],"default":"analyze","description":"analyze = read-only project language + toolchain status; ensure = make Ubuntu READY + install missing toolchains for this project"},"workspaceId":{"type":"string","description":"Workspace id (default: 'default'); files in it are the project root"},"installMissing":{"type":"boolean","default":true,"description":"ensure: batch apt install missing toolchain packages"}},"required":["action"]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { throw IllegalArgumentException("TerminalError:InvalidInput — 参数不是合法 JSON 对象") }
        val action = json["action"]?.jsonPrimitive?.content ?: "analyze"
        val workspaceId = json["workspaceId"]?.jsonPrimitive?.contentOrNull
        val installMissing = json["installMissing"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true

        val report = when (action) {
            "analyze" -> coordinator.analyze(workspaceId)
            "ensure" -> coordinator.ensure(workspaceId, installMissing = installMissing)
            else -> throw IllegalArgumentException(
                "TerminalError:InvalidInput — 未知 action '$action'（可用: analyze/ensure）"
            )
        }
        return reportJson(report)
    }

    private fun reportJson(r: ProjectEnvironmentCoordinator.ProjectEnvironmentReport): String =
        buildJsonObject {
            put("workspaceId", JsonPrimitive(r.workspaceId))
            r.workspaceRoot?.let { put("workspaceRoot", JsonPrimitive(it)) }
            put("lifecyclePhase", JsonPrimitive(r.lifecyclePhase))
            put("detectedLanguages", buildJsonArray {
                r.detectedLanguages.sorted().forEach { add(JsonPrimitive(it)) }
            })
            put("lockfiles", buildJsonArray {
                r.lockfiles.forEach { add(JsonPrimitive(it)) }
            })
            put("requirements", buildJsonArray {
                r.requirements.forEach { req -> add(requirementJson(req)) }
            })
            put("installedPackages", buildJsonArray {
                r.installedPackages.forEach { add(JsonPrimitive(it)) }
            })
            put("durationMs", JsonPrimitive(r.durationMs))
            put("allReady", JsonPrimitive(r.allReady))
            r.detail?.let { put("detail", JsonPrimitive(it)) }
        }.toString()

    private fun requirementJson(
        req: ProjectEnvironmentCoordinator.RequirementStatus
    ) = buildJsonObject {
        put("id", JsonPrimitive(req.id))
        put("displayName", JsonPrimitive(req.displayName))
        put("state", JsonPrimitive(req.state.name))
        req.version?.let { put("version", JsonPrimitive(it)) }
        req.versionConstraint?.let { put("versionConstraint", JsonPrimitive(it)) }
        put("packages", buildJsonArray {
            req.packages.forEach { add(JsonPrimitive(it)) }
        })
        req.detail?.let { put("detail", JsonPrimitive(it)) }
    }
}
