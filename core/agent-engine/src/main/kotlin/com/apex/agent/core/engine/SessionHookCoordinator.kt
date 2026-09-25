package com.apex.agent.core.engine

import com.apex.agent.core.tools.hook.HookEvent
import java.util.concurrent.atomic.AtomicReference

/**
 * # Issue #165 —— 引擎会话生命周期插桩的最小封装
 *
 * [ApexAgentEngine] 在 v1.1 前已达文件预算上限（1200 行红线），#165 的
 * 会话钩子插桩（SessionStart / UserPromptSubmit / Stop / PreCompact /
 * SessionEnd）再塞进去会突破 check_file_size 门禁。本类把这组**内聚的**
 * 状态与派发逻辑抽出来：
 *
 *  - 会话 id 的生成与生命周期（首个 execute 发号，clearHistory 收官，
 *    CAS 防并发 execute 双发号——收号失败者不派发，另一条已胜出）；
 *  - 各插桩点的语义选择（为什么 Stop 只挂正常完成、为什么 SessionEnd
 *    用 fire-and-forget）——KDoc 跟着逻辑走，引擎主类只留一行调用。
 *
 * ## null 安全
 *
 * [hookRunner] 为 null（未装配）时所有方法零开销返回——引擎构造参数
 * 带默认值 null，单测与旧装配路径零迁移。
 */
internal class SessionHookCoordinator(
    private val hookRunner: HookRunner?,
    /** 会话模式名（SessionStart 事件载荷；BUILD/PLAN/SPEC/...）。 */
    private val modeName: () -> String
) {

    /** 当前会话 id（引擎无内建会话标识，内存生成；null = 会话未开始）。 */
    private val sessionRef = AtomicReference<String?>(null)

    /** 当前会话 id（未开号为空串——事件载荷不可空）。 */
    fun currentSessionId(): String = sessionRef.get() ?: ""

    /**
     * 会话首条用户输入进入时开号并派发 SessionStart（幂等：已开号则跳过）。
     * 与 [onUserPrompt] 配对使用（execute 入口处）。
     */
    suspend fun onSessionBeginIfNeeded() {
        val runner = hookRunner ?: return
        if (sessionRef.get() != null) return
        val newId = "session-" + java.util.UUID.randomUUID().toString().take(8)
        if (sessionRef.compareAndSet(null, newId)) {
            runner.dispatch(HookEvent.SessionStart(newId, modeName()))
        }
    }

    /** 用户输入进引擎管线前派发（工具结果回灌不经 execute 入口，天然不触发）。 */
    suspend fun onUserPrompt(text: String) {
        hookRunner?.dispatch(HookEvent.UserPromptSubmit(text))
    }

    /**
     * 一次 execute 回合正常完成（用户输入 → ReAct 循环 → 最终回复）。
     * 刻意只挂正常完成分支：错误/中止路径以 Error/Aborted 事件收官，对齐
     * Claude Code 的 Stop 语义（仅主代理完成响应时触发）。
     */
    suspend fun onTurnCompleted() {
        hookRunner?.dispatch(HookEvent.Stop(currentSessionId()))
    }

    /** 上下文压缩即将发生（自动阈值与手动压缩共用；压缩器动历史前派发）。 */
    suspend fun onPreCompact() {
        hookRunner?.dispatch(HookEvent.PreCompact(currentSessionId()))
    }

    /**
     * 旧会话收官（clearHistory 语义 = 开新会话）。非挂起调用方，故
     * fire-and-forget；恢复历史（restoreHistory）不在此列——那是会话
     * 延续而非终止。进程死亡不派发：钩子本就不可靠，引擎无 close 钩子。
     */
    fun onSessionEnd() {
        val endedSession = sessionRef.getAndSet(null) ?: return
        hookRunner?.dispatchFireAndForget(HookEvent.SessionEnd(endedSession))
    }
}
