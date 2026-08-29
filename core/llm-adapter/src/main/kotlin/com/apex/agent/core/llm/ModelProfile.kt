package com.apex.agent.core.llm

import kotlinx.serialization.Serializable

/**
 * 模型能力标记。
 *
 * 每个 [ModelProfile] 记录该模型具备的能力，便于 Agent 运行时自动选择正确模型
 * （例如视觉任务选 Vision 模型、需要工具调用时校验 ToolCalling）。
 */
@Serializable
data class ModelCapabilities(
    val text: Boolean = true,
    val vision: Boolean = false,
    val toolCalling: Boolean = true,
    val structuredOutput: Boolean = false,
    val streaming: Boolean = true,
    val reasoning: Boolean = false,
    val jsonMode: Boolean = false,
    val imageInput: Boolean = false,
    val longContext: Boolean = false,
) {
    fun summary(): String = buildList {
        if (text) add("Text")
        if (vision) add("Vision")
        if (toolCalling) add("Tools")
        if (structuredOutput) add("JSON")
        if (reasoning) add("Reason")
        if (longContext) add("LongCtx")
    }.joinToString(" · ")
}

/** 响应结构化输出模式。 */
@Serializable
enum class StructuredOutputMode { TEXT, JSON, JSON_SCHEMA }

/** 工具选择策略（对应 OpenAI chat completion 的 `tool_choice`）。 */
@Serializable
enum class ToolChoiceMode { AUTO, REQUIRED, NONE }

/** Provider 鉴权类型。 */
@Serializable
enum class AuthType { BEARER, API_KEY_QUERY, CUSTOM }

/**
 * 多 API Key 轮换策略。Agent 长任务遇到限流/配额错误时自动切换下一个 Key。
 */
@Serializable
enum class KeyRotationMode {
    DISABLED,    // 不轮换
    SEQUENTIAL,  // 每轮依次使用
    ON_ERROR,    // 请求失败时切换
    ON_RATE_LIMIT // 仅遇到 429/quota 时切换
}

/**
 *  Provider（服务商）配置。
 *
 *  Provider 与 [ModelProfile] 解耦：一个 Provider 可挂载多个模型 Profile，
 *  Profile 仅持有 [ProviderConfig.id] 引用。这样切换 Base URL / 运营商时无需改动每个模型。
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val authType: AuthType = AuthType.BEARER,
    /** 支持多个 Key（[KeyRotationMode] 决定如何轮换）。 */
    val apiKeys: List<String> = emptyList(),
    /** 多 Key 轮换策略（数据预埋：客户端轮换逻辑由 LLM client 层后续接入）。 */
    val keyRotationMode: KeyRotationMode = KeyRotationMode.DISABLED,
    val organization: String = "",
    val project: String = "",
    /** Provider 级默认请求头；会与 Profile 级 [ModelProfile.customHeaders] 合并（Profile 优先）。 */
    val defaultHeaders: Map<String, String> = emptyMap(),
    val capabilities: ModelCapabilities = ModelCapabilities(),
    /** 内置 Provider（OpenAI/DeepSeek/Ollama 等）不可被普通删除。 */
    val isBuiltIn: Boolean = false,
)

/**
 * 模型配置档案（Model Profile）。
 *
 * 这是设置中心的核心领域模型，覆盖 Sampling / Reasoning / Context / Network /
 * Tools / Structured Output 等全部维度。持久化由 [com.apex.agent.ui.screen.settings.SettingsRepository]
 * 负责（JSON 序列化）。运行时通过 [LlmConfig.Companion.fromProfile] 转换为 [LlmConfig]。
 */
