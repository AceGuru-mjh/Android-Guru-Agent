package com.apex.agent.platform.csmem.distill

import com.apex.agent.platform.csmem.model.*
import com.apex.agent.platform.csmem.store.FSMMacro
import com.apex.agent.platform.csmem.store.FSMTransition

/**
 * 轨迹蒸馏器 —— 将冗长的 ReAct Trace 压缩为确定性有限状态机 (DFA)。
 *
 * 三个步骤：
 * 1. 状态锚点提取：从 Trace 中找出导致 UI 发生不可逆重大变化的节点
 * 2. 动作链压缩：剔除 LLM 犹豫、多余滑动、无效点击
 * 3. FSM 编译：生成 DFA 转移表
 *
 * 理论依据：
 *   类似人类将"有意识的反复尝试"（短期工作记忆）转化为"肌肉记忆"（长期程序性记忆）。
 */
object TraceDistiller {

    /** UI 变化度阈值：指纹重合率低于此值时视为"重大状态变化" */
    private const val MAJOR_CHANGE_THRESHOLD = 0.7f

    /** 最小动作步数 —— 小于此步数的任务不值得蒸馏 */
    private const val MIN_STEPS_FOR_DISTILL = 2

    /**
     * 原始 Trace 中的单步记录。
     */
    data class TraceStep(
        val stepIndex: Int,
        val actionType: String,       // 工具名称: "ui_tap", "ui_swipe", "input_text" 等
        val actionDescription: String, // 动作参数: "tap(540,1200)"
        val actionResult: String,      // 执行结果
        val beforeFingerprints: List<String>?, // 动作前的 UI 指纹列表
        val afterFingerprints: List<String>?,  // 动作后的 UI 指纹列表
        val isLlmThinking: Boolean = false    // 是否纯 LLM 思考步骤（无实际 UI 操作）
    )

    /**
     * 从 ReAct Trace 蒸馏出 FSM 宏技能。
     *
     * @param trace 完整的 ReAct 轨迹
     * @param goal 任务目标
     * @param appPackage App 包名
     * @return 编译好的 FSM 宏技能，若 Trace 太短或不适合蒸馏则返回 null
     */
    fun distill(
        trace: List<TraceStep>,
        goal: String,
        appPackage: String?
    ): FSMMacro? {
        // 过滤：纯 LLM 思考步骤 + 失败动作
        val effectiveSteps = trace.filter { step ->
            !step.isLlmThinking && !step.actionResult.startsWith("Error:")
        }

        if (effectiveSteps.size < MIN_STEPS_FOR_DISTILL) return null

        // 阶段1：提取状态锚点
        val anchors = extractAnchors(effectiveSteps)

        if (anchors.isEmpty()) return null

        // 阶段2：压缩动作链（剔除无效重复）
        val compressed = compressActions(effectiveSteps)

        // 阶段3：编译 FSM
        val transitions = compileFSM(compressed, anchors)

        // 确定初始和终止指纹
        val initialFp = anchors.firstOrNull()?.beforeFingerprints?.firstOrNull() ?: return null
        val terminalFp = anchors.lastOrNull()?.afterFingerprints?.firstOrNull() ?: return null

        val skillId = "skill_${goal.hashCode()}_${System.currentTimeMillis()}"

        return FSMMacro(
            skillId = skillId,
            name = distillName(goal),
            description = "Auto-distilled from ${effectiveSteps.size} effective steps for: $goal",
            initialFingerprint = initialFp,
            terminalFingerprint = terminalFp,
            transitions = transitions,
            appPackage = appPackage
        )
    }

    // ==================== Private ====================

    /**
     * 提取导致 UI 发生不可逆重大变化的状态锚点。
     *
     * 算法：比较 step.beforeFingerprints 和 step.afterFingerprints，
     *       若重合率低于阈值，则标记为锚点。
     */
    private fun extractAnchors(steps: List<TraceStep>): List<TraceStep> {
        return steps.filter { step ->
            val before = step.beforeFingerprints ?: return@filter false
            val after = step.afterFingerprints ?: return@filter false
            if (before.isEmpty() || after.isEmpty()) return@filter false

            val overlap = before.intersect(after.toSet()).size
            val ratio = overlap.toFloat() / before.size.toFloat()

            // 重合率低 → UI 发生了重大变化 → 这是一个状态锚点
            ratio < MAJOR_CHANGE_THRESHOLD
        }
    }

