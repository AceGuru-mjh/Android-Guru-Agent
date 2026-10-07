package com.apex.agent.mcp.builtin.memory

import com.apex.agent.core.engine.ChatEmotion
import com.apex.agent.core.engine.ChatSignalDetector
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemoryKind
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemoryRecord
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemorySource
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 聊天记忆管线 v2 —— 对话内容自动沉淀为带生命周期的长期记忆。
 *
 * ## v2 架构（结构化账本 + 批量蒸馏 + 评分召回）
 *
 *  1. **双速捕获**：启发式层零成本同步落账（自我披露 / 里程碑句式匹配，
 *     当轮生效），LLM 蒸馏层低频异步批量跑（攒批窗口 + 写入门禁，
 *     SUMMARY 角色路由到便宜模型）；
 *  2. **结构化账本**：自动记忆沉淀到 [MemoryLedgerStore]（评分 / 时间 /
 *     访问计数 / 容量治理），与 memory MCP 的知识图谱物理分离 —— 模型
 *     经 mcp__memory__* 工具显式写的主题实体仍走图谱，互不污染；
 *  3. **评分召回**：画像注入按综合评分（重要 × 新近 × 常用）取 Top-N
 *     而非插入序尾部 —— 被反复用到的稳定事实不再被新噪音挤出窗口；
 *     被召回的记忆自动打点（访问计数 +1），越用越牢；
 *  4. **巩固遗忘**：每批蒸馏落账后跑一次巩固 pass（近重复合并 + 容量
 *     淘汰），账本收敛而不发散；
 *  5. **情绪纵览**：每轮用 [ChatSignalDetector] 与引擎同一套口径判定
 *     情绪，滚动窗口汇总为「用户近况」的单条基调观察（有界替换）；
 *  6. **里程碑日历**：句子级「日期模式 × 人生事件标记」双命中即沉淀，
 *     召回时作为独立段注入 —— Agent 在日子临近时能自然提起。
 *
 * ## 迁移兼容
 *
 * 首次构造时把 v1 图谱里三类聊天实体（画像 / 近况 / 里程碑）的观察
 * 一次性导入账本并从图谱移除 —— 用户既有记忆无损升级，账本成为自动
 * 记忆的唯一事实源。
 *
 * ## 失败语义（防御式 IO 纪律）
 *
 * 所有公开方法绝不向上抛：recall 失败返回 null（省略注入段），
 * onTurn 失败只记日志——记忆子系统的问题绝不阻断主对话。蒸馏失败时
 * 待蒸馏队列保留（持久化），下次触发并入更大窗口重试。
 *
 * ## 线程模型
 *
 * - recall / onTurn 的启发式路径在调用方上下文执行（内存账本操作 +
 *   偶发落盘，毫秒级）；蒸馏转 [scope]（SupervisorJob + IO）
 *   fire-and-forget，捕获不可变快照，失败不传染；
 * - [MemoryLedgerStore] 内部单锁串行化，无需外再加锁。
 */
