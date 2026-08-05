package com.apex.agent.di

import android.content.Context
import com.apex.agent.attachment.AttachmentCleanupManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 附件清理 DI 模块。
 *
 * 通过 [@Provides][Provides] 显式构造 [AttachmentCleanupManager] 单例，
 * 并在构造时立即调度周期性清理任务（[schedulePeriodicCleanup]）。
 *
 * 注意：[AttachmentCleanupManager] 本身不带 `@Inject constructor`，
 * 由本模块统一提供，避免 Hilt 重复绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
object AttachmentModule {

    @Provides
    @Singleton
    fun provideAttachmentCleanupManager(
        @ApplicationContext context: Context
    ): AttachmentCleanupManager {
        return AttachmentCleanupManager(context).apply {
            // 构造完成后立即调度周期性清理（24h 一次，KEEP 策略避免重复）
            schedulePeriodicCleanup()
        }
    }
}