    /**
     * 压缩动作链：剔除同一坐标的连续重复点击、无效滑动。
     */
    private fun compressActions(steps: List<TraceStep>): List<TraceStep> {
        if (steps.size <= 1) return steps

        val result = mutableListOf(steps.first())

        for (i in 1 until steps.size) {
            val prev = result.last()
            val curr = steps[i]

            // 剔除：同一动作连续重复 2 次以上
            if (prev.actionDescription == curr.actionDescription) {
                continue
            }

            // 剔除：无效滑动（滑动后 UI 完全不变）
            if (curr.actionType == "ui_swipe" &&
                curr.beforeFingerprints != null && curr.afterFingerprints != null &&
                curr.beforeFingerprints == curr.afterFingerprints) {
                continue
            }

            result.add(curr)
        }

        return result
    }

    /**
     * 编译 FSM 转移表。
     *
     * 每个锚点之间的动作序列被压缩为单步转移：
     * fromState = 前一个锚点的 afterFingerprint[0]
     * action = 最关键的中间动作
     * toState = 下一个锚点的 afterFingerprint[0]
     */
    private fun compileFSM(
        effectiveSteps: List<TraceStep>,
        anchors: List<TraceStep>
    ): List<FSMTransition> {
        if (anchors.size < 2) return emptyList()

        val transitions = mutableListOf<FSMTransition>()

        for (i in 0 until anchors.size - 1) {
            val fromAnchor = anchors[i]
            val toAnchor = anchors[i + 1]

            val fromState = fromAnchor.afterFingerprints?.firstOrNull() ?: continue
            val toState = toAnchor.afterFingerprints?.firstOrNull() ?: continue

            // 查找两个锚点之间最关键的中间动作
            val fromIdx = effectiveSteps.indexOf(fromAnchor)
            val toIdx = effectiveSteps.indexOf(toAnchor)
            val middleSteps = if (fromIdx >= 0 && toIdx > fromIdx) {
                effectiveSteps.subList(fromIdx + 1, toIdx)
            } else {
                listOf(toAnchor)
            }

            // 选择最有代表性的动作（优先点击 > 输入 > 滑动）
            val bestAction = middleSteps.maxByOrNull { step ->
                when {
                    step.actionType == "ui_tap" -> 3
                    step.actionType == "input_text" -> 2
                    step.actionType == "ui_swipe" -> 1
                    else -> 0
                }
            } ?: continue

            transitions.add(FSMTransition(
                fromState = fromState,
                actionType = bestAction.actionType,
                // 修复：旧实现直接存完整原始描述（如 input_text("hello")），
                // BypassExecutionEngine 回放 input_text 时会把整串字面量
                // 输入进输入框。这里在蒸馏期就提取纯参数（括号内内容），
                // 回放侧再做双保险解析。
                actionParams = extractActionParams(bestAction),
                toState = toState
            ))
        }

        return transitions
    }

    /**
     * 从动作描述中提取回放可用的纯参数。
     *
     * - input_text("hello") / input_text(hello) → hello（去包裹引号）
     * - ui_tap(540,1200) / tap(x=540,y=1200) → 保留原始形式（回放侧用
     *   正则提取坐标数字，保留原始可读性）
     * - 其他（back/home 等）→ 原样返回
     *
     * internal 可见性：供同模块单元测试（TraceDistillerParamExtractionTest）
     * 直接验证新旧两种参数形态的提取语义。
     */
    internal fun extractActionParams(step: TraceStep): String {
        if (step.actionType != "input_text") return step.actionDescription

        val desc = step.actionDescription.trim()
        // 提取首个平衡括号对内的内容
        val open = desc.indexOf('(')
        val close = desc.lastIndexOf(')')
        if (open < 0 || close <= open) {
            // 无括号形态：视为已是纯参数，仅去除包裹引号——与回放侧
            // BypassExecutionEngine.extractInputText 的无括号分支对称，
            // 否则蒸馏产物带着引号入库、回放侧行为两歧。
            return desc.removeSurrounding("\"").removeSurrounding("'")
        }
        val inner = desc.substring(open + 1, close).trim()
        // 去除包裹引号（单/双均可）
        return inner.removeSurrounding("\"").removeSurrounding("'")
    }

    /**
     * 从目标任务中提炼简短的技能名称。
     */
    private fun distillName(goal: String): String {
        // 简单规则：取前 5 个中文/英文词，去除常见动词
        val keywords = goal.split(Regex("[\\s，。！？,.!?]+"))
            .filter { it.length >= 2 }
            .take(4)

        return if (keywords.isNotEmpty()) {
            keywords.joinToString(" ")
        } else {
            goal.take(30)
        }
    }
}
