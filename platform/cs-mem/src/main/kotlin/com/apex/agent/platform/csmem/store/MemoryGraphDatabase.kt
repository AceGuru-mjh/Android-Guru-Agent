package com.apex.agent.platform.csmem.store

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        FSMMacroEntity::class,
        MigrationMapEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class MemoryGraphDatabase : RoomDatabase() {

    abstract fun episodeDao(): EpisodeDao
    abstract fun nodeDao(): NodeDao
    abstract fun edgeDao(): EdgeDao
    abstract fun fsmMacroDao(): FSMMacroDao
    abstract fun migrationDao(): MigrationDao

    companion object {
        /**
         * v1 → v2 迁移：保留全部旧数据（非 destructive）。
         * - nodes 表加列 app_version（默认 NULL，旧节点视为无版本标记）
         * - 新建 migration_map 表（拓扑同胚映射）
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE nodes ADD COLUMN app_version TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS migration_map (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        old_fingerprint TEXT NOT NULL,
                        new_fingerprint TEXT NOT NULL,
                        match_score REAL NOT NULL,
                        from_version TEXT NOT NULL,
                        to_version TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_migration_map_old_fingerprint ON migration_map(old_fingerprint)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_migration_map_new_fingerprint ON migration_map(new_fingerprint)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_migration_map_to_version ON migration_map(to_version)"
                )
            }
        }
    }
}
