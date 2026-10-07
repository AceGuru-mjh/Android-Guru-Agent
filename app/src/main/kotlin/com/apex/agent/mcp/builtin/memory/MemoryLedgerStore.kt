package com.apex.agent.mcp.builtin.memory

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ═══════════════════════════════════════════════════════════════
 *  聊天记忆账本（v2）—— 带评分与生命周期的结构化记忆存储
 * ═══════════════════════════════════════════════════════════════
 *
 * v1 的聊天记忆是知识图谱实体上的纯字符串观察列表：没有元数据（何时
 * 记的、多重要、多久没被用到）、没有容量治理（画像观察无限追加）、
 * 召回按插入序取尾部（新但不重要的事实挤掉旧但重要的事实）。
 *
 * v2 把自动沉淀的聊天记忆升级为**结构化账本**：
 *
 *  1. **记忆条目 = 内容 + 元数据**：重要性 / 可信度 / 创建时间 / 最近
 *     访问时间 / 访问计数 —— 每条记忆有了可计算的生命体征；
 *  2. **综合评分**：`score = importance × 新近度 × 频率增益`——重要性
 *     是主轴，时间半衰（14 天）让长期未用的记忆自然让位，被召回过的
 *     记忆（访问计数）获得增益（越用越牢）；
 *  3. **容量治理（巩固）**：每类记忆有上限，超限时按评分淘汰最低分；
 *     近重复条目（同类别 + 词元 Jaccard ≥ 0.75）合并为一条（重要性取
 *     最大值 + 增益，访问计数求和）—— 记忆库收敛而不是发散；
 *  4. **待蒸馏轮次队列持久化**：批量蒸馏的对话窗口随账本落盘，进程
 *     被杀后未蒸馏的轮次不丢，下次触发时并入更大的窗口重试。
 *
 * 与 [KnowledgeGraphStore] 的关系：memory MCP 工具（模型显式读写）
 * 继续走知识图谱；本账本只服务**自动沉淀链路**（启发式捕获 + 批量
 * 蒸馏 + 评分召回）。两类存储物理分离，互不污染。
 *
 * 持久化：`<filesDir>/chat_memory/ledger.json`，tmp+rename 原子写；
 * 损坏兜底：解析失败原文留档 .corrupt 后空账本启动（宁可丢账不可卡死）。
 *
 * 线程模型：实例内单一 [lock] 串行化全部读写（含落盘），与
 * KnowledgeGraphStore 同款纪律；调用方为 ChatMemoryPipeline（主线程
 * 启发式路径 + IO 蒸馏协程），无高并发诉求。
 */
class MemoryLedgerStore(private val storageDir: File) {

    // ── 数据模型 ─────────────────────────────────────────────────

    /** 记忆种类（与 [ChatMemorySchema] 的三类契约对齐）。 */
    enum class MemoryKind { PROFILE, STATE, MILESTONE }

    /** 记忆来源：启发式捕获 / LLM 批量蒸馏 / 旧图谱一次性迁移。 */
    enum class MemorySource { HEURISTIC, DISTILL, MIGRATED }

    /** 一条结构化记忆（账本的最小单元）。 */
    @Serializable
    data class MemoryRecord(
        /** 稳定主键：内容规范化摘要的哈希（同内容天然幂等）。 */
        val id: String,
        val content: String,
        val kind: MemoryKind,
        val source: MemorySource,
        /** 重要性 0..1（评分主轴；重复捕获 / 蒸馏更新时单调抬升）。 */
        val importance: Float,
        /** 可信度 0..1（蒸馏产物默认高于启发式整句）。 */
        val credibility: Float,
        val createdAt: Long,
        val lastAccessedAt: Long,
        /** 被召回注入的次数（频率增益输入）。 */
        val accessCount: Int
    )

    /** 待蒸馏的一轮对话（批量窗口的元素）。 */
    @Serializable
    data class MemoryTurn(
        val userText: String,
        val assistantText: String,
        val at: Long
    )

    /** 写入结果：新增 / 既有条目重要性抬升 / 纯去重。 */
    data class AddResult(val added: Boolean, val bumped: Boolean) {
        companion object {
            internal val NO_OP = AddResult(added = false, bumped = false)
        }
    }