@Singleton
class ChatMemoryPipeline @Inject constructor(
    private val graph: KnowledgeGraphStore,
    private val ledger: MemoryLedgerStore,
    private val distiller: MemoryDistiller
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 蒸馏节拍计数器（进程级；重启归零无害——队列持久化不丢轮次）。 */
    private val turnCounter = AtomicInteger(0)

    /** 情绪基调滚动窗（近 [TONE_WINDOW] 个情绪轮；进程级，重启后由新一轮重建）。 */
    private val toneWindow = ArrayDeque<ChatEmotion>()

    init {
        migrateFromGraphOnce()
    }

    /**
     * v1 → v2 一次性迁移：图谱三类聊天实体的观察导入账本（账本非空时
     * 幂等跳过），随后从图谱移除这三类实体 —— 自动记忆的事实源切换到
     * 账本，图谱回归「模型显式写入的主题实体」的本来语义。
     */
    private fun migrateFromGraphOnce() {
        runCatching {
            val graphSnapshot = graph.readGraph()
            val chatEntities = graphSnapshot.entities.filter {
                it.entityType in ChatMemorySchema.ENTITY_TYPES
            }
            if (chatEntities.isEmpty()) return
            val now = System.currentTimeMillis()
            val migrated = ledger.migrateFromGraph(chatEntities, now)
            if (migrated > 0) {
                graph.deleteEntities(chatEntities.map { it.name })
                AppLogger.instance.info(
                    LogCategory.CS_MEM, "ChatMemoryPipeline",
                    "v1 图谱聊天记忆迁移完成：$migrated 条入账本，图谱侧实体已移除"
                )
            }
        }.onFailure { e ->
            AppLogger.instance.warn(
                LogCategory.CS_MEM, "ChatMemoryPipeline",
                "图谱迁移失败(忽略，下次启动重试): ${e.message}"
            )
        }
    }

    // ── 召回面（引擎 execute 入口调用）─────────────────────────────

    /**
     * 检索与当前用户消息相关的长期记忆，格式化为注入段内容。
     *
     * 组成：画像评分 Top-N（重要 × 新近 × 常用）+ 关键词直接命中的画像
     * 记忆（去重合并，保证被话题直接提到的记忆优先注入）+「用户近况」
     * 情绪基调 +「用户里程碑」最近几条 + 按消息关键词检索命中的图谱主题
     * 实体观察。被注入的账本条目自动打点（访问计数强化）；总长钳制
     * [MAX_RECALL_CHARS]；无可召回内容返回 null。
     */
    suspend fun recall(userText: String): String? = runCatching {
        val now = System.currentTimeMillis()
        val topProfile = ledger.topByScore(MemoryKind.PROFILE, MAX_PROFILE_ITEMS, now)
        val keywordProfile = keywordHits(userText, now)
        val profile = (topProfile + keywordProfile).distinctBy { it.id }.take(MAX_PROFILE_ITEMS)
        val tone = ledger.recordsOf(MemoryKind.STATE).lastOrNull()
        val milestones = ledger.recordsOf(MemoryKind.MILESTONE)
            .sortedBy { it.createdAt }
            .takeLast(MAX_MILESTONE_RECALL)
        val related = relatedEntities(userText)
        if (profile.isEmpty() && tone == null && milestones.isEmpty() && related.isEmpty()) {
            return null
        }

        // 召回打点：被注入的条目频率强化（一次落盘，毫秒级）
        val recalledIds = (profile + milestones).map { it.id }
        if (recalledIds.isNotEmpty()) {
            runCatching { ledger.touch(recalledIds, now) }
        }

        buildString {
            if (profile.isNotEmpty()) {
                appendLine("### 关于用户")
                profile.forEach { appendLine("- ${it.content}") }
            }
            tone?.let {
                if (isNotEmpty()) appendLine()
                appendLine("### 用户近况")
                appendLine("- ${it.content}")
            }
            if (milestones.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("### 里程碑（用户提过的重要日子）")
                milestones.forEach { appendLine("- ${it.content}") }
            }
            if (related.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("### 相关记忆")
                related.forEach { (name, obs) -> appendLine("- [$name] $obs") }
            }
        }.trim().takeIf { it.isNotEmpty() }?.take(MAX_RECALL_CHARS)
    }.getOrElse { e ->
        AppLogger.instance.warn(LogCategory.CS_MEM, "ChatMemoryPipeline", "召回失败(忽略): ${e.message}")
        null
    }

    // ── 沉淀面（引擎 execute finally 调用）────────────────────────

    /**
     * 一轮对话结束：启发式捕获（同步，零成本）+ 入队待蒸馏窗口；
     * 攒批到点（队列 ≥ [DISTILL_BATCH_TURNS]）或节拍到点（每
     * [DISTILL_FLUSH_EVERY_N_TURNS] 轮且队列非空）触发批量蒸馏（异步）。
     */
    suspend fun onTurn(userText: String, assistantText: String) {
        runCatching { captureHeuristic(userText) }
            .onFailure { e ->
                AppLogger.instance.warn(LogCategory.CS_MEM, "ChatMemoryPipeline", "启发式捕获失败(忽略): ${e.message}")
            }
        runCatching { updateTone(userText) }
            .onFailure { e ->
                AppLogger.instance.warn(LogCategory.CS_MEM, "ChatMemoryPipeline", "情绪基调更新失败(忽略): ${e.message}")
            }
        runCatching { captureMilestones(userText) }
            .onFailure { e ->
                AppLogger.instance.warn(LogCategory.CS_MEM, "ChatMemoryPipeline", "里程碑捕获失败(忽略): ${e.message}")
            }

        // 入队 + 触发判定（队列持久化：进程被杀不丢轮次）
        runCatching { ledger.enqueueTurn(userText, assistantText, System.currentTimeMillis()) }
        val n = turnCounter.incrementAndGet()
        val pending = ledger.pendingTurnCount()
        if (pending >= DISTILL_BATCH_TURNS ||
            (n % DISTILL_FLUSH_EVERY_N_TURNS == 0 && pending >= MIN_DISTILL_TURNS)
        ) {
            // fire-and-forget：不可变窗口快照入协程，主对话不等待蒸馏
            scope.launch { distillBatch() }
        }
    }

    // ── 启发式捕获（零成本层）─────────────────────────────────────

    /**
     * 自我披露句式匹配：含「我叫/我喜欢/我在做…」等标记词且含「我」的句子
     * 整句入账（≤ 60 字，规范化去重 + 重复即重要性抬升由账本保证）。
     * 每轮至多 [MAX_HEURISTIC_PER_TURN] 条，防止长篇独白一次灌满画像。
     */
    private fun captureHeuristic(userText: String) {
        val sentences = userText.split(Regex("[，。！？；\\n,.!?;]+"))
            .map { it.trim() }
            .filter { it.length in 2..60 && '我' in it }
        val captured = mutableListOf<String>()
        for (sentence in sentences) {
            if (captured.size >= MAX_HEURISTIC_PER_TURN) break
            if (SELF_DISCLOSURE_MARKERS.any { sentence.contains(it) }) {
                captured.add(sentence)
            }
        }
        if (captured.isEmpty()) return
        val now = System.currentTimeMillis()
        var added = 0
        var bumped = 0
        for (sentence in captured) {
            val result = ledger.add(
                content = sentence,
                kind = MemoryKind.PROFILE,
                source = MemorySource.HEURISTIC,
                importance = HEURISTIC_IMPORTANCE,
                nowMs = now
            )
            if (result.added) added++ else if (result.bumped) bumped++
        }
        if (added > 0 || bumped > 0) {
            AppLogger.instance.info(
                LogCategory.CS_MEM, "ChatMemoryPipeline",
                "启发式捕获：新增 $added 条，强化 $bumped 条"
            )
        }
    }

    // ── 情绪基调滚动窗（用户近况，单条有界替换）─────────────────

    /**
     * 每轮情绪判定（与引擎注入 Emotional Attunement 同一口径）→ 滚动窗
     * → 基调汇总单条替换「用户近况」。仅情绪轮计入窗口（NONE 不冲淡）。
     */
    private fun updateTone(userText: String) {
        val signal = ChatSignalDetector.detect(userText)
        if (signal.emotion == ChatEmotion.NONE) return
        val summary = synchronized(toneWindow) {
            toneWindow.addLast(signal.emotion)
            while (toneWindow.size > TONE_WINDOW) toneWindow.removeFirst()
            summarizeToneLocked()
        }
        runCatching {
            ledger.replaceState(summary, System.currentTimeMillis())
        }.onFailure { e ->
            AppLogger.instance.warn(LogCategory.CS_MEM, "ChatMemoryPipeline", "基调落账失败(忽略): ${e.message}")
        }
    }

    /** 窗口汇总：主导情绪 + 计数；平票负向优先（SAD 序最低，thenByDescending 使其胜出）。 */
    private fun summarizeToneLocked(): String {
        val counts = toneWindow.groupingBy { it }.eachCount()
        val (dominant, hits) = counts.entries
            .maxWith(compareBy<Map.Entry<ChatEmotion, Int>> { it.value }.thenByDescending { it.key.ordinal })
        val label = TONE_LABELS[dominant] ?: dominant.name
        return "近期情绪基调:偏${label}（近期记录中${hits}轮${label}，留意用户状态、先关心人再办事）"
    }

    // ── 里程碑日历（日期 × 人生事件双命中）───────────────────────

    /**
     * 句子级「日期模式 × 人生事件标记」双命中即整句入账（≤2 句/轮，重复
     * 即重要性抬升由账本保证）。创作型指令（帮我写/文案…）中的日子是素材
     * 不是用户的日子，命中护栏直接跳过该句。
     */
    private fun captureMilestones(userText: String) {
        val hits = userText.split(Regex("[，。！？；\\n,.!?;]+"))
            .map { it.trim() }
            .filter { it.length in 4..60 }
            .filterNot { s -> MILESTONE_GUARDS.any { s.contains(it) } }
            .filter { s -> DATE_PATTERN.containsMatchIn(s) && EVENT_MARKERS.any { s.contains(it) } }
            .take(MAX_MILESTONE_PER_TURN)
        if (hits.isEmpty()) return
        val now = System.currentTimeMillis()
        var added = 0
        for (sentence in hits) {
            val result = ledger.add(
                content = sentence,
                kind = MemoryKind.MILESTONE,
                source = MemorySource.HEURISTIC,
                importance = MILESTONE_IMPORTANCE,
                nowMs = now
            )
            if (result.added) added++
        }
        if (added > 0) {
            AppLogger.instance.info(
                LogCategory.CS_MEM, "ChatMemoryPipeline",
                "里程碑捕获 $added 条"
            )
        }
    }

    // ── LLM 批量蒸馏（低频异步层）────────────────────────────────

    /**
     * 批量蒸馏一整个待处理窗口：账本 Top 事实作参照 → 写入门禁提取 →
     * new 入账 / update 替换 → 清空队列 → 巩固 pass（合并 + 淘汰）。
     * 蒸馏失败（null）时队列保留，下次触发并入更大窗口重试。
     */
    private suspend fun distillBatch() {
        runCatching {
            val now = System.currentTimeMillis()
            val window = ledger.peekPendingTurns()
            if (window.isEmpty()) return
            val existing = ledger.topByScore(MemoryKind.PROFILE, MAX_EXISTING_FACTS, now)
                .map { it.content }
            val outcome = distiller.distill(window, existing) ?: run {
                AppLogger.instance.warn(
                    LogCategory.CS_MEM, "ChatMemoryPipeline",
                    "批量蒸馏失败(队列保留，下次重试): ${window.size} 轮"
                )
                return
            }
            if (outcome.skipped) {
                ledger.clearPendingTurns()
                return
            }
            var added = 0
            var updated = 0
            for (fact in outcome.newFacts) {
                if (ledger.add(
                        content = fact,
                        kind = MemoryKind.PROFILE,
                        source = MemorySource.DISTILL,
                        importance = DISTILL_IMPORTANCE,
                        nowMs = now
                    ).added
                ) added++
            }
            for (update in outcome.updates) {
                if (ledger.applyUpdate(update.oldContent, update.newContent, now)) {
                    updated++
                } else if (ledger.add(
                        content = update.newContent,
                        kind = MemoryKind.PROFILE,
                        source = MemorySource.DISTILL,
                        importance = DISTILL_IMPORTANCE,
                        nowMs = now
                    ).added
                ) {
                    // old 未命中（模型给了不存在的原文）：按新增兜底，信息不丢
                    added++
                }
            }
            ledger.clearPendingTurns()
            val consolidated = ledger.consolidate(now)
            AppLogger.instance.info(
                LogCategory.CS_MEM, "ChatMemoryPipeline",
                "批量蒸馏 ${window.size} 轮：新增 $added / 更新 $updated / " +
                    "合并 ${consolidated.mergedPairs} / 淘汰 ${consolidated.evicted}"
            )
        }.onFailure { e ->
            AppLogger.instance.warn(LogCategory.CS_MEM, "ChatMemoryPipeline", "批量蒸馏异常(忽略): ${e.message}")
        }
    }

    // ── 检索辅助 ──────────────────────────────────────────────

    /** 消息关键词提取（评分检索与图谱检索共用的切分口径）。 */
    private fun extractKeywords(userText: String): List<String> {
        if (userText.isBlank()) return emptyList()
        return userText.split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim().lowercase() }
            .filter { it.length in 2..8 }
            .distinct()
            .take(8)
    }

    /** 关键词直接命中的画像记忆（补充注入，保证话题相关记忆不被评分挤出）。 */
    private fun keywordHits(userText: String, now: Long): List<MemoryRecord> {
        val keywords = extractKeywords(userText)
        if (keywords.isEmpty()) return emptyList()
        return runCatching {
            ledger.search(keywords, setOf(MemoryKind.PROFILE), MAX_KEYWORD_HITS, now)
        }.getOrDefault(emptyList())
    }

    /**
     * 按用户消息关键词检索图谱主题实体（模型经 memory MCP 显式建的
     * project / person / concept 实体，v2 起与自动记忆分库）。
     * 关键词 = 分词后长度 ≥ 2 的 CJK/拉丁段；每段一次 searchNodes
     * （内存包含匹配，微秒级）；命中实体取观察最多的前
     * [MAX_RELATED_ENTITIES] 个，每实体取最近 [MAX_OBS_PER_ENTITY] 条观察。
     */
    private fun relatedEntities(userText: String): List<Pair<String, String>> {
        if (userText.isBlank()) return emptyList()
        return runCatching {
            val keywords = extractKeywords(userText)
            if (keywords.isEmpty()) return emptyList()

            val hitCounts = LinkedHashMap<String, Int>()
            for (kw in keywords) {
                graph.searchNodes(kw).entities
                    .filter { it.observations.isNotEmpty() }
                    .forEach { entity ->
                        hitCounts[entity.name] = (hitCounts[entity.name] ?: 0) + 1
                    }
            }
            val all = graph.readGraph().entities.associateBy { it.name }
            hitCounts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(MAX_RELATED_ENTITIES)
                .mapNotNull { (name, _) ->
                    val entity = all[name] ?: return@mapNotNull null
                    entity.observations.takeLast(MAX_OBS_PER_ENTITY).map { obs -> name to obs }
                }
                .flatten()
                .take(MAX_RELATED_ITEMS)
        }.getOrDefault(emptyList())
    }

    private companion object {
        /**
         * 三类固定实体契约沿用 [ChatMemorySchema]（v2 起该对象同时是账本
         * 类别 ↔ 旧图谱 entityType 的迁移映射源）。
         */
        /** 自我披露标记词（句级匹配，含其一且含「我」即整句入账）。 */
        val SELF_DISCLOSURE_MARKERS = listOf(
            "我叫", "我的名字", "我是", "我在", "我喜欢", "我爱", "我偏好", "我最爱",
            "我讨厌", "我不喜欢", "我烦", "我住", "我的工作", "我从事", "我在学",
            "我正在", "我打算", "我要考", "我今年", "我用", "我的手机", "我们团队", "我家里"
        )

        /** 情绪基调标签（检测器枚举 → 用户可读中文）。 */
        val TONE_LABELS = mapOf(
            ChatEmotion.SAD to "低落", ChatEmotion.ANXIOUS to "焦虑",
            ChatEmotion.ANGRY to "烦躁易怒", ChatEmotion.JOYFUL to "欢快"
        )

        /** 日期模式：具体日期/星期/相对日/节日（与事件标记双命中才算里程碑）。 */
        val DATE_PATTERN = Regex(
            "\\d{1,2}月\\d{1,2}[日号]|周[一二三四五六日天]|明天|后天|下周|" +
                "这周末|周末|月底|年底|元旦|春节|除夕|元宵|清明|端午|中秋|" +
                "国庆|五一|情人节|母亲节|父亲节|高考|考研"
        )

        /** 人生事件标记（与日期模式双命中）。 */
        val EVENT_MARKERS = listOf(
            "生日", "纪念日", "领证", "结婚", "婚礼", "订婚", "求婚", "相亲",
            "面试", "笔试", "考试", "考研", "考公", "开学", "毕业", "入职",
            "离职", "搬家", "体检", "手术", "复诊", "答辩", "开题", "交房",
            "提车", "摇号", "出发", "返程", "预产期", "放榜", "出成绩"
        )

        /** 创作护栏：这些句式里的日子是素材不是用户的日子。 */
        val MILESTONE_GUARDS = listOf(
            "帮我写", "写一篇", "写个", "写一段", "文案", "帮我翻译", "翻译成",
            "生成", "润色", "仿写", "续写", "例句", "示例", "假设"
        )

        const val TONE_WINDOW = 6
        const val MAX_MILESTONE_PER_TURN = 2
        const val MAX_MILESTONE_RECALL = 5
        const val MAX_HEURISTIC_PER_TURN = 3
        const val MAX_PROFILE_ITEMS = 10
        const val MAX_RELATED_ENTITIES = 3
        const val MAX_OBS_PER_ENTITY = 3
        const val MAX_RELATED_ITEMS = 6
        const val MAX_RECALL_CHARS = 900

        /** 关键词直接命中补充注入的画像记忆条数上限。 */
        const val MAX_KEYWORD_HITS = 4

        /** 蒸馏触发：队列攒到 6 轮即开蒸；或每 8 轮节拍兜底冲刷（≥2 轮才值得一次调用）。 */
        const val DISTILL_BATCH_TURNS = 6
        const val DISTILL_FLUSH_EVERY_N_TURNS = 8
        const val MIN_DISTILL_TURNS = 2

        /** 重要性基线：启发式画像 / 里程碑 / 蒸馏产物。 */
        const val HEURISTIC_IMPORTANCE = 0.5f
        const val MILESTONE_IMPORTANCE = 0.7f
        const val DISTILL_IMPORTANCE = 0.6f
        const val MAX_EXISTING_FACTS = 12
    }
}
