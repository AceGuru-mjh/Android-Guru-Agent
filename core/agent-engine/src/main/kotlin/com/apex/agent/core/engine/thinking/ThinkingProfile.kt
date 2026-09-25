package com.apex.agent.core.engine.thinking

import com.apex.agent.core.engine.ThinkingLevel

/**
 * 思考档位的完整执行画像（#168 六档 → v1.2 扩为 **7 深度档 + AUTO 元档**）——
 * 提示词 + 参数 + 执行策略三合一。
 *
 * 旧版 [ThinkingLevel] 只提供提示词（[ThinkingLevel.toPromptInstruction]）与
 * 模型参数（[ThinkingLevel.toThinkingBudget] / [ThinkingLevel.toReasoningEffortName]），
 * 引擎主循环完全不感知档位：迭代上限固定、无验证回路、压缩与工具输出预算
 * 一刀切。本数据类把每一档的**执行策略差异**显式建模：
 *
 *  - [maxIterationsScale]：迭代上限倍率（引擎 `maxIterations × 此值`）。
 *    深思考档允许更多"思考→行动→观察"轮次，快档提前止损；
 *  - [postToolVerification]：关键工具调用后是否追加自检提示
 *    （工具失败或 HIGH 风险工具执行后注入反思问题，见
 *    [ThinkingModeController.postToolCheckPrompt]）；
 *  - [finalSelfCheck]：最终响应前是否注入自评清单（目标达成 / 副作用 /
 *    遗漏），经 system prompt 的 Thinking 段下发（[SELF_CHECK_CHECKLIST]；
 *    APEXCODE 档独占更狠的五问清单 [APEX_SELF_CHECK_CHECKLIST]）；
 *  - [compressionAggressiveness]：压缩阈值倍率（1.0 = 默认；> 1 = 更早压缩
 *    省 token，如 NONE 档 1.2 → 有效阈值 = base / 1.2；**< 1 = 更晚压缩**，
 *    如 APEXCODE 档 0.9 → 有效阈值 = base / 0.9 ≈ 0.889 > base，巅峰档
 *    保留更多上下文供架构级穷举推理）；
 *  - [toolOutputBudget]：工具输出截断预算字符数（深档给模型更多工具输出
 *    上下文供其推理）。
 *
 * 八档画像差异总表：
 *
 * | 档位      | 推理框架      | budget | effort  | 迭代倍率 | 工具自检 | 终检清单 | 压缩倍率 | 输出预算 |
 * |-----------|---------------|--------|---------|----------|----------|----------|----------|----------|
 * | NONE      | 无推理指令    | 0      | null    | ×0.8     | ✗        | ✗        | 1.2      | 6000     |
 * | LIGHT     | 1-2 句简思    | 256    | LOW     | ×0.9     | ✗        | ✗        | 1.0      | 7000     |
 * | STANDARD  | CoT 三步      | 1024   | MEDIUM  | ×1.0     | ✗        | ✗        | 1.0      | 8000     |
 * | DEEP      | 多路径 5 步   | 4096   | HIGH    | ×1.2     | ✓        | ✗        | 1.0      | 9000     |
 * | MAXIMUM   | ToT 7 步      | 16384  | MAX     | ×1.5     | ✓        | ✓        | 1.0      | 10000    |
 * | ULTRACODE | 编码闭环 7 步 | 32768  | MAX     | ×2.0     | ✓        | ✓        | 1.0      | 12000    |
 * | APEXCODE  | 架构穷举 7 步 | 65536  | MAX     | ×3.0     | ✓        | ✓(APEX)  | 0.9      | 16000    |
 * | AUTO      | 委托选档器    | null   | null    | ×1.0*    | 跟随*    | 跟随*    | 1.0*     | 8000*    |
 *
 * ULTRACODE / APEXCODE 的 effort 同为 MAX（Provider 侧天花板）：两档与
 * MAXIMUM 的真实差异由提示词 + budget + 迭代/验证/压缩/输出策略拉开。
 * APEXCODE 终检清单为 [APEX_SELF_CHECK_CHECKLIST]（五问，由
 * [ThinkingModeController.finalSelfCheckPrompt] 按档位切换）。
 *
 * \* AUTO 档自身不携带执行策略 —— 由 [AdaptiveThinkingSelector] 按轮次动态
 * 选出具体档位后，使用**该档**的画像（[ThinkingModeController.profileFor]）。
 * 表中 AUTO 行数值仅是无决策时的占位回退值。
 *
 * @param promptInstruction 注入 system prompt "## Thinking Instructions" 段的
 *   推理框架指令（NONE = 空串 = 不注入指令；AUTO = 空串，由选档结果注入）。
 * @param thinkingBudget Gemini `thinking_budget`（null = 不直接映射）。
 * @param reasoningEffortName OpenAI/Claude `reasoning_effort` 枚举名
 *   （null = 不下发 reasoning 字段）。
 * @param decisionReason AUTO 档专属：本次动态选档的可解释理由（由
 *   [ThinkingModeController] 在解析 AUTO 时填充；非 AUTO 档恒为 null）。
 *   随 system prompt 下发给模型，同时供 UI 展示（`lastAdaptiveDecision`）。
 */
