package com.apex.agent.platform.csmem.dream

import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 梦境渲染触发的最低电量阈值（%），电量低于此值则推迟执行。 */
private const val MIN_BATTERY_LEVEL = 50

/**
 * 梦境渲染引擎 —— 设备息屏/空闲时的记忆保鲜与拓扑同胚迁移。
 *
 * 机制：
 *   1. 触发条件：设备息屏 + 充电中 + WiFi 联网
 *   2. 记忆保鲜：随机抽取低能 Episode/FSM，验证其有效性
 *   3. 拓扑同胚计算：尝试将旧版本 UI 拓扑图映射到新版本（跨版本记忆迁移）
 *   4. 能量衰减：全局记忆熵衰减（遗忘非关键记忆）
 *
 * 理论依据：
 *   "梦境渲染"借鉴了人类睡眠中记忆巩固 (Memory Consolidation) 的概念——
 *   大脑在睡眠中重放和强化白天的经历，同时清理无用突触连接。
 */
@Singleton
class DreamRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: MemoryGraphStore
) {
    companion object {
        private const val WORK_NAME = "cs_mem_dream_render"
        private const val DREAM_INTERVAL_MINUTES = 120L // 每2小时
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 调度梦境渲染周期性任务。
     * 应在 Application.onCreate() 中调用。
     */
    fun scheduleDreamRendering() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)          // 充电中
            .setRequiresBatteryNotLow(true)      // 电量不低于 20%
            .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi
            .build()

        val dreamWork = PeriodicWorkRequestBuilder<DreamWorker>(
            DREAM_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10, TimeUnit.MINUTES
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                dreamWork
            )
    }

    /**
     * 立即触发一次梦境渲染（用于手动测试）。
     */
    fun dreamNow(onComplete: ((DreamResult) -> Unit)? = null) {
        scope.launch {
            val result = performDreamCycle()
            onComplete?.invoke(result)
        }
    }

    /**
     * 执行一次完整的梦境渲染周期。
     */
    suspend fun performDreamCycle(): DreamResult {
        val result = DreamResult()
        val now = System.currentTimeMillis()

        // 1. 能量衰减：所有非晶化记忆指数衰减
        try {
            store.decayAllEnergy(decayFactor = 0.95f) // 每次衰减 5%
            result.energyDecayed = true
        } catch (e: Exception) {
            result.errors.add("Energy decay failed: ${e.message}")
        }

        // 2. 低能剪枝：删除能量低于阈值的记忆
        try {
            val pruned = store.pruneLowEnergy(energyThreshold = 0.05f)
            result.prunedCount = pruned
        } catch (e: Exception) {
            result.errors.add("Pruning failed: ${e.message}")
        }

        // 3. 宏技能保鲜检查：验证高频宏技能的有效性
        try {
            val topMacros = store.getTopMacros(limit = 10)
            // 修复：旧公式 (now - successCount * 86_400_000L) > 7*86_400_000L 把"次数"当成"毫秒时间戳"
            // 相减，几乎永远为真 → 所有宏都被误判过期。改为按 lastExecutedAt 时间戳比较，
            // 7 天内成功回放过的视为新鲜。lastExecutedAt=0（从未回放）视为已过期，触发保鲜降能。
            val staleMacros = topMacros.filter { macro ->
                (now - macro.lastExecutedAt) > 7 * 86_400_000L // 7天未成功回放
            }
            result.staleMacroCount = staleMacros.size
            // 降低过期宏技能的能量
            for (macro in staleMacros.take(3)) {
                store.recordMacroFailure(macro.skillId)
            }
        } catch (e: Exception) {
            result.errors.add("Macro validation failed: ${e.message}")
        }

        // 4. 拓扑同胚迁移：检测 App 版本变化 → 轻量属性相似度映射旧→新节点
        try {
            checkVersionMigration()?.let {
                result.migrationNotes = it
            }
        } catch (e: Exception) {
            result.errors.add("Version migration check failed: ${e.message}")
        }

        result.completedAt = now
        return result
    }

    /**
     * 检查宿主 App 版本是否较"上次已知版本"发生变化；若变化则调用 [TopologyMigrator]
     * 生成旧→新节点映射并落库，使跨版本记忆与 FSM 宏仍能复用。
     *
     * 实现说明：
     * - 版本来源：宿主自身 [Context.getPackageName] 的 versionName（记忆系统服务的对象主要是本 App UI）；
     * - 上一已知版本：store 中最近一条迁移的 toVersion（首跑为 null，跳过迁移）；
     * - 节点集：按版本分组从 store 取全部节点（旧版本组 vs 当前版本组）；
     * - 采用轻量属性相似度（非完整 VF2），分数 ≥ 0.7 才建别名桥，低置信度安全跳过。
     *
     * @return 可读的迁移摘要（审计用），无变化/首跑返回 null
     */
    private suspend fun checkVersionMigration(): String? {
        val pm = context.packageManager
        val packageName = context.packageName
        val currentVersion = try {
            pm.getPackageInfo(packageName, 0).versionName ?: return null
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }

        val lastKnown = store.latestKnownVersion()
        if (lastKnown == null) {
            // 首跑：无历史基线，写一条自映射（old==new==currentVersion）作为基线，
            // 使 latestKnownVersion 后续能取到当前版本，且不产生任何跨版本别名。
            store.recordMigration(listOf(
                com.apex.agent.platform.csmem.store.MigrationMap(
                    oldFingerprint = "__baseline__",
                    newFingerprint = "__baseline__",
                    matchScore = 1f,
                    fromVersion = currentVersion,
                    toVersion = currentVersion
                )
            ))
            return "baseline version recorded: $currentVersion (no migration)"
        }
        if (lastKnown == currentVersion) {
            return null // 版本未变，无需迁移
        }

        // 取旧版本组（lastKnown）与当前版本组（currentVersion）节点
        val oldNodes = store.getNodesByVersion(lastKnown)
        val newNodes = store.getNodesByVersion(currentVersion)
        if (oldNodes.isEmpty() || newNodes.isEmpty()) {
            return "version changed $lastKnown→$currentVersion but no comparable nodes"
        }

        val maps = TopologyMigrator.migrate(oldNodes, newNodes, lastKnown, currentVersion)
        store.recordMigration(maps)
        return if (maps.isEmpty()) {
            "version changed $lastKnown→$currentVersion: no isomorphic nodes found"
        } else {
            "migrated ${maps.size} nodes ($lastKnown→$currentVersion), " +
                "avg score=${"%.2f".format(maps.map { it.matchScore }.average())}"
        }
    }

    /**
     * 取消所有已调度的梦境渲染任务。
     */
    fun cancelDreamRendering() {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_NAME)
        scope.cancel()
    }
}

