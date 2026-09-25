package com.apex.agent.core.engine.assist

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.InputType
import com.apex.agent.core.engine.assist.DecisionPointDetector.DecisionPoint

/**
 * ═══ HUMAN_ASSIST 模式执行策略（#168）═══
 *
 * 响应文本含决策点 → 自动转 ask_user_choice 闭环：
 *
 * ```
 * assistant 纯文本轮（模型写了「方案A…方案B…」但没有调工具）
 *   │
 *   ▼
 * [DecisionPointDetector.detect] 检出决策点
 *   │  null ──────────────────────────────────► return null（引擎照常收尾）
 *   ▼
 * emit UserInputRequired(question, CHOICE)     ← 选项以 `1. xxx` 行编码，
 *   │                                            UserInputDialog 的
 *   │                                            parseChoiceOptions 直接渲染单选卡
 *   ▼
 * awaitUserInput(question)  挂起等待 UI 回传
 *   │  空串（超时/取消）──► return null（安全降级：原回复作为最终答案）
 *   ▼
 * 把用户答复匹配回 DecisionOption（label → key → 序号）
 *   │
 *   ▼
 * return "用户选择：<key>（<label>）——请按该选择继续"
 *   │  引擎侧 addMessage(User(该文本)) → continue 下一轮迭代
 *   ▼
 * 模型带着用户的显式决策继续执行
 * ```
 *
 * 事件结构说明：[AgentEvent.UserInputRequired] 没有结构化 options 字段，
 * 选项编进 question 文本（`<问题>\n1. <label>\n2. <label>\n…`）——
 * 这正好命中 app 层 UserInputDialog 对 CHOICE 类型的既有解析
 * （parseChoiceOptions 按 `数字.` 行切分渲染单选卡），零 UI 改动即可
 * 呈现结构化选项菜单。
 *
 * 引擎接线（executeBuildLoop 纯文本响应分支，#168）：
 * ```kotlin
 * if (config.mode == AgentMode.HUMAN_ASSIST) {
 *     val followUp = HumanAssistFlow(emit) { awaitUserInput() }
 *         .interceptResponse(contentBuilder.toString())
 *     if (followUp != null) {
 *         addMessage(LlmMessage.Assistant(contentBuilder.toString()))
 *         addMessage(LlmMessage.User(followUp))
 *         continue   // 不走 ResponseComplete：任务尚未定案
 *     }
 * }
 * ```
 *
 * 每次拦截现场构造实例（无跨任务状态，构造零成本）；emit / awaitUserInput
 * 由引擎注入，本类不持有引擎引用——纯 Kotlin 可单测（fake 两个 lambda）。
 */
class HumanAssistFlow(
    private val emit: suspend (AgentEvent) -> Unit,
    private val awaitUserInput: suspend (String) -> String
) {

    /** 用户答复匹配回选项时的回填文本模板前缀（引擎作为 User 消息写入历史）。 */
    internal companion object {
        const val SELECTION_PREFIX = "用户选择"
        const val CONTINUE_SUFFIX = "——请按该选择继续"

        /** 自定义答复回填时最多保留的字符数（防超长粘贴刷爆上下文）。 */
        const val MAX_ANSWER_CHARS = 400
    }

    /**
     * 响应文本完成后调用。
     *
     * @param assistantText 本轮 assistant 的完整纯文本回复
     * @return null = 无决策点 / 用户未答复（超时、取消）→ 引擎照常
     *   ResponseComplete 收尾；非 null = 用户已选择，返回值作为**追加
     *   user 消息文本**（引擎 addMessage(User(结果)) 后继续下一轮迭代）
     */
    suspend fun interceptResponse(assistantText: String): String? {
        val point = DecisionPointDetector.detect(assistantText) ?: return null

        // 决策点 → 人工介入：发 CHOICE 事件并挂起等待用户选择。
        val question = formatQuestion(point)
        emit(AgentEvent.UserInputRequired(question, InputType.CHOICE))

        val answer = awaitUserInput(question).trim()
        // 空答复 = 超时 / 用户取消 → 安全降级：不拦截，原回复作为最终答案。
        if (answer.isEmpty()) return null

        val selected = resolveSelection(point, answer)
        return when (selected) {
            null ->
                // 自定义答复（不在选项内的自由文本）：原样回填为用户指令。
                // 模型看到的是用户原话 + 「按该指示继续」，语义保持用户主导。
                "$SELECTION_PREFIX：${answer.take(MAX_ANSWER_CHARS)}——请按该指示继续"
            else ->
                // 命中结构化选项：回填 key + label 双标识（key 供模型引用，
                // label 供人复核；两者一致时只写一次）。
                if (selected.key.equals(selected.label, ignoreCase = true)) {
                    "$SELECTION_PREFIX：${selected.label}——$CONTINUE_SUFFIX"
                } else {
                    "$SELECTION_PREFIX：${selected.key}（${selected.label}）——$CONTINUE_SUFFIX"
                }
        }
    }

    /**
     * 把决策点编码为 UserInputRequired 的 question 文本。
     *
     * 格式（与 app 层 UserInputDialog 的 CHOICE 解析正则严格对齐）：
     * ```
     * <问题文本>
     * 1. <选项1 label>
     * 2. <选项2 label>
     * …
     * <提示行：回复序号或输入自定义指示>
     * ```
     * 选项 label 带截断（单行 60 字符）；detail 拼进 label 尾部括号。
     */
    internal fun formatQuestion(point: DecisionPoint): String = buildString {
        append(point.question.trim())
        append('\n')
        point.options.forEachIndexed { index, option ->
            val detailSuffix = if (option.detail.isBlank()) "" else "（${option.detail}）"
            append(index + 1).append(". ")
            append(option.label.take(60))
            append(detailSuffix.take(100))
            append('\n')
        }
        append("回复序号选择，或直接输入你的指示。")
    }

    /**
     * 用户答复 → 结构化选项匹配。
     *
     * 匹配顺序（大小写不敏感、忽略首尾空白）：
     * 1. label 精确匹配（UserInputDialog 单选卡提交的就是完整 label）；
     * 2. key 精确匹配（用户手输 `A` / `B` / `continue`）;
     * 3. 1..N 序号匹配（用户手输 `1` / `2`）。
     * 全不命中 = 自定义答复 → null。
     */
    internal fun resolveSelection(
        point: DecisionPoint,
        answer: String
    ): DecisionPointDetector.DecisionOption? {
        val normalized = answer.trim()
        if (normalized.isEmpty()) return null

        // 1. label 精确匹配
        point.options.firstOrNull { it.label.equals(normalized, ignoreCase = true) }
            ?.let { return it }

        // 2. key 精确匹配
        point.options.firstOrNull { it.key.equals(normalized, ignoreCase = true) }
            ?.let { return it }

        // 3. 序号匹配（1..options.size）
        normalized.toIntOrNull()?.let { ordinal ->
            if (ordinal in 1..point.options.size) return point.options[ordinal - 1]
        }

        return null
    }
}
