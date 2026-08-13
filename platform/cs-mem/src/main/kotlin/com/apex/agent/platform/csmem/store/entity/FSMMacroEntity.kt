package com.apex.agent.platform.csmem.store.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * FSM 宏技能实体 —— 存储蒸馏后的确定性有限状态机。
 *
 * 宏技能记录从起始状态到终止状态的确定性转移表，
 * 执行时可绕过 LLM 推理直接注入系统事件，实现毫秒级响应。
 */
@Entity(
    tableName = "fsm_macros",
    indices = [
        Index(value = ["skill_id"], unique = true),
        Index(value = ["initial_fingerprint"]),
        Index(value = ["app_package"])
    ]
)
data class FSMMacroEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 技能唯一 ID */
    @ColumnInfo(name = "skill_id")
    val skillId: String,

    /** 技能名称 */
    @ColumnInfo(name = "name")
    val name: String,

    /** 技能描述 */
    @ColumnInfo(name = "description")
    val description: String?,

    /** 起始 UI 指纹（匹配条件） */
    @ColumnInfo(name = "initial_fingerprint")
    val initialFingerprint: String,

    /** 终止 UI 指纹（成功条件） */
    @ColumnInfo(name = "terminal_fingerprint")
    val terminalFingerprint: String,

    /** 状态转移表 JSON 序列化字符串 */
    @ColumnInfo(name = "transitions_json")
    val transitionsJson: String,

    /** 来源 Episode ID */
    @ColumnInfo(name = "source_episode_id")
    val sourceEpisodeId: String?,

    /** App 包名 */
    @ColumnInfo(name = "app_package")
    val appPackage: String?,

    /** 创建时间戳 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** 成功执行次数 */
    @ColumnInfo(name = "success_count")
    val successCount: Int = 0,

    /** 失败执行次数 */
    @ColumnInfo(name = "failure_count")
    val failureCount: Int = 0,

    /** 最后执行时间戳 */
    @ColumnInfo(name = "last_executed_at")
    val lastExecutedAt: Long = 0,

    /** 是否已晶化（固化为底层配置，不可删除） */
    @ColumnInfo(name = "is_crystallized")
    val isCrystallized: Boolean = false,

    /** 记忆能量值 */
    @ColumnInfo(name = "energy")
    val energy: Float = 1.0f
)