@Serializable
data class ModelProfile(
    val id: String,
    val name: String,
    val providerId: String,
    val modelId: String,

    // ── Sampling ──────────────────────────────────────────────
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    /** 0 = disabled。本地模型（Qwen/Gemma/llama.cpp）常用。 */
    val topK: Int = 0,
    val minP: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val repetitionPenalty: Float = 1.0f,
    /** null = Auto（不固定随机种子）。 */
    val seed: Long? = null,
    val stopSequences: List<String> = emptyList(),

    // ── Reasoning ─────────────────────────────────────────────
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    /** 思维预算（tokens）。null = Auto。与 [reasoningEffort] 不强行绑定（不同 Provider 语义不同）。 */
    val thinkingBudget: Int? = null,
    val showThinking: Boolean = true,

    // ── Context ───────────────────────────────────────────────
    /** 模型标称上下文窗口（tokens），如 128000 / 200000 / 1000000。 */
    val contextWindow: Int = 128_000,
    val maxOutputTokens: Int = 4096,
    /** 为 Agent 运行保留的输出预算（System Prompt + Tools + History 等），避免上下文占满。 */
    val reservedOutputTokens: Int = 4096,

    // ── Network ───────────────────────────────────────────────
    val connectTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 120_000,
    val writeTimeoutMs: Long = 30_000,
    val requestTimeoutMs: Long = 120_000,
    val retryCount: Int = 2,
    val retryDelayMs: Long = 1_000,
    val maxRetryDelayMs: Long = 10_000,
    val retryOnCodes: Set<Int> = setOf(408, 429, 500, 502, 503, 504),
    val streaming: Boolean = true,
    val keepAlive: Boolean = true,

    // ── Tools ─────────────────────────────────────────────────
    val enableTools: Boolean = true,
    val toolChoice: ToolChoiceMode = ToolChoiceMode.AUTO,
    val parallelToolCalls: Boolean = true,
    val maxToolCalls: Int = 10,
    val toolTimeoutSeconds: Int = 30,
    val maxToolResultTokens: Int = 4096,

    // ── Structured Output ─────────────────────────────────────
    val structuredOutputMode: StructuredOutputMode = StructuredOutputMode.TEXT,
    val structuredOutputStrict: Boolean = false,

    // ── Prompt / Headers ──────────────────────────────────────
    val systemPromptPrefix: String = "",
    val customHeaders: Map<String, String> = emptyMap(),

    // ── Capabilities（覆盖 Provider 级能力标记）─────────────────
    val capabilities: ModelCapabilities = ModelCapabilities(),

    val isDefault: Boolean = false,
) {
    fun displayContext(): String = when {
        contextWindow >= 1_000_000 -> "1M"
        contextWindow >= 200_000 -> "200K"
        contextWindow >= 128_000 -> "128K"
        contextWindow >= 64_000 -> "64K"
        contextWindow >= 32_000 -> "32K"
        else -> "${contextWindow / 1000}K"
    }
}

/**
 * 多模型角色映射。
 *
 * Android Agent 普遍采用主模型 + 辅助模型架构：主模型跑 Agent loop，
 * 辅助模型负责视觉分析 / 上下文压缩 / 网页总结 / session 检索等。
 * 此处仅持久化"角色 → Profile id"映射；真正的多 client 路由由 Agent 引擎后续接入。
 */
@Serializable
data class ModelRoleConfig(
    val primaryProfileId: String = "",
    val visionProfileId: String = "",
    val reasoningProfileId: String = "",
    val fastProfileId: String = "",
    val summaryProfileId: String = "",
) {
    fun profileIdFor(role: ModelRole): String = when (role) {
        ModelRole.PRIMARY -> primaryProfileId
        ModelRole.VISION -> visionProfileId
        ModelRole.REASONING -> reasoningProfileId
        ModelRole.FAST -> fastProfileId
        ModelRole.SUMMARY -> summaryProfileId
    }
}

enum class ModelRole(val label: String) {
    PRIMARY("Primary Agent"),
    VISION("Vision Model"),
    REASONING("Reasoning Model"),
    FAST("Fast Model"),
    SUMMARY("Summary Model"),
}

