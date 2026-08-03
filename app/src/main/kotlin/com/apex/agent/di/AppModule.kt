package com.apex.agent.di

import android.content.Context
import com.apex.agent.platform.privilege.DefaultPrivilegeManager
import com.apex.agent.platform.privilege.PrivilegeManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * App-level DI module: provides only the PrivilegeManager.
 *
 * Other providers have been split into focused modules:
 * - [LlmModule]            — LlmConfig + LlmClient (constructs its own OkHttpClient via LlmClientFactory)
 * - [ToolModule]            — ToolRegistry + ToolExecutor (with ShellExecuteTool wired to PrivilegeDetector)
 * - [AgentModule]           — AgentConfig + AgentEngine (ApexAgentEngine)
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePrivilegeManager(
        @ApplicationContext context: Context
    ): PrivilegeManager {
        return DefaultPrivilegeManager(context)
    }
}
