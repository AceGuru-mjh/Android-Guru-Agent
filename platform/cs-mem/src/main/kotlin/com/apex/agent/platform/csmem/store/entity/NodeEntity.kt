package com.apex.agent.platform.csmem.store.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 语义节点字典 —— 全局去重后的所有 SemanticNode。
 *
 * 同一逻辑按钮在不同 Episode 中只需存储一次。
 * 指纹相同 = 同一逻辑节点。
 */
@Entity(
    tableName = "nodes",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["role"]),
        Index(value = ["app_package"])
    ]
)
data class NodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 稳定指纹 Hash (SHA-256 前 16 位) */
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,

    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "text_hint")
    val textHint: String?,

    @ColumnInfo(name = "resource_id")
    val resourceId: String?,

    @ColumnInfo(name = "class_name")
    val className: String?,

    /** bounds JSON: {"left":x,"top":y,"right":x,"bottom":y} */
    @ColumnInfo(name = "bounds_json")
    val boundsJson: String,

    /** Bitmask: bit0=clickable, bit1=scrollable, bit2=editable */
    @ColumnInfo(name = "interactive_flags")
    val interactiveFlags: Int = 0,

    /** 首次发现此节点时的 App 包名 */
    @ColumnInfo(name = "app_package")
    val appPackage: String?,

    /** 首次发现时间戳 */
    @ColumnInfo(name = "first_seen_at")
    val firstSeenAt: Long,

    /** 最后出现的时间戳 */
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,

    /** 累计出现次数（跨 Episode） */
    @ColumnInfo(name = "occurrence_count")
    val occurrenceCount: Int = 1,

    /** 记忆能量值 */
    @ColumnInfo(name = "energy")
    val energy: Float = 1.0f
)
