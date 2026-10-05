package com.apex.agent.di

import com.apex.agent.core.tools.ToolRunPolicy

/**
 * ═══ 终端/环境类工具的执行器超时策略表（v3 ToolRunPolicy 显式覆盖）═══
 *
 * 背景（Ubuntu 环境探索验证，docs/ubuntu-environment-exploration.md §6）：
 * `DefaultToolRunPolicyResolver` 对未覆盖的 mutating 工具推断为 60s 执行
 * 预算；而 Ubuntu 环境工具自身管理分钟级~十分钟级预算（rootfs 解包 2~5min、
 * apt install 600s、ensure 编排 15~30min）。执行器 withTimeout 会先于工具
 * 自身的超时逻辑杀掉调用 —— Agent 请求 `terminal.ubuntu.ensure` 等
 * 环境入口 100% 在 60s 收到 "timeout: tool call exceeded 60000ms budget"，
 * 即使工作本身可恢复/可续跑。
 *
 * 原则：执行器预算 = 工具自身内部预算 + 余量，不盲重试（幂等由工具
 * 状态机保证，IN_PROGRESS 可续跑）。提出为公开常量供单测对齐校验
 * （TerminalToolPolicyTest），防再次漂移。
 */
val TERMINAL_TOOL_RUN_POLICIES: Map<String, ToolRunPolicy> = mapOf(
    // shell 命令有自己的命令级确认与用户交互窗口：长超时不重试。
    "shell_execute" to ToolRunPolicy(timeoutMs = 120_000L, maxRetries = 0),
    // 抓屏/敲链可能被系统限速：给一次重试余量。
    "screenshot" to ToolRunPolicy(timeoutMs = 30_000L, maxRetries = 1, baseRetryDelayMs = 500L),
    // ═══ P0 修复（超时错配）：terminal.exec 自身管理 timeout_ms
    // （schema 声明 1000..600000ms，默认 30s），但默认推断策略是
    // mutating() 60s —— 执行器 withTimeout 会先于工具自身的超时
    // 逻辑杀掉长命令，模型明明要了 300s 却在 60s 收到
    // "Error: timeout: tool call exceeded 60000ms budget"。
    // 对齐工具自身上限（600s + 10s 余量），不盲重试。
    "terminal.exec" to ToolRunPolicy(timeoutMs = 610_000L, maxRetries = 0),
    // legacy terminal_exec 默认 timeoutMs 120s（可更高），同样被
    // 60s mutating 策略截杀。
    "terminal_exec" to ToolRunPolicy(timeoutMs = 610_000L, maxRetries = 0),
    // Ubuntu rootfs 安装 / Linux bootstrap 是长时下载+解压操作
    // （完整 rootfs 300MB+，真机上可达十余分钟），60s 必杀。
    "terminal.ubuntu.install" to ToolRunPolicy(timeoutMs = 1_200_000L, maxRetries = 0),
    "terminal.linux.bootstrap" to ToolRunPolicy(timeoutMs = 1_200_000L, maxRetries = 0),
    // 包安装（apt install）也可能超过 60s。
    "terminal.linux.packages" to ToolRunPolicy(timeoutMs = 600_000L, maxRetries = 0),

    // ═══ P0 修复（Ubuntu 探索验证 · 超时错配批次 2）：环境入口工具 ═══
    // 以下工具全部自身管理长预算，但推断策略为 mutating() 60s ——
    // Agent 调用即 60s 必杀（此前从未被覆盖，环境链路事实不可用）。
    // terminal.ubuntu.ensure：一发式 install→bootstrap→capability 快照，
    //   内部预算 DEFAULT_ENSURE_TIMEOUT_MS = 30min（1800s）。
    "terminal.ubuntu.ensure" to ToolRunPolicy(timeoutMs = 1_830_000L, maxRetries = 0),
    // terminal.workspace.environment ensure：lifecycle ensureReady（≤900s
    //   内部预算）+ 批量 apt install 工具链 + 复测。
    "terminal.workspace.environment" to ToolRunPolicy(timeoutMs = 960_000L, maxRetries = 0),
    // terminal.linux.capabilities ensure：refresh probe → apt install
    //   （600s apt 超时）→ invalidate + re-probe。
    "terminal.linux.capabilities" to ToolRunPolicy(timeoutMs = 660_000L, maxRetries = 0),
    // terminal.linux.repair：单轮修复可含 rootfs 重解包（分钟级 proot 操作）。
    "terminal.linux.repair" to ToolRunPolicy(timeoutMs = 1_260_000L, maxRetries = 0),
    // terminal.linux.network diagnose：端到端探针是一次真实 apt-get update
    //   （内部 apt 超时 600s）—— 慢网络下远超 60s。
    "terminal.linux.network" to ToolRunPolicy(timeoutMs = 660_000L, maxRetries = 0),
    // terminal.linux.status 全量 6 维检查：每维一次 proot exec（10~30s 级），
    //   全量可达数分钟（quick=true 轻量）。
    "terminal.linux.status" to ToolRunPolicy(timeoutMs = 300_000L, maxRetries = 0),
    // terminal.diagnostics（T87）：会话/后端面秒回；smokeTest=true 含一次
    //   真实 exec 探针（最坏 600s —— 与 terminal.exec 同预算）。
    "terminal.diagnostics" to ToolRunPolicy(timeoutMs = 660_000L, maxRetries = 0),
    // terminal.wait：schema 无 timeoutMs 上限，模型可请求 > 60s 等待
    //   （默认 60s 恰好撞 mutating 预算线）。给足等待自身上限 + 余量。
    "terminal.wait" to ToolRunPolicy(timeoutMs = 660_000L, maxRetries = 0)
)

/**
 * ═══ P2 修复（开放世界工具族超时错配）：前缀策略表 ═══
 *
 * 背景：browser_*（15 个）/ mcp_call / mcp__server__tool（动态一等工具）/
 * connector_send_message 按注解推断为 mutating() —— 60s 硬超时 + 0 重试：
 * - 慢网页操作 / 慢 MCP 服务端 > 60s 即被执行器 withTimeout 强杀，而强杀
 *   可能留下半执行的副作用（点击已发生、消息已发出），模型看到超时
 *   重试 → 二次副作用；
 * - mcp__ 动态 id 无法逐个列进静态覆盖表 → DefaultToolRunPolicyResolver
 *   新增前缀族覆盖（最长前缀胜出）。
 *
 * 原则：对非幂等工具宁可放大超时也不要早杀（宁可慢也不重复执行副作用）。
 */
val OPEN_WORLD_PREFIX_TOOL_RUN_POLICIES: Map<String, ToolRunPolicy> = mapOf(
    // MCP 动态一等工具（mcp__server__tool）：走远端/沙箱进程，慢服务器常见；
    // 不重试（非幂等风险由具体工具自查），只放宽超时。
    "mcp__" to ToolRunPolicy(timeoutMs = 200_000L, maxRetries = 0),
    // 浏览器自动化族：页面加载/滚动/下载都是秒级~十秒级网络操作。
    "browser_" to ToolRunPolicy(timeoutMs = 120_000L, maxRetries = 1, baseRetryDelayMs = 1_000L),
    // MCP 元工具 mcp_call：远端 JSON-RPC / 沙箱 npx 冷启动（180s 握手放宽口径）。
    "mcp_call" to ToolRunPolicy(timeoutMs = 200_000L, maxRetries = 0),
    // 连接器消息发送（Telegram/邮件等第三方网关）：网络慢常见；不盲重试
    // （消息重复发送比超时更糟）。
    "connector_" to ToolRunPolicy(timeoutMs = 120_000L, maxRetries = 0)
)
