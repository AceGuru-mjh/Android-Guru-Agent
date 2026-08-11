package com.apex.agent.platform.csmem.di

import android.content.Context
import androidx.room.Room
import com.apex.agent.platform.csmem.store.MemoryGraphDatabase
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import com.apex.agent.platform.csmem.store.MemoryGraphStoreImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * CS-Mem Hilt DI 模块。
 *
 * 提供：
 * - MemoryGraphDatabase (Room)
 * - MemoryGraphStore (接口实现 -> MemoryGraphStoreImpl)
 */
@Module
@InstallIn(SingletonComponent::class)
object CsMemModule {

    @Provides
    @Singleton
    fun provideMemoryGraphDatabase(
        @ApplicationContext context: Context
    ): MemoryGraphDatabase {
        return Room.databaseBuilder(
            context,
            MemoryGraphDatabase::class.java,
            "cs_mem_graph.db"
        )
        .fallbackToDestructiveMigration() // 开发期：schema 变更时重建
        .build()
    }

    @Provides
    @Singleton
    fun provideMemoryGraphStore(
        db: MemoryGraphDatabase
    ): MemoryGraphStore {
        return MemoryGraphStoreImpl(db)
    }
}
