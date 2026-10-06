package com.apex.agent.usage

/**
 * ═════════════════════════════════════════════════════════════════════════════
 *  #221：模型费率表 —— 用量仪表盘费用估算
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * 仪表盘此前只有 token 数没有费用口径（issue #221）。本表内置常见模型的
 * **官方公开牌价**（USD / 每百万 token，输入与输出分档），按模型 id 的
 * 子串规则匹配（大小写不敏感；provider 前缀如 openai/ 自定义中转命名
 * 均能命中）。
 *
 * 诚实边界（非缺陷，设计取舍）：
 *  - 费率是**静态牌价**，不含渠道折扣/分层/汇率 —— 展示为「估算」并带
 *    「≈」标记，用户自行折算实际账单；
 *  - 未知模型（自定义/新发布未收录）返回 null —— UI 显示「—」并注明
 *    「未收录费率」，绝不臆造数字；
 *  - 匹配从具体到宽泛（先试长关键词），同前缀家族按高档位靠拢的保守序
 *    （如 gpt-4o 先于 gpt-4）；带 mini/flash/lite 后缀的按廉价档命中。
 */
object ModelPricing {

    /** 单模型费率（USD / 1M tokens，输入与输出分档）。 */
    data class Rate(val inputPerM: Double, val outputPerM: Double) {
        /** 估算费用（USD）：输入/输出分别计费后求和。 */
        fun costUsd(promptTokens: Long, completionTokens: Long): Double =
            promptTokens / 1_000_000.0 * inputPerM +
                completionTokens / 1_000_000.0 * outputPerM
    }

    /**
     * 匹配规则表 —— 顺序即优先级（先具体后宽泛）。
     * 关键词为 modelId 的小写子串匹配；子串命中即返回该行费率。
     */
    private val rules: List<Pair<String, Rate>> = listOf(
        // ── OpenAI ──
        "gpt-4o-mini" to Rate(0.15, 0.60),
        "gpt-4o" to Rate(2.50, 10.00),
        "gpt-4.1-mini" to Rate(0.40, 1.60),
        "gpt-4.1-nano" to Rate(0.10, 0.40),
        "gpt-4.1" to Rate(2.00, 8.00),
        "o4-mini" to Rate(1.10, 4.40),
        "o3-mini" to Rate(1.10, 4.40),
        "o3" to Rate(2.00, 8.00),
        "o1-mini" to Rate(1.10, 4.40),
        "o1" to Rate(15.00, 60.00),
        "gpt-4-turbo" to Rate(10.00, 30.00),
        "gpt-4" to Rate(30.00, 60.00),
        "gpt-3.5" to Rate(0.50, 1.50),
        // ── Anthropic ──
        "claude-opus-4" to Rate(15.00, 75.00),
        "claude-sonnet-4" to Rate(3.00, 15.00),
        "claude-3-7-sonnet" to Rate(3.00, 15.00),
        "claude-3-5-haiku" to Rate(0.80, 4.00),
        "claude-3-5-sonnet" to Rate(3.00, 15.00),
        "claude-3-opus" to Rate(15.00, 75.00),
        "claude-3-haiku" to Rate(0.25, 1.25),
        // ── Google ──
        "gemini-2.5-pro" to Rate(1.25, 10.00),
        "gemini-2.5-flash" to Rate(0.15, 0.60),
        "gemini-2.0-flash-lite" to Rate(0.075, 0.30),
        "gemini-2.0-flash" to Rate(0.10, 0.40),
        "gemini-1.5-pro" to Rate(1.25, 5.00),
        "gemini-1.5-flash" to Rate(0.075, 0.30),
        // ── DeepSeek ──
        "deepseek-reasoner" to Rate(0.55, 2.19),
        "deepseek-chat" to Rate(0.27, 1.10),
        "deepseek" to Rate(0.27, 1.10),
        // ── 阿里 / 智谱 / 月之暗面 / MiniMax / xAI ──
        "qwen-max" to Rate(1.60, 6.40),
        "qwen-plus" to Rate(0.40, 1.20),
        "qwen-turbo" to Rate(0.05, 0.20),
        "glm-4-plus" to Rate(0.70, 2.80),
        "glm-4-flash" to Rate(0.00, 0.00),
        "glm-4" to Rate(1.00, 1.00),
        "kimi-k2" to Rate(0.60, 2.50),
        "moonshot" to Rate(1.20, 12.00),
        "abab" to Rate(1.00, 3.00),
        "grok-3" to Rate(3.00, 15.00),
        "grok-2" to Rate(2.00, 10.00),
        // ── 本地/免费通道（0 费率）──
        "ollama" to Rate(0.00, 0.00),
        "llama" to Rate(0.00, 0.00),
        "qwen2.5" to Rate(0.00, 0.00)
    )

    /**
     * 按模型 id 查费率；未收录返回 null（UI 显示「未收录」，不臆造）。
     * 大小写不敏感；自定义中转命名（含品牌子串）同样命中。
     */
    fun rateFor(modelId: String): Rate? {
        val id = modelId.lowercase()
        for ((key, rate) in rules) {
            if (id.contains(key)) return rate
        }
        return null
    }

    /**
     * 全库费用估算汇总 —— 对每条模型统计匹配费率并求和。
     *
     * @return (估算总费用 USD, 是否全部模型都命中费率) —— 未全命中时
     *   UI 带「≈」标记（实际费用 ≥ 估算，未知模型部分未计入）。
     */
    fun estimateTotalUsd(models: List<UsageModelStat>): Pair<Double, Boolean> {
        var total = 0.0
        var allKnown = models.isNotEmpty()
        for (m in models) {
            val rate = rateFor(m.modelId)
            if (rate == null) {
                allKnown = false
                continue
            }
            total += rate.costUsd(m.promptTokens, m.completionTokens)
        }
        return total to allKnown
    }
}
