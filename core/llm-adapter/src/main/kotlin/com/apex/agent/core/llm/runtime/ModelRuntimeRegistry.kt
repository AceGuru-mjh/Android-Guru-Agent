package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmClientFactory
import com.apex.agent.core.llm.LlmConfig
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * T72 §三 — 运行时模型注册中心。
 *
 * 职责（§三）：
 *  - **Profile → Client 缓存**：同一个 (providerId + modelId + effective
 *    configuration) 不重复创建 HTTP client。缓存键为 `profile.id`（不同 Profile
 *    即便 modelId 相同，sampling / reasoning / structured / headers 不同，也需
 *    各自的 [StreamingOpenAiClient] 实例持有各自的 [LlmConfig]）。
 *  - **Client 生命周期**：[get] / [invalidate] / [invalidateAll] / [shutdown]。
 *  - **并发安全**：基于 [ConcurrentHashMap]；多个 Agent task 可同时 [get]
 *    同一 Profile，不会重复建 client。配置变化时**懒重建**（下次 [get] 比较
 *    缓存的 [LlmConfig] 与当前 [LlmConfig]，不同则重建）——这同时满足 §十七
 *    热更新要求：进行中的请求继续用旧 client，下一次请求自动用新配置。
 *  - **Engine destroy 不泄漏**：[shutdown] 清空缓存（OkHttp 客户端线程池随
 *    GC 回收；显式 dispatcher().executorService().shutdown() 在 JDK 21 上
 *    可选，此处仅清引用以避免持有过期 client）。
 *
 * 本类为**无状态数据源**消费者：不持有 [ModelRuntimeStore] 引用，由调用方
 * （[ModelRoleRouter] / app DI）传入 (profile, provider) 对。这样保持纯函数式
 * 缓存语义，便于测试。
 *
 * @param clientFactory 构造真实 [LlmClient] 的工厂，默认 [LlmClientFactory.create]；
 *                      测试可注入 Fake 工厂以避免真实 HTTP。
 */
class ModelRuntimeRegistry(
    private val clientFactory: (LlmConfig) -> LlmClient = { config -> LlmClientFactory.create(config) }
) {

    private data class CacheEntry(
        val config: LlmConfig,
        val client: LlmClient,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * 获取 [profile] 对应的 [LlmClient]，按需懒创建。
     *
     * - 首次请求：构造 [LlmConfig.fromProfile]，调 [clientFactory] 建 client，缓存。
     * - 命中缓存且配置未变：直接返回缓存 client。
     * - 命中缓存但配置已变（§十七 热更新）：丢弃旧 client，用新配置重建并缓存。
     *
     * @param profile 目标 Profile
     * @param provider 目标 Profile 挂载的 Provider（null → baseUrl/apiKey 为空，
     *                 client 仍会创建但运行时请求会因 isValid=false 失败）
     * @return 对应的 [LlmClient]
     */
    fun get(profile: ModelProfile, provider: ProviderConfig?): LlmClient {
        val config = LlmConfig.fromProfile(profile, provider)
        val existing = cache[profile.id]
        if (existing != null && existing.config == config) {
            return existing.client
        }
        // 配置变化或首次：重建。多次并发进入此处只会各自建一个 client，
        // 最终 cache 最后一个；OkHttp client 互不干扰，旧的随 GC 回收——
        // 这是可接受的（与原 DynamicLlmClient 的"偶发并发重建只是多建一个
        // client"语义一致）。
        val client = clientFactory(config)
        cache[profile.id] = CacheEntry(config, client)
        return client
    }

    /**
     * 失效单个 Profile 的缓存 client。下次 [get] 会重建。
     * 用于 Profile 被显式更新 / 删除时主动释放（非必须：懒重建已保证正确性）。
     */
    fun invalidate(profileId: String) {
        cache.remove(profileId)
    }

    /**
     * 失效全部缓存。用于 Provider 大范围变更（baseUrl/key 变了影响所有挂载 Profile）。
     */
    fun invalidateAll() {
        cache.clear()
    }

    /** 当前缓存的 Profile id 数量（诊断用）。 */
    fun size(): Int = cache.size

    /**
     * 关闭并清空全部缓存 client。Engine/App 销毁时调用，避免泄漏。
     *
     * 注意：OkHttp [okhttp3.OkHttpClient] 没有公开的 close（其 dispatcher /
     * connectionPool 随 JVM 退出回收）；这里只清缓存引用。若未来需要更严格的
     * 资源回收，可在 [clientFactory] 中返回包装类并在 [shutdown] 时调用其 close。
     */
    fun shutdown() {
        cache.clear()
    }
}
