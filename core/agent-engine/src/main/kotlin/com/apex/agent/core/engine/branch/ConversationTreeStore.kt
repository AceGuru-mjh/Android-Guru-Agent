package com.apex.agent.core.engine.branch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * ═══ 会话树列表摘要（4-c）═══
 *
 * 会话列表页的一行数据（对位 RikkaHub LightConversationEntity 摘要 +
 * Paging3 的「列表轻、详情重」分层——列表不需要整棵树）。
 *
 * branchCount 口径：有多候选（可切换分支）的节点数——即 UI 上会显示
 * 「◀ n/total ▶」选择器的槽数。
 */
data class TreeSummary(
    /** 会话 id。 */
    val id: String,
    /** 标题。 */
    val title: String,
    /** 线性消息数（currentMessages.size）。 */
    val messageCount: Int,
    /** 最后更新时间（epoch ms）。 */
    val updatedAt: Long,
    /** 分支节点数（candidateCount > 1 的节点数）。 */
    val branchCount: Int
)

/**
 * ═══ 会话树文件式仓库（4-c）═══
 *
 * 学习 RikkaHub 的「每会话一套完整消息节点」持久化语义，但落盘走
 * apex FileTaskStore / LongTaskStore 纪律（RikkaHub 用 Room 全量重写，
 * apex 纯 JVM 模块用文件原子写——存储介质不同、原子性纪律相同）：
 *
 * ```
 * <dir>/                    DI 注入（App: filesDir/conversations；测试: 临时目录）
 *   ├── {treeId}.json       一个会话一棵树（encodeDefaults 完整快照）
 *   ├── {treeId}.json.tmp   写入中 temp（崩溃残留，首载清理）
 *   └── {treeId}.json.corrupt  损坏隔离（解析失败的文件改名备份，不删除）
 * ```
 *
 * **原子写**：tmp 写入 → flush → fd.sync()（fsync，防 OS 页缓存丢
 * 数据）→ 同目录 rename（同分区原子）；rename 失败回退 copyTo +
 * delete。进程任何时刻死亡，读侧只会看到完整旧文件或完整新文件。
 *
 * **防御式读**：损坏 JSON → 改名隔离到 .corrupt 后备 + logger 留痕
 * + 当作不存在（返回 null），绝不抛——一棵坏树不能让会话列表瘫痪。
 *
 * **并发模型**（对齐 LongTaskStore / PromptTemplateRegistry）：
 * @Volatile 不可变快照缓存（写时复制整体替换，读侧无锁）+ 协程
 * Mutex 串行读改写（save / delete）+ 惰性双检加载（首次访问扫一次
 * 目录）。[listIds] / [snapshots] 为同步快照读（首次调用含一次小目录
 * 扫描，之后纯内存——会话数受上层约束在百量级，可接受）。
 *
 * **id 纪律**：treeId 只允许 [a-zA-Z0-9_-] 且 1..64 位（挡路径穿越
 * 与怪字符）。save 收到非法 id → require 失败（我方 bug，fail-fast）；
 * load / delete 收到非法 id → null / false（外部输入查询语义，不抛）。
 */
class ConversationTreeStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {}
) {

    private val json = Json {
        ignoreUnknownKeys = true   // 前向兼容：未来字段不炸
        isLenient = true
        encodeDefaults = true      // 默认值显式落盘（diff 友好）
        prettyPrint = false
    }

    /** 写路径串行锁：读缓存 → 改 → 发布 → 写盘 四步原子。 */
    private val mutex = Mutex()

    /** 内存缓存：不可变快照，写时复制整体替换（LinkedHashMap 保插入序）。 */
    @Volatile
    private var cache: Map<String, ConversationTree> = LinkedHashMap()

    /** 惰性加载双检锁 + 完成旗标。 */
    private val loadLock = Any()

    @Volatile
    private var loaded = false

    init {
        // 构造即建目录（幂等，失败不抛：只读场景照样可用，首次写盘再留痕）
        runCatching { dir.mkdirs() }
            .onFailure { logger("ConversationTreeStore: mkdirs failed: ${it.message}") }
    }

    // ═══════════════════════ 写 ═══════════════════════

    /**
     * 保存一棵树（upsert by id）：盖当前时钟戳到 updatedAt（内容定稿
     * 时刻由持久化层统一管理）后整体替换缓存与磁盘文件。
     *
     * @return 盖章后的树（调用方应改用返回值——它是落盘的那份）。
     * @throws IllegalArgumentException treeId 非法（[ID_PATTERN]）。
     * 磁盘写失败只留痕不抛（内存已更新，下次保存全量重写时追平——
     * 会话数据「内存为准、尽力持久」的取舍与 LongTaskStore 一致）。
     */
    suspend fun save(tree: ConversationTree): ConversationTree {
        require(ID_PATTERN.matches(tree.id)) {
            "illegal conversation tree id '${tree.id}' (expected [a-zA-Z0-9_-]{1,64})"
        }
        val stamped = tree.copy(updatedAt = clock())
        mutex.withLock {
            ensureLoaded()
            cache = LinkedHashMap(cache).apply { put(stamped.id, stamped) }
            writeAtomic(stamped)
        }
        return stamped
    }

    // ═══════════════════════ 读 ═══════════════════════

    /** 按 id 取树；不存在 / id 非法 → null。 */
    suspend fun load(id: String): ConversationTree? {
        if (!ID_PATTERN.matches(id)) return null
        ensureLoaded()
        return cache[id]
    }

    /** 全部会话 id（updatedAt 降序，同戳按 id 升序——确定性排序）。 */
    fun listIds(): List<String> = snapshots().map { it.id }

    /**
     * 会话列表摘要（updatedAt 降序，同戳按 id 升序）。同步快照读：
     * 首次调用扫一次目录，之后纯内存。messageCount / branchCount
     * 口径见 [TreeSummary]。
     */
    fun snapshots(): List<TreeSummary> {
        ensureLoaded()
        return cache.values
            .map { tree ->
                TreeSummary(
                    id = tree.id,
                    title = tree.title,
                    messageCount = tree.messageCount,
                    updatedAt = tree.updatedAt,
                    branchCount = tree.branchNodeCount
                )
            }
            .sortedWith(
                compareByDescending<TreeSummary> { it.updatedAt }.thenBy { it.id }
            )
    }

    // ═══════════════════════ 删 ═══════════════════════

    /**
     * 删除一棵树（内存 + 磁盘 + 半写 temp）。
     *
     * @return 原本存在并已移除 → true；不存在 / id 非法 → false。
     * 磁盘删除失败只留痕（内存已移除，重启后可能复活——复活比崩溃好）。
     */
    suspend fun delete(id: String): Boolean {
        if (!ID_PATTERN.matches(id)) return false
        mutex.withLock {
            ensureLoaded()
            if (!cache.containsKey(id)) return false
            cache = LinkedHashMap(cache).apply { remove(id) }
            runCatching {
                if (!File(dir, id + JSON_SUFFIX).delete()) {
                    logger("ConversationTreeStore: tree file already gone: $id")
                }
            }.onFailure { logger("ConversationTreeStore: delete failed for '$id': ${it.message}") }
            runCatching { File(dir, id + JSON_SUFFIX + TMP_SUFFIX).delete() }
            return true
        }
    }

    // ═══════════════════════ 内部机制 ═══════════════════════

    /**
     * 惰性加载：首次访问扫 dir 下全部 .json 结尾的文件一次。
     *
     * 以**文件内容里的 tree.id** 为缓存键（文件名只用于初筛与 id 校验）；
     * 损坏文件改名隔离到 .corrupt（已存在同名隔离文件则先删再改名；
     * 隔离自身失败只留痕）+ 当作不存在；双检锁防并发重扫；loaded 置位
     * 后永不再扫——写路径保证缓存是唯一事实源。
     */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val disk = LinkedHashMap<String, ConversationTree>()
            val files = try {
                dir.listFiles { file -> file.isFile && file.name.endsWith(JSON_SUFFIX) }
            } catch (e: SecurityException) {
                logger("ConversationTreeStore: dir not listable: ${dir.path} (${e.message})")
                null
            } ?: emptyArray()
            for (file in files) {
                val id = file.name.removeSuffix(JSON_SUFFIX)
                if (!ID_PATTERN.matches(id)) {
                    logger("ConversationTreeStore: skip non-conforming file name: ${file.name}")
                    continue
                }
                try {
                    val tree = json.decodeFromString(ConversationTree.serializer(), file.readText())
                    disk[tree.id] = tree
                } catch (e: Exception) {
                    // kotlinx SerializationException 是 IllegalArgumentException
                    // 子类；IOException 来自 readText——一并兜住（防御式不抛）。
                    logger(
                        "ConversationTreeStore: tree file corrupt, quarantining: ${file.name} " +
                            "(${e::class.simpleName}: ${e.message})"
                    )
                    runCatching {
                        val backup = File(dir, file.name + CORRUPT_SUFFIX)
                        if (backup.exists()) backup.delete()
                        file.renameTo(backup)
                    }.onFailure {
                        logger("ConversationTreeStore: quarantine move failed: ${file.name}")
                    }
                }
            }
            cache = disk
            cleanupTempResidue()
            loaded = true
        }
    }

    /**
     * 原子写盘（持锁调用）：tmp → fsync → rename；rename 失败回退
     * copyTo + delete。失败清理 temp + 留痕不抛（内存为准）。
     */
    private fun writeAtomic(tree: ConversationTree) {
        val target = File(dir, tree.id + JSON_SUFFIX)
        val tmp = File(dir, tree.id + JSON_SUFFIX + TMP_SUFFIX)
        try {
            runCatching { dir.mkdirs() }
            FileOutputStream(tmp).use { out ->
                out.write(
                    json.encodeToString(ConversationTree.serializer(), tree)
                        .toByteArray(Charsets.UTF_8)
                )
                out.flush()
                // fsync：rename 前数据已落盘——进程死亡时 rename 要么已发生
                // 要么没发生，不会出现「rename 成功但内容丢失」。
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // rename 失败（跨设备 / 目标被锁）→ 回退复制 + 删除
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            logger(
                "ConversationTreeStore: write failed for '${tree.id}' (${e.message}); " +
                    "in-memory cache stays authoritative"
            )
        } catch (e: IllegalArgumentException) {
            tmp.delete()
            logger("ConversationTreeStore: serialize failed for '${tree.id}' (${e.message})")
        } catch (e: SecurityException) {
            tmp.delete()
            logger("ConversationTreeStore: write denied for '${tree.id}' (${e.message})")
        }
    }

    /** 清理半写 temp 残留（仅首载执行，避免与进行中的写盘互踩）。 */
    private fun cleanupTempResidue() {
        val temps = try {
            dir.listFiles { file -> file.isFile && file.name.endsWith(TMP_SUFFIX) }
        } catch (e: SecurityException) {
            null
        } ?: return
        for (tmp in temps) {
            val ok = tmp.delete()
            logger("ConversationTreeStore: cleaned half-written temp file ${tmp.name} (deleted=$ok)")
        }
    }

    companion object {
        /** 合法会话 id：字母数字与连字符下划线，1..64 位（挡路径穿越）。 */
        val ID_PATTERN = Regex("[a-zA-Z0-9_-]{1,64}")

        private const val JSON_SUFFIX = ".json"
        private const val TMP_SUFFIX = ".tmp"
        private const val CORRUPT_SUFFIX = ".corrupt"

        /**
         * 线性历史 → 会话树（迁移辅助：apex ChatHistoryManager 的扁平
         * ChatHistoryMessage 列表接入分支世界的桥）。
         *
         * 分组规则（**有意为之的迁移语义，特此声明**）：
         *
         * 1. USER / SYSTEM 消息各自独占一个新节点（单候选）；
         * 2. **连续的 ASSISTANT/TOOL 消息（一个 agent 回合）合并进同一
         *    个节点——该节点以回合首条消息为唯一候选**。回合内的后续
         *    消息（工具输出、后续 assistant 片段）**不生成候选**：
         *    分支模型的候选是「同一槽位的可切换备选」，而工具链消息
         *    是回合的组成部分而非备选——把它们塞成候选会制造假的
         *    「◀ 2/3 ▶」分支选择器（切换语义完全错误）。apex 现有
         *    恢复路径本来就只取 user/agent 文本对（工具配对无法从
         *    展示态重建），本规则与其口径一致；需要保留完整工具链的
         *    调用方应在调用前自行过滤/拼接线性输入（meta 字段可携带
         *    附加信息）。
         *
         * 3. 节点 id 确定性派生："{id}-n{序号}"（树内唯一）；消息 id
         *    保留输入原值（调用方保证唯一，或迁移后跑 sanitize 去重）。
         * 4. createdAt / updatedAt = clock()（迁移时刻）。
         *
         * @param id 会话 id（须满足 [ID_PATTERN]，调用方保证）。
         * @param messages 线性消息（时间序）。
         */
        fun fromLinearMessages(
            id: String,
            title: String,
            messages: List<BranchMessage>,
            clock: () -> Long = { 0L }
        ): ConversationTree {
            val nodes = mutableListOf<MessageBranchNode>()
            var index = 0
            while (index < messages.size) {
                val message = messages[index]
                if (message.role == MessageRole.ASSISTANT || message.role == MessageRole.TOOL) {
                    // 一个 agent 回合 → 一个节点，首条消息为唯一候选；
                    // 回合终止于首条 USER/SYSTEM 消息（或列表尽头）。
                    var end = index + 1
                    while (end < messages.size &&
                        (messages[end].role == MessageRole.ASSISTANT || messages[end].role == MessageRole.TOOL)
                    ) {
                        end++
                    }
                    nodes.add(
                        MessageBranchNode(
                            id = "$id-n${nodes.size}",
                            candidates = listOf(message),
                            selectIndex = 0
                        )
                    )
                    index = end
                } else {
                    nodes.add(
                        MessageBranchNode(
                            id = "$id-n${nodes.size}",
                            candidates = listOf(message),
                            selectIndex = 0
                        )
                    )
                    index++
                }
            }
            val now = clock()
            return ConversationTree(
                id = id,
                title = title,
                nodes = nodes,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}
