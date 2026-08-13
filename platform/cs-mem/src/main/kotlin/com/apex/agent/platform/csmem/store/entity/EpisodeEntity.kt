package com.apex.agent.platform.csmem.store.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 任务会话 EPISODE 实体。
 *
 * 每个 Episode 记录一次 Agent 任务的完整生命周期元数据。
 * 多个 Episode 共享同一张 Nodes/Edges 字典表。
 */
@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey
    @ColumnInfo(name = "episode_id")
    val episodeId: String,

    @ColumnInfo(name = "goal")
    val goal: String,

    @ColumnInfo(name = "app_package")
    val appPackage: String?,

    @ColumnInfo(name = "activity_name")
    val activityName: String?,

    /** 任务状态: RUNNING, SUCCEEDED, FAILED, DISTILLED */
    @ColumnInfo(name = "status")
    val status: String,

    /** 任务开始时间戳 (ms) */
    @ColumnInfo(name = "started_at")
    val startedAt: Long,

    /** 任务结束时间戳 (ms)，进行中任务为 0 */
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long,

    /** LLM 思考步数 */
    @ColumnInfo(name = "llm_steps")
    val llmSteps: Int = 0,

    /** 总执行动作数 */
    @ColumnInfo(name = "total_actions")
    val totalActions: Int = 0,

    /** 是否已被蒸馏为 FSM 宏技能 */
    @ColumnInfo(name = "is_distilled")
    val isDistilled: Boolean = false,

    /** 蒸馏产出的 skillId（为空表示未蒸馏） */
    @ColumnInfo(name = "skill_id")
    val skillId: String? = null,

    /** 记忆能量值（用于熵增遗忘机制），初始 1.0 */
    @ColumnInfo(name = "energy")
    val energy: Float = 1.0f,

    /** 成功执行次数（用于熵增遗忘机制） */
    @ColumnInfo(name = "success_count")
    val successCount: Int = 0
)
