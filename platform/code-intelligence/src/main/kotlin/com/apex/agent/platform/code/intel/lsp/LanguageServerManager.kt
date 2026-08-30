package com.apex.agent.platform.code.intel.lsp

import com.apex.agent.core.codetools.lsp.LspClient
import com.apex.agent.core.codetools.lsp.UnavailableLspClient
import com.apex.agent.platform.code.ws.CodeWorkspaceManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Language Server 生命周期管理器（Spec §20/§21/§58）。
 *
 * 职责：
 * - per-workspace + per-language 维护 LSP server 会话
 * - **Lazy start**：打开 workspace 不启动 LSP；首次 code_definition 等需要语义时才起
 * - **Reuse**：同一 workspace+language 的后续调用复用已有 server
 * - **Idle shutdown**：长时间未用自动释放（v1: 简化为关闭 workspace 时统一释放）
 * - **Crash recovery**：server 崩溃后下次调用重启（v1: 检测 transport 关闭 → 重建）
 *
 * v1 降级策略（Spec §79）：LSP server 跑在 proot guest 内（apt 装的 clangd/gopls/
 * pyright/rust-analyzer），需 [TerminalLspTransport] 把 JSON-RPC 经 TerminalRuntime
 * 穿梭到 guest 子进程。该 transport 的完整实现需在真实 rootfs + LSP server 环境下验证
 * （属于 Tranche 2）。v1 本管理器对所有 language 返回 [UnavailableLspClient]，
 * code_* 智能工具据此 fallback 到 [com.apex.agent.core.codetools.tools.CodeSearchTool]。
 *
 * 这保证 Code Mode 第一版**功能完整可用**（语义操作降级为文本搜索），
 * LSP 上线后只需替换 [getClient] 的返回值即可，工具层零改动。
 */
@Singleton
class LanguageServerManager @Inject constructor(
    private val workspaceManager: CodeWorkspaceManager
) {
    private data class ServerKey(val workspaceId: String, val languageId: String)
    private data class ServerEntry(
        val client: LspClient,
        val state: State,
        val startedAt: Long
    )
    private enum class State { NOT_STARTED, STARTING, READY, CRASHED, SHUTTING_DOWN }

    private val mutex = Mutex()
    private val servers = mutableMapOf<ServerKey, ServerEntry>()

    /**
     * 取（或懒启动）指定 workspace + language 的 LSP client。
     * v1：始终返回 [UnavailableLspClient]（transport 待 Tranche 2 接入）。
     */
    suspend fun getClient(workspaceId: String, languageId: String): LspClient = mutex.withLock {
        val key = ServerKey(workspaceId, languageId)
        val existing = servers[key]
        if (existing != null && existing.state == State.READY) return existing.client
        // v1: 不实际启动 guest LSP，直接返回 Unavailable（工具会 fallback）
        val entry = ServerEntry(UnavailableLspClient(), State.NOT_STARTED, System.currentTimeMillis())
        servers[key] = entry
        entry.client
    }

    /** 关闭某 workspace 的所有 LSP server（workspace 切换/关闭时调）。 */
    suspend fun shutdownWorkspace(workspaceId: String) = mutex.withLock {
        servers.entries.filter { it.key.workspaceId == workspaceId }.forEach { (k, _) ->
            runCatching { servers[k]?.client?.shutdown() }
            servers.remove(k)
        }
    }

    /** 全部关闭（App 退出 / 全局重置）。 */
    suspend fun shutdownAll() = mutex.withLock {
        servers.values.forEach { runCatching { it.client.shutdown() } }
        servers.clear()
    }

    /** 诊断：当前活跃 server 数（测试/UI 用）。 */
    fun activeServerCount(): Int = servers.count { it.value.state == State.READY }
}

/**
 * 把文件扩展名 / 文件名映射到 languageId（LSP 约定）。
 * 用于 code_definition 等工具调 [LanguageServerManager.getClient] 时选 server。
 */
fun inferLanguageId(path: String): String? = when {
    path.endsWith(".kt") -> "kotlin"
    path.endsWith(".java") -> "java"
    path.endsWith(".py") -> "python"
    path.endsWith(".ts") -> "typescript"
    path.endsWith(".js") -> "javascript"
    path.endsWith(".go") -> "go"
    path.endsWith(".rs") -> "rust"
    path.endsWith(".c") || path.endsWith(".h") -> "c"
    path.endsWith(".cpp") || path.endsWith(".cc") || path.endsWith(".hpp") -> "cpp"
    path.endsWith(".json") -> "json"
    else -> null
}
