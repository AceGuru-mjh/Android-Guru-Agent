package com.apex.agent.ui.screen.code

import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.code.standard.StandardLogicMode
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.goal.GoalSpec
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// S1 — GOAL 目标模式控制（CodeViewModel 的 internal 扩展，God-file 预算拆分）
//
// 组织方式同 CodeLongTaskCenterOps.kt / Agent 屏 AgentChatModeController.kt：
// 依赖成员已开放 internal（goalCoordinator / dualLogicEngine /
// settingsRepository / languageManager），调用点（VM init 的 setupGoalController、
// setMode 尾部的 onEnterGoalMode、CodeScreen 的 startGoalModeGoal 等）零感知。
//
// 职责边界：目标生命周期编排（状态同步/设定/停止/重启/首条拦截/引擎线保障）
// 全部在本文件；每轮验收逻辑在 GoalModeCoordinator（核心层单例），验收钩子
// 在深潜线引擎（CodeModule 注入协调器），本文件不碰引擎配置。
//
// 生命周期口径（与 GoalModeCoordinator 状态机一致）：
// startGoal → ACTIVE →（验收通过）ACHIEVED /（轮次耗尽·用户停止）STOPPED；
// resume = 用原 spec 重新 startGoal（roundsDone 归零）+ 重放 initialPrompt 续跑。
// 目标不持久化（进程级单例）——重启 App 后 GOAL 模式恢复但目标为空，
// setMode 的弹层逻辑会引导重新设定。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 引擎验收通知的 ThinkingChunk 标记（GoalPrompts.userNotice 协议：通知恒以
 * 独立整块 chunk 发射，行首形如「[goal] 」——CodeViewModel.runEngine 据此
 * 把验收结论改道系统行，不进思考卡）。
 */
internal const val GOAL_NOTICE_MARKER: String = "\n[goal] "

/** GoalSpec 字段预算（UI 层截断，与 GoalModels KDoc 对齐）。 */
private const val STATEMENT_BUDGET = 400

private const val CRITERIA_BUDGET = 2000

/** 轮次上限（预设 chips 最大 20，留裕量防脏值）。 */
private const val MAX_ROUNDS_LIMIT = 30

/**
 * GOAL 控制接线（VM init 调用一次）：协调器全局状态流 → uiState.goalState。
 * 状态卡据此渲染——目标存在即显示（ACTIVE/ACHIEVED/STOPPED 一视同仁，
 * 切走模式也显示，用户可随时停止/重启）。
 */
internal fun CodeViewModel.setupGoalController() {
    viewModelScope.launch {
        goalCoordinator.state.collect { gs ->
            _uiState.update { it.copy(goalState = gs) }
        }
    }
}

/** 打开目标设定弹层（[initialText] = 当前输入草稿，预填目标陈述）。 */
internal fun CodeViewModel.showGoalSetup(initialText: String) {
    _uiState.update { it.copy(showGoalSetup = true, goalSetupDraft = initialText) }
}

/** 关闭目标设定弹层（输入框草稿原样保留，仅清弹层自持的预填副本）。 */
internal fun CodeViewModel.dismissGoalSetup() {
    _uiState.update { it.copy(showGoalSetup = false, goalSetupDraft = "") }
}

/**
 * 切入 GOAL 模式的模式附属动作（setMode 尾部调用，选择器 onSelect 与
 * init 恢复共用此单点）：
 * 1. 强制深潜线——GOAL 验收只接在深潜线引擎（CodeModule 注入协调器），
 *    标准线不跑验收钩子；一次性保障，不拦截后续手动切线（切走后验收
 *    静默暂停，重新进入 GOAL 模式时再次保障）；
 * 2. 无活动目标 → 弹设定 Sheet（草稿 = 当前输入框内容，作为目标陈述预填
 *    与首条提示）。目标已存在（含 ACHIEVED/STOPPED 终态）时不弹——状态卡
 *    的「重启目标」即重建入口。
 */
internal fun CodeViewModel.onEnterGoalMode() {
    ensureDeepDiveLine()
    if (goalCoordinator.activeSpec() == null) {
        showGoalSetup(_uiState.value.inputDraft)
    }
}

/**
 * GOAL 模式且尚无活动目标时拦截首条消息：转为打开目标设定弹层（草稿
 * 预填该文本），不直接发送。返回 true = 已拦截（CodeScreen 发送回调据此
 * return）。斜杠指令不拦截（走既有管线）；已有活动目标时正常发送。
 */
