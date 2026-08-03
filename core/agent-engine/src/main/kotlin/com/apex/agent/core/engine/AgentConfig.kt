package com.apex.agent.core.engine

/**
 * Agent执行模式
 */
enum class AgentMode {
    /**
     * 规划模式：先制定完整计划，用户确认后再执行
     * 适合复杂任务、多步骤操作
     */
    PLAN,
    
    /**
     * 构建模式：边想边做，实时执行
     * 适合简单任务、快速响应
     */
    BUILD
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
     * 转换为system prompt中的思考指令
     */
    fun toPromptInstruction(): String = when (this) {
        NONE -> ""
        LIGHT -> "Briefly think about what to do next in 1-2 sentences, then act."
        STANDARD -> "Think step by step about the task. Analyze what needs to be done, choose the best tool, then execute."
        DEEP -> """
            Think deeply before acting:
            1. Analyze the current situation
            2. Consider at least 2-3 possible approaches
            3. Compare pros and cons of each
            4. Assess risks
            5. Choose the best approach and explain why
            Then execute.
        """.trimIndent()
        MAXIMUM -> """
            Perform exhaustive reasoning before any action:
            1. Fully understand the task and its requirements
            2. Break down into sub-problems
            3. For each sub-problem, enumerate ALL possible solutions
            4. Evaluate each solution on: feasibility, efficiency, risk, side effects
            5. Consider edge cases and failure modes
            6. Self-critique: "What could go wrong? Am I missing something?"
            7. Formulate the optimal execution plan
            8. Only then, execute the first step
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
    val modelOverride: String? = null
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
