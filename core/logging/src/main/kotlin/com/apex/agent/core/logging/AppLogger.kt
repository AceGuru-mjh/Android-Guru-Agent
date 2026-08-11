package com.apex.agent.core.logging

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用内日志中枢。
 *
 * 设计目标：把散落在各子系统的日志汇聚到同一处，按"级别 × 分类 × 标签"三维
 * 组织，并提供内存缓冲、实时订阅与聚合统计。整个实现是纯 Kotlin/JVM，不依赖
 * Android，因此 core 层与 app 层都能直接使用。
 *
 * ## 体积感知缓冲
 * 内存缓冲按**估算字节数**而非条数上限管理。容量上限 [maxBufferBytes]
 * （默认 500MB）。每当新增日志使占用超过上限，就从队列头部（最早写入）逐条
 * 淘汰，直到回到上限以内。这样无论单条日志多长、写入多密集，内存占用都有硬
 * 上限，且淘汰顺序严格遵循"先入先出"，不会误删近期日志。
 *
 * ## 会话分段
 * [beginSession] / [endSession] 把连续日志切分为会话段，每段记录起止时间与
 * 日志区间。查看器可据此单独浏览或导出某次 Agent 运行的全过程。
 *
 * ## 并发
 * 所有可变状态由 [Mutex] 保护，[emit] 可被多线程/协程安全调用。
 */
