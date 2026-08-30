package com.apex.agent.core.codetools.lsp

import kotlinx.serialization.Serializable

/**
 * LSP 方法枚举（Spec §18 第一版范围）。
 * 仅覆盖 initialize / shutdown + textDocument 的 didOpen/didChange/didClose/
 * definition/references/hover/publishDiagnostics/rename。
 */
enum class LspMethod(val method: String) {
    INITIALIZE("initialize"),
    INITIALIZED("initialized"),
    SHUTDOWN("shutdown"),
    EXIT("exit"),

    DID_OPEN("textDocument/didOpen"),
    DID_CHANGE("textDocument/didChange"),
    DID_CLOSE("textDocument/didClose"),
    DID_SAVE("textDocument/didSave"),

    DEFINITION("textDocument/definition"),
    REFERENCES("textDocument/references"),
    HOVER("textDocument/hover"),
    DIAGNOSTIC("textDocument/diagnostic"),
    RENAME("textDocument/rename"),

    PUBLISH_DIAGNOSTICS("textDocument/publishDiagnostics");

    companion object {
        fun from(method: String): LspMethod? = entries.firstOrNull { it.method == method }
    }
}

/**
 * JSON-RPC 2.0 消息基类（Spec §59）。
 * 三种：request（有 id，需 response）、response（有 id）、notification（无 id）。
 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: kotlinx.serialization.json.JsonElement
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int,
    val result: kotlinx.serialization.json.JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(val code: Int, val message: String, val data: kotlinx.serialization.json.JsonElement? = null)

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: kotlinx.serialization.json.JsonElement
)

/**
 * JSON-RPC 传输层接口。实现见 [com.apex.agent.platform.code.intel.lsp.TerminalLspTransport]
 * （在 proot guest 内起 LSP server 子进程，经 TerminalRuntime write/observe 穿梭 JSON-RPC）。
 * 纯 JVM 实现可走 in-process tree-sitter 或直连子进程 ProcessBuilder（host 侧 Java LSP）。
 */
interface JsonRpcTransport {
    /** 发送 request 并等待对应 id 的 response。 */
    suspend fun request(method: String, params: kotlinx.serialization.json.JsonElement): JsonRpcResponse
    /** 发送 notification（无 id，无需 response）。 */
    suspend fun notify(method: String, params: kotlinx.serialization.json.JsonElement)
    /** 订阅 server → client 方向的 notification（如 publishDiagnostics）。 */
    fun notifications(): kotlinx.coroutines.flow.Flow<JsonRpcNotification>
    /** transport 是否已连接可用。 */
    fun isOpen(): Boolean
    /** 关闭 transport（不杀 server，server 由 LanguageServerManager 管生命周期）。 */
    suspend fun close()
}

// ═══ LSP 公共数据类型 ═══

@Serializable
data class LspPosition(val line: Int, val character: Int)

@Serializable
data class LspRange(val start: LspPosition, val end: LspPosition)

@Serializable
data class LspLocation(val uri: String, val range: LspRange)

@Serializable
data class LspTextEdit(val range: LspRange, val newText: String)

@Serializable
data class LspWorkspaceEdit(val changes: Map<String, List<LspTextEdit>> = emptyMap())

@Serializable
data class LspDiagnostic(
    val range: LspRange,
    val severity: Int = 1,            // 1=Error 2=Warning 3=Info 4=Hint
    val code: String? = null,
    val source: String? = null,
    val message: String
)

@Serializable
data class LspHover(val contents: kotlinx.serialization.json.JsonElement, val range: LspRange? = null)

@Serializable
data class LspServerCapabilities(
    val definitionProvider: Boolean = false,
    val referencesProvider: Boolean = false,
    val hoverProvider: Boolean = false,
    val renameProvider: Boolean = false,
    val diagnosticProvider: Boolean = false,
    val textDocumentSync: Int? = null    // 0=none 1=full 2=incremental
)

@Serializable
data class LspInitializeParams(
    val processId: Int,
    val rootUri: String,
    val workspaceFolders: List<WorkspaceFolder> = emptyList()
) {
    @Serializable data class WorkspaceFolder(val uri: String, val name: String)
}

@Serializable
data class LspTextDocumentIdentifier(val uri: String)

@Serializable
data class LspTextDocumentItem(val uri: String, val languageId: String, val version: Int, val text: String)

@Serializable
data class LspVersionedTextDocumentIdentifier(val uri: String, val version: Int)

@Serializable
data class LspTextDocumentPositionParams(
    val textDocument: LspTextDocumentIdentifier,
    val position: LspPosition
)

@Serializable
data class LspReferenceParams(
    val textDocument: LspTextDocumentIdentifier,
    val position: LspPosition,
    val context: ReferenceContext = ReferenceContext()
) {
    @Serializable data class ReferenceContext(val includeDeclaration: Boolean = true)
}

@Serializable
data class LspDidOpenParams(val textDocument: LspTextDocumentItem)

@Serializable
data class LspDidCloseParams(val textDocument: LspTextDocumentIdentifier)

@Serializable
data class LspPublishDiagnosticsParams(val uri: String, val diagnostics: List<LspDiagnostic> = emptyList())
