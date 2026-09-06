package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.LlmException
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelRole
import com.apex.agent.core.llm.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * T72 §八 / §十三 — 默认多模型运行时实现。
 *
 * 把 [ModelRoleRouter] 解析出的候选链 + [ModelRuntimeRegistry] 的 Client 缓存
 * 组合成单次请求的执行：按链顺序逐个尝试，能力校验在解析阶段已完成，运行时
 * 只处理"可用性降级"（连接失败 / 5xx / 429 / 超时 / 401-403）。
 *
 * 防止 §十三 警告的"429 → retry → fallback → retry → 无限循环"：
 *  - 每个 request 有最大尝试次数 [maxAttempts]（默认 4，含首选）；
 *  - 每个 profile id 最多尝试一次（visited set）；
 *  - 链必须终止（链本身有限 + visited 去重）。
 *
 * HTTP 级别的同 client 重试仍由 [com.apex.agent.core.llm.LlmClientFactory.RetryInterceptor]
 * 负责（429/5xx 指数退避）；本层只做**跨模型降级**——两层职责清晰分离。
 *
 * 流式降级策略：仅在**首个 chunk 发射前**降级。一旦开始流式输出，若中途
 * 异常则直接抛出（不重启流，避免把已发送给 UI 的 token 与重启后的内容拼接错乱）。
 *
 * `systemPromptPrefix` 接线（§二十二 修复的 dead 字段）：在执行前若解析到的
 * Profile 有非空 `systemPromptPrefix`，则把它拼到 messages 的第一条 System
 * 消息前（若无 System 消息则插入一条）。
 */
