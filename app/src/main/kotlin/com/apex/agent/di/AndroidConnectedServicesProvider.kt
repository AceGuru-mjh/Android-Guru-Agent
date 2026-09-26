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
 * - GitHub：Token 已配置 → 告知登录名 + 首个验证动作（github_get_user）
 * - 消息连接器：微信 / 飞书 / Telegram 已启用且配置凭据 → 告知
 *   connector_list / connector_send_message 可用
 *
 * 设计要点：
 * - 只读快照，无 IO（TokenManager / ConnectorRegistry 均为内存 + 本地存储）；
 *   引擎每轮 buildSystemPrompt 调用一次，开销可忽略。
 * - 未连接服务**不注入**（与 Live Environment 的 fail-open 一致：不知道
 *   的不说，避免教模型一个错误事实）。未连接时模型调用 github_* 会拿到
 *   可行动的引导文案（见 GithubTools 的错误处理），那是执行侧的职责。
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
            sections.add(
                "GitHub: CONNECTED as $login.\n" +
                    "  github_* tools are ready: verify with github_get_user, browse with\n" +
                    "  github_list_repos, read/write files with github_read_file / github_write_file,\n" +
                    "  find code with github_search_code. Prefer these over generic web_fetch for GitHub tasks."
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
