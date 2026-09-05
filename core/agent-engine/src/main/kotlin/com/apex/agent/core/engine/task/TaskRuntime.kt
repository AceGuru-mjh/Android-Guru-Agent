package com.apex.agent.core.engine.task

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.ConversationMemory
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T76 — TaskRuntime：Agent 长任务执行体系总装（D-2 方案 B）。
 *
 * **叠加层定位**（不重写引擎、不替换 AgentEngine 绑定）：
 * - 组合 `AgentEngine`（生产为 ApexAgentEngine 单例）；
 * - `execute()` 包装引擎事件流，在**生命周期边界**（CheckpointBoundary 表）
 *   落盘 checkpoint —— 绝不按 token；
 * - Pause/Resume/Cancel/Retry 提供持久语义；
 * - 启动时发现崩溃遗留的活跃任务，配合 [RecoveryPolicy] +
 *   [DanglingToolCallRepair] 重建上下文后继续执行；
 * - 单活跃执行互斥（N-8）：同一时刻至多一个执行在跑，并发 resume/retry
 *   只有一个成功。
 *
 * **引擎挂钩注入**（N-9/N-12，函数式——core 层零引擎依赖，装配在 app DI）：
 * - [configProvider]：任务创建时快照当前 AgentConfig（配置快照语义 §20）；
 * - [contextInjector]：压缩后向引擎 history 重注入受保护的任务状态消息；
 * - [tagsSetter]：把 taskId/stepId 填入引擎 LlmRequestContext（诊断贯通）。
 * 生产装配：`TaskRuntime(engine, store, memory, cfg = apexEngine::currentConfigSnapshot, …)`。
 *
 * **事件转发契约**（对 VM 透明）：
 * 内部镜像 collector 在 runtimeScope 消费引擎流（与 UI collect 生命周期
 * 解耦——VM cancel 它的 job 不会中断 checkpoint 记录），事件经
 * Channel（UNLIMITED 缓冲，先落盘后转发）交给 UI 的 receiveAsFlow。
 * 流收尾时 close() 通道使订阅端正常完成。这保证：abort/pause 的收尾
 * 事件（Aborted/Complete）即使 VM 已停止消费，落盘逻辑也完整执行。
 */
