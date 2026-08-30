package com.apex.agent.platform.code.intel

import com.apex.agent.core.codetools.lsp.LspClient
import com.apex.agent.core.codetools.lsp.locationToWorkspacePath
import com.apex.agent.core.codetools.lsp.workspacePathToUri
import com.apex.agent.core.codetools.problems.Problem
import com.apex.agent.core.codetools.problems.ProblemsAggregator
import com.apex.agent.core.codetools.tools.WorkspaceFsProvider
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.platform.code.intel.git.CodeWorkspaceIdProvider
import com.apex.agent.platform.code.intel.lsp.LanguageServerManager
import com.apex.agent.platform.code.intel.lsp.inferLanguageId
import com.apex.agent.platform.code.ws.CodeWorkspaceManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Code Intelligence 工具（Spec §28/§29/§18/§30）。
 *
 * 语义工具：code_definition / code_references / code_hover / code_diagnostics / code_rename。
 *
 * **优雅降级（Spec §79）**：每个工具先尝试 LSP；LSP 不可用时（v1 默认），
 * code_definition/code_references/code_hover 返回提示并建议用 code_search（文本搜索）；
 * code_diagnostics 走 ProblemsAggregator（build/test 解析，不依赖 LSP）；code_rename
 * v1 不支持（LSP 未就绪），建议用 code_edit 手动改 + code_search 验证。
 */

private fun noWorkspace(): String = "Error: no active Code workspace."

