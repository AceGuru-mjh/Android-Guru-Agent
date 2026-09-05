package com.apex.agent.core.logging

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用内日志中枢。
 *
 * 设计目标：把散落在各子系统的日志汇聚到同一处，按"级别 × 分类 × 标签"三维
 * 组织，并提供内存缓冲、实时订阅与聚合统计。整个实现是纯 Kotlin/JVM，不依赖
 * Android，因此 core 层与 app 层都能直接使用。
 *
 * ## 体积感知缓冲
 * 内存缓冲按**估算字节数**而非条数上限管理，容量上限 [maxBufferBytes]
 * （默认 8MB）。超过上限时从队列头部（最早写入）逐条淘汰。这样无论单条日志
 * 多长、写入多密集，内存占用都有硬上限，且淘汰顺序严格遵循"先入先出"。
 *
 * ## 会话分段
 * [beginSession] / [endSession] 把连续日志切分为会话段，每段记录起止时间与
 * 日志区间。查看器可据此单独浏览或导出某次 Agent 运行的全过程。
 *
 * ## 并发（v2 修复）
 * 旧实现用协程 Mutex + `runBlocking` 保护状态：日志在任意线程（含主线程）写入
 * 时同步阻塞等待锁；配合每条写入都做一次 O(n) 全量列表拷贝并广播，日志洪峰
 * （Agent 流式输出期每 token 多条日志）下 CPU/内存瞬间打爆 → ANR/OOM；崩溃处理器
 * 里再调 `fatal()` 还可能死锁（崩溃恰好发生在持锁协程内 → 进程僵死）。
 *
 * v2 并发模型：
 * - 状态一致性用 Java 监视器锁（[lock]）保护——非挂起、无 runBlocking、无死锁面；
 * - 全量快照（`_records`）**限速发布**：最快每 [SNAPSHOT_INTERVAL_MS] 一次，
 *   由低优先级后台单线程池兜底刷新（保证洪峰结束后查看器最终一致），
 *   订阅方不再被每条日志触发全量重算；
 * - 统计改为**增量维护**，不再每次写入全表重算；
 * - 缓冲上限从 500MB 降至 8MB（500MB 在移动设备上等于必然 OOM）。
 */
