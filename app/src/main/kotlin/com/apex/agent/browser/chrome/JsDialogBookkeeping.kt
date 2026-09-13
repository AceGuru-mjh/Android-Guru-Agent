package com.apex.agent.browser.chrome

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * JsDialogBookkeeping —— JS 弹窗「簿记」纯核（P1）。
 *
 * 职责：入队（StateFlow）、人类裁决、Agent 路由、恰好一次回放。
 * 刻意不持有任何 WebView / JsResult 类型——bridge 层的 DialogRequestRouter
 * 把 android.webkit.JsResult 适配成 [Replay] 传入，因此本类的全部行为
 * （排队 / 裁决优先级 / Agent 接管 / 幂等回放）都可以在 JVM 上直接单测，
 * 不需要 Android 设备或 Robolectric。
 *
 * 语义（与上一版 DialogRequestRouter 内联实现逐条对齐，可安全替换）：
 * 1. resolve 只作用于「仍在可见队列」的请求——已交给 Agent 的请求人类侧无法再碰；
 * 2. decider 返回 true = Agent 接管：请求出队、回放延后到 finish 被调用；
 * 3. decider 返回 false 或未挂接 = 不接管：立即按人类按下按钮的选择回放；
 * 4. applyFinal 由 pending.remove() 原子守卫——任何路径（人/Agent/竞态）都恰好回放一次。
 */

class JsDialogBookkeeping {

    /** 回放动作：bridge 侧把「JsResult.confirm/cancel」包成这个接口。 */
    fun interface Replay {
        /** choice=POSITIVE 时 promptValue 对 PROMPT 有意义，其余可为 null。 */
        operator fun invoke(choice: JsDialogChoice, promptValue: String?)
    }

    /**
     * Agent 决策钩子：返回 true 表示由 Agent 异步裁决（稍后调用 finish 回放）；
     * 返回 false 表示不接管，沿用人类在 UI 上给出的选择。
     */
    var agentDecider: ((JsDialogRequest, suspend (JsDialogChoice, String?) -> Unit) -> Boolean)? = null

    private data class Pending(
        val request: JsDialogRequest,
        val replay: Replay,
    )

    private val _requests = MutableStateFlow<List<JsDialogRequest>>(emptyList())

    /** 可见队列；首个元素即当前应展示的弹窗。 */
    val requests: StateFlow<List<JsDialogRequest>> = _requests.asStateFlow()

    /** id → 待回放请求（含已交给 Agent、暂不可见的）。 */
    private val pending = ConcurrentHashMap<String, Pending>()

    /** 入队（WebChromeClient 回调线程调用）。返回请求 id。 */
    fun submit(
        kind: JsDialogKind,
        originHost: String,
        message: String,
        promptDefault: String?,
        replay: Replay,
    ): String {
        val id = UUID.randomUUID().toString()
        val request = JsDialogRequest(
            id = id,
            kind = kind,
            originHost = originHost,
            message = message,
            promptDefault = promptDefault,
        )
        pending[id] = Pending(request, replay)
        _requests.value = _requests.value + request
        return id
    }

    /**
     * 裁决入口（gateway.resolveDialog → router → 这里）。
     * 幂等：重复 resolve 同一 id（已回放 / 已出队）不做任何事。
     */
    fun resolve(
        requestId: String,
        choice: JsDialogChoice,
        promptValue: String?,
        routeToAgent: Boolean,
    ) {
        // 只作用于可见队列中的请求：已路由给 Agent 的弹窗人类侧不可再裁决。
        val entry = pending[requestId] ?: return
        if (_requests.value.none { it.id == requestId }) return

        if (routeToAgent) {
            val decider = agentDecider
            if (decider != null) {
                val finish: suspend (JsDialogChoice, String?) -> Unit = { finalChoice, finalValue ->
                    applyFinal(requestId, finalChoice, finalValue)
                }
                if (decider(entry.request, finish)) {
                    // Agent 接管：离开可见队列，等待异步回放（pending 保留）。
                    removeFromQueue(requestId)
                    return
                }
            }
        }
        // 未挂 decider / decider 拒绝接管：立即按人类选择回放。
        removeFromQueue(requestId)
        applyFinal(requestId, choice, promptValue)
    }

    /** 当前待决数量（含已路由 Agent 的）。诊断/测试用。 */
    fun pendingCount(): Int = pending.size

    private fun removeFromQueue(requestId: String) {
        _requests.value = _requests.value.filterNot { it.id == requestId }
    }

    private fun applyFinal(requestId: String, choice: JsDialogChoice, promptValue: String?) {
        val entry = pending.remove(requestId) ?: return // 已回放过：恰好一次保证
        entry.replay(choice, promptValue)
    }
}
