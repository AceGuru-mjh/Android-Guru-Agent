package com.apex.agent.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.llm.LlmClientFactory
import com.apex.agent.core.llm.LlmConfig
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ModelsCatalog
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.core.llm.RemoteModelInfo
import com.apex.agent.core.tools.hook.HookRegistry
import com.apex.agent.ui.language.LanguageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    private val languageManager: LanguageManager,
    // Issue #165：钩子注册表（设置页启停声明式钩子）
    private val hookRegistry: HookRegistry,
    // Issue #164：项目规则状态预计算（默认工作区 AGENTS.md 是否存在）
    @ApplicationContext private val appContext: Context
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

    // ═══ Issue #165：钩子启停（设置页 HooksSettingsSection 数据源）═══

    /** 声明式钩子快照（文件序；注册表 changes 广播后自动刷新）。 */
    private val _hooks = MutableStateFlow(hookRegistry.getConfigs().map { it.toHookUi() })
    val hooks: StateFlow<List<HookUiModel>> = _hooks.asStateFlow()

    init {
        // 配置变更（启停 / 未来增删）→ 快照刷新；SharedFlow 无粘性，先取快照再订阅
        viewModelScope.launch {
            hookRegistry.changes.collect {
                _hooks.value = hookRegistry.getConfigs().map { it.toHookUi() }
            }
        }
    }

    /** 启用/禁用一条声明式钩子（IO 落盘；失败仅 snackbar 提示）。 */
    fun setHookEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            hookRegistry.setEnabled(id, enabled)
                .onFailure {
                    _hookMessage.value = it.message ?: "操作失败"
                }
        }
    }

    /** 钩子操作的非阻断提示（一次性消费；简单起见不用 Channel）。 */
    private val _hookMessage = MutableStateFlow<String?>(null)
    val hookMessage: StateFlow<String?> = _hookMessage.asStateFlow()

    fun consumeHookMessage() {
        _hookMessage.value = null
    }

    private fun HookRegistry.HookConfig.toHookUi() = HookUiModel(
        id = id,
        name = name,
        eventLabel = event.name,
        pattern = pattern,
        isSystem = system,
        enabled = enabled
    )

    // ═══ Issue #164：项目规则状态（默认工作区 AGENTS.md）═══

    /**
     * 默认编码工作区根（与 CodeModule 的 workspaceRoots 同源路径）。
     * 设置页只展示默认工作区——活动工作区随 Code 屏切换，设置页不追踪。
     */
    private val defaultWorkspaceRoot: File
        get() = File(appContext.filesDir, "linux/workspaces/default")

    /** 默认工作区命中的规则文件名（AGENTS.md / CLAUDE.md / .cursorrules；null=未创建）。 */
    suspend fun projectRulesFileName(): String? = withContext(Dispatchers.IO) {
        RULES_FILE_CANDIDATES.firstOrNull { File(defaultWorkspaceRoot, it).isFile }
    }

    /** 在默认工作区创建 AGENTS.md 模板（已存在则不覆盖，返回是否新建）。 */
    suspend fun createProjectRulesTemplate(): Boolean = withContext(Dispatchers.IO) {
        val target = File(defaultWorkspaceRoot, "AGENTS.md")
        if (target.exists()) return@withContext false
        defaultWorkspaceRoot.mkdirs()
        target.writeText(PROJECT_RULES_TEMPLATE)
        true
    }
}

/** 项目规则命中优先级（与 core RulesProvider 一致；显示用）。 */
private val RULES_FILE_CANDIDATES = listOf("AGENTS.md", "CLAUDE.md", ".cursorrules")

/** 项目规则模板（简短引导 + 常用维度提示，用户可自由改写）。 */
private const val PROJECT_RULES_TEMPLATE = """# AGENTS.md

本文件是本工作区的项目规则：coding 会话每轮自动注入（子目录可放更多
AGENTS.md，越靠近当前文件的越优先）。

## 代码风格
- （示例）使用 Kotlin 协程与 Flow，禁止阻塞主线程
- （示例）公共 API 必须有 KDoc，说明「为什么」而非「是什么」

## 提交纪律
- （示例）Conventional Commits：feat/fix/docs/refactor/test/chore

## 注意事项
- （示例）不要改动 build.gradle.kts 的依赖版本，除非我明确要求
"""

data class TestResult(
    val success: Boolean,
    val message: String
)