data class ThinkingProfile(
    val level: ThinkingLevel,
    val promptInstruction: String,
    val thinkingBudget: Int?,
    val reasoningEffortName: String?,
    val maxIterationsScale: Float,
    val postToolVerification: Boolean,
    val finalSelfCheck: Boolean,
    val compressionAggressiveness: Float,
    val toolOutputBudget: Int,
    val uiDescriptionZh: String,
    val uiDescriptionEn: String,
    val decisionReason: String? = null
) {

    companion object {

        /**
         * MAXIMUM 档最终响应前的自评清单（[finalSelfCheck] = true 时注入）。
         *
         * 作为 system prompt Thinking 段的一部分下发 —— 模型在产出最终回复
         * **之前**就已看到清单，从而真实影响本轮输出（而非事后追评）。
         * 三问对应 #168 的验收口径：目标达成 / 副作用 / 遗漏。
         */
        const val SELF_CHECK_CHECKLIST: String = """Before sending your final response, silently run this self-check:
1. Goal: is the user's actual goal fully achieved — not just partially or superficially?
2. Side effects: were there any unintended changes, leftover state, or destructive operations not explicitly requested?
3. Omissions: is anything you promised or planned earlier still missing? Is any success claim unverified by a tool?
If any check fails, fix the gap with tools first; only then deliver the final answer."""

        /**
         * APEXCODE 档专属终检清单（v1.2）：比 [SELF_CHECK_CHECKLIST] 更狠的
         * **五问**对抗性自审 —— 在三问（目标/副作用/遗漏）之上追加了巅峰档
         * 特有的不变量与回归两问，对应其提示词里的 invariants / verification
         * matrix 闭环。
         *
         * 消费方式与三问清单不变：[ThinkingModeController.finalSelfCheckPrompt]
         * 在 [finalSelfCheck] = true 时注入清单——APEXCODE 档优先返回本清单
         * （MAXIMUM / ULTRACODE 仍用 [SELF_CHECK_CHECKLIST]），经 system
         * prompt 的 Thinking 段在模型产出最终回复**之前**下发。
         */
        const val APEX_SELF_CHECK_CHECKLIST: String = """Before sending your final response, run this apex-grade adversarial self-check:
1. Goal: is the user's actual goal fully achieved — every requirement, not just the easy parts?
2. Side effects: did any change touch state, files, or behavior beyond what was explicitly requested?
3. Omissions: is anything you promised or planned still missing? Is any success claim unverified by a tool?
4. Invariants: are all invariants you identified upfront still holding after your changes?
5. Regression: did you scan callers and similar patterns, and re-run the verification matrix (build / lint / test / re-read)?
If any check fails, fix the gap with tools first; only then deliver the final answer."""

        /**
         * DEEP / MAXIMUM / ULTRACODE / APEXCODE 档（[postToolVerification] = true）
         * 在**工具失败**后注入的自检提示（[ThinkingModeController.postToolCheckPrompt]，
         * `%s` = 工具 id）。
         */
        const val POST_TOOL_FAILURE_CHECK: String = """[Thinking-level verification] The tool `%s` just FAILED. Before your next action, briefly:
1. State in one sentence what the error actually means (not just repeat it).
2. Decide: fix the arguments, switch to a different approach, or ask the user — do NOT repeat the identical call."""

        /**
         * DEEP / MAXIMUM / ULTRACODE / APEXCODE 档在 **HIGH 风险工具执行成功**后
         * 注入的确认提示（`%s` = 工具 id）。高风险工具即使成功也要回头验证副作用 ——
         * "执行成功"不等于"效果符合预期"。
         */
        const val POST_TOOL_HIGH_RISK_CHECK: String = """[Thinking-level verification] You just ran the HIGH-RISK tool `%s` successfully. Before continuing, verify the effect matches intent: no unintended side effects, no extra state left behind. If unsure, confirm with a read-only tool first."""

        /**
         * 档位 → 画像全量静态表。
         *
         * 提示词文本自 [ThinkingLevel.toPromptInstruction] 迁移并增强：
         * DEEP / MAXIMUM 追加了与执行策略（工具自检 / 终检清单）呼应的指令，
         * 让提示词与档位行为互相咬合而非各说各话。纯静态、无副作用，
         * [forLevel] 幂等（同档位多次调用返回等值画像）。
         */
        fun forLevel(level: ThinkingLevel): ThinkingProfile = when (level) {
            ThinkingLevel.NONE -> ThinkingProfile(
                level = ThinkingLevel.NONE,
                promptInstruction = "",
                thinkingBudget = 0,
                reasoningEffortName = null,
                maxIterationsScale = 0.8f,
                postToolVerification = false,
                finalSelfCheck = false,
                compressionAggressiveness = 1.2f,
                toolOutputBudget = 6000,
                uiDescriptionZh = "直接执行，不生成推理；快进快出（迭代×0.8、更早压缩省 token）",
                uiDescriptionEn = "Act directly, no reasoning; fast in-and-out (iterations x0.8, earlier compression)"
            )

            ThinkingLevel.LIGHT -> ThinkingProfile(
                level = ThinkingLevel.LIGHT,
                promptInstruction = "Briefly think about what to do next in 1-2 sentences, then act.",
                thinkingBudget = 256,
                reasoningEffortName = "LOW",
                maxIterationsScale = 0.9f,
                postToolVerification = false,
                finalSelfCheck = false,
                compressionAggressiveness = 1.0f,
                toolOutputBudget = 7000,
                uiDescriptionZh = "1-2 句简短思考后立即行动，省 token 的轻量推理",
                uiDescriptionEn = "Think briefly in 1-2 sentences, then act; token-light reasoning"
            )

            ThinkingLevel.STANDARD -> ThinkingProfile(
                level = ThinkingLevel.STANDARD,
                promptInstruction = """
                    Use Chain-of-Thought before acting:
                    1. Break the task into clear sub-steps.
                    2. For the current step, reason step by step what to do and which tool to use.
                    3. Execute, then observe the result before the next step.
                """.trimIndent(),
                thinkingBudget = 1024,
                reasoningEffortName = "MEDIUM",
                maxIterationsScale = 1.0f,
                postToolVerification = false,
                finalSelfCheck = false,
                compressionAggressiveness = 1.0f,
                toolOutputBudget = 8000,
                uiDescriptionZh = "标准三步思维链：拆解任务→逐步推理→执行观察",
                uiDescriptionEn = "Standard 3-step Chain-of-Thought: decompose, reason, execute & observe"
            )

            ThinkingLevel.DEEP -> ThinkingProfile(
                level = ThinkingLevel.DEEP,
                promptInstruction = """
                    Reason carefully using a multi-path approach before acting:
                    1. Decompose the task into sequential sub-problems.
                    2. For the key decision, generate at least 2-3 candidate approaches (branch out, do not commit early).
                    3. Evaluate each on feasibility, efficiency, risk, and side effects.
                    4. Critique: "What could go wrong? What am I missing?"
                    5. Pick the best approach, explain why, then execute the first step and observe.
                    After any failed or high-risk tool call, re-examine your approach before the next action.
                """.trimIndent(),
                thinkingBudget = 4096,
                reasoningEffortName = "HIGH",
                maxIterationsScale = 1.2f,
                postToolVerification = true,
                finalSelfCheck = false,
                compressionAggressiveness = 1.0f,
                toolOutputBudget = 9000,
                uiDescriptionZh = "多路径五步推理（候选方案→评估→批评→择优）；工具失败/高风险后追加自检，迭代×1.2",
                uiDescriptionEn = "Multi-path 5-step reasoning; post-tool verification on failure/high-risk, iterations x1.2"
            )

            ThinkingLevel.MAXIMUM -> ThinkingProfile(
                level = ThinkingLevel.MAXIMUM,
                promptInstruction = """
                    Perform exhaustive reasoning before any action:
                    1. Fully understand the task and constraints.
                    2. Decompose into sub-problems; for each, enumerate ALL plausible solution paths (Tree-of-Thoughts style).
                    3. Score each path on feasibility, efficiency, risk, and side effects.
                    4. Consider edge cases and failure modes.
                    5. Self-critique: "What could go wrong? Am I missing something?"
                    6. Synthesize the optimal execution plan from the best path.
                    7. Only then, execute the first step, observe, and re-evaluate if the outcome diverges.
                    After any failed or high-risk tool call, re-examine your approach before the next action.
                    Before your final answer, run the pre-response self-check (goal / side effects / omissions).
                """.trimIndent(),
                thinkingBudget = 16384,
                reasoningEffortName = "MAX",
                maxIterationsScale = 1.5f,
                postToolVerification = true,
                finalSelfCheck = true,
                compressionAggressiveness = 1.0f,
                toolOutputBudget = 10000,
                uiDescriptionZh = "七步思维树穷举推理 + 工具自检 + 最终自评清单（目标/副作用/遗漏），迭代×1.5",
                uiDescriptionEn = "7-step Tree-of-Thoughts + post-tool verification + final self-check, iterations x1.5"
            )

            ThinkingLevel.ULTRACODE -> ThinkingProfile(
                level = ThinkingLevel.ULTRACODE,
                promptInstruction = """
                    Use coding-grade deep reasoning before any edit:
                    1. Read-map-plan: re-read the relevant files and endpoints first; map the current state before changing it.
                    2. State the invariants: what must remain true after the change (behavior, APIs, data, tests).
                    3. Generate 2-3 candidate edits and rank them by risk of breaking the invariants.
                    4. Choose the smallest change that satisfies the goal; prefer surgical edits over rewrites.
                    5. Apply the edit, then immediately verify: re-read the changed region; run build/lint/test when available.
                    6. Scan for regressions: callers, similar patterns, and side effects your edit may have introduced.
                    7. If verification fails, diagnose the actual cause before retrying — never blind-retry the same edit.
                    After any failed or high-risk tool call, re-examine your approach before the next action.
                    Before your final answer, run the pre-response self-check (goal / side effects / omissions).
                """.trimIndent(),
                thinkingBudget = 32768,
                reasoningEffortName = "MAX",
                maxIterationsScale = 2.0f,
                postToolVerification = true,
                finalSelfCheck = true,
                compressionAggressiveness = 1.0f,
                toolOutputBudget = 12000,
                uiDescriptionZh = "编码特化深推理：不变量→候选改法→风险排序→最小修改→即时验证；迭代×2.0、输出预算 12000",
                uiDescriptionEn = "Coding-grade deep reasoning: invariants, candidate edits, risk-ranking, minimal change, immediate verification; iterations x2.0, output budget 12000"
            )

            ThinkingLevel.APEXCODE -> ThinkingProfile(
                level = ThinkingLevel.APEXCODE,
                promptInstruction = """
                    Operate at architecture level with exhaustive rigor:
                    1. Architecture-first decomposition: map components, layers, and data flow before touching any file.
                    2. Blast-radius mapping: list every module, API, and test that the intended change can affect.
                    3. Build 2-3 full plans (Tree-of-Thoughts style) and score them against a verification matrix (build / lint / test / re-read).
                    4. Adversarial self-review: attack your own plan and diff as a hostile reviewer would; hunt the weakest assumption.
                    5. Execute with checkpoints: after each stage, verify against the matrix before proceeding.
                    6. Post-verification is exhaustive: run or re-run build, lint, and tests; re-read every file you changed end-to-end.
                    7. Deliver an evidence-based summary: every claim backed by a verification result, not an impression.
                    After any failed or high-risk tool call, re-examine your approach before the next action.
                    Before your final answer, run the apex-grade adversarial self-check (goal / side effects / omissions / invariants / regression).
                """.trimIndent(),
                thinkingBudget = 65536,
                reasoningEffortName = "MAX",
                maxIterationsScale = 3.0f,
                postToolVerification = true,
                finalSelfCheck = true,
                // < 1 = 更晚压缩（有效阈值 = base / 0.9 ≈ 0.889 > base）：巅峰档
                // 保留更多上下文供架构级穷举推理，宁可多花 token 也不丢证据链。
                compressionAggressiveness = 0.9f,
                toolOutputBudget = 16000,
                uiDescriptionZh = "架构级穷举推理 + 对抗性自审 + 全量验证矩阵；迭代×3.0、更晚压缩保留更多上下文",
                uiDescriptionEn = "Architecture-level exhaustive reasoning + adversarial self-review + full verification matrix; iterations x3.0, later compression keeps more context"
            )

            ThinkingLevel.AUTO -> ThinkingProfile(
                level = ThinkingLevel.AUTO,
                promptInstruction = "",
                thinkingBudget = null,
                reasoningEffortName = null,
                maxIterationsScale = 1.0f,
                postToolVerification = false,
                finalSelfCheck = false,
                compressionAggressiveness = 1.0f,
                toolOutputBudget = 8000,
                uiDescriptionZh = "自动：按任务复杂度（长度/多步指示/代码含量/风险词/错误史）逐轮动态选档",
                uiDescriptionEn = "Auto: pick the level per turn from task complexity (length / steps / code / risk / errors)"
            )
        }
    }
}
