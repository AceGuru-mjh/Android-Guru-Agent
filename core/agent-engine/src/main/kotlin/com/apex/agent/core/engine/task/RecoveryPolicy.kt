package com.apex.agent.core.engine.task

/**
 * T76 — 崩溃恢复策略（N-5，纯函数决策器）。
 *
 * 输入：崩溃时中断的工具操作（journal 中 [OperationStatus.RUNNING] 态，
 * 恢复扫描时已降级为 UNKNOWN 语义）+ 其幂等性分类。
 * 输出：[RecoveryAction] 决策（retry / verify / skip / ask-user / fail）。
 *
 * 决策矩阵（任务书 §24）：
 * ```
 * 操作状态        幂等性            → 动作
 * SUCCEEDED      任意              → 无动作（已成功，绝不重复执行）
 * FAILED         任意              → 无动作（交给 LLM 重规划——错误已在历史）
 * NOT_STARTED    任意              → 无动作（从未执行）
 * UNKNOWN(中断)  READ_ONLY         → RETRY   （重跑无副作用，且多为获取上下文）
 * UNKNOWN(中断)  IDEMPOTENT_WRITE  → RETRY   （重放等价）
 * UNKNOWN(中断)  NON_IDEMPOTENT    → VERIFY  （可能已执行——先验证再决定）
 * UNKNOWN(中断)  UNKNOWN           → VERIFY  （不可判定——保守验证）
 * 用户交互中断    ask_user 族       → RETRY   （重新提问）
 * ```
 *
 * **VERIFY 语义**：不重跑操作，而是向 LLM 注入一条提示
 * （"操作 X 可能已在中断前执行，先验证其效果再决定是否重做"），
 * 让模型用 READ_ONLY 工具核实——这与 DanglingToolCallRepair 合成的
 * UNKNOWN ToolResult 文本呼应，形成一致的恢复叙事。
 *
 * ASK_USER 当前仅在任务级（多个 NON_IDEMPOTENT 中断 + 高风险）升级时使用；
 * v1 交互面收敛为横幅"继续恢复 / 取消任务"，不做逐操作弹窗（避免恢复
 * 流程被 N 个弹窗淹没）——逐操作决策交给 LLM + VERIFY 提示。
 */
object RecoveryPolicy {

    /** 单个中断操作的恢复决策。 */
    fun decideForOperation(op: ToolOperationRecord, isUserInteraction: Boolean): RecoveryAction {
        return when (op.status) {
            // 已成功落盘的操作绝不重复执行（任务书 §24 幂等性验收核心）；
            // FAILED 的错误已在对话历史（LLM 自行重规划）；NOT_STARTED 从未执行。
            // 三者统一 SKIP：无恢复动作，不重放。
            OperationStatus.SUCCEEDED,
            OperationStatus.FAILED,
            OperationStatus.NOT_STARTED -> RecoveryAction.SKIP

            OperationStatus.RUNNING,
            OperationStatus.UNKNOWN -> when {
                isUserInteraction -> RecoveryAction.RETRY
                op.idempotency == ToolIdempotencyClass.READ_ONLY -> RecoveryAction.RETRY
                op.idempotency == ToolIdempotencyClass.IDEMPOTENT_WRITE -> RecoveryAction.RETRY
                op.idempotency == ToolIdempotencyClass.NON_IDEMPOTENT -> RecoveryAction.VERIFY
                else -> RecoveryAction.VERIFY // UNKNOWN 分类 → 保守验证
            }
        }
    }

    /**
     * 整任务的恢复计划（横幅呈现 + 恢复上下文注入依据）。
     *
     * @param crashedTask 崩溃现场任务（RUNNING 态操作将被视为 UNKNOWN）
     * @param classify 工具幂等分类器（ToolExecutionPolicy）
     */
    fun planForTask(
        crashedTask: AgentTask,
        classify: (String) -> ToolIdempotencyClass,
        isUserInteraction: (String) -> Boolean
    ): RecoveryPlan {
        val interrupted = crashedTask.operations.map { op ->
            // 崩溃现场：RUNNING 态在恢复扫描时即"结果未知"语义
            if (op.status == OperationStatus.RUNNING) op.copy(status = OperationStatus.UNKNOWN) else op
        }
        val decisions = interrupted
            .filter { it.status == OperationStatus.UNKNOWN }
            .map { op -> op to decideForOperation(op, isUserInteraction(op.toolName)) }

        val retryOps = decisions.filter { it.second == RecoveryAction.RETRY }.map { it.first }
        val verifyOps = decisions.filter { it.second == RecoveryAction.VERIFY }.map { it.first }

        return RecoveryPlan(
            taskId = crashedTask.taskId,
            unknownOperations = interrupted.filter { it.status == OperationStatus.UNKNOWN },
            retryOperations = retryOps,
            verifyOperations = verifyOps,
            // 多个 NON_IDEMPOTENT/UNKNOWN 同时中断 → 高风险信号（UI 横幅可据此提示谨慎继续）
            highRisk = verifyOps.size >= HIGH_RISK_VERIFY_COUNT
        )
    }

    /**
     * 构造注入给 LLM 的恢复上下文提示（恢复执行前的 system 级说明）。
     *
     * 内容包含：中断点（step / 最近操作）、每个 UNKNOWN 操作的"先验证"
     * 提示、绝不重复已成功操作的声明。文本给 LLM 读，不是给用户读。
     */
    fun buildRecoveryPrompt(plan: RecoveryPlan, task: AgentTask): String = buildString {
        appendLine("[SYSTEM RECOVERY CONTEXT — 上次执行因进程中断而停止，现在恢复]")
        appendLine("任务目标：${task.userInput.take(200)}")
        if (task.steps.isNotEmpty()) {
            appendLine("计划进度：${task.completedSteps}/${task.steps.size} 步已完成")
            task.currentStepIndex.takeIf { it >= 0 }?.let { idx ->
                task.steps.getOrNull(idx)?.let { s ->
                    appendLine("中断于步骤 ${s.index + 1}：${s.description.take(120)}")
                }
            }
        }
        plan.unknownOperations.takeIf { it.isNotEmpty() }?.let { ops ->
            appendLine("以下操作在中断时刻执行中、结果未知（历史中的 ToolResult 已标注 UNKNOWN）：")
            ops.take(5).forEach { op ->
                appendLine("- ${op.toolName} ${op.arguments.take(80)}")
            }
            appendLine("对标注 UNKNOWN 的操作：先用只读工具验证其实际效果，再决定是否重做。")
        }
        plan.verifyOperations.takeIf { it.isNotEmpty() }?.let { ops ->
            appendLine("以下操作不可幂等重放（可能已造成副作用），禁止盲目重试：")
            ops.take(5).forEach { op -> appendLine("- ${op.toolName}") }
        }
        append("已确认成功的操作不要重复执行；从中断点继续完成任务。")
    }

    /** 任务级恢复计划。 */
    data class RecoveryPlan(
        val taskId: String,
        /** 中断（UNKNOWN 态）操作全量。 */
        val unknownOperations: List<ToolOperationRecord>,
        /** 可安全重跑的（READ_ONLY / IDEMPOTENT_WRITE / ask_user）。 */
        val retryOperations: List<ToolOperationRecord>,
        /** 需先验证的（NON_IDEMPOTENT / UNKNOWN 分类）。 */
        val verifyOperations: List<ToolOperationRecord>,
        /** 高风险信号：多个不可重放操作同时中断。 */
        val highRisk: Boolean
    )

    private const val HIGH_RISK_VERIFY_COUNT = 2
}
