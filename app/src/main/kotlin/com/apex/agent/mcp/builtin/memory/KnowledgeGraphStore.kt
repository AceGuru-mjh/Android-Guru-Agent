package com.apex.agent.mcp.builtin.memory

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 知识图谱节点（对齐官方 server-memory 的 Entity）。
 *
 * [name] 是图内唯一主键；[entityType] 是松散分类（如 project / person /
 * concept）；[observations] 是挂在该节点上的观察事实列表（追加式、去重）。
 */
@Serializable
data class GraphEntity(
    val name: String,
    val entityType: String = "",
    val observations: List<String> = emptyList()
)

/**
 * 知识图谱有向边（对齐官方 server-memory 的 Relation）。
 *
 * 三元组 [from] → [relationType] → [to]，端点用 [GraphEntity.name] 引用，
 * 重复三元组在 [KnowledgeGraphStore] 里按整体去重。
 */
@Serializable
data class GraphRelation(
    val from: String,
    val to: String,
    val relationType: String
)

/** 图快照（read/search 的返回值，脱离内部可变结构，调用方可放心持有）。 */
data class KnowledgeGraph(
    val entities: List<GraphEntity>,
    val relations: List<GraphRelation>
)

/** 落盘形态：单文件 JSON 的顶层结构。 */
@Serializable
private data class GraphState(
    val entities: List<GraphEntity> = emptyList(),
    val relations: List<GraphRelation> = emptyList()
)

/**
 * # Memory MCP 的核心存储 —— 知识图谱（纯 Kotlin，零 Android 依赖）
 *
 * 语义对齐官方 @modelcontextprotocol/server-memory，但按任务书 #150 做了
 * 两处显式决策：
 *
 * 1. **create_entities 幂等合并**：同名实体已存在时合并 observations（保序
 *    去重并集，entityType 保留旧值），而非官方的"整条丢弃"—— 跨会话沉淀
 *    场景里模型重复创建同名实体是常态，合并比丢弃更符合"记忆只增不减"的
 *    直觉。
 * 2. **每次变更同步落盘**（官方是内存态 + 显式 save）：Android 进程随时可能
 *    被杀，"记得住"比"写得快"重要；写入用 tmp+rename 原子写，进程崩溃最多
 *    丢最后一次变更，不会留下半截 JSON。
 *
 * 持久化位置由构造参数 [storageDir] 注入（App 侧约定
 * `<filesDir>/mcp_memory/`），文件名固定 memory.json。损坏兜底：解析失败时
 * 原文留档为 memory.json.corrupt 后空图启动 —— 宁可丢图不可卡死 MCP 通道。
 *
 * 线程安全：实例内单一 [lock] 串行化全部读写（含落盘），锁粒度足够粗，
 * 因为调用方只有 McpClient 的 tools/call（Dispatchers.IO），无高并发诉求。
 *
 * 为什么独立成类而不是塞进 Transport：协议适配（JSON-RPC 派发）与领域逻辑
 * （图操作 + 持久化）分开后，后者可以做纯 JVM 单测（见
 * app/src/test 下的 KnowledgeGraphStoreTest），Transport 薄委托即可。
 */
class KnowledgeGraphStore(private val storageDir: File) {

    /** create_entities 的结果：新建的主键 + 发生观察合并的既有主键。 */
    data class EntitiesOutcome(val created: List<String>, val merged: List<String>)

    /** create_relations 的结果：新增条数 + 因重复被跳过的条数。 */
    data class RelationsOutcome(val added: Int, val duplicates: Int)

    /** delete_entities 的结果：删除个数 + 未找到的名字。 */
    data class DeletionOutcome(val deleted: Int, val missing: List<String>)

    private val lock = Any()

    /** encodeDefaults 让空 observations 也落盘 —— 与官方 JSON 形态对齐。 */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 实体表：name → 实体（LinkedHashMap 保持插入序，read_graph 输出稳定）。 */
    private val entities = LinkedHashMap<String, GraphEntity>()

    /** 关系表：允许重复入参、存储侧整体去重。 */
    private val relations = ArrayList<GraphRelation>()

    init {
        loadFromDisk()
    }

    // ── 图操作（全部锁内执行，变更后同步落盘） ─────────────────────

    /**
     * 批量创建实体（幂等）：新主键直接插入；已存在的主键合并 observations
     * （旧观察在前、新观察按序追加，重复项跳过）。
     */
    fun createEntities(newEntities: List<GraphEntity>): EntitiesOutcome = synchronized(lock) {
        val created = ArrayList<String>()
        val merged = ArrayList<String>()
        for (entity in newEntities) {
            val existing = entities[entity.name]
            if (existing == null) {
                entities[entity.name] = entity
                created.add(entity.name)
            } else {
                if (entity.observations.isEmpty()) continue // 空观察的重复创建：无变化
                val union = ArrayList(existing.observations)
                entity.observations.forEach { obs -> if (obs !in union) union.add(obs) }
                entities[entity.name] = existing.copy(observations = union)
                merged.add(entity.name)
            }
        }
        if (created.isNotEmpty() || merged.isNotEmpty()) save()
        EntitiesOutcome(created, merged)
    }