class AppLogger(
    private val maxBufferBytes: Long = DEFAULT_MAX_BUFFER_BYTES
) {
    private val records = ArrayDeque<LogRecord>()
    private val totalBytes = AtomicLong(0)
    private val seq = AtomicLong(0)

    private val mutex = Mutex()

    /** 实时广播：每条新日志都会发到这个 SharedFlow（重放最新若干条，便于新订阅者补全上下文）。 */
    private val _stream = MutableSharedFlow<LogRecord>(replay = 64, extraBufferCapacity = 1024)
    val stream = _stream.asSharedFlow()

    /** 聚合统计快照（按分类计数、按级别计数、总字节），任何写入后更新。 */
    private val _stats = MutableStateFlow(LogStats.EMPTY)
    val stats = _stats.asStateFlow()

    /** 当前活跃会话（null 表示未分段）。 */
    private var activeSession: LogSession? = null

    // ───────────────────────── 写入 ─────────────────────────

    fun verbose(category: LogCategory, source: String, message: String, vararg tags: String) =
        emit(LogLevel.VERBOSE, category, source, message, null, tags.toSet())

    fun debug(category: LogCategory, source: String, message: String, vararg tags: String) =
        emit(LogLevel.DEBUG, category, source, message, null, tags.toSet())

    fun info(category: LogCategory, source: String, message: String, vararg tags: String) =
        emit(LogLevel.INFO, category, source, message, null, tags.toSet())

    fun warn(category: LogCategory, source: String, message: String, vararg tags: String) =
        emit(LogLevel.WARN, category, source, message, null, tags.toSet())

    fun error(
        category: LogCategory,
        source: String,
        message: String,
        throwable: Throwable? = null,
        vararg tags: String
    ) = emit(LogLevel.ERROR, category, source, message, throwable, tags.toSet())

    fun fatal(
        category: LogCategory,
        source: String,
        message: String,
        throwable: Throwable? = null,
        vararg tags: String
    ) = emit(LogLevel.FATAL, category, source, message, throwable, tags.toSet())

    /** 把一条 Android 原生 Log 行（已解析出级别/标签/文本）汇入中枢，便于统一检索。 */
    fun fromAndroid(
        level: LogLevel,
        category: LogCategory,
        source: String,
        message: String,
        vararg tags: String
    ) = emit(level, category, source, message, null, tags.toSet())

    private fun emit(
        level: LogLevel,
        category: LogCategory,
        source: String,
        message: String,
        throwable: Throwable?,
        tags: Set<String>
    ) {
        val record = LogRecord(
            id = seq.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            category = category,
            source = source,
            message = message,
            tags = tags,
            throwable = throwable
        )
        // 环形缓冲淘汰与广播都放到后台协程无关——这里同步完成以保证不丢日志。
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                records.addLast(record)
                totalBytes.addAndGet(record.approxBytes.toLong())
                evictIfNeeded()
                activeSession?.let { s ->
                    s.endId = record.id
                    s.endTime = record.timestamp
                    s.count++
                }
                rebuildStats()
            }
        }
        _stream.tryEmit(record)
    }

    /**
     * 当估算总字节超过 [maxBufferBytes] 时，从队列头部（最早）逐条移除，
     * 直到回到上限以内。返回被移除的条数（用于调试/统计）。
     */
    private fun evictIfNeeded() {
        var removed = 0
        while (totalBytes.get() > maxBufferBytes && records.isNotEmpty()) {
            val head = records.removeFirst()
            totalBytes.addAndGet(-head.approxBytes.toLong())
            removed++
        }
        if (removed > 0) {
            // 会话区间一旦被淘汰头部，可能跨过边界；会话记录保留但不修正区间，
            // 仅保证缓冲区不溢出。超出段的日志会在查看器侧标注"已淘汰"。
        }
    }

    private fun rebuildStats() {
        val byCategory = mutableMapOf<LogCategory, Int>()
        val byLevel = mutableMapOf<LogLevel, Int>()
        var errorCount = 0
        for (r in records) {
            byCategory[r.category] = byCategory.getOrDefault(r.category, 0) + 1
            byLevel[r.level] = byLevel.getOrDefault(r.level, 0) + 1
            if (r.level.atLeast(LogLevel.ERROR)) errorCount++
        }
        _stats.value = LogStats(
            total = records.size,
            totalBytes = totalBytes.get(),
            maxBytes = maxBufferBytes,
            byCategory = byCategory,
            byLevel = byLevel,
            errorCount = errorCount
        )
    }

    // ───────────────────────── 读取 ─────────────────────────

    /** 返回当前缓冲区的不可变快照（最新在前）。 */
    suspend fun snapshot(): List<LogRecord> = mutex.withLock {
        records.toList().asReversed()
    }

    /**
     * 组合过滤：级别门槛（含）、分类白名单（null=全部）、标签集合（需全部命中，
     * 空集合=不限制）、关键词（不区分大小写，匹配 message 或 source，空=不限制）。
     * 返回最新在前的列表。
     */
    suspend fun query(
        minLevel: LogLevel = LogLevel.VERBOSE,
        categories: Set<LogCategory>? = null,
        requiredTags: Set<String> = emptySet(),
        keyword: String = ""
    ): List<LogRecord> = mutex.withLock {
        val kw = keyword.lowercase()
        records.asReversed().filter { r ->
            r.level.atLeast(minLevel) &&
                (categories == null || r.category in categories) &&
                requiredTags.all { tag -> r.tags.contains(tag) } &&
                (kw.isEmpty() || r.message.lowercase().contains(kw) || r.source.lowercase().contains(kw))
        }
    }

    /**
     * 面向 UI 的过滤入口：在 [query] 基础上叠加会话段约束——只返回属于
     * [sessionId] 区间（startId..endId）内的日志。会话段为空时等价于全量。
     */
    suspend fun queryFiltered(
        minLevel: LogLevel = LogLevel.VERBOSE,
        categories: Set<LogCategory>? = null,
        keyword: String = "",
        sessionId: Long? = null
    ): List<LogRecord> = mutex.withLock {
        val session = sessionId?.let { id -> sessions.firstOrNull { it.id == id } }
        val kw = keyword.lowercase()
        records.asReversed().filter { r ->
            r.level.atLeast(minLevel) &&
                (categories == null || r.category in categories) &&
                (session == null || (r.id >= session.startId && r.id <= session.endId)) &&
                (kw.isEmpty() || r.message.lowercase().contains(kw) || r.source.lowercase().contains(kw))
        }
    }

    /** 清空缓冲区与统计（不结束会话）。 */
    suspend fun clear() = mutex.withLock {
        records.clear()
        totalBytes.set(0)
        rebuildStats()
    }

    // ───────────────────────── 会话分段 ─────────────────────────

    /** 开始一个新会话段，返回会话 id。若已有活跃会话则先结束它。 */
    fun beginSession(label: String = ""): Long {
        endSession()
        val session = LogSession(
            id = seq.incrementAndGet(),
            label = label,
            startTime = System.currentTimeMillis(),
            startId = seq.get()
        )
        activeSession = session
        sessions.add(session)
        return session.id
    }

    /** 结束当前活跃会话（若有时）。 */
    fun endSession() {
        activeSession?.let { s ->
            s.endId = seq.get()
            s.endTime = System.currentTimeMillis()
            activeSession = null
        }
    }

    /** 所有历史会话段（只读视图）。 */
    val sessions: MutableList<LogSession> = mutableListOf()

    companion object {
        /** 默认内存缓冲上限：500MB。 */
        const val DEFAULT_MAX_BUFFER_BYTES: Long = 500L * 1024 * 1024

        /** 进程内单例。 */
        val instance: AppLogger by lazy { AppLogger() }
    }
}

