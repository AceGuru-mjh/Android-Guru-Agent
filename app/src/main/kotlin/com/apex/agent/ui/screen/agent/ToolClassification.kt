package com.apex.agent.ui.screen.agent

import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata

/**
 * # 工具调用来源分类（Tool System v2 抽出的单一职责协作文件）
 *
 * [classifyTool] 原内嵌于 [AgentChatViewModel]，随 v2 元数据接入膨胀后
 * 按职责缝拆出（文件预算门禁：主源 ≤1200 行）。纯函数、无状态——
 * 分类规则与 ViewModel 的会话状态/引擎接线互不纠缠。
 */

/** [classifyTool] 用的 server 字段提取正则（原实现在每次调用时重复编译，现提升到顶层）。 */
private val SERVER_FIELD_REGEX = Regex("""(?i)"server"\s*:\s*"([^"]+)"""")

/**
 * 根据工具名推断调用来源分类，用于 UI 差异化呈现。
 *
 * 规则（按优先级）：
 * - `mcp_call` / `mcp_call_<server>_<tool>` → MCP，并从参数中解析 server；
 * - **元数据优先**（v2）：注册表能查到元数据时按类别直接映射
 *   （GITHUB/http_request→连接器、SKILL/PLUGIN/MCP 同名、WEB→搜索/抓取）
 *   ——新工具零改动获得正确分类，不再依赖 id 前缀启发式；
 * - `web_search` → 联网搜索；`web_fetch` → 网页抓取；
 * - `plugin*` 前缀 → 插件（plugin-sdk 经 ToolRegistry 注册的工具）；
 * - `connector*` / `http_request` / `github_*` → 连接器（连接外部服务的 API 调用）；
 * - 其余含 "skill" → Skill；
 * - 均未命中但当前处于 Skill/连接器/插件路由上下文 → 归入该上下文来源；
 * - 其余 → 本地工具。
 */
fun classifyTool(
    toolName: String,
    args: String,
    contextKind: ToolKind? = null,
    metadata: ToolMetadata? = null
): Pair<ToolKind, String?> {
    if (toolName.startsWith("mcp_call")) {
        // RouterMcpTool 通过 arguments 的 "server" 字段传入 server 名。
        val server = SERVER_FIELD_REGEX.find(args)
            ?.groupValues?.getOrNull(1)
        return ToolKind.MCP to server
    }
    // ── v2：元数据优先（注册表命中且能映射到非本地来源时）──
    if (metadata != null) {
        val kind = when (metadata.category) {
            ToolCategory.MCP -> ToolKind.MCP
            ToolCategory.GITHUB -> ToolKind.CONNECTOR
            ToolCategory.SKILL -> ToolKind.SKILL
            ToolCategory.PLUGIN -> ToolKind.PLUGIN
            ToolCategory.WEB -> when (toolName) {
                "web_search" -> ToolKind.WEB_SEARCH
                "web_fetch" -> ToolKind.WEB_FETCH
                "http_request" -> ToolKind.CONNECTOR
                else -> ToolKind.WEB_FETCH
            }
            else -> null
        }
        if (kind != null) return kind to null
    }
    if (toolName == "web_search") return ToolKind.WEB_SEARCH to null
    if (toolName == "web_fetch") return ToolKind.WEB_FETCH to null
    if (toolName.startsWith("plugin")) return ToolKind.PLUGIN to null
    if (toolName.startsWith("connector") || toolName == "http_request" ||
        toolName.startsWith("github_")) {
        return ToolKind.CONNECTOR to null
    }
    if (toolName.contains("skill", ignoreCase = true)) return ToolKind.SKILL to null
    // 路由上下文兜底：Skill/连接器/插件流水线内产生的未匹配工具归入该来源，
    // 让 `/connector:ssh` 会话里的 shell_execute 也能带上连接器链路徽章。
    if (contextKind != null) return contextKind to null
    return ToolKind.LOCAL to null
}
