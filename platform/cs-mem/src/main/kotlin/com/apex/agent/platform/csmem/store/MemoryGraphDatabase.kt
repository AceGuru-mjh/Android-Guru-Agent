package com.apex.agent.platform.csmem.store

import androidx.room.Database
import androidx.room.RoomDatabase
import com.apex.agent.platform.csmem.store.dao.*
import com.apex.agent.platform.csmem.store.entity.*

/**
 * CS-Mem 图存储数据库。
 *
 * 基于 Room + SQLite，用关系型图映射 Schema 实现轻量级图存储。
 * 支持 JSON1 扩展和 FTS5 全文搜索（未来可扩展）。
 */
@Database(
    entities = [
        EpisodeEntity::class,
        NodeEntity::class,
        EdgeEntity::class,
        FSMMacroEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MemoryGraphDatabase : RoomDatabase() {

    abstract fun episodeDao(): EpisodeDao
    abstract fun nodeDao(): NodeDao
    abstract fun edgeDao(): EdgeDao
    abstract fun fsmMacroDao(): FSMMacroDao
}
