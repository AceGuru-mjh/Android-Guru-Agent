package com.apex.agent.core.code.standard

import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.PlanStep
import com.apex.agent.core.engine.RiskLevel

/**
 * # Standard Plan Parser — PLAN 档规划文本解析器
 *
 * 规划师画像的最终回复按固定四段结构产出（Goal / Steps / Verification /
 * Risks——见 [StandardPrompts.planAgent] 的模板契约）。本解析器把
 * `### Steps` 段的编号 / 复选 / 无序列表行解析为 [ExecutionPlan]：
 *
 * - 支持 `1.` / `2.` 编号、`- [ ]` / `- [x]` 复选、`-` / `*` 无序四种行形态；
 * - Steps 段缺失或无有效步骤 → 返回 null（降级为普通回复收尾）；
 * - Goal 取 `### Goal` 标题后的首个非空行，缺失时回退会话标题。
 */
internal object StandardPlanParser {

    /** 解析规划文本；无 Steps 段或步骤为空返回 null。 */
    fun parse(text: String, fallbackTitle: String?): ExecutionPlan? {
        val lines = text.lines()
        val goal = lines
            .firstOrNull { it.trim().equals("### Goal", ignoreCase = true) }
            ?.let { header ->
                lines.dropWhile { it.trim() != header.trim() }
                    .drop(1)
                    .firstOrNull { it.isNotBlank() }
            }
        val stepsStart = lines.indexOfFirst { it.trim().equals("### Steps", ignoreCase = true) }
        if (stepsStart < 0) return null
        val steps = mutableListOf<PlanStep>()
        var index = 0
        for (i in stepsStart + 1 until lines.size) {
            val raw = lines[i].trim()
            if (raw.startsWith("###") || raw.startsWith("## ")) break
            if (raw.isEmpty()) continue
            val cleaned = raw
                .removePrefix("- [ ]").removePrefix("- [x]")
                .removePrefix("-").removePrefix("*")
                .replace(Regex("^\\d+\\.\\s*"), "")
                .trim()
            if (cleaned.isEmpty()) continue
            steps.add(
                PlanStep(
                    index = index,
                    description = cleaned,
                    toolName = null,
                    estimatedArgs = null
                )
            )
            index++
        }
        if (steps.isEmpty()) return null
        return ExecutionPlan(
            goal = goal ?: fallbackTitle ?: "用户任务",
            steps = steps,
            estimatedToolCalls = steps.size,
            riskLevel = RiskLevel.MEDIUM,
            reasoning = text.take(300)
        )
    }
}
