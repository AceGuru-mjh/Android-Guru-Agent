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
    val systemPromptPrefix: String = ""
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
