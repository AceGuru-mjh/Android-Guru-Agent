package com.apex.agent.core.engine.task

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * T76 — 持久层任务状态（粗粒度、可恢复）。
 *
 * **两层状态模型**（审计报告 R-8）：
 * - 本枚举是**持久层聚合态**：Checkpoint 落盘、跨进程恢复、UI 任务卡展示。
 *   粗粒度、全量可序列化，PAUSED/CANCELLED 等语义只在这一层存在。
 * - A68 `orchestrator.TaskState`（Planning/Acting/Observing…）是**运行时
 *   瞬时态**：单次进程内细化，不落盘。两层各自独立校验迁移，映射关系见
 *   `docs/task-execution-architecture.md`。
 *
 * 迁移合法性由 [TaskStatusMachine] 的显式迁移表约束，禁止任意跳转。
 */
enum class TaskStatus {
    /** 已创建，尚未开始执行（短暂瞬态，execute 启动后立即离开）。 */
    PENDING,

    /** Plan/Spec 模式正在生成计划/规格（LLM 规划阶段）。 */
    PLANNING,

    /** 正在执行（ReAct 循环 / 步骤执行中）。 */
    RUNNING,

    /** 等待用户输入/计划确认/规格确认（ask_user / PlanAwaitingConfirmation）。 */
    WAITING_USER,

    /** 用户主动暂停：落盘保留，可通过 resume 从断点继续（与 CANCELLED 的核心区别）。 */
    PAUSED,

    /** 用户请求取消，正在协作式停止（abort 已发出，等待执行流收尾）。 */
    CANCELLING,

    /** 崩溃恢复中：启动后发现未完成任务，正在重建上下文。 */
    RECOVERING,

    /** 失败后用户请求重试（重试计数校验通过的过渡态）。 */
    RETRYING,

    /** 终态：全部完成。不可迁移（除测试 reset）。 */
    COMPLETED,

    /** 终态：失败（含重试耗尽）。可迁移到 RETRYING（用户重试）或 CANCELLED（放弃）。 */
    FAILED,

    /** 终态：用户取消。不可迁移。重启后不自动继续。 */
    CANCELLED
}

/** 持久层 Step 执行状态（Plan 模式的计划步骤 / Spec 模式的交付项）。 */
enum class StepStatus {
    /** 尚未开始（含未执行到的步骤）。 */
    PENDING,

    /** 正在执行该步骤。 */
    RUNNING,

    /** 该步骤完成。 */
    DONE,

    /** 该步骤执行失败。 */
    FAILED,

    /** 该步骤被跳过（依赖失败或用户取消）。 */
    SKIPPED
}

/**
 * 持久层工具操作状态（幂等性恢复判断依据，任务书 §24）。
 *
 * 崩溃恢复时 [UNKNOWN] 是关键：进程死亡时操作可能已实际执行但结果未落盘，
 * 恢复策略按 [ToolExecutionPolicy] 的幂等性分类决定 retry / verify / skip。
 */
enum class OperationStatus {
    /** 已记录但尚未开始执行。 */
    NOT_STARTED,

    /** 执行中（进程死亡于此 → 恢复时即为悬空/UNKNOWN 语义）。 */
    RUNNING,

    /** 执行成功且结果已落盘。 */
    SUCCEEDED,

    /** 执行失败。 */
    FAILED,

    /** 结果未知（崩溃时 RUNNING 态的操作在恢复扫描中被降级为 UNKNOWN）。 */
    UNKNOWN
}

/**
 * T76 — 持久化任务模型（N-1）。
 *
 * `AgentTask` 是 Checkpoint 的顶层载体：一个任务 = 一个 JSON 文件（TaskStore）。
 * 对话本体仍由 `ConversationMemory` 承载（Session ≠ Task，任务书 §13），
 * [conversationRef]/[historyAnchor] 以引用语义关联（当前实现为全局单条历史，
 * 未来引入多 Session 时不需改本 schema）。
 */
