package com.apex.agent.di

import com.apex.agent.platform.terminal.TerminalManager
import com.apex.agent.platform.terminal.pty.JniNativePty
import com.apex.agent.platform.terminal.pty.NativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicy
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.tools.*
import com.apex.agent.core.tools.ToolRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TerminalModule {

    @Provides
    @Singleton
    fun provideTerminalManager(): TerminalManager = TerminalManager()

    /** Bind the [NativePty] interface to the JNI-backed production adapter (Spec §2.2/§44.1). */
    @Provides
    @Singleton
    fun provideNativePty(): NativePty = JniNativePty()

    @Provides
    @Singleton
    fun provideTerminalPolicy(): TerminalPolicy = TerminalPolicyImpl()

    @Provides
    @Singleton
    fun provideTerminalRuntime(
        native: NativePty,
        policy: TerminalPolicy
    ): TerminalRuntime = TerminalRuntimeImpl(native, policy)
}

// 在 ToolModule.provideToolRegistry() 中注册：
// registry.register(TerminalCreateTool(terminalManager))
// registry.register(TerminalExecTool(terminalManager))
// registry.register(TerminalSendTool(terminalManager))
// registry.register(TerminalReadTool(terminalManager))
// registry.register(TerminalListTool(terminalManager))
// registry.register(TerminalCloseTool(terminalManager))
