package com.apex.agent.core.engine

import com.apex.agent.core.engine.compression.TokenEstimator
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.Usage
import java.util.concurrent.atomic.AtomicLong

/**
 * 真实用量统计（自 [ApexAgentEngine] 迁出，God-file 预算拆分；用户反馈
 * 「已用 token 像假的、用完还是 0」的根因修复载体）。
 *
 * 背景：流式路径此前完全不解析 usage —— OpenAI 协议流式默认不带统计，
 * 仪表盘只能拿 [TokenEstimator] 的启发式估算充数，且仅在 Complete 时刷新。
 * 现在：请求体带 stream_options.include_usage（客户端层），流尾统计帧
 * 解析进 LlmStreamChunk.usage，本类记录最近一次真实值并在每轮结束产出
 * [AgentEvent.UsageUpdated]，仪表盘显示服务端返回的真实 token 数。
 *
 * 线程模型：track/accumulate 在流收集协程调用，currentContextTokens /
 * sessionTotalTokens 由 UI 线程读取 —— lastRealUsage 用 @Volatile、
 * 会话累计用 [AtomicLong]，均为单写多读安全。
 */
internal class EngineUsageTracker(
    /** 回退估算所需的历史快照（惰性求值，仅在无真实 usage 时调用）。 */
    private val history: () -> List<LlmMessage>
) {

    /** 最近一次 LLM 响应携带的真实 usage（null = 端点未返回统计）。 */
    @Volatile
    private var lastRealUsage: Usage? = null

    private val sessionTotalTokensReal = AtomicLong(0)

    /** 记录流帧携带的 usage（仅接受 totalTokens>0 的有效统计）。 */
    fun track(u: Usage?) {
        if (u != null && u.totalTokens > 0) lastRealUsage = u
    }

    /**
     * 当前上下文 token 数（UI 仪表盘）：
     * 优先返回最近一次响应的**真实**统计（prompt+completion ≈ 压缩后全上下文），
     * 端点不返回 usage 时回退 [TokenEstimator] 启发式估算（与压缩阈值同源）。
     */
    fun currentContextTokens(): Int {
        val real = lastRealUsage
        if (real != null && real.totalTokens > 0) return real.totalTokens
        return TokenEstimator.estimateHistory(history())
    }

    /** 会话累计消耗的真实 token（多轮累加；0 = 尚无统计）。 */
    fun sessionTotalTokens(): Long = sessionTotalTokensReal.get()

    /**
     * 累加一轮真实 usage，返回应发射的仪表盘事件（无有效统计时返回
     * null，调用方免判空）。同时更新最近真实值与会话累计。
     */
    fun accumulate(u: Usage?): AgentEvent.UsageUpdated? {
        if (u == null || u.totalTokens <= 0) return null
        track(u)
        sessionTotalTokensReal.addAndGet(u.totalTokens.toLong())
        return AgentEvent.UsageUpdated(u.promptTokens, u.completionTokens, u.totalTokens)
    }
}