@Serializable
data class AgentTask(
    /** 稳定任务 ID（`task-<epochMillis>-<random4>`），跨重启不变。 */
    val taskId: String,

    /** 用户可见标题（取 userInput 首行，截断 80 字符）。 */
    val title: String,

    /** 原始用户输入（恢复/重试时重新驱动引擎用）。 */
    val userInput: String,

    /** 执行模式名（AgentMode.name 快照）。 */
    val mode: String,

    /** 任务创建时刻的 AgentConfig 快照（任务书 §20：任务级配置快照）。 */
    val configSnapshot: TaskConfigSnapshot = TaskConfigSnapshot(),

    /** 当前持久层状态。 */
    val status: TaskStatus = TaskStatus.PENDING,

    /** 计划步骤（Plan 确认后写入；BUILD 模式为空——无步骤粒度）。 */
    val steps: List<TaskStepModel> = emptyList(),

    /**
     * 工具操作 journal（生命周期边界追加，见 [CheckpointBoundary]）。
     * 只保留最近 [TaskStoreLimits.MAX_JOURNAL_SIZE] 条 + 计数器，防文件膨胀。
     */
    val operations: List<ToolOperationRecord> = emptyList(),

    /** 重试次数（用户显式 retry，非 A68 工具级重试——那是运行时的）。 */
    val retryCount: Int = 0,

    /** 压缩次数（checkpoint 兼容性校验用）。 */
    val compressionCount: Int = 0,

    /** 压缩后历史锚点：压缩边界落盘时持久化消息条数（contextRef）。 */
    val historyAnchor: Int = 0,

    /** 失败/错误摘要（FAILED 态非空）。 */
    val error: String? = null,

    /** 会话引用（当前恒为 "global"——全局单条历史；预留多 Session 扩展）。 */
    val conversationRef: String = "global",

    // ═══ 时间戳（epoch millis）═══
    val createdAt: Long = 0L,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val completedAt: Long = 0L,

    /** 成功完成时的产出摘要（可选）。 */
    val completionSummary: String? = null
) {
    /** 是否处于"活跃"（可发现恢复 / 显示状态卡）状态。 */
    val isActive: Boolean
        get() = status in ACTIVE_STATUSES

    /** 是否终态（迁移表校验参考；FAILED 例外允许 RETRYING）。 */
    val isTerminal: Boolean
        get() = status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED

    /** 当前执行到的步骤索引（无步骤返回 -1）。 */
    val currentStepIndex: Int
        get() {
            val runningIdx = steps.indexOfFirst { it.status == StepStatus.RUNNING }
            if (runningIdx >= 0) return runningIdx
            val lastDone = steps.indexOfLast { it.status == StepStatus.DONE }
            return if (steps.isNotEmpty() && lastDone >= 0) lastDone + 1 else -1
        }

    /** 已完成步骤数 / 总步骤数（UI 进度 "Step x/y"）。 */
    val completedSteps: Int get() = steps.count { it.status == StepStatus.DONE }

    companion object {
        /** 恢复发现扫描的状态集合：进程死亡时任务可能停留在这几态。 */
        val ACTIVE_STATUSES: Set<TaskStatus> = setOf(
            TaskStatus.PENDING, TaskStatus.PLANNING, TaskStatus.RUNNING,
            TaskStatus.WAITING_USER, TaskStatus.PAUSED, TaskStatus.RECOVERING
        )
    }
}

/**
 * 任务级配置快照（任务书 §20）。
 *
 * 只快照影响执行语义的字段；非执行字段（如 enabledToolIds 白名单）亦纳入，
 * 保证恢复/重试时执行环境与创建时刻一致。完整 AgentConfig 中 UI 展示类
 * 字段（displayName 等）不入快照。
 */
@Serializable
data class TaskConfigSnapshot(
    val mode: String = "BUILD",
    val thinkingLevel: String = "STANDARD",
    val maxIterations: Int = 25,
    val maxContextTokens: Int = 128000,
    val compressionThreshold: Float = 0.8f,
    val preserveRecentTurns: Int = 5,
    val maxToolOutputLength: Int = 2000,
    val temperature: Float = 0.7f,
    val reflectionRounds: Int = 1,
    val enabledToolIds: List<String> = emptyList()
)

/** 持久层计划步骤（基于引擎 `PlanStep` 增加 status / stepId / 次数）。 */
@Serializable
data class TaskStepModel(
    /** 稳定步骤 ID（`<taskId>-s<index>`）。 */
    val stepId: String,
    /** 步骤序号（0-based，与 PlanStep.index 对齐）。 */
    val index: Int,
    /** 步骤描述（Plan 步骤描述 / Spec 交付项文本）。 */
    val description: String,
    /** 计划中预估的工具名（可空——BUILD 式步骤无预绑定工具）。 */
    val toolName: String? = null,
    /** 依赖的步骤索引（PlanStep.dependsOn）。 */
    val dependsOn: List<Int> = emptyList(),
    val status: StepStatus = StepStatus.PENDING,
    /** 该步骤的尝试次数（step 级预算）。 */
    val attempts: Int = 0,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    /** 步骤失败原因（FAILED 时）。 */
    val error: String? = null
)

/**
 * 持久层工具操作 journal 条目。
 *
 * **operationId 本地生成**（审计 §4.2 缺口 1）：`<taskId>-op<seq>`，
 * 与 LLM 返回的 callId 建立映射（[llmCallId]），恢复时用 operationId 判断
 * 幂等性、用 llmCallId 修补悬空历史。
 */
