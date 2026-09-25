package com.apex.agent.core.engine

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.hook.HookDispatchResult
import com.apex.agent.core.tools.hook.HookEvent
import com.apex.agent.core.tools.hook.HookRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * # Issue #165 — 引擎侧钩子派发口
 *
 * [ApexAgentEngine] 通过本接口派发生命周期钩子事件（SessionStart /
 * UserPromptSubmit / Stop / PreCompact / SessionEnd），不直接依赖
 * [HookRegistry] 具体类型——注册表的构造（configDir/scope/errorLog）
 * 是装配层的事，引擎只认「能派发」这个最小契约。
 *
 * ## 模块依赖方向（查证结论，见 worklog Task 2-c）
 *
 * core:agent-engine **已经依赖** core:tool-registry（agent-engine 的
 * build.gradle.kts：`implementation(project(":core:tool-registry"))`，反向
 * 不成立），因此 [HookEvent] / [HookDispatchResult] 定义在 tool-registry
 * 的 hook 包即可被引擎直接引用——无需独立第三模块，也无需把接口下沉到
 * app 层做反转桥。[HookRegistryHookRunner] 桥接类放在本模块（而非 app）
 * 正是利用了这个方向：它同时看得见 HookRunner（本模块）与 HookRegistry
 * （tool-registry）。
 *
 * ## SubagentStop 契约（装配层接线，引擎不经手）
 *
 * `HookEvent.SubagentStop(sessionId, subagentId)` 在 code_task 子代理工具
 * 完成处派发（ToolModule 装配 SubAgentRunner 的工具实现内，工具结果返回
 * 前），经 `hookRegistry.dispatchFireAndForget(...)` 非阻断发出——子代理
 * 回合的收官点在工具执行侧，不在主引擎循环里。
 *
 * ## null 安全
 *
 * 引擎以 `hookRunner: HookRunner? = null` 注入：null 时所有插桩点零开销
 * （一次空判断），事件流语义与接入前完全一致。
 */
interface HookRunner {

    /**
     * 挂起派发：等钩子链走完。
     *
     * UserPromptSubmit / SessionStart / Stop / PreCompact 用本入口——它们
     * 位于回合管线内，天然挂起，且语义上「钩子看完再继续」；返回值当前
     * 仅用于观测（非 PreToolUse 事件的 Blocked/Modified 按设计无效，见
     * HookOutcome KDoc），保留返回是为后续扩展（如输入过滤）预留接缝。
     */
    suspend fun dispatch(event: HookEvent): HookDispatchResult

    /**
     * 非阻断派发：立即返回，钩子在 [CoroutineScope] 上异步执行。
     *
     * SessionEnd 用本入口——它的调用点（clearHistory）是非挂起函数。
     * 实现方必须保证本方法不抛异常（派发失败只能内部消化）。
     */
    fun dispatchFireAndForget(event: HookEvent)
}

/**
 * [HookRegistry] → [HookRunner] 的默认桥接（DI 直接可用）。
 *
 * @param registry 钩子注册表（工具钩子与生命周期钩子共用同一注册表，
 *   设置 UI 的启停对所有事件类型统一生效）。
 * @param scope fire-and-forget 的执行作用域：进程级 Supervisor + IO 即可
 *   （参照 ToolModule 的 SkillHotReloader 作用域惯例）；作用域死亡只影响
 *   异步派发，不影响挂起派发。
 */
class HookRegistryHookRunner(
    private val registry: HookRegistry,
    private val scope: CoroutineScope
) : HookRunner {

    override suspend fun dispatch(event: HookEvent): HookDispatchResult =
        registry.dispatch(event)

    override fun dispatchFireAndForget(event: HookEvent) {
        // dispatch 设计上不抛（单钩子异常注册表内部隔离）；runCatching 是
        // 异步边界的最后防线——非挂起调用方无处接收异常，绝不能炸宿主作用域，
        // 失败现场经 AppLogger（agent-engine 依赖 core:logging）留痕。
        scope.launch {
            runCatching { registry.dispatch(event) }
                .onFailure {
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, "HookRegistryHookRunner",
                        "fire-and-forget 派发失败: ${it.message}"
                    )
                }
        }
    }
}
