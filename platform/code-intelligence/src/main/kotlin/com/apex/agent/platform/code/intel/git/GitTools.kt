package com.apex.agent.platform.code.intel.git

import com.apex.agent.core.codetools.tools.WorkspaceFsProvider
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.platform.code.intel.GuestCommandRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Git 工具组（Spec §31）。
 *
 * 全部在 proot guest 内运行 `git`（Android host 无 git 二进制），经
 * [GuestCommandRunner] 执行，限定在当前 workspace（`git -C /workspace`）。
 *
 * 危险操作（commit / checkout / branch delete / reset）走确认机制（Spec §66），
 * 由 [com.apex.agent.ui.screen.code.CodeViewModel] 在调用前弹确认对话框。
 */

private fun noWorkspace(): String = "Error: no active Code workspace."
private fun ws(provider: WorkspaceFsProvider): String? = (provider as? CodeWorkspaceIdProvider)?.currentId()

/** 取当前 workspaceId（git 工具需 guest 路径，必须知道 active workspace）。 */
fun interface CodeWorkspaceIdProvider { fun currentId(): String? }

// ═══ git_status ═══
@Singleton
class GitStatusTool @Inject constructor(
    private val idProvider: CodeWorkspaceIdProvider,
    private val runner: GuestCommandRunner
) : AgentTool {
    override val id = "git_status"
    override val name = "Git Status"
    override val description = "Run `git status --porcelain` in the active workspace. Returns changed files."
    override val parametersSchema = """{"type":"object","properties":{}}"""
    override suspend fun execute(arguments: String): String {
        val ws = idProvider.currentId() ?: return noWorkspace()
        val r = runner.run(ws, "git -C /workspace status --porcelain")
        return if (r.isSuccess) "✅ git status:\n${r.stdout.ifBlank { "(clean)" }}"
        else "Error: ${r.error} — ${r.stdout}"
    }
}

// ═══ git_diff ═══
@Singleton
class GitDiffTool @Inject constructor(
    private val idProvider: CodeWorkspaceIdProvider,
    private val runner: GuestCommandRunner
) : AgentTool {
    override val id = "git_diff"
    override val name = "Git Diff"
    override val description = "Show git diff (unstaged by default, --staged for staged). Returns unified patch."
    override val parametersSchema = """{"type":"object","properties":{"staged":{"type":"boolean","default":false},"path":{"type":"string","description":"limit to a path"}}}"""
    override suspend fun execute(arguments: String): String {
        val ws = idProvider.currentId() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val staged = o["staged"]?.jsonPrimitive?.booleanOrNull ?: false
        val path = o["path"]?.jsonPrimitive?.contentOrNull
        val flag = if (staged) "--staged" else ""
        val pathArg = path?.let { " -- $it" } ?: ""
        val r = runner.run(ws, "git -C /workspace diff $flag$pathArg")
        return if (r.isSuccess) "✅ git diff:\n${r.stdout.ifBlank { "(no changes)" }}"
        else "Error: ${r.error} — ${r.stdout}"
    }
}

// ═══ git_log ═══
@Singleton
class GitLogTool @Inject constructor(
    private val idProvider: CodeWorkspaceIdProvider,
    private val runner: GuestCommandRunner
) : AgentTool {
    override val id = "git_log"
    override val name = "Git Log"
    override val description = "Show recent git log (oneline). Default 20 entries."
    override val parametersSchema = """{"type":"object","properties":{"count":{"type":"integer","default":20}}}"""
    override suspend fun execute(arguments: String): String {
        val ws = idProvider.currentId() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val count = o["count"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 200) ?: 20
        val r = runner.run(ws, "git -C /workspace log --oneline -$count")
        return if (r.isSuccess) "✅ git log ($count):\n${r.stdout.ifBlank { "(no commits)" }}"
        else "Error: ${r.error} — ${r.stdout}"
    }
}

// ═══ git_branch ═══
@Singleton
class GitBranchTool @Inject constructor(
    private val idProvider: CodeWorkspaceIdProvider,
    private val runner: GuestCommandRunner
) : AgentTool {
    override val id = "git_branch"
    override val name = "Git Branch"
    override val description = "List branches (`list`) or create a new branch (`create`, name required). Current branch marked with *."
    override val parametersSchema = """{"type":"object","properties":{"action":{"type":"string","enum":["list","create"],"default":"list"},"name":{"type":"string"}}}"""
    override suspend fun execute(arguments: String): String {
        val ws = idProvider.currentId() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val action = o["action"]?.jsonPrimitive?.contentOrNull ?: "list"
        val cmd = when (action) {
            "create" -> {
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'name' required for create"
                "git -C /workspace checkout -b $name"
            }
            else -> "git -C /workspace branch"
        }
        val r = runner.run(ws, cmd)
        return if (r.isSuccess) "✅ git $action:\n${r.stdout}" else "Error: ${r.error} — ${r.stdout}"
    }
}

// ═══ git_checkout ═══
@Singleton
class GitCheckoutTool @Inject constructor(
    private val idProvider: CodeWorkspaceIdProvider,
    private val runner: GuestCommandRunner
) : AgentTool {
    override val id = "git_checkout"
    override val name = "Git Checkout (DANGEROUS)"
    override val description = "Checkout a branch/commit. DANGEROUS — UI requires user confirmation before execution (Spec §66)."
    override val parametersSchema = """{"type":"object","properties":{"ref":{"type":"string","description":"branch or commit"}},"required":["ref"]}"""
    override suspend fun execute(arguments: String): String {
        val ws = idProvider.currentId() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val ref = o["ref"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'ref' required"
        // 注意：实际调用前 ViewModel 应已通过确认对话框获得用户许可。
        val r = runner.run(ws, "git -C /workspace checkout $ref")
        return if (r.isSuccess) "✅ checked out: $ref\n${r.stdout}" else "Error: ${r.error} — ${r.stdout}"
    }
}

// ═══ git_commit ═══
@Singleton
class GitCommitTool @Inject constructor(
    private val idProvider: CodeWorkspaceIdProvider,
    private val runner: GuestCommandRunner
) : AgentTool {
    override val id = "git_commit"
    override val name = "Git Commit (DANGEROUS)"
    override val description = "Stage all (-A) and commit with message. DANGEROUS — UI requires user confirmation (Spec §66). Does NOT push."
    override val parametersSchema = """{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}"""
    override suspend fun execute(arguments: String): String {
        val ws = idProvider.currentId() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val msg = o["message"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'message' required"
        // 用 -F - 从 stdin 传 message 避免引号转义；但 GuestCommandRunner 不支持 stdin 写入，
        // 故先 stage，再用单引号包裹 message（git 支持）。message 内含单引号会出问题 → 工具层拒绝。
        if (msg.contains('\'')) return "Error: message must not contain single quote"
        val r = runner.run(ws, "git -C /workspace add -A && git -C /workspace commit -m '$msg'")
        return if (r.isSuccess) "✅ committed: $msg\n${r.stdout}" else "Error: ${r.error} — ${r.stdout}"
    }
}
