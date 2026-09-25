package com.apex.agent.core.engine.plan

/**
 * #169 Plan 模式强化 —— 计划确认决策的承载。
 *
 * 旧确认通道只有 `Boolean`（执行/取消）；Plan 模式人控升级后，用户在确认卡
 * 上还能**勾选启用步骤**与**重排执行顺序**，UI 经
 * `ApexAgentEngine.submitPlanConfirmation(confirmed, enabledSteps, order)` 把
 * 三元组传入引擎，唤醒挂起在 `awaitPlanConfirmationDecision()` 上的
 * [com.apex.agent.core.engine.ApexAgentEngine.executePlanMode]。
 *
 * 字段语义（均指**原始计划的 step.index**，Phase 3.5 重编号前的编号）：
 *  - [confirmed]：false = 取消（引擎直接 Aborted，忽略其余字段）；
 *  - [enabledSteps]：null = 全量启用（旧两参签名的兼容路径）；
 *  - [order]：null = 声明顺序；非空时为用户期望的完整顺序清单，
 *    [PlanGraph.applyAdjustments] 负责容错（未覆盖追加、未知忽略）。
 */
data class PlanDecision(
    val confirmed: Boolean,
    val enabledSteps: List<Int>? = null,
    val order: List<Int>? = null
) {
    companion object {
        /** 旧两参确认语义的等价表达：全量步骤 + 声明顺序。 */
        fun legacy(confirmed: Boolean): PlanDecision = PlanDecision(confirmed, null, null)
    }
}
