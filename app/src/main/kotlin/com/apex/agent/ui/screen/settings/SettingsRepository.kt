package com.apex.agent.ui.screen.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.apex.agent.core.engine.modes.ModePreset
import com.apex.agent.core.engine.modes.migrateLegacyCustomInstruction
import com.apex.agent.core.engine.modes.selectedModePreset
import com.apex.agent.core.llm.*
import com.apex.agent.permission.PermissionMode
import com.apex.agent.permission.PermissionRule
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

    /**
     * API Key 的加密存储（AES-256-GCM + AES-256-SIV，由 Android Keystore 主密钥保护）。
     *
     * 背景：旧实现把 Provider 的 `apiKeys` 连同其它字段一起明文 JSON 落盘在
     * `apex_settings` 里 —— 只要设备被 root 或做一次 adb backup，Key 就裸奔。
     * 现在 Key **只**存在于此处，内存中保留一份副本供 [LlmConfig] 构建使用。
     */
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, PREF_SECURE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 极少数设备 Keystore 初始化失败：退化为普通 SP，宁可不加密也绝不让配置丢失
            context.getSharedPreferences(PREF_SECURE_FALLBACK, Context.MODE_PRIVATE)
        }
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
        provs = migrateAndHydrateApiKeys(provs)
        migrateLegacyConfig(profs, provs) { newProvs -> provs = newProvs }
        provs = migrateAndHydrateApiKeys(provs)
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

    // ── API Key：加密存储 ───────────────────────────────────────
    /** 读取某个 Provider 的 API Key（密文；无则返回空串而非 null，便于直接判空）。 */
    fun getProviderApiKey(providerId: String): String {
        if (providerId.isBlank()) return ""
        return securePrefs.getString(KEY_PROVIDER_KEY + providerId, null)
            ?: prefs.getString(KEY_PROVIDER_KEY + providerId, null).orEmpty()
    }

    /**
     * 写入某个 Provider 的 API Key —— 用户手输的唯一入口。
     *
     * 同时把内存副本的 `apiKeys` 同步更新，保证 [defaultLlmConfig] /
     * ModelRuntime 在**不重启 App**的前提下立刻用上新 Key。
     */
    fun setProviderApiKey(providerId: String, apiKey: String) {
        if (providerId.isBlank()) return
        val trimmed = apiKey.trim()
        securePrefs.edit().putString(KEY_PROVIDER_KEY + providerId, trimmed).apply()
        // 清理历史遗留的明文副本
        if (prefs.contains(KEY_PROVIDER_KEY + providerId)) {
            prefs.edit().remove(KEY_PROVIDER_KEY + providerId).apply()
        }
        val idx = _providers.value.indexOfFirst { it.id == providerId }
        if (idx >= 0) {
            val list = _providers.value.toMutableList()
            list[idx] = list[idx].copy(
                apiKeys = if (trimmed.isBlank()) emptyList() else listOf(trimmed)
            )
            _providers.value = list
        }
    }

    /**
     * 明文 Key → 加密存储迁移 + 启动时回填。
     *
     * - Provider 列表里若还带着明文 `apiKeys`（旧版本落盘），写入加密区；
     * - 随后无论 Key 来自加密区还是已迁移，都回填到内存副本（供请求使用）；
     * - 返回的列表不含明文 Key —— 真正落盘时还会由 [providersForPersist] 二次剥离。
     */
    private fun migrateAndHydrateApiKeys(providers: List<ProviderConfig>): List<ProviderConfig> =
        providers.map { prov ->
            prov.apiKeys.firstOrNull { it.isNotBlank() }?.let { plain ->
                securePrefs.edit().putString(KEY_PROVIDER_KEY + prov.id, plain).apply()
                if (prefs.contains(KEY_PROVIDER_KEY + prov.id)) {
                    prefs.edit().remove(KEY_PROVIDER_KEY + prov.id).apply()
                }
            }
            val stored = getProviderApiKey(prov.id).trim()
            prov.copy(apiKeys = if (stored.isBlank()) emptyList() else listOf(stored))
        }

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
        // Key 一旦出现在 incoming 里就立即入加密区，绝不随 Provider 一起明文落盘
        provider.apiKeys.firstOrNull { it.isNotBlank() }?.let { setProviderApiKey(provider.id, it) }
        val key = getProviderApiKey(provider.id)
        val sanitized = provider.copy(
            apiKeys = if (key.isBlank()) emptyList() else listOf(key)
        )
        val list = _providers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == sanitized.id }
        if (idx >= 0) list[idx] = sanitized else list.add(sanitized)
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
        // 连同存储的 Key 一起清掉（不含 Key 的残留密文就是密钥泄漏面）
        securePrefs.edit().remove(KEY_PROVIDER_KEY + id).apply()
        prefs.edit().remove(KEY_PROVIDER_KEY + id).apply()
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

    /**
     * #168 当前生效的 CUSTOM 模式指令（单一解析源，AgentModule 启动快照与
     * AgentChatViewModel 运行时热切换共用）：
     * 1. 选中预设（[AgentSettings.selectedModePresetId] 命中内置或用户预设）
     *    → 该预设的 instruction；
     * 2. 未选预设 → 回退旧单串 `custom_mode_instruction`（兼容通道）；
     * 3. 都为空 → ""（CUSTOM 模式不注入任何额外指令）。
     */
    fun effectiveCustomInstruction(): String {
        val agent = _agentSettings.value
        selectedModePreset(agent.customModePresets, agent.selectedModePresetId)
            ?.let { return it.instruction.trim() }
        return prefs.getString(LEGACY_KEY_CUSTOM_INSTRUCTION, null)?.trim().orEmpty()
    }

    // ── 持久化 ─────────────────────────────────────────────────
    private fun persistProfiles() =
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(_profiles.value)).apply()

    /** Provider 的落盘副本：剥离全部 API Key（Key 只在加密区，见 [securePrefs]）。 */
    private fun providersForPersist(): List<ProviderConfig> =
        _providers.value.map { it.copy(apiKeys = emptyList()) }

    private fun persistProviders() =
        prefs.edit().putString(KEY_PROVIDERS, json.encodeToString(providersForPersist())).apply()

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
            runCatching { json.decodeFromString<AgentSettings>(raw) }.getOrNull()?.let { return migrateCustomModePresets(it) }
        }
        // 兼容旧版散装 Agent 设置 Key
        return migrateCustomModePresets(
            AgentSettings(
                defaultMode = prefs.getString("agent_default_mode", "auto") ?: "auto",
                thinkLevel = prefs.getString("agent_think_level", "standard") ?: "standard",
                maxIterations = prefs.getInt("agent_max_iterations", 20),
                keepAlive = prefs.getBoolean("agent_keep_alive", true),
            )
        )
    }

    /**
     * #168 预设迁移：旧版 CUSTOM 单串指令（SharedPreferences 平键
     * `custom_mode_instruction`，AgentChatViewModel 写入）→ 首个用户预设。
     *
     * 迁移条件见 [migrateLegacyCustomInstruction]（旧指令非空 + 尚无用户预设
     * + 未迁移过 → 幂等）；迁移后自动选中，保证升级前后 CUSTOM 模式的
     * system prompt 注入内容逐字一致（行为零变化）。新用户（无旧指令）
     * 不产生任何预设，选中留空 = 走旧单串兼容路径（选中预设优先于旧串）。
     */
    private fun migrateCustomModePresets(settings: AgentSettings): AgentSettings {
        if (settings.customModePresets.isNotEmpty()) return settings
        val legacy = prefs.getString(LEGACY_KEY_CUSTOM_INSTRUCTION, null)
        val migrated = migrateLegacyCustomInstruction(emptyList(), legacy) ?: return settings
        return settings.copy(
            customModePresets = listOf(migrated),
            selectedModePresetId = migrated.id
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

        /** #168：旧版 CUSTOM 单串指令键（AgentChatViewModel 同款键名，迁移读取源）。 */
        private const val LEGACY_KEY_CUSTOM_INSTRUCTION = "custom_mode_instruction"

        /** 加密 Preference 文件名 */
        private const val PREF_SECURE = "apex_secure_settings"
        /** Keystore 不可用时的退化文件名 */
        private const val PREF_SECURE_FALLBACK = "apex_secure_settings_fallback"
        /** API Key 前缀键名（拼接 providerId） */
        private const val KEY_PROVIDER_KEY = "provider_api_key_"
    }
}