class AppLogger(
    private val maxBufferBytes: Long = DEFAULT_MAX_BUFFER_BYTES
) {
    private val records = ArrayDeque<LogRecord>()
    private val totalBytes = AtomicLong(0)
    private val seq = AtomicLong(0)

    private val lock = Any()

    // 增量统计（全部在 lock 内维护）
    private var statTotal = 0
    private var statErrorCount = 0
    private val statByCategory = mutableMapOf<LogCategory, Int>()
    private val statByLevel = mutableMapOf<LogLevel, Int>()

    /** 实时广播：每条新日志都会发到这个 SharedFlow（重放最新若干条，便于新订阅者补全上下文）。 */
    private val _stream = MutableSharedFlow<LogRecord>(replay = 64, extraBufferCapacity = 1024)
    val stream = _stream.asSharedFlow()

    /** 聚合统计快照（按分类计数、按级别计数、总字节），增量维护、限速发布。 */
    private val _stats = MutableStateFlow(LogStats.EMPTY)
    val stats = _stats.asStateFlow()

    /**
     * 全量日志快照流：最新在前的不可变列表。
     * v2：限速发布（[SNAPSHOT_INTERVAL_MS]）——洪峰时不再每条日志一次 O(n) 拷贝。
     */
    private val _records = MutableStateFlow<List<LogRecord>>(emptyList())
    val recordsFlow = _records.asStateFlow()

    /** 当前活跃会话（null 表示未分段）。 */
    private var activeSession: LogSession? = null

    /** 快照是否待发布（洪峰期间置位，由后台线程兜底刷新）。 */
    @Volatile private var snapshotDirty = false
    @Volatile private var lastSnapshotAt = 0L

    /**
     * 低优先级后台单线程池：限速兜底发布快照。
     * 旧实现每条日志同步全量拷贝 + 广播；现在洪峰期间最多 4 次/秒，
     * 且不占用任何调用方线程（写入路径在锁内只做 O(1) 追加 + 增量统计）。
     */
    private val snapshotPublisher: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "apex-logsnapshot").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

    init {
        snapshotPublisher.scheduleWithFixedDelay(
            {
                if (snapshotDirty) {
                    publishSnapshot(force = true)
                }
            },
            SNAPSHOT_INTERVAL_MS,
            SNAPSHOT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

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
        val now = record.timestamp
        // v2：监视器锁内只做 O(1) 追加 + O(1) 增量统计 + 限速快照，
        // 任意线程（含主线程、崩溃处理器）调用都不会阻塞在协程锁上。
        val shouldPublishNow = synchronized(lock) {
            records.addLast(record)
            totalBytes.addAndGet(record.approxBytes.toLong())
            evictIfNeeded()
            activeSession?.let { s ->
                s.endId = record.id
                s.endTime = record.timestamp
                s.count++
            }
            // 增量统计
            statTotal++
            statByCategory[category] = (statByCategory[category] ?: 0) + 1
            statByLevel[level] = (statByLevel[level] ?: 0) + 1
            if (level.atLeast(LogLevel.ERROR)) statErrorCount++

            (now - lastSnapshotAt) >= SNAPSHOT_INTERVAL_MS
        }
        if (shouldPublishNow) {
            publishSnapshot(force = false)
        } else {
            snapshotDirty = true
        }
        _stream.tryEmit(record)
    }

    /**
     * 当估算总字节超过 [maxBufferBytes] 时，从队列头部（最早）逐条移除，
     * 直到回到上限以内。同步维护增量统计。
     */
    private fun evictIfNeeded() {
        while (totalBytes.get() > maxBufferBytes && records.isNotEmpty()) {
            val head = records.removeFirst()
            totalBytes.addAndGet(-head.approxBytes.toLong())
            statTotal--
            statByCategory[head.category]?.let { n ->
                if (n <= 1) statByCategory.remove(head.category) else statByCategory[head.category] = n - 1
            }
            statByLevel[head.level]?.let { n ->
                if (n <= 1) statByLevel.remove(head.level) else statByLevel[head.level] = n - 1
            }
            if (head.level.atLeast(LogLevel.ERROR)) statErrorCount--
        }
    }

    /** 限速发布全量快照 + 统计（必须在锁外调用，避免 StateFlow 订阅者回调死锁）。 */
    private fun publishSnapshot(force: Boolean) {
        val snapshot: List<LogRecord>
        val statsSnapshot: LogStats
        synchronized(lock) {
            if (!force && (System.currentTimeMillis() - lastSnapshotAt) < SNAPSHOT_INTERVAL_MS) {
                snapshotDirty = true
                return
            }
            snapshot = records.toList().asReversed()
            statsSnapshot = buildStatsLocked()
            lastSnapshotAt = System.currentTimeMillis()
            snapshotDirty = false
        }
        _records.value = snapshot
        _stats.value = statsSnapshot
    }

    private fun buildStatsLocked(): LogStats = LogStats(
        total = statTotal,
        totalBytes = totalBytes.get(),
        maxBytes = maxBufferBytes,
        byCategory = statByCategory.toMap(),
        byLevel = statByLevel.toMap(),
        errorCount = statErrorCount
    )

    // ───────────────────────── 读取 ─────────────────────────

    /** 返回当前缓冲区的不可变快照（最新在前）。 */
    fun snapshot(): List<LogRecord> = synchronized(lock) {
        records.toList().asReversed()
    }

    /**
     * 组合过滤：级别门槛（含）、分类白名单（null=全部）、标签集合（需全部命中，
     * 空集合=不限制）、关键词（不区分大小写，匹配 message 或 source，空=不限制）。
     * 返回最新在前的列表。
     */
    fun query(
        minLevel: LogLevel = LogLevel.VERBOSE,
        categories: Set<LogCategory>? = null,
        requiredTags: Set<String> = emptySet(),
        keyword: String = ""
    ): List<LogRecord> = synchronized(lock) {
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
    fun queryFiltered(
        minLevel: LogLevel = LogLevel.VERBOSE,
        categories: Set<LogCategory>? = null,
        keyword: String = "",
        sessionId: Long? = null
    ): List<LogRecord> = synchronized(lock) {
        val session = sessionId?.let { id -> sessions.firstOrNull { it.id == id } }
        val kw = keyword.lowercase()
        records.asReversed().filter { r ->
            r.level.atLeast(minLevel) &&
                (categories == null || r.category in categories) &&
                (session == null || (r.id >= session.startId && r.id <= session.endId)) &&
                (kw.isEmpty() || r.message.lowercase().contains(kw) || r.source.lowercase().contains(kw))
        }
    }

    /** 清空缓冲区与统计（不结束会话）。非挂起、非阻塞：可在 UI 回调/崩溃路径直接调用。 */
    fun clear() {
        synchronized(lock) {
            records.clear()
            totalBytes.set(0)
            statTotal = 0
            statErrorCount = 0
            statByCategory.clear()
            statByLevel.clear()
        }
        publishSnapshot(force = true)
    }

    // ───────────────────────── 会话分段 ─────────────────────────

    /** 开始一个新会话段，返回会话 id。若已有活跃会话则先结束它。 */
    fun beginSession(label: String = ""): Long = synchronized(lock) {
        endSessionLocked()
        val session = LogSession(
            id = seq.incrementAndGet(),
            label = label,
            startTime = System.currentTimeMillis(),
            startId = seq.get()
        )
        activeSession = session
        sessions.add(session)
        session.id
    }

    /** 结束当前活跃会话（若有时）。 */
    fun endSession() = synchronized(lock) { endSessionLocked() }

    private fun endSessionLocked() {
        activeSession?.let { s ->
            s.endId = seq.get()
            s.endTime = System.currentTimeMillis()
            activeSession = null
        }
    }

    /** 所有历史会话段（只读视图，[lock] 保护）。 */
    val sessions: MutableList<LogSession> = mutableListOf()

    companion object {
        /**
         * 快照发布最小间隔。日志洪峰（Agent 流式输出）下，查看器刷新频率
         * 被限制在 4 次/秒，不再每条日志触发 O(n) 全量拷贝 + LazyColumn 全量 diff。
         */
        const val SNAPSHOT_INTERVAL_MS: Long = 250

        /**
         * 默认内存缓冲上限：8MB。
         * 旧值 500MB 在 Android 设备上等于必然 OOM（进程堆通常 128–512MB）。
         * 8MB 约可容纳 2 万条平均 400 字节的日志，足够回溯完整会话。
         */
        const val DEFAULT_MAX_BUFFER_BYTES: Long = 8L * 1024 * 1024

        /** 进程内单例。 */
        val instance: AppLogger by lazy { AppLogger() }
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
