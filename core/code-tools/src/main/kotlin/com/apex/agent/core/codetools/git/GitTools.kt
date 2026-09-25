package com.apex.agent.core.codetools.git

import com.apex.agent.core.tools.AgentTool

/**
 * # GitTools — Coding 模式 git 工具集聚合入口（Issue #153）
 *
 * 五个 git 工具（status / diff / log / commit / branch），与 code_read 等
 * 文件工具同族：
 *
 * - 全部经注入的 [GitCommandRunner] 执行——生产实现是 app 层的
 *   ProotGitCommandRunner（git 二进制在 PRoot Ubuntu 沙箱内，工作区恒定
 *   bind 为 guest /workspace）；测试注入脚本化假 runner，纯 JVM 可跑；
 * - Agent 模式与 Code 模式互用（注册进 ToolRegistry 后两模式共享）；
 * - diff 视图复用 UI 侧已有的 DiffOutput 渲染（code_git_diff 输出统一
 *   diff 原文，+/- 行着色由渲染层完成）；
 * - 全部工具输出超过 [MAX_TOOL_OUTPUT_CHARS] 字符即截断——对齐执行器的
 *   maxToolOutputLength=8000 预算，给工具调用包装信息留出余量。
 *
 * 注册方式（主控 ToolModule 接线）：
 * `GitTools.all(ProotGitCommandRunner(...))` 追加进现有 code_* 工具清单。
 */
object GitTools {

    /**
     * 构建全部 code_git_* 工具。
     *
     * @param runner git 命令执行通道（DI 提供；工具实例无状态，可多工具共享）
     */
    fun all(runner: GitCommandRunner): List<AgentTool> = listOf(
        GitStatusTool(runner),
        GitDiffTool(runner),
        GitLogTool(runner),
        GitCommitTool(runner),
        GitBranchTool(runner)
    )
}

// ══════════════════════════════════════════════════════════════════
//  git/ 包内共享助手（internal——不进入模块公开 API）
// ══════════════════════════════════════════════════════════════════

/** 全部 git 工具的输出字符上限（对齐 maxToolOutputLength=8000 预算留余量）。 */
internal const val MAX_TOOL_OUTPUT_CHARS = 6000

/**
 * 把工具输出截断到 [MAX_TOOL_OUTPUT_CHARS] 字符；超限时尾部追加提示行
 * （提示行不以 + 或 - 开头，避免被 diff 渲染层着色）。
 */
internal fun boundedToolOutput(text: String): String {
    if (text.length <= MAX_TOOL_OUTPUT_CHARS) return text
    return text.take(MAX_TOOL_OUTPUT_CHARS) +
        "\n（输出超长，已截断至 $MAX_TOOL_OUTPUT_CHARS 字符——请缩小范围后分批查看）"
}

/**
 * 「不是 git 仓库」的统一引导文本——git 报错原文对模型不友好，翻译成
 * 可操作的下一步（初始化 or 装环境）。
 */
internal fun notRepoGuidance(): String =
    "当前工作区还不是 git 仓库。可先调用 code_git_commit 创建首次提交（会自动 " +
        "git init 初始化仓库），或让用户在终端的 Ubuntu 环境里手动执行 git init" +
        "（若终端尚未安装 Ubuntu，先执行 terminal.ubuntu.install 安装环境）"

/**
 * 清洗模型传入的相对路径参数（diff/log 的 pathspec）。
 *
 * 返回 null 表示「未提供」；返回空串以外的值表示有效路径。拒绝三类输入：
 * 绝对路径（以 / 开头）、前导短横线（会被 git 当作选项解析）、含 .. 段
 * （目录穿越，与 BuiltinFsMcpTransport 的防线语义一致——直接拒绝而非
 * 规范化宽松处理）。
 */
internal fun sanitizePathspec(raw: String?): String? {
    val path = raw?.trim().orEmpty()
    if (path.isEmpty()) return null
    if (path.startsWith("/")) return null
    if (path.startsWith("-")) return null
    if (path.split('/').any { it == ".." }) return null
    return path
}

/** sanitizePathspec 拒绝时的报错文案（字段名由调用方拼进 ToolResult）。 */
internal fun invalidPathMessage(field: String): String =
    "'$field' 必须是工作区内的相对路径：不能以 / 或 - 开头，也不能包含 .. 段"
