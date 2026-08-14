package com.apex.agent.core.engine

/**
 * Agent执行模式
 */
enum class AgentMode(val displayName: String, val description: String) {
    /**
     * 构建模式：边想边做，实时执行
     * 适合简单任务、快速响应
     */
    BUILD("Build", "边想边做，实时执行"),

    /**
     * 规划模式：先制定完整计划，用户确认后再执行
     * 适合复杂任务、多步骤操作
     */
    PLAN("Plan", "先制定完整计划，确认后再执行"),

    /**
     * 规格模式：先产出需求规格（目标 / 需求 / 约束 / 验收标准 / 交付物），
     * 用户确认后再逐项执行。比 Plan 更强调"做什么、做成什么样才算完成"。
     */
    SPEC("Spec", "先产出需求规格，确认后再执行"),

    /**
     * 反思模式：Agent 通过"生成 → 评审 → 修正"循环自我审视并改进输出，
     * 适合代码生成、内容创作等对质量要求高的场景。
     */
    REFLECTION("Reflect", "生成 → 评审 → 修正，提高输出质量"),

    /**
     * 人工协助模式：Agent 遇到多种选择（方案 / 目标 / 偏好）时，
     * 强制弹出选项菜单交由人工决策，不擅自猜测。
     */
    HUMAN_ASSIST("Assist", "遇多选时弹出选项菜单，人工决策"),

    /**
     * 自定义模式：附加用户自定义指令（如输出格式 / 语言 / 行为约束），
     * 指令持久化保存并拼入 system prompt。
     */
    CUSTOM("Custom", "附加自定义指令运行")
}

/**
 * 思考深度等级
 * 控制Agent在每次决策前的推理深度
 */
enum class ThinkingLevel(val level: Int, val description: String) {
    /** 不思考，直接行动 */
    NONE(0, "直接执行，不生成推理过程"),
    
    /** 浅思考：1-2句简短推理 */
    LIGHT(1, "简短分析后立即行动"),
    
    /** 标准思考：正常推理链 */
    STANDARD(2, "分析任务→选择方案→执行"),
    
    /** 深思考：多方案比较 */
    DEEP(3, "多方案对比→风险评估→最优选择"),
    
    /** 极深思考：完整思维链+自我质疑 */
    MAXIMUM(4, "完整推理链+自我反思+多轮验证");
    
    /**
     * 转换为 system prompt 中的思考指令。
     *
     * 推理框架参考自失败项目 [Apex-agent] 的 ChainOfThoughtSkill / TreeOfThoughtsSkill /
     * ReActSkill：将其中"分解-逐步推理-综合"与"多路径探索评估"的结构化骨架提炼为
     * 思考提示词，融入本项目的思考深度控制。仅调提示词文本，不改引擎主循环。
     */
    fun toPromptInstruction(): String = when (this) {
        NONE -> ""
        LIGHT -> "Briefly think about what to do next in 1-2 sentences, then act."
        STANDARD -> """
            Use Chain-of-Thought before acting:
            1. Break the task into clear sub-steps.
            2. For the current step, reason step by step what to do and which tool to use.
            3. Execute, then observe the result before the next step.
        """.trimIndent()
        DEEP -> """
            Reason carefully using a multi-path approach before acting:
            1. Decompose the task into sequential sub-problems.
            2. For the key decision, generate at least 2-3 candidate approaches (branch out, do not commit early).
            3. Evaluate each on feasibility, efficiency, risk, and side effects.
            4. Critique: "What could go wrong? What am I missing?"
            5. Pick the best approach, explain why, then execute the first step and observe.
        """.trimIndent()
        MAXIMUM -> """
            Perform exhaustive reasoning before any action:
            1. Fully understand the task and constraints.
            2. Decompose into sub-problems; for each, enumerate ALL plausible solution paths (Tree-of-Thoughts style).
            3. Score each path on feasibility, efficiency, risk, and side effects.
            4. Consider edge cases and failure modes.
            5. Self-critique: "What could go wrong? Am I missing something?"
            6. Synthesize the optimal execution plan from the best path.
            7. Only then, execute the first step, observe, and re-evaluate if the outcome diverges.
        """.trimIndent()
    }
    
    /**
     * 对应的thinking_budget（用于支持此参数的模型如Gemini）
     */
    fun toThinkingBudget(): Int? = when (this) {
        NONE -> 0
        LIGHT -> 256
        STANDARD -> 1024
        DEEP -> 4096
        MAXIMUM -> 16384
    }
}

/**
 * Agent配置
 */
data class AgentConfig(
    /** 执行模式 */
    val mode: AgentMode = AgentMode.BUILD,
    
    /** 思考深度 */
    val thinkingLevel: ThinkingLevel = ThinkingLevel.STANDARD,
    
    /** 最大迭代次数（防止无限循环）*/
    val maxIterations: Int = 25,
    
    /** 最大context tokens（超出触发压缩）*/
    val maxContextTokens: Int = 128000,
    
    /** 压缩触发阈值（占maxContextTokens的比例）*/
    val compressionThreshold: Float = 0.8f,
    
    /** 保留最近N轮不压缩 */
    val preserveRecentTurns: Int = 5,
    
    /** 工具输出最大长度（超出截断）*/
    val maxToolOutputLength: Int = 2000,
    
    /** 是否流式输出 */
    val streaming: Boolean = true,
    
    /** 温度 */
    val temperature: Float = 0.7f,
    
    /** 模型名称覆盖 */
    val modelOverride: String? = null,

    /**
     * 反思模式轮数（[AgentMode.REFLECTION] 生效）：每轮执行一次
     * "评审 → 修正"。默认 1 轮（生成 → 评审 → 修正）。
     */
    val reflectionRounds: Int = 1,

    /**
     * 自定义模式附加指令（[AgentMode.CUSTOM] 生效）：
     * 原样拼入 system prompt 的 "## Custom Instructions" 段落。
     */
    val customInstruction: String? = null
) {
    companion object {
        /** 快速模式：Build + 无思考 */
        val QUICK = AgentConfig(
            mode = AgentMode.BUILD,
            thinkingLevel = ThinkingLevel.NONE,
            maxIterations = 10
        )
        
        /** 标准模式：Build + 标准思考 */
        val STANDARD = AgentConfig(
            mode = AgentMode.BUILD,
            thinkingLevel = ThinkingLevel.STANDARD
        )
        
        /** 谨慎模式：Plan + 深思考 */
        val CAREFUL = AgentConfig(
            mode = AgentMode.PLAN,
            thinkingLevel = ThinkingLevel.DEEP
        )
    }
}
