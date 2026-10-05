package com.apex.agent.core.code.standard

/**
 * # Standard Agent Catalog — Agent 画像目录（标准任务循环的「用工表」）
 *
 * 六个内置画像（对齐业界标准 Agent 工作流的角色分工）：
 *
 * | 画像 | 角色 | 工具面 | 典型用途 |
 * |---|---|---|---|
 * | [BUILD] 构建者 | 主代理 | 全量（code_* / shell / git / web / skill / mcp） | 默认档：读-改-验-总结闭环 |
 * | [PLAN] 规划师 | 主代理 | 只读（read/grep/glob/git status） | PLAN 档：产出待确认计划，零副作用 |
 * | [GENERAL] 通用 | 主代理 | 全量 | BUILD/PLAN 之外的兜底（小任务/答疑式编码）；亦可作 task 工具的子代理类型（通用执行者） |
 * | [EXPLORE] 探索 | 子代理 | 只读 | "找出所有用到 X 的地方"类委派 |
 * | [RESEARCH] 调研 | 子代理 | 联网+读 | "查一下这个库怎么用"类委派 |
 * | [REVIEWER] 评审 | 子代理 | 只读+git 变更面 | "评审本次改动"类委派（v3） |
 *
 * ## 工具面前缀白名单语义
 *
 * [StandardToolSurface] 按前缀集过滤注册表：
 * - `""`（空串）= 通配一切（构建者/通用）；
 * - `code_read` = 精确 id；
 * - `code_git_` = 前缀（命中 code_git_status / code_git_diff / …）；
 * - `mcp__` = 前缀（MCP 一等工具动态注册，全部放行给构建者/通用/调研）。
 */
object StandardAgentCatalog {

    /** 构建者（主代理默认）。 */
    val BUILD: StandardAgentDefinition = StandardAgentDefinition(
        kind = StandardAgentKind.BUILD,
        description = "构建者：直接完成任务——读、改、跑、验一体，" +
            "以最小改动闭环交付，从不留 TODO 占位。",
        toolAllowlist = setOf(
            "", // 通配：code_* / shell_execute / git / web / skill / mcp__* 全量
        ),
        defaultMaxTurns = 32,
        subagentMaxTurns = 12
    )

    /** 规划师（主代理 PLAN 档）：只读 + 计划产出。 */
    val PLAN: StandardAgentDefinition = StandardAgentDefinition(
        kind = StandardAgentKind.PLAN,
        description = "规划师：只读探索工作区，产出可执行的分步计划（Todo 清单 + " +
            "每步的验证口径），交用户确认——规划阶段零副作用。",
        toolAllowlist = setOf(
            "code_read", "code_grep", "code_glob", "code_check",
            "code_git_status", "code_git_diff", "code_git_log",
            "code_todo"
        ),
        writeToolsAllowed = false,
        defaultMaxTurns = 16,
        subagentMaxTurns = 8
    )

    /**
     * 通用（主代理兜底）。也可作为 task 工具的子代理类型（通用执行者：
     * 完整工具面的自包含多步任务执行，画像段见
     * [StandardPrompts.generalSubAgent]）。
     */
    val GENERAL: StandardAgentDefinition = StandardAgentDefinition(
        kind = StandardAgentKind.GENERAL,
        description = "通用执行：与构建者同工具面，回答式编码（答疑 / " +
            "小改动 / 现场检查）优先直答，不强行展开任务循环。",
        toolAllowlist = setOf(""),
        defaultMaxTurns = 24,
        subagentMaxTurns = 12
    )

    /** 探索（子代理）：只读代码探索。 */
    val EXPLORE: StandardAgentDefinition = StandardAgentDefinition(
        kind = StandardAgentKind.EXPLORE,
        description = "探索员：只读探索代码，返回带 path:line 引用的结构化结论，" +
            "不改动任何文件。",
        toolAllowlist = setOf(
            "code_read", "code_grep", "code_glob", "code_check",
            "code_git_status", "code_git_diff", "code_git_log"
        ),
        writeToolsAllowed = false,
        defaultMaxTurns = 16,
        subagentMaxTurns = 10
    )

    /** 调研（子代理）：联网检索 + 本地阅读。 */
    val RESEARCH: StandardAgentDefinition = StandardAgentDefinition(
        kind = StandardAgentKind.RESEARCH,
        description = "调研员：联网检索（web 搜索 / 抓取 / HTTP）+ 本地只读，" +
            "返回带来源 URL 的结论。",
        toolAllowlist = setOf(
            "web_search", "web_fetch", "http_request", "http",
            "code_read", "code_grep", "code_glob",
            "mcp__"
        ),
        writeToolsAllowed = false,
        defaultMaxTurns = 16,
        subagentMaxTurns = 10
    )

    /** 评审（子代理，v3）：只读代码评审——先看 git 变更再深入读相关文件，
     *  按严重性分级输出发现并给出合入结论（画像段见
     *  [StandardPrompts.reviewerSubAgent]）。 */
    val REVIEWER: StandardAgentDefinition = StandardAgentDefinition(
        kind = StandardAgentKind.REVIEWER,
        description = "评审员：只读评审代码（先看 git 变更再深入读相关文件），" +
            "按严重性分级输出发现（阻断/警告/建议），给出是否可合入的结论。",
        toolAllowlist = setOf(
            "code_read", "code_grep", "code_glob", "code_check",
            "code_git_status", "code_git_diff", "code_git_log"
        ),
        writeToolsAllowed = false,
        defaultMaxTurns = 16,
        subagentMaxTurns = 10
    )

    /** 全量目录（稳定顺序：主代理三画像在前，子代理三画像在后）。 */
    val ALL: List<StandardAgentDefinition> =
        listOf(BUILD, PLAN, GENERAL, EXPLORE, RESEARCH, REVIEWER)

    /** 可作为 task 工具 subagent_type 的画像（角色 = 子代理）。 */
    val SUBAGENT_KINDS: List<StandardAgentKind> =
        ALL.map { it.kind }.filter { it.role == StandardAgentRole.SUBAGENT }

    /** 主代理可切换画像（Coding 屏 Build/Plan 双档的映射目标）。 */
    val PRIMARY_KINDS: List<StandardAgentKind> =
        ALL.map { it.kind }.filter { it.role == StandardAgentRole.PRIMARY }

    /**
     * 按种类取画像；未知种类（不可能——枚举封闭）抛 IllegalStateException
     * 提前暴露目录缺项。
     */
    fun definitionOf(kind: StandardAgentKind): StandardAgentDefinition =
        ALL.firstOrNull { it.kind == kind }
            ?: error("StandardAgentCatalog missing definition for ${kind.key}")

    /**
     * 主代理档位映射：Coding 屏 Build/Plan 双档 → 标准
     * Agent 画像（BUILD → [BUILD]；PLAN → [PLAN]；其余历史档 → [GENERAL]）。
     */
    fun primaryFor(modeKey: String): StandardAgentDefinition {
        val normalized = modeKey.trim().lowercase()
        return when (normalized) {
            "build" -> BUILD
            "plan" -> PLAN
            else -> GENERAL
        }
    }
}