/** 内置 Provider 与默认 Profile 种子数据。 */
object ModelProfileDefaults {

    private fun provider(
        id: String,
        displayName: String,
        baseUrl: String,
        capabilities: ModelCapabilities = ModelCapabilities(),
    ) = ProviderConfig(
        id = id,
        displayName = displayName,
        baseUrl = baseUrl,
        capabilities = capabilities,
        isBuiltIn = true,
    )

    val builtInProviders: List<ProviderConfig> = listOf(
        provider("openai", "OpenAI", "https://api.openai.com/v1",
            ModelCapabilities(text = true, vision = true, toolCalling = true,
                structuredOutput = true, streaming = true, reasoning = true, jsonMode = true, longContext = true)),
        provider("anthropic", "Anthropic", "https://api.anthropic.com/v1",
            ModelCapabilities(text = true, vision = true, toolCalling = true, streaming = true, reasoning = true, longContext = true)),
        provider("google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            ModelCapabilities(text = true, vision = true, toolCalling = true, structuredOutput = true, streaming = true, reasoning = true, longContext = true)),
        provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1",
            ModelCapabilities(text = true, toolCalling = true, streaming = true, reasoning = true)),
        provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            ModelCapabilities(text = true, vision = true, toolCalling = true, structuredOutput = true, streaming = true, reasoning = true, longContext = true)),
        provider("ollama", "Ollama", "http://localhost:11434/v1",
            ModelCapabilities(text = true, vision = true, toolCalling = true, streaming = true)),
        provider("lmstudio", "LM Studio", "http://localhost:1234/v1",
            ModelCapabilities(text = true, vision = true, toolCalling = true, streaming = true)),
        provider("vllm", "vLLM", "http://localhost:8000/v1",
            ModelCapabilities(text = true, toolCalling = true, streaming = true)),
        provider("custom_openai", "Custom OpenAI Compatible", "https://",
            ModelCapabilities(text = true, toolCalling = true, streaming = true)),
    )

    fun defaultProfiles(providers: List<ProviderConfig>): List<ModelProfile> {
        val openai = providers.firstOrNull { it.id == "openai" }
        val deepseek = providers.firstOrNull { it.id == "deepseek" }
        val ollama = providers.firstOrNull { it.id == "ollama" }
        val list = mutableListOf<ModelProfile>()
        openai?.let {
            list += ModelProfile(
                id = "default-gpt", name = "GPT (Default)", providerId = it.id,
                modelId = "gpt-4o-mini", isDefault = true,
                capabilities = ModelCapabilities(text = true, vision = true, toolCalling = true,
                    structuredOutput = true, streaming = true, jsonMode = true, longContext = true),
            )
        }
        deepseek?.let {
            list += ModelProfile(
                id = "default-deepseek", name = "DeepSeek Reason", providerId = it.id,
                modelId = "deepseek-chat",
                capabilities = ModelCapabilities(text = true, toolCalling = true, streaming = true, reasoning = true),
            )
        }
        ollama?.let {
            list += ModelProfile(
                id = "default-local", name = "Local (Ollama)", providerId = it.id,
                modelId = "qwen2.5:7b", topK = 40, minP = 0.05f, repetitionPenalty = 1.1f,
                capabilities = ModelCapabilities(text = true, vision = true, toolCalling = true, streaming = true),
            )
        }
        return list
    }

    fun defaultRoles(profiles: List<ModelProfile>): ModelRoleConfig {
        val primary = profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull()
        return ModelRoleConfig(
            primaryProfileId = primary?.id ?: "",
            visionProfileId = profiles.firstOrNull { it.capabilities.vision }?.id ?: (primary?.id ?: ""),
            reasoningProfileId = profiles.firstOrNull { it.capabilities.reasoning }?.id ?: (primary?.id ?: ""),
            fastProfileId = primary?.id ?: "",
            summaryProfileId = primary?.id ?: "",
        )
    }
}
