package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillHotReloadLogLevel
import com.apex.agent.core.tools.skill.SkillMenuProvider
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.plugin.host.PluginManager
import com.apex.agent.ui.component.SlashMenuProvider
import com.apex.agent.ui.language.LanguageManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Module
@InstallIn(SingletonComponent::class)
object SkillModule {

    /**
     * Issue #166：内置技能释放专用后台 scope——SupervisorJob 保证释放失败
     * 不殊及其它单例初始化；Dispatchers.IO 承载 assets 读取与 manifest 落盘。
     * 模块级单发协程（provideSkillRegistry 每进程只构造一次单例），
     * 不存在重复释放窗口。
     */
    private val bundledReleaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideSkillRegistry(@ApplicationContext context: Context): SkillRegistry {
        val registry = SkillRegistry(
            skillsDir = File(context.filesDir, "skills"),
            // core:tool-registry 不依赖 core:logging，SkillRegistry 以注入式
            // 日志出口暴露（同 SkillHotReloader 约定），这里桥接到 AppLogger。
            logger = { level, message ->
                when (level) {
                    SkillHotReloadLogLevel.INFO -> AppLogger.instance.info(
                        LogCategory.PLUGIN, "SkillRegistry", message
                    )
                    SkillHotReloadLogLevel.WARN -> AppLogger.instance.warn(
                        LogCategory.PLUGIN, "SkillRegistry", message
                    )
                }
            }
        )
        // Issue #166：首启（及每次进程启动）幂等释放 assets/skills/ 内置优质技能集。
        // 注册表单例构造即触发，主控无需在别处再调用（接线契约见 releaseBundledSkills KDoc）。
        releaseBundledSkills(context, registry)
        return registry
    }

    /**
     * Issue #166：把 APK assets/skills/ 下打包的内置技能 manifest 释放到
     * `<filesDir>/skills/`（SkillRegistry 的安装目录）。
     *
     * - **IO 线程**：assets 读取 + 落盘全在 [bundledReleaseScope]（Dispatchers.IO），
     *   不阻塞 DI 构造线程；
     * - **失败只记日志不阻断启动**：assets 目录缺失 / 读取异常被 runCatching 吞掉
     *   （记 WARN），单条 JSON 损坏由 [SkillRegistry.installBundled] 的单条隔离兜住；
     * - **每次启动都跑（幂等）**：installBundled 内部做版本比较——已装同版或更高
     *   直接跳过，用户禁用过的技能升级后仍保持禁用态；重复启动零副作用、零重复写入；
     * - **时序说明**：释放是异步的，首启后极短窗口内（通常 <100ms）市场页 /
     *   prompt 注入可能尚未见到内置技能；市场页有 LaunchedEffect 强制刷新、
     *   引擎每轮重读 getPromptInjections，窗口过后自动补齐，无需额外通知。
     */
    private fun releaseBundledSkills(context: Context, registry: SkillRegistry) {
        bundledReleaseScope.launch {
            runCatching {
                val manifestJsons = context.assets.list("skills")
                    ?.filter { it.endsWith(".json") }
                    ?.sorted()
                    .orEmpty()
                    .map { name ->
                        context.assets.open("skills/$name").use { stream ->
                            stream.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                val added = registry.installBundled(manifestJsons).getOrDefault(0)
                AppLogger.instance.info(
                    LogCategory.PLUGIN, "SkillModule",
                    "内置技能释放完成：assets 共 ${manifestJsons.size} 个，本次新增 $added 个"
                )
            }.onFailure {
                AppLogger.instance.warn(
                    LogCategory.PLUGIN, "SkillModule",
                    "内置技能释放失败（不阻断启动）：${it.message}"
                )
            }
        }
    }

    @Provides
    @Singleton
    fun provideSkillMenuProvider(skillRegistry: SkillRegistry): SkillMenuProvider {
        return SkillMenuProvider(skillRegistry)
    }

    /** 连接器注册表（市场页「连接器」页签 + 斜杠菜单数据源）。 */
    @Provides
    @Singleton
    fun provideConnectorRegistry(@ApplicationContext context: Context): ConnectorRegistry {
        return ConnectorRegistry(File(context.filesDir, "mcp_config"))
    }

    @Provides
    @Singleton
    fun provideSlashMenuProvider(
        skillMenuProvider: SkillMenuProvider,
        skillRegistry: SkillRegistry,
        mcpManager: McpManager,
        pluginManager: PluginManager,
        connectorRegistry: ConnectorRegistry,
        languageManager: LanguageManager
    ): SlashMenuProvider {
        return SlashMenuProvider(
            skills = skillMenuProvider,
            skillRegistry = skillRegistry,
            mcpManager = mcpManager,
            pluginManager = pluginManager,
            connectorRegistry = connectorRegistry,
            languageManager = languageManager
        )
    }
}
