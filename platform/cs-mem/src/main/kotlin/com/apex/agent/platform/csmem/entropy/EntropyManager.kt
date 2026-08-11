package com.apex.agent.platform.csmem.entropy

import com.apex.agent.platform.csmem.store.MemoryGraphStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 熵管理器 —— 记忆的热力学熵增与遗忘机制。
 *
 * 核心原理：
 *   - 每条记忆具有"能量值 (Energy)"，取值范围 [0.01, 10.0]
 *   - 成功检索/执行 → 能量增加（热力学中的"能量注入"）
 *   - 时间推移 → 能量指数衰减（热力学第二定律：封闭系统熵增）
 *   - 能量 < 阈值 → 记忆坍缩 (Memory Collapse)，被删除
 *   - 高频 FSM → 晶化 (Crystallized)，固化为 ROM 级别配置
 *
 * 这模拟了人类记忆的"遗忘曲线"——艾宾浩斯遗忘曲线上，
 * 不复习的记忆随时间指数衰减，而被频繁使用的则转化为长期记忆。
 */
@Singleton
class EntropyManager @Inject constructor(
    private val store: MemoryGraphStore
) {
    /** 默认衰减因子（每次衰减保留的比例） */
    var defaultDecayFactor: Float = 0.95f

    /** 能量断层：能量低于此值触发记忆坍缩 */
    var collapseThreshold: Float = 0.05f

    /** 晶化阈值：能量高于此值的 FSM 自动晶化 */
    var crystallizeThreshold: Float = 8.0f

    /** 检索能量加成（成功执行一次） */
    var retrievalBoost: Float = 0.2f

    /** 创建能量加成（新记忆初始能量） */
    var initialEnergy: Float = 1.0f

    /** 失败惩罚（执行失败一次） */
    var failurePenalty: Float = 0.5f // 乘以 0.5

    /**
     * 执行一次完整的记忆维护周期：
     * 1. 全局衰减
     * 2. 坍缩剪枝
     *
     * @return 维护结果统计
     */
    suspend fun performMaintenanceCycle(): MaintenanceResult {
        val startTime = System.currentTimeMillis()

        // 1. 全局衰减
        store.decayAllEnergy(defaultDecayFactor)

        // 2. 坍缩剪枝
        val pruned = store.pruneLowEnergy(collapseThreshold)

        return MaintenanceResult(
            decayFactor = defaultDecayFactor,
            prunedItems = pruned,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 计算一条记忆被遗忘的概率（艾宾浩斯遗忘曲线近似）。
     *
     * @param energy 当前能量值
     * @param daysSinceLastUse 距末次使用天数
     * @return 遗忘概率 [0, 1]，越高越可能被遗忘
     */
    fun forgettingProbability(energy: Float, daysSinceLastUse: Int): Float {
        // 艾宾浩斯曲线近似: R = e^(-t/S)
        // 其中 t = 天数, S = 相对记忆强度（能量越高越难忘）
        val strength = energy.coerceIn(0.1f, 10f)
        val retention = kotlin.math.exp(-daysSinceLastUse.toFloat() / strength)
        return (1f - retention).coerceIn(0f, 1f)
    }

    /**
     * 判断一个宏技能是否应该被晶化。
     *
     * 晶化条件：
     *   - 能量 >= 8.0
     *   - 成功执行 >= 10 次
     *   - 成功率 >= 90%
     */
    fun shouldCrystallize(
        energy: Float,
        successCount: Int,
        failureCount: Int
    ): Boolean {
        if (energy < crystallizeThreshold) return false
        if (successCount < 10) return false
        val total = successCount + failureCount
        if (total == 0) return false
        val successRate = successCount.toFloat() / total.toFloat()
        return successRate >= 0.9f
    }
}

/**
 * 记忆维护结果。
 */
data class MaintenanceResult(
    val decayFactor: Float,
    val prunedItems: Int,
    val durationMs: Long
)
