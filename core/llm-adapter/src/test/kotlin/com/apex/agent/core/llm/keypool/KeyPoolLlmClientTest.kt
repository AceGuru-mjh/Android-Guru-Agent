package com.apex.agent.core.llm.keypool

import com.apex.agent.core.llm.KeyRotationMode
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmException
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolChoiceSpec
import com.apex.agent.core.llm.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [KeyPoolLlmClient] 单元测试（Task 4-a）。
 *
 * 手写 fake 双件套（无 mock 框架，仓库纪律）：
 * - [FakeKeyDelegate]：按 key 绑定的脚本化 [LlmClient]（chat 结果队列 +
 *   流式"先吐块后抛错"脚本），记录每次调用的 toolChoice；
 * - [RecordingFactory]：记录 acquire 出的 key 顺序（验证轮换轨迹）。
 *
 * 覆盖矩阵：
 * - 401 → 换 key → 成功（key1 判死 UNAVAILABLE）；
 * - 全 429 → 重抛最后一个原始错误（保真错误分类）；
 * - maxKeyRetries 上限（不同 key 计数）；
 * - 空池 / 全冷却开局 → [KeyPoolExhaustedException]；
 * - 成功上报 usage；
 * - 流式：首块前失败换 key 重来；首块后失败传播 + 池记账 + 下一通自然切换；
 * - 非换 key 错误（Parse / 默认 500）不轮换；500 开关两态；
 * - ON_RATE_LIMIT 仅 429 轮换；DISABLED 调用内不轮换（跨调用兜底）；
 * - toolChoice 透传；CancellationException 直通不做池记账；
 * - 异常 / 池内错误消息永不含 key 材料（含"响应体回显 key"剥除场景）。
 */
class KeyPoolLlmClientTest {

    // ── 手写 fake ──────────────────────────────────────────────

    /** 流式脚本：先依次吐 [chunks]，再抛 [error]（可空 = 干净收尾）。 */
    private data class StreamScript(
        val chunks: List<LlmStreamChunk> = emptyList(),
        val error: Throwable? = null
    )

    /** 按 key 绑定的脚本化 client——chat 结果 / 流式脚本各自出队。 */
    private class FakeKeyDelegate : LlmClient {

