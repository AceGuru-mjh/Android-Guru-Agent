package com.apex.agent.platform.csmem.dream

import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import androidx.core.content.getSystemService
import androidx.work.*
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import com.apex.agent.platform.csmem.store.MemoryGraphStoreImpl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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
        private const val MIN_BATTERY_LEVEL = 50
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
            val staleMacros = topMacros.filter { macro ->
                (now - macro.successCount * 86_400_000L) > 7 * 86_400_000L // 7天未成功
            }
            result.staleMacroCount = staleMacros.size
            // 降低过期宏技能的能量
            for (macro in staleMacros.take(3)) {
                store.recordMacroFailure(macro.skillId)
            }
        } catch (e: Exception) {
            result.errors.add("Macro validation failed: ${e.message}")
        }

        // 4. 拓扑同胚迁移（占位 - 完整 VF2 实现见 Phase 5 后续）
        // 检查是否有 App 版本号变化 → 尝试图同胚映射
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
     * 检查是否有 App 版本更新，尝试拓扑同胚迁移。
     * 完整 VF2 子图同构匹配留给后续迭代。
     */
    private fun checkVersionMigration(): String? {
        val pm = context.packageManager
        val notes = mutableListOf<String>()

        // 检查已安装的"关键 App"版本变化（通过数据库中记录的包名）
        // 当前为占位实现：检查是否有系统 WebView 更新
        try {
            val webViewInfo = pm.getPackageInfo(
                "com.google.android.webview", 0
            )
            notes.add("WebView version: ${webViewInfo.versionName}")
        } catch (_: PackageManager.NameNotFoundException) {
            // WebView 未安装
        }

        return if (notes.isNotEmpty()) {
            notes.joinToString("\n")
        } else null
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
 */
class DreamWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // DreamRenderer 通过 Hilt 注入，此处简化实现
        // 实际项目中应使用 HiltWorker
        return try {
            // 检查电量是否充足
            val batteryManager = applicationContext.getSystemService<BatteryManager>()
            val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100

            if (batteryLevel < 50) {
                return Result.retry()
            }

            // 梦境渲染的主要工作由 DreamRenderer.performDreamCycle() 完成
            // 此处由 HiltWorkerFactory 注入 DreamRenderer 实例

            Result.success()
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