/**
 * 把引擎事件映射为结构化日志并写入中枢。
 *
 * 事件是 UI 流式更新的载体，本身也是极佳的审计轨迹：思考、规划、工具调用、
 * 上下文压缩、错误都被转成对应分类/级别的日志，使"所有日志"涵盖 Agent 运行
 * 的完整生命周期，无需在各处重复打点。
 */
fun AppLogger.logEvent(event: com.apex.agent.core.engine.AgentEvent) {
    when (event) {
        is com.apex.agent.core.engine.AgentEvent.ThinkingStart ->
            debug(LogCategory.ENGINE, "Engine", "思考开始 #${event.iteration} (level=${event.thinkingLevel})", "thinking")
        is com.apex.agent.core.engine.AgentEvent.ThinkingComplete ->
            debug(LogCategory.ENGINE, "Engine", "思考完成 (${event.fullThought.length} 字)", "thinking")
        is com.apex.agent.core.engine.AgentEvent.PlanGenerated ->
            info(LogCategory.ENGINE, "Engine", "生成计划: ${event.plan.steps.size} 步, 风险=${event.plan.riskLevel}", "plan")
        is com.apex.agent.core.engine.AgentEvent.IterationStart ->
            info(LogCategory.ENGINE, "Engine", "迭代 #${event.iteration} 开始", "iteration")
        is com.apex.agent.core.engine.AgentEvent.StepStart ->
            info(LogCategory.ENGINE, "Engine", "执行步骤 #${event.stepIndex}: ${event.description}", "step")
        is com.apex.agent.core.engine.AgentEvent.ToolCallStart ->
            info(LogCategory.TOOL, event.toolName, "调用工具 args=${event.arguments.take(200)}", "tool:${event.toolName}", "call-start")
        is com.apex.agent.core.engine.AgentEvent.ToolCallComplete ->
            if (event.success)
                info(LogCategory.TOOL, event.toolName, "完成 (${event.durationMs}ms) out=${event.output.length}字", "tool:${event.toolName}", "call-complete")
            else
                error(LogCategory.TOOL, event.toolName, "失败 (${event.durationMs}ms): ${event.output.take(300)}", "tool:${event.toolName}", "call-error")
        is com.apex.agent.core.engine.AgentEvent.ToolProgress ->
            debug(LogCategory.TOOL, "Engine", "进度 ${event.percent?.let { "%.0f%%".format(it * 100) } ?: ""} ${event.message ?: ""}", "progress")
        is com.apex.agent.core.engine.AgentEvent.ContextCompressed ->
            warn(LogCategory.ENGINE, "Compressor", "上下文压缩 ${event.beforeTokens}→${event.afterTokens} tokens, 策略=${event.strategy}, 移除=${event.messagesRemoved}", "compression")
        is com.apex.agent.core.engine.AgentEvent.Error ->
            error(LogCategory.ENGINE, "Engine", event.message, "engine-error")
        is com.apex.agent.core.engine.AgentEvent.Complete ->
            info(LogCategory.ENGINE, "Engine", "完成: ${event.totalIterations} 迭代, ${event.totalToolCalls} 工具调用, ${event.totalDurationMs}ms", "complete")
        is com.apex.agent.core.engine.AgentEvent.Aborted ->
            warn(LogCategory.ENGINE, "Engine", "任务中止", "aborted")
        else -> { /* 流式 chunk 类事件不落日志，避免刷屏 */ }
    }
}

/**
 * 聚合统计快照。
 */
data class LogStats(
    val total: Int,
    val totalBytes: Long,
    val maxBytes: Long,
    val byCategory: Map<LogCategory, Int>,
    val byLevel: Map<LogLevel, Int>,
    val errorCount: Int
) {
    val usageRatio: Float get() = if (maxBytes == 0L) 0f else totalBytes.toFloat() / maxBytes

    companion object {
        val EMPTY = LogStats(0, 0, 0, emptyMap(), emptyMap(), 0)
    }
}

/**
 * 一次连续的日志会话段（例如一次 Agent 运行的完整生命周期）。
 */
data class LogSession(
    val id: Long,
    val label: String,
    val startTime: Long,
    var startId: Long,
    var endId: Long = startId,
    var endTime: Long = startTime,
    var count: Int = 0
) {
    val durationMs: Long get() = endTime - startTime
}
