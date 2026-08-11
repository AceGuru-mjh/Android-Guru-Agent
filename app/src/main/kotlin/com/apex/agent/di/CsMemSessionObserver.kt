package com.apex.agent.di

import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.platform.csmem.session.CsMemSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 将 CS-Mem 的 [CsMemSessionManager] 适配为 agent-engine 的 [ExecutionMemoryObserver]。
 *
 * 这样就绪了报告 P2 的"隐式记忆采集"闭环：Agent 任务开始/每步动作/结束时，
 * 自动把 UI 轨迹写入 CS-Mem，无需 LLM 主动调用记忆工具。
 *
 * 所有调用委托给 CsMemSessionManager，其自身已在无障碍未开启等情况下静默跳过，
 * 因此此处无需额外吞异常（但保留防御性 try-catch 以防万一阻断 Agent 主流程）。
 */
@Singleton
class CsMemSessionObserver @Inject constructor(
    private val sessionManager: CsMemSessionManager
) : ExecutionMemoryObserver {

    override suspend fun onTaskStart(goal: String, appPackage: String?) {
        runCatching { sessionManager.startSession(goal, appPackage) }
    }

    override suspend fun onActionExecuted(actionDescription: String) {
        runCatching { sessionManager.afterAction(actionDescription) }
    }

    override suspend fun onTaskFinish(success: Boolean) {
        val status = if (success) "SUCCEEDED" else "FAILED"
        runCatching { sessionManager.finishSession(status) }
    }
}
