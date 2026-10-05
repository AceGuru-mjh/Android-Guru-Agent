package com.apex.agent.core.engine

/**
 * #242：工具降级的用户可见提示文案。
 *
 * Provider 4xx 拒绝 tools 时引擎逐级降级（1=纯 CORE 精简工具、2=无工具纯
 * 对话），旧实现仅 AppLogger.warn —— 事件流上无任何信号，用户只觉得
 * Agent「突然变笨」（不再调用任何工具）。此处集中承载各降级等级的提示
 * 文案，主循环在 toolDegradationLevel++ 后经 [AgentEvent.ThinkingChunk]
 * 发射（与 LLM 瞬时错误重试提示同一风格与通道，UI 现成渲染）。
 */
object EngineDegradationNotice {

    /**
     * 降级等级 → 用户可见文案。
     *
     * @param level 1=精简工具（纯 CORE 无强制），2=无工具纯对话
     * @return 提示文本；未知等级返回 null（调用方不发射任何事件）
     */
    fun noticeFor(level: Int): String? = when (level) {
        1 -> "⚠ 服务端拒绝了完整工具集，已降级为精简工具模式重试\n"
        2 -> "⚠ 服务端持续拒绝工具调用，本轮已降级为纯对话（无工具）" +
            "——若持续出现请检查模型/网关是否支持函数调用\n"
        else -> null
    }
}