    /** 巩固结果：合并对数 + 淘汰条数。 */
    data class ConsolidateResult(val mergedPairs: Int, val evicted: Int)

    /** 落盘形态。 */
    @Serializable
    private data class LedgerState(
        val records: List<MemoryRecord> = emptyList(),
        val pendingTurns: List<MemoryTurn> = emptyList()
    )

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var state = LedgerState()

    init {
        loadFromDisk()
    }

    // ── 读面 ──────────────────────────────────────────────────────

    /** 全部记录（快照，按 kind 契约序 + createdAt 稳定排序）。 */
    fun allRecords(): List<MemoryRecord> = synchronized(lock) {
        state.records.sortedWith(
            compareBy({ kindOrder(it.kind) }, { it.createdAt })
        )
    }

    fun recordsOf(kind: MemoryKind): List<MemoryRecord> = synchronized(lock) {
        state.records.filter { it.kind == kind }.sortedBy { it.createdAt }
    }

    fun count(): Int = synchronized(lock) { state.records.size }

    /** 评分 Top-N（画像注入的主通道：重要 × 新近 × 常用）。 */
    fun topByScore(kind: MemoryKind, limit: Int, nowMs: Long): List<MemoryRecord> =
        synchronized(lock) {
            state.records
                .filter { it.kind == kind }
                .sortedByDescending { score(it, nowMs) }
                .take(limit)
        }

    /**
     * 关键词检索：命中关键词数加权 × 综合评分排序。
     * 关键词由调用方分词（与既有 relatedEntities 同款切分）。
     */
    fun search(
        keywords: List<String>,
        kinds: Set<MemoryKind>,
        limit: Int,
        nowMs: Long
    ): List<MemoryRecord> = synchronized(lock) {
        if (keywords.isEmpty()) return emptyList()
        state.records
            .filter { it.kind in kinds }
            .mapNotNull { record ->
                val haystack = record.content.lowercase()
                val hits = keywords.count { it.length >= 2 && haystack.contains(it) }
                if (hits == 0) null else record to hits
            }
            .sortedWith(
                compareByDescending<Pair<MemoryRecord, Int>> { it.second }
                    .thenByDescending { score(it.first, nowMs) }
            )
            .take(limit)
            .map { it.first }
    }

    /** 综合评分：重要性 × 新近衰减 × 频率增益（锁内/锁外皆可调用，纯函数）。 */
    internal fun score(record: MemoryRecord, nowMs: Long): Float {
        val ageDays = ((nowMs - record.lastAccessedAt).coerceAtLeast(0L)) / DAY_MS
        val recency = RECENCY_FLOOR + (1f - RECENCY_FLOOR) *
            Math.pow(2.0, -ageDays / HALF_LIFE_DAYS).toFloat()
        val frequency = 1f + FREQ_GAIN * kotlin.math.ln(1f + record.accessCount)
        return record.importance * recency * frequency
    }

    // ── 写面 ──────────────────────────────────────────────────────

    /**
     * 新增一条记忆（内容级幂等）：
     *  - 规范化内容已存在（同 kind）→ 重要性抬升 [IMPORTANCE_BUMP] 后返回
     *    bumped（重复即强化，不产生重复条目）；
     *  - 新内容 → 以 [importance] 入账。
     * 空白 / 超长内容直接拒绝。
     */
    fun add(
        content: String,
        kind: MemoryKind,
        source: MemorySource,
        importance: Float,
        nowMs: Long
    ): AddResult = synchronized(lock) {
        val trimmed = content.trim()
        if (trimmed.length !in 2..MAX_CONTENT_LENGTH) return AddResult.NO_OP
        val id = recordId(trimmed, kind)
        val existing = state.records.firstOrNull { it.id == id }
        if (existing != null) {
            state = state.copy(
                records = state.records.map {
                    if (it.id == id) it.copy(
                        importance = (it.importance + IMPORTANCE_BUMP).coerceAtMost(1f)
                    ) else it
                }
            )
            save()
            return AddResult(added = false, bumped = true)
        }
        state = state.copy(
            records = state.records + MemoryRecord(
                id = id,
                content = trimmed,
                kind = kind,
                source = source,
                importance = importance.coerceIn(0f, 1f),
                credibility = if (source == MemorySource.DISTILL) 0.85f else 0.65f,
                createdAt = nowMs,
                lastAccessedAt = nowMs,
                accessCount = 0
            )
        )
        save()
        AddResult(added = true, bumped = false)
    }

