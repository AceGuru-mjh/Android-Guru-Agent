package com.apex.agent.core.code.standard

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.EngineResilienceGuard
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * P1 修复（标准线韧性）：Coding 标准线的 LLM 瞬时错误退避重试助手。
 *
 * 旧实现（StandardModeEngine 主循环内联）：SSE 中途断流且本轮尚无输出时
 * 直接把异常上抛 —— 一次网络抖动杀掉整轮任务；已有部分输出时追加失败
 * 说明收尾。与 Agent 屏（ApexAgentEngine + EngineResilienceGuard 退避
 * 重试 5 次）行为不一致，是「Coding 屏失败率高」的直接根因。
 *
 * 现在零输出轮次的瞬时错误（限流/超时/断连/5xx）复用引擎同款守卫做
 * 指数退避重试；已有部分输出时保持旧行为（追加失败说明后诚实收尾 ——
 * 重放整轮会造成 UI 重复拼接）。抽出为独立文件遵循仓库的职责缝拆分
 * 纪律（主文件在 1200 行预算边缘，见 check_file_size.sh 门禁）。
 */
internal object StandardLlmResilience {

    /**
     * 带韧性地执行一次流式请求循环。
     *
     * - 成功完成 → 正常返回；
     * - [hasPartialOutput] 为真 → 追加中断说明后返回（诚实部分结果收尾）；
     * - 零输出且瞬时错误 → 指数退避后重试同一轮（预算见
     *   [EngineResilienceGuard]；重试仅发生在零输出状态，无需清空累积器）；
     * - 零输出且非瞬时/预算耗尽 → 上抛（Error 事件由 execute 收口）。
     *
     * @param attempt 一次流式请求（collect 向调用方闭包持有的累积器写输出）
     * @param hasPartialOutput 本轮是否已有部分输出（内容/思考/工具片段任一）
     * @param appendInterruptNote 部分输出收尾时的失败说明追加
     */
    suspend fun run(
        guard: EngineResilienceGuard,
        emit: suspend (AgentEvent) -> Unit,
        hasPartialOutput: () -> Boolean,
        appendInterruptNote: (String?) -> Unit,
        attempt: suspend () -> Unit
    ) {
        while (true) {
            try {
                attempt()
                return
            } catch (e: CancellationException) {
                // 全仓纪律：协程取消必须继续抛出（用户 abort 语义）。
                throw e
            } catch (e: Exception) {
                if (hasPartialOutput()) {
                    appendInterruptNote(e.message)
                    return
                }
                when (val decision = guard.onLlmFailure(e)) {
                    is EngineResilienceGuard.LlmRetryDecision.Retry -> {
                        AppLogger.instance.warn(
                            LogCategory.LLM, "StandardModeEngine",
                            "LLM 瞬时失败（${e::class.simpleName}），退避 ${decision.delayMs}ms 后" +
                                "重试（${decision.attempt}/${guard.policy.maxLlmRetries}）: ${e.message}"
                        )
                        emit(
                            AgentEvent.ThinkingChunk(
                                "[engine] 模型暂时不可用（${e::class.simpleName ?: "error"}）— " +
                                    "${decision.delayMs / 1000.0}s 后自动重试\n"
                            )
                        )
                        delay(decision.delayMs)
                    }
                    is EngineResilienceGuard.LlmRetryDecision.Stop -> throw e
                }
            }
        }
    }
}
