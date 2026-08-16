package com.apex.agent.di

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.platform.terminal.tools.TerminalTool

/**
 * Adapts a [TerminalTool] (defined in `platform:terminal`, which must not depend on
 * `core:tool-registry`) into the engine's [AgentTool] contract.
 *
 * Mirrors the existing [com.apex.agent.core.tools.skill.SkillToolAdapter] pattern:
 * all four metadata fields come straight from the terminal tool, and [execute]
 * delegates to its JSON-string [TerminalTool.invoke].
 */
class TerminalToolAdapter(
    private val tool: TerminalTool
) : AgentTool {
    override val id: String = tool.id
    override val name: String = tool.name
    override val description: String = tool.description
    override val parametersSchema: String = tool.parametersSchema

    override suspend fun execute(arguments: String): String = tool.invoke(arguments)
}
