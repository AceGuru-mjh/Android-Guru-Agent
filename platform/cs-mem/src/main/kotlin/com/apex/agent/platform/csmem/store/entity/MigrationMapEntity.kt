package com.apex.agent.platform.csmem.store.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 拓扑同胚迁移映射 —— 跨 App 版本记忆保鲜的"指纹别名"表。
 *
 * 旧版本 UI 拓扑的节点指纹（oldFingerprint）在 App 升级后可能失效；
 * 本表记录其到新版本等价节点指纹（newFingerprint）的映射，供召回/宏匹配层
 * 在做指纹解析时做别名替换，使长期记忆与 FSM 宏在版本演进后仍能复用。
 *
 * 设计要点：
 * - 不重写旧指纹本身（指纹是 stable key，改了会破坏去重与宏的初始/终止指纹），
 *   只建立别名桥；
 * - 一对多/多对一由调用方保证一对一（TopologyMigrator 已按最高分唯一候选落库）；
 * - 低置信度映射不入库（score < 阈值由 migrator 过滤）。
 */
@Entity(
    tableName = "migration_map",
    indices = [
        Index(value = ["old_fingerprint"], unique = true),
        Index(value = ["new_fingerprint"]),
        Index(value = ["to_version"])
    ]
)
data class MigrationMapEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 旧版本节点指纹（stable key） */
    @ColumnInfo(name = "old_fingerprint")
    val oldFingerprint: String,

    /** 新版本等价节点指纹 */
    @ColumnInfo(name = "new_fingerprint")
    val newFingerprint: String,

    /** 映射置信度（0~1，来自属性相似度打分） */
    @ColumnInfo(name = "match_score")
    val matchScore: Float,

    /** 迁移来源 App 版本 */
    @ColumnInfo(name = "from_version")
    val fromVersion: String,

    /** 迁移目标 App 版本 */
    @ColumnInfo(name = "to_version")
    val toVersion: String,

    /** 创建时间戳 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
