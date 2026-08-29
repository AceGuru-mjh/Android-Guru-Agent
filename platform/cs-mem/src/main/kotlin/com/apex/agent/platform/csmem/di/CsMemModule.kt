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
        .addMigrations(MemoryGraphDatabase.MIGRATION_1_2) // v1→v2 保留旧数据
        // 仅在版本降级时允许 destructive 重建；升级必须显式提供 Migration，
        // 否则任何未来 schema 升级缺失 Migration 都会静默清空长期记忆 DB。
        // 注：@Database(exportSchema = false)，未来应配置 ksp room.schemaLocation
        // 并切换 exportSchema = true，导出 schema JSON 以做迁移审计/自动测试。
        .fallbackToDestructiveMigrationOnDowngrade()
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
