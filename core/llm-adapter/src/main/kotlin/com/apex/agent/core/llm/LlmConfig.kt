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

    /** API密钥（单 client 默认取 Provider 的第一个 Key；Key 轮换由上层处理） */
    val apiKey: String = "",

    /** 模型名称，如 "gpt-4o", "claude-3-5-sonnet", "qwen2.5:72b" */
    val model: String = "",

    /** 温度 */
    val temperature: Float = 0.7f,

    /** 最大输出token */
    val maxTokens: Int = 4096,

    /** 是否启用流式 */
    val streaming: Boolean = true,

    /** 读超时秒数（旧字段，等价于 readTimeoutMs/1000，保留以兼容旧调用） */
    val timeoutSeconds: Long = 120,

    /** 自定义请求头（某些API需要额外header）*/
    val customHeaders: Map<String, String> = emptyMap(),

    /** 系统提示词前缀 */
    val systemPromptPrefix: String = "",

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
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,

    // ── Sampling（完整采样参数）─────────────────────────────────
    val topP: Float = 1.0f,
    /** 0 = disabled */
    val topK: Int = 0,
    val minP: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val repetitionPenalty: Float = 1.0f,
    /** null = Auto（不固定种子） */
    val seed: Long? = null,
    val stopSequences: List<String> = emptyList(),

    // ── Reasoning 扩展 ─────────────────────────────────────────
    /** 思维预算（tokens），null = Auto。与 [reasoningEffort] 不强行绑定。 */
    val thinkingBudget: Int? = null,
    val showThinking: Boolean = true,

    // ── Context ────────────────────────────────────────────────
    val contextWindow: Int = 128_000,
    /** 为 Agent 运行保留的输出预算 */
    val reservedOutputTokens: Int = 4096,

    // ── Network ────────────────────────────────────────────────
    val connectTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 120_000,
    val writeTimeoutMs: Long = 30_000,
    val requestTimeoutMs: Long = 120_000,
    val retryCount: Int = 2,
    val retryDelayMs: Long = 1_000,
    val maxRetryDelayMs: Long = 10_000,
    val retryOnCodes: Set<Int> = setOf(408, 429, 500, 502, 503, 504),
    val keepAlive: Boolean = true,

    // ── Tools ──────────────────────────────────────────────────
    val enableTools: Boolean = true,
    val toolChoice: ToolChoiceMode = ToolChoiceMode.AUTO,
    val parallelToolCalls: Boolean = true,
    val maxToolCalls: Int = 10,
    val toolTimeoutSeconds: Int = 30,
    val maxToolResultTokens: Int = 4096,

    // ── Structured Output ──────────────────────────────────────
    val structuredOutputMode: StructuredOutputMode = StructuredOutputMode.TEXT,
    val structuredOutputStrict: Boolean = false,

    // ── Capabilities ───────────────────────────────────────────
    val capabilities: ModelCapabilities = ModelCapabilities(),
) {
    val isValid: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank() &&
            // 强制 http/https scheme：旧实现仅检查非空，导致 file://、ftp://、
            // 或无 scheme 的字符串通过校验，延迟到 OkHttp 构造 Request 时才以
            // 原始 IllegalArgumentException 抛出，用户难以定位。
            (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
    
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

        /**
         * 由 [ModelProfile] + 其挂载的 [ProviderConfig] 转换为运行时 [LlmConfig]。
         *
         * - apiKey 取 Provider 的第一个 Key（多 Key 轮换由上层 client 管理，不在单 [LlmConfig] 表达）。
         * - customHeaders = Provider.defaultHeaders + Profile.customHeaders（Profile 优先）。
         * - timeoutSeconds 与 readTimeoutMs 保持同步（旧字段兼容）。
         */
        fun fromProfile(profile: ModelProfile, provider: ProviderConfig?): LlmConfig {
            val headers = buildMap {
                provider?.defaultHeaders?.let { putAll(it) }
                putAll(profile.customHeaders)
            }
            return LlmConfig(
                baseUrl = provider?.baseUrl?.takeIf { it.isNotBlank() } ?: "",
                apiKey = provider?.apiKeys?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "",
                model = profile.modelId,
                temperature = profile.temperature,
                maxTokens = profile.maxOutputTokens,
                streaming = profile.streaming,
                timeoutSeconds = profile.readTimeoutMs / 1000,
                customHeaders = headers,
                systemPromptPrefix = profile.systemPromptPrefix,
                reasoningEffort = profile.reasoningEffort,
                topP = profile.topP,
                topK = profile.topK,
                minP = profile.minP,
                frequencyPenalty = profile.frequencyPenalty,
                presencePenalty = profile.presencePenalty,
                repetitionPenalty = profile.repetitionPenalty,
                seed = profile.seed,
                stopSequences = profile.stopSequences,
                thinkingBudget = profile.thinkingBudget,
                showThinking = profile.showThinking,
                contextWindow = profile.contextWindow,
                reservedOutputTokens = profile.reservedOutputTokens,
                connectTimeoutMs = profile.connectTimeoutMs,
                readTimeoutMs = profile.readTimeoutMs,
                writeTimeoutMs = profile.writeTimeoutMs,
                requestTimeoutMs = profile.requestTimeoutMs,
                retryCount = profile.retryCount,
                retryDelayMs = profile.retryDelayMs,
                maxRetryDelayMs = profile.maxRetryDelayMs,
                retryOnCodes = profile.retryOnCodes,
                keepAlive = profile.keepAlive,
                enableTools = profile.enableTools,
                toolChoice = profile.toolChoice,
                parallelToolCalls = profile.parallelToolCalls,
                maxToolCalls = profile.maxToolCalls,
                toolTimeoutSeconds = profile.toolTimeoutSeconds,
                maxToolResultTokens = profile.maxToolResultTokens,
                structuredOutputMode = profile.structuredOutputMode,
                structuredOutputStrict = profile.structuredOutputStrict,
                capabilities = profile.capabilities,
            )
        }
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
