package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.llm.*
import com.apex.agent.core.llm.runtime.DefaultModelRuntime
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.llm.runtime.ModelRuntimeRegistry
import com.apex.agent.core.llm.runtime.ModelRoleRouter
import com.apex.agent.core.llm.runtime.ModelRuntimeStore
import com.apex.agent.ui.screen.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides
    @Singleton
    fun provideLlmConfig(repo: SettingsRepository): LlmConfig {
        // 由默认模型 Profile + Provider 派生运行时配置（含完整 sampling / network / tools 参数）
        return repo.defaultLlmConfig()
    }

    @Provides
    @Singleton
    fun provideLlmClient(repo: SettingsRepository): LlmClient {
        // 动态委托：设置页/对话页"小大脑"菜单修改默认模型或采样参数后即时生效，
        // 无需重启 App（内部按 profiles/providers 变化重建真实 client）。
        // T72 之后：引擎已改用 [ModelRuntime] 路由多模型；此 [LlmClient] 单例仍
        // 保留供需要直连的旧消费者（如 SettingsViewModel.testConnection）。
        return DynamicLlmClient(repo)
    }

    /**
     * T72 §十七 — 把 [SettingsRepository] 适配为 [ModelRuntimeStore]。
     *
     * 用匿名 object 转发 repo 的三个 StateFlow，**不**修改 SettingsRepository
     * 的类签名（零风险）。Router/Registry 在 core/llm-adapter（纯 JVM）通过
     * 此接口读取配置快照，每次 resolve 都读最新值——设置变更无需重启 App，
     * 下一次请求立即使用新配置。
     */
    @Provides
    @Singleton
    fun provideModelRuntimeStore(repo: SettingsRepository): ModelRuntimeStore = object : ModelRuntimeStore {
        override val profiles get() = repo.profiles
        override val providers get() = repo.providers
        override val roles get() = repo.roles
    }

    /**
     * T72 §三 — 运行时模型注册中心（per-profile Client 缓存 + 生命周期）。
     * @Singleton 生命周期合理：缓存本身无 task 级状态，多 task 共享。
     */
    @Provides
    @Singleton
    fun provideModelRuntimeRegistry(): ModelRuntimeRegistry = ModelRuntimeRegistry()

    /**
     * T72 §四 — 角色路由器（无状态，每次 resolve 读 store 快照）。
     */
    @Provides
    @Singleton
    fun provideModelRoleRouter(
        store: ModelRuntimeStore,
        registry: ModelRuntimeRegistry
    ): ModelRoleRouter = ModelRoleRouter(store)

    /**
     * T72 §八 — 默认多模型运行时（角色路由 + 能力校验 + 降级 + 诊断）。
     * 引擎与压缩器注入此实例，获得完整多模型能力。
     */
    @Provides
    @Singleton
    fun provideModelRuntime(
        router: ModelRoleRouter,
        registry: ModelRuntimeRegistry
    ): ModelRuntime = DefaultModelRuntime(router, registry)
}

/**
 * 未配置时的占位客户端
 */
class NoOpLlmClient : LlmClient {
    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse {
        return LlmResponse(
            content = "请先在设置中配置API（Base URL + API Key + Model）",
            toolCalls = emptyList()
        )
    }

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): kotlinx.coroutines.flow.Flow<LlmStreamChunk> {
        return kotlinx.coroutines.flow.flowOf(
            LlmStreamChunk(content = "请先在设置中配置API"),
            LlmStreamChunk(isFinish = true)
        )
    }
}
