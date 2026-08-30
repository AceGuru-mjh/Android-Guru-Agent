package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ProviderConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * T72 §十七 — Settings → Runtime 热更新的可观测数据源。
 *
 * [ModelRuntimeRegistry] 与 [ModelRoleRouter] 都在 `core/llm-adapter`（纯 JVM，
 * 无 Android 依赖），不能直接依赖 app 层的 `SettingsRepository`。本接口是它们
 * 消费配置的唯一入口：实现方（生产环境为 app 的 `SettingsRepository` 适配器，
 * 测试环境为 `FakeModelRuntimeStore`）暴露三个 StateFlow。
 *
 * Router 在每次 `resolve(role)` 时读取 `profiles.value / providers.value /
 * roles.value` 的**当前快照**——因此设置变更后无需重启 App / 重建引擎，
 * 下一次请求立即使用新配置（§十七 要求）。
 *
 * 进行中的请求允许继续使用旧 snapshot（OkHttp 流不可中途换 client），
 * 这与 [ModelRuntimeRegistry] 的"按 profileId 缓存 + 配置变化时懒重建"
 * 策略配合实现。
 */
interface ModelRuntimeStore {
    val profiles: StateFlow<List<ModelProfile>>
    val providers: StateFlow<List<ProviderConfig>>
    val roles: StateFlow<ModelRoleConfig>
}
