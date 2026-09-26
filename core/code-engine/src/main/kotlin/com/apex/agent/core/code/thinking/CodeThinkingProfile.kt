package com.apex.agent.core.code.thinking

import kotlin.math.roundToInt

/**
 * # Code Thinking Profile — Coding 模式七档执行画像
 *
 * 与 agent-engine 六档画像（ThinkingProfile）**平行分立**：这里的表只
 * 服务 coding 工作流，多出的两行是编码深水档 ULTRACODE / APEXCODE。
 *
 * ## 与引擎的分工（映射打底 + 旋钮反补）
 *
 * 引擎执行内核按六档画像运转。coding 侧把 [CodeThinkingLevel.toAgentLevel]
 * 的结果交给引擎（NONE..MAXIMUM 一一对应，引擎自己的倍率体系生效）；
 * 深水两档映射 MAXIMUM 打底后，其**超出 MAXIMUM 的增量**由本类的
 * compensated\* 纯函数反补（经 `patchConfig` 覆写引擎配置基数）：
 *
 * | 档位      | budget | effort | 迭代倍率 | 工具输出预算 | 压缩倍率 |
 * |-----------|--------|--------|----------|--------------|----------|
 * | NONE      | 0      | null   | ×0.8     | 6000         | 1.2      |
 * | LIGHT     | 256    | LOW    | ×0.9     | 7000         | 1.0      |
 * | STANDARD  | 1024   | MEDIUM | ×1.0     | 8000         | 1.0      |
 * | DEEP      | 4096   | HIGH   | ×1.2     | 9000         | 1.0      |
 * | MAXIMUM   | 16384  | MAX    | ×1.5     | 10000        | 1.0      |
 * | ULTRACODE | 32768  | MAX    | ×2.0     | 12000        | 1.0      |
 * | APEXCODE  | 65536  | MAX    | ×3.0     | 16000        | 0.9      |
 *
 * ## APEX 五问清单
 *
 * [APEX_SELF_CHECK_CHECKLIST]（目标 / 副作用 / 遗漏 / 不变量 / 回归）
 * 迁入 coding 侧，经 [CodeThinkingPrompts] 的 APEXCODE 指令通道注入
 * （additionalSystemContext 时机等价：模型在产出最终回复之前看到）。
 *
 * 纯静态、无副作用；[forLevel] 幂等（同档位多次调用返回等值画像）。
 */
