package com.apex.agent.platform.code.intel.lsp

import com.apex.agent.core.codetools.lsp.JsonRpcNotification
import com.apex.agent.core.codetools.lsp.JsonRpcResponse
import com.apex.agent.core.codetools.lsp.JsonRpcTransport
import com.apex.agent.core.codetools.lsp.JsonRpcRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

/**
 * JSON-RPC over TerminalRuntime transport（Spec §60）。
 *
 * 设计目标：在 proot guest 内起 LSP server 子进程（clangd/gopls/pyright/...），
 * 经 [com.apex.agent.platform.terminal.runtime.TerminalRuntime] 的 write/observe
 * 穿梭 JSON-RPC 消息（`Content-Length: N\r\n\r\n{json}` 帧）。
 *
 * **状态：Tranche 2 骨架** —— 本文件给出协议帧结构与 transport 接口契约，
 * 但实际的 terminal 子进程穿梭 + 请求/响应 id 关联 + notification 订阅
 * 需在真实 rootfs + LSP server 环境下验证（属于 Tranche 2，需 build/设备反馈）。
 *
 * 在 v1，[LanguageServerManager] 不使用本 transport（直接返回 UnavailableLspClient）。
 * 本文件存在以锁定 v2 实现的架构边界，避免后续重构。
 */
class TerminalLspTransport @Inject constructor(
    // v2 注入：private val terminalRuntime: TerminalRuntime,
    // v2 注入：private val serverCommand: String  // e.g. "clangd --stdio"
) : JsonRpcTransport {

    @Volatile private var open = false
    @Volatile private var nextId = 1
    private val pending = mutableMapOf<Int, kotlinx.coroutines.CompletableDeferred<JsonRpcResponse>>()
    private val notificationFlow = kotlinx.coroutines.channels.Channel<JsonRpcNotification>(64)

    // v2: 在 guest 内起 `serverCommand --stdio` 子进程，bind 到一个 workspace session，
    // 然后 pump stdin/stdout。需要：
    // 1. terminalRuntime.create(backendId="linux-ubuntu", workspaceId=ws) → sessionId
    // 2. terminalRuntime.run(sessionId, "clangd --stdio", ...) → 长驻 job
    // 3. 一个 pump 协程：observe(RAW) 增量读 stdout → 解析 Content-Length 帧 → 分发到 pending/notificationFlow
    // 4. request：write(RAW, frame) → 等对应 id 的 CompletableDeferred
    // 这套逻辑非平凡，且必须在真实 LSP server 下验证；v1 不接通。

    fun connect(workspaceId: String, serverCommand: String): Boolean {
        // TODO (Tranche 2): 实际起子进程 + pump。v1 返回 false（不可用）。
        open = false
        return false
    }

    override suspend fun request(method: String, params: JsonElement): JsonRpcResponse {
        if (!open) return JsonRpcResponse(id = -1, error = com.apex.agent.core.codetools.lsp.JsonRpcError(-1, "transport not open", null))
        val id = nextId++
        val req = JsonRpcRequest(id = id, method = method, params = params)
        // TODO (Tranche 2): serialize to Content-Length frame, terminalRuntime.write(RAW, frame)
        // val deferred = CompletableDeferred<JsonRpcResponse>(); pending[id] = deferred
        // return deferred.await()
        return JsonRpcResponse(id = id, error = com.apex.agent.core.codetools.lsp.JsonRpcError(-1, "not implemented in v1", null))
    }

    override suspend fun notify(method: String, params: JsonElement) {
        // TODO (Tranche 2): serialize notification, write, no response expected
    }

    override fun notifications(): Flow<JsonRpcNotification> = flow {
        for (n in notificationFlow) emit(n)
    }

    override fun isOpen(): Boolean = open

    override suspend fun close() {
        open = false
        notificationFlow.close()
        // TODO (Tranche 2): terminalRuntime.close(sessionId) + reap LSP server
    }
}
