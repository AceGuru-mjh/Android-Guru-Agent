package com.apex.agent.core.engine.plan

import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.PlanStep

/**
 * #169 Plan 模式强化 —— 依赖图拓扑排序与用户调整应用。
 *
 * 旧执行器对 [PlanStep.dependsOn] 纯顺序忽略；本对象把"声明顺序"升级为
 * "依赖驱动顺序"：
 *
 *  - [topoSort]：Kahn 拓扑排序 + 环检测。同层多候选时按声明顺序取首个
 *    （确定性、对无依赖计划零重排）；
 *  - [applyAdjustments]：用户确认时的步骤筛选（勾选）与重排（上移/下移），
 *    输出步骤重新编号 index = 0..n-1，dependsOn 同步重映射；
 *  - [lock]：引擎 Phase 3.5 的单一入口 —— 应用调整 → 拓扑排序 → 最终
 *    重编号 → 生成锁定播报消息。
 *
 * 纯 Kotlin、无状态、无 Android 依赖（与 EngineToolPlanner 同一拆分模式），
 * 单测覆盖链式/菱形/独立步骤、环回退、筛选+重排+重编号与空集防御。
 */
object PlanGraph {

    /** [topoSort] 结果：有序下标（入参 steps 的位置）+ 环警告（null = 无环）。 */
    data class TopoResult(
        val orderedIndices: List<Int>,
        val cycleWarning: String?
    )

    /** [applyAdjustments] 结果：调整后的步骤（已重编号）+ 警告清单。 */
    data class AdjustedPlan(
        val steps: List<PlanStep>,
        val warnings: List<String>
    )

    /** [lock] 产物：锁定计划 + 警告 + 锁定播报消息（引擎直接 addMessage 持久化）。 */
    data class LockedPlan(
        val plan: ExecutionPlan,
        val warnings: List<String>,
        val lockMessage: String
    )

    /**
     * 拓扑排序 + 环检测。
     *
     * dependsOn 引用的是步骤的 [PlanStep.index] 字段值（解析器保证原计划
     * index = 声明位置；[applyAdjustments] 重编号后同样成立），此处先建
     * index → 位置 的映射再解析依赖，容忍 index 与位置错位的输入。
     *
     * 环（含自依赖）时不抛异常：回退为声明顺序并返回环警告，调用方以
     * "能执行"优先于"顺序完美"。
     */
    fun topoSort(steps: List<PlanStep>): TopoResult {
        if (steps.size <= 1) return TopoResult(steps.indices.toList(), null)

        // index 字段值 → 位置（重复 index 时首个生效；解析器保证唯一）。
        val positionOfIndex = HashMap<Int, Int>(steps.size)
        steps.forEachIndexed { pos, s -> positionOfIndex.putIfAbsent(s.index, pos) }

        // 依赖位置集合（过滤自身 / 未知 index —— 防御畸形输入，不构成环）。
        val deps = steps.mapIndexed { pos, s ->
            s.dependsOn.mapNotNull { depIdx ->
                val depPos = positionOfIndex[depIdx]
                when {
                    depPos == null -> null            // 未知依赖：忽略（无法定位）
                    depPos == pos -> null             // 自依赖：忽略（否则必然成环）
                    else -> depPos
                }
            }
        }
        val remainingDeps = deps.map { it.toMutableSet() }
        val satisfied = BooleanArray(steps.size) { false }
        val ordered = mutableListOf<Int>()

        // 反复扫描：每轮取「依赖已全部满足」的最靠前步骤 —— 同层候选按声明
        // 顺序消解，无依赖计划得到恒等排列（对用户重排的扰动最小）。
        while (ordered.size < steps.size) {
            val pick = (0 until steps.size).firstOrNull { pos ->
                !satisfied[pos] && remainingDeps[pos].all { satisfied[it] }
            }
            if (pick == null) {
                // 本轮无可调度步骤 → 剩余节点构成环：整体回退声明顺序 + 警告
                // （丢弃部分进度序 —— 计划含环即整份可疑，声明序最可预期）。
                val cycleSteps = (0 until steps.size).filterNot { satisfied[it] }
                return TopoResult(
                    orderedIndices = steps.indices.toList(),
                    cycleWarning = "依赖图中存在环（涉及步骤 " +
                        cycleSteps.joinToString(", ") { "${steps[it].index + 1}" } +
                        "），已回退为声明顺序执行"
                )
            }
            satisfied[pick] = true
            ordered.add(pick)
            remainingDeps.forEach { it.remove(pick) }
        }
        return TopoResult(ordered, null)
    }

