package com.apex.agent.ui.screen.market

import com.apex.agent.core.tools.ToolCircuitBreaker
import com.apex.agent.core.tools.ToolTraceRecorder
import com.apex.agent.core.tools.ToolUsageTracker
import com.apex.agent.core.tools.skill.SkillManifest
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.platform.csmem.store.FSMMacro
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * # Skill Analytics 投影器
 *
 * 把散落在四个单例（[MemoryGraphStore] / [ToolUsageTracker] /
 * [ToolCircuitBreaker] / [ToolTraceRecorder]）中的技能健康数据，
 * 聚合成单个 [SkillDetailUiState]，供市场详情对话框一次性渲染。
 *
 * 这是「认知市场」的数据枢纽 ——
 * - cs-mem 的 [FSMMacro] 提供 energy / successCount / failureCount /
 *   isCrystallized / lastExecutedAt（技能是否被频繁使用、是否结晶为可跳过 LLM 的确定性宏）；
 * - [ToolUsageTracker] 提供每个工具的调用次数 / 成功率 / 均耗时；
 * - [ToolCircuitBreaker] 提供熔断状态（连续失败次数 / 冷却剩余）；
 * - [ToolTraceRecorder] 提供最近 N 次调用轨迹（参数已脱敏，仅长度+指纹）。
 *
 * 纯函数式聚合，无副作用，可在 IO 线程安全调用。
 */
class SkillAnalytics(
    private val memoryGraphStore: MemoryGraphStore,
    private val usageTracker: ToolUsageTracker,
    private val circuitBreaker: ToolCircuitBreaker,
    private val traceRecorder: ToolTraceRecorder
) {

    /**
     * 投影单个技能的完整分析状态。
     *
     * @param skillId 技能 id（== manifest.id == FSMMacro.skillId）
     * @param manifest 技能清单（用于枚举其 tools[].id）
     */
    suspend fun projectSkillAnalytics(
        skillId: String,
        manifest: SkillManifest?
    ): SkillDetailUiState = coroutineScope {
        // 1. cs-mem 宏健康数据（energy / 结晶 / 成功失败计数 / 最近执行）
        val macro = async { memoryGraphStore.getMacroBySkillId(skillId) }

        // 2. 每个 tool 的使用统计
        val toolIds = manifest?.tools?.map { it.id }.orEmpty()
        val toolStats = async {
            toolIds.mapNotNull { id -> usageTracker.statFor(id)?.let { id to it } }
        }

        // 3. 熔断状态
        val breakerStates = async {
            toolIds.map { id ->
                ToolBreakerState(
                    toolId = id,
                    isOpen = circuitBreaker.isOpen(id),
                    state = circuitBreaker.stateFor(id).name,
                    failureCount = circuitBreaker.failureCount(id)
                )
            }
        }

        // 4. 最近轨迹（每个工具最多 5 条，合并后按时间倒序取前 10）
        val traces = async {
            toolIds.flatMap { id ->
                traceRecorder.spansFor(id).take(5).map { id to it }
            }.sortedByDescending { it.second.startedAtEpochMs }.take(10)
        }

        val m = macro.await()
        val stats = toolStats.await()
        val breakers = breakerStates.await()
        val spans = traces.await()

        // 聚合技能级统计（所有工具之和）
        val totalInvocations = stats.sumOf { it.second.invocations }
        val totalSuccesses = stats.sumOf { it.second.successes }
        val totalFailures = stats.sumOf { it.second.failures }
        val anyBreakerOpen = breakers.any { it.isOpen }
        val successRate = if (totalInvocations == 0) 0.0
        else totalSuccesses.toDouble() / totalInvocations

        SkillDetailUiState(
            skillId = skillId,
            macro = m?.let { MacroHealth(
                energy = it.energy,
                isCrystallized = it.isCrystallized,
                successCount = it.successCount,
                failureCount = it.failureCount,
                lastExecutedAt = it.lastExecutedAt,
                transitions = it.transitions.size
            )},
            totalInvocations = totalInvocations,
            totalSuccesses = totalSuccesses,
            totalFailures = totalFailures,
            successRate = successRate,
            anyBreakerOpen = anyBreakerOpen,
            toolStats = stats.map { (id, s) ->
                ToolUsageRow(
                    toolId = id,
                    invocations = s.invocations,
                    successes = s.successes,
                    failures = s.failures,
                    successRate = s.successRate,
                    meanDurationMs = s.meanDurationMs,
                    lastUsedAt = s.lastUsedAtEpochMs,
                    isOpen = circuitBreaker.isOpen(id),
                    failureCount = circuitBreaker.failureCount(id)
                )
            },
            breakers = breakers,
            traces = spans.map { (id, span) ->
                TraceRow(
                    toolId = id,
                    callId = span.callId,
                    startedAt = span.startedAtEpochMs,
                    durationMs = span.durationMs,
                    outcome = span.outcome.name,
                    attempt = span.attempt,
                    argsDigest = span.argsDigest,
                    errorSlug = span.errorSlug
                )
            }
        )
    }

    /**
     * 批量投影：为已安装技能列表的每一项快速计算 energy + lastUsedAt + isCrystallized。
     * 比 [projectSkillAnalytics] 轻量 —— 只取 cs-mem 宏的四个字段，不查工具级数据。
     */
    suspend fun batchProjectSkillHealth(
        skillIds: List<String>
    ): Map<String, SkillHealthBrief> = coroutineScope {
        val deferred = skillIds.map { id -> async { id to memoryGraphStore.getMacroBySkillId(id) } }
        deferred.associate { it.await() }
            .mapValues { (_, macro) ->
                SkillHealthBrief(
                    energy = macro?.energy ?: 1.0f,
                    isCrystallized = macro?.isCrystallized ?: false,
                    lastUsedAt = macro?.lastExecutedAt ?: 0,
                    successCount = macro?.successCount ?: 0,
                    failureCount = macro?.failureCount ?: 0
                )
            }
    }

    /**
     * 解析技能清单文本为 [SkillManifest]（不写入磁盘）。
     * 失败返回 null。供详情对话框展示 manifest 详情使用。
     */
    fun parseManifest(content: String): SkillManifest? = runCatching {
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(SkillManifest.serializer(), content)
    }.getOrNull()

    /**
     * 读取已安装技能的 manifest 原文（用于详情页展示完整 manifest）。
     */
    suspend fun readInstalledManifest(
        skillRegistry: SkillRegistry,
        skillId: String
    ): SkillManifest? = coroutineScope {
        skillRegistry.getInstalled().firstOrNull { it.manifest.id == skillId }?.manifest
    }
}

