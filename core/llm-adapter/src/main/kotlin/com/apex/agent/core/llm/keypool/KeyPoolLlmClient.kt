package com.apex.agent.core.llm.keypool

import com.apex.agent.core.llm.KeyRotationMode
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmException
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolChoiceSpec
import com.apex.agent.core.llm.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * # Key 池 LLM 客户端包装器（Task 4-a）
 *
 * 把 [ApiKeyPool] 接到 [LlmClient] 接口上：每次调用从池里取 key → 用
 * [delegateFactory] 现建真实 client（如 StreamingOpenAiClient）→ 失败按
 * 模式换 key 重试。这是 worklog 2-c 指出的实现路径②——"新建 KeyPoolLlmClient
 * 包装器捕获 LlmException.Http(429/401) 按 KeyRotationMode 换 key 重试"，
 * 且**必须在请求层包装器做**（ModelRuntimeRegistry 缓存以 LlmConfig 相等为键，
 * 改 config 重建会击穿缓存语义）。
 *
 * 对标：
 * - Operit 的轮换只做"游标前进"，失败不换 key 重试（AIServiceFactory 每
 *   Provider 构造期注入 provider）；
 * - RikkaHub 的 KeyRoulette 在 header 组装时换 key，无失败驱动逻辑；
 * - 这里把"失败分类 → 上报池 → 显式轮换 → 下一把"做成完整闭环。
 *
 * ## 重试语义
 * - 值得换 key 的失败（key-rotation-worthy）：
 *   - ON_ERROR / SEQUENTIAL：HTTP 401 / 403 / 429，加 5xx（需
 *     [rotateOnServerErrors] = true，默认关——5xx 多为端点问题，换 key 无益）；
 *   - ON_RATE_LIMIT：**仅 429**（限流 / 配额才换，鉴权失败等下一通调用
 *     再经候选过滤自然切换——严格按模式名语义）；
 *   - DISABLED：**不换**（用户明说"不轮换"；坏 key 仍会在**下一通调用**被
 *     池的候选过滤自然跳过——跨调用兜底而非调用内轮换）。
 * - 失败先 [ApiKeyPool.reportFailure]（池记账：401/403 判死、429/5xx/网络
 *   冷却退避、其余仅计数），再对粘性模式 [ApiKeyPool.rotateCursor]
 *   （SEQUENTIAL 的游标随 acquire 自前进，无需显式轮换）。
 * - 每通调用最多尝试 [maxKeyRetries] 把**不同** key；耗尽或池无可选时
 *   **重抛最后一个原始错误**（保真上层错误分类，如 ErrorClassifier）；
 *   一把都没试过（开局即空池 / 全冷却）才抛 [KeyPoolExhaustedException]。
 * - 流式（[chatStream]）只在**首块之前**失败才换 key 重试；已经吐过块
 *   （半途失败）绝不能重放——直接向上传播，同时照常 reportFailure，
 *   让下一通调用自然换 key。
 *
 * ## 秘钥卫生
 * key 材料只流向 [delegateFactory]；异常消息 / 池内错误信息一律脱敏
 * （[KeyPoolExhaustedException] 只带 id / outcome / reason；写入池的
 * 错误消息会先剥除 key 子串再截断——对齐 ModelRuntimeErrors.kt
 * "绝不携带 API Key" 的纪律）。
 *
 * @param pool               key 池（与设置页 / 可用性测试器共享同一实例）
 * @param rotationMode       轮换模式（来自 ProviderConfig.keyRotationMode）
 * @param delegateFactory    用选中 key 现建底层 client——app 层典型实现：
 *                           `{ key -> StreamingOpenAiClient(config.copy(apiKey = key)) }`
 * @param maxKeyRetries      单通调用最多尝试的不同 key 数（>= 1，构造期校验）
 * @param rotateOnServerErrors 5xx 是否也值得换 key（默认 false）
 */