// ═══ code_definition ═══
@Singleton
class CodeDefinitionTool @Inject constructor(
    private val lspManager: LanguageServerManager,
    private val idProvider: CodeWorkspaceIdProvider
) : AgentTool {
    override val id = "code_definition"
    override val name = "Goto Definition"
    override val description = """
        Find the definition of the symbol at file:line:column (1-based).
        Uses LSP textDocument/definition. If LSP unavailable, falls back to code_search with the symbol name.
        Input: file (relative to workspace), line (1-based), column (1-based).
    """.trimIndent()
    override val parametersSchema = """{"type":"object","properties":{"file":{"type":"string"},"line":{"type":"integer"},"column":{"type":"integer"}},"required":["file","line","column"]}"""
    override suspend fun execute(arguments: String): String {
        val ws = idProvider.currentId() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val file = o["file"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'file' required"
        val line = o["line"]?.jsonPrimitive?.content?.toIntOrNull() ?: return "Error: 'line' required"
        val column = o["column"]?.jsonPrimitive?.content?.toIntOrNull() ?: return "Error: 'column' required"
        val lang = inferLanguageId(file) ?: return "Error: unknown language for $file — use code_search instead"
        val client: LspClient = lspManager.getClient(ws, lang)
        return client.definition(workspacePathToUri(file), line - 1, column - 1).fold(
            onSuccess = { locs ->
                if (locs.isEmpty()) "No definition found."
                else locs.joinToString("\n") { loc -> locationToWorkspacePath(loc)?.let { "→ $it:${loc.range.start.line + 1}:${loc.range.start.character + 1}" } ?: "→ ${loc.uri}" }
            },
            onFailure = { "⚠️ LSP unavailable for $lang. Use code_search with the symbol name (regex) to find definitions." }
        )
    }
}

// ═══ code_references ═══
@Singleton
class CodeReferencesTool @Inject constructor(
    private val lspManager: LanguageServerManager,
    private val idProvider: CodeWorkspaceIdProvider
) : AgentTool {
    override val id = "code_references"
    override val name = "Find References"
    override val description = """
        Find all references to the symbol at file:line:column (1-based).
        Uses LSP textDocument/references. Accepts location (file:line:column), NOT symbol name —
        same-name symbols would be ambiguous (Spec §29). If LSP unavailable, fall back to code_search.
    """.trimIndent()
    override val parametersSchema = """{"type":"object","properties":{"file":{"type":"string"},"line":{"type":"integer"},"column":{"type":"integer"},"include_declaration":{"type":"boolean","default":true}},"required":["file","line","column"]}"""
    override suspend fun execute(arguments: String): String {
        val ws = idProvider.currentId() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val file = o["file"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'file' required"
        val line = o["line"]?.jsonPrimitive?.content?.toIntOrNull() ?: return "Error: 'line' required"
        val column = o["column"]?.jsonPrimitive?.content?.toIntOrNull() ?: return "Error: 'column' required"
        val includeDecl = o["include_declaration"]?.jsonPrimitive?.booleanOrNull ?: true
        val lang = inferLanguageId(file) ?: return "Error: unknown language — use code_search"
        val client = lspManager.getClient(ws, lang)
        return client.references(workspacePathToUri(file), line - 1, column - 1, includeDecl).fold(
            onSuccess = { refs ->
                if (refs.isEmpty()) "No references found."
                else buildString {
                    appendLine("Found ${refs.size} reference(s):")
                    refs.forEachIndexed { i, loc ->
                        val p = locationToWorkspacePath(loc) ?: loc.uri
                        appendLine("${i + 1}. $p:${loc.range.start.line + 1}:${loc.range.start.character + 1}")
                    }
                }
            },
            onFailure = { "⚠️ LSP unavailable for $lang. Use code_search with the symbol name (regex) as fallback." }
        )
    }
}

// ═══ code_hover ═══
@Singleton
class CodeHoverTool @Inject constructor(
    private val lspManager: LanguageServerManager,
    private val idProvider: CodeWorkspaceIdProvider
) : AgentTool {
    override val id = "code_hover"
    override val name = "Hover Info"
    override val description = "Get hover info (type/doc) for symbol at file:line:column. LSP textDocument/hover. Falls back to code_read of the file if LSP unavailable."
    override val parametersSchema = """{"type":"object","properties":{"file":{"type":"string"},"line":{"type":"integer"},"column":{"type":"integer"}},"required":["file","line","column"]}"""
    override suspend fun execute(arguments: String): String {
        val ws = idProvider.currentId() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val file = o["file"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'file' required"
        val line = o["line"]?.jsonPrimitive?.content?.toIntOrNull() ?: return "Error: 'line' required"
        val column = o["column"]?.jsonPrimitive?.content?.toIntOrNull() ?: return "Error: 'column' required"
        val lang = inferLanguageId(file) ?: return "Error: unknown language"
        val client = lspManager.getClient(ws, lang)
        return client.hover(workspacePathToUri(file), line - 1, column - 1).fold(
            onSuccess = { h -> h?.contents?.toString() ?: "No hover info." },
            onFailure = { "⚠️ LSP unavailable. Use code_read to view the file around line $line." }
        )
    }
}

// ═══ code_diagnostics ═══
@Singleton
class CodeDiagnosticsTool @Inject constructor(
    private val problems: ProblemsAggregator,
    private val idProvider: CodeWorkspaceIdProvider
) : AgentTool {
    override val id = "code_diagnostics"
    override val name = "Get Diagnostics"
    override val description = """
        Get aggregated problems (LSP + build + test) for the workspace or a specific file.
        Sources: LSP publishDiagnostics, build stderr, test failures (Spec §23).
    """.trimIndent()
    override val parametersSchema = """{"type":"object","properties":{"file":{"type":"string","description":"filter to a single file; omit for all"}}}"""
    override suspend fun execute(arguments: String): String {
        idProvider.currentId() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val file = o["file"]?.jsonPrimitive?.contentOrNull
        val all = problems.all().let { list -> if (file != null) list.filter { it.file == file } else list }
        if (all.isEmpty()) return "✅ No problems. (summary: ${problems.summary()})"
        return buildString {
            appendLine("Problems: ${problems.summary()}")
            all.groupBy { it.file }.forEach { (f, items) ->
                appendLine("── $f ──")
                items.forEach { p -> appendLine("  L${p.line}:${p.column} [${p.severity}] ${p.message}${p.code?.let { " ($it)" } ?: ""}") }
            }
        }
    }
}

// ═══ code_rename ═══
@Singleton
class CodeRenameTool @Inject constructor(
    private val lspManager: LanguageServerManager,
    private val idProvider: CodeWorkspaceIdProvider
) : AgentTool {
    override val id = "code_rename"
    override val name = "Rename Symbol"
    override val description = """
        Rename a symbol across the workspace (LSP textDocument/rename → WorkspaceEdit).
        v1: requires LSP; if unavailable, suggests manual code_edit + code_search verification.
    """.trimIndent()
    override val parametersSchema = """{"type":"object","properties":{"file":{"type":"string"},"line":{"type":"integer"},"column":{"type":"integer"},"new_name":{"type":"string"}},"required":["file","line","column","new_name"]}"""
    override suspend fun execute(arguments: String): String {
        val ws = idProvider.currentId() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val file = o["file"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'file' required"
        val line = o["line"]?.jsonPrimitive?.content?.toIntOrNull() ?: return "Error: 'line' required"
        val column = o["column"]?.jsonPrimitive?.content?.toIntOrNull() ?: return "Error: 'column' required"
        val newName = o["new_name"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'new_name' required"
        val lang = inferLanguageId(file) ?: return "Error: unknown language"
        val client = lspManager.getClient(ws, lang)
        return client.rename(workspacePathToUri(file), line - 1, column - 1, newName).fold(
            onSuccess = { edit ->
                if (edit == null || edit.changes.isEmpty()) "No rename edits produced."
                else buildString {
                    appendLine("Rename → '$newName' (${edit.changes.size} file(s)):")
                    edit.changes.forEach { (uri, edits) ->
                        val p = uri.removePrefix("file:///workspace/")
                        appendLine("  $p: ${edits.size} edit(s)")
                    }
                    appendLine("Apply via code_edit per file (v1: workspace edit auto-apply TBD).")
                }
            },
            onFailure = { "⚠️ LSP unavailable — rename not supported. Manual: code_search the symbol name, then code_edit each occurrence." }
        )
    }
}
