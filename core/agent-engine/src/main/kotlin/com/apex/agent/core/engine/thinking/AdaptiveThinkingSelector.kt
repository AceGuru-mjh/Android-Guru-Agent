package com.apex.agent.core.engine.thinking

import com.apex.agent.core.engine.ThinkingLevel

/**
 * AUTO 档自适应选档输入（#168）。
 *
 * @param userText 本轮用户输入（或步骤 prompt）——复杂度分类的主要文本信号。
 * @param historyToolCalls 历史工具调用总数（累计；长任务"深水区"信号）。
 * @param recentErrors 最近 3 轮错误数（错误恢复信号：出错 → 升档反思）。
 * @param iterationIndex 当前迭代序号（0 基或 1 基皆可，仅 `== 0` 判定探索期）。
 * @param planMode 是否 Plan 模式规划期（探索期保底信号）。
 */
data class AdaptiveInput(
    val userText: String,
    val historyToolCalls: Int,
    val recentErrors: Int,
    val iterationIndex: Int,
    val planMode: Boolean
)

/**
 * AUTO 档选档结果：档位 + 可解释理由（UI / 日志可见）。
 *
 * 理由为中文短语拼接关键因子，如
 * `代码/命令+2、风险词(rm -rf)+3 → 评分 5 → DEEP（风险词保底 DEEP）`，
 * 供 [ThinkingProfile.decisionReason] 注入 system prompt 与 UI 展示。
 */
data class AdaptiveDecision(val level: ThinkingLevel, val reason: String)

/**
 * AUTO 档自适应选档器：输入复杂度分类 → 按轮次动态选 [ThinkingLevel]（#168；
 * v1.2 阶梯顶端扩到 ULTRACODE）。
 *
 * **APEXCODE 仅用户显式指定，自动化永不选择**——巅峰档的预算/迭代/上下文
 * 成本不可控，交给选档器会失控；本选档器的任何路径（阈值/保底/升档）都
 * 不可能返回 APEXCODE。
 *
 * ## 评分模型（每项加权分，全部因子都会写进 [AdaptiveDecision.reason]）
 *
 * | 维度 | 条件 | 加分 | 说明 |
 * |------|------|------|------|
 * | 文本长度 | > 500 字 | +2 | 长需求通常含隐式多步 |
 * |          | > 1500 字 | +3 | 超长需求（替换 +2，不叠加） |
 * | 多步指示词 | 先/然后/接着/步骤/first/then/step/plan/多任务 并列出现 | +2 | 显式多步任务 |
 * | 代码/命令含量 | ``` 代码块 / `$ ` 提示符 / git·npm·apt·gradle·pip 等命令词 | +2 | 技术任务需要结构化推理 |
 * | 风险词 | 删除/uninstall/rm -rf/格式化/刷机/权限 等 | +3 | **硬保底 DEEP**（无论总分） |
 * | 错误恢复 | recentErrors > 0 | +2 且**显式升一档** | 近期出错 → 当前思路有问题，加深反思避免重蹈（ULTRACODE 封顶） |
 *
 * ## 总分 → 档位阈值
 *
 * | 总分 | 档位 |
 * |------|------|
 * | < 3 | LIGHT |
 * | 3..5 | STANDARD |
 * | 6..8 | DEEP |
 * | 9..11 | MAXIMUM |
 * | > 11 | ULTRACODE |
 *
 * ## 档位下限（在阈值结果之上取 max，可解释地写进理由）
 *
 * - 风险词命中 → 至少 **DEEP**（破坏性操作必须多方案对比 + 风险评估）；
 * - 探索期（iterationIndex == 0 且 planMode）→ 至少 **STANDARD**
 *   （规划期首轮需要完整 CoT 而非简思）；
 * - 长任务深水区（historyToolCalls > 15）→ 至少 **STANDARD**
 *   （大量工具调用后上下文复杂度上升，需要一致性与反思防跑偏）；
 * - 长任务深水区 + 连环失败（historyToolCalls > 30 且 recentErrors >= 2）
 *   → 至少 **ULTRACODE**（v1.2：长任务跑到 30+ 次工具调用还在连环失败 =
 *   当前思路已经系统性跑偏，需要编码特化深推理闭环重新锚定不变量）。
 *
 * ## 短平快
 *
 * 输入 < 80 字符且无任何指示词/代码/风险信号 → 评分必然 < 3 → LIGHT，
 * 理由标注「短平快」。该规则与阈值表天然一致，无需额外分支。
 *
 * 纯函数、无状态：同一 [AdaptiveInput] 永远得到同一决策。
 */