class TaskRuntime(
    private val engine: AgentEngine,
    private val store: TaskStore,
    private val memory: ConversationMemory? = null,
    private val toolPolicy: ToolExecutionPolicy = ToolExecutionPolicy(),
    /** 任务创建时的配置快照源（DI: apexEngine.currentConfig() → snapshot）。 */
    private val configProvider: () -> TaskConfigSnapshot = { TaskConfigSnapshot() },
    /** 压缩后任务状态重注入（DI: apexEngine::injectSystemContext）。 */
    private val contextInjector: (String) -> Unit = {},
    /** LlmRequestContext 的 taskId/stepId 填充（DI: apexEngine::setLlmExecutionTags）。 */
    private val tagsSetter: (taskId: String?, stepId: String?) -> Unit = { _, _ -> },
    private val retryLimit: Int = DEFAULT_RETRY_LIMIT,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis
) {

    // ═══ 对外状态 ═══

    private val _activeTask = MutableStateFlow<AgentTask?>(null)
    /** 当前活跃任务（无任务为 null）。UI 任务状态卡数据源。 */
    val activeTask: StateFlow<AgentTask?> = _activeTask.asStateFlow()

    private val _runtimeEvents = MutableSharedFlow<TaskRuntimeEvent>(
        replay = 16, extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** 低频任务生命周期事件（AgentEvent 冻结，任务关联走此通道，§16）。 */
    val runtimeEvents: SharedFlow<TaskRuntimeEvent> = _runtimeEvents.asSharedFlow()

    // ═══ 内部执行状态 ═══

    /**
     * 单活跃执行互斥（N-8）：原子占位标志。
     *
     * 比 Mutex 更适用：resume()/retry()/execute() 的前置检查与真正占位
     * 之间不存在竞态窗口——并发两次 resume 只有一个 CAS 成功，另一个
     * 立即返回 null（任务书 §1"8H：并发 resume 只允许一个成功"）。
     * 跨进程互斥由 TaskStore 文件状态承担（活跃态任务需先进入终态）。
     */
    private val claiming = AtomicBoolean(false)

    private fun tryClaim(): Boolean = claiming.compareAndSet(false, true)

    /**
     * 当前执行的事件通道（execute 期间非 null）。
     *
     * 用 [Channel]（UNLIMITED）而非 SharedFlow：镜像 collector 在流收尾时
     * close()，使订阅端的 receiveAsFlow() 自然完成——SharedFlow 是热流，
     * 订阅者会永久挂起等待下一个事件。
     */
    @Volatile
    private var eventTap: Channel<AgentEvent>? = null

    /** 当前流收尾信号（pause/cancel 等待它完成）。 */
    @Volatile
    private var streamDone: CompletableDeferred<Unit>? = null

    @Volatile
    private var pauseRequested = false
    @Volatile
    private var cancelRequested = false

    /**
     * 模拟进程死亡标志（测试专用，[simulateCrash] 置位）。
     *
     * 真实进程被 SIGKILL 时 finally 收尾逻辑**根本不会执行**——store 停留在
     * 最后一个 checkpoint。scope.cancel() 会触发 finally，因此模拟死亡必须
     * 显式短路 [finalizeStream]（不迁移、不落盘），保持崩溃现场原样。
     */
    @Volatile
    private var crashSimulated = false

    /** journal 序号（operationId 生成）。 */
    private var operationSeq = 0

    /** 当前任务"当前步骤索引"（StepStart 推导，-1 = 无步骤语境）。 */
    @Volatile
    private var currentStepIndex = -1

    // ═══ 执行入口 ═══

    /**
     * 包装引擎 execute：创建/更新任务 → 镜像消费事件流（checkpoint 落盘）
     * → 经 SharedFlow 重放给调用方。
     *
     * 返回的 Flow 与 UI collect 生命周期解耦（见类注释），订阅者晚到/重订阅
     * 均不丢事件（replay=MAX）。任务结束（终态落盘）后 flow 不再发射。
     *
     * @throws IllegalStateException 已有活跃执行（互斥拒绝，N-8）
     */
    fun execute(input: UserInput): Flow<AgentEvent> = executeAsTask(input, resumeOf = null)

    private fun executeAsTask(input: UserInput, resumeOf: AgentTask?): Flow<AgentEvent> {
        // 互斥：并发第二次 execute 直接拒绝（任务书 §18H 并发场景）
        check(tryClaim()) {
            "TaskRuntime rejects concurrent execution: an active execution is already running"
        }

        val now = clock()
        var task = resumeOf ?: newTask(input, now)

        // 状态迁移：新任务 PENDING→(PLANNING/RUNNING)；恢复/重试统一迁入 RUNNING
        // （幂等：已是 RUNNING 的预迁移任务跳过，避免自环非法迁移）
        task = when {
            resumeOf == null -> TaskStatusMachine.transition(
                task,
                if (task.mode == "PLAN" || task.mode == "SPEC") TaskStatus.PLANNING else TaskStatus.RUNNING,
                now
            )
            task.status == TaskStatus.RUNNING -> task
            else -> TaskStatusMachine.transition(task, TaskStatus.RUNNING, now)
        }

        pauseRequested = false
        cancelRequested = false
        currentStepIndex = -1
        operationSeq = task.operations.size
        tagsSetter(task.taskId, null) // N-12：诊断四元 ID 贯通开始
        persist(task, CheckpointBoundary.TASK_CREATED)

        val tap = Channel<AgentEvent>(capacity = Channel.UNLIMITED)
        eventTap = tap
        val done = CompletableDeferred<Unit>()
        streamDone = done
        _activeTask.value = task

        scope.launch {
            try {
                engine.execute(input).collect { event ->
                    onEngineEvent(task.taskId, event)   // checkpoint 边界（先落盘）
                    tap.send(event)                     // 再转发给 UI（UNLIMITED 不阻塞）
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 引擎流被取消（罕见：runtimeScope 关闭）→ 走 finalize 兜底
                AppLogger.instance.warn(LogCategory.ENGINE, TAG, "engine stream cancelled: ${e.message}")
            } catch (e: Throwable) {
                AppLogger.instance.error(LogCategory.ENGINE, TAG, "mirror collector failed: ${e.message}", e)
                // 转发错误给 UI（引擎内部已捕获大多数异常，这里是保险层）
                runCatching { tap.send(AgentEvent.Error(e.message ?: "runtime failure", recoverable = false)) }
                finalizeWith(task.taskId, TaskStatus.FAILED, e.message)
            } finally {
                // 异常安全：finalize 内部任何异常都不得吞掉 done/close
                // （否则 pause/cancel 永久挂起、UI collect 永不完成）。
                runCatching { finalizeStream(task.taskId) }.onFailure { e ->
                    AppLogger.instance.error(
                        LogCategory.ENGINE, TAG,
                        "finalizeStream failed (task left at last checkpoint): ${e.message}", e
                    )
                }
                tap.close() // 订阅端 receiveAsFlow 完成（UI collect 正常返回）
                done.complete(Unit)
            }
        }

        return tap.receiveAsFlow()
    }

    // ═══ 事件 → Checkpoint（核心推导表，N-4）═══

    private suspend fun onEngineEvent(taskId: String, event: AgentEvent) {
        val task = _activeTask.value?.takeIf { it.taskId == taskId } ?: return
        when (event) {
            is AgentEvent.PlanConfirmed,
            is AgentEvent.SpecConfirmed -> {
                // 计划/规格确认：进入执行阶段（PLANNING→RUNNING）+ 步骤持久化。
                // 非 PLANNING 态（重放/恢复场景已 RUNNING）幂等跳过迁移。
                val withSteps = extractSteps(event)
                    ?.let { task.copy(steps = it) } ?: task
                val target = if (withSteps.status == TaskStatus.PLANNING) {
                    TaskStatusMachine.transition(withSteps, TaskStatus.RUNNING, clock())
                } else withSteps
                persist(target, CheckpointBoundary.PLAN_CONFIRMED)
                emitRuntimeSync(TaskRuntimeEvent.StatusChanged(target.taskId, target.status))
            }

            is AgentEvent.StepStart -> {
                currentStepIndex = event.stepIndex
                tagsSetter(task.taskId, task.steps.getOrNull(event.stepIndex)?.stepId)
                // 步骤切换推导前序步骤完成（AgentEvent 体系无 StepComplete——
                // 下一个 StepStart 到达即隐含前序完成；最后步骤由 Complete 兜底）
                val steps = task.steps.mapIndexed { i, s ->
                    when {
                        i == event.stepIndex -> s.copy(
                            status = StepStatus.RUNNING,
                            startedAt = clock(),
                            attempts = s.attempts + 1
                        )
                        s.status == StepStatus.RUNNING && i < event.stepIndex ->
                            s.copy(status = StepStatus.DONE, finishedAt = clock())
                        else -> s
                    }
                }
                persist(task.copy(steps = steps), CheckpointBoundary.STEP_STARTED)
            }

            is AgentEvent.ToolCallStart -> {
                operationSeq++
                val op = ToolOperationRecord(
                    operationId = "${taskId}-op$operationSeq",
                    llmCallId = event.callId,
                    toolName = event.toolName,
                    arguments = event.arguments.take(TaskStoreLimits.MAX_ARGS_SNAPSHOT),
                    status = OperationStatus.RUNNING,
                    idempotency = toolPolicy.classify(event.toolName),
                    startedAt = clock()
                )
                persist(task.appendOperation(op), CheckpointBoundary.TOOL_CALL_STARTED)
            }

            is AgentEvent.ToolCallComplete -> {
                val ops = task.operations.map {
                    if (it.llmCallId == event.callId) it.copy(
                        status = if (event.success) OperationStatus.SUCCEEDED else OperationStatus.FAILED,
                        finishedAt = clock(),
                        durationMs = event.durationMs,
                        outputDigest = event.output.take(TaskStoreLimits.MAX_OUTPUT_DIGEST)
                    ) else it
                }
                persist(task.copy(operations = ops), CheckpointBoundary.TOOL_CALL_FINISHED)
            }

            is AgentEvent.ContextCompressed -> {
                // N-9：压缩边界——记录 historyAnchor + 任务状态重注入到引擎历史
                contextInjector(buildTaskStateMessage(task))
                persist(
                    task.copy(
                        compressionCount = task.compressionCount + 1,
                        historyAnchor = memory?.count() ?: 0
                    ),
                    CheckpointBoundary.CONTEXT_COMPRESSED
                )
                emitRuntime(TaskRuntimeEvent.CheckpointSaved(task.taskId, CheckpointBoundary.CONTEXT_COMPRESSED))
            }

            is AgentEvent.UserInputRequired -> {
                transitionAndPersist(task, TaskStatus.WAITING_USER, CheckpointBoundary.WAITING_USER)
            }

            is AgentEvent.Error -> {
                // 可恢复错误不终态化（LLM 会在下一轮处理），不可恢复的 finalize FAILED
                if (!event.recoverable) {
                    transitionAndPersist(task, TaskStatus.FAILED, CheckpointBoundary.ERROR, event.message)
                } else {
                    persist(task, CheckpointBoundary.ERROR)
                }
            }

            is AgentEvent.Complete -> {
                // 引擎 finally 无条件发 Complete（含失败场景）——终态由 finalizeStream
                // 统一裁决（pause/cancel/failure 标志优先），此处记录摘要 +
                // 最后步骤的完成化兜底（RUNNING 步骤在成功收尾时视为 DONE）。
                val steps = task.steps.map {
                    if (it.status == StepStatus.RUNNING) it.copy(status = StepStatus.DONE, finishedAt = clock())
                    else it
                }
                persistSummary(task.copy(steps = steps), event.summary, event.totalToolCalls)
            }

            is AgentEvent.Aborted -> {
                // 等待 finalizeStream 裁决 PAUSED vs CANCELLING（pause/cancel 标志）
                persist(task, CheckpointBoundary.PAUSED.takeIf { pauseRequested } ?: CheckpointBoundary.CANCELLED)
            }

            else -> Unit // 流式 chunk 等高频事件：无 checkpoint（绝不按 token 落盘）
        }
    }

    /** 引擎流收尾：统一终态裁决（pause > cancel > 失败 > 完成）。 */
    private suspend fun finalizeStream(taskId: String) {
        // 模拟进程死亡：真实 SIGKILL 不执行任何 finally——保持崩溃现场原样
        if (crashSimulated) {
            claiming.set(false)
            return
        }
        val task = _activeTask.value?.takeIf { it.taskId == taskId } ?: run {
            claiming.set(false)
            return
        }
        when {
            pauseRequested -> transitionAndPersist(task, TaskStatus.PAUSED, CheckpointBoundary.PAUSED)
            cancelRequested -> {
                // CANCELLING → CANCELLED 链（流已收尾，无再等待的执行）
                val cancelling = TaskStatusMachine.transition(task, TaskStatus.CANCELLING, clock())
                transitionAndPersist(cancelling, TaskStatus.CANCELLED, CheckpointBoundary.CANCELLED)
            }
            task.status == TaskStatus.FAILED -> {
                // 失败已在 Error 事件中终态化；此处理论上不应到达——幂等保护
                AppLogger.instance.warn(LogCategory.ENGINE, TAG, "finalize on already-FAILED task")
            }
            else -> transitionAndPersist(task, TaskStatus.COMPLETED, CheckpointBoundary.COMPLETED)
        }
        emitRuntime(TaskRuntimeEvent.Finished(taskId, _activeTask.value?.status ?: TaskStatus.COMPLETED))
        tagsSetter(null, null) // 诊断 ID 清理
        eventTap = null
        streamDone = null
        claiming.set(false)
    }

    private suspend fun finalizeWith(taskId: String, status: TaskStatus, error: String?) {
        _activeTask.value?.takeIf { it.taskId == taskId }?.let {
            transitionAndPersist(it, status, CheckpointBoundary.ERROR, error)
        }
    }

    // ═══ 控制操作（Pause/Resume/Cancel/Retry，N-6）═══

    /** 用户请求暂停（可续）。返回 false = 当前无可暂停执行。 */
    suspend fun pause(): Boolean {
        val task = _activeTask.value ?: return false
        if (task.status != TaskStatus.RUNNING && task.status != TaskStatus.WAITING_USER &&
            task.status != TaskStatus.PLANNING
        ) return false
        pauseRequested = true
        engine.abort()
        // 等待引擎流收尾（镜像 collector 的 finally 落盘 PAUSED）
        streamDone?.await()
        return _activeTask.value?.status == TaskStatus.PAUSED
    }

    /** 用户请求取消（终态）。返回 false = 当前无可取消任务。 */
    suspend fun cancel(): Boolean {
        val task = _activeTask.value ?: return false
        if (task.isTerminal) return false
        cancelRequested = true
        if (streamDone != null) {
            engine.abort()
            streamDone?.await()
        } else {
            // 无执行流（PAUSED/WAITING/FAILED 态直接取消）：
            // FAILED→CANCELLED 是表内直达边（放弃路径）；其余经 CANCELLING 瞬态链
            val t = if (task.status == TaskStatus.FAILED) {
                TaskStatusMachine.transition(task, TaskStatus.CANCELLED, clock())
            } else {
                TaskStatusMachine.transition(task, TaskStatus.CANCELLING, clock())
                    .let { TaskStatusMachine.transition(it, TaskStatus.CANCELLED, clock()) }
            }
            persist(t, CheckpointBoundary.CANCELLED)
            emitRuntime(TaskRuntimeEvent.Finished(t.taskId, t.status))
        }
        return _activeTask.value?.status == TaskStatus.CANCELLED
    }

    /** 恢复暂停任务。返回执行流；null = 任务不可恢复（状态非 PAUSED/互斥占用）。 */
    fun resume(): Flow<AgentEvent>? {
        val task = _activeTask.value ?: return null
        if (task.status != TaskStatus.PAUSED) return null
        if (claiming.get()) return null

        // 注入恢复提示：告诉 LLM 从中断点继续（上下文同引擎连续）
        val resumeNote = "[RESUME] 此前任务被用户暂停。当前进度：${
            if (task.steps.isNotEmpty()) "步骤 ${task.completedSteps}/${task.steps.size} 完成" else "进行中"
        }。从中断处继续，不要重复已完成的操作。"
        return executeAsTask(UserInput.text(resumeNote), resumeOf = task)
    }

    /** 重试失败任务（retryCount 上限校验）。返回执行流；null = 不允许重试。 */
    fun retry(): Flow<AgentEvent>? {
        val task = _activeTask.value ?: return null
        if (task.status != TaskStatus.FAILED) return null
        if (task.retryCount >= retryLimit) {
            emitRuntimeSync(TaskRuntimeEvent.RetryExhausted(task.taskId, task.retryCount))
            return null
        }
        if (claiming.get()) return null

        val retrying = TaskStatusMachine.transition(task, TaskStatus.RETRYING, clock())
            .copy(retryCount = task.retryCount + 1)
        persist(retrying, CheckpointBoundary.RETRY_STARTED)

        val retryNote = "[RETRY] 上次执行失败：${task.error ?: "unknown"}。重试整个任务，" +
            "已完成且成功的操作（见对话历史）不要重复执行。"
        return executeAsTask(UserInput.text(retryNote), resumeOf = retrying)
    }

    // ═══ 崩溃恢复（N-5/D-3）═══

    /**
     * 启动发现（D-3：调用方为 VM init，确定性文件扫描）。
     *
     * 返回可恢复任务列表；PAUSED 态任务含在列表中（用户可选择继续），
     * 其他活跃态（RUNNING/WAITING/…）已在发现时迁移 RECOVERING。
     * 同步 IO —— 调用方应在 IO dispatcher 调度。
     */
    fun discoverRecoverableTasks(): List<AgentTask> {
        if (claiming.get()) return emptyList() // 执行中不做发现（保守）
        val active = store.loadActiveTasks()
        val recovered = mutableListOf<AgentTask>()
        for (task in active) {
            val entry = TaskStatusMachine.crashRecoveryEntry(task.status)
            // null = 不参与恢复（终态）；entry == status = 保持原态（PAUSED 语义）
            if (entry == null || entry == task.status) {
                recovered.add(task) // PAUSED：保持，等用户显式 resume
                continue
            }
            val migrated = TaskStatusMachine.transition(task, entry, clock())
            // 恢复扫描即修补悬空历史（R-5）：镜像 memory 修补一次并回写
            repairDanglingInMemory()
            persist(migrated, CheckpointBoundary.RECOVERED)
            recovered.add(migrated)
        }
        _activeTask.value = recovered.maxByOrNull { it.updatedAt }
        return recovered
    }

    /**
     * 从崩溃任务继续执行（用户在恢复横幅上选择"继续"）。
     *
     * 恢复注入 = RecoveryPolicy 提示（先验证 UNKNOWN 操作）。null = 不可恢复。
     */
    fun resumeFromCrash(taskId: String): Flow<AgentEvent>? {
        val task = _activeTask.value?.takeIf { it.taskId == taskId }
            ?: store.load(taskId)?.takeIf { it.isActive }
            ?: return null
        if (claiming.get()) return null

        val plan = RecoveryPolicy.planForTask(
            task,
            classify = { toolPolicy.classify(it) },
            isUserInteraction = { toolPolicy.isUserInteractionTool(it) }
        )
        val prompt = RecoveryPolicy.buildRecoveryPrompt(plan, task)
        // 状态迁移统一由 executeAsTask 处理（PAUSED/RECOVERING→RUNNING）
        return executeAsTask(UserInput.text(prompt), resumeOf = task)
    }

    /** 恢复前修补持久化对话历史中的悬空 toolCall（R-5）。 */
    private fun repairDanglingInMemory() {
        val mem = memory ?: return
        runCatching {
            val history = mem.load()
            val report = DanglingToolCallRepair.repair(history)
            if (report.hasRepairs) {
                mem.save(report.repairedHistory)
                AppLogger.instance.warn(
                    LogCategory.ENGINE, TAG,
                    "repaired ${report.repairedCallIds.size} dangling toolCalls in persisted history"
                )
            }
        }.onFailure {
            AppLogger.instance.error(LogCategory.ENGINE, TAG, "dangling repair failed: ${it.message}", it)
        }
    }

    // ═══ 任务历史查询（D-4：模型一等 + 历史列表）═══

    fun loadTaskHistory(): List<AgentTask> = store.loadAllTasks()

    // ═══ 辅助 ═══

    private fun newTask(input: UserInput, now: Long): AgentTask {
        val cfg = configProvider()
        return AgentTask(
            taskId = TaskIds.newId(now),
            title = input.text.lineSequence().firstOrNull()?.take(TaskStoreLimits.MAX_TITLE_LENGTH) ?: "Task",
            userInput = input.text,
            mode = cfg.mode,
            configSnapshot = cfg,
            createdAt = now
        )
    }

    private fun extractSteps(event: AgentEvent): List<TaskStepModel>? = when (event) {
        is AgentEvent.PlanConfirmed -> event.plan.steps.map {
            TaskStepModel(
                stepId = "s${it.index}", index = it.index, description = it.description,
                toolName = it.toolName, dependsOn = it.dependsOn
            )
        }
        is AgentEvent.SpecConfirmed -> event.spec.let { spec ->
            val items = spec.deliverables.ifEmpty { spec.requirements }.ifEmpty { listOf(spec.goal) }
            items.mapIndexed { i, text ->
                TaskStepModel(stepId = "s$i", index = i, description = text)
            }
        }
        else -> null
    }

    /** 压缩后重注入的任务状态 system 消息（N-9：受保护位）。 */
    private fun buildTaskStateMessage(task: AgentTask): String = buildString {
        append("[TASK STATE — 此消息由系统维护，压缩时保留]")
        appendLine()
        append("任务：${task.title}")
        if (task.steps.isNotEmpty()) {
            appendLine()
            append("进度：${task.completedSteps}/${task.steps.size} 步完成")
            task.steps.dropLast(0).take(3).forEach {
                appendLine("- [${it.status}] ${it.description.take(60)}")
            }
        }
        append("依据此状态继续执行，勿重复已完成步骤。")
    }

    private fun persist(task: AgentTask, boundary: CheckpointBoundary) {
        val stamped = task.copy(updatedAt = clock())
        // TaskStore 为同步阻塞语义（D-1 契约）；调用方运行于 IO dispatcher。
        // 失败不中断执行流（checkpoint 是尽力而为的持久层）。
        //
        // 先落盘再更新内存态：保证 _activeTask 对外可见的状态必已持久化，
        // 消除 waitTerminal(内存态可见 COMPLETED) 与 store.load(持久态仍 RUNNING)
        // 之间的竞态——内存态绝不能领先于持久态。
        runCatching { store.save(stamped) }.onFailure { e ->
            AppLogger.instance.error(
                LogCategory.ENGINE, TAG,
                "checkpoint save failed at $boundary: ${e.message}", e
            )
        }
        _activeTask.value = stamped
    }

    private fun transitionAndPersist(
        task: AgentTask,
        to: TaskStatus,
        boundary: CheckpointBoundary,
        error: String? = null
    ) {
        val migrated = TaskStatusMachine.transition(task, to, clock())
            .let { if (error != null) it.copy(error = error.take(TaskStoreLimits.MAX_ERROR_LENGTH)) else it }
        persist(migrated, boundary)
        emitRuntimeSync(TaskRuntimeEvent.StatusChanged(migrated.taskId, to))
    }

    private suspend fun persistSummary(task: AgentTask, summary: String, toolCalls: Int) {
        persist(
            task.copy(
                completionSummary = summary.take(TaskStoreLimits.MAX_OUTPUT_DIGEST),
                operations = task.operations // journal 保持
            ),
            CheckpointBoundary.COMPLETED
        )
    }

    private suspend fun emitRuntime(event: TaskRuntimeEvent) {
        runCatching { _runtimeEvents.emit(event) }
    }

    private fun emitRuntimeSync(event: TaskRuntimeEvent) {
        runCatching { _runtimeEvents.tryEmit(event) }
    }

    /** 供关闭测试用（生产单例不关闭）。 */
    fun shutdown() {
        scope.cancel()
    }

    /**
     * **测试专用**：模拟进程被 SIGKILL。
     *
     * 与 [shutdown] 的关键区别：短路 finalize（真实死亡不执行 finally），
     * TaskStore 停留在最后一个 checkpoint（崩溃现场原样）。用于确定性
     * 崩溃恢复测试（任务书 §23：模拟死亡 ≠ 真机 E2E，如实声明）。
     */
    fun simulateCrash() {
        crashSimulated = true
        scope.cancel()
    }

    private fun AgentTask.appendOperation(op: ToolOperationRecord): AgentTask =
        copy(
            operations = (operations + op).takeLast(TaskStoreLimits.MAX_JOURNAL_SIZE)
        )

    companion object {
        private const val TAG = "TaskRuntime"

        /** 用户显式重试上限（任务书 §7：不允许无限 retry）。 */
        const val DEFAULT_RETRY_LIMIT = 3
    }
}

/**
 * 任务生命周期低频事件（§16：AgentEvent 冻结，任务关联走此通道）。
 * 模式对应 A68 TaskLifecycleEvent，但携带持久层语义。
 */
sealed interface TaskRuntimeEvent {
    /** 持久层状态迁移。 */
    data class StatusChanged(val taskId: String, val status: TaskStatus) : TaskRuntimeEvent

    /** Checkpoint 落盘（含边界类型——观测/测试用）。 */
    data class CheckpointSaved(val taskId: String, val boundary: CheckpointBoundary) : TaskRuntimeEvent

    /** 任务到达终态。 */
    data class Finished(val taskId: String, val status: TaskStatus) : TaskRuntimeEvent

    /** 重试预算耗尽。 */
    data class RetryExhausted(val taskId: String, val retryCount: Int) : TaskRuntimeEvent

    /** 发现可恢复的崩溃任务（VM init 扫描）。 */
    data class RecoverableDiscovered(val tasks: List<AgentTask>) : TaskRuntimeEvent
}
