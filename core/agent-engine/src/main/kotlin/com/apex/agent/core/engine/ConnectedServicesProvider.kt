package com.apex.agent.core.engine

/**
 * 已连接服务提供者 —— 系统提示词 "## Connected Services" 段的数据源。
 *
 * ## 为什么需要它（根因修复）
 *
 * 修复前，GitHub Token 已配置 / 连接器已启用时，模型完全不知道：
 * 工具清单里 7 个 github_* 工具混在 106 个工具中，描述被截断到 160 字符，
 * 模型无从判断"GitHub 已连接、现在就能用"，于是从不主动调用（用户反馈
 * "GitHub 连接没有作用"）。未连接时更要命：模型调用后拿到"未连接"错误，
 * 既不知道引导用户去哪配置，也往往直接放弃。
 *
 * 修复策略（学习 opencode 的 env 块 + operit 的 ACTIVE_PACKAGES 段）：
 * 把"服务连接状态"作为**每轮注入的环境真值**告诉模型——与
 * ToolEnvironmentState 的 fail-open 哲学一致：知道的一定说，不知道的
 * 不猜。App 层实现（AndroidConnectedServicesProvider）聚合：
 * - GitHub：是否连接 + 登录名（引导用 github_get_user 验证）
 * - 连接器：启用的微信/飞书/Telegram 等（引导用 connector_list / connector_send_message）
 *
 * 引擎侧仅消费 [connectedServicesSummary] 的字符串，保持 core 模块
 * 零 Android 依赖（实现类在 app 模块，经 Hilt 注入）。
 */
interface ConnectedServicesProvider {
    /**
     * 当前已连接/已启用的外部服务摘要（面向模型的文本）。
     *
     * 返回 null 或空白 = 无任何已连接服务（系统提示词省略该段落，
     * 不注入噪音）。文本应是自包含的引导性描述，例如：
     *
     * ```
     * GitHub: CONNECTED as octocat — github_* tools are ready.
     *   Verify anytime with github_get_user; list repos with github_list_repos.
     * Messaging connectors: feishu (configured), telegram (configured).
     *   Use connector_list then connector_send_message to send messages.
     * ```
     */
    fun connectedServicesSummary(): String?

    /**
     * 已连接服务对应的工具 id 集 —— **连接即对模型可见**（P0 修复）。
     *
     * ## 为什么需要它（提示词与 tools 数组不一致的根因）
     *
     * `connectedServicesSummary` 告诉模型 "github_* tools are ready, use
     * them directly"，但 github_* 不在 ToolTierPolicy.CORE_TOOL_IDS、也不在
     * 会话激活集 → 请求的 tools 数组里**根本没有这些函数**。OpenAI 兼容
     * provider 对未声明的函数调用一律拒绝（"tool not found" / 参数校验失败），
     * 弱模型也不会主动走 tool_search → tool_open 两跳 —— 表现即用户反馈的
     * "GitHub 密钥连接没有一点作用，agent 根本不会直接使用"。
     *
     * 引擎把这些 id 并入本轮工具计划（与 CORE 同待遇进请求），让
     * "提示词宣称的能力"与"请求 tools 数组实际下发的函数"永远一致。
     * 未注册的 id 自动忽略（assemble 阶段 mapNotNull 过滤）。
     *
     * 返回空集 = 无已连接服务（零行为变化）。
     */
    fun connectedToolIds(): Set<String> = emptySet()
}
