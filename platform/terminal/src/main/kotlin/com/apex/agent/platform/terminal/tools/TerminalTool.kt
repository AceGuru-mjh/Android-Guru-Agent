package com.apex.agent.platform.terminal.tools

/**
 * Bridge contract for terminal tools so they can be adapted into the engine's
 * [com.apex.agent.core.tools.AgentTool] without forcing `platform:terminal` to
 * depend on `core:tool-registry` (module boundary).
 *
 * Each terminal tool exposes a JSON-string contract:
 *  - [invoke] parses a JSON `arguments` object, runs the operation, and returns
 *    a JSON result string (serialized by the tool itself).
 *  - [parametersSchema] is a JSON-Schema string describing the `arguments` shape.
 *
 * The app module wraps a [TerminalTool] via `TerminalToolAdapter` to register it
 * in the [com.apex.agent.core.tools.ToolRegistry].
 */
interface TerminalTool {
    val id: String
    val name: String
    val description: String
    val parametersSchema: String
    suspend fun invoke(arguments: String): String
}
