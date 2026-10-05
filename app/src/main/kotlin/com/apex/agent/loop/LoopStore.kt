package com.apex.agent.loop

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * S2 — Loop 循环持久化单例。
 *
 * 布局：`filesDir/loops/loops.json` 单文件（全部循环 + runLog 汇总）。
 *
 * - **原子写**（对齐 FileTaskStore 的写法）：tmp 写入 → flush → fd.sync()
 *   → 同目录 renameTo；rename 失败回退 copy+delete。读侧永远只看到完整文件。
 * - **防御式读**：损坏 JSON 折叠为空状态 + AppLogger.warn + 坏文件改名
 *   `.bak` 隔离（不删除，留现场）；任何 IO 异常都不上抛。
 * - **内存态 + StateFlow**：[state] 是唯一真源；[mutate] 在协程锁内改内存、
 *   更新流、落盘（fire-and-forget 协程域 SupervisorJob 隔离——调用方
 *   （VM/调度器）绝不等磁盘，AGENTS.md 纪律 6）。
 * - **启动竞态**：构造时异步首载（CompletableDeferred 关卡）——首载完成前
 *   一切 mutateAwait 挂起等待，杜绝「首载覆盖晚到写入」的丢更新窗口。
 */
@Singleton
class LoopStore @Inject constructor(
    @ApplicationContext context: Context
) {
    /** 持久化信封（LoopRunLog 非 Serializable 模型层，用同构 DTO 映射）。 */
    @Serializable
    internal data class PersistedLog(val at: Long = 0L, val ok: Boolean = true, val summary: String = "")

    @Serializable
    internal data class PersistedState(
        val loops: List<LoopConfig> = emptyList(),
        val runLogs: Map<String, List<PersistedLog>> = emptyMap()
    )

    private val dir = File(context.filesDir, "loops")
    private val file = File(dir, FILE_NAME)
    private val tmp = File(dir, FILE_NAME + TMP_SUFFIX)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _state = MutableStateFlow(LoopState())

    /** 内存真源（初值空；首载完成后回填磁盘内容；结构相等天然去重）。 */
    val state: StateFlow<LoopState> = _state.asStateFlow()

    /** 首载关卡：完成后 mutateAwait 才放行（防首载覆盖竞态）。 */
    private val initialLoad = CompletableDeferred<Unit>()

    /** 落盘协程域（SupervisorJob 隔离：单次写失败不殃及兄弟任务）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 读-改-写串行锁（协程级：mutateAwait 的状态段互斥，防丢失更新）。 */
    private val stateMutex = Mutex()

    /** 文件写串行锁（阻塞级：save 的 tmp/rename 原子段互斥）。 */
    private val fileLock = ReentrantLock()

    init {
        dir.mkdirs()
        scope.launch { load() }
    }

    /** 全量读盘（同步，IO 协程内调用）。损坏 → 空状态 + .bak 隔离，不上抛。 */
    fun load(): LoopState {
        cleanupTemp()
        if (file.exists()) {
            val loaded = try {
                val parsed = json.decodeFromString(PersistedState.serializer(), file.readText())
                LoopState(
                    loops = parsed.loops,
                    runLogs = parsed.runLogs.mapValues { (_, logs) ->
                        logs.map { LoopRunLog(at = it.at, ok = it.ok, summary = it.summary) }
                    }
                )
            } catch (e: Exception) {
                // kotlinx SerializationException 是 IllegalArgumentException 子类；
                // 任何形状的坏文件都折叠为空状态 + 留痕 + 隔离，不上抛。
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, TAG,
                    "loops.json corrupt, quarantining as .bak (${e::class.simpleName}: ${e.message})"
                )
                runCatching {
                    file.renameTo(File(dir, "${FILE_NAME}.${System.currentTimeMillis()}.bak"))
                }.onFailure {
                    AppLogger.instance.warn(LogCategory.SYSTEM, TAG, "quarantine rename failed: ${file.name}")
                }
                LoopState()
            }
            _state.value = loaded
        }
        initialLoad.complete(Unit)
        return _state.value
    }

    /** 全量写盘（同步，内部走原子写 + 文件锁；失败只留痕不上抛）。 */
    fun save(state: LoopState) {
        fileLock.withLock {
            try {
                val persisted = PersistedState(
                    loops = state.loops,
                    runLogs = state.runLogs.mapValues { (_, logs) ->
                        logs.map { PersistedLog(at = it.at, ok = it.ok, summary = it.summary) }
                    }
                )
                FileOutputStream(tmp).use { out ->
                    out.write(json.encodeToString(PersistedState.serializer(), persisted).toByteArray())
                    out.flush()
                    // fsync：rename 前数据先落盘（进程死亡 → rename 要么发生要么没发生，
                    // 不会出现「rename 成功但内容丢失」）
                    out.fd.sync()
                }
                if (!tmp.renameTo(file)) {
                    // rename 失败（跨设备/目标被锁）→ 回退复制 + 删除
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete() // 半写 temp 清理，不留垃圾
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, TAG,
                    "save loops.json failed (${e::class.simpleName}: ${e.message}) — keep in-memory state"
                )
            }
        }
    }

    /**
     * fire-and-forget 变更：IO 协程内改内存 → 更新流 → 落盘。
     * 调用方（VM 的 upsert/remove/setEnabled 等）不等待磁盘。
     */
    fun mutate(block: (MutableLoopState) -> Unit) {
        scope.launch { mutateAwait(block) }
    }

    /**
     * 挂起版变更（调度器 tick 用）：变更 + 落盘完成后才返回，调用方拿到的
     * 是**已持久化**的新状态（persist-then-emit 的基石——轮次标记先落盘，
     * 再对外发射 dueEvent，崩溃窗口内不会双跑）。
     */
    internal suspend fun mutateAwait(block: (MutableLoopState) -> Unit): LoopState {
        initialLoad.await()
        return stateMutex.withLock {
            val mutable = MutableLoopState(
                loops = _state.value.loops.toMutableList(),
                runLogs = _state.value.runLogs.mapValues { (_, v) -> v.toMutableList() }.toMutableMap()
            )
            block(mutable)
            val next = LoopState(
                loops = mutable.loops.toList(),
                runLogs = mutable.runLogs.mapValues { (_, v) -> v.toList() }
            )
            _state.value = next
            save(next)
            next
        }
    }

    /** 扫掉半写 temp 残留（load 时顺带执行；FileTaskStore 同款纪律）。 */
    private fun cleanupTemp() {
        val temps = dir.listFiles { f -> f.isFile && f.name.endsWith(TMP_SUFFIX) } ?: return
        for (stale in temps) {
            val ok = stale.delete()
            AppLogger.instance.warn(
                LogCategory.SYSTEM, TAG,
                "cleaned half-written temp: ${stale.name} (deleted=$ok)"
            )
        }
    }

    private companion object {
        const val TAG = "LoopStore"
        const val FILE_NAME = "loops.json"
        const val TMP_SUFFIX = ".tmp"
    }
}

/** 不可变快照（StateFlow 载荷）。 */
data class LoopState(
    val loops: List<LoopConfig> = emptyList(),
    val runLogs: Map<String, List<LoopRunLog>> = emptyMap()
)

/** mutate 回调的操作视图（变更完由 store 转回不可变快照）。 */
class MutableLoopState(
    val loops: MutableList<LoopConfig> = mutableListOf(),
    val runLogs: MutableMap<String, MutableList<LoopRunLog>> = mutableMapOf()
)