/**
 * Agent 运行参数（扩展版）。
 *
 * 字段中已真正打通到 [com.apex.agent.core.engine.AgentConfig] 的有（DI 一次性快照，
 * 设置变更需重启应用生效）：
 * defaultMode / thinkLevel / maxIterations / reflection / reflectionRounds /
 * maxContextTokens / compressionThreshold / preserveRecentTurns / maxToolOutputLength
 * （streaming / temperature 则来自选中的模型 Profile）。
 *
 * 其余字段为数据预埋，由对应层后续接入：
 *  - loopDetection / planning / replanning / backgroundExecution / keepAlive /
 *    autoRetry 等 → Agent 引擎后续接入（UI 已逐项暴露）；
 *  - visionEnabled / screenshotQuality / maxScreenshots → 视觉管线后续接入；
 *  - themeMode / dynamicColor / fontScale / showTimestamps → 界面/主题层消费
 *    （fontScale / showTimestamps 设计为立即生效）。
 */
@kotlinx.serialization.Serializable
data class AgentSettings(
    val defaultMode: String = "auto",        // auto | chat | build
    val thinkLevel: String = "standard",     // standard | deep | minimal | auto
    /**
     * #168 聊天页思考档位覆盖（持久化）："" = 未覆盖（跟随 thinkLevel 启动默认 +
     * Profile 原生 reasoningEffort）；"auto" = AUTO 自适应；其余 = ThinkingLevel
     * 枚举名小写（none/light/standard/deep/maximum）。聊天页选择器写入，
     * AgentChatViewModel init 恢复（patchConfig 运行时生效，无需重启）。
     */
    val thinkingLevelOverride: String = "",
    /**
     * v1.2 Coding 模式思考档位（持久化，与聊天页 thinkingLevelOverride 互不
     * 干扰——两模式各自记忆）："" = 未设置（回退 STANDARD）；"auto" = AUTO
     * 自适应；其余 = ThinkingLevel 枚举名小写
     * （none/light/standard/deep/maximum/ultracode/apexcode）。
     * Coding 页选择器写入，CodeViewModel init 恢复（updateThinkingLevel
     * 双通道运行时生效，无需重启）。
     */
    val codeThinkingLevel: String = "",
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

    // ── 上下文压缩（对应 AgentConfig，重启应用/新会话后生效）──
    val maxContextTokens: Int = 128_000,
    val compressionThreshold: Float = 0.8f,   // 0.5..0.95
    val preserveRecentTurns: Int = 5,
    val maxToolOutputLength: Int = 2000,

    // ── 反思 ──
    val reflectionRounds: Int = 1,            // 1..3（设置页 Slider 绑定）

    // ═══ #168 CUSTOM 模式预设（多套命名指令）═══
    // customModePresets 只存用户自定义预设；内置 4 套由
    // BuiltinModePresets.ALL 运行时合成（effectiveModePresets）。
    // selectedModePresetId = "" → 未选：回退旧单串 custom_mode_instruction
    // （AgentChatViewModel 兼容通道）；选中预设的 instruction 由 VM 拍平进
    // AgentConfig.customInstruction（既有 "## Custom Instructions" 注入点，
    // 引擎/提示词零新概念）。旧单串首启自动迁移为预设（幂等，见
    // SettingsRepository.migrateCustomModePresets）。
    val customModePresets: List<ModePreset> = emptyList(),
    val selectedModePresetId: String = "",

    // ── 界面 ──
    val themeMode: String = "system",         // system | dark | light
    val dynamicColor: Boolean = false,
    // 预设主题配色（mint | amber | coral | violet | ocean | rose | forest |
    // cyan | sunset | gold | crimson）；Dynamic Color 开启时被壁纸取色覆盖。
    // 见 ui/theme/ThemePalettes.kt。
    val accentPalette: String = "mint",
    val fontScale: Float = 1.0f,              // 0.8..1.4
    val showTimestamps: Boolean = true,

    // ── 语言 / 输入行为（界面层即时消费；语言切换由 MainActivity recreate 生效）──
    val language: String = "system",          // system | zh | en
    val sendKeyBehavior: String = "send",      // send | newline（聊天输入框 IME 行为）
    val showRunSummary: Boolean = false,       // 任务总结卡默认隐藏

    // ── 新手引导 ──
    // 首次启动展示 Onboarding（欢迎/能力/权限/模型配置四页）；
    // 老版本升级用户也会看到一次（ignoreUnknownKeys 反序列化缺省 false），
    // 属预期行为 —— 引导页本身也承担新功能布道。
    val onboardingCompleted: Boolean = false,

    // ═══ Agent 角色（人设层 · 运行时可热切换，无需重启）═══
    // agentRoles 只存自定义角色；内置全能角色运行时合成（AgentRole.ALL_ROUNDER，
    // 升级即最新且不可删）。activeRoleId 悬空/被删 → activeRole() 诚实回落内置。
    // 操作助手见 AgentRole.kt（withRoleUpserted/withRoleRemoved/withRoleActivated）。
    val agentRoles: List<AgentRole> = emptyList(),
    val activeRoleId: String = AgentRole.BUILTIN_ALL_ROUNDER_ID,

    // ═══ 工具权限（v1.0 #155 opencode 式权限模式）═══
    // 两模式共享的 ToolExecutor 门控：模式（BYPASS/DEFAULT/ACCEPT_EDITS/PLAN）
    // + 按序首匹配规则三元组（ALLOW/ASK/DENY，pattern 支持尾缀星号通配，
    // 覆盖 mcp__ 动态 id）。决策器见 PermissionDecider，门见 PermissionModeGate。
    val permissionMode: PermissionMode = PermissionMode.DEFAULT,
    val permissionRules: List<PermissionRule> = emptyList(),

    // ═══ 行为规则（v1.1 #164 Rules 规则系统）═══
    // 全局规则：设置层持久化的自由文本，对所有工作区/两种模式生效，
    // 注入系统提示词 "## Global Rules" 段（coding 模式经 additionalSystemContext
    // 通道、agent 模式经 EnginePrompts.globalRules 参数——二选一，防双注）。
    // 项目规则（AGENTS.md/CLAUDE.md/.cursorrules）不持久化，由 RulesProvider
    // 每轮从工作区即时发现。默认空串 = 无规则，向后兼容零迁移。
    val globalRules: String = "",
)