@Serializable
data class ToolOperationRecord(
    val operationId: String,
    /** LLM 返回的 tool call id（悬空修补的关键映射）。 */
    val llmCallId: String,
    val toolName: String,
    /** 参数摘要（JSON 原文截断至 [TaskStoreLimits.MAX_ARGS_SNAPSHOT]，不落敏感全文）。 */
    val arguments: String,
    val status: OperationStatus = OperationStatus.NOT_STARTED,
    /** 幂等性分类快照（落盘时从 ToolExecutionPolicy 查得，恢复时无需重查）。 */
    val idempotency: ToolIdempotencyClass = ToolIdempotencyClass.UNKNOWN,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    /** 输出摘要（截断；完整输出仍在 ConversationMemory 的 ToolResult 消息里）。 */
    val outputDigest: String? = null,
    val durationMs: Long = 0L
)

/**
 * 工具幂等性分类（N-7，恢复策略的决策依据）。
 *
 * 分类语义（恢复时对 UNKNOWN/中断操作）：
 * - [READ_ONLY]：重跑无副作用 → 恢复时可直接 RETRY；
 * - [IDEMPOTENT_WRITE]：重跑结果一致（整覆盖写、安装）→ 恢复时可 RETRY；
 * - [NON_IDEMPOTENT]：重跑可能造成重复副作用（卸载、点击、发送）→ 恢复时
 *   VERIFY（喂给 LLM "可能已执行，先验证"）或 SKIP；
 * - [UNKNOWN]：行为不可判定（任意 shell、插件）→ 默认 ASK_USER / VERIFY。
 */
enum class ToolIdempotencyClass {
    READ_ONLY,
    IDEMPOTENT_WRITE,
    NON_IDEMPOTENT,
    UNKNOWN
}

/** 恢复动作（RecoveryPolicy 对中断操作给出的决策）。 */
enum class RecoveryAction {
    /** 直接重跑（READ_ONLY / IDEMPOTENT_WRITE 且未成功）。 */
    RETRY,
    /** 不重跑，向 LLM 注入"可能已执行，先验证结果"提示。 */
    VERIFY,
    /** 跳过（标记 SKIPPED，向 LLM 声明已跳过）。 */
    SKIP,
    /** 升级为用户决策（危险/未知操作，UI 弹确认）。 */
    ASK_USER,
    /** 标记任务失败（不可恢复类错误）。 */
    FAIL
}

/** 任务存储层的物理限制（防文件膨胀 / 防御性截断）。 */
object TaskStoreLimits {
    /** journal 保留条数上限（超出滚动淘汰最旧条目）。 */
    const val MAX_JOURNAL_SIZE = 200

    /** 参数快照截断长度。 */
    const val MAX_ARGS_SNAPSHOT = 512

    /** 输出摘要截断长度。 */
    const val MAX_OUTPUT_DIGEST = 256

    /** 标题截断长度。 */
    const val MAX_TITLE_LENGTH = 80

    /** 错误摘要截断长度。 */
    const val MAX_ERROR_LENGTH = 400
}

/**
 * Checkpoint 保存边界（N-4）——**生命周期边界持久化，绝不按 token**。
 *
 * 每个常量对应 [TaskRuntime] 观察引擎事件流的一类落盘点。写入频率 =
 * 这些边界的发生频率（远低于流式 chunk 频率），保证 TaskStore 的
 * O(文件) 原子写在可接受范围。
 */
enum class CheckpointBoundary(val description: String) {
    TASK_CREATED("任务创建（execute 入口）"),
    PLAN_CONFIRMED("Plan/Spec 确认，步骤持久化"),
    STEP_STARTED("步骤开始执行"),
    TOOL_CALL_STARTED("工具调用开始（journal 追加 RUNNING）"),
    TOOL_CALL_FINISHED("工具调用完成（journal 更新 SUCCEEDED/FAILED）"),
    STEP_FINISHED("步骤完成/失败/跳过"),
    CONTEXT_COMPRESSED("上下文压缩边界（historyAnchor 更新 + 状态重注入）"),
    WAITING_USER("等待用户输入/确认（WAITING_USER 落盘）"),
    ERROR("错误边界（FAILED 落盘）"),
    PAUSED("暂停落盘（PAUSED）"),
    CANCELLED("取消落盘（CANCELLED）"),
    RECOVERED("崩溃恢复完成（恢复上下文重建）"),
    COMPLETED("任务完成（终态落盘）"),
    RETRY_STARTED("用户重试（RETRYING → RUNNING）")
}

/** TaskStore schema 版本（宽容解析 + 未来迁移依据）。 */
object TaskStoreSchema {
    const val VERSION = 1
}

/** 落盘 envelope：版本号 + payload。 */
@Serializable
data class TaskStoreEnvelope(
    val version: Int = TaskStoreSchema.VERSION,
    @SerialName("task") val task: AgentTask
)
