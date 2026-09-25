package com.apex.agent.core.tools.hook

import com.apex.agent.core.tools.ToolArguments

/**
 * # Issue #165 — Hooks 钩子系统：事件定义
 *
 * 通用钩子事件总线的事件载荷。对标 Claude Code hooks 的八类事件，
 * 覆盖工具调用（前/后）与代理会话生命周期的全部关键切面：
 *
 *  - **PreToolUse / PostToolUse**：单次工具调用的前/后介入点。
 *    PreToolUse 是唯一能改写参数（[HookOutcome.Modified]）或拦截执行
 *    （[HookOutcome.Blocked]）的事件；PostToolUse 仅供观察（审计日志、
 *    事后清理）。
 *  - **UserPromptSubmit**：用户原始输入进入引擎管线之前（区别于工具
 *    结果回灌——工具结果不进入用户输入管线）。
 *  - **SessionStart / SessionEnd**：会话首条输入与会话清理（引擎
 *    clearHistory）。
 *  - **Stop**：一次 execute 回合（用户输入 → ReAct 循环 → 最终回复）
 *    正常结束；错误/中止路径不触发（与 Claude Code 的 Stop 语义一致）。
 *  - **SubagentStop**：子代理（code_task）回合结束，由装配层在其工具
 *    完成处派发，引擎主循环不经手。
 *  - **PreCompact**：上下文压缩即将发生（自动阈值触发与手动压缩共用）。
 *
 * 设计约束：本包位于 core:tool-registry（纯 JVM、零 Android 依赖），
 * 因为 [ToolArguments] 与工具执行管线都在这里；core:agent-engine 已
 * 依赖 tool-registry（依赖方向查证见 worklog Task 2-c），引擎侧可直接
 * 引用本类型，无需反转桥。
 */
sealed interface HookEvent {

    /** 工具即将执行（gate 已放行之后、schema 校验之前派发）。 */
    data class PreToolUse(
        val toolId: String,
        val args: ToolArguments
    ) : HookEvent

    /** 工具执行完成：成功与异常路径都派发（异常时 [isError]=true）。 */
    data class PostToolUse(
        val toolId: String,
        val args: ToolArguments,
        val result: String,
        val isError: Boolean,
        val durationMs: Long
    ) : HookEvent

    /** 用户输入进入引擎管线之前（非工具结果回灌）。 */
    data class UserPromptSubmit(val prompt: String) : HookEvent

    /** 会话开始：会话首条用户输入进入时派发。 */
    data class SessionStart(val sessionId: String, val mode: String) : HookEvent

    /** 会话结束：会话清理/重置处派发。 */
    data class SessionEnd(val sessionId: String) : HookEvent

    /** 一次 execute 回合正常完成（每轮 Agent 回合结束）。 */
    data class Stop(val sessionId: String) : HookEvent

    /** 子代理（code_task）回合结束。 */
    data class SubagentStop(val sessionId: String, val subagentId: String) : HookEvent

    /** 上下文压缩即将发生。 */
    data class PreCompact(val sessionId: String) : HookEvent
}

/** 钩子事件类型枚举：[HookRegistry.HookConfig.event] 的持久化形态。 */
enum class HookEventType {
    PRE_TOOL_USE,
    POST_TOOL_USE,
    USER_PROMPT_SUBMIT,
    SESSION_START,
    SESSION_END,
    STOP,
    SUBAGENT_STOP,
    PRE_COMPACT
}

/**
 * 事件 → 类型枚举的映射（[HookRegistry] 过滤声明式钩子用）。
 * 独立扩展函数而非接口属性：保持各事件 data class 形状最小（只含载荷），
 * 映射逻辑集中一处，新增事件类型时编译器会在这里强制补全。
 */
fun HookEvent.eventType(): HookEventType = when (this) {
    is HookEvent.PreToolUse -> HookEventType.PRE_TOOL_USE
    is HookEvent.PostToolUse -> HookEventType.POST_TOOL_USE
    is HookEvent.UserPromptSubmit -> HookEventType.USER_PROMPT_SUBMIT
    is HookEvent.SessionStart -> HookEventType.SESSION_START
    is HookEvent.SessionEnd -> HookEventType.SESSION_END
    is HookEvent.Stop -> HookEventType.STOP
    is HookEvent.SubagentStop -> HookEventType.SUBAGENT_STOP
    is HookEvent.PreCompact -> HookEventType.PRE_COMPACT
}
