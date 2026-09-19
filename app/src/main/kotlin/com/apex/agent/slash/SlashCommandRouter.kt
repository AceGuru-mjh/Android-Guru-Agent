package com.apex.agent.slash

/**
 * Runtime context the [SlashCommandRouter] consults to decide how a command
 * should be routed. Currently GitHub connection state (for the
 * `/mcp:github` real binding) and live MCP server connection state are needed,
 * but the shape is extensible: future connectors / plugins plug in by adding
 * fields here.
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
 * @param mcpConnected 已连接的 MCP 服务器名快照（路由时点）。`/mcp:<id>` 对已
 *   连接的服务器注入 mcp_call 引导提示词；未携带快照的旧调用方退回
 *   Token 连接态判定（内置 github 服务器随启动自动连接，两者语义一致）。
 */
data class SlashRouteContext(
    val githubConnected: Boolean = false,
    val githubUsername: String? = null,
    val mcpConnected: Set<String> = emptySet()
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
    val skillName: String? = null,
    /**
     * 路由来源类别："skill" / "connector" / "plugin"，null 表示普通指令。
     * 与 [sourceName] 一起供 UI 渲染流水线横幅与后续工具调用的来源徽章。
     */
    val routeKind: String? = null,
    /** 路由来源名称（指令 id）：Skill / 连接器 / 插件指令均有值。 */
    val sourceName: String? = null
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
     * 内置 GitHub MCP 服务器 id（= McpManager 配置名 = mcp_call 的 server 参数）。
     * 与 github/mcp 包的 `BuiltinGithubMcpServer.ID` 保持一致；这里不 import
     * app 层类，保持 slash 包纯 JVM 可单测（同 [GITHUB_TOOL_IDS] 的镜像策略）。
     */
    private const val GITHUB_MCP_SERVER_ID = "github"

    /**
     * GitHub MCP 工具（内置 github MCP 服务器 tools/list 返回的 7 个工具，
     * 命名对齐官方 github-mcp-server），经 mcp_call 调用。
     */
    private val GITHUB_MCP_TOOLS: List<String> = listOf(
        "get_me",
        "list_repositories",
        "get_file_contents",
        "create_or_update_file",
        "create_issue",
        "list_issues",
        "search_code"
    )

    /**
     * GitHub 原生工具 IDs registered by `ToolModule` when a token is connected.
     * Mirrored here (rather than imported) so the `slash` package stays free
     * of `app`-module dependencies and remains pure-JVM unit-testable. If
     * `ToolModule` adds/removes a GitHub tool, update this list in lockstep
     * （与上方 [GITHUB_MCP_TOOLS] 是两套共存的能力：原生工具直调，MCP 工具走
     * mcp_call —— 提示词里两者都列出，互为备份）。
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
            skillName = command.id,
            routeKind = "skill",
            sourceName = command.id
        )

        is SlashCommand.Mcp -> routeMcp(command, context)

        is SlashCommand.Connector -> SlashCommandRoute(
            systemMessage = "🔗 使用连接器: ${command.id}",
            agentPrompt = command.buildAgentPrompt(
                verb = "请根据此指令执行对应操作，通过 connector 工具执行",
                toolHint = "connector"
            ),
            routeKind = "connector",
            sourceName = command.id
        )
        is SlashCommand.Plugin -> SlashCommandRoute(
            // 诚实化：PluginManager.registerPluginTools 仍是 TODO（AIDL 接口未定型），
            // ToolRegistry 中不存在 plugin 工具 —— 原文案会让模型寻找不存在的工具并
            // 可能虚构执行结果。改为如实告知，禁止伪造。
            systemMessage = "📦 插件: ${command.id}（Agent 工具桥接建设中）",
            agentPrompt = command.buildAgentPrompt(
                verb = "插件 ${command.id} 已加载并验证连通，但 Agent 侧插件工具桥（AIDL → ToolRegistry）尚未接线 —— 请如实告知用户当前版本暂不能执行插件工具，不要虚构执行结果",
                toolHint = "插件运行时"
            ),
            routeKind = "plugin",
            sourceName = command.id
        )
        is SlashCommand.Unknown -> SlashCommandRoute(
            systemMessage = "⚡ 指令: ${command.raw.trim()}",
            // Forward the raw input verbatim — the user typed it on purpose
            // and the agent is best placed to interpret free-form commands.
            agentPrompt = command.raw
        )
    }

    /**
     * MCP routing.
     *
     * - `/mcp:github`：绑定内置 GitHub MCP 服务器（BUILTIN 进程内 transport，
     *   随 App 启动自动连接）。已连接（或旧调用方只携带 Token 态且已连接）→
     *   注入列出 7 个 MCP 工具 + mcp_call 用法的提示词；未连接 → 引导走
     *   GithubTokenDialog 配置流程（与此前行为一致，不回归）。
     * - 其他 `/mcp:<id>`：已连接的服务器 → 提示词引导用 mcp_call 调用其工具；
     *   未连接 → 通用骨架（"指令存在，能力待接线"，与此前一致）。
     */
    private fun routeMcp(command: SlashCommand.Mcp, context: SlashRouteContext): SlashCommandRoute {
        if (command.id == GITHUB_MCP_SERVER_ID) {
            // 内置 github 服务器随启动自动连接；上下文未携带 MCP 快照时退回
            // Token 连接态（两者对内置服务器语义一致，见 SlashRouteContext.kdoc）。
            val mcpReady = command.id in context.mcpConnected || context.githubConnected
            return if (mcpReady) {
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
        // Generic MCP (postgres / filesystem / future servers).
        return if (command.id in context.mcpConnected) {
            SlashCommandRoute(
                systemMessage = "🔌 连接 MCP: ${command.id}",
                agentPrompt = command.buildAgentPrompt(
                    verb = "请根据此指令执行对应操作，通过 MCP 服务器 ${command.id} 的工具执行（用 mcp_call 调用，server 填 \"${command.id}\"；mcp_list 可列出该服务器的全部工具）",
                    toolHint = "mcp"
                )
            )
        } else {
            SlashCommandRoute(
                systemMessage = "🔌 连接 MCP: ${command.id}",
                agentPrompt = command.buildAgentPrompt(
                    verb = "请根据此指令执行对应操作，通过 MCP 工具执行",
                    toolHint = "mcp"
                )
            )
        }
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
     * v2（内置 GitHub MCP 上线）：优先引导用 mcp_call 调用内置服务器 "github"
     * 的 7 个 MCP 工具（与官方 github-mcp-server 命名对齐），原生 `github_*`
     * 工具仍然可用（两者共存，互为备份）。若 mcp_call 返回「未连接」类错误，
     * 模型应改用原生 github_* 工具完成同一任务。User extra text is still
     * forwarded so `/mcp:github repo=owner/name 列出 open issues` works
     * end-to-end.
     */
    private fun SlashCommand.Mcp.buildGithubAgentPrompt(username: String): String = buildString {
        append("用户触发了快捷指令: /mcp:github\n")
        append("GitHub MCP 上下文已启用（已连接用户: ").append(username).append("）。\n")
        append("优先通过 MCP 调用 GitHub（用 mcp_call 工具，server 填 \"github\"，arguments 为 JSON 字符串）:\n")
        GITHUB_MCP_TOOLS.forEach { append("  - ").append(it).append('\n') }
        append("mcp_call 调用形如: {\"server\": \"github\", \"tool\": \"工具名\", \"arguments\": \"{...JSON 参数...}\"}\n")
        append("mcp_list 可随时列出已连接 MCP 服务器与全部工具。\n")
        append("原生 github_* 工具也仍然可用（若 mcp_call 报未连接/不可用错误，改用这些完成同一任务）:\n")
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
