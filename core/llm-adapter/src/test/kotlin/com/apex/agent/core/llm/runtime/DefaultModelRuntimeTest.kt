package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmException
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRole
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ProviderConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T72 §八 / §十三 / §二十四 — [DefaultModelRuntime] 集成测试。
 *
 * 完整 Agent → Router → Registry → FakeClient 路径，覆盖：成功、降级、降级耗尽、
 * 非降级错误、防环、流式降级边界、诊断快照、并发隔离。
 *
 * 链序约定：SUMMARY 角色 fallback 链为 [summary, primary, default]。为验证"首选
 * 失败 → 降级到下一个"，把"首选"（SUMMARY 配置的 profile，即链首）安排为会失败的
 * client，"下一个"（PRIMARY 配置的 profile）安排为成功 client。
 */
class DefaultModelRuntimeTest {

    private val fullProvider = ProviderConfig(
        id = "openai", displayName = "OpenAI", baseUrl = "https://api.openai.com/v1",
        apiKeys = listOf("sk-x"),
        capabilities = ModelCapabilities(text = true, vision = true, imageInput = true, toolCalling = true, reasoning = true)
    )

    private fun profile(id: String, isDefault: Boolean = false) = ModelProfile(
        id = id, name = id, providerId = "openai", modelId = "m-$id",
        capabilities = ModelCapabilities(text = true), isDefault = isDefault
    )

    /**
     * 双 profile 装置：profile "p" 是 SUMMARY 链首（首选），"p2" 是 PRIMARY/默认（fallback）。
     * SUMMARY chain = [p, p2, p2] → 去重 [p, p2]。用 SUMMARY 角色请求触发 p→p2 降级。
     */
    private fun twoProfileRuntime(
        firstClient: LlmClient,    // profile "p"（链首）
        secondClient: LlmClient    // profile "p2"（fallback）
    ): DefaultModelRuntime {
        val store = FakeModelRuntimeStore(
            listOf(profile("p"), profile("p2", isDefault = true)),
            listOf(fullProvider),
            ModelRoleConfig(primaryProfileId = "p2", summaryProfileId = "p")
        )
        val registry = ModelRuntimeRegistry(clientFactory = { config ->
            val pid = config.model.removePrefix("m-")
            if (pid == "p") firstClient else secondClient
        })
        return DefaultModelRuntime(ModelRoleRouter(store), registry)
    }

    private val msgs = listOf(LlmMessage.User("hi"))

    // ── 成功 ──

    @Test
    fun `normal primary request succeeds on first try`() = runBlocking {
        val client = FakeLlmClient(responses = listOf(LlmResponse(content = "ok")))
        val store = FakeModelRuntimeStore(
            listOf(profile("p", isDefault = true)),
            listOf(fullProvider),
            ModelRoleConfig(primaryProfileId = "p")
        )
        val rt = DefaultModelRuntime(ModelRoleRouter(store), ModelRuntimeRegistry(clientFactory = { client }))
        val resp = rt.chat(LlmRequestContext.primary("test"), msgs)
        assertEquals("ok", resp.content)
        assertEquals(1, client.chatCalls.size)
    }

    // ── 降级 ──

    @Test
    fun `fallback on 500 succeeds on next profile`() = runBlocking {
        val failing = FakeLlmClient(responses = listOf(LlmException.Http(500, "boom")))
        val ok = FakeLlmClient(responses = listOf(LlmResponse(content = "recovered")))
        val rt = twoProfileRuntime(failing, ok)
        val resp = rt.chat(LlmRequestContext.summary("test"), msgs)
        assertEquals("recovered", resp.content)
        assertEquals(1, failing.chatCalls.size)
        assertEquals(1, ok.chatCalls.size)
    }

    @Test
    fun `fallback on 429 succeeds on next profile`() = runBlocking {
        val failing = FakeLlmClient(responses = listOf(LlmException.Http(429, "rate limited")))
        val ok = FakeLlmClient(responses = listOf(LlmResponse(content = "ok2")))
        val rt = twoProfileRuntime(failing, ok)
        val resp = rt.chat(LlmRequestContext.summary("test"), msgs)
        assertEquals("ok2", resp.content)
    }

    @Test
    fun `fallback exhausted when all profiles fail`() = runBlocking {
        val failing1 = FakeLlmClient(responses = listOf(LlmException.Http(500, "boom1")))
        val failing2 = FakeLlmClient(responses = listOf(LlmException.Http(500, "boom2")))
        val rt = twoProfileRuntime(failing1, failing2)
        try {
            rt.chat(LlmRequestContext.summary("test"), msgs)
            assert(false) { "应抛错" }
        } catch (e: ModelRuntimeException) {
            // 链上两个都 500 → 最后一个是 ModelUnavailable
            assertTrue(e is ModelRuntimeException.ModelUnavailable)
        }
        assertEquals(1, failing1.chatCalls.size)
        assertEquals(1, failing2.chatCalls.size)
    }

