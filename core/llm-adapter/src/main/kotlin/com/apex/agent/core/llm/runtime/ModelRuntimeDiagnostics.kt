package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.ModelRole
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * T72 §十五 / §十六 — 运行时诊断。
 *
 * 记录每个 Profile 的累计请求 / 成功 / 失败 / 降级 / 总时延 / 最近错误，
 * 以及每次请求的 trace（requestId / taskId / stepId / role / profileId /
 * modelId / attempt / fallback / latency / result）。
 *
 * **安全保证**：绝不记录 API Key、完整 prompt 或完整 response。错误信息仅保留
 * 异常类型与短消息（最多 200 字符），避免泄露用户数据。
 */
class ModelRuntimeDiagnostics {

    /** 单个 Profile 的累计统计快照（§十五）。 */
    data class ModelRuntimeSnapshot(
        val profileId: String,
        val providerId: String,
        val modelId: String,
        val role: ModelRole,        // 最近一次使用的角色
        val requestCount: Long,
        val successCount: Long,
        val failureCount: Long,
        val fallbackCount: Long,
        val totalLatencyMs: Long,
        val lastError: String?,
        val lastUsedAt: Long,       // epoch millis；0 表示从未使用
    )

    /** 单次请求的 trace（§十六）。 */
    data class ModelRequestTrace(
        val requestId: String,
        val taskId: String?,
        val stepId: String?,
        val role: ModelRole,
        val profileId: String,
        val providerId: String,
        val modelId: String,
        val attempt: Int,           // 该请求内的第几次尝试（0-based）
        val fallback: Int,          // 该请求内第几次降级（0 表示首选 Profile）
        val latencyMs: Long,
        val result: String,         // "SUCCESS" | "FAILURE:<ErrorSimpleClassName>:<short message>"
    )

    private data class ProfileStats(
        val profileId: String,
        val providerId: String,
        val modelId: String,
        @Volatile var role: ModelRole = ModelRole.PRIMARY,
        val requestCount: AtomicLong = AtomicLong(0),
        val successCount: AtomicLong = AtomicLong(0),
        val failureCount: AtomicLong = AtomicLong(0),
        val fallbackCount: AtomicLong = AtomicLong(0),
        val totalLatencyMs: AtomicLong = AtomicLong(0),
        @Volatile var lastError: String? = null,
        @Volatile var lastUsedAt: Long = 0L,
    )

    private val stats = ConcurrentHashMap<String, ProfileStats>()

    /** 记录一次尝试（无论成功/失败）。 */
    fun recordAttempt(
        profileId: String,
        providerId: String,
        modelId: String,
        role: ModelRole,
        latencyMs: Long,
        fallback: Int,
        success: Boolean,
        error: Throwable? = null,
    ): ModelRequestTrace {
        val s = stats.computeIfAbsent(profileId) {
            ProfileStats(profileId, providerId, modelId)
        }
        s.requestCount.incrementAndGet()
        s.totalLatencyMs.addAndGet(latencyMs)
        s.role = role
        s.lastUsedAt = System.currentTimeMillis()
        if (success) {
            s.successCount.incrementAndGet()
        } else {
            s.failureCount.incrementAndGet()
            s.lastError = sanitizeError(error)
        }
        if (fallback > 0) s.fallbackCount.incrementAndGet()
        return ModelRequestTrace(
            requestId = "",        // 由调用方在记录后填入（见 DefaultModelRuntime）
            taskId = null,
            stepId = null,
            role = role,
            profileId = profileId,
            providerId = providerId,
            modelId = modelId,
            attempt = fallback,
            fallback = fallback,
            latencyMs = latencyMs,
            result = if (success) "SUCCESS" else "FAILURE:${error?.let { it::class.simpleName ?: it::class.java.simpleName } ?: "Unknown"}:${(error?.message ?: "").take(120)}",
        )
    }

    /** 全部 Profile 的快照。 */
    fun snapshot(): List<ModelRuntimeSnapshot> = stats.values.map {
        ModelRuntimeSnapshot(
            profileId = it.profileId,
            providerId = it.providerId,
            modelId = it.modelId,
            role = it.role,
            requestCount = it.requestCount.get(),
            successCount = it.successCount.get(),
            failureCount = it.failureCount.get(),
            fallbackCount = it.fallbackCount.get(),
            totalLatencyMs = it.totalLatencyMs.get(),
            lastError = it.lastError,
            lastUsedAt = it.lastUsedAt,
        )
    }

    /** 重置全部统计（测试用）。 */
    fun reset() {
        stats.clear()
    }

    private fun sanitizeError(error: Throwable?): String? {
        if (error == null) return null
        // 仅保留异常类名 + 短消息，绝不保留 stacktrace / 完整响应体
        val cls = error::class.simpleName ?: error::class.java.simpleName
        val msg = (error.message ?: "").take(200)
        return "$cls: $msg"
    }
}
