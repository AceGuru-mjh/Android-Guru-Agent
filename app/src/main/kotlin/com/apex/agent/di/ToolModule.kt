package com.apex.agent.di

import com.apex.agent.core.tools.*
import com.apex.agent.core.tools.builtin.ShellExecuteTool
import com.apex.agent.platform.privilege.PrivilegeDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ToolModule {

    @Provides
    @Singleton
    fun provideToolRegistry(): ToolRegistry {
        val registry = DefaultToolRegistry()
        
        // 注册Shell工具
        registry.register(ShellExecuteTool { command ->
            val result = PrivilegeDetector.executeShell(command)
            if (result.success) {
                result.output.ifBlank { "(command completed, no output)" }
            } else {
                "Error (exit ${result.exitCode}): ${result.output}"
            }
        })
        
        return registry
    }

    @Provides
    @Singleton
    fun provideToolExecutor(registry: ToolRegistry): ToolExecutor {
        return DefaultToolExecutor(registry)
    }
}
