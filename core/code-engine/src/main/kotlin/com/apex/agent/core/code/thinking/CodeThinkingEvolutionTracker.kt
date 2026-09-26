package com.apex.agent.core.code.thinking

import com.apex.agent.core.code.longtask.LongTaskRecord
import com.apex.agent.core.code.longtask.LongTaskStatus
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * # Thinking Evolution Tracker — 思考档位效能追踪（v1.2）
 *
 * ## 解决什么问题
 *
 * 七档思考系统给用户 7 个深度档 + AUTO，但「哪个档适合我」没有数据支撑——
 * 用户只能凭感觉选。本类把**长任务留档**（[LongTaskRecord]，已过规模闸门
 * 的真实运行）按「工作区 × 档位」聚合成效能统计：跑了几次、成功率、平均
 * 迭代/工具/时长/文件数。用户在长任务中心的「档位效能」页签能看到自己
 * 的历史画像，据此选档（例：DEEP 档 5 次任务 4 次成功、平均 12 轮；
 * ULTRACODE 档 2 次、平均 31 轮——浅任务用 DEEP 足够）。
 *
 * ## 数据口径（与长任务中心对齐）
 *
 * - **只统计长任务**：短任务不留档（[com.apex.agent.core.code.longtask.LongTaskDetector]
 *   闸门），本类的输入就是留档记录——统计基数少但都是"值得复盘"的运行；
 * - **AUTO 聚合在 AUTO 名下**：AUTO 运行记录的 thinkingLevel 字段是
 *   "AUTO"（逐轮实际档位在引擎侧决策，不落记录）——统计语义是"选 AUTO
 *   这个决策的表现"而非"某个实际档位的表现"（诚实口径，不冒领）；
 * - **状态三分**：completed / failed / aborted 分别计数，成功率 =
 *   completed / runs（aborted 算未成功——用户中止说明没跑完）。
 *
 * ## 持久化
 *
 * `baseDir/stats_<workspaceId>.json`（DI 注入 `filesDir/longtask/thinking-stats`）：
 * - 原子写（tmp + rename），损坏文件跳过重置（防御式，对齐 LongTaskStore 风格）；
 * - **内存优先**：读一次缓存，后续 [ingest] 只改内存 + fire-and-forget 落盘
 *   （[persistScope] 注入，主线程零 IO）；
 * - 单工作区文件很小（≤8 档 × 一个聚合对象），整文件覆写即可。
 *
 * ## 线程模型
 *
 * [ingest] / [statsFor] 由 VM 主线程串行调用（endRun 链路），无并发竞争；
 * 落盘协程捕获的是**已组装完成的不可变快照**（LevelRunStat data class），
 * 与后续状态无共享可变引用。
 */