class KeyPoolLlmClient(
    private val pool: ApiKeyPool,
    private val rotationMode: KeyRotationMode,
    private val delegateFactory: (apiKey: String) -> LlmClient,
    private val maxKeyRetries: Int = 3,
    private val rotateOnServerErrors: Boolean = false
) : LlmClient {

    init {
        require(maxKeyRetries >= 1) { "maxKeyRetries must be >= 1 (was $maxKeyRetries)" }
    }

    // ═══════════════════════ LlmClient：非流式 ═══════════════════════

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse = chat(messages, tools, temperature, maxTokens, null)

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        toolChoice: ToolChoiceSpec?
    ): LlmResponse = withKeyRetry(allowRetry = { true }) { delegate, _ ->
        delegate.chat(messages, tools, temperature, maxTokens, toolChoice)
    }

    // ═══════════════════════ LlmClient：流式 ═══════════════════════

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): Flow<LlmStreamChunk> = chatStream(messages, tools, temperature, maxTokens, null)

    /**
     * 流式 + 换 key 重试。
     *
     * `emitted` 标记是关键：首块之前失败 → [allowRetry] 放行 → 换 key 整流重来；
     * 已发块后失败 → 不许重试（重放会重复输出）→ 上抛。发块动作借外层
     * flow 构建器的 collector 完成——[withKeyRetry] 在同一协程内同步调用
     * block，无上下文切换，满足 FlowCollector 的发射不变量。
     */
    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        toolChoice: ToolChoiceSpec?
    ): Flow<LlmStreamChunk> = flow {
        var emitted = false
        withKeyRetry(allowRetry = { !emitted }) { delegate, _ ->
            delegate.chatStream(messages, tools, temperature, maxTokens, toolChoice)
                .collect { chunk ->
                    emitted = true
                    emit(chunk)
                }
        }
    }

    // ═══════════════════════ 重试内核 ═══════════════════════

    /**
     * 取 key → 建 delegate → 执行 [block]；失败按分类决定"上报后换 key 重来"
     * 还是"上报后上抛"。
     *
     * @param allowRetry 额外闸门（流式用它实现"发过块就不许重试"）
     * @param block      以选中的 delegate 执行实际调用；返回值即整个重试环的产物
     *
     * 环内不变量：
     * - `triedIds` 记录本通已用过的 key id——同一把绝不二连试（防止轮换绕回
     *   原地打转成死循环）；也用于 [maxKeyRetries] 上限与耗尽诊断；
     * - CancellationException 原样上抛（协作取消绝不吞——仓库纪律）；
     * - Error（OOM 等）不做池记账直接上抛（不是 key 健康事件）。
     */
    private suspend fun <T> withKeyRetry(
        allowRetry: (Throwable) -> Boolean,
        block: suspend (delegate: LlmClient, entry: ApiKeyEntry) -> T
    ): T {
        val triedIds = LinkedHashSet<String>()
        var lastError: Throwable? = null

        while (true) {
            val selection = pool.acquire(rotationMode)
            val entry = selection.selectedEntry
            if (entry == null) {
                // 池给不出 key：试过的错更有信息量 → 重抛；一把没试过 → 池耗尽异常
                lastError?.let { throw it }
                throw KeyPoolExhaustedException.from(selection, rotationMode, triedIds.toList())
            }
            if (!triedIds.add(entry.id)) {
                // 防御：池又给了同一把（理论不可达——可换级失败都会把 key 踢出候选）
                throw lastError
                    ?: KeyPoolExhaustedException.from(selection, rotationMode, triedIds.toList())
            }

            val delegate = delegateFactory(entry.key)
            try {
                val result = block(delegate, entry)
                pool.reportSuccess(entry.id)
                return result
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e
                pool.reportFailure(entry.id, httpCodeOf(e), sanitizeMessage(errorMessageOf(e), entry))
                if (!isRotationWorthy(e) || !allowRetry(e)) throw e
                if (triedIds.size >= maxKeyRetries) throw e
                if (rotationMode != KeyRotationMode.SEQUENTIAL) pool.rotateCursor()
                // 继续环：acquire 已因 reportFailure / rotateCursor 指向下一把
            }
        }
    }

    /** 该错误在此模式下是否值得换 key 重试（见类 KDoc 的模式矩阵）。 */
    private fun isRotationWorthy(t: Throwable): Boolean {
        if (t !is LlmException.Http) return false
        return when (rotationMode) {
            KeyRotationMode.DISABLED -> false
            KeyRotationMode.ON_RATE_LIMIT -> t.code == 429
            KeyRotationMode.ON_ERROR,
            KeyRotationMode.SEQUENTIAL ->
                t.code == 401 || t.code == 403 || t.code == 429 ||
                    (rotateOnServerErrors && t.code in 500..599)
        }
    }

    /** 错误 → 池可分类的 HTTP 码。约定：0 = 网络层（Network），-1 = 无码错误。 */
    private fun httpCodeOf(t: Throwable): Int = when (t) {
        is LlmException.Http -> t.code
        is LlmException.Network -> 0
        else -> -1
    }

    /** 错误 → 可读消息（Http 取响应体，Network 取原始 IOException 信息）。 */
    private fun errorMessageOf(t: Throwable): String = when (t) {
        is LlmException.Http -> t.body
        is LlmException.Network -> t.cause?.message ?: t.message ?: ""
        else -> t.message ?: ""
    }

    /**
     * 写入池前的消息净化：先剥除 key 子串（个别中转会回显 Authorization），
     * 再截断到池的消息预算——保证 lastErrorMessage 永远无秘钥材料。
     */
    private fun sanitizeMessage(message: String, entry: ApiKeyEntry): String {
        val stripped = if (entry.key.isNotEmpty()) {
            message.replace(entry.key, REDACTED_PLACEHOLDER)
        } else {
            message
        }
        return stripped.take(ApiKeyPool.MAX_ERROR_MESSAGE_LENGTH)
    }

    private companion object {
        /** 秘钥被剥除后的占位符。 */
        const val REDACTED_PLACEHOLDER = "[redacted]"
    }
}

