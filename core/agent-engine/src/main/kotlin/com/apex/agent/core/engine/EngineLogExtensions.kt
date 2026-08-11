package com.apex.agent.core.engine

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.logging.LogLevel

/**
 * 把引擎事件映射为结构化日志并写入日志中枢。
 *
 * 引擎事件是 UI 流式更新的载体，本身也是完整的运行轨迹：思考、规划、工具调用、
 * 上下文压缩、错误都被转成对应分类 / 级别的日志，使汇聚层覆盖 Agent 运行的
 * 全生命周期，无需在各业务点重复打点。放在 engine 模块是为了避免 logging 核心
 * 反向依赖 engine（否则会形成循环依赖）。
 */
fun AppLogger.logEvent(event: AgentEvent) {
    when (event) {
        is AgentEvent.ThinkingStart ->
            debug(LogCategory.ENGINE, "Engine", "思考开始 #${event.iteration} (level=${event.thinkingLevel})", "thinking")
        is AgentEvent.ThinkingComplete ->
            debug(LogCategory.ENGINE, "Engine", "思考完成 (${event.fullThought.length} 字)", "thinking")
        is AgentEvent.PlanGenerated ->
            info(LogCategory.ENGINE, "Engine", "生成计划: ${event.plan.steps.size} 步, 风险=${event.plan.riskLevel}", "plan")
        is AgentEvent.IterationStart ->
            info(LogCategory.ENGINE, "Engine", "迭代 #${event.iteration} 开始", "iteration")
        is AgentEvent.StepStart ->
            info(LogCategory.ENGINE, "Engine", "执行步骤 #${event.stepIndex}: ${event.description}", "step")
        is AgentEvent.ToolCallStart ->
            info(LogCategory.TOOL, event.toolName, "调用工具 args=${event.arguments.take(200)}", "tool:${event.toolName}", "call-start")
        is AgentEvent.ToolCallComplete ->
            if (event.success)
                info(LogCategory.TOOL, event.toolName, "完成 (${event.durationMs}ms) out=${event.output.length}字", tags = arrayOf("tool:${event.toolName}", "call-complete"))
            else
                error(LogCategory.TOOL, event.toolName, "失败 (${event.durationMs}ms): ${event.output.take(300)}", tags = arrayOf("tool:${event.toolName}", "call-error"))
        is AgentEvent.ToolProgress ->
            debug(LogCategory.TOOL, "Engine", "进度 ${event.percent?.let { "%.0f%%".format(it * 100) } ?: ""} ${event.message ?: ""}", "progress")
        is AgentEvent.ContextCompressed ->
            warn(LogCategory.ENGINE, "Compressor", "上下文压缩 ${event.beforeTokens}→${event.afterTokens} tokens, 策略=${event.strategy}, 移除=${event.messagesRemoved}", "compression")
        is AgentEvent.Error ->
            error(LogCategory.ENGINE, "Engine", event.message, tags = arrayOf("engine-error"))
        is AgentEvent.Complete ->
            info(LogCategory.ENGINE, "Engine", "完成: ${event.totalIterations} 迭代, ${event.totalToolCalls} 工具调用, ${event.totalDurationMs}ms", "complete")
        is AgentEvent.Aborted ->
            warn(LogCategory.ENGINE, "Engine", "任务中止", "aborted")
        else -> { /* 流式 chunk 类事件不落日志，避免刷屏 */ }
    }
}
