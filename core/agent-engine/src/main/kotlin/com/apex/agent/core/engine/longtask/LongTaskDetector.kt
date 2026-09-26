package com.apex.agent.core.engine.longtask

/**
 * # 长任务判定器（纯函数，零状态零依赖）
 *
 * 「什么样的运行值得留档」是长任务中心的第一道闸门——判定太松，历史列表
 * 被单轮问答淹没；太紧，真正有复跑价值的任务悄悄流失。本对象只做一件事：
 * 把四个聚合信号映射到「是 / 否长任务」与「规模档位」。
 *
 * ## 信号与阈值
 *
 * 四个信号全部来自 [LongTaskTracker] 的事件聚合（迭代数 = IterationStart
 * 计数、工具调用数 = ToolCallStart 计数、时长 = beginRun→endRun、触碰
 * 文件数 = code_edit / code_write 成功调用去重）：
 *
 * | 信号 | MEDIUM | LONG | EPIC |
 * |------|--------|------|------|
 * | 迭代数 | ≥8 | ≥15 | ≥25 |
 * | 工具调用数 | ≥12 | ≥25 | ≥40 |
 * | 时长 | ≥120s | ≥300s | ≥600s |
 * | 触碰文件数 | ≥3 | ≥8 | ≥15 |
 *
 * 阈值的设计依据（每档的完整推导见 [LongTaskMagnitude] 常量区注释）：
 * MEDIUM 档对齐「一个想清楚再做的多步任务」的自然规模；LONG 档对齐引擎
 * 默认 maxIterations=25 的深水区；EPIC 档对齐默认上限打满 + 升档作业。
 *
 * ## 判定语义
 *
 * - **OR 语义**：四维任一达标即判定为长任务——等待型（长编译）与批量型
 *   （多文件快改）都漏不掉；
 * - **取最高档**：`magnitude` 返回所有满足档位中的最高者，EPIC 信号不被
 *   低维信号稀释；
 * - **null = 不是长任务**：`magnitude` 与 [isLongTask] 永远一致（
 *   `isLongTask(s) == (magnitude(s) != null)`），判定逻辑只有一处
 *   （[LongTaskMagnitude.fromSignals]），本对象是它对外的门面。
 *
 * ## 为什么是 object 而不是顶层 fun
 *
 * 与 [LongTaskTemplates] / [LongTaskDiff] 同构：无状态纯函数族收拢在
 * 具名命名空间下，调用点自文档（`LongTaskDetector.isLongTask(...)` 一眼
 * 看出在问谁），也便于未来加第二套阈值（如用户自定义门槛）时挂伴生配置。
 */
object LongTaskDetector {

    /**
     * 一次运行的规模聚合信号（[LongTaskTracker] 在 endRun 时组装）。
     *
     * @param iterations 累计迭代数（IterationStart 事件计数——用计数而非
     *   事件携带的 iteration 序号：Plan 模式多步执行每步从 1 重新编号，
     *   计数才单调可比）。
     * @param toolCalls 累计工具调用数（ToolCallStart 事件计数，含失败）。
     * @param durationMs 运行时长（beginRun → endRun 墙钟差，毫秒）。
     * @param filesTouched 触碰文件数（code_edit / code_write 成功调用提取
     *   path 去重后的数量）。
     */
    data class Signals(
        val iterations: Int,
        val toolCalls: Int,
        val durationMs: Long,
        val filesTouched: Int
    )

    /**
     * 判定一组信号是否构成「值得留档的长任务」。
     *
     * 四维任一达到 MEDIUM 门槛即为 true。与 [magnitude] 的关系：
     * `isLongTask(s) == (magnitude(s) != null)`——两个入口共享同一阶梯
     * （[LongTaskMagnitude.fromSignals]），不存在「是长任务但无档位」的
     * 矛盾状态。
     */
    fun isLongTask(s: Signals): Boolean = LongTaskMagnitude.fromSignals(s) != null

    /**
     * 推断规模档位；null = 不是长任务（四维全部低于 MEDIUM 门槛）。
     *
     * 纯函数、零副作用：同样输入永远同样输出，无时钟无随机——阈值判定
     * 必须可复现（测试与线上行为一致，也便于未来做阈值回归基线）。
     */
    fun magnitude(s: Signals): LongTaskMagnitude? = LongTaskMagnitude.fromSignals(s)
}
