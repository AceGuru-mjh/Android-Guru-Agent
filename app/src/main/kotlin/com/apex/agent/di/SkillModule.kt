package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillMenuProvider
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.plugin.host.PluginManager
import com.apex.agent.ui.component.SlashMenuProvider
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

    @Provides
    @Singleton
    fun provideSlashMenuProvider(
        skillMenuProvider: SkillMenuProvider,
        mcpManager: McpManager,
        pluginManager: PluginManager,
        connectorRegistry: ConnectorRegistry
    ): SlashMenuProvider {
        return SlashMenuProvider(skillMenuProvider, mcpManager, pluginManager, connectorRegistry)
    }
}
