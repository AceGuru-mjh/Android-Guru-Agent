package com.apex.agent.core.tools.hook

import com.apex.agent.core.tools.ToolArguments

/**
 * # Issue #165 — Hooks 钩子系统：钩子接口与结果
 *
 * [ToolHook] 是所有钩子的统一抽象：一个 id（幂等注册/注销的键）加一个
 * 挂起的 [onEvent]。钩子可以编程式注册（[HookRegistry.register]，进程内
 * 生效），也可以声明式持久化（hooks.json，经 [DeclarativeHook] 实例化）。
 *
 * [HookOutcome] 是单钩子对事件的表态；[HookDispatchResult] 是整个注册表
 * 派发一轮后的聚合结论（执行器据此拦截或改写参数）。
 */
interface ToolHook {

    /** 钩子唯一标识：注册表按它做幂等替换与 [HookRegistry.unregister] 注销。 */
    val id: String

    /**
     * 处理一个事件。
     *
     * 实现方注意：
     * - 单钩子异常会被 [HookRegistry.dispatch] 捕获隔离（记入 errorLog，
     *   链继续）——这是刻意设计：一条坏钩子不得瘫痪整个工具管线；
     * - 钩子内部取消（如自建 withTimeout 超时）同样被隔离，但**调用方
     *   协程已取消时取消必须传播**（dispatch 内部以协程活跃度判别）；
     * - 尽量快速返回：PreToolUse 钩子串行位于工具执行热路径上。
     */
    suspend fun onEvent(event: HookEvent): HookOutcome
}

/**
 * 单钩子对事件的表态。
 *
 * - [Pass]：放行/仅观察，链继续。
 * - [Blocked]：**仅对 [HookEvent.PreToolUse] 有意义**——拦截本次工具执行，
 *   [reason] 会透传给模型（格式对齐 gate 拒绝：`Error: permission denied: …`）。
 *   其他事件上返回 Blocked 按设计视为 Pass（会话/输入类事件没有「拦截后
 *   改道」的合理语义——输入已经发生，事后否决只会造成状态分裂）。
 * - [Modified]：**仅对 [HookEvent.PreToolUse] 有意义**——用新参数替换原
 *   参数走后续管线（schema 校验按新参数执行）。其余事件上返回 Modified
 *   被忽略（没有可改写的载荷）。
 *
 * 多个钩子的合并语义（[HookRegistry.dispatch]）：首个 Blocked 胜出并短路
 * （对齐 CompositeToolGate 的「首个 Deny 胜出」）；Modified 链式传递——
 * 后续钩子看到的是前面钩子改写后的参数，最终改写以最后一个为准。
 */
sealed interface HookOutcome {
    object Pass : HookOutcome {
        override fun toString(): String = "Pass"
    }

    /** 拦截（仅 PreToolUse 有意义；其余事件按 Pass 处理）。 */
    data class Blocked(val reason: String) : HookOutcome

    /** 参数改写（仅 PreToolUse 有意义；其余事件忽略）。 */
    data class Modified(val args: ToolArguments) : HookOutcome
}

/**
 * 一次 [HookRegistry.dispatch] 的聚合结论。
 *
 * @param blocked 是否有 PreToolUse 钩子拦截（true 时 [blockReason] 非空）。
 * @param blockReason 拦截原因（透传给模型的文案）。
 * @param modifiedArgs 链上最后一个改写钩子产出的新参数；null = 无改写。
 *   仅 PreToolUse 派发下可能非空。
 * @param firedCount 实际触发（收到事件）的钩子数——含抛异常被隔离的钩子，
 *   不含被 pattern/事件类型过滤掉的声明式钩子。执行器侧的桥接层用它区分
 *   「无钩子关心」（=0，可短路跳过后续判定）与「有钩子放行」。
 */
data class HookDispatchResult(
    val blocked: Boolean = false,
    val blockReason: String? = null,
    val modifiedArgs: ToolArguments? = null,
    val firedCount: Int = 0
) {
    /** 无任何钩子关心本次事件（执行器桥据此返回 null 走原路径）。 */
    val isNoOp: Boolean get() = firedCount == 0
}
