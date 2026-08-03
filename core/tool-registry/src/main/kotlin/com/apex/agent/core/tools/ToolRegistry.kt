package com.apex.agent.core.tools

import com.apex.agent.core.llm.ToolDefinition

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
}

/**
 * 工具执行器
 */
interface ToolExecutor {
    suspend fun execute(toolId: String, arguments: String): String
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
}

class DefaultToolExecutor(
    private val registry: ToolRegistry
) : ToolExecutor {
    override suspend fun execute(toolId: String, arguments: String): String {
        val tool = registry.getTool(toolId)
            ?: return "Error: Tool '$toolId' not found"
        
        return try {
            tool.execute(arguments)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
