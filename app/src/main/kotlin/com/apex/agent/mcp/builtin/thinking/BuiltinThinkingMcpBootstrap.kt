package com.apex.agent.mcp.builtin.thinking

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.mcp.McpManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 内置顺序思考 MCP 的启动器：幂等预置配置 + 后台自动连接
 * （模式同 [com.apex.agent.search.mcp.BuiltinSearchMcpBootstrap]）。
 *
 * 由 app 的 McpModule 在 `provideMcpManager` 里调用（@Provides 副作用模式）：
 * - `ensureBuiltinServer` 幂等写入配置，用户自建同名 thinking 配置不会被
 *   劫持，用户手动禁用后尊重偏好不再自动连接；
 * - ensure/connect 是挂起函数，放到自持 IO scope 执行，不阻塞注入线程；
 * - 连接失败只记日志不重试 —— 握手是纯进程内 JSON 构造，实际不会失败；
 *   工具调用期的失败（参数校验）以 isError 文本返回给模型。
 */
object BuiltinThinkingMcpBootstrap {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureAndConnect(manager: McpManager) {
        scope.launch {
            manager.ensureBuiltinServer(BuiltinThinkingMcpServer.config()).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinThinkingMcp",
                    "预置内置 Thinking MCP 失败: ${it.message}"
                )
                return@launch
            }
            val enabled = manager.getConfigs()
                .any { it.name == BuiltinThinkingMcpServer.ID && it.enabled }
            if (!enabled) return@launch   // 用户明确禁用：尊重偏好，不自动连接
            manager.connect(BuiltinThinkingMcpServer.ID).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinThinkingMcp",
                    "连接内置 Thinking MCP 失败: ${it.message}"
                )
            }
        }
    }
}
