package com.apex.agent.core.code.thinking

import com.apex.agent.core.engine.ThinkingLevel

/**
 * # Code Thinking Level — Coding 模式专属七档思考阶梯
 *
 * ## 为什么 code-engine 要有**自己的**档位枚举
 *
 * Agent 与 Coding 是两个独立的功能页面，档位体系理应各自独立：
 * - Agent 聊天页的六档（NONE..MAXIMUM + AUTO）面向**通用对话**，
 *   深度阶梯止于 MAXIMUM；
 * - Coding 页的七档（NONE..APEXCODE + AUTO）面向**改代码工作流**，
 *   在 MAXIMUM 之上还有两个编码深水档：
 *   [ULTRACODE]（不变量→候选改法→风险排序→最小修改→即时验证的深推理
 *   闭环）与 [APEXCODE]（架构级穷举 + 对抗性自审 + 全量验证矩阵，
 *   仅用户显式指定）。
 *
 * 历史上七档曾直接加进 agent-engine 的 [ThinkingLevel]，导致 Agent 聊天页
 * 也出现这两个编码档（模式错位）。本枚举把七档阶梯**收回 coding 模式**：
 * Agent 引擎零感知、聊天页回归六档。
 *
 * ## 与引擎六档的映射（[toAgentLevel]）
 *
 * 引擎执行内核（Thinking Instructions 段、迭代/压缩/输出预算倍率）按
 * 六档画像运转，coding 侧不重写引擎，而是「映射打底 + 旋钮反补」：
 *
 * - NONE..MAXIMUM 一一对应；
 * - **ULTRACODE / APEXCODE 映射 MAXIMUM 打底**，两档相对 MAXIMUM 的
 *   增量（更高迭代倍率 / 输出预算、APEX 更晚压缩）由
 *   [CodeThinkingProfile] 的 compensated\* 纯函数经 `patchConfig` 反补
 *   （见 [com.apex.agent.core.code.CodeAgentEngine.updateThinkingLevel]，
 *   引擎零改动、Agent 模式零影响）；
 * - AUTO 映射 DEEP 兜底——但 AUTO 的**正常路径**是 VM 发送前经
 *   [CodeAdaptiveThinkingSelector] 预检解析出具体深度档再下发，
 *   引擎从不接收 AUTO（coding 自治语义，见该类 KDoc）。
 *
 * 序数即深度序：枚举天然 Comparable，`ULTRACODE > MAXIMUM` 成立，
 * 选档器与 UI 均可直接比较。
 */
enum class CodeThinkingLevel(val level: Int, val description: String) {
    /** 不思考，直接行动（快速直改）。 */
    NONE(0, "直接执行，不生成推理过程"),

    /** 浅思考：1-2 句简短推理（一读一改）。 */
    LIGHT(1, "简短分析后立即行动"),

    /** 标准思考：读→改→验→回读的完整编码循环。 */
    STANDARD(2, "分析任务→选择方案→执行"),

    /** 深思考：改动集思维，多方案比较。 */
    DEEP(3, "多方案对比→风险评估→最优选择"),

    /** 极深思考：不变量守护，完整思维链 + 自我质疑。 */
    MAXIMUM(4, "完整推理链+自我反思+多轮验证"),

    /**
     * 编码特化深推理：面向改代码任务的深水档——先把不变量钉死，
     * 再枚举候选改法、按风险排序，永远选最小修改并即时验证。
     */
    ULTRACODE(5, "编码特化深推理：不变量→候选改法→风险排序→最小修改→即时验证"),

    /**
     * 巅峰档：架构级穷举推理 + 对抗性自审 + 全量验证矩阵。
     * **仅用户显式指定**——自动化（AUTO 预检/深水区升级观察器）永不
     * 选择，防止失控成本；预算与迭代倍率都是全梯度天花板。
     */
    APEXCODE(6, "架构级穷举推理 + 对抗性自审 + 全量验证矩阵"),

    /**
     * 自动档（元档）：**发送前预检选档 + 运行中深水区升级**，全部在
     * coding 侧自治（[CodeAdaptiveThinkingSelector]），引擎只接收解析
     * 后的具体深度档。level 数值 7 = 恒在全部深度档之后（元档不算深度层）。
     */
    AUTO(7, "自动：发送前预检选档，运行中深水区可升级");

    /**
     * 映射到引擎六档（执行内核的通用画像通道）。
     *
     * 深水两档映射 MAXIMUM 打底，增量经 [CodeThinkingProfile] 旋钮补偿
     * 反补；AUTO 映射 DEEP 兜底（正常路径下 VM 已解析出具体档，引擎
     * 不应收到 AUTO——此分支仅防御性兜底）。
     */
    fun toAgentLevel(): ThinkingLevel = when (this) {
        NONE -> ThinkingLevel.NONE
        LIGHT -> ThinkingLevel.LIGHT
        STANDARD -> ThinkingLevel.STANDARD
        DEEP -> ThinkingLevel.DEEP
        MAXIMUM, ULTRACODE, APEXCODE -> ThinkingLevel.MAXIMUM
        AUTO -> ThinkingLevel.DEEP
    }

    /** 对应的 thinking_budget（支持此参数的模型如 Gemini；AUTO = null 不直接映射）。 */
    fun toThinkingBudget(): Int? = when (this) {
        NONE -> 0
        LIGHT -> 256
        STANDARD -> 1024
        DEEP -> 4096
        MAXIMUM -> 16384
        ULTRACODE -> 32768 // 编码深水档：双倍于 MAXIMUM
        APEXCODE -> 65536 // 巅峰档：四倍于 MAXIMUM
        AUTO -> null
    }

    /**
     * 映射为模型原生思考强度枚举名（app 层转 [com.apex.agent.core.llm.ReasoningEffort]
     * 持久化到默认 ModelProfile）。ULTRACODE / APEXCODE 同映射 "MAX"——
     * Provider 侧 MAX 已是天花板，两档与 MAXIMUM 的真实差异由编码指令
     * （[CodeThinkingPrompts]）+ 旋钮补偿（[CodeThinkingProfile]）拉开。
     */
    fun toReasoningEffortName(): String? = when (this) {
        NONE -> null
        LIGHT -> "LOW"
        STANDARD -> "MEDIUM"
        DEEP -> "HIGH"
        MAXIMUM, ULTRACODE, APEXCODE -> "MAX"
        AUTO -> "adaptive" // 哨兵值：调用方必须特判 AUTO，不回退 ReasoningEffort.NONE
    }

    /**
     * 是否为深度档（7 深度档 = NONE..APEXCODE；仅 AUTO 元档不算——
     * 它不落在深度轴上，由预检解析后借具体档的画像）。
     */
    val isDepthTier: Boolean get() = this != AUTO

    companion object {
        /**
         * 持久化字符串解析（大小写不敏感）。
         *
         * 消费方：AgentSettings.codeThinkingLevel 启动恢复、长任务模板
         * recommendedThinkingLevel 落地、复制链档位覆盖。未知/空值返回
         * null，由调用方兜底（STANDARD），历史脏值（如 agent 侧遗留的
         * 拼写）不致崩溃。
         */
        fun fromName(name: String?): CodeThinkingLevel? =
            name?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { s -> entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
    }
}
