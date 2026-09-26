package com.apex.agent.core.engine.longtask

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * # 长任务持久化仓库
 *
 * ## 磁盘布局
 *
 * ```
 * <baseDir>/                        DI 注入（App: filesDir/longtask；测试: 临时目录）
 *   └── records/                    一个记录一个 JSON 文件
 *        ├── <id>.json              已落盘记录（encodeDefaults 完整快照）
 *        └── <id>.json.tmp          写入中 temp（崩溃残留，扫描时视为不存在）
 * ```
 *
 * ## 并发与一致性模型（本类最重要的设计决策）
 *
 * 三层结构：
 *
 * 1. **内存缓存**（`cache`）：不可变 Map 快照 + 写时复制。每次写操作在
 *    Mutex 内复制-修改-整体替换，读侧（含主线程 [snapshotBlocking]）只读
 *    `@Volatile` 引用——**读永远不加锁不挂起**，代价是并发写进行中读侧
 *    可能拿到上一个快照（最终一致，对「历史列表」这个读多写少的场景是
 *    正确取舍：列表 UI 宁可晚一拍看到新记录，也不能因锁竞争掉帧）；
 * 2. **写串行锁**（`mutex`）：所有**读改写**类操作（upsert / delete /
 *    incrementCopyCount / prune）在协程 Mutex 内串行，保证「读缓存 → 改 →
 *    发布 → 写盘」四步不被并发交错撕开（否则两次并发
 *    incrementCopyCount 会丢一次自增）；
 * 3. **惰性加载**（`ensureLoaded`）：首次访问（无论读写）同步扫描 records
 *    目录一次，之后 `loaded = true` 不再扫。扫描本身用监视器锁
 *    （`loadLock`）防重入——它与 Mutex 的顺序是「先 ensureLoaded 后改
 *    缓存」，且扫描只发生在 loaded 翻转前，不可能覆盖已发布的写。
 *
 * ## 崩溃安全
 *
 * - **原子写**：先写 `<id>.json.tmp` → flush → fd.sync()（fsync，防 OS 页
 *   缓存丢数据）→ 同目录 rename（同分区原子）。进程在任何时刻死亡，读侧
 *   看到的要么是旧完整文件、要么是新完整文件，绝无半写 JSON；
 * - rename 失败（跨设备 / 目标被锁）回退 copyTo + delete，最后才认输记日志。
 *
 * ## 防御式 IO（对齐 RulesProvider / FileTaskStore 的一贯纪律）
 *
 * - **读侧**：损坏 / 不可读的 JSON 文件跳过并 warn 留痕，绝不抛——一条
 *   坏记录不该让整个历史列表瘫痪；
 * - **写侧**：IOException / 序列化失败 → 清理 temp + warn 留痕 + **不向
 *   上抛**。取舍：内存缓存已更新（UI 一致），磁盘在下次 upsert 全量重写
 *   时自然追平；长任务历史是「尽力持久」的增强数据，一次写盘失败不值得
 *   让收尾链路崩溃。若需要强一致，调用方可在 upsert 后回读校验（本类
 *   不做——读放大不划算）；
 * - **路径安全**：记录 id 只允许字母数字与 `-_`（UUID / template-key /
 *   slug 化 workspaceId 都天然满足），杜绝 `../` 穿越读任意文件。upsert
 *   收到非法 id 直接 require 失败（我方代码 bug，fail-fast）；get/delete
 *   对非法 id 返回「未找到」（外部输入的查询语义，不抛）。
 *
 * ## 线程模型
 *
 * 惰性扫描与写盘是阻塞 IO——suspend 写方法应从 IO 调度器调用（DI 注入
 * SupervisorJob + Dispatchers.IO 的 scope，Tracker 的 fire-and-forget 即是）。
 * [snapshotBlocking] 为 VM 主线程设计：首次调用做一次小目录扫描（记录数
 * 被 prune 上限约束在几十量级），之后纯内存读——**绝不含 runBlocking**
 * （主线程同步等协程锁是 ANR 制造机，v1.2 前的 AppLogger v2 重构教训）。
 */
