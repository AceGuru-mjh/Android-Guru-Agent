package com.apex.agent.core.engine.goal

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * # Goal 模式协调器（v3）
 *
 * 持有当前活动目标的全局状态（单例——同一时刻一个目标），供两侧消费：
 * - **引擎侧**（[com.apex.agent.core.engine.ApexAgentEngine] 每轮纯文本收尾钩子）：
 *   [onAgentTurn] 调快速模型验收，返回 [GoalDecision]（Continue=注入差距续跑 /
 *   Stop=照常收尾）；
 * - **UI 侧**（CodeViewModel / 设置页）：[startGoal] / [stopGoal] / [state]
 *   驱动目标设定对话框与进度卡。
 *
 * 设计纪律（AGENTS.md「薄包装不重写」）：协调器不持有引擎引用、不改消息
 * 时间线——续跑注入由引擎按惯例（同 HUMAN_ASSIST/REFLECTION 拦截）执行；
 * 状态互斥用 [Mutex]（验收挂起期间 UI 可能 stopGoal）。
 *
 * 防御式：[onAgentTurn] 内部吞掉一切非取消异常折叠为 Stop（绝不阻断主循环）；
 * 轮次耗尽时目标转 STOPPED（UI 提示「继续」可重新 startGoal 续跑）。
 */
class GoalModeCoordinator(
    private val verifier: GoalVerifier,
    /** 验收开关（设置层实时快照：关闭后信任主模型自评，不调快速模型）。 */
    private val verifyEnabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 引擎钩子回调互斥：验收挂起期间不允许 start/stop 并发改状态。 */
    private val mutex = Mutex()

    private val _state = MutableStateFlow<GoalRuntimeState?>(null)
    val state: StateFlow<GoalRuntimeState?> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value?.status == GoalStatus.ACTIVE

    /** 当前活动目标（无 / 非 ACTIVE 返回 null）。 */
    fun activeSpec(): GoalSpec? = _state.value?.takeIf { it.status == GoalStatus.ACTIVE }?.spec

    /** 设定新目标（覆盖旧目标——同一时刻一个目标，符合「单任务使命」语义）。 */
    fun startGoal(spec: GoalSpec) {
        _state.value = GoalRuntimeState(
            spec = spec,
            status = GoalStatus.ACTIVE,
            roundsDone = 0,
        )
        AppLogger.instance.info(
            LogCategory.ENGINE, TAG,
            "goal started: '${spec.statement.take(80)}' rounds=${spec.maxRounds}"
        )
    }

    /**
     * 停止目标（用户手动 / 新目标覆盖前 / 会话清空）。
     * @param achieved 显式达成标记（默认按未达成 STOPPED 收尾）。
     */
    fun stopGoal(achieved: Boolean = false) {
        val cur = _state.value ?: return
        if (cur.status != GoalStatus.ACTIVE) return
        _state.value = cur.copy(
            status = if (achieved) GoalStatus.ACHIEVED else GoalStatus.STOPPED,
        )
        AppLogger.instance.info(LogCategory.ENGINE, TAG, "goal stopped (achieved=$achieved)")
    }

    /**
     * 引擎每轮纯文本收尾钩子（GOAL 模式且目标 ACTIVE 时被调用）。
     *
     * 返回 null = 无活动目标 / 验收关闭（引擎默认收尾）；非 null = 引擎按
     * [GoalDecision] 执行（Continue → System 注入 + continue；Stop → 收尾）。
     */
    suspend fun onAgentTurn(workReport: String): GoalDecision? = mutex.withLock {
        val snapshot = _state.value ?: return@withLock null
        if (snapshot.status != GoalStatus.ACTIVE) return@withLock null
        val spec = snapshot.spec
        val roundsLeft = spec.maxRounds - snapshot.roundsDone
        if (roundsLeft <= 0) {
            _state.value = snapshot.copy(status = GoalStatus.STOPPED)
            return@withLock GoalDecision.Stop(
                userNotice = "\n[goal] 已达最大验收轮次（${spec.maxRounds}），目标停止——可点「继续」重启目标续跑。",
                achieved = false,
            )
        }

        val result = try {
            if (verifyEnabled()) {
                verifier.verify(spec, workReport)
            } else {
                // 验收关闭：主模型自评「完成报告」关键词放行（弱判定，兜底用）。
                val selfClaimed = workReport.contains("完成报告") || workReport.contains("verified", true)
                GoalCheckResult(selfClaimed, "验收器关闭，按主模型自评处理")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 防御式：验收器异常绝不拖垮主循环——unknown 放行。
            GoalCheckResult.unknown("${e::class.simpleName}: ${e.message?.take(120)}")
        }

        val roundsDone = snapshot.roundsDone + 1
        val notice = GoalPrompts.userNotice(roundsDone, spec.maxRounds, result)
        if (result.achieved) {
            _state.value = snapshot.copy(
                status = GoalStatus.ACHIEVED,
                roundsDone = roundsDone,
                lastCheck = result,
                lastCheckAt = clock(),
            )
            GoalDecision.Stop(userNotice = notice + "\n[goal] 目标达成 🎉", achieved = true)
        } else if (roundsDone >= spec.maxRounds) {
            _state.value = snapshot.copy(
                status = GoalStatus.STOPPED,
                roundsDone = roundsDone,
                lastCheck = result,
                lastCheckAt = clock(),
            )
            GoalDecision.Stop(
                userNotice = notice + "\n[goal] 轮次耗尽，目标停止——可调整条件后重启。",
                achieved = false,
            )
        } else {
            _state.value = snapshot.copy(
                roundsDone = roundsDone,
                lastCheck = result,
                lastCheckAt = clock(),
            )
            GoalDecision.Continue(
                continuationNote = GoalPrompts.continuationNote(result, spec.maxRounds - roundsDone),
                userNotice = notice,
            )
        }
    }

    private companion object {
        const val TAG = "GoalModeCoordinator"
    }
}
