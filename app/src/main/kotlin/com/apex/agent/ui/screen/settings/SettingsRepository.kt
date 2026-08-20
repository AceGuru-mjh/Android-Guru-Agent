package com.apex.agent.ui.screen.settings

import android.content.Context
import android.content.SharedPreferences
import com.apex.agent.core.llm.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置中心持久化层（单一可信源）。
 *
 * 取代原先散落在 SettingsViewModel / LlmModule 中的零散 SharedPreferences 读写，
 * 统一以 JSON 序列化保存：
 *  - 模型 Profile 列表（[ModelProfile]）
 *  - Provider 列表（[ProviderConfig]）
 *  - 多模型角色映射（[ModelRoleConfig]）
 *  - Agent 运行参数（[AgentSettings]）
 *
 * 首次启动注入内置 Provider + 默认 Profile，并把旧版散装 Key（llm_base_url 等）
 * 迁移为一个 Custom Profile，保证老用户配置不丢。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("apex_settings", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _profiles = MutableStateFlow(emptyList<ModelProfile>())
    val profiles: StateFlow<List<ModelProfile>> = _profiles.asStateFlow()

    private val _providers = MutableStateFlow(emptyList<ProviderConfig>())
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    private val _roles = MutableStateFlow(ModelRoleConfig())
    val roles: StateFlow<ModelRoleConfig> = _roles.asStateFlow()

    private val _agentSettings = MutableStateFlow(AgentSettings())
    val agentSettings: StateFlow<AgentSettings> = _agentSettings.asStateFlow()

    init {
        // 所有状态流声明完成后，统一执行加载 / 种子 / 迁移，并立即持久化，
        // 避免字段初始化顺序导致迁移结果被覆盖。
        var provs = readProviders() ?: ModelProfileDefaults.builtInProviders
        var profs = (readProfiles(provs) ?: ModelProfileDefaults.defaultProfiles(provs)).toMutableList()
        migrateLegacyConfig(profs, provs) { newProvs -> provs = newProvs }
        _providers.value = provs
        _profiles.value = profs
        _roles.value = readRoles(profs) ?: ModelProfileDefaults.defaultRoles(profs)
        _agentSettings.value = readAgentSettings()
        persistProviders()
        persistProfiles()
        persistRoles()
        persistAgentSettings()
    }

    // ── 查询辅助 ───────────────────────────────────────────────
    fun getProfile(id: String): ModelProfile? = _profiles.value.firstOrNull { it.id == id }
    fun getProvider(id: String): ProviderConfig? = _providers.value.firstOrNull { it.id == id }

    fun defaultProfile(): ModelProfile {
        val list = _profiles.value
        return list.firstOrNull { it.isDefault }
            ?: list.firstOrNull()
            ?: ModelProfile(id = "fallback", name = "未配置", providerId = "", modelId = "")
    }

    fun defaultProvider(): ProviderConfig? = getProvider(defaultProfile().providerId)

    /** 由「默认模型 Profile + 其 Provider」派生运行时 [LlmConfig]，供引擎 / 测试连接使用。 */
    fun defaultLlmConfig(): LlmConfig {
        val profile = defaultProfile()
        val provider = getProvider(profile.providerId)
        return LlmConfig.fromProfile(profile, provider)
    }

    // ── Profile 增改 ───────────────────────────────────────────
    fun upsertProfile(profile: ModelProfile) {
        val list = _profiles.value.toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        _profiles.value = list
        persistProfiles()
    }

    fun deleteProfile(id: String) {
        // 不允许删除最后一个
        if (_profiles.value.size <= 1) return
        val wasDefault = _profiles.value.firstOrNull { it.id == id }?.isDefault == true
        val list = _profiles.value.filter { it.id != id }.toMutableList()
        if (wasDefault) list[0] = list[0].copy(isDefault = true)
        _profiles.value = list
        // 清理角色映射中对该 Profile 的引用
        var r = _roles.value
        fun String.orPrimary() = if (this == id) (list.firstOrNull()?.id ?: "") else this
        r = r.copy(
            primaryProfileId = r.primaryProfileId.orPrimary(),
            visionProfileId = r.visionProfileId.orPrimary(),
            reasoningProfileId = r.reasoningProfileId.orPrimary(),
            fastProfileId = r.fastProfileId.orPrimary(),
            summaryProfileId = r.summaryProfileId.orPrimary(),
        )
        _roles.value = r
        persistProfiles()
        persistRoles()
    }

    fun duplicateProfile(id: String) {
        val src = getProfile(id) ?: return
        val copy = src.copy(
            id = "profile_${System.currentTimeMillis()}",
            name = "${src.name} (副本)",
            isDefault = false
        )
        upsertProfile(copy)
    }

    fun setDefaultProfile(id: String) {
        _profiles.value = _profiles.value.map { it.copy(isDefault = it.id == id) }
        persistProfiles()
    }

    // ── Provider 增改 ──────────────────────────────────────────
    fun upsertProvider(provider: ProviderConfig) {
        val list = _providers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == provider.id }
        if (idx >= 0) list[idx] = provider else list.add(provider)
        _providers.value = list
        persistProviders()
    }

    fun deleteProvider(id: String) {
        val prov = getProvider(id) ?: return
        if (prov.isBuiltIn) return
        // 把挂载在该 Provider 下的 Profile 改为「无 Provider」（避免悬空引用）
        _profiles.value = _profiles.value.map {
            if (it.providerId == id) it.copy(providerId = "") else it
        }
        _providers.value = _providers.value.filter { it.id != id }
        persistProviders()
        persistProfiles()
    }

    // ── 角色映射 ───────────────────────────────────────────────
    fun updateRoles(block: ModelRoleConfig.() -> ModelRoleConfig) {
        _roles.value = _roles.value.block()
        persistRoles()
    }

    // ── Agent 设置 ─────────────────────────────────────────────
    fun updateAgentSettings(block: AgentSettings.() -> AgentSettings) {
        _agentSettings.value = _agentSettings.value.block()
        persistAgentSettings()
    }

    // ── 持久化 ─────────────────────────────────────────────────
    private fun persistProfiles() =
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(_profiles.value)).apply()

    private fun persistProviders() =
        prefs.edit().putString(KEY_PROVIDERS, json.encodeToString(_providers.value)).apply()

    private fun persistRoles() =
        prefs.edit().putString(KEY_ROLES, json.encodeToString(_roles.value)).apply()

    private fun persistAgentSettings() =
        prefs.edit().putString(KEY_AGENT, json.encodeToString(_agentSettings.value)).apply()

    // ── 加载 / 种子 / 迁移 ──────────────────────────────────────
    private fun readProviders(): List<ProviderConfig>? {
        val raw = prefs.getString(KEY_PROVIDERS, null) ?: return null
        return runCatching { json.decodeFromString<List<ProviderConfig>>(raw) }.getOrNull()
    }

    private fun readProfiles(providers: List<ProviderConfig>): List<ModelProfile>? {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return null
        return runCatching { json.decodeFromString<List<ModelProfile>>(raw) }.getOrNull()
            ?: ModelProfileDefaults.defaultProfiles(providers)
    }

    private fun readRoles(profiles: List<ModelProfile>): ModelRoleConfig? {
        val raw = prefs.getString(KEY_ROLES, null) ?: return null
        return runCatching { json.decodeFromString<ModelRoleConfig>(raw) }.getOrNull()
            ?: ModelProfileDefaults.defaultRoles(profiles)
    }

    private fun readAgentSettings(): AgentSettings {
        val raw = prefs.getString(KEY_AGENT, null)
        if (raw != null) {
            runCatching { json.decodeFromString<AgentSettings>(raw) }.getOrNull()?.let { return it }
        }
        // 兼容旧版散装 Agent 设置 Key
        return AgentSettings(
            defaultMode = prefs.getString("agent_default_mode", "auto") ?: "auto",
            thinkLevel = prefs.getString("agent_think_level", "standard") ?: "standard",
            maxIterations = prefs.getInt("agent_max_iterations", 20),
            keepAlive = prefs.getBoolean("agent_keep_alive", true),
        )
    }

    /**
     * 迁移旧版散装配置（llm_base_url 等）为一个 Custom Profile + Provider，并设为默认。
     * 通过 [onProvidersChanged] 回调把新增的 Provider 回传给调用方（init 中赋值给可变变量），
     * 避免在字段初始化顺序未确定的情况下直接改写 [_providers]。
     */
    private fun migrateLegacyConfig(
        profiles: MutableList<ModelProfile>,
        providers: List<ProviderConfig>,
        onProvidersChanged: (List<ProviderConfig>) -> Unit
    ) {
        val legacyKey = prefs.getString("llm_api_key", null)
        if (legacyKey.isNullOrBlank()) return
        if (prefs.getBoolean(KEY_LEGACY_MIGRATED, false)) return

        val baseUrl = prefs.getString("llm_base_url", "") ?: ""
        val model = prefs.getString("llm_model", "") ?: ""
        val temperature = prefs.getFloat("llm_temperature", 0.7f)
        val effort = ReasoningEffort.fromName(prefs.getString("llm_reasoning_effort", null))

        val customProvider = ProviderConfig(
            id = "migrated",
            displayName = "Migrated (旧配置)",
            baseUrl = baseUrl,
            apiKeys = listOf(legacyKey),
            isBuiltIn = false,
        )
        val migratedProfile = ModelProfile(
            id = "profile_migrated",
            name = "Migrated Config",
            providerId = customProvider.id,
            modelId = model,
            temperature = temperature,
            reasoningEffort = effort,
            isDefault = true,
        )
        // 旧的默认 Profile 取消默认
        val idx = profiles.indexOfFirst { it.isDefault }
        if (idx >= 0) profiles[idx] = profiles[idx].copy(isDefault = false)
        profiles.add(migratedProfile)
        onProvidersChanged(providers + customProvider)
        prefs.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply()
    }

    companion object {
        private const val KEY_PROFILES = "model_profiles_v2"
        private const val KEY_PROVIDERS = "model_providers_v2"
        private const val KEY_ROLES = "model_roles_v2"
        private const val KEY_AGENT = "agent_settings_v2"
        private const val KEY_LEGACY_MIGRATED = "legacy_migrated_v2"
    }
}

