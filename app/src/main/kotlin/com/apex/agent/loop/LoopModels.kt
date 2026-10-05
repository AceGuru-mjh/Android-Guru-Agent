package com.apex.agent.loop

import kotlinx.serialization.Serializable

/**
 * ═══ S2 — Loop 循环与 Cron 模式：数据模型 ═══
 *
 * LOOP 模式的三种子形态（[LoopKind]）：
 * - INTERVAL：固定间隔重复（每 N 毫秒一轮，下限 [LoopModels.MIN_INTERVAL_MS]）；
 * - CRON：标准 5 字段 Vixie cron（分 时 日 月 周），解析复用 core 侧
 *   [com.apex.agent.core.tools.builtin.VixieCron]（cron_next 工具同源，绝不复制逻辑）；
 * - ONCE：一次性提醒（triggerAt 到点触发一轮后自动停用）。
 *
 * 设计决策（KDoc 留档，见 LoopScheduler）：
 * - 循环独立于 AgentMode 存活 —— 用户切走 LOOP 模式不停止循环；
 *   只有 runsDone 达到 maxRuns、ONCE 到点跑完或用户显式停止才结束。
 * - mode != LOOP 时到期的轮次只记 runLog（notifyOnRun 且 App 在后台时补通知），
 *   不注入当前会话。
 */
@Serializable
enum class LoopKind { INTERVAL, CRON, ONCE }

/**
 * 一条循环配置（filesDir/loops/loops.json 里的一等实体，kotlinx 序列化）。
 *
 * @param id 稳定标识（UUID，[LoopModels.newId]）。
 * @param prompt 循环提示词：每轮以「[loop 第N轮] 前缀」注入会话。
 * @param kind 子形态（INTERVAL / CRON / ONCE）。
 * @param intervalMs INTERVAL 用：轮次间隔毫秒（钳制下限 30s 防打爆）。
 * @param cronExpr CRON 用：5 字段表达式（分 时 日 月 周）。
 * @param triggerAt ONCE 用：目标时刻 epoch ms。
 * @param maxRuns 最大执行轮数（ONCE 固定 1）；达到即自动 enabled=false。
 * @param runsDone 已执行轮数（调度器标记，UI 状态卡「第 N/M 轮」）。
 * @param enabled 总开关：false = 已停用（保留配置与 runLog，不删除）。
 * @param createdAt 创建时刻 epoch ms（INTERVAL 首轮基点）。
 * @param lastRunAt 最近一次触发时刻 epoch ms（INTERVAL 后续轮基点；0 = 从未跑过）。
 * @param sessionTag 绑定的会话标识（当前会话 id，无则 "default"）——
 *   恢复会话 / 判定 dueEvent 归属用。
 * @param notifyOnRun App 在后台时到点发系统通知（ONCE 提醒恒发，INTERVAL/CRON 看此开关）。
 */
@Serializable
data class LoopConfig(
    val id: String,
    val prompt: String,
    val kind: LoopKind,
    val intervalMs: Long = 300_000L,
    val cronExpr: String = "",
    val triggerAt: Long = 0L,
    val maxRuns: Int = 10,
    val runsDone: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    val lastRunAt: Long = 0L,
    val sessionTag: String = "",
    val notifyOnRun: Boolean = true
)

/** 单轮触发记录（每循环上限 [LoopModels.MAX_RUN_LOGS] 条，FIFO 截断）。 */
data class LoopRunLog(
    val at: Long,
    val ok: Boolean,
    val summary: String
)

object LoopModels {

    /** INTERVAL 最小间隔（30s）：防打爆——LLM 轮次天然是分钟级操作。 */
    const val MIN_INTERVAL_MS: Long = 30_000L

    /** 每循环保留的 runLog 条数上限（FIFO）。 */
    const val MAX_RUN_LOGS: Int = 20

    /** 新循环 id（UUID）。 */
    fun newId(): String = java.util.UUID.randomUUID().toString()
}
