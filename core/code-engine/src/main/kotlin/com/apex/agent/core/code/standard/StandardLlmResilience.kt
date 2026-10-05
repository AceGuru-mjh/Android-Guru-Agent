package com.apex.agent.core.code.standard

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.llm.runtime.ModelRuntimeException
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.ToolRetrySchedules
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 标准线 LLM 请求容错（阶梯重试 + 请求级拒绝降级续跑）。
 *
 * 演进史：
 * - P1（PR #314）：SSE 中途断流且本轮无输出时直接上抛 —— 一次网络抖动
 *   杀掉整轮任务；接入 `EngineResilienceGuard` 指数退避（5 次）；
 * - 本版（用户规格收口）：Coding 标准线的 LLM 网络错误改走
 *   **用户规格递增阶梯**（复用 [ToolRetrySchedules]：第一次 2s、第二次
 *   5s、第三次 10s …… 逐级递增至 160s；下一级达 180s 封顶自动停止 ——
 *   与工具错误重试（PR #309）同一张表，语义一致）；并补上**请求级
 *   拒绝（400/404/配置/鉴权）的降级续跑**：重发同一请求体无意义，但
 *   任务不该死——**一次**去工具纯文本重发同一消息（「换种方式继续
 *   完成任务」），降级再败才诚实上抛。
 *
 * 行为矩阵（[run]）：
 * - 成功完成 → 正常返回；
 * - 已有部分输出时失败 → 追加中断说明后返回（诚实部分结果收尾 ——
 *   重放整轮会造成 UI 重复拼接，不重试）；
 * - 零输出 + 瞬时错误（限流/超时/断连/5xx/响应无效）→ 阶梯延迟后重试
 *   同一轮（零输出状态无需清空累积器）；阶梯穷尽 → 上抛（Error 事件
 *   由 execute 收口，任务收尾不悬挂）；
 * - 零输出 + 请求级拒绝 → 一次降级为纯文本重发（[attempt] 以
 *   `textOnly = true` 再入）；降级再败 → 上抛。
 *
 * 过程可见性：每次重试/降级发射 [AgentEvent.LlmRetryScheduled]（时间轴
 * 状态行，不弹错误横幅；与 [AgentEvent.Error] 的收口语义分工见其 KDoc）。
 *
 * @param retrySleeper 阶梯延迟执行器（测试注入记录器断言序列；生产默认
 *   真实 [delay]）。
 */
internal object StandardLlmResilience {

    /**
     * 带容错地执行一次流式请求循环。
     *
     * @param emit 事件发射器（与引擎同通道）
     * @param retrySleeper 阶梯延迟执行器（注入点）
     * @param hasPartialOutput 本轮是否已有部分输出（内容/思考/工具片段任一）
     * @param appendInterruptNote 部分输出收尾时的失败说明追加
     * @param attempt 一次流式请求（collect 向调用方闭包持有的累积器写输出）；
     *   请求级拒绝降级续跑时以 `textOnly = true` 再入（调用方据此把工具面
     *   换成空表——消息列表不动，不污染会话）
     */
    suspend fun run(
        emit: suspend (AgentEvent) -> Unit,
        retrySleeper: suspend (Long) -> Unit = { delay(it) },
        hasPartialOutput: () -> Boolean,
        appendInterruptNote: (String?) -> Unit,
        attempt: suspend (textOnly: Boolean) -> Unit
    ) {
        var retryIndex = 0
        var degradedToTextOnly = false
        while (true) {
            try {
                attempt(degradedToTextOnly)
                return
            } catch (e: CancellationException) {
                // 全仓纪律：协程取消必须继续抛出（用户 abort 语义）。
                throw e
            } catch (e: Exception) {
                if (hasPartialOutput()) {
                    appendInterruptNote(e.message)
                    return
                }
                when {
                    isRequestLevelRejection(e) && !degradedToTextOnly -> {
                        // 一次降级：同消息去工具纯文本重发（「换种方式继续」）。
                        degradedToTextOnly = true
                        emit(
                            AgentEvent.LlmRetryScheduled(
                                attempt = 0,
                                delayMs = 0L,
                                reason = "请求被服务端拒绝（${e.message}），本轮已降级为无工具纯文本继续"
                            )
                        )
                        AppLogger.instance.warn(
                            LogCategory.LLM, "StandardModeEngine",
                            "LLM request rejected (${e::class.simpleName}), " +
                                "degrading this turn to text-only: ${e.message}"
                        )
                    }
                    isTransientLlmFailure(e) &&
                        !ToolRetrySchedules.ladderExhausted(retryIndex) -> {
                        // 用户规格阶梯：2s/5s/10s/…/160s；下一级达 180s 封顶自动停止。
                        val delayMs = ToolRetrySchedules.ladderDelayMs(retryIndex)
                        emit(
                            AgentEvent.LlmRetryScheduled(
                                attempt = retryIndex + 1,
                                delayMs = delayMs,
                                reason = (e.message ?: e::class.simpleName ?: "unknown").take(80)
                            )
                        )
                        AppLogger.instance.warn(
                            LogCategory.LLM, "StandardModeEngine",
                            "LLM 瞬时失败（${e::class.simpleName}），阶梯 ${delayMs}ms 后" +
                                "重试（第 ${retryIndex + 1} 次）: ${e.message}"
                        )
                        retrySleeper(delayMs)
                        retryIndex++
                    }
                    else -> throw e
                    // 阶梯穷尽 / 降级后再败 / 未分类异常 → 诚实上抛
                }
            }
        }
    }

    // ═══════════════ LLM 请求错误分类 ═══════════════

    /**
     * 瞬时/网络类失败（阶梯重试候选）：Unavailable / RateLimited / Timeout /
     * ResponseInvalid + 纯 [IOException]（SingleClientModelRuntime 回退路径
     * 未经 DefaultModelRuntime 分类器）。鉴权失败重试无意义（Key 本身无效）；
     * 请求级 400 换时间重发仍是同一请求体；配置错误须用户修改设置 ——
     * 均走 [isRequestLevelRejection] 的降级路径。
     */
    private fun isTransientLlmFailure(e: Throwable): Boolean = when (e) {
        is ModelRuntimeException.ModelUnavailable,
        is ModelRuntimeException.ModelRateLimited,
        is ModelRuntimeException.ModelTimeout,
        is ModelRuntimeException.ModelResponseInvalid,
        is IOException -> true
        else -> false
    }

    /**
     * 请求级拒绝（重发同一请求体无意义）：400/404 请求拒绝 / 配置错误 /
     * 鉴权失败。处置 = 一次去工具降级续跑（「换种方式继续」），而非阶梯
     * 重试；降级再败才诚实上抛。
     */
    private fun isRequestLevelRejection(e: Throwable): Boolean = when (e) {
        is ModelRuntimeException.ModelRequestRejected,
        is ModelRuntimeException.ModelConfigurationError,
        is ModelRuntimeException.ProviderConfigurationError,
        is ModelRuntimeException.ModelAuthenticationFailed -> true
        else -> false
    }
}
