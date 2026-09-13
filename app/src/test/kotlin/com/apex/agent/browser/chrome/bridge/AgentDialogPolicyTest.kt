package com.apex.agent.browser.chrome.bridge

import com.apex.agent.browser.chrome.JsDialogChoice
import com.apex.agent.browser.chrome.JsDialogKind
import com.apex.agent.browser.chrome.JsDialogRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * AgentDialogPolicy 单测（P1）：
 * 安全默认（ALERT 自动确认 / CONFIRM 拒绝 / PROMPT 不接管）、白名单、
 * 委托插槽、回放时序（有 scope 走 launch、无 scope 同步）。
 */
class AgentDialogPolicyTest {

    private fun request(
        kind: JsDialogKind,
        host: String = "site.example.com",
        message: String = "msg",
    ) = JsDialogRequest(id = "r1", kind = kind, originHost = host, message = message)

    /** Unconfined：launch 体在调用线程立即执行，测试无需挂起等待。 */
    private val eagerScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Test
    fun `ALERT 自动确认并返回接管`() {
        val policy = AgentDialogPolicy(scope = eagerScope)
        var replayed: Pair<JsDialogChoice, String?>? = null
        val handled = policy.invoke(request(JsDialogKind.ALERT)) { choice, value ->
            replayed = choice to value
        }
        assertTrue(handled)
        assertEquals(JsDialogChoice.POSITIVE to null, replayed)
    }

    @Test
    fun `关闭 ALERT 自动确认则不接管`() {
        val policy = AgentDialogPolicy(scope = eagerScope, autoAcknowledgeAlerts = false)
        val handled = policy.invoke(request(JsDialogKind.ALERT)) { _, _ -> }
        assertFalse(handled)
    }

    @Test
    fun `CONFIRM 默认安全拒绝`() {
        val policy = AgentDialogPolicy(scope = eagerScope)
        var replayed: Pair<JsDialogChoice, String?>? = null
        val handled = policy.invoke(request(JsDialogKind.CONFIRM)) { choice, value ->
            replayed = choice to value
        }
        assertTrue(handled)
        assertEquals(JsDialogChoice.NEGATIVE to null, replayed)
    }

    @Test
    fun `白名单站点的 CONFIRM 自动确认`() {
        val policy = AgentDialogPolicy(
            scope = eagerScope,
            trustedConfirmHosts = setOf("trusted.example.com"),
        )
        var replayed: Pair<JsDialogChoice, String?>? = null
        val handled = policy.invoke(request(JsDialogKind.CONFIRM, host = "trusted.example.com")) { choice, value ->
            replayed = choice to value
        }
        assertTrue(handled)
        assertEquals(JsDialogChoice.POSITIVE to null, replayed)
    }

    @Test
    fun `白名单外站点不受影响`() {
        val policy = AgentDialogPolicy(
            scope = eagerScope,
            trustedConfirmHosts = setOf("trusted.example.com"),
        )
        var replayed: Pair<JsDialogChoice, String?>? = null
        policy.invoke(request(JsDialogKind.CONFIRM, host = "other.example.com")) { choice, value ->
            replayed = choice to value
        }
        assertEquals(JsDialogChoice.NEGATIVE to null, replayed)
    }

    @Test
    fun `PROMPT 无委托不接管`() {
        val policy = AgentDialogPolicy(scope = eagerScope)
        val handled = policy.invoke(request(JsDialogKind.PROMPT)) { _, _ -> }
        assertFalse(handled)
    }

    @Test
    fun `PROMPT 有委托时按委托结果回放`() {
        val policy = AgentDialogPolicy(
            scope = eagerScope,
            answerPrompt = { req ->
                assertEquals("msg", req.message)
                JsDialogChoice.POSITIVE to "agent-filled"
            },
        )
        var replayed: Pair<JsDialogChoice, String?>? = null
        val handled = policy.invoke(request(JsDialogKind.PROMPT)) { choice, value ->
            replayed = choice to value
        }
        assertTrue(handled)
        assertEquals(JsDialogChoice.POSITIVE to "agent-filled", replayed)
    }

    @Test
    fun `无 scope 时同步回放`() {
        val policy = AgentDialogPolicy() // runBlocking 路径
        var replayed: Pair<JsDialogChoice, String?>? = null
        val handled = policy.invoke(request(JsDialogKind.CONFIRM)) { choice, value ->
            replayed = choice to value
        }
        assertTrue(handled)
        assertEquals(JsDialogChoice.NEGATIVE to null, replayed) // invoke 返回前已回放
    }

    @Test
    fun `onDecision 审计钩子被调用`() {
        val log = mutableListOf<Triple<JsDialogKind, JsDialogChoice, String?>>()
        val policy = AgentDialogPolicy(
            scope = eagerScope,
            answerPrompt = { JsDialogChoice.POSITIVE to "v" },
            onDecision = { req, choice, value, byAgent ->
                assertTrue(byAgent)
                log.add(Triple(req.kind, choice, value))
            },
        )
        policy.invoke(request(JsDialogKind.ALERT)) { _, _ -> }
        policy.invoke(request(JsDialogKind.CONFIRM)) { _, _ -> }
        policy.invoke(request(JsDialogKind.PROMPT)) { _, _ -> }
        assertEquals(
            listOf(
                Triple(JsDialogKind.ALERT, JsDialogChoice.POSITIVE, null),
                Triple(JsDialogKind.CONFIRM, JsDialogChoice.NEGATIVE, null),
                Triple(JsDialogKind.PROMPT, JsDialogChoice.POSITIVE, "v"),
            ),
            log,
        )
    }

    @Test
    fun `策略可直接赋给 agentDecider 函数类型`() {
        // 类型兼容性冒烟：AgentDialogPolicy 实现 decider 的函数类型，且经该类型调用可用
        var decider: ((JsDialogRequest, suspend (JsDialogChoice, String?) -> Unit) -> Boolean)? = null
        decider = AgentDialogPolicy(scope = eagerScope)
        var replayed: Pair<JsDialogChoice, String?>? = null
        val handled = decider!!.invoke(request(JsDialogKind.CONFIRM)) { choice, value ->
            replayed = choice to value
        }
        assertTrue(handled)
        assertEquals(JsDialogChoice.NEGATIVE to null, replayed)
    }
}
