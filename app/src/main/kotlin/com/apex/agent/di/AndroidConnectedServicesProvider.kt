package com.apex.agent.di

import com.apex.agent.core.engine.ConnectedServicesProvider
import com.apex.agent.core.tools.catalog.McpToolRegistrar
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.github.GithubTokenManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android 侧已连接服务聚合器（[ConnectedServicesProvider] 实现）。
 *
 * 系统提示词 "## Connected Services" 段的数据源：
 * - GitHub：Token 已配置 → 登录名 + 默认仓库 + **主动使用指令**（v3 S3
 *   升级：明确告知 agent 现在就能自主使用 github_* 工具、优先于 web_fetch、
 *   沙箱内 git clone/push 亦可用凭据 —— 用户核心诉求「确保 agent 会主动
 *   利用这个方式调用 GitHub，他要能知道自己能够使用」）；未配置 → 一行
 *   可行动指引（Coding 屏 GitHub 图标）——9 个 github_* 工具在 ToolModule
 *   无条件注册、工具目录里始终可见，沉默会让 agent 撞工具报错才知未连；
 * - 消息连接器：微信 / 飞书 / Telegram 已启用且配置凭据 → 告知
 *   connector_list / connector_send_message 可用。
 *
 * 设计要点：
 * - 只读快照，无 IO（TokenManager / ConnectorRegistry 均为内存 + 本地
 *   存储）；引擎每轮 buildSystemPrompt 调用一次，开销可忽略。
 * - 除 GitHub 外未连接服务仍**不注入**（与 Live Environment 的 fail-open
 *   一致：不知道的不说，避免教模型一个错误事实）—— GitHub 是唯一例外：
 *   其工具恒注册在场（见上），未连接一行指引比沉默更诚实。未连接时模型
 *   调用 github_* 会拿到可行动的引导文案（见 GithubTools 的错误处理），
 *   两处文案指向同一入口。
 */
@Singleton
class AndroidConnectedServicesProvider @Inject constructor(
    private val githubTokenManager: GithubTokenManager,
    private val connectorRegistry: ConnectorRegistry,
    // P0 修复（连接即可见）：MCP 一等工具注册表快照源
    private val mcpToolRegistrar: McpToolRegistrar
) : ConnectedServicesProvider {

    override fun connectedServicesSummary(): String? {
        val sections = mutableListOf<String>()

        // ═══ GitHub ═══
        if (githubTokenManager.isConnected()) {
            val login = githubTokenManager.getUsername() ?: "(unknown)"
            // 默认仓库未设置时同样可行动：问用户或用 github_search_repos 自找
            val repo = githubTokenManager.defaultRepo.value.ifBlank {
                "not set — ask the user or use github_search_repos"
            }
            sections.add(
                "GitHub: CONNECTED as $login.\n" +
                    "  Default workspace repo: $repo.\n" +
                    "  You CAN and SHOULD use GitHub autonomously right now: verify with\n" +
                    "  github_get_user, read/write files with github_read_file / github_write_file,\n" +
                    "  manage issues with github_create_issue / github_list_issues, search code\n" +
                    "  with github_search_code. Prefer github_* tools over generic web_fetch\n" +
                    "  for ANY github.com task. git clone/push inside the Ubuntu sandbox also\n" +
                    "  works with your credentials (terminal.exec / git tools inject them)."
            )
        } else {
            // v3 S3：未连接也注入一行可行动指引（github_* 工具恒注册在场，
            // 沉默只会让 agent 撞工具报错；见类 KDoc 的例外说明）
            sections.add(
                "GitHub: NOT connected — ask the user to tap the GitHub icon on the " +
                    "Coding toolbar to add a PAT (Settings → GitHub also works)."
            )
        }

        // ═══ 消息 / 服务连接器（微信 / 飞书 / Telegram / 自定义）═══
        val enabled = connectorRegistry.getEnabled()
        if (enabled.isNotEmpty()) {
            val ready = enabled.filter { !it.apiKey.isNullOrBlank() || it.endpoint.isNotBlank() }
            if (ready.isNotEmpty()) {
                val lines = ready.joinToString("\n") { def ->
                    val credState = if (def.apiKey.isNullOrBlank()) "endpoint-only" else "configured"
                    "  - ${def.id} (${def.name}) [$credState]"
                }
                sections.add(
                    "Messaging/service connectors (ENABLED):\n$lines\n" +
                        "  Call connector_list to see live status; send messages with\n" +
                        "  connector_send_message (WeChat Work bot / Feishu bot / Telegram bot)."
                )
            }
        }

        if (sections.isEmpty()) return null
        return sections.joinToString("\n\n")
    }

    override fun connectedToolIds(): Set<String> {
        val ids = mutableSetOf<String>()
        // ═══ GitHub：Token 已配置 → 9 个 github_* 原生工具全部进请求 ═══
        //（提示词宣称 "github_* tools are ready" —— tools 数组必须真的有它们）
        if (githubTokenManager.isConnected()) {
            ids += GITHUB_TOOL_IDS
        }
        // ═══ 已连接 MCP 服务器的一等工具（mcp__{server}__{tool}）═══
        // 注册表快照即「当前真实可用」；连接中的沙箱/远程服务器同样适用。
        // 上限保护：MCP 工具总量钳制（大服务器几十个工具时不挤占 CORE 集的
        // 请求预算 —— ToolRequestBudget.MAX_TOOLS=64，超出部分按名称序丢弃）。
        var mcpBudget = MAX_MCP_TOOLS
        for ((_, toolIds) in mcpToolRegistrar.registeredSnapshot()) {
            for (toolId in toolIds) {
                if (mcpBudget <= 0) return ids
                if (ids.add(toolId)) mcpBudget--
            }
        }
        return ids
    }

    companion object {
        /** GithubTools.kt 注册的 9 个原生工具 id（连接即全部可见）。 */
        private val GITHUB_TOOL_IDS: Set<String> = setOf(
            "github_get_user", "github_list_repos", "github_read_file",
            "github_write_file", "github_create_issue", "github_list_issues",
            "github_search_code", "github_list_branches", "github_search_repos"
        )

        /**
         * MCP 一等工具并入请求的数量上限 —— 给 CORE 集（~45 个）保留
         * MAX_TOOLS 预算的余量，超大 MCP 服务器按服务器注册顺序截断。
         */
        private const val MAX_MCP_TOOLS = 12
    }
}
