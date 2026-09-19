package com.apex.agent.di

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmClientFactory
import com.apex.agent.core.llm.LlmConfig
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.llm.WebSearchMode
import com.apex.agent.ui.screen.agent.toolkit.ChatToolkitStore
import com.apex.agent.ui.screen.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 动态 LLM 客户端：跟随 [SettingsRepository] 的默认模型 Profile 实时重建内部委托。
 *
 * 背景：原 [LlmModule.provideLlmClient] 是 @Singleton 一次性构建——对话页"小大脑"
 * 菜单里切换模型 / 调节采样参数（temperature/topP/maxTokens 等持久化在
 * [com.apex.agent.core.llm.ModelProfile] 上）后，运行中的引擎仍持有旧 client，
 * 必须重启 App 才生效。本包装类监听 profiles/providers 两个 StateFlow，
 * 派生配置变化时原子替换委托，后续请求立即使用新模型与新参数。
 *
 * 线程安全：[delegate] 用 @Volatile 保证引擎协程读到最新引用；重建本身幂等，
 * 偶发并发重建只是多建一个 client（OkHttp 线程为守护线程，旧实例随 GC 回收）。
 *
 * 会话级联网搜索（根因修复）：[ChatToolkitStore.webSearchEnabled] 打开时，
 * 本客户端把当前配置的 webSearch 提升为 AUTO（按端点自动选择原生搜索策略，
 * 未知端点解析为 OFF 防 400）——与 LlmModule 的 ModelRuntimeStore 适配器
 * 同一策略的两处落点（此处覆盖旧直连消费者，如 SettingsViewModel.testConnection
 * 之外经引擎直连的路径；多模型路由路径由 store 适配器覆盖）。
 */
class DynamicLlmClient(
    private val repo: SettingsRepository,
    private val chatToolkit: ChatToolkitStore? = null
) : LlmClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var delegate: LlmClient = buildDelegate(effectiveConfig(repo.defaultLlmConfig()))

    @Volatile
    private var currentConfig: LlmConfig = effectiveConfig(repo.defaultLlmConfig())

    /** 会话级"网络搜索"开关 → 提升配置 webSearch 为 AUTO（未提供 toolkit 时原样返回）。 */
    private fun effectiveConfig(base: LlmConfig): LlmConfig {
        val enabled = chatToolkit?.webSearchEnabled?.value ?: false
        return if (enabled && base.isValid) base.copy(webSearch = WebSearchMode.AUTO) else base
    }

    init {
        scope.launch {
            // profiles/providers 变化 或 工具栏搜索开关变化 → 重建委托。
            // combine 三流：任一变化即重算有效配置；distinctUntilChanged 去重后
            // 重建（开关反复切换幂等）。
            val configFlow = combine(
                repo.profiles,
                repo.providers,
                chatToolkit?.webSearchEnabled ?: MutableStateFlow(false)
            ) { _, _, _ -> effectiveConfig(repo.defaultLlmConfig()) }
                .distinctUntilChanged()
                // 首帧与构造函数里的初始配置相同，跳过避免重复建 client
                .drop(1)
            configFlow.collect { newConfig ->
                currentConfig = newConfig
                delegate = buildDelegate(newConfig)
            }
        }
    }

    private fun buildDelegate(config: LlmConfig): LlmClient =
        if (config.isValid) LlmClientFactory.create(config) else NoOpLlmClient()

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse = delegate.chat(messages, tools, temperature, maxTokens)

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): Flow<LlmStreamChunk> = delegate.chatStream(messages, tools, temperature, maxTokens)
}
