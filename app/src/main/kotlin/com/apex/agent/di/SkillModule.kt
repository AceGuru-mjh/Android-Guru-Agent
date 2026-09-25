package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillMenuProvider
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.core.tools.connector.ConnectorMessenger
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.plugin.host.PluginManager
import com.apex.agent.ui.component.SlashMenuProvider
import okhttp3.OkHttpClient
import com.apex.agent.ui.language.LanguageManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SkillModule {

    @Provides
    @Singleton
    fun provideSkillRegistry(@ApplicationContext context: Context): SkillRegistry {
        return SkillRegistry(File(context.filesDir, "skills"))
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

    /**
     * 连接器消息发送器（微信 ClawBot/企业微信、飞书、QQ、Telegram）。
     *
     * 单例提供而不是在 ToolModule 里就地 new：内置 IM MCP 服务器与
     * connector_send_message 工具必须共享同一实例（QQ/飞书 token 缓存随实例存活，
     * 两份实例会各自换 token、白白多一倍请求）。
     */
    @Provides
    @Singleton
    fun provideConnectorMessenger(httpClient: OkHttpClient): ConnectorMessenger {
        return ConnectorMessenger(httpClient)
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