    /**
     * 蒸馏更新：按旧内容（精确或规范化匹配）替换为完整新内容，
     * 重要性抬升、时间戳刷新。未命中返回 false（调用方按需补一条 new）。
     */
    fun applyUpdate(oldContent: String, newContent: String, nowMs: Long): Boolean =
        synchronized(lock) {
            val trimmedNew = newContent.trim()
            if (trimmedNew.length !in 2..MAX_CONTENT_LENGTH) return false
            val oldId = recordId(oldContent.trim(), MemoryKind.PROFILE)
            val target = state.records.firstOrNull { it.id == oldId }
                ?: state.records.firstOrNull { it.content == oldContent.trim() }
                ?: return false
            state = state.copy(
                records = state.records.map {
                    if (it.id == target.id) it.copy(
                        content = trimmedNew,
                        importance = (it.importance + IMPORTANCE_BUMP).coerceAtMost(1f),
                        lastAccessedAt = nowMs
                    ) else it
                }
            )
            save()
            true
        }

    /** 近况基调的有界替换：STATE 类永远只保留最新一条。 */
    fun replaceState(content: String, nowMs: Long) = synchronized(lock) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        state = state.copy(
            records = state.records.filter { it.kind != MemoryKind.STATE } + MemoryRecord(
                id = recordId(trimmed, MemoryKind.STATE),
                content = trimmed,
                kind = MemoryKind.STATE,
                source = MemorySource.HEURISTIC,
                importance = 0.6f,
                credibility = 0.6f,
                createdAt = nowMs,
                lastAccessedAt = nowMs,
                accessCount = 0
            )
        )
        save()
    }

    /** 按内容删除（精确匹配优先，规范化兜底）。返回删除条数。 */
    fun remove(kind: MemoryKind, content: String): Int = synchronized(lock) {
        val trimmed = content.trim()
        val id = recordId(trimmed, kind)
        val before = state.records.size
        state = state.copy(
            records = state.records.filterNot {
                (it.kind == kind) && (it.content == trimmed || it.id == id)
            }
        )
        val removed = before - state.records.size
        if (removed > 0) save()
        removed
    }

    /** 按主键删除（记忆页逐条删除走这里——主键比内容精确）。 */
    fun removeById(id: String): Boolean = synchronized(lock) {
        val before = state.records.size
        state = state.copy(records = state.records.filterNot { it.id == id })
        val removed = before - state.records.size
        if (removed > 0) save()
        removed > 0
    }

    /** 清空指定类别（一键清空：三类全清；保留其余类别）。 */
    fun clearKinds(kinds: Set<MemoryKind>): Int = synchronized(lock) {
        val before = state.records.size
        state = state.copy(records = state.records.filterNot { it.kind in kinds })
        val removed = before - state.records.size
        if (removed > 0) save()
        removed
    }

    /** 召回打点：被注入的记忆获得访问计数 + 1 与 lastAccessedAt 刷新。 */
    fun touch(ids: List<String>, nowMs: Long) = synchronized(lock) {
        if (ids.isEmpty()) return
        val idSet = ids.toHashSet()
        state = state.copy(
            records = state.records.map {
                if (it.id in idSet) it.copy(
                    accessCount = it.accessCount + 1,
                    lastAccessedAt = nowMs
                ) else it
            }
        )
        save()
    }

    // ── 巩固（容量治理 + 近重复合并）──────────────────────────────

    /**
     * 巩固 pass：蒸馏批次落账后调用。
     *  1. 近重复合并：同 kind + 词元 Jaccard ≥ [MERGE_JACCARD] 的条目对
     *     合并（保留评分高者的内容；importance = max + bump、accessCount
     *     求和、createdAt 取更早者——保留更老的身世）；
     *  2. 容量淘汰：超过类别上限时按评分升序淘汰（STATE 由 replaceState
     *     天然有界，不参与）。
     */
    fun consolidate(nowMs: Long): ConsolidateResult = synchronized(lock) {
        var mergedPairs = 0
        var records = state.records

        // 近重复合并（O(n²) 但 n ≤ 上百，账本量级下毫秒级）
        for (kind in listOf(MemoryKind.PROFILE, MemoryKind.MILESTONE)) {
            var merged = true
            while (merged) {
                merged = false
                val pool = records.filter { it.kind == kind }
                outer@ for (i in pool.indices) {
                    for (j in i + 1 until pool.size) {
                        val a = pool[i]
                        val b = pool[j]
                        if (jaccard(tokens(a.content), tokens(b.content)) >= MERGE_JACCARD) {
                            val (keep, drop) =
                                if (score(a, nowMs) >= score(b, nowMs)) a to b else b to a
                            val mergedRecord = keep.copy(
                                importance = (maxOf(a.importance, b.importance) + IMPORTANCE_BUMP)
                                    .coerceAtMost(1f),
                                accessCount = a.accessCount + b.accessCount,
                                createdAt = minOf(a.createdAt, b.createdAt)
                            )
                            records = records.filterNot { it.id == a.id || it.id == b.id } + mergedRecord
                            mergedPairs++
                            merged = true
                            break@outer
                        }
                    }
                }
            }
        }

        // 容量淘汰：评分最低者优先出账
        var evicted = 0
        for (entry in CAPACITY) {
            val pool = records.filter { it.kind == entry.key }
            if (pool.size > entry.value) {
                val doomed = pool
                    .sortedBy { score(it, nowMs) }
                    .take(pool.size - entry.value)
                    .map { it.id }
                    .toHashSet()
                records = records.filterNot { it.id in doomed }
                evicted += doomed.size
            }
        }

        if (mergedPairs > 0 || evicted > 0) {
            state = state.copy(records = records)
            save()
        }
        ConsolidateResult(mergedPairs, evicted)
    }

    // ── 待蒸馏轮次队列（持久化，进程被杀不丢）─────────────────────

    /** 入队一轮对话（有界：超出 [MAX_PENDING_TURNS] 丢最旧）。 */
    fun enqueueTurn(userText: String, assistantText: String, nowMs: Long) =
        synchronized(lock) {
            val turn = MemoryTurn(
                userText = userText.take(MAX_TURN_TEXT_LENGTH),
                assistantText = assistantText.take(MAX_TURN_TEXT_LENGTH),
                at = nowMs
            )
            val turns = (state.pendingTurns + turn).takeLast(MAX_PENDING_TURNS)
            state = state.copy(pendingTurns = turns)
            save()
        }

    fun pendingTurnCount(): Int = synchronized(lock) { state.pendingTurns.size }

    /** 只读窗口快照（蒸馏失败时队列保留，下次并入更大窗口重试）。 */
    fun peekPendingTurns(): List<MemoryTurn> = synchronized(lock) {
        state.pendingTurns.toList()
    }

    /** 蒸馏成功后清空队列。 */
    fun clearPendingTurns() = synchronized(lock) {
        if (state.pendingTurns.isEmpty()) return
        state = state.copy(pendingTurns = emptyList())
        save()
    }

    // ── 旧图谱一次性迁移 ──────────────────────────────────────────

    /**
     * 从 v1 知识图谱的三类聊天实体导入（幂等：仅当账本为空时执行）。
     * 返回迁移条数；调用方负责把图谱侧实体删除（账本成为唯一事实源）。
     */
    fun migrateFromGraph(entities: List<GraphEntity>, nowMs: Long): Int = synchronized(lock) {
        if (state.records.isNotEmpty()) return 0
        var migrated = 0
        for (entity in entities) {
            val kind = when (entity.entityType) {
                ChatMemorySchema.PROFILE_TYPE -> MemoryKind.PROFILE
                ChatMemorySchema.STATE_TYPE -> MemoryKind.STATE
                ChatMemorySchema.MILESTONE_TYPE -> MemoryKind.MILESTONE
                else -> continue
            }
            for (observation in entity.observations) {
                val trimmed = observation.trim()
                if (trimmed.length !in 2..MAX_CONTENT_LENGTH) continue
                state = state.copy(
                    records = state.records + MemoryRecord(
                        id = recordId(trimmed, kind),
                        content = trimmed,
                        kind = kind,
                        source = MemorySource.MIGRATED,
                        importance = if (kind == MemoryKind.MILESTONE) 0.7f else 0.55f,
                        credibility = 0.6f,
                        createdAt = nowMs,
                        lastAccessedAt = nowMs,
                        accessCount = 0
                    )
                )
                migrated++
            }
        }
        if (migrated > 0) save()
        migrated
    }

    // ── 规范化与工具 ─────────────────────────────────────────────

    /** 内容规范化：小写 + 去标点 + 压缩空白（去重的比较基准）。 */
    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[\\p{Punct}\\p{IsPunctuation}\\s]+"), "")

    /** 稳定主键：kind + 规范化内容的 hash（同内容跨进程幂等）。 */
    private fun recordId(content: String, kind: MemoryKind): String =
        kind.name + "_" + Integer.toHexString(normalize(content).hashCode())

    /**
     * 词元化（近重复合并的比较基准）：
     *  - CJK 连续段 → 相邻双字组（用户/户喜/喜欢…—— 短文本相似度的主力信号）；
     *  - 拉丁/数字连续段 → 整段一个小写词元（react / rust / 18 —— 数字串
     *    保留单字符，让「编号1」与「编号2」在词元层可区分，不误合并）。
     */
    private fun tokens(text: String): Set<String> {
        val result = HashSet<String>()
        val run = StringBuilder()
        var runIsCjk = false
        fun flushRun() {
            if (run.isEmpty()) return
            if (runIsCjk) {
                for (i in 0 until run.length - 1) result.add(run.substring(i, i + 2))
            } else {
                result.add(run.toString())
            }
            run.setLength(0)
        }
        for (ch in text.lowercase()) {
            val isCjk = ch.code in CJK_START..CJK_END
            if (isCjk || ch.isLetterOrDigit()) {
                if (run.isNotEmpty() && isCjk != runIsCjk) flushRun()
                runIsCjk = isCjk
                run.append(ch)
            } else {
                flushRun()
            }
        }
        flushRun()
        return result
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val intersection = a.intersect(b).size.toFloat()
        val union = a.union(b).size.toFloat()
        return intersection / union
    }

    private fun kindOrder(kind: MemoryKind): Int = when (kind) {
        MemoryKind.PROFILE -> 0
        MemoryKind.STATE -> 1
        MemoryKind.MILESTONE -> 2
    }

    // ── 持久化 ─────────────────────────────────────────────────────

    private fun storageFile(): File = File(storageDir, FILE_NAME)

    private fun loadFromDisk() {
        val file = storageFile()
        if (!file.isFile) return
        try {
            state = json.decodeFromString(LedgerState.serializer(), file.readText())
        } catch (e: Exception) {
            // 损坏兜底：留档 + 空账本启动（与 KnowledgeGraphStore 同款纪律）
            runCatching { file.copyTo(File(storageDir, "$FILE_NAME.corrupt"), overwrite = true) }
            state = LedgerState()
        }
    }

    private fun save() {
        storageDir.mkdirs()
        val text = json.encodeToString(LedgerState.serializer(), state)
        atomicWrite(storageFile(), text)
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val FILE_NAME = "ledger.json"
        const val MAX_CONTENT_LENGTH = 120
        const val MAX_TURN_TEXT_LENGTH = 1200
        const val MAX_PENDING_TURNS = 12

        /** CJK 统一表意文字区间（词元化的双字组判定）。 */
        const val CJK_START = 0x4E00
        const val CJK_END = 0x9FFF

        /** 时间半衰（天）：14 天未被召回，新近度衰减到一半。 */
        const val HALF_LIFE_DAYS = 14.0

        /** 新近度地板：再旧的记忆也保留 55% 的新近度权重。 */
        const val RECENCY_FLOOR = 0.55f

        /** 频率增益：每次召回 +12% × ln(1+次数) 的评分乘数。 */
        const val FREQ_GAIN = 0.12f

        /** 重复捕获 / 蒸馏更新时的重要性抬升。 */
        const val IMPORTANCE_BUMP = 0.1f

        /** 近重复合并阈值（词元 Jaccard）。 */
        const val MERGE_JACCARD = 0.75f

        const val DAY_MS = 24L * 60 * 60 * 1000

        /** 类别容量上限（STATE 由 replaceState 天然有界为 1）。 */
        val CAPACITY = mapOf(
            MemoryKind.PROFILE to 60,
            MemoryKind.MILESTONE to 30
        )
    }
}
