package com.apex.agent.di

import com.apex.agent.core.engine.BypassOutcome
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.platform.csmem.bypass.BypassExecutionEngine
import com.apex.agent.platform.csmem.bypass.BypassResult
import com.apex.agent.platform.csmem.prune.UiTreePruner
import com.apex.agent.platform.csmem.session.CsMemSessionManager
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.accessibility.ApexAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 将 CS-Mem 的 [CsMemSessionManager] 适配为 agent-engine 的 [ExecutionMemoryObserver]。
 *
 * 这样就绪了报告 P2 的"隐式记忆采集"闭环：Agent 任务开始/每步动作/结束时，
 * 自动把 UI 轨迹写入 CS-Mem，无需 LLM 主动调用记忆工具。
 *
 * 同时实现 [tryBypass]，在每轮 LLM 推理前尝试"肌肉记忆"旁路执行（报告 P3/P4 闭环）：
 * 若记忆中存在匹配当前 UI 的 FSM 宏技能，直接执行并跳过 LLM，节省延迟与 Token。
 *
 * 所有调用委托给 CS-Mem 组件，其自身已在无障碍未开启等情况下静默跳过，
 * 因此此处无需额外吞异常（但保留防御性 try-catch 以防万一阻断 Agent 主流程）。
 */
@Singleton
class CsMemSessionObserver @Inject constructor(
    private val sessionManager: CsMemSessionManager,
    private val bypassEngine: BypassExecutionEngine,
    private val privilegeManager: PrivilegeManager
) : ExecutionMemoryObserver {

    override suspend fun onTaskStart(goal: String, appPackage: String?) {
        runCatching { sessionManager.startSession(goal, appPackage) }
    }

    override suspend fun onActionExecuted(actionDescription: String, success: Boolean) {
        runCatching { sessionManager.afterAction(actionDescription, success = success) }
    }

    override suspend fun onTaskFinish(success: Boolean) {
        val status = if (success) "SUCCEEDED" else "FAILED"
        runCatching { sessionManager.finishSession(status) }
    }

    override suspend fun tryBypass(): BypassOutcome = runCatching {
        val uiTree = privilegeManager.getUiTree()
        if (!uiTree.success || uiTree.nodes.isEmpty()) {
            return@runCatching BypassOutcome.NotAttempted
        }
        val fingerprints = UiTreePruner.prune(uiTree.nodes, null).map { it.fingerprint }
        val appPackage = ApexAccessibilityService.instance?.getForegroundPackage() ?: ""
        when (val result = bypassEngine.tryBypass(fingerprints, appPackage)) {
            is BypassResult.Succeeded -> BypassOutcome.Executed(result.actionCount)
            is BypassResult.Failed -> BypassOutcome.Failed(result.reason)
            is BypassResult.NotMatched -> BypassOutcome.NotMatched
        }
    }.getOrElse { BypassOutcome.NotAttempted }
}
