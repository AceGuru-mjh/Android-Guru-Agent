package com.apex.agent.core.code.longtask

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * # 长任务追踪器——事件流到持久记录的聚合器
 *
 * ## 职责与位置
 *
 * 挂在 VM 的 AgentEvent collect 链上（`tracker.onEvent(event)` 一行接线，
 * 引擎零改动）：运行期间**纯内存聚合**（每事件 O(1) 或 O(200) 有界操作，
 * 绝不碰磁盘），endRun 时判定规模——达到长任务阈值才组装
 * [LongTaskRecord] 并 fire-and-forget 落库，未达标返回 null 静默丢弃
 * （短任务不值得留档，这是 [LongTaskDetector] 的闸门语义）。
 *
 * ## 聚合规则（事件 → 信号）
 *
 * | 事件 | 聚合动作 |
 * |------|----------|
 * | IterationStart | 迭代计数 +1（计数而非取事件序号：Plan 多步执行每步从 1 重新编号） |
 * | ToolCallStart | 工具计数 +1、toolsUsed[名] +1 |
 * | ToolCallComplete | 对话行「工具 <名> ✓/✗」；成功且是 code_edit/code_write 时从 arguments 提取 path 入 filesTouched |
 * | ResponseChunk | 追加进 200 字符尾环缓冲（当前助手消息的尾巴） |
 * | ResponseComplete | 尾巴定格为「已完成消息」，对话行「助手: …」，环复位 |
 * | 其余事件 | 忽略（思考流 / 计划 / 进度等对本聚合无贡献） |
 *
 * filesTouched 只记**成功**的编辑调用：失败的编辑不保证落盘，计入会污染
 * 「改动集」语义（diff 与重跑上下文都依赖它）。toolsUsed 则含失败——
 * 「哪些工具在反复失败」本身就是重跑价值信号。
 *
 * ## 检查点策略
 *
 * 满足任一即追加（[maybeCheckpoint]）：
 * - **迭代驱动**：累计迭代数是 5 的倍数，且在**完成型事件**
 *   （ToolCallComplete / ResponseComplete）上触发—— IterationStart 时第 N
 *   轮的工具还没跑，此时拍照会把 toolCallCount / filesTouchedCount 少计一轮；
 * - **时间驱动**：距上个检查点 ≥60s 且期间有新事件（防止挂机时钟空转
 *   刷检查点：EPIC 任务里单个工具跑几分钟也该有中间快照，但纯静默期
 *   不该拍照）——任意事件均可触发（含 Start 型，长静默后的首个动作即拍照）。
 *
 * 同一轮内的多个完成型事件不重复拍（[lastCheckpointIteration] 守卫：
 * 第 5 轮的每个工具完成都满足 5%5==0，只拍第一次）。
 *
 * 检查点持有 ≤[LongTaskRecord.CHECKPOINTS_MAX]（10）个，超限丢最旧。
 *
 * ## 线程模型（为什么没有 @Volatile / 锁）
 *
 * 全部可变状态由 **VM 主线程串行调用**驱动（beginRun / onEvent /
 * noteTodos / endRun 都在 collect 回调或发送链路上，同一 Dispatcher 串行
 * 排队）——单写者无竞争，普通 var 即可，不需要 @Volatile（可见性由
 * 调度器的 happens-before 保证）也不需要锁。唯一的跨线程出口是
 * [persistScope].launch 落库——launch 捕获的是**已组装完成的不可变
 * [LongTaskRecord]**，与后续状态变更无共享可变引用，天然无竞争。
 * 若未来改为多线程喂事件，需先补同步（本类不预留投机锁——YAGNI）。
 *
 * ## 时钟注入
 *
 * [clock] 参数默认墙钟；测试注入假时钟驱动检查点的时间分支。生产侧
 * 不传即系统时间，DI 零负担。
 *
 * ## 防御性
 *
 * - beginRun 时若有未收尾的旧 run：旧 run 自动按 ABORTED 走完整收尾
 *   判定（够格照样落库——「用户忘了收尾」不该丢掉一次 EPIC 运行的画像）；
 * - endRun 无活跃 run → null（幂等，重复 endRun 第二次返回 null）；
 * - arguments JSON 解析失败 / path 非字符串 → 静默跳过（事件流是外部
 *   输入，格式不可信）；
 * - goal / title / summary / errorMessage 的截断在组装记录时统一执行
 *   （见 [LongTaskRecord] 字段文档的上限语义）。
 */