class LongTaskStore(private val baseDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true   // 旧文件读到未来字段不炸（前向兼容）
        encodeDefaults = true      // 默认值显式落盘（字段语义自文档，diff 友好）
    }

    /** 记录文件目录（baseDir/records）。 */
    private val recordsDir: File = File(baseDir, RECORDS_DIR_NAME)

    /** 写路径串行锁：读改写四步（读缓存→改→发布→写盘）的原子性保证。 */
    private val mutex = Mutex()

    /**
     * 内存缓存：不可变快照，写时复制后整体替换发布。
     * 读侧不加锁——见类 KDoc「并发与一致性模型」的取舍说明。
     */
    @Volatile
    private var cache: Map<String, LongTaskRecord> = emptyMap()

    /** 惰性加载的监视器锁 + 完成旗标（双检锁：loaded 翻转后永不再扫）。 */
    private val loadLock = Any()

    @Volatile
    private var loaded = false

    init {
        // 构造即建目录（幂等）：DI 在 Application 级构造本类，records 子目录
        // 必须在第一次写之前就位；mkdirs 失败不抛（只读场景照样可用，
        // 首次写盘时 writeAtomic 会再次 warn）。
        baseDir.mkdirs()
        recordsDir.mkdirs()
    }

    // ═══════════════════════ 写 ═══════════════════════

    /**
     * 插入或整体替换一条记录（同 id 覆盖——Tracker 的 endRun / 副本计数
     * bump 都走全量覆盖语义）。
     *
     * @throws IllegalArgumentException 记录 id 含非法字符（我方代码 bug，
     *   fail-fast 不吞）。
     */
    suspend fun upsert(record: LongTaskRecord) {
        require(record.id.matches(ID_PATTERN)) { "illegal long task record id: ${record.id}" }
        mutex.withLock {
            ensureLoaded()
            val next = cache.toMutableMap()
            next[record.id] = record
            publish(next)
            writeAtomic(record)
        }
    }

    // ═══════════════════════ 读 ═══════════════════════

    /** 按 id 取记录；不存在 / id 非法 → null。 */
    suspend fun get(id: String): LongTaskRecord? {
        if (!id.matches(ID_PATTERN)) return null
        ensureLoaded()
        return cache[id]
    }

    /**
     * 列出记录，按 updatedAt 降序（最新在前——历史列表的自然序）。
     *
     * @param workspaceId 非空时只返回该工作区的记录；null = 全部工作区。
     */
    suspend fun list(workspaceId: String? = null): List<LongTaskRecord> {
        ensureLoaded()
        return sortedSnapshot(workspaceId)
    }

    /**
     * 主线程快速读：语义与 [list] 完全一致，但**不含任何挂起与
     * runBlocking**——直接读内存缓存（必要时首次同步扫盘一次）。
     *
     * 场景：VM 在 Compose 快照 / 列表刷新时需要同步拿到当前历史。之后
     * 的更新由 VM 在写路径后自行刷新（或再次调用本方法）。
     */
    fun snapshotBlocking(workspaceId: String? = null): List<LongTaskRecord> {
        ensureLoaded()
        return sortedSnapshot(workspaceId)
    }

    // ═══════════════════════ 删 ═══════════════════════

    /**
     * 删除一条记录（内存 + 磁盘）。
     *
     * @return 记录原本存在并已被移除 → true；不存在 / id 非法 → false。
     *   磁盘删除失败只 warn（内存已移除，重启后可能复活——复活比崩溃好）。
     */
    suspend fun delete(id: String): Boolean {
        if (!id.matches(ID_PATTERN)) return false
        mutex.withLock {
            ensureLoaded()
            if (!cache.containsKey(id)) return false
            val next = cache.toMutableMap()
            next.remove(id)
            publish(next)
            val file = File(recordsDir, id + JSON_SUFFIX)
            try {
                if (!file.delete()) {
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, TAG,
                        "记录文件删除失败（可能已不存在）: $id"
                    )
                }
            } catch (e: SecurityException) {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, TAG,
                    "记录文件删除被安全策略拒绝: $id (${e.message})"
                )
            }
            // 半写 temp 一并清理（若有）
            runCatching { File(recordsDir, id + JSON_SUFFIX + TMP_SUFFIX).delete() }
            return true
        }
    }

    // ═══════════════════════ 读改写 ═══════════════════════

    /**
     * copyCount 原子自增（TaskCopyEngine 复制成功后回写源记录）。
     *
     * 读-改-写全程持 Mutex——并发复制同一源时不丢计数。记录不存在 →
     * no-op + warn（复制链上游已校验过存在性，走到这里多半是并发删除，
     * 不值得抛）。updatedAt 一并刷新（记录确实变了）。
     */
    suspend fun incrementCopyCount(id: String) {
        if (!id.matches(ID_PATTERN)) return
        mutex.withLock {
            ensureLoaded()
            val current = cache[id]
            if (current == null) {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, TAG,
                    "incrementCopyCount 目标记录不存在: $id"
                )
                return
            }
            val bumped = current.copy(
                copyCount = current.copyCount + 1,
                updatedAt = System.currentTimeMillis()
            )
            val next = cache.toMutableMap()
            next[id] = bumped
            publish(next)
            writeAtomic(bumped)
        }
    }

    /**
     * 裁剪历史：保留最新 [keep] 条非模板记录，其余删除。
     *
     * **模板记录（isTemplate=true）豁免**——它们是用户可反复实例化的
     * 起点（且不占 keep 名额：模板被裁掉等于功能消失，而模板数量天然
     * 有界——每工作区每模板至多一条稳定 id 记录）。
     *
     * @param keep 保留条数（负数按 0 处理 = 清空全部非模板记录）。
     * @return 实际删除的条数（磁盘删除失败也计入——内存视角已删除）。
     */
    suspend fun prune(keep: Int = 50): Int {
        val keepCount = keep.coerceAtLeast(0)
        mutex.withLock {
            ensureLoaded()
            val newestFirst = cache.values.sortedByDescending { it.updatedAt }
            val doomed = newestFirst.filter { !it.isTemplate }.drop(keepCount)
            if (doomed.isEmpty()) return 0
            val next = cache.toMutableMap()
            for (record in doomed) {
                next.remove(record.id)
                try {
                    File(recordsDir, record.id + JSON_SUFFIX).delete()
                } catch (e: SecurityException) {
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, TAG,
                        "prune 删除文件被安全策略拒绝: ${record.id} (${e.message})"
                    )
                }
            }
            publish(next)
            return doomed.size
        }
    }

    // ═══════════════════════ 内部机制 ═══════════════════════

    /** updatedAt 降序 + 可选工作区过滤（读侧共用）。 */
    private fun sortedSnapshot(workspaceId: String?): List<LongTaskRecord> {
        val values = cache.values
        return if (workspaceId == null) {
            values.sortedByDescending { it.updatedAt }
        } else {
            values.filter { it.workspaceId == workspaceId }.sortedByDescending { it.updatedAt }
        }
    }

    /** 发布新的缓存快照（volatile 整体替换，读侧立即可见）。 */
    private fun publish(next: Map<String, LongTaskRecord>) {
        cache = next
    }

    /**
     * 惰性加载：首次访问扫 records 目录一次。
     *
     * 以**文件内容里的 record.id** 为缓存键而非文件名（文件改名/手工搬运
     * 后仍可读）；损坏文件跳过 + warn（防御式，不抛）。双检锁防并发重扫；
     * `loaded` 置位后永不再扫——写路径已保证缓存是唯一事实源。
     */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val disk = mutableMapOf<String, LongTaskRecord>()
            val files = try {
                recordsDir.listFiles { f -> f.isFile && f.name.endsWith(JSON_SUFFIX) }
            } catch (e: SecurityException) {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, TAG,
                    "records 目录不可读: ${recordsDir.path} (${e.message})"
                )
                null
            } ?: emptyArray()
            for (file in files) {
                try {
                    val record = json.decodeFromString(LongTaskRecord.serializer(), file.readText())
                    disk[record.id] = record
                } catch (e: Exception) {
                    // 损坏文件隔离：跳过 + 留痕，不拖垮其他记录也不抛
                    // （kotlinx SerializationException 是 IllegalArgumentException
                    // 子类，IOException 来自 readText——一并兜住）。
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, TAG,
                        "跳过损坏的长任务记录文件: ${file.name} " +
                            "(${e::class.simpleName}: ${e.message})"
                    )
                }
            }
            cache = disk
            loaded = true
        }
    }

    /**
     * 原子写：temp 写入 → flush → fsync → rename（同分区原子）。
     * 任一步失败：清理 temp + warn + 不抛（见类 KDoc「防御式 IO」取舍）。
     */
    private fun writeAtomic(record: LongTaskRecord) {
        val target = File(recordsDir, record.id + JSON_SUFFIX)
        val tmp = File(recordsDir, record.id + JSON_SUFFIX + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(
                    json.encodeToString(LongTaskRecord.serializer(), record)
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
            AppLogger.instance.warn(
                LogCategory.ENGINE, TAG,
                "长任务记录写盘失败（内存缓存已更新，下次 upsert 将重写）: " +
                    "${record.id} (${e.message})"
            )
        } catch (e: IllegalArgumentException) {
            // kotlinx SerializationException 的父类：编码失败（理论不可达——
            // 模型全字段可序列化）
            tmp.delete()
            AppLogger.instance.warn(
                LogCategory.ENGINE, TAG,
                "长任务记录序列化失败: ${record.id} (${e.message})"
            )
        } catch (e: SecurityException) {
            tmp.delete()
            AppLogger.instance.warn(
                LogCategory.ENGINE, TAG,
                "长任务记录写盘被安全策略拒绝: ${record.id} (${e.message})"
            )
        }
    }

    private companion object {
        const val TAG = "LongTaskStore"

        /** records 子目录名（与类 KDoc 磁盘布局一致）。 */
        const val RECORDS_DIR_NAME = "records"

        const val JSON_SUFFIX = ".json"
        const val TMP_SUFFIX = ".tmp"

        /**
         * 合法记录 id：字母数字与 `-_`。
         * UUID / template-<key>-<slug> 天然满足；挡住路径穿越与怪字符。
         */
        val ID_PATTERN = Regex("[a-zA-Z0-9_-]+")
    }
}
