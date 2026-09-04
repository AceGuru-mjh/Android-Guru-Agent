package com.apex.agent.ui.screen.agent

import com.apex.agent.github.GithubTokenManager
import com.apex.agent.slash.SlashCommandParser
import com.apex.agent.slash.SlashCommandRouter
import com.apex.agent.slash.SlashRouteContext

/**
 * 斜杠指令的纯解析/路由执行器（从 [AgentChatViewModel.handleSlashCommand] 抽出）。
 *
 * 解析格式：`/skill:code_interpreter [key=value ...] 附加的用户要求...`
 *
 * 解析与路由职责已下沉到 [SlashCommandParser] + [SlashCommandRouter]，
 * 本对象只负责：
 * - 把当前 GitHub 连接状态快照成 [SlashRouteContext] 传给路由器；
 * - 把路由结果（systemMessage + agentPrompt）整理成 [Result] 交给调用方。
 *
 * 本对象不触碰任何 UI 状态 / 信号流 / 引擎 —— 由调用方（AgentChatViewModel）
 * 把 [Result] 应用到 StateFlow、发射 GitHub 连接信号并执行 agentPrompt，
 * 保证与原内联实现行为完全一致。
 */
internal object SlashCommands {

    /**
     * 一条斜杠指令经解析与路由后的执行计划。
     */
    sealed interface Result {

        /**
         * 追加到消息列表的反馈条目：Skill/连接器/插件指令用 [AgentUiMessage.PipelineBanner]
         * 专用横幅，其余指令用 [AgentUiMessage.System] 行。
         */
        val banner: AgentUiMessage

        /** 应用该结果时 UI 应进入的 loading 状态（GitHub 连接请求为 false）。 */
        val isLoading: Boolean

        /**
         * 可直接交给 AgentEngine 执行的普通路由结果。
         *
         * @property contextKind 触发的流水线来源（Skill/连接器/插件），无则 null；
         *   调用方应记录为路由上下文，循环内产生的工具调用会被标记同来源。
         * @property contextName 触发的流水线名称（指令 id），无则 null。
         * @property agentPrompt 交给 AgentEngine 执行的提示词。
         */
        data class Execute(
            override val banner: AgentUiMessage,
            val contextKind: ToolKind?,
            val contextName: String?,
            val agentPrompt: String
        ) : Result {
            override val isLoading: Boolean get() = true
        }

        /**
         * 需要用户先完成 GitHub 连接的特例路由结果（目前仅未连接时的 `/mcp:github`）。
         *
         * 调用方应追加 [banner]、发射 GitHub 连接信号并**不**调用 agentEngine.execute
         * —— 因为没有可执行的上下文。
         */
        data class RequestGithubConnect(
            override val banner: AgentUiMessage
        ) : Result {
            override val isLoading: Boolean get() = false
        }
    }

    /**
     * 解析并路由一条斜杠指令。
     *
     * @param command 以 `/` 开头的原始指令文本。
     * @param githubTokenManager 用于快照当前 GitHub 连接状态。
     */
    fun handle(command: String, githubTokenManager: GithubTokenManager): Result {
        val parsed = SlashCommandParser.parse(command)
        val githubState = githubTokenManager.connectionState.value
        val context = SlashRouteContext(
            githubConnected = githubState.isConnected,
            githubUsername = githubState.username
        )
        val route = SlashCommandRouter.route(parsed, context)

        // 始终携带反馈消息，让用户看到指令被识别 + 当前状态：
        // Skill/连接器/插件指令使用专用横幅（PipelineBanner），其余指令用 System 行。
        val banner = if (route.routeKind != null && route.sourceName != null) {
            AgentUiMessage.PipelineBanner(
                kind = kindOf(route.routeKind),
                name = route.sourceName
            )
        } else {
            AgentUiMessage.System(route.systemMessage)
        }

        return if (route.requestGithubConnect) {
            Result.RequestGithubConnect(banner = banner)
        } else {
            Result.Execute(
                banner = banner,
                contextKind = route.routeKind?.let { kindOf(it) },
                contextName = route.sourceName,
                agentPrompt = route.agentPrompt
            )
        }
    }

    /** 路由类别字符串 → [ToolKind]（未知类别归为 Skill 展示）。 */
    private fun kindOf(routeKind: String?): ToolKind = when (routeKind) {
        "connector" -> ToolKind.CONNECTOR
        "plugin" -> ToolKind.PLUGIN
        else -> ToolKind.SKILL
    }
}
