package com.apex.agent.core.llm

import kotlinx.serialization.Serializable

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

    /**
     * 模型原生联网搜索（Provider-native web search）。
     *
     * - OFF（默认）：不发送任何原生搜索参数 —— 请求体与旧版完全一致，兼容所有端点；
     * - AUTO：按 baseUrl/providerId 自动选择各 Provider 的原生搜索策略（推断不出 → OFF）。
     *
     * 与 Agent 内置 web_search 工具（客户端 HTML 抓取，脆弱且常被反爬）互补：
     * 原生搜索由服务端执行并注入上下文，引用随响应返回（annotations/search_result）。
     * 详见 [WebSearchMode]。
     */
    val webSearch: WebSearchMode = WebSearchMode.OFF,

    // ── Structured Output ──────────────────────────────────────
    val structuredOutputMode: StructuredOutputMode = StructuredOutputMode.TEXT,
    val structuredOutputStrict: Boolean = false,
    /** JSON Schema 字符串（JSON_SCHEMA 模式下作为 `response_format.json_schema.schema` 发送）。 */
    val jsonSchema: String? = null,

    // ── Capabilities ───────────────────────────────────────────
    val capabilities: ModelCapabilities = ModelCapabilities(),

    // ── Provider 标识（T72 §二十二：用于按 provider 分支请求体构造）──────────
    /** 该 Profile 挂载的 Provider id。运行时按此判定是否发送 top_k/min_p/ repetition_penalty 等 provider 专有字段。 */
    val providerId: String = "",
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
                webSearch = profile.webSearch,
                structuredOutputMode = profile.structuredOutputMode,
                structuredOutputStrict = profile.structuredOutputStrict,
                jsonSchema = profile.jsonSchema,
                capabilities = profile.capabilities,
                providerId = profile.providerId,
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

/**
 * 模型原生联网搜索模式（Provider-native web search）。
 *
 * 背景：各家 Provider 的原生搜索参数形态互不兼容，发错端点会直接 400，
 * 故按 Provider 分支注入（由 [StreamingOpenAiClient.buildRequestBody] 消费）：
 *
 * - OFF：不发送任何原生搜索参数（默认，兼容所有端点 —— 含未知中转，防 400）
 * - AUTO：按 baseUrl/providerId 自动选择下述策略（推断不出 → OFF）
 * - OPENAI：`web_search_options`（gpt-4o-search-preview 系）
 * - DASHSCOPE：`enable_search: true`（阿里百炼 Qwen compatible-mode）
 * - ZHIPU：tools 数组追加 `{"type":"web_search",...}`（智谱 GLM server tool）
 * - OPENROUTER：`plugins: [{"id":"web"}]`（等价模型名 `:online` 后缀）
 * - ANTHROPIC：tools 数组追加 `web_search_20250305`（Anthropic server tool）
 * - DEEPSEEK：tools 数组追加 `{"type":"web_search"}`（Responses 兼容端点）
 *
 * 作为 [ModelProfile] 的序列化字段（带默认值 OFF，旧持久化 JSON 兼容），
 * 故标注 @Serializable（与 [ToolChoiceMode]/[StructuredOutputMode] 同风格）。
 */
@Serializable
enum class WebSearchMode(val displayName: String) {
    /** 不发送任何原生搜索参数（默认，兼容所有端点） */
    OFF("关闭"),

    /** 按 baseUrl/providerId 自动选择策略；无法识别的端点回退 OFF */
    AUTO("自动"),

    /** OpenAI Chat Completions 的 web_search_options（search-preview 系模型） */
    OPENAI("OpenAI web_search_options"),

    /** 阿里百炼 DashScope compatible-mode 的 enable_search 顶层开关 */
    DASHSCOPE("DashScope enable_search"),

    /** 智谱 GLM 的 web_search server-side tool（入 tools 数组） */
    ZHIPU("Zhipu web_search tool"),

    /** OpenRouter 的 web 插件（等价模型名 :online 后缀） */
    OPENROUTER("OpenRouter :online"),

    /** Anthropic 的 web_search_20250305 server-side tool */
    ANTHROPIC("Anthropic web_search tool"),

    /** DeepSeek Responses 兼容端点的 web_search server tool */
    DEEPSEEK("DeepSeek web_search tool");

    /**
     * 解析该模式在给定端点下实际生效的策略（实例入口，
     * 供 `config.webSearch.resolveFor(baseUrl, providerId)` 直接调用）。
     *
     * - 显式指定的模式（非 AUTO）原样生效 —— 用户手动选 OPENAI 即发 OpenAI 参数；
     * - AUTO 按 baseUrl（小写包含匹配）+ providerId 推断：
     *   openrouter.ai→OPENROUTER；api.openai.com 或 providerId=="openai"→OPENAI；
     *   dashscope→DASHSCOPE；bigmodel.cn 或 providerId 含 zhipu/glm→ZHIPU；
     *   anthropic→ANTHROPIC；deepseek→DEEPSEEK；
     * - 其余未知端点→OFF：不发送非标准参数（防 400 —— 自建中转/兼容网关
     *   对陌生命令普遍直接拒绝）。
     */
    fun resolveFor(baseUrl: String, providerId: String): WebSearchMode {
        if (this != AUTO) return this
        val url = baseUrl.lowercase()
        val pid = providerId.lowercase()
        return when {
            url.contains("openrouter.ai") -> OPENROUTER
            url.contains("api.openai.com") || pid == "openai" -> OPENAI
            url.contains("dashscope") -> DASHSCOPE
            url.contains("bigmodel.cn") || pid.contains("zhipu") || pid.contains("glm") -> ZHIPU
            url.contains("anthropic") -> ANTHROPIC
            url.contains("deepseek") -> DEEPSEEK
            else -> OFF
        }
    }

    companion object {
        /**
         * 静态入口（等价实例 [resolveFor]）：显式模式原样生效，AUTO 按端点推断。
         * 注：Kotlin 的 companion 成员不能通过实例调用，因此同时提供实例方法
         * （`mode.resolveFor(baseUrl, pid)`）与本静态形式两种入口。
         */
        fun resolveFor(mode: WebSearchMode, baseUrl: String, providerId: String): WebSearchMode =
            mode.resolveFor(baseUrl, providerId)

        /** 按枚举名解析（设置层持久化 / UI 透传用）；未知名称回退 OFF。 */
        fun fromName(name: String?): WebSearchMode =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}