/**
 * Agent 运行参数（扩展版）。
 *
 * 字段中已真正打通到 [com.apex.agent.core.engine.AgentConfig] 的有：
 * defaultMode / thinkLevel / maxIterations / streaming(flag) / reflection。
 * 其余（loopDetection / planning / replanning / backgroundExecution 等）为数据预埋，
 * 由 Agent 引擎后续接入，UI 上已逐项暴露。
 */
@kotlinx.serialization.Serializable
data class AgentSettings(
    val defaultMode: String = "auto",        // auto | chat | build
    val thinkLevel: String = "standard",     // standard | deep | minimal
    val maxIterations: Int = 20,
    val keepAlive: Boolean = true,

    // 重试 / 循环防护
    val autoRetry: Boolean = true,
    val maxRetryPerAction: Int = 2,
    val loopDetection: Boolean = true,
    val loopDetectionWindow: Int = 5,
    val sameActionThreshold: Int = 3,
    val autoRecovery: Boolean = true,

    // 高级 Agent 行为（数据预埋，引擎后续接入）
    val reflection: Boolean = true,
    val planning: Boolean = true,
    val replanning: Boolean = false,
    val parallelToolExecution: Boolean = true,
    val backgroundExecution: Boolean = false,

    // 视觉 / 截图（数据预埋，引擎后续接入）
    val visionEnabled: Boolean = true,
    val screenshotQuality: String = "auto",   // auto | low | medium | high
    val maxScreenshots: Int = 3,
)
