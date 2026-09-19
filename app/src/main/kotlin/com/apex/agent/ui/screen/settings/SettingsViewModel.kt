package com.apex.agent.ui.screen.settings

import androidx.lifecycle.ViewModel
import com.apex.agent.R
import com.apex.agent.core.llm.LlmClientFactory
import com.apex.agent.core.llm.LlmConfig
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ModelsCatalog
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.core.llm.RemoteModelInfo
import com.apex.agent.ui.language.LanguageManager
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
    private val repo: SettingsRepository,
    private val languageManager: LanguageManager
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

    /**
     * 仅更新某 Provider 的 Base URL（其余字段保持不变）。
     *
     * 供 ModelSetupCard 的防抖落盘 / 冲刷路径使用 —— 调用方只有 URL 这个编辑位，
     * 不应为了改一个字段而自行拼整份 ProviderConfig（容易把过期的 apiKeys
     * 等字段写回去）。
     */
    fun setProviderBaseUrl(providerId: String, baseUrl: String) {
        val existing = repo.getProvider(providerId) ?: return
        val trimmed = baseUrl.trim()
        if (existing.baseUrl == trimmed) return
        repo.upsertProvider(existing.copy(baseUrl = trimmed))
    }

    /** API Key 读取 / 写入 —— 写入即加密持久化（见 SettingsRepository 的 securePrefs）。 */
    fun getProviderApiKey(providerId: String): String = repo.getProviderApiKey(providerId)
    fun setProviderApiKey(providerId: String, apiKey: String) =
        repo.setProviderApiKey(providerId, apiKey)

    /**
     * 从 Provider 的 Base URL 拉取**真实**模型列表（`GET /models`）。
     *
     * 取代设置页里那批写死的"推荐模型"：端点自己返回的才是能跑的。
     *
     * @param baseUrlOverride 调用方编辑中、尚未落盘的 Base URL（ModelSetupCard
     *   防抖窗口内点「获取模型」时传入，确保拉取的就是用户眼前看到的端点）；
     *   null 时使用已持久化的 Provider.baseUrl。
     */
    suspend fun fetchModels(
        providerId: String,
        baseUrlOverride: String? = null
    ): Result<List<RemoteModelInfo>> =
        withContext(Dispatchers.IO) {
            val resolved = repo.getProvider(providerId)
                ?: return@withContext Result.failure(
                    Exception(languageManager.getString(R.string.settings_error_provider_not_found).format(providerId))
                )
            val baseUrl = (baseUrlOverride ?: resolved.baseUrl).trim()
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                return@withContext Result.failure(
                    Exception(languageManager.getString(R.string.settings_error_invalid_base_url))
                )
            }
            ModelsCatalog.fetchModels(
                baseUrl = baseUrl,
                apiKey = repo.getProviderApiKey(resolved.id),
                extraHeaders = resolved.defaultHeaders
            )
        }

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
                return@withContext TestResult(
                    false,
                    languageManager.getString(R.string.settings_error_profile_not_found)
                )
            }
            if (profile.modelId.isBlank()) {
                return@withContext TestResult(
                    false,
                    languageManager.getString(R.string.settings_error_model_id_blank)
                )
            }
            val config = LlmConfig.fromProfile(profile, provider)
            if (config.baseUrl.isBlank()) {
                return@withContext TestResult(
                    false,
                    languageManager.getString(R.string.settings_error_base_url_empty)
                )
            }
            if (config.apiKey.isBlank()) {
                return@withContext TestResult(
                    false,
                    languageManager.getString(R.string.settings_error_api_key_empty)
                )
            }

            try {
                val client = LlmClientFactory.create(config)
                val messages = listOf(
                    com.apex.agent.core.llm.LlmMessage.User(content = "ping")
                )
                val result = client.chat(
                    messages = messages,
                    tools = emptyList(),
                    temperature = config.temperature,
                    maxTokens = 16
                )
                TestResult(
                    true,
                    languageManager.getString(R.string.settings_test_success)
                        .format(result.content?.length ?: 0)
                )
            } catch (e: Exception) {
                TestResult(
                    false,
                    languageManager.getString(R.string.settings_test_failed)
                        .format(e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }
}

data class TestResult(
    val success: Boolean,
    val message: String
)
