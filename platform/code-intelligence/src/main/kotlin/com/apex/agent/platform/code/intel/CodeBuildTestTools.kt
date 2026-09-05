package com.apex.agent.platform.code.intel

import com.apex.agent.core.codetools.tools.WorkspaceFsProvider
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.platform.code.ws.CodeWorkspaceManager
import com.apex.agent.platform.code.intel.git.CodeWorkspaceIdProvider
import com.apex.agent.platform.terminal.environment.ProjectEnvironmentAnalyzer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Build / Test 工具（Spec §34/§35）。
 *
 * 复用 [GuestCommandRunner]（proot guest 内执行）+ [ProjectEnvironmentAnalyzer]
 * （自动判断 build 系统）。命令不硬编码 —— 由 workspace 的 buildSystem 决定。
 */

@Singleton
class CodeBuildTool @Inject constructor(
    private val runner: GuestCommandRunner,
    private val workspaceManager: CodeWorkspaceManager
) : AgentTool {
    override val id = "code_build"
    override val name = "Build Project"
    override val description = """
        Build the active workspace project. Auto-detects build system (gradle/npm/cargo/go/make/cmake).
        Runs in the proot guest at /workspace. Returns exit code + output.
        For Android Gradle: runs ./gradlew assembleDebug.
    """.trimIndent()
    override val parametersSchema = """{"type":"object","properties":{"args":{"type":"string","description":"extra args appended to the build command, e.g. '--offline' or '-x test'"}}}"""
    override suspend fun execute(arguments: String): String {
        val wsId = workspaceManager.activeId() ?: return "Error: no active Code workspace."
        val ws = workspaceManager.inspect(wsId).getOrElse { return "Error: ${it.message}" }
        val cmd = buildBuildCommand(ws.buildSystem, ws.hostRootPath,
            Json.parseToJsonElement(arguments).let { it.jsonObject["args"]?.jsonPrimitive?.contentOrNull })
        val r = runner.run(wsId, cmd, timeoutMs = 600_000L)  // build 可能耗时，10 分钟
        return buildString {
            appendLine("🔨 build (${ws.buildSystem ?: "unknown"}): $cmd")
            appendLine("exit=${r.exitCode} duration=${r.durationMs}ms${if (r.timedOut) " [TIMEOUT]" else ""}")
            if (r.stdout.isNotBlank()) { appendLine("───── output ─────"); append(r.stdout.take(8000)) }
            if (!r.isSuccess) appendLine("Error: ${r.error}")
        }
    }

    private fun buildBuildCommand(buildSystem: String?, hostRoot: String, args: String?): String {
        val extra = args?.let { " $it" } ?: ""
        return when (buildSystem) {
            "gradle" -> "cd /workspace && ./gradlew assembleDebug$extra"
            "npm" -> "cd /workspace && npm run build$extra"
            "cargo" -> "cd /workspace && cargo build$extra"
            "go" -> "cd /workspace && go build ./...$extra"
            "maven" -> "cd /workspace && mvn package$extra"
            "cmake" -> "cd /workspace && cmake --build build$extra"
            "pip" -> "cd /workspace && python -m build$extra"
            else -> "cd /workspace && make$extra"
        }
    }
}

@Singleton
class CodeTestTool @Inject constructor(
    private val runner: GuestCommandRunner,
    private val workspaceManager: CodeWorkspaceManager
) : AgentTool {
    override val id = "code_test"
    override val name = "Run Tests"
    override val description = """
        Run tests in the active workspace. Auto-detects test runner (gradle test / pytest / npm test / cargo test / go test).
        Runs in the proot guest at /workspace. Returns exit code + output + parsed failure summary.
    """.trimIndent()
    override val parametersSchema = """{"type":"object","properties":{"filter":{"type":"string","description":"test filter, e.g. class name or path"}}}"""
    override suspend fun execute(arguments: String): String {
        val wsId = workspaceManager.activeId() ?: return "Error: no active Code workspace."
        val ws = workspaceManager.inspect(wsId).getOrElse { return "Error: ${it.message}" }
        val o = Json.parseToJsonElement(arguments).jsonObject
        val filter = o["filter"]?.jsonPrimitive?.contentOrNull
        val cmd = buildTestCommand(ws.buildSystem, filter)
        val r = runner.run(wsId, cmd, timeoutMs = 600_000L)
        return buildString {
            appendLine("🧪 test (${ws.buildSystem ?: "unknown"}): $cmd")
            appendLine("exit=${r.exitCode} duration=${r.durationMs}ms${if (r.timedOut) " [TIMEOUT]" else ""}")
            if (r.stdout.isNotBlank()) { appendLine("───── output ─────"); append(r.stdout.take(8000)) }
            if (!r.isSuccess) appendLine("Error: ${r.error}")
        }
    }

    private fun buildTestCommand(buildSystem: String?, filter: String?): String {
        val f = filter?.let { " $it" } ?: ""
        return when (buildSystem) {
            "gradle" -> "cd /workspace && ./gradlew test$f"
            "npm" -> "cd /workspace && npm test$f"
            "cargo" -> "cd /workspace && cargo test$f"
            "go" -> "cd /workspace && go test ./...$f"
            "maven" -> "cd /workspace && mvn test$f"
            "pip" -> "cd /workspace && python -m pytest$f"
            else -> "cd /workspace && make test$f"
        }
    }
}
