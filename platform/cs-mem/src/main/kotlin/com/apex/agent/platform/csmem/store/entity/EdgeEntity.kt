package com.apex.agent.platform.csmem.store.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 图边实体 —— 存储节点间的所有关系。
 *
 * 分三种类型：
 * - SPATIAL:  空间拓扑（相邻、嵌套）
 * - CAUSAL:   因果关系（动作触发→状态跃迁）
 * - SEMANTIC: 语义关联
 */
@Entity(
    tableName = "edges",
    foreignKeys = [
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_node_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["episode_id"]),
        Index(value = ["source_node_id"]),
        Index(value = ["target_node_id"]),
        Index(value = ["type"])
    ]
)
data class EdgeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "episode_id")
    val episodeId: String?,

    /** 边在图表中的唯一 id */
    @ColumnInfo(name = "edge_label")
    val edgeLabel: String,

    @ColumnInfo(name = "source_node_id")
    val sourceNodeId: Long,

    @ColumnInfo(name = "target_node_id")
    val targetNodeId: Long,

    /** SPATIAL / CAUSAL / SEMANTIC */
    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "metadata")
    val metadata: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** 记忆能量值 */
    @ColumnInfo(name = "energy")
    val energy: Float = 1.0f
)
