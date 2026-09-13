package com.apex.agent.browser.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * JsDialogBookkeeping 单测（P1）：
 * 排队 / 人类裁决回放 / Agent 接管与回绝 / 恰好一次回放 / 幂等。
 * 全部纯 JVM——这正是把簿记逻辑从 DialogRequestRouter 抽出来的意义。
 */
class JsDialogBookkeepingTest {

    /** 记录回放动作的假 Replay。 */
    private class Recording : JsDialogBookkeeping.Replay {
        val calls = mutableListOf<Pair<JsDialogChoice, String?>>()

        override fun invoke(choice: JsDialogChoice, promptValue: String?) {
            calls.add(choice to promptValue)
        }
    }

    private fun submit(
        book: JsDialogBookkeeping,
        kind: JsDialogKind = JsDialogKind.CONFIRM,
        message: String = "hello",
        default: String? = null,
        host: String = "site.example.com",
    ): Pair<String, Recording> {
        val rec = Recording()
        val id = book.submit(kind, host, message, default, rec)
        return id to rec
    }

    @Test
    fun `提交后进入可见队列且为队首`() {
        val book = JsDialogBookkeeping()
        val (id, _) = submit(book, message = "第一条")
        assertEquals(1, book.requests.value.size)
        assertEquals(id, book.requests.value.first().id)
        assertEquals(JsDialogKind.CONFIRM, book.requests.value.first().kind)
        assertEquals("site.example.com", book.requests.value.first().originHost)
        assertEquals("第一条", book.requests.value.first().message)
    }

    @Test
    fun `多弹窗排队 - 先到先展示 - 解决后下一个成为队首`() {
        val book = JsDialogBookkeeping()
        val (id1, _) = submit(book, message = "1")
        val (id2, _) = submit(book, message = "2")
        assertEquals(2, book.requests.value.size)

        book.resolve(id1, JsDialogChoice.NEGATIVE, null, routeToAgent = false)
        assertEquals(1, book.requests.value.size)
        assertEquals(id2, book.requests.value.first().id)
    }

    @Test
    fun `confirm 裁决回放 POSITIVE`() {
        val book = JsDialogBookkeeping()
        val (id, rec) = submit(book)
        book.resolve(id, JsDialogChoice.POSITIVE, null, routeToAgent = false)
        assertEquals(listOf(JsDialogChoice.POSITIVE to null), rec.calls)
        assertTrue(book.requests.value.isEmpty())
    }

    @Test
    fun `cancel 与 dismiss 都回放取消语义`() {
        val book = JsDialogBookkeeping()
        val (id1, rec1) = submit(book, message = "c1")
        book.resolve(id1, JsDialogChoice.NEGATIVE, null, routeToAgent = false)
        assertEquals(listOf(JsDialogChoice.NEGATIVE to null), rec1.calls)

        val (id2, rec2) = submit(book, message = "c2")
        book.resolve(id2, JsDialogChoice.DISMISS, null, routeToAgent = false)
        assertEquals(listOf(JsDialogChoice.DISMISS to null), rec2.calls)
    }

    @Test
    fun `prompt 裁决携带输入值`() {
        val book = JsDialogBookkeeping()
        val (id, rec) = submit(book, kind = JsDialogKind.PROMPT, default = "预设")
        assertEquals("预设", book.requests.value.first().promptDefault)

        book.resolve(id, JsDialogChoice.POSITIVE, "用户输入", routeToAgent = false)
        assertEquals(listOf(JsDialogChoice.POSITIVE to "用户输入"), rec.calls)
    }

    @Test
    fun `回放恰好一次 - 重复 resolve 幂等`() {
        val book = JsDialogBookkeeping()
        val (id, rec) = submit(book)
        book.resolve(id, JsDialogChoice.POSITIVE, null, routeToAgent = false)
        book.resolve(id, JsDialogChoice.NEGATIVE, null, routeToAgent = false) // 已出队：无入口
        book.resolve(id, JsDialogChoice.POSITIVE, null, routeToAgent = true)
        assertEquals(1, rec.calls.size)
        assertEquals(JsDialogChoice.POSITIVE, rec.calls[0].first)
    }

    @Test
    fun `未知 id 完全无副作用`() {
        val book = JsDialogBookkeeping()
        val (id, rec) = submit(book)
        book.resolve("no-such-id", JsDialogChoice.POSITIVE, null, routeToAgent = false)
        assertTrue(rec.calls.isEmpty())
        assertEquals(1, book.requests.value.size)
        assertEquals(id, book.requests.value.first().id)
    }

    @Test
    fun `Agent 接管后出队 - finish 异步回放一次`() {
        val book = JsDialogBookkeeping()
        val (id, rec) = submit(book)
        var handedRequest: JsDialogRequest? = null
        book.agentDecider = { request, finish ->
            handedRequest = request
            kotlinx.coroutines.runBlocking { finish(JsDialogChoice.POSITIVE, "agent-answer") } // Agent 立刻回放
            true
        }
        book.resolve(id, JsDialogChoice.POSITIVE, null, routeToAgent = true)
        assertEquals(id, handedRequest?.id) // Agent 拿到的是同一个请求
        assertTrue(book.requests.value.isEmpty()) // 已从可见队列移除
        assertEquals(listOf(JsDialogChoice.POSITIVE to "agent-answer"), rec.calls)
        assertEquals(0, book.pendingCount())
    }

    @Test
    fun `Agent 延迟回放期间人类无法裁决`() {
        val book = JsDialogBookkeeping()
        val (id, rec) = submit(book)
        book.agentDecider = { _, _ -> true } // 接管但不回放
        book.resolve(id, JsDialogChoice.POSITIVE, null, routeToAgent = true)
        assertTrue(book.requests.value.isEmpty())
        assertTrue(rec.calls.isEmpty())

        // UI 上弹窗已消失，人类的 resolve 不应产生任何效果
        book.resolve(id, JsDialogChoice.DISMISS, null, routeToAgent = false)
        assertTrue(rec.calls.isEmpty())
    }

    @Test
    fun `Agent 回绝时沿用人类选择立即回放`() {
        val book = JsDialogBookkeeping()
        val (id, rec) = submit(book)
        book.agentDecider = { _, _ -> false } // 不接管
        book.resolve(id, JsDialogChoice.POSITIVE, null, routeToAgent = true)
        assertEquals(listOf(JsDialogChoice.POSITIVE to null), rec.calls)
    }

    @Test
    fun `未挂 decider 时 routeToAgent 退化为人类选择`() {
        val book = JsDialogBookkeeping()
        val (id, rec) = submit(book)
        book.resolve(id, JsDialogChoice.NEGATIVE, null, routeToAgent = true)
        assertEquals(listOf(JsDialogChoice.NEGATIVE to null), rec.calls)
    }

    @Test
    fun `Agent 接管后回放为 prompt 时携带值`() {
        val book = JsDialogBookkeeping()
        val (id, rec) = submit(book, kind = JsDialogKind.PROMPT, default = "d")
        book.agentDecider = { _, finish ->
            kotlinx.coroutines.runBlocking { finish(JsDialogChoice.NEGATIVE, null) }
            true
        }
        book.resolve(id, JsDialogChoice.POSITIVE, "human-typed", routeToAgent = true)
        // Agent 的答案生效，人类草稿不回放
        assertEquals(listOf(JsDialogChoice.NEGATIVE to null), rec.calls)
    }
}