    /** 批量创建关系：三元组整体重复的跳过（同 create 语义的幂等去重）。 */
    fun createRelations(newRelations: List<GraphRelation>): RelationsOutcome = synchronized(lock) {
        var added = 0
        var duplicates = 0
        for (relation in newRelations) {
            val dup = relations.any {
                it.from == relation.from && it.to == relation.to &&
                    it.relationType == relation.relationType
            }
            if (dup) {
                duplicates++
            } else {
                relations.add(relation)
                added++
            }
        }
        if (added > 0) save()
        RelationsOutcome(added, duplicates)
    }

    /**
     * 向实体追加观察（去重）。
     *
     * @throws IllegalArgumentException 实体不存在（对齐官方 server-memory 的报错语义）
     * @return 实际新增条数（内容为空或全部已存在时为 0，此时不落盘）
     */
    fun addObservations(entityName: String, contents: List<String>): Int = synchronized(lock) {
        val existing = entities[entityName]
            ?: throw IllegalArgumentException("实体不存在: $entityName")
        val union = ArrayList(existing.observations)
        var appended = 0
        for (obs in contents) {
            if (obs.isNotBlank() && obs !in union) {
                union.add(obs)
                appended++
            }
        }
        if (appended > 0) {
            entities[entityName] = existing.copy(observations = union)
            save()
        }
        appended
    }

    /** 读全图（快照）。 */
    fun readGraph(): KnowledgeGraph = synchronized(lock) {
        KnowledgeGraph(entities.values.toList(), relations.toList())
    }

    /**
     * 按关键词检索：name / entityType / 任一 observation 大小写不敏感包含匹配；
     * 关系只保留**两端都命中**的边（对齐官方 searchNodes 的过滤规则）。
     * 空 query 视为命中全图（包含匹配对空串恒真的数学一致性）。
     */
    fun searchNodes(query: String): KnowledgeGraph = synchronized(lock) {
        val needle = query.trim().lowercase()
        val matched = entities.values.filter { entity ->
            needle.isEmpty() ||
                entity.name.lowercase().contains(needle) ||
                entity.entityType.lowercase().contains(needle) ||
                entity.observations.any { it.lowercase().contains(needle) }
        }
        val names = matched.map { it.name }.toHashSet()
        val matchedRelations = relations.filter { it.from in names && it.to in names }
        KnowledgeGraph(matched, matchedRelations)
    }

    /**
     * 批量删除实体：任一端点被删的关系**级联删除**（官方语义），未找到的
     * 名字收集进 outcome.missing 供上层提示。
     */
    fun deleteEntities(entityNames: List<String>): DeletionOutcome = synchronized(lock) {
        val deleted = ArrayList<String>()
        val missing = ArrayList<String>()
        for (name in entityNames) {
            if (entities.remove(name) != null) deleted.add(name) else missing.add(name)
        }
        if (deleted.isNotEmpty()) {
            val gone = deleted.toHashSet()
            relations.removeAll { it.from in gone || it.to in gone }
            save()
        }
        DeletionOutcome(deleted.size, missing)
    }

    // ── 观测口径（测试与上层摘要用） ───────────────────────────────

    fun entityCount(): Int = synchronized(lock) { entities.size }

    fun relationCount(): Int = synchronized(lock) { relations.size }

    // ── 持久化 ─────────────────────────────────────────────────────

    /** 磁盘文件路径（storageDir 由构造方注入）。 */
    private fun storageFile(): File = File(storageDir, FILE_NAME)

    /** 启动加载：文件缺失 = 全新图谱；解析失败 = 留档 + 空图启动。 */
    private fun loadFromDisk() {
        val file = storageFile()
        if (!file.isFile) return
        try {
            val state = json.decodeFromString(GraphState.serializer(), file.readText())
            state.entities.forEach { entities[it.name] = it }
            relations.addAll(state.relations)
        } catch (e: Exception) {
            // 损坏兜底：原文复制为 .corrupt 留档（供人工抢救），随后空图启动；
            // 下一次成功 save 会覆盖坏的主文件
            runCatching { file.copyTo(File(storageDir, "$FILE_NAME.corrupt"), overwrite = true) }
        }
    }

    /** 落盘（调用方已持 [lock]，这里不再加锁避免重入死锁）。 */
    private fun save() {
        storageDir.mkdirs()
        val text = json.encodeToString(
            GraphState.serializer(),
            GraphState(entities.values.toList(), relations.toList())
        )
        atomicWrite(storageFile(), text)
    }

    /**
     * 原子写：先写同目录 .tmp 再 rename —— 同一文件系统内 rename(2) 对
     * 观察者原子，进程被杀时要么旧文件完整、要么新文件完整，不存在半截 JSON。
     * rename 失败（目标被占用等罕见场景）退化为复制覆盖。
     */
    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val FILE_NAME = "memory.json"
    }
}