internal fun CodeViewModel.maybeGoalFirstSend(text: String): Boolean {
    val trimmed = text.trim()
    if (_uiState.value.mode != AgentMode.GOAL || goalCoordinator.activeSpec() != null) return false
    if (trimmed.isEmpty() || trimmed.startsWith("/")) return false
    showGoalSetup(trimmed)
    return true
}

/**
 * 开始目标（设定 Sheet「开始」入口）：构造钳制后的 GoalSpec → 协调器
 * startGoal → 模式兜底切 GOAL（activeSpec 已非空，不会重复弹设定 Sheet）
 * → 关闭弹层 → initialPrompt 非空时走正常发送管线（[sendMessage] 复用：
 * 用户气泡/草稿清理/runEngine 全套，不复制逻辑）。
 */
internal fun CodeViewModel.startGoalModeGoal(
    statement: String,
    criteria: String,
    maxRounds: Int,
    initialPrompt: String
) {
    val prompt = initialPrompt.trim()
    val spec = GoalSpec(
        statement = statement.trim().take(STATEMENT_BUDGET),
        acceptanceCriteria = criteria.trim().take(CRITERIA_BUDGET),
        maxRounds = maxRounds.coerceIn(1, MAX_ROUNDS_LIMIT),
        createdAt = System.currentTimeMillis(),
        initialPrompt = prompt
    )
    goalCoordinator.startGoal(spec)
    if (_uiState.value.mode != AgentMode.GOAL) setMode(AgentMode.GOAL) else ensureDeepDiveLine()
    _uiState.update { it.copy(showGoalSetup = false, goalSetupDraft = "") }
    appendSystemMessage(languageManager.getString(R.string.goal_started_notice, spec.statement))
    if (prompt.isNotBlank()) sendMessage(prompt)
}

/**
 * 停止当前目标（状态卡「停止目标」按钮）：STOPPED 收尾。进行中的本轮
 * 照常跑完（引擎下一钩子发现非 ACTIVE 即正常收尾，不丢已生成的回复）；
 * 引擎运行的中止仍走输入栏的停止按钮（与本按钮语义分离）。
 */
internal fun CodeViewModel.stopActiveGoal() {
    goalCoordinator.stopGoal(achieved = false)
    appendSystemMessage(languageManager.getString(R.string.goal_stopped_notice))
}

/**
 * 重启目标（状态卡「重启目标」按钮，STOPPED/ACHIEVED 均可用）：用原 spec
 * 重新 startGoal（roundsDone 归零）→ 模式/引擎线保障 → 重放 initialPrompt
 * 续跑（运行中或 initialPrompt 为空时不重放——用户手动发首条即可启动）。
 */
internal fun CodeViewModel.resumeGoal() {
    val spec = goalCoordinator.state.value?.spec ?: return
    goalCoordinator.startGoal(spec)
    if (_uiState.value.mode != AgentMode.GOAL) setMode(AgentMode.GOAL) else ensureDeepDiveLine()
    appendSystemMessage(languageManager.getString(R.string.goal_restart_notice))
    if (!_uiState.value.isRunning && spec.initialPrompt.isNotBlank()) {
        sendMessage(spec.initialPrompt)
    }
}

/** 设置页「最大验收轮次」当前值（设定 Sheet 预选 chips 用，钳制防脏值）。 */
internal fun CodeViewModel.goalDefaultMaxRounds(): Int =
    settingsRepository.agentSettings.value.goalMaxRounds.coerceIn(1, MAX_ROUNDS_LIMIT)

/**
 * 深潜线保障（切入 GOAL / 目标启动 / 重启共用）：
 * - 标准线在跑时不切——abort 会被路由到新激活线，旧 run 失控（与
 *   setLogicMode 的 isRunning 拒绝同口径）；已是深潜线时零开销直过；
 * - 持久化 + 新激活线同步当前模式/档位（与 setLogicMode 同口径，但不发
 *   系统消息——GOAL 切线是模式附属行为，状态卡已是足够反馈）。
 */
private fun CodeViewModel.ensureDeepDiveLine() {
    if (_uiState.value.isRunning) return
    if (_uiState.value.logicMode == StandardLogicMode.DEEP_DIVE) return
    val switched = dualLogicEngine?.switchLogic(StandardLogicMode.DEEP_DIVE) ?: true
    if (!switched) return
    settingsRepository.updateAgentSettings {
        copy(codeThinkingLogic = StandardLogicMode.DEEP_DIVE.persistenceName)
    }
    _uiState.update { it.copy(logicMode = StandardLogicMode.DEEP_DIVE) }
    dualLogicEngine?.updateMode(_uiState.value.mode)
    dualLogicEngine?.updateThinkingLevel(_uiState.value.thinkingLevel)
}
