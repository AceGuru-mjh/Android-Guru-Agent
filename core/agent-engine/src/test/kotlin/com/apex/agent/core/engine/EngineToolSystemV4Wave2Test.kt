package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.catalog.ToolActivationStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool System v4.1 — engine-level Wave 2 behaviour:
 *
 * 1. **循环守卫**：同工具+同参数第 3 次起被拦截（不执行），模型收到
 *    loop_detected 自修复指引；不同参数不受影响；
 * 2. **终答回收**：空终稿（无内容无工具调用）→ 去工具 + reminder 重试 →
 *    任务以恢复的最终文本完成，而非 "Empty response from LLM" 报错。
 */
class EngineToolSystemV4Wave2Test {

    private class RecordedCall(val tools: List<ToolDefinition>, val messages: List<LlmMessage>)

    private sealed class Script {
        data class Respond(val text: String) : Script()
        object RespondEmpty : Script()
        data class CallTool(val name: String, val args: String) : Script()
    }

    /** 按次弹出的脚本客户端；记录每次 tools/messages。 */
    private class ScriptedClient(private val script: List<Script>) : LlmClient {
        val calls = mutableListOf<RecordedCall>()
        private var index = 0

        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): LlmResponse = LlmResponse(content = "")

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<LlmStreamChunk> =
            chatStream(messages, tools, temperature, maxTokens, null)

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int,
            toolChoice: com.apex.agent.core.llm.ToolChoiceSpec?
        ): Flow<LlmStreamChunk> = flow {
            val i = index++
            calls += RecordedCall(tools, messages)
            when (val step = script.getOrElse(i) { Script.Respond("(exhausted)") }) {
                is Script.Respond ->
                    emit(LlmStreamChunk(content = step.text, isFinish = true))
                is Script.RespondEmpty ->
                    emit(LlmStreamChunk(isFinish = true))
                is Script.CallTool ->
                    emit(
                        LlmStreamChunk(
                            toolCalls = listOf(ToolCall("call_$i", step.name, step.args)),
                            isFinish = true
                        )
                    )
            }
        }
    }

    private class FakeTool(
        override val id: String,
        override val description: String = "Test tool $id",
        override val parametersSchema: String = """{"type":"object","properties":{}}"""
    ) : AgentTool {
        override val name: String = "Fake $id"
        var executions = 0
        override suspend fun execute(arguments: String): String {
            executions++
            return "ok:$id#$executions"
        }
    }

    private class FakeRegistry(tools: List<AgentTool>) : ToolRegistry {
        private val map = linkedMapOf<String, AgentTool>()
        init { tools.forEach { map[it.id] = it } }
        override fun register(tool: AgentTool) { map[tool.id] = tool }
        override fun unregister(toolId: String) { map.remove(toolId) }
        override fun getTool(toolId: String): AgentTool? = map[toolId]
        override fun getAllTools(): List<AgentTool> = map.values.toList()
        override fun getToolDefinitions(): List<ToolDefinition> =
            map.values.map { ToolDefinition(it.id, it.description, it.parametersSchema) }
    }

    private fun runEngine(
        registry: ToolRegistry,
        client: ScriptedClient,
        maxIterations: Int = 12
    ): List<AgentEvent> = runBlocking {
        val engine = ApexAgentEngine(
            llmClient = client,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            config = AgentConfig(
                mode = AgentMode.BUILD,
                thinkingLevel = ThinkingLevel.NONE,
                maxIterations = maxIterations
            ),
            toolActivation = ToolActivationStore()
        )
        engine.execute("do it").toList()
    }

    // ── 1. loop guard ─────────────────────────────────────────────

    @Test
    fun `identical third call is blocked with guidance`() = runBlocking {
        val tool = FakeTool("read_file")
        val registry = FakeRegistry(listOf(tool, FakeTool("tool_search")))
        val args = """{"path":"/x"}"""
        // 模型连续 4 次发完全相同的调用
        val client = ScriptedClient(
            listOf(
                Script.CallTool("read_file", args),
                Script.CallTool("read_file", args),
                Script.CallTool("read_file", args),
                Script.CallTool("read_file", args),
                Script.Respond("done")
            )
        )
        val events = runEngine(registry, client)

        val completions = events.filterIsInstance<AgentEvent.ToolCallComplete>()
            .filter { it.toolName == "read_file" }
        assertEquals(4, completions.size)
        // 第 1、2 次真实执行（每次结果序号递增）
        assertTrue(completions[0].output.contains("ok:read_file#1"))
        assertTrue(completions[1].output.contains("ok:read_file#2"))
        // 第 3、4 次被守卫拦截：loop_detected 指引，且不再执行
        assertTrue(completions[2].output.contains("loop_detected"))
        assertTrue(completions[3].output.contains("loop_detected"))
        assertEquals(2, tool.executions)
        // 拦截的工具调用标记为失败（success=false），提示出现在事件流里
        assertTrue(!completions[2].success)
        // 任务本身照常完成
        assertNotNull(events.filterIsInstance<AgentEvent.ResponseComplete>().lastOrNull())
    }

    @Test
    fun `different arguments are never blocked`() = runBlocking {
        val tool = FakeTool("read_file")
        val registry = FakeRegistry(listOf(tool, FakeTool("tool_search")))
        val client = ScriptedClient(
            listOf(
                Script.CallTool("read_file", """{"path":"/a"}"""),
                Script.CallTool("read_file", """{"path":"/b"}"""),
                Script.CallTool("read_file", """{"path":"/c"}"""),
                Script.Respond("done")
            )
        )
        runEngine(registry, client)
        assertEquals(3, tool.executions)
    }

    // ── 2. final-answer recovery ──────────────────────────────────

    @Test
    fun `empty final response recovers via reminder retry`() = runBlocking {
        val registry = FakeRegistry(listOf(FakeTool("read_file"), FakeTool("tool_search")))
        // 第 1 轮：正常工具调用；第 2 轮：空响应（无内容无工具）→ 触发回收
        val client = ScriptedClient(
            listOf(
                Script.CallTool("read_file", """{"path":"/x"}"""),
                Script.RespondEmpty,
                // 回收请求（第 3 次 LLM 调用）返回最终文本
                Script.Respond("final synthesized answer")
            )
        )
        val events = runEngine(registry, client)

        // 回收请求不带工具（tools stripped）
        assertTrue(client.calls[2].tools.isEmpty())
        // 回收请求携带 reminder
        val lastUser = client.calls[2].messages.filterIsInstance<LlmMessage.User>().lastOrNull()
        assertNotNull(lastUser)
        assertTrue(lastUser!!.content.contains("FINAL ANSWER"))
        // 任务以恢复文本完成，而非 Empty response 错误
        val complete = events.filterIsInstance<AgentEvent.ResponseComplete>().lastOrNull()
        assertNotNull(complete)
        assertEquals("final synthesized answer", complete!!.fullText)
        assertTrue(events.none { it is AgentEvent.Error })
    }

    @Test
    fun `persistent empty responses still surface the error`() = runBlocking {
        val registry = FakeRegistry(listOf(FakeTool("read_file"), FakeTool("tool_search")))
        val client = ScriptedClient(
            listOf(
                Script.RespondEmpty,   // 主轮空
                Script.RespondEmpty,   // 回收 1 空
                Script.RespondEmpty    // 回收 2 空
            )
        )
        val events = runEngine(registry, client)
        assertTrue(events.any { it is AgentEvent.Error && it.message.contains("Empty response") })
    }
}
