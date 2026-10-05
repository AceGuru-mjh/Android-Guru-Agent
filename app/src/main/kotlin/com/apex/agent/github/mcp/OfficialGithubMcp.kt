package com.apex.agent.github.mcp

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// ══════════════════════════════════════════════════════════════════════
//  官方远程 GitHub MCP 预设（v3 S3）
// ══════════════════════════════════════════════════════════════════════

/**
 * # GitHub 官方远程 MCP 服务器预设
 *
 * GitHub 官方托管的远程 MCP Server（Streamable HTTP）——与内置进程内
 * [BuiltinGithubMcpServer]（7 工具、REST 直连）互补：官方服务器暴露
 * 全量 GitHub 工具面（issues/PR/pull-request review/code scanning 等，
 * 持续跟随上游更新），适合需要内置版没有的能力时启用。
 *
 * ## 接入形态（GitHub 官方文档核实）
 * - URL：`https://api.githubcopilot.com/mcp/`，HTTP 传输；
 * - 认证：PAT 经 `Authorization: Bearer <PAT>` 请求头（**不支持匿名**）；
 * - 本地 Docker 形态（ghcr.io/github/github-mcp-server + env
 *   GITHUB_PERSONAL_ACCESS_TOKEN）在 Android 上不可行（无 docker）——
 *   本 App 走远程 HTTP 形态。
 *
 * ## 令牌零落盘（G8 根修）
 * 预置配置的 Authorization 头写 [TOKEN_PLACEHOLDER] 占位符（Bearer 前缀 +
 * `${GITHUB_TOKEN}` 字面量，见 [config]），**占位符原样落盘
 * mcp_servers.json**；连接时由 McpManager 的令牌桥（`gitHubTokenProvider`
 * → GithubTokenManager）解析注入真 PAT——真 token 永不进入明文配置，
 * GitHub 断开后重连即失败并给出可行动指引。
 *
 * ## 预置语义（安装 ≠ 启动）
 * `enabled=false` 预置：mcp_list / 市场页可见，用户手动启用后连接；
 * 同名条目已存在（用户自建或旧版预置）→ 不动（enabled 偏好跨升级保留）。
 */
object OfficialGithubMcp {

    /** 服务器名（= mcp_servers.json 配置名 = mcp_connect/mcp_call 的 server 参数）。 */
    const val SERVER_NAME = "github-official"

    /** 官方远程 MCP 端点（GitHub 托管，HTTP 传输）。 */
    const val REMOTE_URL = "https://api.githubcopilot.com/mcp/"

    /**
     * PAT 占位符（与 McpManager.GITHUB_TOKEN_PLACEHOLDER 同值同源）：
     * 写在 headers/env 值里，连接时由令牌桥解析为真 PAT。
     * Kotlin 源码内写法 `"$" + "{GITHUB_TOKEN}"`（$ 转义防模板插值）。
     */
    const val TOKEN_PLACEHOLDER = McpManager.GITHUB_TOKEN_PLACEHOLDER

    /** 官方远程 MCP 配置（enabled=false：安装 ≠ 启动，用户在市场手动启用）。 */
    fun config(): McpServerConfig = McpServerConfig(
        name = SERVER_NAME,
        url = REMOTE_URL,
        transport = McpTransport.HTTP,
        enabled = false,
        // #197 市场分级：GitHub 能力归属 Coding 工位（与内置 github 一致）
        scope = "coding",
        // 官方端点要求 Bearer 方案 → 值为 "Bearer ${占位符}"（连接时整体解析），
        // 纯占位符（无 Bearer 前缀）会被官方端点 401 拒绝
        headers = mapOf("Authorization" to "Bearer $TOKEN_PLACEHOLDER")
    )
}

/**
 * 官方远程 GitHub MCP 的启动预置器（BuiltinGithubMcpBootstrap 同款模式）。
 *
 * 由 McpModule 在 `provideMcpManager` 里调用（@Provides 副作用模式）：
 * 幂等写入配置（仅缺失时——同名条目已存在则完全不碰，用户 enabled 偏好
 * 与自建同名配置都不被动持），不自动连接（enabled=false，与 SANDBOX_
 * PRESET_SERVERS「只预置不连接」口径一致）。ensure 是挂起函数，放自持
 * IO scope 执行，不阻塞注入线程。
 */
object OfficialGithubMcpBootstrap {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensurePreset(manager: McpManager) {
        scope.launch {
            runCatching {
                val existing = manager.getConfigs()
                    .any { it.name == OfficialGithubMcp.SERVER_NAME }
                if (existing) return@launch
                manager.addServer(OfficialGithubMcp.config()).onFailure {
                    AppLogger.instance.warn(
                        LogCategory.SYSTEM, "OfficialGithubMcp",
                        "预置官方 GitHub 远程 MCP 失败: ${it.message}"
                    )
                }
            }.onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "OfficialGithubMcp",
                    "预置官方 GitHub 远程 MCP 异常: ${it.message ?: it.javaClass.simpleName}"
                )
            }
        }
    }
}
