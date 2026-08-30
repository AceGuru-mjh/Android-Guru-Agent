package com.apex.agent.core.code

import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ThinkingLevel

/**
 * Code Agent 的工作流策略（Spec §25）。
 *
 * 不新建一套独立 Agent Loop —— 复用现有 [com.apex.agent.core.engine.orchestrator.TaskOrchestrator]
 * / [com.apex.agent.core.engine.orchestrator.TaskStateMachine] /
 * [com.apex.agent.core.engine.orchestrator.RecoveryPlanner] /
 * [com.apex.agent.core.engine.orchestrator.LoopDetector]。
 * 本类只是给 orchestrator 喂的配置：定义 code 任务的步骤偏好、重试预算、循环检测阈值。
 *
 * 典型 Code 工作流（Spec §26 示例）：
 * Understand → Locate → Analyze → Plan → Edit → Diagnose → Build → Test → Review
 * 具体步骤由任务决定，不要求每次都完整执行。
 */
data class CodeOrchestrationPolicy(
    /** Code engine 默认执行模式（PLAN 偏先出计划再改；BUILD 偏边想边做）。 */
    val defaultMode: AgentMode = AgentMode.PLAN,
    /** Code 默认思考深度（多方案比对 + 风险评估，避免乱改代码）。 */
    val thinkingLevel: ThinkingLevel = ThinkingLevel.DEEP,
    /** 最大重试预算：edit→diagnostics→build→test 循环上限（Spec §68 Loop detection）。 */
    val maxRepairIterations: Int = 3,
    /** 是否强制 edit 后跑 diagnostics（Spec §24 自动验证闭环）。 */
    val requireDiagnosticsAfterEdit: Boolean = true,
    /** 是否强制 build 后跑 test。 */
    val requireTestAfterBuild: Boolean = true,
    /** edit→diagnostics→build→test 全失败时是否自动尝试修复。 */
    val autoRepair: Boolean = true,
    /** Code 默认暴露给 LLM 的工具子集（null = 全部 code_* + git_* + 继承的通用工具）。 */
    val enabledToolIds: Set<String>? = null
) {
    companion object {
        /**
         * 默认 Code 工具白名单（Spec §44）—— code_* + git_* + 必要的通用工具。
         * null 表示全开（继承 Agent 全部工具）；此处给出推荐白名单供 DI 注入。
         */
        val DEFAULT_CODE_TOOL_IDS: Set<String> = setOf(
            // code 文件工具
            "code_read", "code_write", "code_edit", "code_create", "code_delete", "code_move", "code_copy", "code_glob", "code_search",
            // code intelligence
            "code_definition", "code_references", "code_hover", "code_diagnostics", "code_rename",
            // build / test
            "code_build", "code_test",
            // git
            "git_status", "git_diff", "git_log", "git_commit",
            // 继承的通用工具（web 搜索查文档、终端兜底、记忆）
            "web_search", "web_fetch", "memorize", "recall"
        )

        val DEFAULT = CodeOrchestrationPolicy(
            defaultMode = AgentMode.PLAN,
            thinkingLevel = ThinkingLevel.DEEP,
            maxRepairIterations = 3,
            requireDiagnosticsAfterEdit = true,
            requireTestAfterBuild = true,
            autoRepair = true,
            enabledToolIds = DEFAULT_CODE_TOOL_IDS
        )
    }
}