class LongTaskTracker(
    private val store: LongTaskStore,
    private val persistScope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    // ═══ 当前 run 的聚合状态（VM 主线程串行访问，见类 KDoc 线程模型）═══

    private var active = false
    private var goal = ""
    private var workspaceId = ""
    private var workspaceName = ""
    private var thinkingLevel = ""
    private var agentMode = ""
    private var startedAt = 0L

    private var iterationCount = 0
    private var toolCallCount = 0
    private val toolsUsed = LinkedHashMap<String, Int>()

    /** code_edit / code_write 成功调用提取的 path（LinkedHashSet 保插入序，组装时再排序）。 */
    private val filesTouched = LinkedHashSet<String>()

    /** 当前助手消息的尾环缓冲（≤[TAIL_RING_CHARS] 字符，溢出删头）。 */
    private val tailRing = StringBuilder()

    /** 已完成助手消息的尾巴（≤[COMPLETED_TAILS_MAX] 条，summary 的来源）。 */
    private val completedTails = ArrayDeque<String>()

    /** 对话摘要行（用户/助手/工具），检查点 recentExchange 的来源。 */
    private val exchangeLines = ArrayDeque<String>()

    private val checkpoints = ArrayList<LongTaskCheckpoint>()
    private var lastCheckpointAt = 0L
    private var lastCheckpointIteration = -1
    private var progressSinceCheckpoint = false

    private var todoSnapshot: List<String> = emptyList()

    /** 解析 ToolCallComplete.arguments 用的只读 Json（无配置依赖 parseToJsonElement）。 */
    private val argsJson = Json

    // ═══════════════════════ 生命周期 ═══════════════════════

    /**
     * 开始追踪一次运行。
     *
     * 若上一轮 run 未收尾仍活跃：先按 ABORTED 自动收尾（走完整规模判定，
     * 够格入库）再重置状态——「用户直接发新任务」是最常见的未收尾路径，
     * 不能让上一轮 EPIC 画像默默蒸发。
     *
     * @param goal 本次任务的首条用户消息全文（组记录时截断 ≤4000）。
     * @param thinkingLevel 运行档位名（ThinkingLevel.name）。
     * @param agentMode 运行模式名（AgentMode.name）。
     */
    fun beginRun(
        goal: String,
        workspaceId: String,
        workspaceName: String,
        thinkingLevel: String,
        agentMode: String
    ) {
        if (active) {
            finalizeRun(LongTaskStatus.ABORTED, errorMessage = null, summary = null)
            AppLogger.instance.warn(
                LogCategory.ENGINE, TAG,
                "上一轮长任务未收尾，已自动按 ABORTED 收尾判定"
            )
        }
        active = true
        this.goal = goal
        this.workspaceId = workspaceId
        this.workspaceName = workspaceName
        this.thinkingLevel = thinkingLevel
        this.agentMode = agentMode
        startedAt = clock()

        iterationCount = 0
        toolCallCount = 0
        toolsUsed.clear()
        filesTouched.clear()
        tailRing.clear()
        completedTails.clear()
        exchangeLines.clear()
        checkpoints.clear()
        todoSnapshot = emptyList()
        lastCheckpointAt = startedAt
        lastCheckpointIteration = -1
        progressSinceCheckpoint = false

        pushExchangeLine("用户: ${firstLineOrFallback(goal)}")
    }

    /**
     * 逐事件聚合（VM collect 里调用；纯内存 O(1)/O(200)，详见类 KDoc）。
     * 无活跃 run 时静默忽略（事件晚到 / 收尾后的尾部事件）。
     */
    fun onEvent(event: AgentEvent) {
        if (!active) return
        when (event) {
            is AgentEvent.IterationStart -> {
                iterationCount++
                progressSinceCheckpoint = true
                maybeCheckpoint(completionEvent = false)
            }
            is AgentEvent.ToolCallStart -> {
                toolCallCount++
                toolsUsed.merge(event.toolName, 1, Int::plus)
                progressSinceCheckpoint = true
                maybeCheckpoint(completionEvent = false)
            }
            is AgentEvent.ToolCallComplete -> {
                onToolCallComplete(event)
                progressSinceCheckpoint = true
                maybeCheckpoint(completionEvent = true)
            }
            is AgentEvent.ResponseChunk -> {
                appendToTailRing(event.text)
            }
            is AgentEvent.ResponseComplete -> {
                onResponseComplete(event.fullText)
            }
            else -> {
                // 思考流 / 计划 / 进度 / 错误 / 完成等事件对规模聚合无贡献，
                // 刻意忽略——收尾判定与状态归属由 VM 裁决后经 endRun 传入。
            }
        }
    }

    /**
     * VM 每轮刷新 todo 快照（渲染好的「☐/☑ 文本」行）。
     * 防御性截断：≤[TODO_LINES_MAX] 行、每行 ≤[TODO_LINE_MAX_CHARS] 字符
     * （todo 是模型输出，长度不可信）。空列表合法（清空 todo）。
     */
    fun noteTodos(todos: List<String>) {
        todoSnapshot = todos
            .map { line -> line.replace('\n', ' ').trim().take(TODO_LINE_MAX_CHARS) }
            .take(TODO_LINES_MAX)
    }

    /**
     * 收尾本次运行。
     *
     * @param status 最终状态（VM 裁决：Complete→COMPLETED / Error→FAILED /
     *   用户中止→ABORTED）。
     * @param errorMessage FAILED 时的原因（截断 ≤500）。
     * @param summary 显式收尾摘要（如 Complete 事件的 summary）；null 或
     *   空白时回退到尾环缓冲推导的「末条助手消息尾段」。
     * @return 未达长任务阈值 → null（不入库）；达标 → 已组装的记录
     *   （**已 fire-and-forget 投递到 [persistScope] 落库**——返回不等落盘
     *   完成；需要确认落盘的调用方自行在 persistScope 上 join/等待，常规
     *   UI 不必）。
     */
    fun endRun(
        status: LongTaskStatus,
        errorMessage: String? = null,
        summary: String? = null
    ): LongTaskRecord? {
        if (!active) return null
        return finalizeRun(status, errorMessage, summary)
    }

    // ═══════════════════════ 内部聚合 ═══════════════════════

    /** 收尾的共用实现（endRun 与 beginRun 的自动 ABORTED 收尾共用）。 */
    private fun finalizeRun(
        status: LongTaskStatus,
        errorMessage: String?,
        summary: String?
    ): LongTaskRecord? {
        active = false
        val endedAt = clock()
        val durationMs = (endedAt - startedAt).coerceAtLeast(0L)

        val signals = LongTaskDetector.Signals(
            iterations = iterationCount,
            toolCalls = toolCallCount,
            durationMs = durationMs,
            filesTouched = filesTouched.size
        )
        val magnitude = LongTaskDetector.magnitude(signals)
        if (magnitude == null) return null // 短任务：不留档

        val resolvedSummary = resolveSummary(summary)
        val record = LongTaskRecord(
            id = UUID.randomUUID().toString(),
            title = titleFromGoal(goal),
            goal = goal.take(LongTaskRecord.GOAL_MAX_CHARS),
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            thinkingLevel = thinkingLevel,
            agentMode = agentMode,
            status = status,
            createdAt = startedAt,
            updatedAt = endedAt,
            endedAt = endedAt,
            iterations = iterationCount,
            toolCalls = toolCallCount,
            durationMs = durationMs,
            filesTouched = filesTouched.toSortedSet().take(LongTaskRecord.FILES_MAX).toList(),
            toolsUsed = LinkedHashMap(toolsUsed),
            errorMessage = errorMessage?.take(LongTaskRecord.ERROR_MAX_CHARS),
            summary = resolvedSummary?.take(LongTaskRecord.SUMMARY_MAX_CHARS),
            checkpoints = ArrayList(checkpoints),
            todoSnapshot = ArrayList(todoSnapshot)
        )

        // fire-and-forget：VM 主线程绝不等待磁盘 IO（见类 KDoc 线程模型）。
        persistScope.launch { store.upsert(record) }
        return record
    }

    /** 显式 summary 优先；否则取末条助手消息尾巴（已完成消息 → 环缓冲）。 */
    private fun resolveSummary(explicit: String?): String? {
        if (!explicit.isNullOrBlank()) return explicit.trim()
        val fromCompleted = completedTails.lastOrNull()
        if (!fromCompleted.isNullOrBlank()) return fromCompleted
        val fromRing = tailRing.toString()
        return fromRing.takeIf { it.isNotBlank() }?.trim()
    }

    /** ToolCallComplete：对话行 + 成功编辑调用的 path 提取。 */
    private fun onToolCallComplete(event: AgentEvent.ToolCallComplete) {
        pushExchangeLine(
            "工具 ${event.toolName} ${if (event.success) SUCCESS_MARK else FAILURE_MARK}"
        )
        if (!event.success) return
        if (event.toolName !in FILE_TOUCH_TOOLS) return
        val path = extractPath(event.arguments) ?: return
        filesTouched.add(path)
    }

    /**
     * 从工具 arguments（JSON 字符串）防御式提取顶层字符串字段 `path`。
     *
     * code_edit / code_write 的参数 schema 顶层就是 path（相对工作区根）。
     * 空白 / 非 JSON / path 缺失 / path 非字符串 → null（事件流是外部输入，
     * 任何形状都必须吞得下）。kotlinx 的 SerializationException 是
     * IllegalArgumentException 子类，一并兜住。
     */
    private fun extractPath(arguments: String): String? = try {
        if (arguments.isBlank()) {
            null
        } else {
            val element = argsJson.parseToJsonElement(arguments)
            val path = (element as? JsonObject)?.get(PATH_FIELD)
            (path as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotBlank() }
        }
    } catch (e: IllegalArgumentException) {
        null
    }

    /** ResponseChunk：追加当前助手消息尾巴（环容量截断见 [appendToTailRing]）。 */
    private fun appendToTailRing(text: String) {
        if (text.isEmpty()) return
        tailRing.append(text)
        if (tailRing.length > TAIL_RING_CHARS) {
            tailRing.delete(0, tailRing.length - TAIL_RING_CHARS)
        }
    }

    /**
     * ResponseComplete：当前消息定格。
     *
     * 正常流里 ResponseChunk 先于 Complete 抵达（环里已是尾巴）；防御
     * 非流式路径（无 chunk 直达 Complete）——环空而 fullText 非空时取
     * fullText 尾段补位。
     */
    private fun onResponseComplete(fullText: String) {
        val tail = if (tailRing.isNotEmpty()) {
            tailRing.toString()
        } else if (fullText.isNotBlank()) {
            fullText.takeLast(TAIL_RING_CHARS)
        } else {
            return
        }
        tailRing.setLength(0)
        while (completedTails.size >= COMPLETED_TAILS_MAX) {
            completedTails.removeFirst()
        }
        completedTails.addLast(tail)
        pushExchangeLine("助手: ${singleLine(tail, EXCHANGE_LINE_MAX_CHARS)}")
        progressSinceCheckpoint = true
        maybeCheckpoint(completionEvent = true)
    }

    /**
     * 检查点闸门：迭代驱动（5 的倍数、非同迭代重复、且在完成型事件上——
     * 见类 KDoc 的计数语义）或时间驱动（距上点 ≥60s 且期间有新事件，
     * 任意事件可触发）。检查点本身 O(1)（只抄计数与行引用）。
     */
    private fun maybeCheckpoint(completionEvent: Boolean) {
        if (!active) return
        val iterationTriggered =
            completionEvent &&
                iterationCount > 0 &&
                iterationCount % CHECKPOINT_EVERY_ITERATIONS == 0 &&
                iterationCount != lastCheckpointIteration
        val timeTriggered =
            clock() - lastCheckpointAt >= CHECKPOINT_INTERVAL_MS && progressSinceCheckpoint
        if (!iterationTriggered && !timeTriggered) return

        checkpoints.add(
            LongTaskCheckpoint(
                id = UUID.randomUUID().toString(),
                atIteration = iterationCount,
                timestamp = clock(),
                toolCallCount = toolCallCount,
                filesTouchedCount = filesTouched.size,
                recentExchange = exchangeLines.toList().takeLast(LongTaskCheckpoint.RECENT_EXCHANGE_MAX),
                todoDigest = ArrayList(todoSnapshot)
            )
        )
        if (checkpoints.size > LongTaskRecord.CHECKPOINTS_MAX) {
            checkpoints.removeAt(0) // 丢最旧保最新（末段进程更有回看价值）
        }
        lastCheckpointAt = clock()
        lastCheckpointIteration = iterationCount
        progressSinceCheckpoint = false
    }

    /** 追加对话摘要行（截断 + 去换行），环容量 [EXCHANGE_LINES_MAX]。 */
    private fun pushExchangeLine(line: String) {
        val cleaned = singleLine(line, EXCHANGE_LINE_MAX_CHARS)
        if (cleaned.isBlank()) return
        while (exchangeLines.size >= EXCHANGE_LINES_MAX) {
            exchangeLines.removeFirst()
        }
        exchangeLines.addLast(cleaned)
    }

    // ═══════════════════════ 纯文本工具 ═══════════════════════

    /** title 语义：goal 首行截断 ≤60；空白 goal 落兜底文案。 */
    internal fun titleFromGoal(goalText: String): String {
        val first = firstLineOrFallback(goalText)
        return if (first.length > LongTaskRecord.TITLE_MAX_CHARS) {
            first.take(LongTaskRecord.TITLE_MAX_CHARS)
        } else {
            first
        }
    }

    private fun firstLineOrFallback(text: String): String {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        return if (first.isNullOrEmpty()) FALLBACK_TITLE else first
    }

    /** 压成单行（换行折叠为空格）并截断到 [maxChars]。 */
    private fun singleLine(text: String, maxChars: Int): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length > maxChars) flat.take(maxChars) else flat
    }

    private companion object {
        const val TAG = "LongTaskTracker"

        /** 尾环缓冲容量（字符）：当前助手消息的尾巴，供 summary / 对话行。 */
        const val TAIL_RING_CHARS = 200

        /** 已完成消息尾巴的保留条数（末条优先）。 */
        const val COMPLETED_TAILS_MAX = 8

        /** 对话摘要行环容量（检查点取尾 6，多留些余量给时间窗）。 */
        const val EXCHANGE_LINES_MAX = 16

        /** 单条对话摘要行截断（回看素材，不是全文）。 */
        const val EXCHANGE_LINE_MAX_CHARS = 120

        /** 迭代驱动检查点周期：每 5 次迭代。 */
        const val CHECKPOINT_EVERY_ITERATIONS = 5

        /** 时间驱动检查点周期：距上点 60s。 */
        const val CHECKPOINT_INTERVAL_MS = 60_000L

        /** 计入 filesTouched 的工具（coding 模式的两个落盘编辑工具）。 */
        val FILE_TOUCH_TOOLS = setOf("code_edit", "code_write")

        /** 工具参数里承载文件路径的字段名（code_edit / code_write schema）。 */
        const val PATH_FIELD = "path"

        const val SUCCESS_MARK = "✓"
        const val FAILURE_MARK = "✗"

        /** goal 全空时的标题兜底。 */
        const val FALLBACK_TITLE = "未命名任务"

        /** todo 快照防线：行数与单行长度上限。 */
        const val TODO_LINES_MAX = 50
        const val TODO_LINE_MAX_CHARS = 160
    }
}
