package com.apex.agent.core.llm

import kotlinx.coroutines.flow.Flow

/**
 * LLM客户端统一接口
 * 支持OpenAI兼容API（覆盖OpenAI/Claude/Gemini/本地模型）
 *
 * 采样参数哨兵回退制（B1/B2 修复）：
 * - [temperature] / [maxTokens] 的默认值是**哨兵**（< 0 = 未指定），不再硬编码
 *   0.7f / 4096。实现层（如 [StreamingOpenAiClient]）在哨兵时回退到
 *   [LlmConfig] 的值（即 Profile 的 temperature / maxOutputTokens），由
 *   DynamicLlmClient / ModelRuntimeRegistry 监听设置变化即时重建 client——
 *   这样设置页或"小大脑"菜单改参数后，下一次请求即真实生效。
 *   旧行为：引擎每处调用都显式传 `temperature = config.temperature`（引擎
 *   AgentConfig 的快照），Profile 改动被覆盖，参数链路断裂。
 * - 调用方需要**强制覆盖**（如摘要压缩要低温短输出）时传显式值即可：
 *   `temperature >= 0` / `maxTokens > 0` 优先于 Profile 值。
 */
interface LlmClient {
    suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition> = emptyList(),
        /** < 0（默认 -1）= 未指定 → 用 Profile 值；>= 0 = 显式覆盖。 */
        temperature: Float = -1f,
        /** < 0（默认 -1）= 未指定 → 用 Profile 值（皆未设置时回退 4096）。 */
        maxTokens: Int = -1
    ): LlmResponse

    fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition> = emptyList(),
        /** < 0（默认 -1）= 未指定 → 用 Profile 值；>= 0 = 显式覆盖。 */
        temperature: Float = -1f,
        /** < 0（默认 -1）= 未指定 → 用 Profile 值（皆未设置时回退 4096）。 */
        maxTokens: Int = -1
    ): Flow<LlmStreamChunk>

    // ═══ Tool System v4 — per-request tool choice overloads ═══
    //
    // The v4 engine uses these to honour the chat input's "调用函数" selection
    // as FORCED tool use (tool_choice = required / specific function).
    // Default implementations delegate to the legacy methods with no
    // override, so every existing LlmClient implementation (fakes in tests,
    // DynamicLlmClient, NoOpLlmClient, …) keeps compiling and behaving.

    suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        toolChoice: ToolChoiceSpec?
    ): LlmResponse = chat(messages, tools, temperature, maxTokens)

    fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        toolChoice: ToolChoiceSpec?
    ): Flow<LlmStreamChunk> = chatStream(messages, tools, temperature, maxTokens)
}

sealed interface LlmMessage {
    data class System(val content: String) : LlmMessage
    data class User(val content: String, val images: List<ImageContent> = emptyList()) : LlmMessage
    data class Assistant(val content: String, val toolCalls: List<ToolCall> = emptyList()) : LlmMessage
    data class ToolResult(val toolCallId: String, val content: String) : LlmMessage
}

/**
 * 多模态输入图片。
 *
 * 支持两种形态（二选一，[url] 非空时优先）：
 * - base64 内联（本地文件压缩后上传，兼容性最好）；
 * - 远程 URL 直传（OpenAI `image_url.url` 原生支持 https 链接，
 *   免去本地重编码、显著降低请求体体积）。
 */
data class ImageContent(
    val base64Data: String,
    val mimeType: String = "image/jpeg",
    val detail: String = "auto",
    /** 远程图片直链（https://...）。非空时请求体直接传 URL，忽略 base64 字段。 */
    val url: String? = null
)

data class LlmResponse(
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: Usage? = null,
    /**
     * 模型生成的图片（多模态输出）。每个元素是可展示的 URL 或 `data:` URI。
     *
     * 覆盖的端点形态：
     * - OpenRouter / Gemini OpenAI 兼容层：`choices[].message.content` 为
     *   content-parts 数组，含 `image_url` part（`data:image/png;base64,...`）；
     * - GLM CogView 等 chat 式生图：`choices[].message.images: [{"url": ...}]`，
     *   或 `b64_json` 字段（自动包装成 `data:` URI）。
     */
    val images: List<String> = emptyList(),
    /** 模型生成的视频（多模态输出）：`message.video_url` / content-parts `video_url`。 */
    val videos: List<String> = emptyList()
)

data class LlmStreamChunk(
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val isFinish: Boolean = false,
    /**
     * 原生推理/思考内容（流式增量）。
     *
     * OpenAI o-series 返回 `delta.reasoning_content`；DeepSeek-R1 返回
     * `delta.reasoning_content`；部分 Anthropic 代理返回 `delta.reasoning`。
     * 旧实现完全丢弃该字段，导致思考类模型（R1/Qwen3-thinking/GLM-Z1）
     * 的思维链在 UI 上不可见。这里透传给上层（引擎）自行决定如何呈现
     * （如作为 [com.apex.agent.core.engine.AgentEvent.ThinkingChunk] 发射）。
     */
    val reasoningContent: String? = null,
    /**
     * 本 chunk 携带的生成图片（URL / `data:` URI）。与 [content] 可同时出现
     * （文本与图片分属不同 content-parts 依次流回）。
     */
    val images: List<String> = emptyList(),
    /** 本 chunk 携带的生成视频 URL（`video_url` part / `delta.video_url` 字段）。 */
    val videos: List<String> = emptyList()
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,  // JSON string
    /**
     * 流式片段在并行工具调用中的索引位置（OpenAI `tool_calls[].index`）。
     *
     * - 非流式响应：恒为 -1（不适用）。
     * - 流式响应：OpenAI 在并行工具调用的首个片段携带 `index` 与 `id`，
     *   后续片段只携带 `index` 而 `id` 为空。旧实现以 `id` 作为累加器键，
     *   导致后续片段被误开一个新累加器、参数被裁断、工具调用永远拼不成。
     *   现在透传 `index`，由累加侧在 `id` 为空时回退到 `index` 作为键。
     */
    val index: Int = -1
)

data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: String  // JSON Schema string
)