data class CodeThinkingProfile(
    val level: CodeThinkingLevel,
    val thinkingBudget: Int?,
    val reasoningEffortName: String?,
    val maxIterationsScale: Float,
    val toolOutputBudget: Int,
    val compressionAggressiveness: Float,
    val uiDescriptionZh: String,
    val uiDescriptionEn: String
) {
    companion object {

        /**
         * APEXCODE 档专属终检清单：在三问（目标/副作用/遗漏）之上追加
         * 巅峰档特有的不变量与回归两问，对应其指令里的 invariants /
         * verification matrix 闭环。
         *
         * 消费方式：[CodeThinkingPrompts] 把它拼进 APEXCODE 编码指令
         * （additionalSystemContext 通道），模型在产出最终回复**之前**
         * 看到。
         */
        const val APEX_SELF_CHECK_CHECKLIST: String = """Before sending your final response, run this apex-grade adversarial self-check:
1. Goal: is the user's actual goal fully achieved — every requirement, not just the easy parts?
2. Side effects: did any change touch state, files, or behavior beyond what was explicitly requested?
3. Omissions: is anything you promised or planned still missing? Is any success claim unverified by a tool?
4. Invariants: are all invariants you identified upfront still holding after your changes?
5. Regression: did you scan callers and similar patterns, and re-run the verification matrix (build / lint / test / re-read)?
If any check fails, fix the gap with tools first; only then deliver the final answer."""

        /** 档位 → 画像全量静态表（7 深度档；AUTO 元档不在表内——由预检解析后用该档画像）。 */
        fun forLevel(level: CodeThinkingLevel): CodeThinkingProfile = when (level) {
            CodeThinkingLevel.NONE -> CodeThinkingProfile(
                level = CodeThinkingLevel.NONE,
                thinkingBudget = 0,
                reasoningEffortName = null,
                maxIterationsScale = 0.8f,
                toolOutputBudget = 6000,
                compressionAggressiveness = 1.2f,
                uiDescriptionZh = "直接动手：跳过推理，快进快出（迭代×0.8、更早压缩省 token）",
                uiDescriptionEn = "Act directly, no reasoning; fast in-and-out (iterations x0.8, earlier compression)"
            )

            CodeThinkingLevel.LIGHT -> CodeThinkingProfile(
                level = CodeThinkingLevel.LIGHT,
                thinkingBudget = 256,
                reasoningEffortName = "LOW",
                maxIterationsScale = 0.9f,
                toolOutputBudget = 7000,
                compressionAggressiveness = 1.0f,
                uiDescriptionZh = "一读一改：扫一眼目标处就改，省 token 的轻量推理",
                uiDescriptionEn = "Glance-edit: read the spot, patch, glance the result — token-light"
            )

            CodeThinkingLevel.STANDARD -> CodeThinkingProfile(
                level = CodeThinkingLevel.STANDARD,
                thinkingBudget = 1024,
                reasoningEffortName = "MEDIUM",
                maxIterationsScale = 1.0f,
                toolOutputBudget = 8000,
                compressionAggressiveness = 1.0f,
                uiDescriptionZh = "标准编码循环：读→改→验→回读，diff 最小化",
                uiDescriptionEn = "Standard coding loop: read → edit → verify → re-read, minimal diff"
            )

            CodeThinkingLevel.DEEP -> CodeThinkingProfile(
                level = CodeThinkingLevel.DEEP,
                thinkingBudget = 4096,
                reasoningEffortName = "HIGH",
                maxIterationsScale = 1.2f,
                toolOutputBudget = 9000,
                compressionAggressiveness = 1.0f,
                uiDescriptionZh = "改动集思维：先建改动清单再动手，每处改动关联验证方式",
                uiDescriptionEn = "Changeset mindset: list every change before acting, tie each to its verification"
            )

            CodeThinkingLevel.MAXIMUM -> CodeThinkingProfile(
                level = CodeThinkingLevel.MAXIMUM,
                thinkingBudget = 16384,
                reasoningEffortName = "MAX",
                maxIterationsScale = 1.5f,
                toolOutputBudget = 10000,
                compressionAggressiveness = 1.0f,
                uiDescriptionZh = "不变量守护：识别不变量与连锁影响，改后全链路核对",
                uiDescriptionEn = "Invariant guarding: identify invariants & ripple effects, re-check the whole chain after edits"
            )

            CodeThinkingLevel.ULTRACODE -> CodeThinkingProfile(
                level = CodeThinkingLevel.ULTRACODE,
                thinkingBudget = 32768,
                reasoningEffortName = "MAX",
                maxIterationsScale = 2.0f,
                toolOutputBudget = 12000,
                compressionAggressiveness = 1.0f,
                uiDescriptionZh = "编码深推理闭环：不变量→候选改法→风险排序→最小修改→即时验证；迭代×2.0",
                uiDescriptionEn = "Coding deep-reasoning loop: invariants, candidate edits, risk-ranking, minimal change, immediate verification; iterations x2.0"
            )

            CodeThinkingLevel.APEXCODE -> CodeThinkingProfile(
                level = CodeThinkingLevel.APEXCODE,
                thinkingBudget = 65536,
                reasoningEffortName = "MAX",
                maxIterationsScale = 3.0f,
                toolOutputBudget = 16000,
                // < 1 = 更晚压缩（有效阈值 = base / 0.9 ≈ 1.111 × base）：巅峰档
                // 保留更多上下文供架构级穷举推理，宁可多花 token 也不丢证据链。
                compressionAggressiveness = 0.9f,
                uiDescriptionZh = "架构级穷举推理 + 对抗性自审 + 全量验证矩阵；迭代×3.0、更晚压缩保留更多上下文",
                uiDescriptionEn = "Architecture-level exhaustive reasoning + adversarial self-review + full verification matrix; iterations x3.0, later compression keeps more context"
            )

            // AUTO 元档：预检解析出具体深度档后走对应行；此处返回 STANDARD
            // 占位（引擎不应收到 AUTO——CodeThinkingLevel.toAgentLevel 已兜底 DEEP）。
            CodeThinkingLevel.AUTO -> forLevel(CodeThinkingLevel.STANDARD)
        }

        // ═══ 旋钮补偿纯函数（深水两档的「映射 MAXIMUM 打底 + 增量反补」）═══

        /**
         * 补偿后的引擎 maxIterations 基数。
         *
         * 引擎对 MAXIMUM 档自乘 ×1.5（六档画像），深水两档要达到目标倍率：
         * - ULTRACODE ×2.0 → 基数补 (2.0 / 1.5)，引擎 1.5 × 补偿 = 2.0；
         * - APEXCODE ×3.0 → 基数补 (3.0 / 1.5)，引擎 1.5 × 补偿 = 3.0。
         *
         * NONE..MAXIMUM 与 AUTO 档返回 base 原值（引擎自己的倍率体系生效）。
         * 端到端等式见 CodeThinkingProfileTest（base × 补偿 × 引擎倍率 = 目标倍率）。
         */
        fun compensatedMaxIterations(base: Int, level: CodeThinkingLevel): Int = when (level) {
            CodeThinkingLevel.ULTRACODE -> (base * ULTRACODE_ITER_DIVISOR).roundToInt().coerceAtLeast(1)
            CodeThinkingLevel.APEXCODE -> (base * APEXCODE_ITER_DIVISOR).roundToInt().coerceAtLeast(1)
            else -> base
        }

        /**
         * 补偿后的引擎 maxToolOutputLength 基数。
         *
         * 引擎取 max(配置值, 画像预算) 且 MAXIMUM 画像预算 10000：深水两档
         * 直接把配置抬到档位预算（≥10000 时引擎不再放大），其余档返回 base。
         */
        fun compensatedToolOutputBudget(base: Int, level: CodeThinkingLevel): Int = when (level) {
            CodeThinkingLevel.ULTRACODE -> maxOf(base, forLevel(CodeThinkingLevel.ULTRACODE).toolOutputBudget)
            CodeThinkingLevel.APEXCODE -> maxOf(base, forLevel(CodeThinkingLevel.APEXCODE).toolOutputBudget)
            else -> base
        }

        /**
         * 补偿后的引擎 compressionThreshold 基数。
         *
         * APEXCODE 档压缩倍率 0.9（更晚压缩）：基数除以 0.9 抬高阈值，
         * 引擎对 MAXIMUM 档倍率为 1.0（不除不减），端到端 = base / 0.9。
         * 钳制 0.1..0.98 防极端配置。其余档返回 base。
         */
        fun compensatedCompressionThreshold(base: Float, level: CodeThinkingLevel): Float = when (level) {
            CodeThinkingLevel.APEXCODE -> (base / forLevel(CodeThinkingLevel.APEXCODE).compressionAggressiveness)
                .coerceIn(0.1f, 0.98f)
            else -> base
        }

        /** ULTRACODE 迭代补偿系数（目标 ×2.0 ÷ 引擎 MAXIMUM ×1.5）。 */
        private const val ULTRACODE_ITER_DIVISOR = 2.0f / 1.5f

        /** APEXCODE 迭代补偿系数（目标 ×3.0 ÷ 引擎 MAXIMUM ×1.5）。 */
        private const val APEXCODE_ITER_DIVISOR = 3.0f / 1.5f
    }
}
