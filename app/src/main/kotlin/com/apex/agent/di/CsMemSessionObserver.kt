package com.apex.agent.di

import com.apex.agent.core.engine.BypassOutcome
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.mcp.builtin.memory.ChatMemoryPipeline
import com.apex.agent.platform.csmem.bypass.BypassExecutionEngine
import com.apex.agent.platform.csmem.bypass.BypassResult
import com.apex.agent.platform.csmem.prune.UiTreePruner
import com.apex.agent.platform.csmem.session.CsMemSessionManager
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.accessibility.ApexAccessibilityService
import com.apex.agent.ui.screen.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 将 CS-Mem 的 [CsMemSessionManager] 适配为 agent-engine 的 [ExecutionMemoryObserver]。
 *
 * 这样就绪了报告 P2 的"隐式记忆采集"闭环：Agent 任务开始/每步动作/结束时，
 * 自动把 UI 轨迹写入 CS-Mem，无需 LLM 主动调用记忆工具。
 *
 * 同时实现 [tryBypass]，在每轮 LLM 推理前尝试"肌肉记忆"旁路执行（报告 P3/P4 闭环）：
 * 若记忆中存在匹配当前 UI 的 FSM 宏技能且验证通过，直接执行并跳过 LLM，节省延迟与 Token。
 *
 * ── 聊天自动记忆（对话内容沉淀）────────────────────────────────
 *
 * [recallChatMemory] / [onConversationTurn] 委托给 [ChatMemoryPipeline]：
 * UI 轨迹记忆（cs-mem）与对话内容记忆（知识图谱）在本观察者合流——引擎
 * 仍然只认识一个 [ExecutionMemoryObserver]，记忆面扩展不改引擎接线。
 * pipeline 内部全部防御式（失败返回 null / 只记日志），此处无需再包一层。
 *
 * 所有调用委托给 CS-Mem 组件，其自身已在无障碍未开启等情况下静默跳过，
 * 因此此处无需额外吞异常（但保留防御性 try-catch 以防万一阻断 Agent 主流程）。
 *
 * ── #218 记忆摄取开关 ──
 *
 * [chatMemoryCapture]（设置页可关）门控聊天记忆的双向链路：关闭时
 * [recallChatMemory] 返回 null（不再注入提示词）、[onConversationTurn]
 * 直接返回（不再沉淀新记忆）——敏感对话不落盘由用户自主控制。设置读取
 * 从 StateFlow 快照拿值（无挂起，门控零开销）。
 */
@Singleton
class CsMemSessionObserver @Inject constructor(
    private val sessionManager: CsMemSessionManager,
    private val bypassEngine: BypassExecutionEngine,
    private val privilegeManager: PrivilegeManager,
    private val chatMemoryPipeline: ChatMemoryPipeline,
    private val settingsRepository: SettingsRepository
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

    /** 聊天长期记忆召回：pipeline 内部已防御式（失败 → null），不阻断主对话。
     * #218：设置里关掉记忆摄取时同步静默召回（已有记忆不再注入）。 */
    override suspend fun recallChatMemory(userText: String): String? {
        if (!settingsRepository.agentSettings.value.chatMemoryCapture) return null
        return chatMemoryPipeline.recall(userText)
    }

    /** 对话内容自动沉淀：启发式同步捕获 + 节拍 LLM 蒸馏（pipeline 内部转后台）。
     * #218：设置里关掉记忆摄取时直接返回（新对话不再落盘）。 */
    override suspend fun onConversationTurn(userText: String, assistantText: String) {
        if (!settingsRepository.agentSettings.value.chatMemoryCapture) return
        chatMemoryPipeline.onTurn(userText, assistantText)
    }
}
