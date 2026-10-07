package com.apex.agent.mcp.builtin.memory

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.llm.runtime.SingleClientModelRuntime
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemoryTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量记忆蒸馏器单测 —— 纯 JVM。
 *
 * 覆盖：提示词组装（写入门禁段 / 参照事实注入 / 窗口轮次呈现）、
 * 结构化解析（合法 JSON / 剥离代码块围栏 / skip / new 上限 / update
 * 形状校验 / 非法输入折叠 null）、distill 主链路（Fake client 往返 /
 * 异常时队列语义由调用方保障 → 返回 null）。
 */
class MemoryDistillerTest {

    /** 单响假 client：chat 返回构造时给定的文本，流式不支持。 */
    private class FakeClient(private val reply: String) : LlmClient {
        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): LlmResponse = LlmResponse(content = reply)

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<LlmStreamChunk> = throw UnsupportedOperationException("测试不走流式")
    }

    private fun distiller(reply: String = "") =
        MemoryDistiller(SingleClientModelRuntime(FakeClient(reply)))

    private val window = listOf(
        MemoryTurn("我在学 Rust，因为想写系统工具", "挺好的选择…", 1000L),
        MemoryTurn("Rust 学完了，最近在折腾 WebAssembly", "进展很快…", 2000L)
    )

    // ═══ 提示词组装 ═══

    @Test
    fun `buildPrompt contains write gate, existing facts and window turns`() {
        val prompt = distiller().buildPrompt(
            window = window,
            existingFacts = listOf("用户在学 Rust", "用户喜欢终端工具")
        )

        assertTrue(prompt.contains("写入门槛"))
        assertTrue(prompt.contains("不记录：常识或公开定义"))
        assertTrue(prompt.contains("更新优先于新增"))
        assertTrue(prompt.contains("【已有记忆】"))
        assertTrue(prompt.contains("- 用户在学 Rust"))
        assertTrue(prompt.contains("【对话窗口（共 2 轮）】"))
        assertTrue(prompt.contains("#1 用户：我在学 Rust"))
        assertTrue(prompt.contains("#2 用户：Rust 学完了"))
    }

    // ═══ 结构化解析 ═══

    @Test
    fun `parseOutcome handles new and update arrays`() {
        val outcome = distiller().parseOutcome(
            """{"new": ["用户在学 WebAssembly"], "update": [{"old": "用户在学 Rust", "content": "用户已学完 Rust"}]}"""
        )

        assertNotNull(outcome)
        assertFalse(outcome!!.skipped)
        assertEquals(listOf("用户在学 WebAssembly"), outcome.newFacts)
        assertEquals(1, outcome.updates.size)
        assertEquals("用户在学 Rust", outcome.updates[0].oldContent)
        assertEquals("用户已学完 Rust", outcome.updates[0].newContent)
    }

    @Test
    fun `parseOutcome strips code fences and surrounding prose`() {
        val outcome = distiller().parseOutcome(
            "好的，以下是提取结果：\n```json\n{\"new\": [\"用户偏爱系统编程\"]}\n```\n以上。"
        )

        assertNotNull(outcome)
        assertEquals(listOf("用户偏爱系统编程"), outcome!!.newFacts)
    }

    @Test
    fun `parseOutcome skip flag short circuits`() {
        val outcome = distiller().parseOutcome("""{"skip": true}""")

        assertNotNull(outcome)
        assertTrue(outcome!!.skipped)
        assertTrue(outcome.newFacts.isEmpty())
    }

    @Test
    fun `parseOutcome caps new facts and filters shape violations`() {
        val outcome = distiller().parseOutcome(
            """{"new": ["事实一", "  ", "x", "事实二", "事实三", "事实四"]}"""
        )

        assertNotNull(outcome)
        // ≤3 条上限 + 空白/单字过滤
        assertEquals(listOf("事实一", "事实二", "事实三"), outcome!!.newFacts)
    }

    @Test
    fun `parseOutcome drops malformed update entries but keeps valid ones`() {
        val outcome = distiller().parseOutcome(
            """{"update": [{"old": "用户在学 Rust", "content": "用户已学完 Rust"},
                           {"old": "", "content": "空 old 应被过滤"},
                           {"old": "只有 old 缺 content"}]}"""
        )

        assertNotNull(outcome)
        assertEquals(1, outcome!!.updates.size)
        assertEquals("用户已学完 Rust", outcome.updates[0].newContent)
    }

    @Test
    fun `parseOutcome returns null for garbage and empty extraction`() {
        assertNull(distiller().parseOutcome("完全不是 JSON"))
        assertNull(distiller().parseOutcome("没有大括号的内容"))
        // 空提取（无 new 无 update）按 skip 处理，不返回 null
        val emptyOutcome = distiller().parseOutcome("""{"new": []}""")
        assertNotNull(emptyOutcome)
        assertTrue(emptyOutcome!!.skipped)
    }

    // ═══ distill 主链路（Fake client 往返）═══

    /** 抛错假 client：网络/降级异常路径。 */
    private class ThrowingClient : LlmClient {
        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): LlmResponse = throw IllegalStateException("网络错误")

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<LlmStreamChunk> = throw UnsupportedOperationException()
    }

    @Test
    fun `distill returns parsed outcome from fake client`() = runTest {
        val distiller = MemoryDistiller(SingleClientModelRuntime(FakeClient(
            """{"new": ["用户在学 WebAssembly"]}"""
        )))

        val outcome = distiller.distill(window, listOf("用户在学 Rust"))

        assertNotNull(outcome)
        assertEquals(listOf("用户在学 WebAssembly"), outcome!!.newFacts)
    }

    @Test
    fun `distill returns null when client throws`() = runTest {
        val distiller = MemoryDistiller(SingleClientModelRuntime(ThrowingClient()))

        assertNull(distiller.distill(window, emptyList()))
    }

    @Test
    fun `distill with empty window short circuits without client call`() = runTest {
        val outcome = distiller().distill(emptyList(), emptyList())

        assertNotNull(outcome)
        assertTrue(outcome!!.skipped)
    }
}
