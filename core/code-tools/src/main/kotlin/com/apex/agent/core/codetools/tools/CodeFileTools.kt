package com.apex.agent.core.codetools.tools

import com.apex.agent.core.codetools.fs.CodeWorkspaceFileSystem
import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Code 文件工具的 workspace 解析器：返回当前激活 workspace 的 [CodeWorkspaceFileSystem]。
 *
 * 工具本身是无状态单例（在 [com.apex.agent.di.ToolModule] 启动期注册一次），
 * 但 workspace 可切换 —— 故工具在每次 [AgentTool.execute] 时调用本 provider 取
 * 当前 FS。无激活 workspace 时返回 null，工具返回友好错误而非崩溃。
 */
fun interface WorkspaceFsProvider {
    fun current(): CodeWorkspaceFileSystem?
}

/**
 * 共享错误处理：无激活 workspace 时的统一提示。
 */
private fun noWorkspace(): String =
    "Error: no active Code workspace. Open or create a project first (terminal.workspaces create)."

/**
 * 9 个 code_* 文件工具（Spec §14）。
 *
 * 全部委托 [CodeWorkspaceFileSystem]（host 侧 java.io.File，非 shell），
 * 路径安全由 [com.apex.agent.core.codetools.fs.WorkspacePathSafety] 保证。
 * 工具层只做：① 解析 JSON 参数 → ② 调 FS → ③ 格式化结果给 LLM。
 */

// ═══ 1. code_read ═══
class CodeReadTool(private val fsProvider: WorkspaceFsProvider) : AgentTool {
    override val id = "code_read"
    override val name = "Read Code File"
    override val description = """
        Read a code file from the active Code workspace (windowed, line-numbered).
        Default returns 80 lines from offset 0; max 500 lines per call.
        Use offset/limit for paging through large files. Binary files are rejected.
        Path is relative to the workspace root; absolute paths must stay inside workspace.
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"file path relative to workspace root"},
          "offset":{"type":"integer","description":"0-based line offset (default 0)"},
          "limit":{"type":"integer","description":"max lines to return (default 80, max 500)"}
        },"required":["path"]}
    """.trimIndent()
    override suspend fun execute(arguments: String): String {
        val fs = fsProvider.current() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'path' required"
        val offset = o["offset"]?.jsonPrimitive?.intOrNull ?: 0
        val limit = o["limit"]?.jsonPrimitive?.intOrNull ?: 80
        val r = fs.read(path, offset, limit)
        if (!r.exists) return "Error: not found: $path"
        if (r.isBinary) return "Error: binary file: $path"
        return buildString {
            appendLine("📄 $path (${r.returnedLines}/${r.totalLines} lines, ${r.sizeBytes} bytes)${if (r.truncated) " [truncated]" else ""}")
            appendLine("offset=${r.offsetLine} limit=${r.returnedLines}")
            appendLine("─────")
            r.content.lines().forEachIndexed { i, line -> appendLine("${r.offsetLine + i + 1}".padStart(5) + " │ $line") }
        }
    }
}

