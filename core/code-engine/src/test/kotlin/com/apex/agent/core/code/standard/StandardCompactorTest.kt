package com.apex.agent.core.code.standard

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.llm.runtime.SingleClientModelRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardCompactor 压缩器测试：
 * 触发阈值 / 边界对齐 / LLM 摘述 / 滑窗降级 / 不压缩路径 /
 * 增量摘要（priorSummary）/ 压缩后继续指令 / 摘要预算。
 */
class StandardCompactorTest {

    /**
     * 可编程假 LlmClient：chat 返回固定文本并记录调用（messages +
     * maxTokens）；chatStream 透传 chat。
     */
    private class ScriptedClient(var chatResponse: String? = null) : LlmClient {
        /** chat 调用记录：(messages, maxTokens)。 */
        val chatCalls = mutableListOf<Pair<List<LlmMessage>, Int>>()

        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<com.apex.agent.core.llm.ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): LlmResponse {
            chatCalls += messages to maxTokens
            return LlmResponse(content = chatResponse)
        }

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<com.apex.agent.core.llm.ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<LlmStreamChunk> = flow {
            chatResponse?.let { emit(LlmStreamChunk(content = it, isFinish = true)) }
        }
    }

    private fun messagesOf(count: Int): List<LlmMessage> = buildList {
        add(LlmMessage.User("任务开始"))
        repeat(count) { i ->
            add(LlmMessage.Assistant("步骤 $i", listOf(com.apex.agent.core.llm.ToolCall("c$i", "code_read", "{}"))))
            add(LlmMessage.ToolResult("c$i", "结果 $i " + "x".repeat(200)))
        }
    }

    @Test
    fun `no compaction below threshold`() = runTest {
        val compactor = StandardCompactor(SingleClientModelRuntime(ScriptedClient()))
        val report = compactor.compactIfNeeded(
            messages = messagesOf(4),
            estimatedTokens = 100,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecent = 6
        )
        assertNull(report)
    }

    @Test
    fun `llm summary compaction replaces old segment`() = runTest {
        val summary = "## 任务：修复登录\n- 已改 A.kt\n- 验证通过".let { "x".repeat(80) + it }
        val compactor = StandardCompactor(SingleClientModelRuntime(ScriptedClient(summary)))
        val messages = messagesOf(10)
        val report = compactor.compactIfNeeded(
            messages = messages,
            estimatedTokens = 500_000, // 远超阈值
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecent = 6
        )
        assertNotNull(report)
        assertTrue(report!!.effective)
        assertEquals("LLM_SUMMARY", report.strategy)
        // 替换 = [System 摘要] + 最近段 + [System 继续指令]
        assertTrue(report.replacement.first() is LlmMessage.System)
        assertTrue(report.replacement.last() is LlmMessage.System)
        assertTrue(report.replacement.size < messages.size)
        assertEquals(messages.size - 1 - report.messagesRemoved + 2, report.replacement.size)
        assertTrue(report.afterTokens < report.beforeTokens)
    }

    @Test
    fun `llm failure falls back to sliding window digest`() = runTest {
        // chat 抛异常 → 滑窗降级
        val failing = object : LlmClient {
            override suspend fun chat(
                messages: List<LlmMessage>,
                tools: List<com.apex.agent.core.llm.ToolDefinition>,
                temperature: Float,
                maxTokens: Int
            ): LlmResponse = throw IllegalStateException("network down")

            override fun chatStream(
                messages: List<LlmMessage>,
                tools: List<com.apex.agent.core.llm.ToolDefinition>,
                temperature: Float,
                maxTokens: Int
            ): Flow<LlmStreamChunk> = flow { }
        }
        val compactor = StandardCompactor(SingleClientModelRuntime(failing))
        val report = compactor.compactIfNeeded(
            messages = messagesOf(10),
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecent = 6
        )
        assertNotNull(report)
        assertTrue(report!!.effective)
        assertEquals("SLIDING_WINDOW", report.strategy)
        assertTrue(report.summary.contains("sliding-window"))
        // 最近 6 条保留：replacement = 摘要 + 6 + 继续指令
        assertEquals(8, report.replacement.size)
        // 末尾是压缩后继续指令
        val tail = report.replacement.last()
        assertTrue(tail is LlmMessage.System)
        assertTrue((tail as LlmMessage.System).content.startsWith("SYSTEM: Earlier context was compacted"))
    }

    @Test
    fun `boundary aligns to tool result edge`() {
        val compactor = StandardCompactor(SingleClientModelRuntime(ScriptedClient()))
        val messages = listOf(
            LlmMessage.User("q"),
            LlmMessage.Assistant("a", listOf(com.apex.agent.core.llm.ToolCall("c1", "code_read", "{}"))),
            LlmMessage.ToolResult("c1", "r1"),
            LlmMessage.Assistant("done")
        )
        // 边界 2：切在 assistant(toolCalls) 与 result 之间 → 对齐到 3（result 之后）
        assertEquals(3, compactor.alignBoundary(messages, 2))
        // 边界 1：段末是 User（其后 assistant 属保留段）→ 不动
        assertEquals(1, compactor.alignBoundary(messages, 1))
        // 边界 3：result 之后 → 不动
        assertEquals(3, compactor.alignBoundary(messages, 3))
        // 超尾边界 → 钳制
        assertEquals(4, compactor.alignBoundary(messages, 99))
    }

    @Test
    fun `empty summary gives up compaction`() = runTest {
        val compactor = StandardCompactor(SingleClientModelRuntime(ScriptedClient(null)))
        // 旧段只含 System 注入（滑窗骨架也不产线）→ 两条摘述链都空 → 放弃压缩
        val report = compactor.compactIfNeeded(
            messages = listOf(
                LlmMessage.System("sys1"), LlmMessage.System("sys2"),
                LlmMessage.User("q"), LlmMessage.Assistant("a")
            ),
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecent = 2
        )
        assertNull(report)
    }

    // ═══ 压缩后继续指令（业界标准 compaction_continue 语义）═══

    @Test
    fun `continue directive appended at tail after compaction`() = runTest {
        val summary = "y".repeat(140)
        val compactor = StandardCompactor(SingleClientModelRuntime(ScriptedClient(summary)))
        val messages = messagesOf(8)
        val report = compactor.compactIfNeeded(
            messages = messages,
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecent = 4
        )
        assertNotNull(report)
        assertTrue(report!!.effective)
        val tail = report.replacement.last()
        assertTrue(tail is LlmMessage.System)
        assertEquals(StandardCompactor.COMPACTION_CONTINUE, (tail as LlmMessage.System).content)
        // 指令在最近段之后：倒数第二条仍是原会话消息
        assertTrue(report.replacement[report.replacement.size - 2] is LlmMessage.ToolResult)
        // 尺寸 = 摘要头 + 最近段 + 继续指令；messagesRemoved 照常不含指令
        assertEquals(messages.size - report.messagesRemoved + 1, report.replacement.size)
    }

    // ═══ 增量摘要（priorSummary）═══

    @Test
    fun `prior summary extraction drops header and takes first summary only`() {
        val compactor = StandardCompactor(SingleClientModelRuntime(ScriptedClient()))
        // 命中：去头行取正文
        assertEquals(
            "旧摘要正文：已修复登录缺陷",
            compactor.extractPriorSummary(
                listOf(
                    LlmMessage.System("[SESSION SUMMARY — earlier context was compacted]\n旧摘要正文：已修复登录缺陷"),
                    LlmMessage.User("q")
                )
            )
        )
        // 多条摘要取第一条
        assertEquals(
            "第一条",
            compactor.extractPriorSummary(
                listOf(
                    LlmMessage.System("[SESSION SUMMARY]\n第一条"),
                    LlmMessage.System("[SESSION SUMMARY]\n第二条")
                )
            )
        )
        // 普通系统消息 / 空会话 / 只剩头行 → null
        assertNull(compactor.extractPriorSummary(listOf(LlmMessage.System("sys"), LlmMessage.User("q"))))
        assertNull(compactor.extractPriorSummary(emptyList()))
        assertNull(
            compactor.extractPriorSummary(
                listOf(LlmMessage.System("[SESSION SUMMARY — earlier context was compacted]"))
            )
        )
    }

    @Test
    fun `prior summary feeds the compaction prompt incrementally`() = runTest {
        val client = ScriptedClient("x".repeat(140))
        val compactor = StandardCompactor(SingleClientModelRuntime(client))
        val messages = buildList {
            add(
                LlmMessage.System(
                    "[SESSION SUMMARY — earlier context was compacted]\n" +
                        "已修复登录缺陷：A.kt / B.kt 已验证"
                )
            )
            addAll(messagesOf(8))
        }
        val report = compactor.compactIfNeeded(
            messages = messages,
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecent = 4
        )
        assertNotNull(report)
        // 摘要请求的 System 提示词携带旧摘要正文（增量合并输入）
        val prompt = client.chatCalls.first().first.first() as LlmMessage.System
        assertTrue(prompt.content.contains("已修复登录缺陷：A.kt / B.kt 已验证"))
    }

    @Test
    fun `no prior summary when session has none`() = runTest {
        val client = ScriptedClient("z".repeat(140))
        val compactor = StandardCompactor(SingleClientModelRuntime(client))
        compactor.compactIfNeeded(
            messages = messagesOf(8),
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecent = 4
        )
        // 首轮压缩：提示词不携带旧摘要段
        val prompt = client.chatCalls.first().first.first() as LlmMessage.System
        assertTrue(!prompt.content.contains("<prior-summary>"))
    }

    // ═══ 摘要请求预算 ═══

    @Test
    fun `summary request budget is 1000 tokens`() = runTest {
        val client = ScriptedClient("w".repeat(140))
        val compactor = StandardCompactor(SingleClientModelRuntime(client))
        compactor.compactIfNeeded(
            messages = messagesOf(8),
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecent = 4
        )
        assertEquals(1000, client.chatCalls.first().second)
    }
}
