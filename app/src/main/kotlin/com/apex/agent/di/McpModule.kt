package com.apex.agent.di

import com.apex.agent.core.tools.mcp.McpManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallsIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object McpModule {

    @Provides
    @Singleton
    fun provideMcpManager(@ApplicationContext context: Context): McpManager {
        val configDir = File(context.filesDir, "mcp_config")
        return McpManager(configDir)
    }
}
