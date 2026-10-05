package com.apex.agent.mcp.builtin.memory

import com.apex.agent.core.engine.ChatEmotion
import com.apex.agent.core.engine.ChatSignalDetector
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 聊天记忆管线 —— 对话内容自动沉淀为长期记忆（学 operit 图记忆 + 三点自主创新）。
 *
 * ## 与 operit / Mem0 的差异（创新点）
 *
 * 1. **双速捕获**：启发式层零成本同步落盘（自我披露句式匹配，当轮生效），
 *    LLM 蒸馏层低频异步后台跑（每 [DISTILL_EVERY_N_TURNS] 轮一次，SUMMARY
 *    角色路由到便宜模型）——operit 全靠 LLM 工具调用显式写图，成本高且易漏；
 * 2. **主动召回**：每轮 execute 入口自动检索并注入系统提示词
 *    （"## Remembered About You"），模型无需先调 memory 工具才知道用户是谁；
 * 3. **存储共生**：与 memory MCP 共享同一个 [KnowledgeGraphStore] 实例
 *    （`<filesDir>/mcp_memory/memory.json`）——自动记忆与模型显式写的记忆
 *    同图同源，用户经 memory 工具 / mcp_call 全部可见、可删、可改；
 * 4. **情绪纵览（R2）**：每轮用 [ChatSignalDetector] 与引擎同一套口径判定
 *    情绪，滚动窗口（近 [TONE_WINDOW] 轮）汇总为「用户近况」实体的单条
 *    基调观察（delete+recreate 有界替换，永不膨胀）——下次对话开头模型
 *    就知道用户近来的情绪基调，开口不再「没记性」；
 * 5. **里程碑日历（R2）**：句子级「日期模式 × 人生事件标记」双命中即沉淀
 *    为「用户里程碑」观察（下周三面试/10月2日领证/我妈生日…），召回时
 *    作为独立段注入——Agent 能在日子临近时自然提起，这是 operit/Mem0
 *    都没有的「时间感知」能力。
 *
 * ## 数据面
 *
 * - 稳定用户事实（我叫/我喜欢/我在做…）→ 「用户画像」实体的 observations
 *   （addObservations 内容级去重，天然幂等）；
 * - LLM 蒸馏产物同样进画像实体（第三人称、≤ 40 字/条）；
 * - 模型自己经 mcp__memory__create_entities 建的主题实体与本管线共生，
 *    召回时按关键词检索一并注入。
 *
 * ## 失败语义（防御式 IO 纪律）
 *
 * 所有公开方法绝不向上抛：recall 失败返回 null（省略注入段），
 * onTurn 失败只记日志——记忆子系统的问题绝不阻断主对话。
 *
 * ## 线程模型
 *
 * - recall / onTurn 的启发式路径在调用方上下文执行（纯内存图操作 + 偶发
 *   落盘，毫秒级）；LLM 蒸馏转 [scope]（SupervisorJob + IO）fire-and-forget，
 *   捕获不可变快照，失败不传染；
 * - [KnowledgeGraphStore] 内部单锁串行化，无需外再加锁。
 */