class DefaultModelRuntime(
    private val router: ModelRoleRouter,
    private val registry: ModelRuntimeRegistry,
    private val diagnostics: ModelRuntimeDiagnostics = ModelRuntimeDiagnostics(),
    private val maxAttempts: Int = 4,
) : ModelRuntime {

    override suspend fun chat(
        context: LlmRequestContext,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse {
        val chain = resolveChain(context)
        val effectiveMessages = applySystemPromptPrefix(chain, messages)
        val visited = mutableSetOf<String>()
        var lastError: ModelRuntimeException? = null

        for ((attempt, resolved) in chain.withIndex()) {
            if (attempt >= maxAttempts) break
            if (!visited.add(resolved.profile.id)) continue  // 防环：每个 profile 仅一次
            val client = registry.get(resolved.profile, resolved.provider)
            val start = System.currentTimeMillis()
            try {
                val resp = client.chat(effectiveMessages, tools, temperature, maxTokens)
                diagnostics.recordAttempt(
                    profileId = resolved.profile.id,
                    providerId = resolved.provider?.id ?: "",
                    modelId = resolved.profile.modelId,
                    role = context.role,
                    latencyMs = System.currentTimeMillis() - start,
                    fallback = attempt,
                    success = true,
                )
                return resp
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                val err = ErrorClassifier.classify(e, resolved.profile.id)
                diagnostics.recordAttempt(
                    profileId = resolved.profile.id,
                    providerId = resolved.provider?.id ?: "",
                    modelId = resolved.profile.modelId,
                    role = context.role,
                    latencyMs = System.currentTimeMillis() - start,
                    fallback = attempt,
                    success = false,
                    error = err,
                )
                lastError = err
                if (!err.isFallbackEligible) throw err  // 非可降级错误 → 立即抛出
                // 否则继续下一个候选
            }
        }
        throw lastError ?: ModelRuntimeException.ModelFallbackExhausted(
            "角色 ${context.role.label} 的 fallback 链为空", context.role, chain.map { it.profile.id }
        )
    }

    override fun chatStream(
        context: LlmRequestContext,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): Flow<LlmStreamChunk> = flow {
        val chain = resolveChain(context)
        val effectiveMessages = applySystemPromptPrefix(chain, messages)
        val visited = mutableSetOf<String>()
        var lastError: ModelRuntimeException? = null

        for ((attempt, resolved) in chain.withIndex()) {
            if (attempt >= maxAttempts) break
            if (!visited.add(resolved.profile.id)) continue
            val client = registry.get(resolved.profile, resolved.provider)
            val start = System.currentTimeMillis()
            var streamedAny = false
            try {
                val stream = client.chatStream(effectiveMessages, tools, temperature, maxTokens)
                stream.collect { chunk ->
                    streamedAny = true
                    emit(chunk)
                }
                diagnostics.recordAttempt(
                    profileId = resolved.profile.id,
                    providerId = resolved.provider?.id ?: "",
                    modelId = resolved.profile.modelId,
                    role = context.role,
                    latencyMs = System.currentTimeMillis() - start,
                    fallback = attempt,
                    success = true,
                )
                return@flow
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                val err = ErrorClassifier.classify(e, resolved.profile.id)
                diagnostics.recordAttempt(
                    profileId = resolved.profile.id,
                    providerId = resolved.provider?.id ?: "",
                    modelId = resolved.profile.modelId,
                    role = context.role,
                    latencyMs = System.currentTimeMillis() - start,
                    fallback = attempt,
                    success = false,
                    error = err,
                )
                lastError = err
                // 已开始流式输出 → 不降级，直接抛出（避免半截内容 + 重启内容错乱）
                if (streamedAny) throw err
                if (!err.isFallbackEligible) throw err
                // 否则继续下一个候选
            }
        }
        throw lastError ?: ModelRuntimeException.ModelFallbackExhausted(
            "角色 ${context.role.label} 的 fallback 链为空", context.role, chain.map { it.profile.id }
        )
    }

    override fun snapshot(): List<ModelRuntimeDiagnostics.ModelRuntimeSnapshot> = diagnostics.snapshot()

    override fun resolve(
        role: ModelRole,
        requiredCapabilities: ModelCapabilities
    ): ModelRoleRouter.ResolutionResult = router.resolve(role, requiredCapabilities)

    // ── 内部 ──

    private fun resolveChain(context: LlmRequestContext): List<ModelRoleRouter.ResolvedProfile> {
        return when (val res = router.resolve(context.role, context.requiredCapabilities)) {
            is ModelRoleRouter.ResolutionResult.Success -> res.chain
            is ModelRoleRouter.ResolutionResult.Failure -> throw res.error
        }
    }

    /**
     * §二十二 修复：把解析到的首选 Profile 的 `systemPromptPrefix`（旧实现 dead
     * 字段）拼到 messages 的首条 System 前。仅 [DefaultModelRuntime] 接线——
     * [SingleClientModelRuntime] 保持旧行为（不拼），不破坏现有测试。
     */
    private fun applySystemPromptPrefix(
        chain: List<ModelRoleRouter.ResolvedProfile>,
        messages: List<LlmMessage>
    ): List<LlmMessage> {
        val primary = chain.firstOrNull() ?: return messages
        val prefix = primary.profile.systemPromptPrefix
        if (prefix.isBlank()) return messages
        return if (messages.isNotEmpty() && messages[0] is LlmMessage.System) {
            val first = messages[0] as LlmMessage.System
            ArrayList(messages).apply {
                this[0] = LlmMessage.System(prefix + "\n\n" + first.content)
            }
        } else {
            ArrayList<LlmMessage>(messages.size + 1).apply {
                add(LlmMessage.System(prefix))
                addAll(messages)
            }
        }
    }
}

/**
 * 把底层 [Throwable]（[com.apex.agent.core.llm.LlmException] 子类 / IOException /
 * 其它）映射为 [ModelRuntimeException]，供 [DefaultModelRuntime] 决定是否降级。
 *
 * 分类规则（§十四）：
 *  - LlmException.Http(401|403) → [ModelRuntimeException.ModelAuthenticationFailed]
 *  - LlmException.Http(429) → [ModelRuntimeException.ModelRateLimited]
 *  - LlmException.Http(408) → [ModelRuntimeException.ModelTimeout]
 *  - LlmException.Http(5xx) → [ModelRuntimeException.ModelUnavailable]
 *  - LlmException.Http(其它) → [ModelRuntimeException.ModelRequestRejected]
 *  - LlmException.EmptyResponse / EmptyBody → [ModelRuntimeException.ModelResponseInvalid]
 *  - LlmException.Network / SocketTimeoutException → [ModelRuntimeException.ModelTimeout] /
 *    [ModelRuntimeException.ModelUnavailable]
 *  - 其它 Exception → [ModelRuntimeException.ModelUnavailable]（保守可降级）
 *
 * [CancellationException] 由调用方单独处理，不进本分类器。
 */
internal object ErrorClassifier {

    fun classify(e: Throwable, profileId: String): ModelRuntimeException {
        return when (e) {
            is LlmException.Http -> when {
                e.code == 401 || e.code == 403 ->
                    ModelRuntimeException.ModelAuthenticationFailed("鉴权失败 (${e.code})", profileId)
                e.code == 429 ->
                    ModelRuntimeException.ModelRateLimited("被限流 (429)", profileId)
                e.code == 408 ->
                    ModelRuntimeException.ModelTimeout("请求超时 (408)", profileId)
                e.code in 500..599 ->
                    ModelRuntimeException.ModelUnavailable("服务端错误 (${e.code})", profileId, e)
                else ->
                    ModelRuntimeException.ModelRequestRejected("请求被拒绝 (${e.code})", profileId)
            }
            is LlmException.EmptyResponse ->
                ModelRuntimeException.ModelResponseInvalid("空响应", profileId)
            is LlmException.EmptyBody ->
                ModelRuntimeException.ModelResponseInvalid("空响应体", profileId)
            is LlmException.Parse ->
                ModelRuntimeException.ModelResponseInvalid("响应解析失败: ${e.message}", profileId)
            is LlmException.Network -> classifyIoException(e.cause ?: e, profileId)
            else -> classifyIoException(e, profileId)
        }
    }

    private fun classifyIoException(e: Throwable, profileId: String): ModelRuntimeException = when (e) {
        is SocketTimeoutException ->
            ModelRuntimeException.ModelTimeout("Socket 超时: ${e.message}", profileId, e)
        is IOException ->
            ModelRuntimeException.ModelUnavailable("网络/IO 错误: ${e.message}", profileId, e)
        else ->
            // 未知异常保守归为 Unavailable（可降级），避免把可恢复的瞬时错误误判为 fatal。
            ModelRuntimeException.ModelUnavailable("未分类错误: ${e.message ?: e::class.simpleName}", profileId, e)
    }
}
