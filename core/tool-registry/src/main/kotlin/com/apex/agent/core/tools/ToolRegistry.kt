package com.apex.agent.core.tools

import com.apex.agent.core.llm.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

/**
 * 工具注册表
 * 管理所有可用工具（内置 + 插件提供）
 */
interface ToolRegistry {
    fun register(tool: AgentTool)
    fun unregister(toolId: String)
    fun getTool(toolId: String): AgentTool?
    fun getAllTools(): List<AgentTool>
    fun getToolDefinitions(): List<ToolDefinition>

    /**
     * 当前已注册工具数量。作为 UI 刷新函数调用菜单的变更信号：
     * [AgentChatScreen] 用它作为 `remember(key)` 的 key，避免菜单快照被永久缓存
     * （注册表在启动期构建一次，进程内若热注册新工具，下次重组时 key 变化即刷新）。
     * 提供默认实现以保持接口向后兼容（现有实现无需显式 override）。
     */
    val toolCount: Int
        get() = getAllTools().size
}

/**
 * 工具执行器。
 *
 * 提供两种执行入口：
 * - [execute]：一次性返回完整输出（向后兼容，现有调用点不变）。
 * - [executeStream]：返回 [ToolStreamEvent] 流，允许工具逐段输出。engine 优先
 *   使用 [executeStream]，这样实现了 [StreamingAgentTool] 的工具（如
 *   shell_execute）能逐行把输出推到 UI，而非流式工具则被透明包装成
 *   “单个 Output + Complete” 的事件序列。
 */
interface ToolExecutor {

    /**
     * 兼容旧逻辑：一次性执行工具。
     */
    suspend fun execute(toolId: String, arguments: String): String

    /**
     * 新逻辑：流式执行工具。始终以 [Flow] 形式返回，无论工具是否实现
     * [StreamingAgentTool]：
     * - 实现了 [StreamingAgentTool]：直接转发 `tool.executeStream(...)` 的事件。
     * - 未实现：调用 `tool.execute(...)`，把结果包成单个
     *   [ToolStreamEvent.Output] + [ToolStreamEvent.Complete]（失败时为 [ToolStreamEvent.Error]）。
     */
    fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent>
}

/**
 * Agent工具接口
 */
interface AgentTool {
    val id: String
    val name: String
    val description: String
    val parametersSchema: String  // JSON Schema
    
    suspend fun execute(arguments: String): String
}

/**
 * 默认实现
 */
class DefaultToolRegistry : ToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()

    override fun register(tool: AgentTool) {
        tools[tool.id] = tool
    }

    override fun unregister(toolId: String) {
        tools.remove(toolId)
    }

    override fun getTool(toolId: String): AgentTool? = tools[toolId]

    override fun getAllTools(): List<AgentTool> = tools.values.toList()

    override fun getToolDefinitions(): List<ToolDefinition> {
        return tools.values.map { tool ->
            ToolDefinition(
                name = tool.id,
                description = tool.description,
                parameters = tool.parametersSchema
            )
        }
    }

    // O(1) 直接读 map 大小，避免 getAllTools() 复制整个 values 列表。
    override val toolCount: Int
        get() = tools.size
}

class DefaultToolExecutor(
    private val registry: ToolRegistry
) : ToolExecutor {

    override suspend fun execute(toolId: String, arguments: String): String {
        val tool = registry.getTool(toolId)

        if (tool == null) {
            val available = registry.getAllTools()
                .joinToString(", ") { it.id }
                .ifBlank { "none" }

            return "Error: Tool '$toolId' not found. Available tools: $available"
        }

        return try {
            tool.execute(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            "Error: 权限不足，无法执行。${e.message ?: toolId}"
        } catch (e: IOException) {
            "Error: 权限不足或 I/O 失败，无法执行。${e.message ?: toolId}"
        } catch (e: Throwable) {
            "Error: 工具执行失败。${e.message ?: e::class.simpleName}"
        }
    }

    /**
     * 流式执行入口。
     *
     * - 工具不存在：发射一条 [ToolStreamEvent.Error] 后结束。
     * - 工具实现了 [StreamingAgentTool]：透传其事件流（`emitAll`）。
     * - 普通工具：调用 [execute] 并把结果包成单个 [ToolStreamEvent.Output] +
     *   [ToolStreamEvent.Complete]（结果以 "Error" 开头时改发 [ToolStreamEvent.Error]）。
     *
     * 异常处理与 [execute] 一致：CancellationException 重抛，其他异常转成
     * [ToolStreamEvent.Error]，保证收集方永远拿到一个完整的事件序列。
     * 整个流在 [Dispatchers.IO] 上执行，避免阻塞调用方。
     */
    override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> = flow {
        val tool = registry.getTool(toolId)

        if (tool == null) {
            val available = registry.getAllTools()
                .joinToString(", ") { it.id }
                .ifBlank { "none" }
            emit(ToolStreamEvent.Error("Error: Tool '$toolId' not found. Available tools: $available"))
            return@flow
        }

        try {
            if (tool is StreamingAgentTool) {
                emitAll(tool.executeStream(arguments))
            } else {
                val result = tool.execute(arguments)
                if (result.startsWith("Error")) {
                    emit(ToolStreamEvent.Error(result))
                } else {
                    if (result.isNotEmpty()) {
                        emit(ToolStreamEvent.Output(result))
                    }
                    emit(ToolStreamEvent.Complete(result))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            emit(ToolStreamEvent.Error("Error: 权限不足，无法执行。${e.message ?: toolId}"))
        } catch (e: IOException) {
            emit(ToolStreamEvent.Error("Error: 权限不足或 I/O 失败。${e.message ?: toolId}"))
        } catch (e: Throwable) {
            emit(ToolStreamEvent.Error("Error: 工具执行失败。${e.message ?: e::class.simpleName}"))
        }
    }.flowOn(Dispatchers.IO)
}