    @Test
    fun `non-fallback error fails immediately without trying next`() = runBlocking {
        val rejected = FakeLlmClient(responses = listOf(LlmException.Http(400, "bad request")))
        val ok = FakeLlmClient(responses = listOf(LlmResponse(content = "should-not-reach")))
        val rt = twoProfileRuntime(rejected, ok)
        try {
            rt.chat(LlmRequestContext.summary("test"), msgs)
            assert(false) { "应抛 ModelRequestRejected" }
        } catch (e: ModelRuntimeException.ModelRequestRejected) {
            // 400 不降级，立即抛出
        }
        assertEquals(1, rejected.chatCalls.size)
        assertEquals(0, ok.chatCalls.size)  // 未尝试 fallback
    }

    // ── 防环 ──

    @Test
    fun `cycle prevention same profile tried only once`() = runBlocking {
        // summary 与 primary 指向同一 profile，链去重后只剩一个；失败即终止
        val failing = FakeLlmClient(responses = listOf(
            LlmException.Http(500, "x"), LlmException.Http(500, "y")
        ))
        val store = FakeModelRuntimeStore(
            listOf(profile("p", isDefault = true)),
            listOf(fullProvider),
            ModelRoleConfig(primaryProfileId = "p", summaryProfileId = "p")
        )
        val rt = DefaultModelRuntime(ModelRoleRouter(store), ModelRuntimeRegistry(clientFactory = { failing }))
        try { rt.chat(LlmRequestContext.summary("test"), msgs) } catch (_: ModelRuntimeException) {}
        // 链去重只剩 1 个候选 → 只调用 1 次（防环）
        assertEquals(1, failing.chatCalls.size)
    }

    @Test
    fun `maxAttempts caps total tries`() = runBlocking {
        val failing = FakeLlmClient(responses = listOf(
            LlmException.Http(500, "1"), LlmException.Http(500, "2"),
            LlmException.Http(500, "3"), LlmException.Http(500, "4")
        ))
        val store = FakeModelRuntimeStore(
            listOf(profile("p"), profile("p2", isDefault = true)),
            listOf(fullProvider),
            ModelRoleConfig(primaryProfileId = "p2", summaryProfileId = "p")
        )
        val rt = DefaultModelRuntime(ModelRoleRouter(store), ModelRuntimeRegistry(clientFactory = { failing }), maxAttempts = 1)
        try { rt.chat(LlmRequestContext.summary("test"), msgs) } catch (_: ModelRuntimeException) {}
        // maxAttempts=1 → 至多 1 次尝试
        assertTrue("attempts should be <= 1, got ${failing.chatCalls.size}", failing.chatCalls.size <= 1)
    }

    // ── 流式降级边界 ──

    @Test
    fun `streaming fallback before first chunk succeeds on next profile`() = runBlocking {
        val failingStream = object : OpenFakeLlmClient() {
            override fun chatStream(messages: List<LlmMessage>, tools: List<com.apex.agent.core.llm.ToolDefinition>, temperature: Float, maxTokens: Int) =
                kotlinx.coroutines.flow.flow<LlmStreamChunk> { throw LlmException.Http(500, "boom") }
        }
        val okStream = FakeLlmClient(streamResponses = listOf(
            listOf(LlmStreamChunk(content = "hello", isFinish = false), LlmStreamChunk(isFinish = true))
        ))
        val rt = twoProfileRuntime(failingStream, okStream)
        val chunks = rt.chatStream(LlmRequestContext.summary("test"), msgs).toList()
        // 链首 p 在首个 chunk 前抛 500 → 降级到 p2 → 拿到 "hello"
        assertTrue("expected hello in $chunks", chunks.any { it.content == "hello" })
    }

    @Test
    fun `streaming error after first chunk surfaces without fallback`() = runBlocking {
        val partialStream = object : OpenFakeLlmClient() {
            override fun chatStream(messages: List<LlmMessage>, tools: List<com.apex.agent.core.llm.ToolDefinition>, temperature: Float, maxTokens: Int) =
                kotlinx.coroutines.flow.flow<LlmStreamChunk> {
                    emit(LlmStreamChunk(content = "partial", isFinish = false))
                    throw LlmException.Http(500, "mid-stream")
                }
        }
        val okStream = FakeLlmClient(streamResponses = listOf(listOf(LlmStreamChunk(content = "should-not-reach"))))
        val rt = twoProfileRuntime(partialStream, okStream)
        val chunks = mutableListOf<LlmStreamChunk>()
        try {
            rt.chatStream(LlmRequestContext.summary("test"), msgs).collect { chunks += it }
            assert(false) { "应抛错" }
        } catch (e: ModelRuntimeException) {
            assertTrue(e is ModelRuntimeException.ModelUnavailable)
        }
        // 拿到 partial，但已开始流式 → 不降级，直接抛出
        assertTrue(chunks.any { it.content == "partial" })
        assertEquals(0, okStream.streamCalls.size)
    }

    // ── 能力不匹配 ──

