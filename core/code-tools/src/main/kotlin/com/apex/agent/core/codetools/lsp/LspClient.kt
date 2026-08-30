package com.apex.agent.core.codetools.lsp

/**
 * 高层 LSP 客户端接口（Spec §18/§59）。
 *
 * 职责：把 JSON-RPC 细节封装成语义化方法（initialize / didOpen / definition / …），
 * 让 [code_definition] / [code_references] / [code_hover] / [code_diagnostics] /
 * [code_rename] 工具只关心参数解析与结果格式化，不碰协议。
 *
 * Transport 实现由 :platform:code-intelligence 提供：
 * - [com.apex.agent.platform.code.intel.lsp.TerminalLspTransport]：guest 子进程经 TerminalRuntime
 * - 未来可加 in-process 纯 Java LSP（host 侧）
 */
interface LspClient {

    /** 当前 server 是否已 initialize 成功。 */
    val isReady: Boolean
    /** server 支持的能力（initialize 后填充）。 */
    val capabilities: LspServerCapabilities?

    suspend fun initialize(rootUri: String): Result<LspServerCapabilities>
    suspend fun initialized()
    suspend fun shutdown()

    suspend fun didOpen(uri: String, languageId: String, text: String)
    suspend fun didChange(uri: String, version: Int, text: String)
    suspend fun didClose(uri: String)

    suspend fun definition(uri: String, line: Int, character: Int): Result<List<LspLocation>>
    suspend fun references(uri: String, line: Int, character: Int, includeDeclaration: Boolean = true): Result<List<LspLocation>>
    suspend fun hover(uri: String, line: Int, character: Int): Result<LspHover?>
    suspend fun rename(uri: String, line: Int, character: Int, newName: String): Result<LspWorkspaceEdit?>
}

/**
 * LSP server 不可用时的优雅降级（Spec §79）。
 * 所有方法返回 Result.failure，工具层应据此 fallback 到文本搜索。
 */
class UnavailableLspClient : LspClient {
    override val isReady: Boolean = false
    override val capabilities: LspServerCapabilities? = null
    override suspend fun initialize(rootUri: String) = Result.failure(UnsupportedOperationException("LSP unavailable"))
    override suspend fun initialized() {}
    override suspend fun shutdown() {}
    override suspend fun didOpen(uri: String, languageId: String, text: String) {}
    override suspend fun didChange(uri: String, version: Int, text: String) {}
    override suspend fun didClose(uri: String) {}
    override suspend fun definition(uri: String, line: Int, character: Int) = Result.failure(UnsupportedOperationException("LSP unavailable"))
    override suspend fun references(uri: String, line: Int, character: Int, includeDeclaration: Boolean) = Result.failure(UnsupportedOperationException("LSP unavailable"))
    override suspend fun hover(uri: String, line: Int, character: Int) = Result.failure(UnsupportedOperationException("LSP unavailable"))
    override suspend fun rename(uri: String, line: Int, character: Int, newName: String) = Result.failure(UnsupportedOperationException("LSP unavailable"))
}

/**
 * 把 guest 内的 `/workspace/path/file.kt` 路径转成 LSP `file://` URI。
 * host 侧 [com.apex.agent.core.codetools.fs.CodeWorkspaceFileSystem] 用相对 path，
 * LSP 传相对 path 的 file:// uri（rootUri 已指向 /workspace）。
 */
fun workspacePathToUri(path: String): String {
    val norm = path.trimStart('/').replace('\\', '/')
    return "file:///workspace/${norm}"
}

fun locationToWorkspacePath(loc: LspLocation): String? {
    val uri = loc.uri
    val prefix = "file:///workspace/"
    return if (uri.startsWith(prefix)) uri.removePrefix(prefix) else null
}
