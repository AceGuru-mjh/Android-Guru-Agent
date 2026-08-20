package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolRegistry

/**
 * A68.1 — Minimal [ToolRegistry] for orchestrator tests.
 *
 * Tests rarely need real [AgentTool] instances — the orchestrator routes tool
 * calls through [FakeToolExecutor] which scripts behaviour by tool name. This
 * registry just needs to expose [ToolDefinition]s (so the LLM "sees" the tools
 * in its prompt) and answer [getTool] lookups.
 *
 * Lives ONLY in the test source set.
 */
class FakeToolRegistry(
    private val toolDefs: List<ToolDefinition> = emptyList()
) : ToolRegistry {

    private val tools = mutableMapOf<String, AgentTool>()

    /** Convenience builder: vararg of (name, description, schema) triples. */
    constructor(vararg defs: Triple<String, String, String>) : this(
        defs.map { ToolDefinition(name = it.first, description = it.second, parameters = it.third) }
    )

    init {
        // Pre-populate empty AgentTool stubs for each definition so getTool()
        // doesn't return null for registered tools. The stubs are never
        // actually invoked — FakeToolExecutor handles execution.
        toolDefs.forEach { td ->
            tools[td.name] = StubAgentTool(
                id = td.name,
                name = td.name,
                description = td.description,
                parametersSchema = td.parameters
            )
        }
    }

    override fun register(tool: AgentTool) {
        tools[tool.id] = tool
    }

    override fun unregister(toolId: String) {
        tools.remove(toolId)
    }

    override fun getTool(toolId: String): AgentTool? = tools[toolId]

    override fun getAllTools(): List<AgentTool> = tools.values.toList()

    override fun getToolDefinitions(): List<ToolDefinition> = toolDefs

    private class StubAgentTool(
        override val id: String,
        override val name: String,
        override val description: String,
        override val parametersSchema: String
    ) : AgentTool {
        override suspend fun execute(arguments: String): String =
            "Stub: $id should not be called directly — FakeToolExecutor handles execution"
    }
}