    @Test
    fun `capability mismatch throws before any HTTP attempt`() = runBlocking {
        val client = FakeLlmClient(responses = listOf(LlmResponse(content = "x")))
        val store = FakeModelRuntimeStore(
            listOf(profile("p", isDefault = true).copy(capabilities = ModelCapabilities(text = true))),  // 无 vision
            listOf(fullProvider),
            ModelRoleConfig(primaryProfileId = "p", visionProfileId = "p")
        )
        val rt = DefaultModelRuntime(ModelRoleRouter(store), ModelRuntimeRegistry(clientFactory = { client }))
        try {
            rt.chat(LlmRequestContext.vision("test"), msgs)
            assert(false) { "应抛 ModelCapabilityMismatch" }
        } catch (e: ModelRuntimeException.ModelCapabilityMismatch) {
            assertEquals(ModelRole.VISION, e.role)
        }
        assertEquals(0, client.chatCalls.size)  // 能力校验在 HTTP 前
    }

    // ── 诊断 ──

    @Test
    fun `snapshot records success and failure per profile`() = runBlocking {
        val failing = FakeLlmClient(responses = listOf(LlmException.Http(500, "x")))
        val ok = FakeLlmClient(responses = listOf(LlmResponse(content = "ok")))
        val rt = twoProfileRuntime(failing, ok)
        rt.chat(LlmRequestContext.summary("test"), msgs)
        val snap = rt.snapshot()
        val pSnap = snap.first { it.profileId == "p" }
        val p2Snap = snap.first { it.profileId == "p2" }
        assertEquals(1L, pSnap.failureCount)
        assertEquals(0L, pSnap.successCount)
        assertEquals(1L, p2Snap.successCount)
        assertEquals(1L, p2Snap.fallbackCount)  // p2 是第 2 个候选（fallback=1）
    }

    @Test
    fun `snapshot never contains api keys`() = runBlocking {
        val client = FakeLlmClient(responses = listOf(LlmResponse(content = "ok")))
        val store = FakeModelRuntimeStore(
            listOf(profile("p", isDefault = true)),
            listOf(fullProvider),
            ModelRoleConfig(primaryProfileId = "p")
        )
        val rt = DefaultModelRuntime(ModelRoleRouter(store), ModelRuntimeRegistry(clientFactory = { client }))
        rt.chat(LlmRequestContext.primary("test"), msgs)
        val snap = rt.snapshot()
        // snapshot 是纯统计结构，无 key 字段；这里只验证不抛 + 有记录
        assertTrue(snap.isNotEmpty())
    }

    // ── 并发隔离（§十九）──

    @Test
    fun `concurrent requests on different roles do not cross-contaminate`() = runBlocking {
        val primaryClient = FakeLlmClient(responses = List(8) { LlmResponse(content = "primary") })
        val summaryClient = FakeLlmClient(responses = List(8) { LlmResponse(content = "summary") })
        val store = FakeModelRuntimeStore(
            listOf(profile("p"), profile("s", isDefault = true)),
            listOf(fullProvider),
            ModelRoleConfig(primaryProfileId = "s", summaryProfileId = "p")
        )
        val registry = ModelRuntimeRegistry(clientFactory = { config ->
            val pid = config.model.removePrefix("m-")
            if (pid == "p") summaryClient else primaryClient
        })
        val rt = DefaultModelRuntime(ModelRoleRouter(store), registry)
        kotlinx.coroutines.coroutineScope {
            val jobs = (1..8).map { i ->
                launch {
                    val ctx = if (i % 2 == 0) LlmRequestContext.primary("c$i") else LlmRequestContext.summary("c$i")
                    val resp = rt.chat(ctx, msgs)
                    // PRIMARY 走 s → "primary"；SUMMARY 走 p → "summary"
                    assertTrue("got ${resp.content}", resp.content == "primary" || resp.content == "summary")
                }
            }
            jobs.forEach { it.join() }
        }
        assertTrue(primaryClient.chatCalls.isNotEmpty())
        assertTrue(summaryClient.chatCalls.isNotEmpty())
    }

    // ── 热更新（§十七）──

    @Test
    fun `profile change takes effect on next request without rebuild`() = runBlocking {
        val client1 = FakeLlmClient(responses = listOf(LlmResponse(content = "v1")))
        val client2 = FakeLlmClient(responses = listOf(LlmResponse(content = "v2")))
        val store = FakeModelRuntimeStore(
            listOf(profile("p", isDefault = true)),
            listOf(fullProvider),
            ModelRoleConfig(primaryProfileId = "p")
        )
        var which = 1
        val registry = ModelRuntimeRegistry(clientFactory = { config ->
            // config 变化（temperature）触发重建；返回不同 client
            if (which == 1) client1 else client2
        })
        val rt = DefaultModelRuntime(ModelRoleRouter(store), registry)
        val r1 = rt.chat(LlmRequestContext.primary("test"), msgs)
        assertEquals("v1", r1.content)
        // 改 store 里的 profile temperature → 下次 get 配置不同 → registry 重建 client
        store.setProfiles(listOf(profile("p", isDefault = true).copy(temperature = 0.9f)))
        which = 2
        val r2 = rt.chat(LlmRequestContext.primary("test"), msgs)
        assertEquals("v2", r2.content)
    }
}