class AdaptiveThinkingSelector {

    /** @return 选档结果 + 可解释理由（UI/日志可见） */
    fun select(input: AdaptiveInput): AdaptiveDecision {
        val text = input.userText
        val factors = mutableListOf<String>()
        var score = 0

        // ── 维度 1：文本长度（>500 +2；>1500 +3，替换不叠加）──
        when {
            text.length > LONG_TEXT_HEAVY -> {
                score += 3; factors.add("超长文本+3")
            }
            text.length > LONG_TEXT_LIGHT -> {
                score += 2; factors.add("长文本+2")
            }
        }

        // ── 维度 2：多步指示词 ──
        val indicator = MULTI_STEP_INDICATORS.firstOrNull { text.contains(it, ignoreCase = true) }
        if (indicator != null) {
            score += 2; factors.add("多步指示($indicator)+2")
        }

        // ── 维度 3：代码 / 命令含量 ──
        val codeSignal = codeSignalOf(text)
        if (codeSignal != null) {
            score += 2; factors.add("代码/命令($codeSignal)+2")
        }

        // ── 维度 4：风险词（硬保底 DEEP）──
        val riskWord = RISK_KEYWORDS.firstOrNull { text.contains(it, ignoreCase = true) }
        if (riskWord != null) {
            score += 3; factors.add("风险词($riskWord)+3")
        }

        // ── 维度 5：错误恢复（近期出错 → 加深一档反思）──
        if (input.recentErrors > 0) {
            score += 2; factors.add("错误恢复(${input.recentErrors}次)+2")
        }

        // ── 总分 → 基础档位 ──
        var level = when {
            score > ULTRACODE_SCORE_CEILING -> ThinkingLevel.ULTRACODE
            score > DEEP_SCORE_CEILING -> ThinkingLevel.MAXIMUM
            score >= DEEP_SCORE_FLOOR -> ThinkingLevel.DEEP
            score >= STANDARD_SCORE_FLOOR -> ThinkingLevel.STANDARD
            else -> ThinkingLevel.LIGHT
        }

        // ── 短平快标注（< 80 字符且零信号 → LIGHT，阈值表已保证；仅补理由可读性）──
        if (factors.isEmpty() && text.length < QUICK_TEXT_MAX) {
            factors.add("短平快(<${QUICK_TEXT_MAX}字符)")
        }

        // ── 调整（升档 / 保底，逐条写进理由）──
        val adjustments = mutableListOf<String>()

        // 错误恢复升一档：评分 +2 之外显式保证「升一档」语义 —— 仅靠 +2 跨不过
        // 档位边界时会卡在原档（如零信号任务 0+2=2 仍是 LIGHT），故直接提升
        // 一档；v1.2 阶梯扩为 LIGHT→…→DEEP→MAXIMUM→ULTRACODE，ULTRACODE 封顶
        // 不再升（APEXCODE 不可自动选，见类 KDoc 的成本防线说明）。
        if (input.recentErrors > 0) {
            level = when (level) {
                ThinkingLevel.LIGHT -> ThinkingLevel.STANDARD
                ThinkingLevel.STANDARD -> ThinkingLevel.DEEP
                ThinkingLevel.DEEP -> ThinkingLevel.MAXIMUM
                ThinkingLevel.MAXIMUM -> ThinkingLevel.ULTRACODE
                ThinkingLevel.ULTRACODE, ThinkingLevel.APEXCODE -> ThinkingLevel.ULTRACODE // ULTRACODE 封顶；APEXCODE 不可达（基础档位不含），穷举保护
                ThinkingLevel.NONE, ThinkingLevel.AUTO -> level // 不可达（基础档位不含），穷举保护
            }
            adjustments.add("错误恢复升一档")
        }

        if (riskWord != null && level < ThinkingLevel.DEEP) {
            level = ThinkingLevel.DEEP
            adjustments.add("风险词保底DEEP")
        }
        if (input.iterationIndex == 0 && input.planMode && level < ThinkingLevel.STANDARD) {
            level = ThinkingLevel.STANDARD
            adjustments.add("规划期首轮保底STANDARD")
        }
        if (input.historyToolCalls > DEEP_WATER_TOOL_CALLS && level < ThinkingLevel.STANDARD) {
            level = ThinkingLevel.STANDARD
            adjustments.add("长任务深水区(>${DEEP_WATER_TOOL_CALLS}次调用)保底STANDARD")
        }
        // v1.2 深水区升级规则：长任务（>30 次调用）叠加连环失败（≥2 错误）=
        // 系统性跑偏，STANDARD 保底不够用 → 至少 ULTRACODE（编码特化深推理
        // 闭环重新锚定不变量）。APEXCODE 仍不可自动选（成本防线）。
        if (input.historyToolCalls > DEEP_WATER_ULTRACODE_CALLS &&
            input.recentErrors >= ULTRACODE_ERROR_FLOOR &&
            level < ThinkingLevel.ULTRACODE
        ) {
            level = ThinkingLevel.ULTRACODE
            adjustments.add("深水区连环失败(${input.historyToolCalls}次调用&${input.recentErrors}错误)保底ULTRACODE")
        }

        val reason = buildString {
            append(factors.joinToString("、").ifEmpty { "无信号" })
            append(" → 评分 ").append(score)
            append(" → ").append(level.name)
            if (adjustments.isNotEmpty()) {
                append("（").append(adjustments.joinToString("；")).append("）")
            }
        }
        return AdaptiveDecision(level, reason)
    }

