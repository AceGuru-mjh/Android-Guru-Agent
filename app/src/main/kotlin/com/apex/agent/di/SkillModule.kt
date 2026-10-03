package com.apex.agent.di

import android.content.Context
import com.apex.agent.BuildConfig
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillActivationStore
import com.apex.agent.core.tools.skill.SkillAutoActivator
import com.apex.agent.core.tools.skill.SkillHotReloadLogLevel
import com.apex.agent.core.tools.skill.SkillMenuProvider
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.plugin.host.PluginManager
import com.apex.agent.ui.component.SlashMenuProvider
import com.apex.agent.ui.language.LanguageManager
import com.apex.agent.update.HotContentStore
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
        // Hub v2：随包技能已全部迁官方仓库（apex-skill-hub，67 技能按需安装），
        // assets/skills 不再打包 —— 本释放管线仍保留：热更技能重放 + pruneStaleBundled
        // 反向清理旧 APK 释放过的内置残留（升级用户自动迁移到仓库按需安装模式）。
        releaseBundledSkills(context, registry)
        return registry
    }

    /**
     * Issue #166：把 APK assets/skills/ 下打包的内置技能 manifest 释放到
     * `<filesDir>/skills/`（SkillRegistry 的安装目录）。
     *
     * Hub v2（docs/hub-ecosystem.md）：随包技能已全部迁官方仓库
     * （apex-skill-hub），assets/skills 不再打包 —— 空集是合法状态：
     * 安装零副作用，pruneStaleBundled 空白名单会把旧 APK 释放过的内置
     * 技能全部四清（升级用户改从市场按需安装），热更通道不受影响。
     *
     * v1.4.7 热更通道接入（docs/hot-update-pipeline.md）：
     * - **热更技能重放**：`filesDir/hot/active` 里生效中的热更技能清单
     *   （数据层累积快照）追加进 installBundled 队尾 —— 版本化幂等升级，
     *   与 assets 通道同一套语义（保持用户启停态）；
     * - **白名单合并**：pruneStaleBundled 的清理白名单 = assets id ∪ 热更
     *   id —— 防「热更装上的新技能」被下次启动按 assets 白名单反向清理
     *   （assets 还是旧 APK 的快照，没有热更新增的 id）。
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
                val assetNames = context.assets.list("skills")
                    ?.filter { it.endsWith(".json") }
                    ?.sorted()
                    .orEmpty()
                val manifestJsons = assetNames.map { name ->
                    context.assets.open("skills/$name").use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    }
                }
                // 热更 overlay：数据层快照重放（无热更内容时为空集/空表，零影响）。
                // store 读取入口自带「APK 追平退役」——重装新版 APK 后旧热更
                // 内容自动让位给随包 assets。
                val hotStore = HotContentStore(
                    context.filesDir,
                    HotContentStore.SharedPrefs(context),
                    BuildConfig.VERSION_CODE
                )
                val hotManifests = hotStore.activeSkillManifests()
                // Hub 生态迁移：assets 白名单（<id>.json → id）反查清理「曾经内置、
                // 现已迁往官方仓库」的旧条目（详见 SkillRegistry.pruneStaleBundled）。
                // 白名单并入热更 id：热更新增的技能同样受「不可卸载的内置」保护，
                // 不被旧 APK 的 assets 快照误清理。
                val bundledIds = assetNames.map { it.removeSuffix(".json") }.toSet() +
                    hotStore.activeSkillIds()
                val pruned = registry.pruneStaleBundled(bundledIds)
                // assets 先、热更后：同 id 时热更版本 ≥ assets（数据层累积快照），
                // installBundled 的版本比较保证热更升级胜出
                val added = registry.installBundled(manifestJsons + hotManifests)
                    .getOrDefault(0)
                AppLogger.instance.info(
                    LogCategory.PLUGIN, "SkillModule",
                    "内置技能释放完成：assets 共 ${manifestJsons.size} 个" +
                        (if (hotManifests.isNotEmpty()) " + 热更 ${hotManifests.size} 个" else "") +
                        "，本次新增 $added 个" +
                        (if (pruned > 0) "，内置瘦身清理 $pruned 个（已迁官方仓库）" else "")
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

    /**
     * 技能会话激活存储（渐进披露）：与引擎 / skill_activate 工具 /
     * 斜杠指令 / 自动装备器四处共享同一 @Singleton 实例。FIFO 上限 8
     * （每个技能方法论 2-4KB，激活集封顶 ≈ 30KB，请求体积有界）。
     */
    @Provides
    @Singleton
    fun provideSkillActivationStore(): SkillActivationStore {
        return SkillActivationStore(maxActive = 8)
    }

    /**
     * 技能自动装备器：用户消息与技能 tags/id/name 字面命中 → 零成本预激活。
     * 由 AgentChatViewModel 发送路径调用（发送前装备，本轮系统提示词即携带）。
     */
    @Provides
    @Singleton
    fun provideSkillAutoActivator(
        skillRegistry: SkillRegistry,
        activationStore: SkillActivationStore
    ): SkillAutoActivator {
        return SkillAutoActivator(skillRegistry, activationStore, maxActivations = 2)
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
