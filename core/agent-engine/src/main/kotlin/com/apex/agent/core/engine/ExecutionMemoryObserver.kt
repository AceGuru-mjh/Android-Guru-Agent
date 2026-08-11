package com.apex.agent.core.engine

/**
 * 执行记忆观察者 —— 解耦 agent-engine（纯 JVM）与 platform:cs-mem（Android）。
 *
 * CS-Mem 的记忆采集应当是"隐式"的：Agent 执行任务时自动记录 UI 轨迹，
 * 无需 LLM 主动调用记忆工具。为此 agent-engine 在任务生命周期的关键节点
 * 回调本接口，由 app 层用 [com.apex.agent.platform.csmem.session.CsMemSessionManager]
 * 实现并注入。
 *
 * 设计要点：
 * - 所有方法均为 suspend 且有默认空实现，便于 engine 无条件调用（观察者未注入时静默跳过）。
 * - 观察者实现内部必须自行处理异常（如无障碍未开启），绝不能阻断 Agent 主流程。
 * - 这是"隐式记忆"闭环（报告 P2）的核心接口；Bypass/Recall 工具（P3）是另一路径。
 */
interface ExecutionMemoryObserver {

    /**
     * Agent 任务开始时调用。对应 CsMemSessionManager.startSession()。
     *
     * @param goal 任务目标文本
     * @param appPackage 当前前台 App 包名（可能为空）
     */
    suspend fun onTaskStart(goal: String, appPackage: String?) {
        // 默认空实现
    }

    /**
     * Agent 每执行完一个工具/动作后调用。对应 CsMemSessionManager.afterAction()。
     *
     * @param actionDescription 刚执行的动作描述（如 "ui_tap(540,1200)"）
     */
    suspend fun onActionExecuted(actionDescription: String) {
        // 默认空实现
    }

    /**
     * Agent 任务结束时调用。对应 CsMemSessionManager.finishSession()。
     *
     * @param success 任务是否成功完成
     */
    suspend fun onTaskFinish(success: Boolean) {
        // 默认空实现
    }
}