    /**
     * 应用用户勾选与重排。
     *
     * @param plan 原始计划（PlanGenerated 事件携带、UI 展示的那份）
     * @param enabled 启用步骤的**原 index** 集合；null 或空集 = 防御性全量
     *   启用（空集视为误传而非"零步执行"，附警告）
     * @param order 用户期望的**原 index** 顺序清单；null = 声明顺序。清单
     *   未覆盖的启用步骤按声明顺序追加在末尾；含未知 index 的条目忽略并附警告
     *
     * 输出：步骤按最终顺序重编号（index = 位置），dependsOn 重映射到新编号；
     * 被禁用步骤的依赖直接移除（附警告）——后续步骤不再等待不存在的产物。
     */
    fun applyAdjustments(
        plan: ExecutionPlan,
        enabled: Set<Int>?,
        order: List<Int>?
    ): AdjustedPlan {
        val warnings = mutableListOf<String>()
        if (plan.steps.isEmpty()) return AdjustedPlan(emptyList(), warnings)

        // ── 筛选 ──
        val allIndices = plan.steps.map { it.index }.toSet()
        val enabledSet = when {
            enabled == null -> allIndices
            enabled.isEmpty() -> {
                warnings += "启用步骤集为空，已回退为全部 ${plan.steps.size} 步"
                allIndices
            }
            else -> enabled
        }
        val unknownEnabled = enabledSet - allIndices
        if (unknownEnabled.isNotEmpty()) {
            warnings += "忽略未知的启用步骤 index：${unknownEnabled.sorted().joinToString(", ")}"
        }
        val keptIndices = plan.steps.map { it.index }.filter { it in enabledSet }.toSet()
        if (keptIndices.isEmpty()) return AdjustedPlan(emptyList(), warnings)

        // ── 重排 ──
        val orderedIndices = mutableListOf<Int>()
        val declaredOrder = plan.steps.map { it.index }.filter { it in keptIndices }
        if (order != null) {
            val seen = mutableSetOf<Int>()
            for (idx in order) {
                if (idx in keptIndices && seen.add(idx)) orderedIndices.add(idx)
            }
            // order 未覆盖的启用步骤：按声明顺序补齐（用户只动了前几步的常见路径）。
            val missing = keptIndices - seen
            if (missing.isNotEmpty()) {
                orderedIndices.addAll(declaredOrder.filter { it in missing })
                if (seen.isNotEmpty()) {
                    warnings += "顺序清单未覆盖 ${missing.size} 个启用步骤，已按声明顺序追加在末尾"
                }
            }
            val ignoredOrderEntries = order.filter { it !in keptIndices }.distinct()
            if (ignoredOrderEntries.isNotEmpty()) {
                warnings += "顺序清单忽略未启用/未知步骤 index：${ignoredOrderEntries.joinToString(", ")}"
            }
        } else {
            orderedIndices.addAll(declaredOrder)
        }

        // ── 重编号 + dependsOn 重映射 ──
        val newIndexOfOriginal = HashMap<Int, Int>(orderedIndices.size)
        orderedIndices.forEachIndexed { newPos, originalIdx ->
            newIndexOfOriginal[originalIdx] = newPos
        }
        val steps = orderedIndices.mapIndexed { newPos, originalIdx ->
            val original = plan.steps.first { it.index == originalIdx }
            val (mappedDeps, droppedDeps) = original.dependsOn.partition { it in newIndexOfOriginal }
            if (droppedDeps.isNotEmpty()) {
                warnings += "步骤 ${newPos + 1}（原 ${originalIdx + 1}）依赖的步骤 " +
                    droppedDeps.joinToString("/") { "${it + 1}" } + " 已被禁用，依赖已移除"
            }
            original.copy(index = newPos, dependsOn = mappedDeps.map { newIndexOfOriginal.getValue(it) })
        }
        return AdjustedPlan(steps, warnings)
    }

    /**
     * 引擎 Phase 3.5 单一入口：应用用户调整 → 拓扑排序 → 最终重编号（index
     * = 执行位置，StepStart / 步骤提示词与 UI 高亮同源）→ 锁定播报消息。
     *
     * 产物 [LockedPlan.plan] 为不可变快照（引擎侧局部 val 持有），执行期间
     * 任何用户操作都不再改变它 —— 这就是"计划锁定"语义。
     */
    fun lock(plan: ExecutionPlan, enabledSteps: List<Int>?, order: List<Int>?): LockedPlan {
        val adjusted = applyAdjustments(plan, enabledSteps?.toSet(), order)
        val topo = topoSort(adjusted.steps)
        val warnings = adjusted.warnings + listOfNotNull(topo.cycleWarning)

        val lockedSteps = topo.orderedIndices.mapIndexed { execPos, adjustedPos ->
            adjusted.steps[adjustedPos].copy(index = execPos)
        }
        val userAdjusted = enabledSteps != null || order != null
        val enabledCount = enabledSteps?.intersect(plan.steps.map { it.index }.toSet())?.size
            ?: plan.steps.size
        val reordered = order != null && order.filter { it in plan.steps.map { s -> s.index } } !=
            plan.steps.map { it.index }
        val lockedPlan = plan.copy(
            steps = lockedSteps,
            estimatedToolCalls = if (plan.steps.isEmpty()) plan.estimatedToolCalls
            else maxOf(1, plan.estimatedToolCalls * lockedSteps.size / plan.steps.size)
        )
        val lockMessage = buildString {
            append("📋 计划已锁定：${lockedSteps.size} 步")
            if (userAdjusted) {
                append("（用户调整：启用 $enabledCount/${plan.steps.size} 步")
                if (reordered) append("，已重排")
                append("）")
            }
            append("，执行开始。执行期间计划不可修改。")
            if (warnings.isNotEmpty()) {
                appendLine()
                append("⚠️ ")
                append(warnings.joinToString("；"))
            }
        }
        return LockedPlan(lockedPlan, warnings, lockMessage)
    }
}
