package com.apex.agent.di

import com.apex.agent.platform.terminal.pty.JniNativePty
import com.apex.agent.platform.terminal.pty.NativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicy
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.compat.LegacyTerminalManager
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

    /** Compat facade: old TerminalManager API → new Runtime (settle-time DELETED). Spec §35. */
    @Provides
    @Singleton
    fun provideLegacyTerminalManager(runtime: TerminalRuntime): LegacyTerminalManager =
        LegacyTerminalManager(runtime)
}
