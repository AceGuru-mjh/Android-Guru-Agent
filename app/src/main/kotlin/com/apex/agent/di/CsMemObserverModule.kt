package com.apex.agent.di

import com.apex.agent.core.engine.ExecutionMemoryObserver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 将 [ExecutionMemoryObserver] 接口绑定到其 CS-Mem 实现 [CsMemSessionObserver]。
 *
 * 必须单独放在 abstract @Module 中：Hilt 不允许同一个 @Module 同时包含
 * abstract 绑定方法（@Binds）与非静态提供方法（@Provides，需位于 object）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CsMemObserverModule {

    @Binds
    @Singleton
    abstract fun bindExecutionMemoryObserver(
        impl: CsMemSessionObserver
    ): ExecutionMemoryObserver
}
