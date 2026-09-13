package com.apex.agent.browser.chrome.bridge

import com.apex.agent.browser.chrome.JsDialogChoice
import com.apex.agent.browser.chrome.JsDialogKind
import com.apex.agent.browser.chrome.JsDialogRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/*
 * AgentDialogPolicy —— agentDecider 的默认策略（P1「挂 Agent 策略」的一行解）。
 *
 *   wiring.agentDecider = AgentDialogPolicy(scope = wiringScope)
 *
 * 与被替换的旧路径（「自动 confirm 全部弹窗」）的关键差异——安全默认，不盲放行：
 * - ALERT   → 自动确认。alert 不承载用户选择，确认无副作用，且不阻塞自动化链路；
 * - CONFIRM → 默认安全拒绝；trustedConfirmHosts 白名单内的站点自动确认；
 * - PROMPT  → 有 answerPrompt 委托时按其结果回填；没有委托则「不接管」
 *             （invoke 返回 false，人类在 UI 上按下的选择照常生效）。
 *
 * 本类实现的是 agentDecider 的函数类型，因此可以直接赋值；策略本身
 * 零 android 依赖，规则与回放时序都可在 JVM 上单测。
 */
class AgentDialogPolicy(
    /** 回放所用的协程域；null 时同步回放（runBlocking，适合 JVM 单测/无协程域宿主）。 */
    private val scope: CoroutineScope? = null,
    /** ALERT 是否自动确认。 */
    private val autoAcknowledgeAlerts: Boolean = true,
    /** CONFIRM 的默认裁决（站点不在白名单时）。 */
    private val confirmChoice: JsDialogChoice = JsDialogChoice.NEGATIVE,
    /** CONFIRM 自动确认的白名单站点（精确 host 匹配）。 */
    private val trustedConfirmHosts: Set<String> = emptySet(),
    /**
     * PROMPT 的答案委托——接真实 Agent / LLM 的插槽：
     * 返回 (choice, promptValue)；不设置则 PROMPT 一律不接管。
     */
    private val answerPrompt: (suspend (JsDialogRequest) -> Pair<JsDialogChoice, String?>)? = null,
    /** 审计钩子：每次策略落定时回调（记录日志 / 上报行为流水）。 */
    private val onDecision: ((request: JsDialogRequest, choice: JsDialogChoice, promptValue: String?, byAgent: Boolean) -> Unit)? = null,
) : (JsDialogRequest, suspend (JsDialogChoice, String?) -> Unit) -> Boolean {

    override fun invoke(
        request: JsDialogRequest,
        finish: suspend (JsDialogChoice, String?) -> Unit,
    ): Boolean {
        return when (request.kind) {
            JsDialogKind.ALERT -> {
                if (!autoAcknowledgeAlerts) return false
                settle(request, finish, JsDialogChoice.POSITIVE, null)
                true
            }

            JsDialogKind.CONFIRM -> {
                val choice =
                    if (request.originHost in trustedConfirmHosts) JsDialogChoice.POSITIVE
                    else confirmChoice
                settle(request, finish, choice, null)
                true
            }

            JsDialogKind.PROMPT -> {
                val delegate = answerPrompt ?: return false
                deliver {
                    val (choice, value) = delegate(request)
                    onDecision?.invoke(request, choice, value, true)
                    finish(choice, value)
                }
                true
            }
        }
    }

    /* ---------------- 内部 ---------------- */

    private fun settle(
        request: JsDialogRequest,
        finish: suspend (JsDialogChoice, String?) -> Unit,
        choice: JsDialogChoice,
        promptValue: String?,
    ) {
        onDecision?.invoke(request, choice, promptValue, true)
        deliver { finish(choice, promptValue) }
    }

    /** 有 scope 走 launch（异步、不阻塞调用线程）；无 scope 同步回放。 */
    private fun deliver(block: suspend () -> Unit) {
        val sc = scope
        if (sc != null) sc.launch { block() } else runBlocking { block() }
    }
}