        val chatScript = ArrayDeque<Any>()       // LlmResponse 或 Throwable
        val streamScript = ArrayDeque<StreamScript>()
        val chatCalls = mutableListOf<Pair<List<LlmMessage>, ToolChoiceSpec?>>()
        val streamCalls = mutableListOf<Pair<List<LlmMessage>, ToolChoiceSpec?>>()

        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): LlmResponse = chat(messages, tools, temperature, maxTokens, null)

        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int,
            toolChoice: ToolChoiceSpec?
        ): LlmResponse {
            chatCalls += messages to toolChoice
            return when (val next = chatScript.removeFirstOrNull()) {
                is LlmResponse -> next
                is Throwable -> throw next
                null -> LlmResponse(content = "default-ok")
                else -> LlmResponse(content = next.toString())
            }
        }

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<LlmStreamChunk> = chatStream(messages, tools, temperature, maxTokens, null)

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int,
            toolChoice: ToolChoiceSpec?
        ): Flow<LlmStreamChunk> = flow {
            streamCalls += messages to toolChoice
            val script = streamScript.removeFirstOrNull()
                ?: StreamScript(listOf(LlmStreamChunk(content = "default-stream", isFinish = true)))
            script.chunks.forEach { emit(it) }
            script.error?.let { throw it }
        }
    }

    /** 记录 key 出队顺序的 delegateFactory。 */
    private class RecordingFactory : (String) -> LlmClient {
        val usedKeys = mutableListOf<String>()
        val delegates = linkedMapOf<String, FakeKeyDelegate>()
        fun script(key: String): FakeKeyDelegate = delegates.getOrPut(key) { FakeKeyDelegate() }
        override fun invoke(key: String): LlmClient {
            usedKeys += key
            return script(key)
        }
    }

    // ── 构造工具 ──────────────────────────────────────────────

    private val msgs = listOf(LlmMessage.User("hi"))
    private val clock: () -> Long = { 1_000L }

    private fun pool(vararg ids: String, preferUnusedKeys: Boolean = true) = ApiKeyPool(
        KeyPoolState(
            entries = ids.map { ApiKeyEntry(id = it, key = "sk-$it") },
            preferUnusedKeys = preferUnusedKeys
        ),
        clock
    )

    private fun chunk(text: String, finish: Boolean = false) =
        LlmStreamChunk(content = text, isFinish = finish)

    private suspend fun statusOf(p: ApiKeyPool, id: String): KeyStatus =
        p.currentState().entryById(id)!!.status

    // ═════════════ 非流式：换 key 重试 ═════════════

    @Test
    fun `401 on first key rotates to second and marks first dead`() = runTest {
        val p = pool("k1", "k2")
        val factory = RecordingFactory()
        factory.script("sk-k1").chatScript += LlmException.Http(401, "invalid api key")
        factory.script("sk-k2").chatScript += LlmResponse(content = "recovered")
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory)

        val resp = client.chat(msgs)

        assertEquals("recovered", resp.content)
        assertEquals(listOf("sk-k1", "sk-k2"), factory.usedKeys)
        // 池记账：k1 鉴权死 / k2 成功可用
        assertEquals(KeyStatus.UNAVAILABLE, statusOf(p, "k1"))
        assertEquals(401, p.currentState().entryById("k1")!!.lastErrorCode)
        assertEquals(KeyStatus.AVAILABLE, statusOf(p, "k2"))
        assertEquals(1L, p.currentState().entryById("k2")!!.usageCount)
        assertEquals(1_000L, p.currentState().entryById("k2")!!.lastUsedAt)
    }

    @Test
    fun `all keys rate limited rethrows last error after full rotation`() = runTest {
        val p = pool("k1", "k2")
        val factory = RecordingFactory()
        factory.script("sk-k1").chatScript += LlmException.Http(429, "rate limit 1")
        factory.script("sk-k2").chatScript += LlmException.Http(429, "rate limit 2")
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory)

        try {
            client.chat(msgs)
            fail("应重抛最后一个错误")
        } catch (e: LlmException.Http) {
            assertEquals(429, e.code)
            assertEquals("rate limit 2", e.body) // 最后一个（k2 的），保真上层分类
        }
        assertEquals(listOf("sk-k1", "sk-k2"), factory.usedKeys)
        assertEquals(KeyStatus.COOLDOWN, statusOf(p, "k1"))
        assertEquals(KeyStatus.COOLDOWN, statusOf(p, "k2"))
    }

    @Test
    fun `maxKeyRetries caps distinct keys per call`() = runTest {
        val p = pool("k1", "k2", "k3")
        val factory = RecordingFactory()
        listOf("sk-k1", "sk-k2", "sk-k3").forEach {
            factory.script(it).chatScript += LlmException.Http(429, "limited")
        }
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory, maxKeyRetries = 2)

        try {
            client.chat(msgs)
            fail("应抛错")
        } catch (e: LlmException.Http) {
            assertEquals(429, e.code)
        }
        // 上限 2 把不同 key，第三把没碰
        assertEquals(listOf("sk-k1", "sk-k2"), factory.usedKeys)
    }

    @Test
    fun `empty pool throws KeyPoolExhaustedException before any attempt`() = runTest {
        val factory = RecordingFactory()
        val client = KeyPoolLlmClient(ApiKeyPool(), KeyRotationMode.ON_ERROR, factory)
        try {
            client.chat(msgs)
            fail("应抛池耗尽")
        } catch (e: KeyPoolExhaustedException) {
            assertEquals(KeySelectionOutcome.POOL_EMPTY, e.outcome)
            assertTrue(e.attemptedKeyIds.isEmpty())
        }
        assertTrue(factory.usedKeys.isEmpty())
    }

    @Test
    fun `all cooling at start throws KeyPoolExhaustedException`() = runTest {
        val p = pool("k1")
        p.reportFailure("k1", 429)
        val factory = RecordingFactory()
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory)
        try {
            client.chatStream(msgs).toList()
            fail("应抛池耗尽")
        } catch (e: KeyPoolExhaustedException) {
            assertEquals(KeySelectionOutcome.ALL_COOLING_DOWN, e.outcome)
            assertEquals(KeyRotationMode.ON_ERROR, e.rotationMode)
        }
        assertTrue(factory.usedKeys.isEmpty())
    }

    @Test
    fun `success path reports usage`() = runTest {
        val p = pool("k1")
        val factory = RecordingFactory()
        factory.script("sk-k1").chatScript += LlmResponse(content = "hello")
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory)

        val resp = client.chat(msgs)

        assertEquals("hello", resp.content)
        assertEquals(listOf("sk-k1"), factory.usedKeys)
        // 消息原样透传给 delegate
        assertEquals(msgs, factory.script("sk-k1").chatCalls.first().first)
        val e = p.currentState().entryById("k1")!!
        assertEquals(KeyStatus.AVAILABLE, e.status)
        assertEquals(1L, e.usageCount)
        assertEquals(0L, e.totalFailures)
        assertEquals(1_000L, e.lastUsedAt)
    }

    // ═════════════ 流式重试语义 ═════════════

    @Test
    fun `stream failure before first chunk retries with next key`() = runTest {
        val p = pool("k1", "k2")
        val factory = RecordingFactory()
        factory.script("sk-k1").streamScript += StreamScript(error = LlmException.Http(429, "limited"))
        factory.script("sk-k2").streamScript += StreamScript(
            listOf(chunk("a"), chunk("b", finish = true))
        )
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory)

        val chunks = client.chatStream(msgs).toList()

        assertEquals(listOf("a", "b"), chunks.mapNotNull { it.content })
        assertEquals(listOf("sk-k1", "sk-k2"), factory.usedKeys)
        assertEquals(KeyStatus.COOLDOWN, statusOf(p, "k1")) // 首把被记账
        assertEquals(KeyStatus.AVAILABLE, statusOf(p, "k2"))
        assertEquals(1L, p.currentState().entryById("k2")!!.usageCount) // 全流收完才算成功
    }

    @Test
    fun `stream failure after first chunk propagates and pool marks failure`() = runTest {
        val p = pool("k1", "k2")
        val factory = RecordingFactory()
        factory.script("sk-k1").streamScript += StreamScript(
            listOf(chunk("partial")), error = LlmException.Http(429, "mid-stream")
        )
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory)

        val received = mutableListOf<LlmStreamChunk>()
        try {
            client.chatStream(msgs).collect { received += it }
            fail("半途失败应向上传播")
        } catch (e: LlmException.Http) {
            assertEquals(429, e.code)
        }
        // 已吐块 → 绝不重放：没有第二把 key 被碰
        assertEquals(listOf("partial"), received.mapNotNull { it.content })
        assertEquals(listOf("sk-k1"), factory.usedKeys)
        // 但失败照常记账——下一通调用自然切换
        assertEquals(KeyStatus.COOLDOWN, statusOf(p, "k1"))
        assertEquals(0L, p.currentState().entryById("k1")!!.usageCount)

        factory.script("sk-k2").streamScript += StreamScript(listOf(chunk("ok", finish = true)))
        val second = client.chatStream(msgs).toList()
        assertEquals(listOf("ok"), second.mapNotNull { it.content })
        assertEquals(listOf("sk-k1", "sk-k2"), factory.usedKeys)
    }

    // ═════════════ 非换 key 错误 ═════════════

    @Test
    fun `parse error does not rotate`() = runTest {
        val p = pool("k1", "k2")
        val factory = RecordingFactory()
        factory.script("sk-k1").chatScript += LlmException.Parse(IllegalStateException("bad json"))
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory)

        try {
            client.chat(msgs)
            fail("应原样上抛")
        } catch (e: LlmException.Parse) {
            // 期望：非 Http 分类
        }
        assertEquals(listOf("sk-k1"), factory.usedKeys)
        // 请求级错误：只计数、不动状态
        val e = p.currentState().entryById("k1")!!
        assertEquals(KeyStatus.UNTESTED, e.status)
        assertEquals(1L, e.totalFailures)
        assertEquals(-1, e.lastErrorCode)
    }

    @Test
    fun `http 500 is non-rotating by default but fails over on next call`() = runTest {
        val p = pool("k1", "k2")
        val factory = RecordingFactory()
        factory.script("sk-k1").chatScript += LlmException.Http(500, "boom")
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory) // 默认 rotateOnServerErrors=false

        try {
            client.chat(msgs)
            fail("应原样上抛")
        } catch (e: LlmException.Http) {
            assertEquals(500, e.code)
        }
        assertEquals(listOf("sk-k1"), factory.usedKeys)
        // 池仍按 5xx 分类记账（冷却退避）
        assertEquals(KeyStatus.COOLDOWN, statusOf(p, "k1"))
        assertEquals(1_000L + 30_000L, p.currentState().entryById("k1")!!.cooldownUntilMs)

        // 跨调用：冷却中的 k1 被候选过滤出局 → k2 顶上
        factory.script("sk-k2").chatScript += LlmResponse(content = "second-call-ok")
        assertEquals("second-call-ok", client.chat(msgs).content)
        assertEquals(listOf("sk-k1", "sk-k2"), factory.usedKeys)
    }

    @Test
    fun `http 500 rotates when rotateOnServerErrors enabled`() = runTest {
        val p = pool("k1", "k2")
        val factory = RecordingFactory()
        factory.script("sk-k1").chatScript += LlmException.Http(500, "boom")
        factory.script("sk-k2").chatScript += LlmResponse(content = "recovered")
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory, rotateOnServerErrors = true)

        assertEquals("recovered", client.chat(msgs).content)
        assertEquals(listOf("sk-k1", "sk-k2"), factory.usedKeys)
        assertEquals(KeyStatus.COOLDOWN, statusOf(p, "k1"))
    }

    @Test
    fun `network error is bookkept but never rotates`() = runTest {
        val p = pool("k1", "k2")
        val factory = RecordingFactory()
        factory.script("sk-k1").chatScript += LlmException.Network(java.io.IOException("timeout"))
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory)

        try {
            client.chat(msgs)
            fail("应原样上抛")
        } catch (e: LlmException.Network) {
            // 网络错不是 key 健康事件：不换 key
        }
        assertEquals(listOf("sk-k1"), factory.usedKeys)
        // 池按网络哨兵码（0）冷却记账
        assertEquals(KeyStatus.COOLDOWN, statusOf(p, "k1"))
        assertEquals(0, p.currentState().entryById("k1")!!.lastErrorCode)
    }

    // ═════════════ 模式门控 ═════════════

    @Test
    fun `on rate limit mode rotates only for 429`() = runTest {
        // 401 在 ON_RATE_LIMIT 下不换 key（鉴权不是限流）
        val p = pool("k1", "k2")
        var factory = RecordingFactory()
        factory.script("sk-k1").chatScript += LlmException.Http(401, "dead")
        factory.script("sk-k2").chatScript += LlmResponse(content = "ok")
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_RATE_LIMIT, factory)

        try {
            client.chat(msgs)
            fail("401 不应轮换")
        } catch (e: LlmException.Http) {
            assertEquals(401, e.code)
        }
        assertEquals(listOf("sk-k1"), factory.usedKeys)
        // 下一通调用经候选过滤自然切到 k2（死 key 出局）
        assertEquals("ok", client.chat(msgs).content)
        assertEquals(listOf("sk-k1", "sk-k2"), factory.usedKeys)

        // 429 在 ON_RATE_LIMIT 下立即换 key
        val p2 = pool("k1", "k2")
        factory = RecordingFactory()
        factory.script("sk-k1").chatScript += LlmException.Http(429, "limited")
        factory.script("sk-k2").chatScript += LlmResponse(content = "ok2")
        val client2 = KeyPoolLlmClient(p2, KeyRotationMode.ON_RATE_LIMIT, factory)
        assertEquals("ok2", client2.chat(msgs).content)
        assertEquals(listOf("sk-k1", "sk-k2"), factory.usedKeys)
    }

    @Test
    fun `disabled mode does not rotate within a single call`() = runTest {
        val p = pool("k1", "k2")
        val factory = RecordingFactory()
        factory.script("sk-k1").chatScript += LlmException.Http(429, "limited")
        factory.script("sk-k2").chatScript += LlmResponse(content = "ok")
        val client = KeyPoolLlmClient(p, KeyRotationMode.DISABLED, factory)

        try {
            client.chat(msgs)
            fail("DISABLED 调用内不应轮换")
        } catch (e: LlmException.Http) {
            assertEquals(429, e.code)
        }
        assertEquals(listOf("sk-k1"), factory.usedKeys)
        // 跨调用兜底：冷却中的 k1 出局 → DISABLED 的"第一个候选"滑到 k2
        assertEquals("ok", client.chat(msgs).content)
        assertEquals(listOf("sk-k1", "sk-k2"), factory.usedKeys)
    }

    // ═════════════ 透传与卫生 ═════════════

    @Test
    fun `toolChoice overloads delegate through`() = runTest {
        val p = pool("k1")
        val factory = RecordingFactory()
        factory.script("sk-k1").chatScript += LlmResponse(content = "forced")
        factory.script("sk-k1").streamScript += StreamScript(listOf(chunk("s", finish = true)))
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory)

        client.chat(msgs, emptyList(), 0.5f, 128, ToolChoiceSpec.Required)
        client.chatStream(msgs, emptyList(), 0.5f, 128, ToolChoiceSpec.Function("get_weather")).toList()
        // 4 参旧签名 → toolChoice 为 null
        client.chat(msgs)

        val delegate = factory.script("sk-k1")
        assertEquals(ToolChoiceSpec.Required, delegate.chatCalls[0].second)
        assertEquals(ToolChoiceSpec.Function("get_weather"), delegate.streamCalls[0].second)
        assertEquals(null, delegate.chatCalls[1].second)
    }

    @Test
    fun `cancellation propagates without pool bookkeeping`() = runTest {
        val p = pool("k1")
        val factory = RecordingFactory()
        factory.script("sk-k1").chatScript += CancellationException("stop")
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory)

        var caught: Throwable? = null
        try {
            client.chat(msgs)
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue("应直通 CancellationException，实为 $caught", caught is CancellationException)
        // 协作取消不是失败：池不记账
        assertEquals(listOf("sk-k1"), factory.usedKeys)
        val e = p.currentState().entryById("k1")!!
        assertEquals(0L, e.totalFailures)
        assertEquals(KeyStatus.UNTESTED, e.status)
    }

    @Test
    fun `exceptions and pool messages never contain key material`() = runTest {
        val p = ApiKeyPool(
            KeyPoolState(
                entries = listOf(
                    ApiKeyEntry(id = "k1", key = "sk-SECRET1"),
                    ApiKeyEntry(id = "k2", key = "sk-SECRET2")
                )
            ),
            clock
        )
        val factory = RecordingFactory()
        // 个别中转会在错误体里回显 Authorization——写入池前必须剥除
        factory.script("sk-SECRET1").chatScript += LlmException.Http(401, "key sk-SECRET1 rejected")
        factory.script("sk-SECRET2").chatScript += LlmException.Http(429, "rate limited")
        val client = KeyPoolLlmClient(p, KeyRotationMode.ON_ERROR, factory)

        try {
            client.chat(msgs)
            fail("应抛错")
        } catch (e: LlmException.Http) {
            assertFalse(e.message!!.contains("SECRET"))
        }
        assertEquals(listOf("sk-SECRET1", "sk-SECRET2"), factory.usedKeys)
        // 池内存储的错误消息已脱敏
        val stored = p.currentState().entryById("k1")!!.lastErrorMessage
        assertEquals("key [redacted] rejected", stored)
        assertFalse(p.snapshot().summary().contains("SECRET"))
        p.snapshot().keys.forEach { assertFalse(it.keyMasked.contains("SECRET")) }
    }

    @Test
    fun `requirement on maxKeyRetries is enforced at construction`() {
        try {
            KeyPoolLlmClient(
                pool("k1"),
                KeyRotationMode.ON_ERROR,
                delegateFactory = { _: String -> FakeKeyDelegate() },
                maxKeyRetries = 0
            )
            fail("maxKeyRetries < 1 应拒绝构造")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxKeyRetries"))
        }
    }
}
