package com.apex.agent.ui.language

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.apex.agent.ui.screen.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语言切换基建（system | zh | en）。
 *
 * 完整链路：设置页 Dropdown → [SettingsRepository.agentSettings].language 持久化 →
 * [com.apex.agent.MainActivity] collect 后 `recreate()` → 新实例的 `attachBaseContext`
 * 重新包裹 Locale（本类 companion 静态快读，绕开 Hilt 注入时序）→ Compose
 * `stringResource` 经 Activity 的 Resources 取 values-zh / values(默认英文)。
 *
 * 实现要点：
 *  - `attachBaseContext` 早于 `onCreate` / Hilt 字段注入，Activity 侧**不能**依赖
 *    本单例 —— 用 [resolveLanguageFromPrefs] 直接读 `apex_settings` 的
 *    `agent_settings_v2` JSON（正则抠字段，不为一次读取实例化整个 Repository）；
 *  - [resolveContext] / [applyLanguage] 用 `createConfigurationContext` 包裹，
 *    `language == "system"` 时原样返回 base（交系统 locale）；
 *  - [getString] 供 ViewModel / 服务层等非 Compose 场景按当前语言取词，
 *    内部维护 resolvedContext 缓存并在语言变化时刷新。
 */
@Singleton
class LanguageManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    settingsRepository: SettingsRepository
) {
    /** 单例自管理作用域（跟随进程生命周期；仅收集设置流，无泄漏面）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _language = MutableStateFlow(settingsRepository.agentSettings.value.language)

    /** 当前语言（"system" | "zh" | "en"）；设置中心驱动，即时更新。 */
    val language: StateFlow<String> = _language.asStateFlow()

    /** 当前语言已解析的 Context 缓存（语言变化时刷新，供 [getString] 取词）。 */
    @Volatile
    private var resolvedContext: Context = applyLanguage(appContext, _language.value)

    init {
        // 设置中心 language 变化 → 更新流与缓存；Activity 侧另行 collect 后 recreate
        scope.launch {
            settingsRepository.agentSettings.collect { settings ->
                if (settings.language != _language.value) {
                    _language.value = settings.language
                    resolvedContext = applyLanguage(appContext, settings.language)
                }
            }
        }
    }

    /** 按当前语言包裹 base（zh/en 包对应 Locale，其余原样返回）。 */
    fun resolveContext(base: Context): Context = applyLanguage(base, _language.value)

    /** 非 Compose 场景（ViewModel / 服务层）按当前语言取字符串资源。 */
    fun getString(@StringRes resId: Int): String = resolvedContext.getString(resId)

    /** 非 Compose 场景带格式化参数（等价 Context.getString(resId, formatArgs)）。 */
    fun getString(@StringRes resId: Int, vararg formatArgs: Any): String =
        resolvedContext.getString(resId, *formatArgs)

    companion object {
        const val LANG_SYSTEM = "system"
        const val LANG_ZH = "zh"
        const val LANG_EN = "en"

        /** agent_settings_v2 JSON 里抠 "language":"..." 字段（值不含转义引号）。 */
        private val LANGUAGE_REGEX = Regex("\"language\"\\s*:\\s*\"([^\"]*)\"")

        /**
         * attachBaseContext 时机早于 Hilt 注入 —— 静态快速读持久化语言。
         * 读不到 / 解析失败一律回落 "system"（交系统 locale）。
         */
        fun resolveLanguageFromPrefs(context: Context): String = runCatching {
            val raw = context
                .getSharedPreferences("apex_settings", Context.MODE_PRIVATE)
                .getString("agent_settings_v2", null)
            raw?.let { LANGUAGE_REGEX.find(it)?.groupValues?.get(1) } ?: LANG_SYSTEM
        }.getOrDefault(LANG_SYSTEM)

        /** 语言 → Context：zh/en 用 createConfigurationContext 包 Locale，其余原样返回。 */
        fun applyLanguage(base: Context, language: String): Context = when (language) {
            LANG_ZH -> base.createConfigurationContext(
                Configuration().apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            )
            LANG_EN -> base.createConfigurationContext(
                Configuration().apply { setLocale(Locale.ENGLISH) }
            )
            else -> base
        }
    }
}
