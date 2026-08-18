package com.apex.agent.ui.screen.settings

import androidx.lifecycle.ViewModel
import com.apex.agent.core.llm.LlmClientFactory
import com.apex.agent.core.llm.LlmConfig
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ProviderConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 设置中心 ViewModel。
 *
 * 所有持久化委托给 [SettingsRepository]（单一可信源）。本类仅负责：
 *  - 向 UI 暴露各 StateFlow；
 *  - 转发增改操作；
 *  - 提供「测试连接」能力。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository
) : ViewModel() {

    val profiles: StateFlow<List<ModelProfile>> = repo.profiles
    val providers: StateFlow<List<ProviderConfig>> = repo.providers
    val roles: StateFlow<ModelRoleConfig> = repo.roles
    val agentSettings: StateFlow<AgentSettings> = repo.agentSettings

    val defaultProfile: ModelProfile get() = repo.defaultProfile()

    // ── Profile 操作 ───────────────────────────────────────────
    fun upsertProfile(profile: ModelProfile) = repo.upsertProfile(profile)
    fun deleteProfile(id: String) = repo.deleteProfile(id)
    fun duplicateProfile(id: String) = repo.duplicateProfile(id)
    fun setDefaultProfile(id: String) = repo.setDefaultProfile(id)
    fun getProfile(id: String) = repo.getProfile(id)

    // ── Provider 操作 ──────────────────────────────────────────
    fun upsertProvider(provider: ProviderConfig) = repo.upsertProvider(provider)
    fun deleteProvider(id: String) = repo.deleteProvider(id)
    fun getProvider(id: String) = repo.getProvider(id)

    // ── 角色 / Agent ───────────────────────────────────────────
    fun updateRoles(block: ModelRoleConfig.() -> ModelRoleConfig) = repo.updateRoles(block)
    fun updateAgentSettings(block: AgentSettings.() -> AgentSettings) = repo.updateAgentSettings(block)

    /**
     * 测试指定模型 Profile 的连接是否可用。
     * 用最小请求调用一次 chat，根据返回判断成功与否。
     */
    suspend fun testConnection(profileId: String): TestResult {
        return withContext(Dispatchers.IO) {
            val profile = repo.getProfile(profileId)
            val provider = profile?.let { repo.getProvider(it.providerId) }

            if (profile == null) {
                return@withContext TestResult(false, "未找到该模型配置")
            }
            if (profile.modelId.isBlank()) {
                return@withContext TestResult(false, "模型名称不能为空")
            }
            val config = LlmConfig.fromProfile(profile, provider)
            if (config.baseUrl.isBlank()) {
                return@withContext TestResult(false, "Base URL 为空（请检查 Provider 配置）")
            }
            if (config.apiKey.isBlank()) {
                return@withContext TestResult(false, "API Key 为空（请检查 Provider 配置）")
            }

            try {
                val client = LlmClientFactory.create(config)
                val messages = listOf(
                    com.apex.agent.core.llm.Message(
                        role = com.apex.agent.core.llm.MessageRole.USER,
                        content = "ping"
                    )
                )
                val result = client.chat(
                    messages = messages,
                    tools = emptyList(),
                    temperature = config.temperature,
                    maxTokens = 16,
                    stream = false
                )
                TestResult(true, "连接成功：收到 ${result.content.length} 字符响应")
            } catch (e: Exception) {
                TestResult(false, "连接失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}

data class TestResult(
    val success: Boolean,
    val message: String
)
