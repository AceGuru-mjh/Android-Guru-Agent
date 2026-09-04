package com.apex.agent.core.engine.task

/**
 * T76 — 持久层状态机（N-3）：显式迁移表 + 非法迁移拒绝。
 *
 * 任务书 §2 要求"明确合法迁移，禁止任意跳转"。A68 `TaskStateMachine`
 * 的 `transitionTo` 不校验合法性（审计 §6.1）；本类是**持久层**的迁移守卫，
 * 与 A68 运行时状态机相互独立（两层状态，R-8）。
 *
 * 迁移表（v1）：
 * ```
 * PENDING     → PLANNING | RUNNING | FAILED | CANCELLED
 * PLANNING    → RUNNING | WAITING_USER | PAUSED | FAILED | CANCELLED
 * RUNNING     → PLANNING | WAITING_USER | PAUSED | CANCELLING |
 *               RECOVERING | COMPLETED | FAILED
 * WAITING_USER→ RUNNING | PAUSED | CANCELLING | RECOVERING | FAILED
 * PAUSED      → RUNNING | CANCELLING | RECOVERING | FAILED
 * CANCELLING  → CANCELLED | FAILED
 * RECOVERING  → RUNNING | CANCELLING | FAILED
 * RETRYING    → RUNNING | CANCELLING | FAILED
 * FAILED      → RETRYING | CANCELLED
 * COMPLETED   → （终态，无出边）
 * CANCELLED   → （终态，无出边）
 * ```
 *
 * 设计说明：
 * - **PAUSED ≠ CANCELLED**：PAUSED 有到 RUNNING 的出边（resume），
 *   CANCELLED 是绝对终态（重启后不自动继续，任务书 §15）；
 * - **CANCELLING 是瞬态**：abort 是协作式的（引擎边界退出），从请求取消到
 *   执行流真正收尾之间任务处于 CANCELLING；只有落盘观察到流结束才进 CANCELLED；
 * - **FAILED → RETRYING**：重试不是"复活"，是带 retryCount 校验的受控迁移；
 * - **RUNNING → PLANNING**：允许 LLM 重规划（Recover 场景 / Plan 模式下一任务）。
 *
 * 纯 Kotlin、无 IO、线程安全（表为不可变对象）。
 */
object TaskStatusMachine {

    /** 非法迁移时抛出。携带 from/to 便于测试与诊断。 */
    class IllegalTaskTransitionException(
        val from: TaskStatus,
        val to: TaskStatus
    ) : IllegalStateException(
        "Illegal task status transition: $from -> $to (see TaskStatusMachine table)"
    )

    /** 显式合法迁移表（终态 COMPLETED/CANCELLED 无出边）。 */
    private val TRANSITIONS: Map<TaskStatus, Set<TaskStatus>> = mapOf(
        TaskStatus.PENDING to setOf(
            TaskStatus.PLANNING, TaskStatus.RUNNING,
            TaskStatus.FAILED, TaskStatus.CANCELLED
        ),
        TaskStatus.PLANNING to setOf(
            TaskStatus.RUNNING, TaskStatus.WAITING_USER, TaskStatus.PAUSED,
            TaskStatus.FAILED, TaskStatus.CANCELLED
        ),
        TaskStatus.RUNNING to setOf(
            TaskStatus.PLANNING, TaskStatus.WAITING_USER, TaskStatus.PAUSED,
            TaskStatus.CANCELLING, TaskStatus.RECOVERING,
            TaskStatus.COMPLETED, TaskStatus.FAILED
        ),
        TaskStatus.WAITING_USER to setOf(
            TaskStatus.RUNNING, TaskStatus.PAUSED, TaskStatus.CANCELLING,
            TaskStatus.RECOVERING, TaskStatus.FAILED
        ),
        TaskStatus.PAUSED to setOf(
            TaskStatus.RUNNING, TaskStatus.CANCELLING,
            TaskStatus.RECOVERING, TaskStatus.FAILED
        ),
        TaskStatus.CANCELLING to setOf(
            TaskStatus.CANCELLED, TaskStatus.FAILED
        ),
        TaskStatus.RECOVERING to setOf(
            TaskStatus.RUNNING, TaskStatus.CANCELLING, TaskStatus.FAILED
        ),
        TaskStatus.RETRYING to setOf(
            TaskStatus.RUNNING, TaskStatus.CANCELLING, TaskStatus.FAILED
        ),
        TaskStatus.FAILED to setOf(
            TaskStatus.RETRYING, TaskStatus.CANCELLED
        ),
        TaskStatus.COMPLETED to emptySet(),
        TaskStatus.CANCELLED to emptySet()
    )

    /** 校验迁移合法性，非法即抛 [IllegalTaskTransitionException]。 */
    fun requireLegal(from: TaskStatus, to: TaskStatus) {
        if (!isLegal(from, to)) throw IllegalTaskTransitionException(from, to)
    }

    /** 迁移是否合法（不抛异常）。 */
    fun isLegal(from: TaskStatus, to: TaskStatus): Boolean =
        to in (TRANSITIONS[from] ?: emptySet())

    /**
     * 应用迁移（校验 + 返回新任务对象）。
     *
     * 顺带维护时间戳：startedAt 首次进入 RUNNING/PLANNING 时固定；
     * updatedAt 每次迁移刷新；completedAt 进入 COMPLETED/FAILED/CANCELLED
     * 终态时固定。幂等重复迁移（from == to）视为非法——状态机不允许自环。
     */
    fun transition(task: AgentTask, to: TaskStatus, nowMs: Long): AgentTask {
        requireLegal(task.status, to)
        return task.copy(
            status = to,
            startedAt = when {
                task.startedAt > 0L -> task.startedAt
                to == TaskStatus.RUNNING || to == TaskStatus.PLANNING -> nowMs
                else -> task.startedAt
            },
            updatedAt = nowMs,
            completedAt = when (to) {
                TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED -> nowMs
                else -> task.completedAt
            }
        )
    }

    /** 全量迁移表（测试/文档用只读视图）。 */
    val transitions: Map<TaskStatus, Set<TaskStatus>>
        get() = TRANSITIONS

    /**
     * 崩溃恢复扫描的降级规则：进程死亡时停留的活跃态 → 恢复期初态。
     *
     * - RUNNING/WAITING_USER/RECOVERING/PLANNING → RECOVERING
     *   （伴随其中 RUNNING 态的 tool 操作降级 UNKNOWN，由 RecoveryPolicy 处理）
     * - PAUSED → 保持 PAUSED（用户已明确暂停，恢复发现后等待用户 resume，
     *    不自动续跑——语义与"重启不自动继续暂停任务"一致，交由用户决定）
     * - PENDING → RECOVERING（未启动即崩溃，视同恢复）
     */
    fun crashRecoveryEntry(status: TaskStatus): TaskStatus? = when (status) {
        TaskStatus.PENDING,
        TaskStatus.PLANNING,
        TaskStatus.RUNNING,
        TaskStatus.WAITING_USER,
        TaskStatus.RECOVERING -> TaskStatus.RECOVERING
        TaskStatus.PAUSED -> TaskStatus.PAUSED
        else -> null
    }
}