@Singleton
class ChatMemoryPipeline @Inject constructor(
    private val store: KnowledgeGraphStore,
    private val modelRuntime: ModelRuntime
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 蒸馏节拍计数器（进程级；重启归零无害——只是少蒸一次）。 */
    private val turnCounter = AtomicInteger(0)

    /** 情绪基调滚动窗（近 [TONE_WINDOW] 个情绪轮；进程级，重启后由新一轮重建）。 */
    private val toneWindow = ArrayDeque<ChatEmotion>()

    init {
        // 幂等：画像/近况/里程碑实体不存在则建（已存在时 createEntities 合并语义零副作用）
        runCatching {
            store.createEntities(
                listOf(
                    GraphEntity(name = PROFILE_ENTITY, entityType = PROFILE_TYPE),
                    GraphEntity(name = STATE_ENTITY, entityType = STATE_TYPE),
                    GraphEntity(name = MILESTONE_ENTITY, entityType = MILESTONE_TYPE)
                )
            )
        }
    }

    // ── 召回面（引擎 execute 入口调用）─────────────────────────────

    /**
     * 检索与当前用户消息相关的长期记忆，格式化为注入段内容。
     *
     * 组成：画像实体最近 [MAX_PROFILE_ITEMS] 条观察（稳定事实，恒注入）+
     * 「用户近况」情绪基调（R2）+「用户里程碑」最近几条（R2）+ 按消息
     * 关键词检索命中的主题实体观察。总长钳制 [MAX_RECALL_CHARS]；
     * 无可召回内容返回 null。
     */
    suspend fun recall(userText: String): String? = runCatching {
        val profile = profileObservations()
        val tone = stateObservation()
        val milestones = milestoneObservations().takeLast(MAX_MILESTONE_RECALL)
        val related = relatedEntities(userText)
        if (profile.isEmpty() && tone == null && milestones.isEmpty() && related.isEmpty()) return null

        buildString {
            if (profile.isNotEmpty()) {
                appendLine("### 关于用户")
                profile.forEach { appendLine("- $it") }
            }
            tone?.let {
                if (isNotEmpty()) appendLine()
                appendLine("### 用户近况")
                appendLine("- $it")
            }
            if (milestones.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("### 里程碑（用户提过的重要日子）")
                milestones.forEach { appendLine("- $it") }
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
     * 一轮对话结束：启发式捕获（同步，零成本）+ 节拍到点触发 LLM 蒸馏（异步）。
     */
    suspend fun onTurn(userText: String, assistantText: String) {
        runCatching { captureHeuristic(userText) }
            .onFailure { e ->
                AppLogger.instance.warn(LogCategory.CS_MEM, "ChatMemoryPipeline", "启发式捕获失败(忽略): ${e.message}")
            }
        // R2：情绪基调滚动窗 + 里程碑日历（同为防御式，失败只记日志）
        runCatching { updateTone(userText) }
            .onFailure { e ->
                AppLogger.instance.warn(LogCategory.CS_MEM, "ChatMemoryPipeline", "情绪基调更新失败(忽略): ${e.message}")
            }
        runCatching { captureMilestones(userText) }
            .onFailure { e ->
                AppLogger.instance.warn(LogCategory.CS_MEM, "ChatMemoryPipeline", "里程碑捕获失败(忽略): ${e.message}")
            }
        val n = turnCounter.incrementAndGet()
        if (n % DISTILL_EVERY_N_TURNS == 0) {
            // fire-and-forget：不可变快照入协程，主对话不等待蒸馏
            val userSnapshot = userText.take(1200)
            val assistantSnapshot = assistantText.take(800)
            scope.launch { distill(userSnapshot, assistantSnapshot) }
        }
    }

    // ── 启发式捕获（零成本层）─────────────────────────────────────

    /**
     * 自我披露句式匹配：含「我叫/我喜欢/我在做…」等标记词且含「我」的句子
     * 整句入库（≤ 60 字，去重由 store 保证）。每轮至多 [MAX_HEURISTIC_PER_TURN] 条，
     * 防止长篇独白一次灌满画像。
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
        if (captured.isNotEmpty()) {
            val added = store.addObservations(PROFILE_ENTITY, captured)
            if (added > 0) {
                AppLogger.instance.info(
                    LogCategory.CS_MEM, "ChatMemoryPipeline",
                    "启发式捕获 $added 条用户事实入画像"
                )
            }
        }
    }

    // ── R2：情绪基调滚动窗（用户近况实体，单条有界替换）─────────

    /**
     * 每轮情绪判定（与引擎注入 Emotional Attunement 同一口径）→ 滚动窗
     * → 基调汇总单条写入「用户近况」。仅情绪轮计入窗口（NONE 不冲淡）；
     * delete+recreate+add 三步实现「替换」语义，实体永远只持一条观察。
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
            store.deleteEntities(listOf(STATE_ENTITY))
            store.createEntities(listOf(GraphEntity(name = STATE_ENTITY, entityType = STATE_TYPE)))
            store.addObservations(STATE_ENTITY, listOf(summary))
        }.onFailure { e ->
            AppLogger.instance.warn(LogCategory.CS_MEM, "ChatMemoryPipeline", "基调落盘失败(忽略): ${e.message}")
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

    // ── R2：里程碑日历（用户里程碑实体）────────────────────────

    /**
     * 句子级「日期模式 × 人生事件标记」双命中即整句入库（≤2 句/轮，内容
     * 级去重由 store 保证）。创作型指令（帮我写/文案…）中的日子是素材
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
        val added = store.addObservations(MILESTONE_ENTITY, hits)
        if (added > 0) {
            AppLogger.instance.info(
                LogCategory.CS_MEM, "ChatMemoryPipeline",
                "里程碑捕获 $added 条"
            )
            compactMilestonesIfNeeded()
        }
    }

    /** 超限压缩：只保留最近 [MAX_MILESTONE_ITEMS] 条（delete+recreate）。 */
    private fun compactMilestonesIfNeeded() {
        val all = milestoneObservations()
        if (all.size <= MAX_MILESTONE_ITEMS) return
        runCatching {
            store.deleteEntities(listOf(MILESTONE_ENTITY))
            store.createEntities(listOf(GraphEntity(name = MILESTONE_ENTITY, entityType = MILESTONE_TYPE)))
            store.addObservations(MILESTONE_ENTITY, all.takeLast(MAX_MILESTONE_ITEMS))
        }
    }

    // ── LLM 蒸馏（低频异步层，Mem0 式提取）───────────────────────

    /**
     * 把一轮对话蒸馏为 ≤ 5 条第三人称用户事实。SUMMARY 角色路由（通常是
     * 便宜模型），低温；输出防御式解析（截取首个 [ 到最后一个 ]），任何
     * 形状异常都吞掉——蒸馏失败只是少记一次，绝无副作用。
     */
    private suspend fun distill(userText: String, assistantText: String) {
        runCatching {
            val prompt = buildString {
                appendLine("从下面这轮对话中提取「值得长期记住的用户事实」。")
                appendLine("只提取用户明确表达的稳定信息（身份、职业、偏好、目标、约束、常用工具）；")
                appendLine("忽略一次性任务细节、寒暄、以及助手说了什么。没有值得记的输出 []。")
                appendLine("输出格式：JSON 字符串数组，每条 ≤ 40 字，第三人称（以「用户」开头），最多 5 条。")
                appendLine("只输出 JSON 数组本身，不要任何其他文字或代码块标记。")
                appendLine()
                appendLine("用户：$userText")
                appendLine("助手：$assistantText")
            }
            val response: LlmResponse = modelRuntime.chat(
                context = LlmRequestContext.summary("chat_memory_distill"),
                messages = listOf(LlmMessage.System("你是严格的对话事实提取器。"), LlmMessage.User(prompt)),
                temperature = 0.1f,
                maxTokens = 500
            )
            val facts = parseFacts(response.content.orEmpty())
            if (facts.isNotEmpty()) {
                val added = store.addObservations(PROFILE_ENTITY, facts)
                if (added > 0) {
                    AppLogger.instance.info(
                        LogCategory.CS_MEM, "ChatMemoryPipeline",
                        "LLM 蒸馏新增 $added 条用户事实"
                    )
                }
            }
        }.onFailure { e ->
            AppLogger.instance.warn(LogCategory.CS_MEM, "ChatMemoryPipeline", "蒸馏失败(忽略): ${e.message}")
        }
    }

    /** 防御式 JSON 数组解析：截取方括号区段，逐条过滤长度与形状。 */
    private fun parseFacts(raw: String): List<String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw.substring(start, end + 1)).jsonArray
                .mapNotNull { el ->
                    runCatching { el.jsonPrimitive.content }.getOrNull()
                        ?.trim()
                        ?.takeIf { it.length in 2..60 }
                }
                .distinct()
                .take(5)
        }.getOrDefault(emptyList())
    }

    // ── 检索辅助 ──────────────────────────────────────────────────

    /** 画像实体最近 N 条观察（插入序即时间序，取尾部）。 */
    private fun profileObservations(): List<String> {
        return runCatching {
            store.readGraph().entities
                .firstOrNull { it.name == PROFILE_ENTITY }
                ?.observations
                ?.takeLast(MAX_PROFILE_ITEMS)
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 「用户近况」唯一基调观察；实体缺失/空观察返回 null。 */
    private fun stateObservation(): String? {
        return runCatching {
            store.readGraph().entities
                .firstOrNull { it.name == STATE_ENTITY }
                ?.observations
                ?.lastOrNull()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 「用户里程碑」全部观察（插入序）。 */
    private fun milestoneObservations(): List<String> {
        return runCatching {
            store.readGraph().entities
                .firstOrNull { it.name == MILESTONE_ENTITY }
                ?.observations
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * 按用户消息关键词检索主题实体（排除画像本体，画像恒注入无需重复）。
     * 关键词 = 分词后长度 ≥ 2 的 CJK/拉丁段；每段一次 searchNodes（内存
     * 包含匹配，微秒级）；命中实体取观察最多的前 [MAX_RELATED_ENTITIES] 个，
     * 每实体取最近 [MAX_OBS_PER_ENTITY] 条观察。
     */
    private fun relatedEntities(userText: String): List<Pair<String, String>> {
        if (userText.isBlank()) return emptyList()
        return runCatching {
            val keywords = userText.split(Regex("[^\\p{L}\\p{N}]+"))
                .map { it.trim() }
                .filter { it.length in 2..8 }
                .distinct()
                .take(8)
            if (keywords.isEmpty()) return emptyList()

            val hitCounts = LinkedHashMap<String, Int>()
            for (kw in keywords) {
                store.searchNodes(kw).entities
                    .filter { it.name != PROFILE_ENTITY && it.observations.isNotEmpty() }
                    .forEach { entity ->
                        hitCounts[entity.name] = (hitCounts[entity.name] ?: 0) + 1
                    }
            }
            val all = store.readGraph().entities.associateBy { it.name }
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
        // #219：三类固定实体的命名契约上移 ChatMemorySchema（记忆页「聊天记忆」
        // 分区与本管线共用同一份常量，编译期防字符串漂移）；此处保留短名引用，
        // 管线逻辑零变化。
        /** 画像实体名（与 memory MCP 图共用命名空间，用户可见可管理）。 */
        const val PROFILE_ENTITY = ChatMemorySchema.PROFILE_ENTITY
        const val PROFILE_TYPE = ChatMemorySchema.PROFILE_TYPE

        /** R2：「用户近况」（情绪基调，单条替换）与「用户里程碑」（日期事件）。 */
        const val STATE_ENTITY = ChatMemorySchema.STATE_ENTITY
        const val STATE_TYPE = ChatMemorySchema.STATE_TYPE
        const val MILESTONE_ENTITY = ChatMemorySchema.MILESTONE_ENTITY
        const val MILESTONE_TYPE = ChatMemorySchema.MILESTONE_TYPE

        /** 自我披露标记词（句级匹配，含其一且含「我」即整句入库）。 */
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
        const val MAX_MILESTONE_ITEMS = 30
        const val MAX_MILESTONE_RECALL = 5
        const val DISTILL_EVERY_N_TURNS = 4
        const val MAX_HEURISTIC_PER_TURN = 3
        const val MAX_PROFILE_ITEMS = 10
        const val MAX_RELATED_ENTITIES = 3
        const val MAX_OBS_PER_ENTITY = 3
        const val MAX_RELATED_ITEMS = 6
        const val MAX_RECALL_CHARS = 900
    }
}
