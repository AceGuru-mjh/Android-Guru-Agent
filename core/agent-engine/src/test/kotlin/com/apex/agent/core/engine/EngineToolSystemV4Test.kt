package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmException
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.llm.ToolChoiceSpec
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.catalog.ToolActivationStore
import com.apex.agent.core.tools.catalog.ToolOpenTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool System v4 — engine-level behaviour:
 *
 * 1. 默认模式：请求只携带 CORE 工具（provider 安全名）+ 目录段进系统提示词；
 * 2. 强制模式（「调用函数」圈选）：请求只携带选中工具 + tool_choice=required/Function；
 * 3. tool_open 渐进披露：模型打开目录工具 → 下一轮请求自动带上它；
 * 4. 降级自愈：Provider 400 拒绝带 tools 的请求 → 纯 CORE 重试 → 无工具重试 →
 *    任务照常完成（直接发送对话永远有响应，不再一击报错）。
 */
class EngineToolSystemV4Test {

    // ── recording client ──────────────────────────────────────────

    private class RecordedCall(
        val tools: List<ToolDefinition>,
        val toolChoice: ToolChoiceSpec?,
        val messages: List<LlmMessage>
    )

    /** 每次调用弹出一个脚本项；记录 tools / toolChoice / messages。 */
    private class RecordingLlmClient(
        private val script: List<Script>
    ) : LlmClient {
        sealed class Script {
            data class Respond(val text: String) : Script()
            data class CallTool(val name: String, val args: String) : Script()
            data class Fail(val error: Throwable) : Script()
        }

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
        ): Flow<LlmStreamChunk> = chatStream(messages, tools, temperature, maxTokens, null)

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int,
            toolChoice: ToolChoiceSpec?
        ): Flow<LlmStreamChunk> = flow {
            val i = index++
            calls += RecordedCall(tools, toolChoice, messages)
            val step = script.getOrElse(i) { Script.Respond("(script exhausted)") }
            when (step) {
                is Script.Respond -> emit(LlmStreamChunk(content = step.text, isFinish = true))
                is Script.CallTool -> emit(
                    LlmStreamChunk(
                        toolCalls = listOf(ToolCall("call_$i", step.name, step.args)),
                        isFinish = true
                    )
                )
                is Script.Fail -> throw step.error
            }
        }
    }

    // ── fakes ─────────────────────────────────────────────────────

    private class FakeTool(
        override val id: String,
        override val description: String = "Test tool $id",
        override val parametersSchema: String = """{"type":"object","properties":{}}"""
    ) : AgentTool {
        override val name: String = "Fake $id"
        override suspend fun execute(arguments: String): String = "ok:$id"
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
        client: RecordingLlmClient,
        config: AgentConfig,
        activation: ToolActivationStore = ToolActivationStore()
    ): List<AgentEvent> = runBlocking {
        val engine = ApexAgentEngine(
            llmClient = client,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            config = config,
            toolActivation = activation
        )
        engine.execute("do it").toList()
    }

    private val baseConfig = AgentConfig(
        mode = AgentMode.BUILD,
        thinkingLevel = ThinkingLevel.NONE,
        maxIterations = 8
    )

    // ── 1. default plan ships CORE only, sanitized names, catalog section ──

    @Test
    fun `default request carries core tools only with provider-safe names`() = runBlocking {
        val registry = FakeRegistry(
            listOf(
                FakeTool("read_file"),
                FakeTool("terminal.exec"),
                FakeTool("github_create_issue"),
                FakeTool("tool_search"),
                FakeTool("tool_open"),
                FakeTool("tool_list")
            )
        )
        val client = RecordingLlmClient(listOf(RecordingLlmClient.Script.Respond("done")))
        runEngine(registry, client, baseConfig)

        assertEquals(1, client.calls.size)
        val names = client.calls[0].tools.map { it.name }.toSet()
        assertTrue("read_file" in names)
        assertTrue("terminal_exec" in names)   // dotted id sanitized
        assertTrue("tool_search" in names)     // catalog meta tools ship
        assertFalse("github_create_issue" in names) // catalog-only: NOT shipped
        assertNull(client.calls[0].toolChoice) // default mode: no forcing
    }

    @Test
    fun `system prompt lists provider names and advertises the catalog`() = runBlocking {
        val registry = FakeRegistry(
            listOf(
                FakeTool("read_file"),
                FakeTool("github_create_issue"),
                FakeTool("tool_search"),
                FakeTool("tool_open"),
                FakeTool("tool_list")
            )
        )
        val client = RecordingLlmClient(listOf(RecordingLlmClient.Script.Respond("done")))
        runEngine(registry, client, baseConfig)

        val systemPrompt = client.calls[0]
            .messages.filterIsInstance<LlmMessage.System>().first().content
        assertTrue(systemPrompt.contains("tool_search"))
        assertTrue(systemPrompt.contains("Tool Catalog"))
        // prompt must NOT advertise the raw dotted id when names differ
        val registry2 = FakeRegistry(listOf(FakeTool("terminal.exec"), FakeTool("tool_search")))
        val client2 = RecordingLlmClient(listOf(RecordingLlmClient.Script.Respond("done")))
        runEngine(registry2, client2, baseConfig)
        val prompt2 = client2.calls[0].messages.filterIsInstance<LlmMessage.System>().first().content
        assertTrue(prompt2.contains("- terminal_exec:"))
    }

    // ── 2. forced mode («调用函数» selection) ─────────────────────

    @Test
    fun `forced selection ships only forced tools with required tool_choice`() = runBlocking {
        val registry = FakeRegistry(
            listOf(
                FakeTool("read_file"),
                FakeTool("web_search"),
                FakeTool("github_create_issue"),
                FakeTool("tool_search")
            )
        )
        val client = RecordingLlmClient(listOf(RecordingLlmClient.Script.Respond("done")))
        runEngine(registry, client, baseConfig.copy(forcedToolIds = setOf("web_search")))

        val call = client.calls[0]
        assertEquals(listOf("web_search"), call.tools.map { it.name })
        // single selection → specific function forcing
        assertTrue(call.toolChoice is ToolChoiceSpec.Function)
        assertEquals("web_search", (call.toolChoice as ToolChoiceSpec.Function).name)
    }

    @Test
    fun `multi forced selection uses required`() = runBlocking {
        val registry = FakeRegistry(
            listOf(FakeTool("read_file"), FakeTool("web_search"), FakeTool("tool_search"))
        )
        val client = RecordingLlmClient(listOf(RecordingLlmClient.Script.Respond("done")))
        runEngine(registry, client, baseConfig.copy(forcedToolIds = setOf("read_file", "web_search")))
        assertEquals(ToolChoiceSpec.Required, client.calls[0].toolChoice)
        assertEquals(setOf("read_file", "web_search"), client.calls[0].tools.map { it.name }.toSet())
    }

    // ── 3. progressive disclosure: tool_open → next request ───────

    /** Registry view resolved lazily so ToolOpenTool sees late registrations. */
    private class LateRegistry(private val delegate: () -> ToolRegistry) : ToolRegistry {
        override fun register(tool: AgentTool) = delegate().register(tool)
        override fun unregister(toolId: String) = delegate().unregister(toolId)
        override fun getTool(toolId: String): AgentTool? = delegate().getTool(toolId)
        override fun getAllTools(): List<AgentTool> = delegate().getAllTools()
        override fun getToolDefinitions(): List<ToolDefinition> = delegate().getToolDefinitions()
    }

    @Test
    fun `tool_open activates a catalog tool for the next request`() = runBlocking {
        val activation = ToolActivationStore()
        val base = FakeRegistry(mutableListOf<AgentTool>())
        val late = LateRegistry { base }
        base.register(FakeTool("read_file"))
        base.register(FakeTool("github_create_issue"))
        base.register(FakeTool("tool_search"))
        base.register(ToolOpenTool(late, activation))

        val client = RecordingLlmClient(
            listOf(
                RecordingLlmClient.Script.CallTool(
                    "tool_open",
                    """{"tool_name":"github_create_issue"}"""
                ),
                RecordingLlmClient.Script.Respond("done")
            )
        )
        val events = runEngine(base, client, baseConfig, activation)

        // first request: github_create_issue NOT shipped
        assertFalse("github_create_issue" in client.calls[0].tools.map { it.name })
        // the tool_open execution succeeded
        assertTrue(events.any { it is AgentEvent.ToolCallComplete && it.success })
        // second request: activated tool now ships
        assertTrue("github_create_issue" in client.calls[1].tools.map { it.name })
    }

    // ── 4. tools-request degradation self-heal ────────────────────

    @Test
    fun `tools-related 400 degrades to core-only then no-tools and still completes`() = runBlocking {
        val registry = FakeRegistry(
            listOf(
                FakeTool("read_file"),
                FakeTool("tool_search"),
                FakeTool("tool_open"),
                FakeTool("tool_list")
            )
        )
        val client = RecordingLlmClient(
            listOf(
                RecordingLlmClient.Script.Fail(
                    LlmException.Http(400, """{"error":{"message":"Invalid function name 'terminal.exec'"}}""")
                ),
                // after degradation level 1 (core-only), some providers still 400
                RecordingLlmClient.Script.Fail(
                    LlmException.Http(400, """{"error":{"message":"tools not supported"}}""")
                ),
                RecordingLlmClient.Script.Respond("recovered answer")
            )
        )
        val events = runEngine(registry, client, baseConfig)

        // three LLM calls: original → core-only retry → no-tools retry
        assertEquals(3, client.calls.size)
        // last attempt carries no tools
        assertTrue(client.calls[2].tools.isEmpty())
        // and the task completed with the final text
        val complete = events.filterIsInstance<AgentEvent.ResponseComplete>().lastOrNull()
        assertEquals("recovered answer", complete?.fullText)
    }

    @Test
    fun `non-tools errors still propagate`() = runBlocking {
        val registry = FakeRegistry(listOf(FakeTool("read_file"), FakeTool("tool_search")))
        val client = RecordingLlmClient(
            listOf(RecordingLlmClient.Script.Fail(LlmException.Http(401, "bad key")))
        )
        val events = runEngine(registry, client, baseConfig)
        // 401 is not tools-related → no degradation retries, error surfaces
        assertEquals(1, client.calls.size)
        assertTrue(events.any { it is AgentEvent.Error || it is AgentEvent.ResponseComplete })
    }
}
