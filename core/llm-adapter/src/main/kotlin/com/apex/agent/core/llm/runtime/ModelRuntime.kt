package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelRole
import com.apex.agent.core.llm.ToolChoiceSpec
import com.apex.agent.core.llm.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * T72 §八 — 模型运行时统一执行入口。
 *
 * Agent Engine 不再直接 `client.chat(...)`，而是：
 *
 * ```
 * modelRuntime.chatStream(context, messages, tools, temperature)
 * modelRuntime.chat(context, messages, tools, temperature)
 * ```
 *
 * 实现负责：角色路由 → 能力校验 → Client 选择 → 请求执行 → 失败降级
 * （§十三）→ 运行时统计（§十五）→ 请求 trace（§十六）。
 *
 * [LlmRequestContext] 携带角色、所需能力、调用原因、task/step/request id。
 */
interface ModelRuntime {

    /**
     * 非流式请求。可能抛 [ModelRuntimeException]（含降级耗尽 / 能力不匹配等）。
     *
     * 采样参数哨兵回退制（B1/B2 修复）：[temperature] / [maxTokens] 默认为
     * 哨兵（< 0 = 未指定），透传给 [LlmClient] 后由实现层回退到 Profile
     * （LlmConfig）值——引擎不再显式传 AgentConfig 快照，设置页改参数对
     * 下一次请求真实生效；需要强制覆盖的场景（摘要压缩低温）传显式值。
     */
    suspend fun chat(
        context: LlmRequestContext,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition> = emptyList(),
        /** < 0（默认 -1）= 未指定 → 用 Profile 值；>= 0 = 显式覆盖。 */
        temperature: Float = -1f,
        /** < 0（默认 -1）= 未指定 → 用 Profile 值（皆未设置时回退 4096）。 */
        maxTokens: Int = -1
    ): LlmResponse

    /** 流式请求。Flow 内可能抛 [ModelRuntimeException]。 */
    fun chatStream(
        context: LlmRequestContext,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition> = emptyList(),
        /** < 0（默认 -1）= 未指定 → 用 Profile 值；>= 0 = 显式覆盖。 */
        temperature: Float = -1f,
        /** < 0（默认 -1）= 未指定 → 用 Profile 值（皆未设置时回退 4096）。 */
        maxTokens: Int = -1
    ): Flow<LlmStreamChunk>

    // ═══ Tool System v4 — per-request tool choice overloads ═══
    // 默认实现透传 legacy 路径（无 tool_choice 覆盖）。

    suspend fun chat(
        context: LlmRequestContext,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        toolChoice: ToolChoiceSpec?
    ): LlmResponse = chat(context, messages, tools, temperature, maxTokens)

    fun chatStream(
        context: LlmRequestContext,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        toolChoice: ToolChoiceSpec?
    ): Flow<LlmStreamChunk> = chatStream(context, messages, tools, temperature, maxTokens)

    /** 当前全部 Profile 的运行时快照（§十五），不含 API Key。 */
    fun snapshot(): List<ModelRuntimeDiagnostics.ModelRuntimeSnapshot>

    /**
     * 解析角色 → 候选 Profile 链（**不执行**）。供 UI / 诊断展示
     * "VISION 会路由到哪个模型、fallback 是谁"。
     */
    fun resolve(
        role: ModelRole,
        requiredCapabilities: ModelCapabilities = ModelCapabilities(text = true)
    ): ModelRoleRouter.ResolutionResult
}

/**
 * 单 Client 运行时——向后兼容与测试用的最小实现。
 *
 * - 把所有角色都路由到同一个 [client]，不做能力校验、不做 fallback。
 * - 行为与 T72 之前的"一个 Engine = 一个固定 LlmClient"完全等价。
 *
 * Agent Engine / Compressor 的构造函数把 [ModelRuntime] 参数默认为 null，
 * null 时回退到 `SingleClientModelRuntime(llmClient)`——这样：
 *  1. 现有所有基于 `FakeLlmClient` 的测试无需改动即可继续通过；
 *  2. 生产环境由 DI 注入 [DefaultModelRuntime]，获得完整多模型能力。
 */
class SingleClientModelRuntime(
    private val client: LlmClient
) : ModelRuntime {

    override suspend fun chat(
        context: LlmRequestContext,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse = client.chat(messages, tools, temperature, maxTokens)

    override fun chatStream(
        context: LlmRequestContext,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): Flow<LlmStreamChunk> = client.chatStream(messages, tools, temperature, maxTokens)

    // v4：强制函数调用透传（否则 legacy 单 client 路径会丢失 tool_choice）。

    override suspend fun chat(
        context: LlmRequestContext,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        toolChoice: ToolChoiceSpec?
    ): LlmResponse = client.chat(messages, tools, temperature, maxTokens, toolChoice)

    override fun chatStream(
        context: LlmRequestContext,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        toolChoice: ToolChoiceSpec?
    ): Flow<LlmStreamChunk> =
        client.chatStream(messages, tools, temperature, maxTokens, toolChoice)

    override fun snapshot(): List<ModelRuntimeDiagnostics.ModelRuntimeSnapshot> = emptyList()

    override fun resolve(
        role: ModelRole,
        requiredCapabilities: ModelCapabilities
    ): ModelRoleRouter.ResolutionResult {
        // 单 client 无法做真实解析；返回一个占位 Success 表明"会路由到这个 client"
        return ModelRoleRouter.ResolutionResult.Failure(
            ModelRuntimeException.ModelConfigurationError(
                "SingleClientModelRuntime 不支持 resolve（单 client 回退模式）"
            )
        )
    }
}