class CodeThinkingEvolutionTracker(
    private val baseDir: File,
    private val persistScope: CoroutineScope
) {

    /** 单档位累计统计（入库记录聚合，均值由 [avgIterations] 等派生）。 */
    @Serializable
    data class LevelRunStat(
        val level: String,
        val runs: Int = 0,
        val completed: Int = 0,
        val failed: Int = 0,
        val aborted: Int = 0,
        val totalIterations: Int = 0,
        val totalToolCalls: Int = 0,
        val totalDurationMs: Long = 0,
        val totalFilesTouched: Int = 0
    ) {
        /** 成功率（0..1；runs=0 时 0——展示层显示"—"更诚实，但保底 0 防除零）。 */
        val successRate: Float get() = if (runs > 0) completed.toFloat() / runs else 0f

        /** 平均迭代数（runs=0 → 0）。 */
        val avgIterations: Int get() = if (runs > 0) totalIterations / runs else 0

        /** 平均工具调用数。 */
        val avgToolCalls: Int get() = if (runs > 0) totalToolCalls / runs else 0

        /** 平均时长（ms）。 */
        val avgDurationMs: Long get() = if (runs > 0) totalDurationMs / runs else 0L

        /** 平均触碰文件数。 */
        val avgFilesTouched: Int get() = if (runs > 0) totalFilesTouched / runs else 0
    }

    /** 一个工作区的全部档位统计（持久化单元）。 */
    @Serializable
    data class WorkspaceThinkingStats(
        val workspaceId: String,
        val levels: Map<String, LevelRunStat> = emptyMap(),
        val updatedAt: Long = 0
    )

    /** 内存缓存：workspaceId → 统计（首次访问惰性加载）。 */
    private val cache = LinkedHashMap<String, WorkspaceThinkingStats>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    init {
        baseDir.mkdirs()
    }

    /**
     * 摄入一条长任务记录（endRun 后由 VM 调用；模板记录不计——它们不是
     * 真实运行）。fire-and-forget 落盘，主线程零 IO。
     */
    fun ingest(record: LongTaskRecord) {
        if (record.isTemplate) return
        val wsId = record.workspaceId
        if (wsId.isBlank()) return

        val stats = cache.getOrPut(wsId) { loadOrNew(wsId) }
        val current = stats.levels[record.thinkingLevel]
            ?: LevelRunStat(level = record.thinkingLevel)

        val updated = current.copy(
            runs = current.runs + 1,
            completed = current.completed + if (record.status == LongTaskStatus.COMPLETED) 1 else 0,
            failed = current.failed + if (record.status == LongTaskStatus.FAILED) 1 else 0,
            aborted = current.aborted + if (record.status == LongTaskStatus.ABORTED) 1 else 0,
            totalIterations = current.totalIterations + record.iterations,
            totalToolCalls = current.totalToolCalls + record.toolCalls,
            totalDurationMs = current.totalDurationMs + record.durationMs,
            totalFilesTouched = current.totalFilesTouched + record.filesTouched.size
        )

        val newStats = stats.copy(
            levels = stats.levels + (record.thinkingLevel to updated),
            updatedAt = System.currentTimeMillis()
        )
        cache[wsId] = newStats
        persistScope.launch { persist(wsId, newStats) }
    }

    /**
     * 查询工作区档位统计（含全部出现过的档位，按 runs 降序）。
     * 首次访问触发一次同步加载（后续命中缓存）——UI 在 IO 线程调用。
     */
    fun statsFor(workspaceId: String): WorkspaceThinkingStats {
        val stats = cache.getOrPut(workspaceId) { loadOrNew(workspaceId) }
        return stats.copy(
            levels = stats.levels.values
                .sortedByDescending { it.runs }
                .associateBy { it.level }
        )
    }

    /** 清空某工作区统计（设置/调试入口；文件与缓存一并清理）。 */
    fun reset(workspaceId: String) {
        cache.remove(workspaceId)
        runCatching { fileFor(workspaceId).delete() }
            .onFailure { AppLogger.instance.warn(LogCategory.ENGINE, TAG, "删除档位统计文件失败：${it.message}") }
    }

    // ═══ 内部：持久化 ═══

    private fun loadOrNew(workspaceId: String): WorkspaceThinkingStats {
        val file = fileFor(workspaceId)
        if (!file.exists()) return WorkspaceThinkingStats(workspaceId)
        return runCatching { json.decodeFromString(WorkspaceThinkingStats.serializer(), file.readText()) }
            .onFailure {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, TAG,
                    "档位统计文件损坏，重置（workspace=$workspaceId）：${it.message}"
                )
            }
            .getOrNull()
            ?.takeIf { it.workspaceId == workspaceId }
            ?: WorkspaceThinkingStats(workspaceId)
    }

    private suspend fun persist(workspaceId: String, stats: WorkspaceThinkingStats) {
        runCatching {
            val file = fileFor(workspaceId)
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            tmp.writeText(json.encodeToString(WorkspaceThinkingStats.serializer(), stats))
            if (!tmp.renameTo(file)) {
                // rename 失败（跨设备/被占用）→ 直接写目标文件兜底
                file.writeText(json.encodeToString(WorkspaceThinkingStats.serializer(), stats))
                tmp.delete()
            }
        }.onFailure {
            AppLogger.instance.warn(LogCategory.ENGINE, TAG, "档位统计落盘失败（workspace=$workspaceId）：${it.message}")
        }
    }

    private fun fileFor(workspaceId: String): File {
        // workspaceId 是内部生成的标识（ws_xxx / default / 空串），仍做一层
        // 路径净化防注入（外部输入不信任原则）
        val safe = workspaceId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(baseDir, "stats_$safe.json")
    }

    private companion object {
        const val TAG = "ThinkingEvolution"
        const val TMP_SUFFIX = ".tmp"
    }
}