    /**
     * 代码/命令信号检测：命中任意信号返回**首个**命中的信号词（写进理由），
     * 未命中返回 null。
     *
     * - ```` ``` ```` 围栏代码块；
     * - `$ ` / `# ` shell 提示符；
     * - git / npm / apt / pip / gradle / adb / shell / curl / kotlin / python 等命令词。
     */
    private fun codeSignalOf(text: String): String? = when {
        text.contains("```") -> "```"
        text.contains("\$ ") -> "\$ "
        else -> CODE_COMMAND_WORDS.firstOrNull { text.contains(it, ignoreCase = true) }
    }

    private companion object {
        /** 多步指示词（中英双语；「多任务」按词匹配，其余按子串）。 */
        val MULTI_STEP_INDICATORS = listOf(
            "先", "然后", "接着", "步骤", "多任务", "first", "then", "step", "plan"
        )

        /** 风险词：破坏性 / 不可逆 / 系统级操作的强信号。 */
        val RISK_KEYWORDS = listOf(
            "rm -rf", "删除", "uninstall", "卸载", "格式化", "刷机", "权限", "factory reset", "wipe"
        )

        /** 命令词（技术任务信号）。 */
        val CODE_COMMAND_WORDS = listOf(
            "git", "npm", "apt", "pip", "gradle", "adb", "shell", "curl", "kotlin", "python", "terminal"
        )

        const val LONG_TEXT_LIGHT = 500
        const val LONG_TEXT_HEAVY = 1500
        const val QUICK_TEXT_MAX = 80
        const val STANDARD_SCORE_FLOOR = 3
        const val DEEP_SCORE_FLOOR = 6
        const val DEEP_SCORE_CEILING = 8
        const val DEEP_WATER_TOOL_CALLS = 15

        /** 总分 > 11 → ULTRACODE（v1.2：满分 12 = 超长+多步+代码+风险+错误全叠加）。 */
        const val ULTRACODE_SCORE_CEILING = 11

        /** 深水区升级规则的调用数门槛：> 30 次工具调用。 */
        const val DEEP_WATER_ULTRACODE_CALLS = 30

        /** 深水区升级规则的错误数门槛：最近 >= 2 次失败。 */
        const val ULTRACODE_ERROR_FLOOR = 2
    }
}