/**
 * key 池耗尽 / 无可选 key 时抛出（仅"一把都没试成"的场景；试过之后的耗尽
 * 重抛最后一个原始错误，保真上层错误分类）。
 *
 * 为什么不复用 [LlmException]：它是 sealed 层级（子类必须与其同包同模块，
 * StreamingOpenAiClient.kt 内已封闭），keypool 子包无法扩展；且池耗尽是
 * 配置层问题而非单次 HTTP 交互问题，独立类型让 app 层能精确捕获并引导
 * 用户去可用性测试页 / 等 cooldown。异常只携带 id / 结局 / 模式 / 原因
 * ——**绝不携带 key 材料**（构造侧保证）。
 */
class KeyPoolExhaustedException(
    message: String,
    /** 池给出的失败结局（POOL_EMPTY / ALL_DISABLED / ALL_COOLING_DOWN / ALL_UNAVAILABLE）。 */
    val outcome: KeySelectionOutcome,
    /** 触发耗尽的轮换模式。 */
    val rotationMode: KeyRotationMode,
    /** 本通调用已试过的 key id（按尝试顺序；开局即空时为空表）。 */
    val attemptedKeyIds: List<String> = emptyList()
) : Exception(message) {

    companion object {
        /** 从一次失败的选择构造（消息只含 id / outcome / reason，无 key 材料）。 */
        fun from(
            selection: KeySelection,
            mode: KeyRotationMode,
            attemptedKeyIds: List<String>
        ): KeyPoolExhaustedException {
            val tried = if (attemptedKeyIds.isEmpty()) {
                ""
            } else {
                ", tried=[${attemptedKeyIds.joinToString(",")}]"
            }
            return KeyPoolExhaustedException(
                message = "API key pool exhausted (outcome=${selection.outcome}, " +
                    "mode=$mode$tried): ${selection.reason}",
                outcome = selection.outcome,
                rotationMode = mode,
                attemptedKeyIds = attemptedKeyIds.toList()
            )
        }
    }
}
