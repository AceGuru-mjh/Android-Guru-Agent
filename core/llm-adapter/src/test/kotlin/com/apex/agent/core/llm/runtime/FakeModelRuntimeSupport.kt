package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.core.llm.ToolDefinition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 测试用 [ModelRuntimeStore] —— 内存里持有 profiles/providers/roles，可随时改写
 * 以验证热更新 / 能力校验 / 降级等行为。**仅存在于 test source set**。
 */
class FakeModelRuntimeStore(
    profiles: List<ModelProfile> = emptyList(),
    providers: List<ProviderConfig> = emptyList(),
    roles: ModelRoleConfig = ModelRoleConfig()
) : ModelRuntimeStore {

    private val _profiles = MutableStateFlow(profiles)
    private val _providers = MutableStateFlow(providers)
    private val _roles = MutableStateFlow(roles)

    override val profiles = _profiles.asStateFlow()
    override val providers = _providers.asStateFlow()
    override val roles = _roles.asStateFlow()

    fun setProfiles(list: List<ModelProfile>) { _profiles.value = list }
    fun setProviders(list: List<ProviderConfig>) { _providers.value = list }
    fun setRoles(roles: ModelRoleConfig) { _roles.value = roles }
}

/**
 * 测试用 [LlmClient] —— 按"调用次数"返回预设响应，或抛预设异常。
 * 记录每次调用的 messages/tools/temperature，供断言。
 *
 * 与 :core:agent-engine 的 [com.apex.agent.core.engine.orchestrator.FakeLlmClient]
 * 风格一致，但本类供 llm-adapter runtime 测试使用（不同模块的 test 源集互不可见）。
 */
class FakeLlmClient(
    private val responses: List<Any> = emptyList(),  // LlmResponse 或 Throwable
    private val streamResponses: List<List<LlmStreamChunk>> = emptyList(),
    private val delayMs: Long = 0L
) : LlmClient {

    private val _chatCalls = mutableListOf<Pair<List<LlmMessage>, List<ToolDefinition>>>()
    val chatCalls: List<Pair<List<LlmMessage>, List<ToolDefinition>>> get() = _chatCalls.toList()

    private val _streamCalls = mutableListOf<Pair<List<LlmMessage>, List<ToolDefinition>>>()
    val streamCalls: List<Pair<List<LlmMessage>, List<ToolDefinition>>> get() = _streamCalls.toList()

    @Volatile private var chatIndex = 0
    @Volatile private var streamIndex = 0

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse {
        _chatCalls.add(messages to tools)
        if (delayMs > 0) delay(delayMs)
        val scripted = responses.getOrNull(chatIndex++)
        return when (scripted) {
            is LlmResponse -> scripted
            is Throwable -> throw scripted
            null -> LlmResponse(content = "default-${chatIndex}")
            else -> LlmResponse(content = scripted.toString())
        }
    }

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): Flow<LlmStreamChunk> = flow {
        _streamCalls.add(messages to tools)
        if (delayMs > 0) delay(delayMs)
        val scripted = streamResponses.getOrNull(streamIndex++)
        if (scripted != null) {
            scripted.forEach { emit(it) }
        } else {
            emit(LlmStreamChunk(content = "default-stream-${streamIndex}", isFinish = true))
        }
    }
}

/**
 * 可被测试覆盖 chatStream 的开放 [FakeLlmClient]（流式降级测试需要抛 mid-stream 异常）。
 */
open class OpenFakeLlmClient : LlmClient {
    val chatCalls = mutableListOf<Pair<List<LlmMessage>, List<ToolDefinition>>>()
    val streamCalls = mutableListOf<Pair<List<LlmMessage>, List<ToolDefinition>>>()
    override suspend fun chat(messages: List<LlmMessage>, tools: List<ToolDefinition>, temperature: Float, maxTokens: Int): LlmResponse {
        chatCalls.add(messages to tools)
        return LlmResponse(content = "default")
    }
    override fun chatStream(messages: List<LlmMessage>, tools: List<ToolDefinition>, temperature: Float, maxTokens: Int): Flow<LlmStreamChunk> = flow {
        streamCalls.add(messages to tools)
        emit(LlmStreamChunk(content = "default-stream", isFinish = true))
    }
}
