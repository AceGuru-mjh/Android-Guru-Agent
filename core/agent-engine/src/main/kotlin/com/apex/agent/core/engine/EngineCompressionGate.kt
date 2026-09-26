package com.apex.agent.core.engine

import com.apex.agent.core.engine.compression.TokenEstimator
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

/**
 * P7 — 上下文压缩触发门（自 [ApexAgentEngine.maybeCompressContext] 迁出，
 * SRP 预算腾挪；模式与 [EnginePromptDelegates] / EngineAskUserFlow 相同：
 * 同包顶层扩展 + internal 成员直调，引擎调用点零改动）。
 *
 * 每轮迭代开始时判定：估算 token 超过阈值 →
 * 1. #165 PreCompact 插桩（HookRegistry 可观测）；
 * 2. 压缩器收敛历史（preserveRecent 保护最近轮次）；
 * 3. 持久化 + 发射 [AgentEvent.ContextCompressed] 让 UI 感知。
 *
 * 失败路径绝不中断主流程，但必须留痕（否则每轮无日志地反复触发
 * 同一个失败的压缩器，且 UI 无法感知上下文已逼近上限）。
 */
internal suspend fun ApexAgentEngine.maybeCompressContext(emit: suspend (AgentEvent) -> Unit) {
    val compressor = contextCompressor ?: return

    val currentTokens = TokenEstimator.estimateHistory(conversationHistory)
    val thresholdTokens = (config.maxContextTokens *
        thinkingController.resolveCompressionThreshold(config.compressionThreshold)).toInt()

    if (currentTokens <= thresholdTokens) return

    // #165：PreCompact——自动压缩（阈值已判定）。
    sessionHooks?.onPreCompact()

    // 需要压缩
    val report = try {
        compressor.compress(
            history = conversationHistory,
            preserveRecent = config.preserveRecentTurns
        )
    } catch (e: Exception) {
        // 压缩失败不应该中断主流程，但必须留痕：否则每轮迭代都会无日志地
        // 反复触发同一个失败的压缩器，且 UI 无法感知上下文已逼近上限。
        AppLogger.instance.error(
            LogCategory.ENGINE, "ApexAgentEngine",
            "上下文压缩失败，本轮跳过压缩（tokens=${currentTokens}，阈值=${thresholdTokens}）: ${e.message}",
            e
        )
        return
    }

    // 同步到持久化记忆（如果存在）
    memory?.save(conversationHistory)

    // 发射压缩事件
    emit(
        AgentEvent.ContextCompressed(
            beforeTokens = report.beforeTokens,
            afterTokens = report.afterTokens,
            strategy = report.strategy.name,
            summary = report.summary.take(200),
            messagesRemoved = report.messagesRemoved,
            messagesTruncated = report.messagesTruncated
        )
    )
}
