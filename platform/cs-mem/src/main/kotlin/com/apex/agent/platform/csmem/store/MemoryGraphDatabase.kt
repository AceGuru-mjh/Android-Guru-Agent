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
    version = 4,
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
                // P1 fix（审计 6-b）：列定义必须与 NodeEntity.appVersion（String?，可空无默认）
                // 逐字匹配 —— 原 `TEXT NOT NULL DEFAULT ''` 使升级路径 Room checkIdentity
                // 失败（DB 永远打不开）。可空无 DEFAULT，旧节点语义即 NULL（无版本标记）。
                db.execSQL(
                    "ALTER TABLE nodes ADD COLUMN app_version TEXT"
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

        /**
         * v2 → v3 迁移：边去重 + 同 Episode 内边标签唯一。
         *
         * 背景：旧边 ID 是帧内自增计数器（e_0, e_1…每帧重置），同一拓扑边
         * 反复落库形成重复行；且 deleteByLabels 按标签全局删除会跨 Episode
         * 误删。v3 起：
         * - 边 ID 改为内容哈希（见 UiTreePruner.stableEdgeId），同一条边天然同 ID；
         * - 清理历史重复行（同 episode 同 label 保留最小 id）；
         * - 建 (episode_id, edge_label) 唯一索引，使 upsert 幂等去重；
         * - deleteByLabels 增加 episode 作用域参数。
         *
         * 注意：episode_id 为 NULL 的历史行不受唯一约束影响（SQLite 唯一索引
         * 对 NULL 不去重），仅保留审计用途，不再产生（新写入全部携带 episode）。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 同 (episode, label) 分组保留最小 id，删除其余重复行
                db.execSQL(
                    """
                    DELETE FROM edges WHERE id NOT IN (
                        SELECT MIN(id) FROM edges
                        WHERE episode_id IS NOT NULL
                        GROUP BY episode_id, edge_label
                    ) AND episode_id IS NOT NULL
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_edges_episode_label " +
                        "ON edges(episode_id, edge_label)"
                )
            }
        }

        /**
         * v3 → v4 迁移：把 (episode_id, edge_label) 唯一索引补进真实 Schema。
         *
         * 背景（审计 P1-2）：v3 只在 MIGRATION_2_3 里建了该索引，[EdgeEntity] 未声明 →
         * 升级路径 Room checkIdentity 失败（DB 打不开）；全新装机又不建该索引 →
         * upsert 幂等去重失效、重复边无限累积。v4 起 Entity 声明（同名索引）与
         * 迁移产物一致，且 v3 存量库经本迁移去重后补上索引。
         *
         * 兼容三种历史状态：
         * - v2（旧升级失败被事务回滚，停在 v2）→ 2_3 已建同名索引，本迁移幂等 no-op；
         * - v3 全新装机（无该索引，可能已积累重复边）→ 先按 (episode, label) 去重再建索引；
         * - v3 经修复后 2_3 路径（已有索引）→ 去重与建索引均 no-op。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM edges WHERE id NOT IN (
                        SELECT MIN(id) FROM edges
                        WHERE episode_id IS NOT NULL
                        GROUP BY episode_id, edge_label
                    ) AND episode_id IS NOT NULL
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_edges_episode_label " +
                        "ON edges(episode_id, edge_label)"
                )
            }
        }
    }
}