/**
 * 梦境渲染 Worker —— 在 WorkManager 后台执行。
 *
 * 通过 @HiltWorker + @AssistedInject 注入 [DreamRenderer]，在 doWork 中真正执行
 * performDreamCycle()（修复此前"此处简化实现"的未完成接线）。
 * 电量约束已由 PeriodicWorkRequest 的 Constraints（RequiresBatteryNotLow）保证，
 * 这里额外做一次运行时校验以防约束未生效。
 */
@HiltWorker
class DreamWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val dreamRenderer: DreamRenderer
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val batteryManager = applicationContext.getSystemService<BatteryManager>()
            val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100

            if (batteryLevel < MIN_BATTERY_LEVEL) {
                return Result.retry()
            }

            val result = dreamRenderer.performDreamCycle()
            if (result.errors.isNotEmpty()) {
                // 有非致命错误时仍标记为成功，避免 WorkManager 反复重试；
                // 错误明细已记录在 result.errors 中供排查。
                Result.success()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/**
 * 梦境渲染结果。
 */
data class DreamResult(
    var energyDecayed: Boolean = false,
    var prunedCount: Int = 0,
    var staleMacroCount: Int = 0,
    var migrationNotes: String? = null,
    var completedAt: Long = 0,
    val errors: MutableList<String> = mutableListOf()
)
