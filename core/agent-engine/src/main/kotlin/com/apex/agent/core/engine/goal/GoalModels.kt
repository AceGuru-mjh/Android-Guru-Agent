package com.apex.agent.core.engine.goal

import kotlinx.serialization.Serializable

/**
 * # Goal 模式数据模型（v3）
 *
 * 目标 = 陈述（做什么）+ **可验证的完成条件**（做成什么样才算完，如
 * 「所有测试通过」「页面在 300ms 内返回 200」）。引擎每轮自然收尾时由
 * 快速模型（FAST 角色）对照条件验收，未达标自动续跑。
 *
 * 状态机：`ACTIVE →（验收通过）→ ACHIEVED`
 *                `└→（轮次耗尽 / 用户停止 / 验收器异常放弃）→ STOPPED`
 * 未达成的目标保持 ACTIVE（跨会话续跑由 UI 层「继续」按钮重放引擎）。
 */
@Serializable
data class GoalSpec(
    /** 目标陈述（做什么），≤400 字符（UI 层截断）。 */
    val statement: String,
    /** 可验证的完成条件（验收判据），≤2000 字符。 */
    val acceptanceCriteria: String,
    /** 最大验收轮次（每轮 = 一次自然收尾 + 一次快速模型验收）。 */
    val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    /** 创建时间（epoch ms）。 */
    val createdAt: Long = 0L,
    /** 目标创建时的首个用户提示（重放/续跑用），可为空（目标即提示）。 */
    val initialPrompt: String = "",
) {
    companion object {
        const val DEFAULT_MAX_ROUNDS = 8

        /** 单轮工作汇报送验前截断（防长回复打爆快速模型上下文）。 */
        const val WORK_REPORT_BUDGET = 6000
    }
}

/** 目标生命周期状态。 */
enum class GoalStatus {
    ACTIVE,
    ACHIEVED,
    STOPPED,
}

/**
 * 单轮验收报告：快速模型对「当前工作 vs 完成条件」的判定。
 *
 * @param achieved 是否达成。
 * @param reason 判定理由（达成 = 满足了什么；未达成 = 差什么），≤500 字符。
 * @param evidence 判定依据引用（工具输出/文件路径等），≤500 字符，可空。
 */
data class GoalCheckResult(
    val achieved: Boolean,
    val reason: String,
    val evidence: String? = null,
) {
    companion object {
        /** 验收器不可用（快速模型连续失败）时的兜底判定：不拦进度，放行收尾。 */
        fun unknown(reason: String): GoalCheckResult =
            GoalCheckResult(achieved = true, reason = "验收器不可用，按达成放行：$reason")
    }
}

/**
 * 引擎每轮收到的验收决策（[GoalModeCoordinator.onAgentTurn] 的返回）。
 */
sealed interface GoalDecision {
    /** 未达标：注入差距说明继续跑（引擎 addMessage(System) + continue）。 */
    data class Continue(val continuationNote: String, val userNotice: String) : GoalDecision

    /**
     * 停止验收循环：达成 / 轮次耗尽 / 验收器放弃 / 目标被外部停止。
     * 引擎照常收尾（ResponseComplete），[userNotice] 以 ThinkingChunk 透出。
     */
    data class Stop(val userNotice: String, val achieved: Boolean) : GoalDecision
}

/** 目标运行时快照（UI 状态展示用，含轮次进度与最近一次验收结果）。 */
data class GoalRuntimeState(
    val spec: GoalSpec,
    val status: GoalStatus,
    val roundsDone: Int = 0,
    val lastCheck: GoalCheckResult? = null,
    val lastCheckAt: Long = 0L,
)