// ═══ 2. code_write ═══
class CodeWriteTool(private val fsProvider: WorkspaceFsProvider) : AgentTool {
    override val id = "code_write"
    override val name = "Write Code File"
    override val description = """
        Write or append content to a file in the active Code workspace.
        For modifying existing files, prefer code_edit (search-replace) to avoid blind overwrite.
        Parent dirs are auto-created.
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{
          "path":{"type":"string"},"content":{"type":"string"},
          "mode":{"type":"string","enum":["write","append"],"default":"write"}
        },"required":["path","content"]}
    """.trimIndent()
    override suspend fun execute(arguments: String): String {
        val fs = fsProvider.current() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'path' required"
        val content = o["content"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'content' required"
        val mode = o["mode"]?.jsonPrimitive?.contentOrNull?.let { runCatching { CodeWorkspaceFileSystem.WriteMode.valueOf(it.uppercase()) }.getOrNull() } ?: CodeWorkspaceFileSystem.WriteMode.WRITE
        val r = fs.write(path, content, mode)
        return if (r.ok) "✅ ${r.message}: $path (${r.bytesWritten} bytes)${r.diff?.let { " — " + it.summary } ?: ""}"
        else "Error: ${r.message}"
    }
}

// ═══ 3. code_edit ═══
class CodeEditTool(private val fsProvider: WorkspaceFsProvider) : AgentTool {
    override val id = "code_edit"
    override val name = "Edit Code File (search-replace)"
    override val description = """
        Edit a code file using atomic search-and-replace blocks (Spec §14).
        Each edit: {search, replace, insert_after_line?}. Empty search + insert_after_line = insert.
        Empty replace = delete. If any search text is not found, the whole operation is rolled back.
        Returns a unified diff of the change. Set create_if_missing=true to create a new file.
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{
          "path":{"type":"string"},
          "edits":{"type":"array","items":{"type":"object","properties":{
            "search":{"type":"string"},"replace":{"type":"string"},
            "insert_after_line":{"type":"integer"}
          },"required":["search","replace"]}},
          "create_if_missing":{"type":"boolean","default":false}
        },"required":["path","edits"]}
    """.trimIndent()
    override suspend fun execute(arguments: String): String {
        val fs = fsProvider.current() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'path' required"
        val editsArr = o["edits"]?.jsonArray ?: return "Error: 'edits' required"
        val createIfMissing = o["create_if_missing"]?.jsonPrimitive?.booleanOrNull ?: false
        val edits = editsArr.map { e ->
            val eo = e.jsonObject
            com.apex.agent.core.codetools.diff.EditOperation(
                search = eo["search"]?.jsonPrimitive?.contentOrNull ?: "",
                replace = eo["replace"]?.jsonPrimitive?.contentOrNull ?: "",
                insertAfterLine = eo["insert_after_line"]?.jsonPrimitive?.intOrNull
            )
        }
        val r = fs.edit(path, edits, createIfMissing)
        return buildString {
            if (r.ok) {
                appendLine("✅ ${r.message}: $path")
                r.appliedOperations.forEach { appendLine("  • $it") }
                r.diff?.let { appendLine("  Diff: ${it.summary}"); if (it.unifiedPatch.length < 4000) append(it.unifiedPatch) }
            } else appendLine("❌ ${r.message}: $path")
        }
    }
}

// ═══ 4. code_create ═══
class CodeCreateTool(private val fsProvider: WorkspaceFsProvider) : AgentTool {
    override val id = "code_create"
    override val name = "Create Empty Code File"
    override val description = "Create an empty file (parent dirs auto-created). Fails if it already exists as a non-empty file."
    override val parametersSchema = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
    override suspend fun execute(arguments: String): String {
        val fs = fsProvider.current() ?: return noWorkspace()
        val path = Json.parseToJsonElement(arguments).jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'path' required"
        val r = fs.create(path)
        return if (r.ok) "✅ ${r.message}: $path" else "Error: ${r.message}"
    }
}

// ═══ 5. code_delete ═══
class CodeDeleteTool(private val fsProvider: WorkspaceFsProvider) : AgentTool {
    override val id = "code_delete"
    override val name = "Delete Code File"
    override val description = "Delete a file (or empty dir) in the active workspace. Non-empty dirs are refused."
    override val parametersSchema = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
    override suspend fun execute(arguments: String): String {
        val fs = fsProvider.current() ?: return noWorkspace()
        val path = Json.parseToJsonElement(arguments).jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'path' required"
        val r = fs.delete(path)
        return if (r.ok) "✅ ${r.message}: $path" else "Error: ${r.message}"
    }
}

// ═══ 6. code_move ═══
class CodeMoveTool(private val fsProvider: WorkspaceFsProvider) : AgentTool {
    override val id = "code_move"
    override val name = "Move/Rename Code File"
    override val description = "Move or rename a file/directory inside the workspace. Refuses to escape workspace root."
    override val parametersSchema = """{"type":"object","properties":{"source":{"type":"string"},"dest":{"type":"string"}},"required":["source","dest"]}"""
    override suspend fun execute(arguments: String): String {
        val fs = fsProvider.current() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val src = o["source"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'source' required"
        val dst = o["dest"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'dest' required"
        val r = fs.move(src, dst)
        return if (r.ok) "✅ ${r.message}: $src → $dst" else "Error: ${r.message}"
    }
}

// ═══ 7. code_copy ═══
class CodeCopyTool(private val fsProvider: WorkspaceFsProvider) : AgentTool {
    override val id = "code_copy"
    override val name = "Copy Code File"
    override val description = "Copy a file/directory inside the workspace. Refuses to escape workspace root."
    override val parametersSchema = """{"type":"object","properties":{"source":{"type":"string"},"dest":{"type":"string"}},"required":["source","dest"]}"""
    override suspend fun execute(arguments: String): String {
        val fs = fsProvider.current() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val src = o["source"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'source' required"
        val dst = o["dest"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'dest' required"
        val r = fs.copy(src, dst)
        return if (r.ok) "✅ ${r.message}: $src → $dst" else "Error: ${r.message}"
    }
}

// ═══ 8. code_glob ═══
class CodeGlobTool(private val fsProvider: WorkspaceFsProvider) : AgentTool {
    override val id = "code_glob"
    override val name = "Glob Code Files"
    override val description = """
        Find files by name pattern (glob: * and ?) in the active workspace.
        Skips .git/build/node_modules/__pycache__/.gradle/.idea/venv/.venv.
        Returns paths relative to workspace root, sorted, capped at max_results.
    """.trimIndent()
    override val parametersSchema = """{"type":"object","properties":{"pattern":{"type":"string","description":"e.g. **/*.kt or src/**/*.py"},"max_results":{"type":"integer","default":200}},"required":["pattern"]}"""
    override suspend fun execute(arguments: String): String {
        val fs = fsProvider.current() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val pattern = o["pattern"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'pattern' required"
        val max = o["max_results"]?.jsonPrimitive?.intOrNull ?: 200
        val r = fs.glob(pattern, max)
        return buildString {
            appendLine("📁 glob '$pattern' → ${r.total} match(es)${if (r.truncated) " [truncated at $max]" else ""}")
            r.matches.take(200).forEach { appendLine("  $it") }
        }
    }
}

// ═══ 9. code_search ═══
class CodeSearchTool(private val fsProvider: WorkspaceFsProvider) : AgentTool {
    override val id = "code_search"
    override val name = "Search Code (regex grep)"
    override val description = """
        Regex content search across the active workspace (Spec §27 — text search fallback).
        Returns paginated matches with file:line:column and optional context lines.
        Skips .git/build/node_modules/etc. Use file_extension to filter (e.g. ".kt").
    """.trimIndent()
    override val parametersSchema = """{"type":"object","properties":{
      "pattern":{"type":"string","description":"regex pattern"},
      "file_extension":{"type":"string","description":"e.g. .kt .py .java"},
      "context_lines":{"type":"integer","default":0},
      "page":{"type":"integer","default":0},
      "page_size":{"type":"integer","default":50},
      "case_sensitive":{"type":"boolean","default":false}
    },"required":["pattern"]}"""
    override suspend fun execute(arguments: String): String {
        val fs = fsProvider.current() ?: return noWorkspace()
        val o = Json.parseToJsonElement(arguments).jsonObject
        val pattern = o["pattern"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'pattern' required"
        val ext = o["file_extension"]?.jsonPrimitive?.contentOrNull
        val ctx = o["context_lines"]?.jsonPrimitive?.intOrNull ?: 0
        val page = o["page"]?.jsonPrimitive?.intOrNull ?: 0
        val size = o["page_size"]?.jsonPrimitive?.intOrNull ?: 50
        val cs = o["case_sensitive"]?.jsonPrimitive?.booleanOrNull ?: false
        val r = fs.search(pattern, ext, ctx, page, size, cs)
        return buildString {
            appendLine("🔎 '$pattern' → ${r.total} match(es), page ${r.page}${if (r.truncated) " [more]" else ""}")
            r.matches.forEach { m ->
                appendLine("${m.file}:${m.line}:${m.column}: ${m.text}")
            }
        }
    }
}
