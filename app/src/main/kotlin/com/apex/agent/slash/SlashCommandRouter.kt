package com.apex.agent.slash

/**
 * The UI/system artifacts produced by routing a parsed [SlashCommand]:
 *
 * - [systemMessage]: a single human-readable status line appended to the
 *   chat as an `AgentUiMessage.System` bubble (e.g. `"🧩 激活 Skill: github"`).
 * - [agentPrompt]: the prompt handed to `AgentEngine.execute(...)` so the
 *   LLM can act on the command, including parsed args + user extra text.
 *
 * Keeping this mapping out of the ViewModel makes it independently testable
 * and lets future command types plug in without touching ViewModel control
 * flow.
 */
data class SlashCommandRoute(
    val systemMessage: String,
    val agentPrompt: String
)

/**
 * Maps a parsed [SlashCommand] into a [SlashCommandRoute].
 *
 * The four known command types (Skill / Mcp / Connector / Plugin) share the
 * same prompt skeleton — only the verb and tool hint differ — so they are
 * built by [buildAgentPrompt]. Unknown commands forward the raw text to the
 * agent verbatim so the user's intent is never silently dropped.
 */
object SlashCommandRouter {

    fun route(command: SlashCommand): SlashCommandRoute = when (command) {
        is SlashCommand.Skill -> SlashCommandRoute(
            systemMessage = "🧩 激活 Skill: ${command.id}",
            agentPrompt = command.buildAgentPrompt(
                verb = "请根据此指令执行对应操作，通过 skill 相关工具执行",
                toolHint = "skill"
            )
        )
        is SlashCommand.Mcp -> SlashCommandRoute(
            systemMessage = "🔌 连接 MCP: ${command.id}",
            agentPrompt = command.buildAgentPrompt(
                verb = "请根据此指令执行对应操作，通过 MCP 工具执行",
                toolHint = "mcp"
            )
        )
        is SlashCommand.Connector -> SlashCommandRoute(
            systemMessage = "🔗 使用连接器: ${command.id}",
            agentPrompt = command.buildAgentPrompt(
                verb = "请根据此指令执行对应操作，通过 connector 工具执行",
                toolHint = "connector"
            )
        )
        is SlashCommand.Plugin -> SlashCommandRoute(
            systemMessage = "📦 调用插件: ${command.id}",
            agentPrompt = command.buildAgentPrompt(
                verb = "请根据此指令执行对应操作，通过 plugin 工具执行",
                toolHint = "plugin"
            )
        )
        is SlashCommand.Unknown -> SlashCommandRoute(
            systemMessage = "⚡ 指令: ${command.raw.trim()}",
            // Forward the raw input verbatim — the user typed it on purpose
            // and the agent is best placed to interpret free-form commands.
            agentPrompt = command.raw
        )
    }

    /**
     * Shared prompt builder for the four known command types.
     *
     * Shape:
     *
     *     用户触发了快捷指令: /<type>:<id>
     *     <verb>（<toolHint>: <id>）
     *     指令参数: k1=v1, k2=v2    ← only when args present
     *
     *     用户附加要求: <userExtra>   ← only when userExtra present
     */
    private fun SlashCommand.buildAgentPrompt(verb: String, toolHint: String): String = buildString {
        append("用户触发了快捷指令: /").append(type).append(":").append(id).append('\n')
        append(verb).append("（").append(toolHint).append("：").append(id).append("）")
        if (args.isNotEmpty()) {
            append("\n指令参数: ")
            append(args.entries.joinToString(", ") { "${it.key}=${it.value}" })
        }
        if (userExtra.isNotBlank()) {
            append("\n\n用户附加要求: ").append(userExtra)
        }
    }
}
