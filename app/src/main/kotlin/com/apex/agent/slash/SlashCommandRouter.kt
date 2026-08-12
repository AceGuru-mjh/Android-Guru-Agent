package com.apex.agent.slash

/**
 * Runtime context the [SlashCommandRouter] consults to decide how a command
 * should be routed. Currently only GitHub connection state is needed (for the
 * `/mcp:github` real binding), but the shape is extensible: future connectors
 * / plugins / MCP servers plug in by adding fields here.
 *
 * The context is a small immutable value object so routing stays pure and
 * unit-testable — the ViewModel snapshots connection state at dispatch time
 * and passes it in, rather than the router reaching into live managers.
 *
 * @param githubConnected `true` when a GitHub Personal Access Token has been
 *   saved and the [githubUsername] is known (or at least non-null per the
 *   token manager). When `false`, `/mcp:github` cannot execute and instead
 *   requests the UI to open the GitHub connect flow.
 * @param githubUsername The GitHub login resolved at token-validation time,
 *   or `null` when not connected. Surfaced in the system message so the user
 *   can see *which* account the MCP context is bound to.
 */
data class SlashRouteContext(
    val githubConnected: Boolean = false,
    val githubUsername: String? = null
) {
    companion object {
        /** Sentinel used when no live connection state is available. */
        val Empty: SlashRouteContext = SlashRouteContext()
    }
}

/**
 * The UI/system artifacts produced by routing a parsed [SlashCommand]:
 *
 * - [systemMessage]: a single human-readable status line appended to the
 *   chat as an `AgentUiMessage.System` bubble (e.g. `"🧩 激活 Skill: github"`).
 * - [agentPrompt]: the prompt handed to `AgentEngine.execute(...)` so the
 *   LLM can act on the command, including parsed args + user extra text.
 *   Empty when [requestGithubConnect] is `true` (nothing to execute yet).
 * - [requestGithubConnect]: when `true`, the command cannot proceed until the
 *   user completes the out-of-band GitHub connection step. The ViewModel
 *   should surface [systemMessage], emit its GitHub-connect UI signal, and
 *   **not** call `agentEngine.execute`. Added so the `/mcp:github` command
 *   degrades gracefully instead of firing a hollow prompt at the agent.
 */
data class SlashCommandRoute(
    val systemMessage: String,
    val agentPrompt: String,
    val requestGithubConnect: Boolean = false,
    /** 当路由来自 Skill 指令时携带 skill 名称，供 UI 标记后续工具调用来源。 */
    val skillName: String? = null
)

/**
 * Maps a parsed [SlashCommand] into a [SlashCommandRoute], consulting the
 * supplied [SlashRouteContext] for commands that depend on live connection
 * state (currently `/mcp:github`).
 *
 * The four known command types (Skill / Mcp / Connector / Plugin) share the
 * same prompt skeleton — only the verb and tool hint differ — so they are
 * built by [buildAgentPrompt]. Unknown commands forward the raw text to the
 * agent verbatim so the user's intent is never silently dropped.
 *
 * `/mcp:github` is special-cased because the project already ships real
 * GitHub tools (registered in `ToolModule` when a token is present). When
 * connected, the agent prompt explicitly enumerates the `github_*` tool IDs
 * so the LLM prefers them; when not connected, routing short-circuits to a
 * connect-request signal instead of emitting a hollow prompt.
 */
object SlashCommandRouter {

    /**
     * GitHub tool IDs registered by `ToolModule` when a token is connected.
     * Mirrored here (rather than imported) so the `slash` package stays free
     * of `app`-module dependencies and remains pure-JVM unit-testable. If
     * `ToolModule` adds/removes a GitHub tool, update this list in lockstep.
     */
    private val GITHUB_TOOL_IDS: List<String> = listOf(
        "github_get_user",
        "github_list_repos",
        "github_read_file",
        "github_write_file",
        "github_create_issue",
        "github_list_issues",
        "github_search_code"
    )

    fun route(
        command: SlashCommand,
        context: SlashRouteContext = SlashRouteContext.Empty
    ): SlashCommandRoute = when (command) {
        is SlashCommand.Skill -> SlashCommandRoute(
            systemMessage = "🧩 激活 Skill: ${command.id}",
            agentPrompt = command.buildAgentPrompt(
                verb = "请根据此指令执行对应操作，通过 skill 相关工具执行",
                toolHint = "skill"
            ),
            skillName = command.id
        )

        is SlashCommand.Mcp -> routeMcp(command, context)

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
     * MCP routing. `/mcp:github` is bound to the real GitHub tool set; all
     * other MCP ids fall back to the generic prompt skeleton (they remain
     * "command exists, capability not yet wired" — same status as before
     * this change, no regression).
     */
    private fun routeMcp(command: SlashCommand.Mcp, context: SlashRouteContext): SlashCommandRoute {
        if (command.id == "github") {
            return if (context.githubConnected) {
                val user = context.githubUsername ?: "GitHub"
                SlashCommandRoute(
                    systemMessage = "🔌 已启用 GitHub MCP 上下文（用户: $user）",
                    agentPrompt = command.buildGithubAgentPrompt(user)
                )
            } else {
                // Degrade gracefully: tell the user what to do and ask the
                // UI to open the GitHub connect flow. No agent execution.
                SlashCommandRoute(
                    systemMessage = "⚠️ GitHub 未连接，请通过输入栏 GitHub 图标连接后再使用 /mcp:github",
                    agentPrompt = "",
                    requestGithubConnect = true
                )
            }
        }
        // Generic MCP fallback (postgres / filesystem / future servers).
        return SlashCommandRoute(
            systemMessage = "🔌 连接 MCP: ${command.id}",
            agentPrompt = command.buildAgentPrompt(
                verb = "请根据此指令执行对应操作，通过 MCP 工具执行",
                toolHint = "mcp"
            )
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

    /**
     * Prompt builder specialized for `/mcp:github` when GitHub is connected.
     *
     * Unlike the generic builder, this one explicitly enumerates the
     * `github_*` tool IDs the agent has access to, so the LLM doesn't have
     * to guess which tools to call for a repo/issue/code-search task. User
     * extra text is still forwarded so `/mcp:github repo=owner/name 列出
     * open issues` works end-to-end.
     */
    private fun SlashCommand.Mcp.buildGithubAgentPrompt(username: String): String = buildString {
        append("用户触发了快捷指令: /mcp:github\n")
        append("GitHub MCP 上下文已启用（已连接用户: ").append(username).append("）。\n")
        append("当前可用 GitHub 工具（优先使用这些）:\n")
        GITHUB_TOOL_IDS.forEach { append("  - ").append(it).append('\n') }
        if (args.isNotEmpty()) {
            append("\n指令参数: ")
            append(args.entries.joinToString(", ") { "${it.key}=${it.value}" })
        }
        if (userExtra.isNotBlank()) {
            append("\n\n用户附加要求: ").append(userExtra)
        }
    }
}
