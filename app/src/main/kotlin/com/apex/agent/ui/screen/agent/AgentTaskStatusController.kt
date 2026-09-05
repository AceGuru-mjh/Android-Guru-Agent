package com.apex.agent.ui.screen.agent

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.engine.task.AgentTask
import com.apex.agent.core.engine.task.TaskRuntime
import com.apex.agent.core.engine.task.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * T76 — VM 侧任务状态控制器（N-11）。
 *
 * **为什么独立文件**：AgentChatViewModel 已 1123/1200 行（quality-gate
 * 门禁逼近），T76 的任务控制逻辑全部收敛于此（VM 行数只减不增原则）。
 * VM 仅注入本类 + 3 处一行改动（execute ×2 / abort ×1）。
 *
 * 职责：
 * - 暴露 [taskState]（UI TaskStatusCard 数据源：状态/步骤进度/重试次数）；
 * - execute 转发（执行走 TaskRuntime 包装流获得 checkpoint 能力）；
 * - pause/resume/cancel/retry 用户操作入口（suspend 直转发，VM 在
 *   viewModelScope 里调用并按需 collect 返回的执行流）；
 * - [discoverRecoverable] 崩溃恢复发现（D-3：确定性文件扫描，非后台任务）。
 */
@Singleton
class AgentTaskStatusController @Inject constructor(
    private val taskRuntime: TaskRuntime
) {

    /** 当前活跃任务（null = 无任务/纯聊天模式）。UI 任务状态卡数据源。 */
    val taskState: StateFlow<AgentTask?> get() = taskRuntime.activeTask

    /**
     * 崩溃恢复发现（VM init 调一次；IO 阻塞扫描，调用方负责 IO 调度）。
     * 返回可恢复任务列表（含 PAUSED——用户可选择继续）。
     */
    fun discoverRecoverable(): List<AgentTask> = taskRuntime.discoverRecoverableTasks()

    /**
     * 执行入口（VM 的 sendMessage / 命令 execute 统一走此）。
     *
     * 返回 TaskRuntime 包装流：镜像消费 + checkpoint 落盘 + Channel 重放
     * （与 UI collect 生命周期解耦——abort/pause 收尾事件即使 VM 已停止
     * 消费，落盘也完整执行）。
     */
    fun execute(input: UserInput): Flow<AgentEvent> = taskRuntime.execute(input)

    // ═══ 任务控制操作（TaskStatusCard 按钮；suspend 转发）═══

    /** 暂停（可续）。false = 当前无可暂停执行。 */
    suspend fun pause(): Boolean = taskRuntime.pause()

    /** 恢复暂停任务。返回执行流（null = 不可恢复/并发竞争失败）。 */
    fun resume(): Flow<AgentEvent>? = taskRuntime.resume()

    /** 取消（终态，重启不自动继续）。false = 当前无可取消任务。 */
    suspend fun cancel(): Boolean = taskRuntime.cancel()

    /** 重试失败任务（retryCount 上限校验在 TaskRuntime 内）。null = 拒绝。 */
    fun retry(): Flow<AgentEvent>? = taskRuntime.retry()

    /** 从崩溃任务继续（RecoveryBanner "继续" 按钮）。null = 不可恢复。 */
    fun resumeFromCrash(taskId: String): Flow<AgentEvent>? = taskRuntime.resumeFromCrash(taskId)

    /**
     * 放弃崩溃任务（RecoveryBanner "取消" 按钮）。
     * 发现扫描已把任务置为 _activeTask，直接走终态链。
     */
    suspend fun abandonCrashed(): Boolean = taskRuntime.cancel()

    /** 任务历史（UI 历史列表数据源）。 */
    fun loadTaskHistory(): List<AgentTask> = taskRuntime.loadTaskHistory()

    companion object {
        /** 状态是否处于可暂停的执行态（UI 按钮可用性判断）。 */
        fun isPausable(task: AgentTask?): Boolean = task?.status.let {
            it == TaskStatus.RUNNING || it == TaskStatus.WAITING_USER || it == TaskStatus.PLANNING
        }
    }
}
