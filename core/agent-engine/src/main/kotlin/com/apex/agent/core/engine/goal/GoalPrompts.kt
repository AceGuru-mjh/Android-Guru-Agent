package com.apex.agent.core.engine.goal

/**
 * # Goal 模式提示词（v3）
 *
 * 两类文本：
 * 1. [modeSection] —— 系统提示词 "## Mode: GOAL" 段（由 EnginePrompts 消费，
 *    与 CHAT/AGENT/BUILD 等模式段同款式：英文硬约束、面向主模型）；
 * 2. [verifierSystem] / [verifierUser] —— 快速模型验收提示词（要求严格
 *    JSON 输出，主模型自评不可信，验收必须独立判据化）。
 *
 * 验收 JSON 协议：`{"achieved": true|false, "reason": "...", "evidence": "..."}`
 * 解析在 [GoalVerifier] 容错完成（剥 markdown 围栏 / 首个 JSON 对象提取）。
 */
internal object GoalPrompts {

    /** 主模型模式段：持续工作纪律 + 汇报格式（方便验收器对照）。 */
    fun modeSection(): String = buildString {
        appendLine("## Mode: GOAL (verifiable objective)")
        appendLine("You are working toward a verifiable goal. The engine will re-check your work")
        appendLine("against the acceptance criteria after each round — do NOT stop early.")
        appendLine("- Keep working autonomously with tools until the criteria are objectively met.")
        appendLine("- Prefer VERIFICATION over claims: run the tests, re-read the file, curl the")
        appendLine("  endpoint — evidence beats narration.")
        appendLine("- When you believe the goal is achieved, end with a short completion report:")
        appendLine("  what was done, what was verified, and the evidence (commands run + results).")
        appendLine("- If verification fails, the engine will tell you exactly what is missing;")
        appendLine("  address that gap directly instead of re-planning from scratch.")
        appendLine("- Never fabricate verification results. If a criterion cannot be verified")
        appendLine("  (missing tooling/permissions), say so explicitly and propose an alternative.")
    }

    /** 验收器系统提示词（FAST 角色，便宜小模型可用）。 */
    fun verifierSystem(): String = """
        You are a strict acceptance verifier. Judge ONLY by the acceptance criteria and the
        work report provided. Reply with ONE JSON object and nothing else:
        {"achieved": <true|false>, "reason": "<short verdict, cite the evidence>",
         "evidence": "<the exact verification evidence quoted from the report, or empty>"}
        Rules:
        - achieved=true ONLY if the report shows concrete verification evidence (commands run,
          outputs, test results, file contents) covering every criterion.
        - Promises and plans ("I will", "should now", "the code should") are NOT evidence.
        - Ignore work unrelated to the criteria. Do not reward effort, only outcomes.
        - If the report is unrelated or empty, achieved=false with reason "no work reported".
    """.trimIndent()

    /** 验收器用户提示词：目标 + 条件 + 本轮工作汇报。 */
    fun verifierUser(spec: GoalSpec, workReport: String): String = buildString {
        appendLine("## Goal")
        appendLine(spec.statement.trim())
        appendLine()
        appendLine("## Acceptance Criteria")
        appendLine(spec.acceptanceCriteria.trim())
        appendLine()
        appendLine("## Work Report (latest round)")
        appendLine(workReport.trim().take(GoalSpec.WORK_REPORT_BUDGET))
        appendLine()
        appendLine("Judge: is EVERY acceptance criterion met with evidence? Reply with the JSON only.")
    }

    /** 未达标时的续跑注入（System 消息，主模型读）。 */
    fun continuationNote(result: GoalCheckResult, roundsLeft: Int): String = buildString {
        appendLine("[goal-verifier] 验收未通过（剩余 ${roundsLeft} 轮）。")
        appendLine("差距：${result.reason}")
        result.evidence?.takeIf { it.isNotBlank() }?.let { appendLine("验收依据：$it") }
        appendLine("请直接针对上述差距继续工作并用可验证的证据（命令/输出/文件内容）复核，")
        appendLine("完成后输出完成报告（做了什么 / 验证了什么 / 证据）。不要重新规划整个任务。")
    }

    /** 引擎 ThinkingChunk 透出给用户的中性提示（不动模型时间线，仅 UI 思考流）。 */
    fun userNotice(round: Int, maxRounds: Int, result: GoalCheckResult): String = buildString {
        appendLine()
        appendLine("[goal] 第 $round/$maxRounds 轮验收：${if (result.achieved) "✅ 达成" else "❌ 未达成"}")
        appendLine("[goal] ${result.reason}")
    }
}