// ═══ 详情 UI 状态模型 ═══

/** 单个技能的完整分析投影（详情对话框渲染用）。 */
data class SkillDetailUiState(
    val skillId: String,
    /** cs-mem 宏健康数据。null = 该技能尚未被蒸馏为宏（未在 cs-mem 中）。 */
    val macro: MacroHealth?,
    val totalInvocations: Int,
    val totalSuccesses: Int,
    val totalFailures: Int,
    /** 0.0..1.0。 */
    val successRate: Double,
    val anyBreakerOpen: Boolean,
    val toolStats: List<ToolUsageRow>,
    val breakers: List<ToolBreakerState>,
    val traces: List<TraceRow>
) {
    /** 是否有任何调用记录。 */
    val hasUsage: Boolean get() = totalInvocations > 0
}

/** cs-mem 宏健康摘要（详情页与卡片列表共用）。 */
data class MacroHealth(
    val energy: Float,
    val isCrystallized: Boolean,
    val successCount: Int,
    val failureCount: Int,
    val lastExecutedAt: Long,
    val transitions: Int
) {
    /** 成功率 0..1。 */
    val successRate: Float get() =
        if (successCount + failureCount == 0) 0f
        else successCount.toFloat() / (successCount + failureCount)
}

/** 卡片级轻量健康摘要（MarketSkillRow.energy 用）。 */
data class SkillHealthBrief(
    val energy: Float,
    val isCrystallized: Boolean,
    /** ms epoch；0 = 从未执行。 */
    val lastUsedAt: Long,
    val successCount: Int,
    val failureCount: Int
)

/** 单个工具的使用统计行。 */
data class ToolUsageRow(
    val toolId: String,
    val invocations: Int,
    val successes: Int,
    val failures: Int,
    val successRate: Double,
    val meanDurationMs: Double,
    val lastUsedAt: Long,
    val isOpen: Boolean,
    val failureCount: Int
)

/** 单个工具的熔断状态。 */
data class ToolBreakerState(
    val toolId: String,
    val isOpen: Boolean,
    val state: String,
    val failureCount: Int
)

/** 单次调用轨迹（参数已脱敏）。 */
data class TraceRow(
    val toolId: String,
    val callId: Long,
    val startedAt: Long,
    val durationMs: Long,
    val outcome: String,
    val attempt: Int,
    val argsDigest: String,
    val errorSlug: String?
)
