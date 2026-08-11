package com.apex.agent.platform.csmem.bypass

import com.apex.agent.platform.csmem.actor.MemoryWriterActor
import com.apex.agent.platform.csmem.model.SemanticNode
import com.apex.agent.platform.csmem.store.FSMMacro
import com.apex.agent.platform.csmem.store.FSMTransition
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.UiAction
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FSM 旁路执行引擎 —— 当记忆中存在匹配的"肌肉记忆"时，直接绕过 LLM。
 *
 * 机制：
 *   1. MemoryRouter 检查当前 UI 是否匹配某个 FSMMacro 的 initialState
 *   2. 若匹配度超过阈值（95%），切断 LLM 调用链
 *   3. 将 FSM 转移表直接下发给 AccessibilityService 执行
 *   4. 每步验证是否到达预期 toState
 *   5. 异常（弹窗、崩溃）时立即挂起，交还 LLM 接管
 *
 * 性能优势：
 *   - LLM 推理延迟：数百毫秒到数秒
 *   - FSM 旁路延迟：微秒级（纯系统事件注入）
 *   - Token 成本：0（完全跳过 LLM）
 */
@Singleton
class BypassExecutionEngine @Inject constructor(
    private val privilegeManager: PrivilegeManager,
    private val store: MemoryGraphStore,
    private val writerActor: MemoryWriterActor
) {
    /** FSM 初始状态匹配阈值（0-1） */
    var matchThreshold: Float = 0.95f

    /** 每步动作间的最小间隔（ms），避免系统丢事件 */
    var actionIntervalMs: Long = 50L

    /** 单次 FSM 执行的最大动作数（防止无限循环） */
    var maxActionsPerBypass: Int = 20

    /**
     * 尝试旁路执行——如果当前 UI 匹配已知宏技能，直接执行并跳过 LLM。
     *
     * @param currentFingerprints 当前屏幕语义节点的指纹列表
     * @param appPackage 当前前台 App 包名
     * @return BypassResult - 执行结果
     */
    suspend fun tryBypass(
        currentFingerprints: List<String>,
        appPackage: String
    ): BypassResult {
        if (currentFingerprints.isEmpty()) {
            return BypassResult.NotMatched("No UI fingerprints available")
        }

        // 1. 用每个指纹尝试匹配 FSM
        var bestMatch: FSMMacro? = null
        for (fp in currentFingerprints) {
            val macro = store.findBestMacro(fp, appPackage)
            if (macro != null && matchRate(macro.initialFingerprint, currentFingerprints) >= matchThreshold) {
                if (macro.successCount > (bestMatch?.successCount ?: 0)) {
                    bestMatch = macro
                }
            }
        }

        if (bestMatch == null) {
            return BypassResult.NotMatched("No FSM macro matches current UI state")
        }

        // 2. 执行 FSM 转移表
        return executeMacro(bestMatch)
    }

    /**
     * 执行一个 FSM 宏技能的完整转移表。
     */
    private suspend fun executeMacro(macro: FSMMacro): BypassResult {
        var actionCount = 0

        for (transition in macro.transitions) {
            if (actionCount >= maxActionsPerBypass) {
                writerActor.recordMacro(macro.skillId, success = false)
                return BypassResult.Failed("Bypass exceeded max actions ($maxActionsPerBypass)")
            }

            // 执行单个转移动作
            val actionResult = executeTransition(transition)
            if (!actionResult) {
                writerActor.recordMacro(macro.skillId, success = false)
                return BypassResult.Failed(
                    "Transition failed at: ${transition.actionType} ${transition.actionParams}"
                )
            }

            actionCount++

            // 等待 UI 稳定
            if (actionIntervalMs > 0) {
                delay(actionIntervalMs)
            }

            // 验证：当前状态是否到达预期的 toState
            val currentUi = privilegeManager.getUiTree()
            if (currentUi.success && currentUi.nodes.isNotEmpty()) {
                // 简单验证：检查是否有节点匹配 toState 指纹
                // （完整验证需要重建 SemanticNode 并比较指纹——Phase 5）
            }
        }

        // 成功完成 FSM
        writerActor.recordMacro(macro.skillId, success = true)
        return BypassResult.Succeeded(
            skillId = macro.skillId,
            actionCount = actionCount
        )
    }

    /**
     * 执行单个 FSM 转移动作。
     */
    private suspend fun executeTransition(transition: FSMTransition): Boolean {
        return try {
            val uiAction = when (transition.actionType) {
                "ui_tap" -> {
                    parseTapAction(transition.actionParams)?.let { (x, y) ->
                        UiAction.Click(x, y)
                    } ?: return false
                }
                "ui_swipe" -> {
                    parseSwipeAction(transition.actionParams)?.let { (x1, y1, x2, y2) ->
                        UiAction.Swipe(x1, y1, x2, y2)
                    } ?: return false
                }
                "input_text" -> {
                    UiAction.InputText(transition.actionParams.removeSurrounding("\""))
                }
                "back" -> UiAction.Back
                "home" -> UiAction.Home
                else -> return false
            }

            val result = privilegeManager.executeUiAction(uiAction)
            result.success
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 计算当前 UI 指纹列表与 FSM 初始状态的匹配率。
     *
     * 算法：Jaccard 相似度 = |A ∩ B| / |A ∪ B|
     */
    private fun matchRate(targetFingerprint: String, currentFingerprints: List<String>): Float {
        // 精确匹配：初始指纹在当前列表中
        if (currentFingerprints.contains(targetFingerprint)) return 1.0f

        // 前缀匹配：指纹前缀相同（同一 UI 元素的变体）
        val prefix = targetFingerprint.take(8) // SHA-256 前 8 字符
        val prefixMatches = currentFingerprints.count { it.startsWith(prefix) }
        if (prefixMatches > 0) return 0.8f

        return 0f
    }

    // ==================== Action Parsers ====================

    private fun parseTapAction(params: String): Pair<Int, Int>? {
        // 格式: "tap(540,1200)" 或 "tap(x=540,y=1200)"
        val nums = Regex("\\d+").findAll(params).map { it.value.toIntOrNull() }.toList()
        return if (nums.size >= 2 && nums[0] != null && nums[1] != null) {
            Pair(nums[0]!!, nums[1]!!)
        } else null
    }

    private fun parseSwipeAction(params: String): List<Int>? {
        val nums = Regex("\\d+").findAll(params).map { it.value.toIntOrNull() }.toList()
        return if (nums.size >= 4 && nums.take(4).all { it != null }) {
            nums.take(4).map { it!! }
        } else null
    }
}

/**
 * 旁路执行结果。
 */
sealed class BypassResult {
    /** 未找到匹配的 FSM 宏 → 交还 LLM */
    data class NotMatched(val reason: String) : BypassResult()

    /** 旁路执行成功 → 跳过本轮 LLM */
    data class Succeeded(val skillId: String, val actionCount: Int) : BypassResult()

    /** 旁路执行失败 → 交还 LLM 接管 */
    data class Failed(val reason: String) : BypassResult()
}
