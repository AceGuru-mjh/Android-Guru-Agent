package com.apex.agent.core.llm

/**
 * LLM配置：支持任何OpenAI兼容API
 * 
 * 兼容列表：
 * - OpenAI (api.openai.com/v1)
 * - Claude via proxy
 * - Gemini via proxy
 * - Ollama (localhost:11434/v1)
 * - vLLM
 * - LM Studio
 * - 任何 /v1/chat/completions 兼容端点
 */
data class LlmConfig(
    /** API基础URL，如 "https://api.openai.com/v1" */
    val baseUrl: String = "",
    
    /** API密钥 */
    val apiKey: String = "",
    
    /** 模型名称，如 "gpt-4o", "claude-3-5-sonnet", "qwen2.5:72b" */
    val model: String = "",
    
    /** 温度 */
    val temperature: Float = 0.7f,
    
    /** 最大输出token */
    val maxTokens: Int = 4096,
    
    /** 是否启用流式 */
    val streaming: Boolean = true,
    
    /** 超时秒数 */
    val timeoutSeconds: Long = 120,
    
    /** 自定义请求头（某些API需要额外header）*/
    val customHeaders: Map<String, String> = emptyMap(),
    
    /** 系统提示词前缀 */
    val systemPromptPrefix: String = "",

    /**
     * 瞬时故障自动重试次数（HTTP 408/429/5xx 或网络错误）。
     * 0 = 不重试。重试只发生在请求建立阶段（流式响应收到第一个 SSE 分片前），
     * 流已经开始后的中断不会重试，避免重复消耗 token。
     */
    val maxRetries: Int = 2,

    /** 重试退避基础延迟（毫秒）；每次尝试翻倍，含随机抖动，上限 8 秒 */
    val retryBaseDelayMs: Long = 500,

    /**
     * 模型原生思考强度。控制 OpenAI o-series 的 `reasoning_effort` 字段，
     * 或 DeepSeek-R1 / Qwen3-thinking / GLM-Z1 等模型的原生思考预算。
     *
     * - NONE：不发送 reasoning_effort（模型默认行为）
     * - LOW / MEDIUM / HIGH / MAX：映射到 OpenAI 的
     *   "low" / "medium" / "high"（MAX 在支持扩展 thinking budget 的模型上
     *   会同时设置 max_completion_tokens 为更高值）。
     *
     * 与 AgentConfig.thinkingLevel 的区别：
     * - thinkingLevel 只影响 system prompt 文本，对任何模型都适用
     * - reasoningEffort 是模型 API 原生参数，仅对支持思考模式的模型生效
     */
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE
) {
    val isValid: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    
    companion object {
        /** 预设：OpenAI */
        fun openai(apiKey: String, model: String = "gpt-4o") = LlmConfig(
            baseUrl = "https://api.openai.com/v1",
            apiKey = apiKey,
            model = model
        )
        
        /** 预设：Ollama本地 */
        fun ollama(model: String = "qwen2.5:72b") = LlmConfig(
            baseUrl = "http://10.0.2.2:11434/v1",  // Android模拟器访问宿主机
            apiKey = "ollama",  // Ollama不需要真实key
            model = model
        )
        
        /** 预设：OpenRouter */
        fun openRouter(apiKey: String, model: String = "anthropic/claude-3.5-sonnet") = LlmConfig(
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = apiKey,
            model = model
        )
        
        /** 预设：DeepSeek */
        fun deepseek(apiKey: String, model: String = "deepseek-chat") = LlmConfig(
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = apiKey,
            model = model
        )
        
        /** 预设：自定义 */
        fun custom(baseUrl: String, apiKey: String, model: String) = LlmConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model
        )
    }
}

/**
 * 模型原生思考强度。
 *
 * 适用于 OpenAI o1/o3/o4 系列、DeepSeek-R1 / DeepSeek-V3.1-thinking、
 * Qwen3-thinking、GLM-Z1 等支持原生思考模式的模型。
 *
 * 不支持的模型会忽略此参数（不会报错）。
 */
enum class ReasoningEffort(val apiValue: String?, val displayName: String) {
    /** 不发送 reasoning_effort 字段（模型默认行为） */
    NONE(null, "默认"),

    /** 低强度思考 — 快速、省 token */
    LOW("low", "Low"),

    /** 中等强度思考 — 平衡 */
    MEDIUM("medium", "Medium"),

    /** 高强度思考 — 深度推理 */
    HIGH("high", "High"),

    /**
     * 最大强度思考。
     * - OpenAI o-series：映射到 "high" + 提升 max_completion_tokens
     * - DeepSeek-R1：映射到 thinking_budget = 16384
     * - 其他模型：映射到 "high"
     */
    MAX("high", "Max");

    companion object {
        fun fromName(name: String?): ReasoningEffort =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}
