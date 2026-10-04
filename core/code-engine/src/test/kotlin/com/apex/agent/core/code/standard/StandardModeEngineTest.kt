package com.apex.agent.core.code.standard

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.llm.runtime.SingleClientModelRuntime
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolStreamEvent
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardModeEngine 端到端测试（假 LLM + 假工具执行器）：
 *
 * 覆盖：纯文本收官 / 工具调用闭环（含权限 ASK 问答）/ 子代理派发 /
 * PLAN 人控门 / 别名归一（胶囊事件携带注册表 id）/ 预算耗尽收敛 /
 * abort 中止。
 */
class StandardModeEngineTest {

    // ═══ 测试基座 ═══

    /** 剧本化 LlmClient：chatStream 逐条吐出脚本化 chunk 序列。 */
    private class ScriptedLlm : LlmClient {
        val scripts = ConcurrentLinkedQueue<List<LlmStreamChunk>>()
        val chatRequests = mutableListOf<List<LlmMessage>>()

        fun enqueue(vararg chunks: LlmStreamChunk) {
            scripts.add(chunks.toList())
        }

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
        ): Flow<LlmStreamChunk> {
            chatRequests.add(messages)
            val script = scripts.poll() ?: listOf(LlmStreamChunk(content = "(script 缺失)", isFinish = true))
            return flow { script.forEach { emit(it) } }
        }
    }

    /** 假工具执行器：按 id 路由到注册 handler；缺省返回固定成功。 */
    private class FakeExecutor : ToolExecutor {
        val executed = mutableListOf<Pair<String, String>>()
        var handler: (suspend (String, String) -> String)? = null
        var streamOutputs: Map<String, List<String>> = emptyMap()

        override suspend fun execute(toolId: String, arguments: String): String =
            "exec:$toolId"

        override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> {
            executed += toolId to arguments
            val custom = handler
            if (custom != null) {
                return flow {
                    emit(ToolStreamEvent.Output(custom(toolId, arguments)))
                    emit(ToolStreamEvent.Complete(custom(toolId, arguments)))
                }
            }
            val outputs = streamOutputs[toolId] ?: listOf("out:$toolId")
            return flow {
                outputs.forEach { emit(ToolStreamEvent.Output(it)) }
                emit(ToolStreamEvent.Complete(outputs.joinToString("\n")))
            }
        }
    }

    private class FakeTool(override val id: String) : AgentTool {
        override val name: String get() = id
        override val description: String get() = "fake $id"
        override val parametersSchema: String = """{"type":"object","properties":{},"required":[]}"""
        override suspend fun execute(arguments: String): String = "ok"
    }

    private fun engine(
        llm: ScriptedLlm,
        executor: FakeExecutor,
        tools: List<String> = listOf(
            "code_read", "code_edit", "code_write", "code_grep", "code_glob",
            "code_todo", "shell_execute", "code_check"
        )
    ): StandardModeEngine {
        val registry = DefaultToolRegistry().apply { tools.forEach { register(FakeTool(it)) } }
        return StandardModeEngine(
            runtime = SingleClientModelRuntime(llm),
            toolRegistry = registry,
            toolExecutor = executor,
            // 纯内存会话（无持久化），预算收紧让测试快
            maxContextTokensConfig = 128_000,
            preserveRecent = 4
        )
    }

    // ═══ 基础循环 ═══

    @Test
    fun `plain text turn completes with response`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueue(
            LlmStreamChunk(content = "你好"),
            LlmStreamChunk(content = "，任务完成", isFinish = true, usage = null)
        )
        val engine = engine(llm, FakeExecutor())
        val events = engine.execute("看下这个项目").toList()

        assertTrue(events.any { it is AgentEvent.ResponseChunk })
        val complete = events.filterIsInstance<AgentEvent.ResponseComplete>().single()
        assertEquals("你好，任务完成", complete.fullText)
        val done = events.filterIsInstance<AgentEvent.Complete>().single()
        assertEquals(1, done.totalIterations)
        assertEquals(0, done.totalToolCalls)
        // 系统提示词包含画像身份与方法论
        val system = llm.chatRequests.single().first() as LlmMessage.System
        assertTrue(system.content.contains("BUILD agent"))
        assertTrue(system.content.contains("Task Methodology"))
        assertTrue(system.content.contains("task"))
    }

    @Test
    fun `reasoning content streams as thinking chunks`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueue(
            LlmStreamChunk(reasoningContent = "思考中…"),
            LlmStreamChunk(content = "结论", isFinish = true)
        )
        val engine = engine(llm, FakeExecutor())
        val events = engine.execute("q").toList()
        val thinking = events.filterIsInstance<AgentEvent.ThinkingChunk>().single()
        assertEquals("思考中…", thinking.text)
    }

    @Test
    fun `tool call executes via executor and appends result`() = runTest {
        val llm = ScriptedLlm()
        // 第一轮：调用工具；第二轮：收官
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "call_1", name = "code_read", arguments = """{"path":"a.kt"}"""))
            )
        )
        llm.enqueue(LlmStreamChunk(content = "已读取完成", isFinish = true))
        val executor = FakeExecutor()
        executor.handler = { id, _ -> "FILE CONTENT 42" }

        val engine = engine(llm, executor)
        val events = engine.execute("读取 a.kt").toList()

        // 工具事件：Start + OutputChunk + Complete
        val start = events.filterIsInstance<AgentEvent.ToolCallStart>().single()
        assertEquals("code_read", start.toolName)
        val output = events.filterIsInstance<AgentEvent.ToolOutputChunk>().single()
        assertEquals("FILE CONTENT 42", output.chunk)
        val toolComplete = events.filterIsInstance<AgentEvent.ToolCallComplete>().single()
        assertTrue(toolComplete.success)
        assertEquals("code_read", toolComplete.toolName)

        // 第二轮请求包含 assistant(toolCalls) + toolResult（OpenAI 消息协议）
        val secondRequest = llm.chatRequests[1]
        assertTrue(secondRequest.any { it is LlmMessage.Assistant && it.toolCalls.isNotEmpty() })
        val toolResult = secondRequest.filterIsInstance<LlmMessage.ToolResult>().single()
        assertEquals("FILE CONTENT 42", toolResult.content)

        val complete = events.filterIsInstance<AgentEvent.Complete>().single()
        assertEquals(1, complete.totalToolCalls)
        assertEquals(2, complete.totalIterations)
    }

    @Test
    fun `alias tool name is normalized to registry id in events`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueue(
            LlmStreamChunk(toolCalls = listOf(ToolCall(id = "c", name = "read", arguments = """{"path":"x"}""")))
        )
        llm.enqueue(LlmStreamChunk(content = "done", isFinish = true))
        val engine = engine(llm, FakeExecutor())
        val events = engine.execute("q").toList()
        // 模型用别名 read，事件与执行都走注册表 id code_read（胶囊分族一致）
        assertEquals("code_read", events.filterIsInstance<AgentEvent.ToolCallStart>().single().toolName)
    }

    @Test
    fun `parallel streamed tool fragments accumulate by index key`() = runTest {
        val llm = ScriptedLlm()
        // OpenAI 并行流式：首片 id+index，续片只有 index
        llm.enqueue(
            LlmStreamChunk(toolCalls = listOf(ToolCall(id = "call_a", name = "code_read", arguments = """{"pa""", index = 0))),
            LlmStreamChunk(toolCalls = listOf(ToolCall(id = "", name = "", arguments = """th":"a"}""", index = 0)))
        )
        llm.enqueue(LlmStreamChunk(content = "ok", isFinish = true))
        val executor = FakeExecutor()
        val engine = engine(llm, executor)
        val events = engine.execute("q").toList()
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolCallComplete>().size)
        assertEquals("""{"path":"a"}""", executor.executed.single().second)
    }

    // ═══ 权限门 ═══

    @Test
    fun `permission ask suspends until user allows`() = runTest {
        val llm = ScriptedLlm()
        // 写文件（DEFAULT 模式 → ASK）
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "w", name = "code_write", arguments = """{"path":"x","content":"y"}"""))
            )
        )
        llm.enqueue(LlmStreamChunk(content = "已写入", isFinish = true))
        val executor = FakeExecutor()

        val engine = engine(llm, executor)

        val collected = mutableListOf<AgentEvent>()
        val job = async {
            engine.execute("写文件").collect { collected += it }
            true
        }
        // 等到问答事件出现（虚拟时间快进）
        while (collected.none { it is AgentEvent.UserInputRequired }) {
            delay(10)
        }
        engine.submitUserInput("允许")
        job.await()

        val question = collected.filterIsInstance<AgentEvent.UserInputRequired>().single()
        assertTrue(question.prompt.contains("code_write"))
        // 允许后工具被执行
        assertTrue(executor.executed.any { it.first == "code_write" })
        assertTrue(collected.filterIsInstance<AgentEvent.ToolCallComplete>().single().success)
    }

    @Test
    fun `permission denial feeds model a structured refusal`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "w", name = "code_write", arguments = """{"path":"x"}"""))
            )
        )
        llm.enqueue(LlmStreamChunk(content = "好的，我改用其他方式", isFinish = true))
        val executor = FakeExecutor()
        val engine = engine(llm, executor)

        val collected = mutableListOf<AgentEvent>()
        val job = async {
            engine.execute("q").collect { collected += it }
            true
        }
        while (collected.none { it is AgentEvent.UserInputRequired }) {
            delay(10)
        }
        engine.submitUserInput("拒绝")
        job.await()

        // 拒绝 → 不执行
        assertTrue(executor.executed.isEmpty())
        val toolComplete = collected.filterIsInstance<AgentEvent.ToolCallComplete>().single()
        assertFalse(toolComplete.success)
        // 第二轮请求里的 ToolResult 是结构化拒因
        val toolResult = llm.chatRequests[1].filterIsInstance<LlmMessage.ToolResult>().single()
        assertTrue(toolResult.content.contains("Permission denied"))
    }

    @Test
    fun `session allow answer remembers tool for the session`() = runTest {
        val llm = ScriptedLlm()
        // 两次写调用（两轮）
        llm.enqueue(LlmStreamChunk(toolCalls = listOf(ToolCall(id = "w1", name = "code_write", arguments = """{"path":"a"}"""))))
        llm.enqueue(LlmStreamChunk(content = "第一个写完", isFinish = true))
        llm.enqueue(LlmStreamChunk(toolCalls = listOf(ToolCall(id = "w2", name = "code_write", arguments = """{"path":"b"}"""))))
        llm.enqueue(LlmStreamChunk(content = "第二个写完", isFinish = true))
        val engine = engine(llm, FakeExecutor())

        val collected = mutableListOf<AgentEvent>()
        val job1 = async { engine.execute("q").collect { collected += it }; true }
        while (collected.none { it is AgentEvent.UserInputRequired }) delay(10)
        engine.submitUserInput("总是")
        job1.await()

        val collected2 = mutableListOf<AgentEvent>()
        val job2 = async { engine.execute("再写一次").collect { collected2 += it }; true }
        while (!job2.isCompleted) delay(10)
        job2.await()
        // 第二轮不再询问（会话记忆放行），且工具被执行一次
        assertTrue(collected2.none { it is AgentEvent.UserInputRequired })
        assertEquals(1, collected2.filterIsInstance<AgentEvent.ToolCallComplete>().size)
    }

    // ═══ 子代理 ═══

    @Test
    fun `task tool dispatches isolated sub-agent and returns conclusion`() = runTest {
        val llm = ScriptedLlm()
        // 主代理第 1 轮：调用 task
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(
                    ToolCall(
                        id = "t1", name = "task",
                        arguments = """{"description":"找调用点","prompt":"扫描 A 模块","subagent_type":"explore"}"""
                    )
                )
            )
        )
        // 子代理轮（隔离引擎自己的请求）：先探索再结论
        llm.enqueue(
            LlmStreamChunk(toolCalls = listOf(ToolCall(id = "s1", name = "code_grep", arguments = """{"pattern":"x"}""")))
        )
        llm.enqueue(LlmStreamChunk(content = "结论：3 处调用（path:line 各异）", isFinish = true))
        // 主代理收官
        llm.enqueue(LlmStreamChunk(content = "根据子代理结论，共 3 处需要改", isFinish = true))

        val executor = FakeExecutor()
        val engine = engine(llm, executor)
        val events = engine.execute("找调用点").toList()

        // 子代理工具事件以 sub_ 前缀进父流（id 前缀重写）
        val subStart = events.filterIsInstance<AgentEvent.ToolCallStart>()
            .single { it.callId.startsWith(StandardSubAgentDispatcher.SUB_PREFIX) }
        assertEquals("code_grep", subStart.toolName)

        // 子代理的正文不进父流（隔离语义）：父流 ResponseComplete 只有主代理结论
        val completes = events.filterIsInstance<AgentEvent.ResponseComplete>()
        assertEquals(1, completes.size)
        assertEquals("根据子代理结论，共 3 处需要改", completes.single().fullText)

        // task 工具结果包含子代理统计与结论
        val toolComplete = events.filterIsInstance<AgentEvent.ToolCallComplete>()
            .single { it.toolName == "task" }
        assertTrue(toolComplete.success)
        assertTrue(toolComplete.output.contains("结论：3 处调用"))
        assertTrue(toolComplete.output.contains("explore"))
    }

    @Test
    fun `sub-agent readonly profile denies write attempts`() = runTest {
        val llm = ScriptedLlm()
        // 主代理派 explore；子代理尝试 code_write（画像面无此工具，但模型可能幻觉调用）
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(
                    ToolCall(
                        id = "t1", name = "task",
                        arguments = """{"description":"d","prompt":"p","subagent_type":"explore"}"""
                    )
                )
            )
        )
        llm.enqueue(
            LlmStreamChunk(toolCalls = listOf(ToolCall(id = "s1", name = "code_write", arguments = """{"path":"x"}""")))
        )
        llm.enqueue(LlmStreamChunk(content = "只读探索完成", isFinish = true))
        llm.enqueue(LlmStreamChunk(content = "主代理收官", isFinish = true))

        val executor = FakeExecutor()
        val engine = engine(llm, executor)
        val events = engine.execute("q").toList()

        // 子代理的写尝试被 PLAN 模式硬门拒绝（explore 画像 → 只读权限模式）
        val subWrite = events.filterIsInstance<AgentEvent.ToolCallComplete>()
            .single { it.callId.startsWith(StandardSubAgentDispatcher.SUB_PREFIX) }
        assertFalse(subWrite.success)
        assertTrue(subWrite.output.contains("Permission denied"))
        // 执行器从未收到写调用
        assertTrue(executor.executed.none { it.first == "code_write" })
    }

    // ═══ PLAN 人控门 ═══

    @Test
    fun `plan profile produces plan and awaits confirmation then executes`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueue(
            LlmStreamChunk(
                content = "### Goal\n修登录\n\n### Steps\n1. 读取代码\n2. 修改逻辑\n3. 验证\n\n### Verification\n跑测试",
                isFinish = true
            )
        )
        // 确认后执行轮：一次工具 + 收官
        llm.enqueue(LlmStreamChunk(toolCalls = listOf(ToolCall(id = "e", name = "code_edit", arguments = """{"path":"a"}"""))))
        llm.enqueue(LlmStreamChunk(content = "执行完毕", isFinish = true))

        val engine = engine(llm, FakeExecutor())
        engine.updateMode(com.apex.agent.core.engine.AgentMode.PLAN)

        val collected = mutableListOf<AgentEvent>()
        val job = async { engine.execute("修登录").collect { collected += it }; true }
        while (collected.none { it is AgentEvent.PlanAwaitingConfirmation }) delay(10)

        val awaiting = collected.filterIsInstance<AgentEvent.PlanAwaitingConfirmation>().single()
        assertEquals(3, awaiting.plan.steps.size)
        assertEquals("修登录", awaiting.plan.goal)

        engine.submitPlanConfirmation(true, null, null)
        while (!job.isCompleted) delay(10)
        job.await()

        assertTrue(collected.any { it is AgentEvent.PlanConfirmed })
        // 确认后切构建者执行（工具调用发生）
        assertTrue(collected.any { it is AgentEvent.ToolCallComplete })
        assertTrue(collected.filterIsInstance<AgentEvent.ResponseComplete>().isNotEmpty())
    }

    @Test
    fun `plan rejection ends without executing`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueue(LlmStreamChunk(content = "### Goal\ng\n\n### Steps\n1. a\n2. b", isFinish = true))
        val executor = FakeExecutor()
        val engine = engine(llm, executor)
        engine.updateMode(com.apex.agent.core.engine.AgentMode.PLAN)

        val collected = mutableListOf<AgentEvent>()
        val job = async { engine.execute("q").collect { collected += it }; true }
        while (collected.none { it is AgentEvent.PlanAwaitingConfirmation }) delay(10)
        engine.submitPlanConfirmation(false)
        while (!job.isCompleted) delay(10)
        job.await()

        assertTrue(executor.executed.isEmpty())
        assertTrue(collected.none { it is AgentEvent.ToolCallComplete })
        val complete = collected.filterIsInstance<AgentEvent.ResponseComplete>().single()
        assertTrue(complete.fullText.contains("驳回"))
    }

    // ═══ 防循环与预算 ═══

    @Test
    fun `repetitive identical calls trigger guard and force convergence`() = runTest {
        val llm = ScriptedLlm()
        // 连续同参调用（防循环守卫 3 次告警 / 4 次强收敛）：只给 4 个工具脚本，
        // 第 5 个脚本是强收敛后的收尾轮（无工具纯文本）——守卫应在第 4 次
        // 执行后强制跳出，直接消费收尾脚本。
        repeat(4) {
            llm.enqueue(
                LlmStreamChunk(
                    toolCalls = listOf(ToolCall(id = "r$it", name = "code_grep", arguments = """{"pattern":"same"}"""))
                )
            )
        }
        llm.enqueue(LlmStreamChunk(content = "按当前最佳答案收尾", isFinish = true))

        val engine = engine(llm, FakeExecutor())
        val events = engine.execute("q").toList()

        // 存在守卫告警注入后的收官 ResponseComplete
        val completes = events.filterIsInstance<AgentEvent.ResponseComplete>()
        assertTrue(completes.isNotEmpty())
        assertEquals("按当前最佳答案收尾", completes.last().fullText)
        // 恰好执行 4 次后强收敛（第 5 次同参调用被止损）
        val grepCalls = events.filterIsInstance<AgentEvent.ToolCallComplete>()
            .count { it.toolName == "code_grep" }
        assertEquals(4, grepCalls)
    }

    @Test
    fun `abort unblocks pending permission and wraps up`() = runTest {
        val llm = ScriptedLlm()
        // 写文件（DEFAULT 模式 → ASK）→ abort 打断挂起中的问答
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "w", name = "code_write", arguments = """{"path":"x"}"""))
            )
        )
        llm.enqueue(LlmStreamChunk(content = "已收尾", isFinish = true))
        val engine = engine(llm, FakeExecutor())

        val collected = mutableListOf<AgentEvent>()
        val job = async { engine.execute("q").collect { collected += it }; true }
        while (collected.none { it is AgentEvent.UserInputRequired }) delay(10)

        // abort：完成挂起的 userInputDeferred（空串 = 拒绝）+ isRunning=false
        engine.abort()
        while (!job.isCompleted) delay(10)
        job.await()

        // 问答被解锁（拒绝路径）→ 循环收尾不悬挂（job 正常完结 + Complete 事件）
        val complete = collected.filterIsInstance<AgentEvent.Complete>().single()
        assertTrue(complete.summary.isNotEmpty())
        val toolComplete = collected.filterIsInstance<AgentEvent.ToolCallComplete>().single()
        assertFalse(toolComplete.success)
        // 中止后的收官无正文结论（isRunning=false 跳过收尾轮）——诚实语义
        assertTrue(collected.none { it is AgentEvent.ResponseComplete })
    }

    // ═══ 门面 ═══

    @Test
    fun `facade methods update engine state`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueue(LlmStreamChunk(content = "ok", isFinish = true))
        val engine = engine(llm, FakeExecutor())
        engine.updateThinkingLevel(com.apex.agent.core.code.thinking.CodeThinkingLevel.DEEP)
        assertEquals(com.apex.agent.core.code.thinking.CodeThinkingLevel.DEEP, engine.thinkingLevel())
        engine.updateMode(com.apex.agent.core.engine.AgentMode.PLAN)
        engine.updateMode(com.apex.agent.core.engine.AgentMode.BUILD)
        engine.updateGlobalRules("规则：测试")
        engine.updateSessionExtras("附加：时间感知")
        engine.updateForcedTools(setOf("code_read"), exposeAll = false)
        engine.prepareForTask()
        assertEquals(0, engine.historyCount())
        assertNotNull(engine.currentTokenCount())
        assertTrue(engine.maxContextTokens() > 0)
    }

    @Test
    fun `task argument parsing validates and defaults`() {
        val ok = StandardModeEngine.parseSubAgentRequest(
            """{"description":"d","prompt":"p","subagent_type":"research"}"""
        )
        assertEquals(StandardAgentKind.RESEARCH, ok!!.kind)
        val default = StandardModeEngine.parseSubAgentRequest("""{"description":"d","prompt":"p"}""")
        assertEquals(StandardAgentKind.EXPLORE, default!!.kind)
        // 主代理类型不可作为子代理 → 折叠 explore
        val primary = StandardModeEngine.parseSubAgentRequest(
            """{"description":"d","prompt":"p","subagent_type":"build"}"""
        )
        assertEquals(StandardAgentKind.EXPLORE, primary!!.kind)
        assertEquals(null, StandardModeEngine.parseSubAgentRequest("""{"prompt":"p"}"""))
    }

    @Test
    fun `turn budget scales with thinking level`() {
        val llm = ScriptedLlm()
        val engine = engine(llm, FakeExecutor())
        val base = engine.effectiveMaxTurns()
        engine.updateThinkingLevel(com.apex.agent.core.code.thinking.CodeThinkingLevel.APEXCODE)
        assertTrue(engine.effectiveMaxTurns() > base)
        engine.updateThinkingLevel(com.apex.agent.core.code.thinking.CodeThinkingLevel.NONE)
        assertTrue(engine.effectiveMaxTurns() < base)
    }

    @Test
    fun `dangling tool results repaired on restore`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueue(LlmStreamChunk(content = "继续", isFinish = true))
        val engine = engine(llm, FakeExecutor())
        // 直接构造悬挂历史（assistant 有 toolCalls 但无 result）
        val memDir = createTempDir()
        val memory = com.apex.agent.core.code.CodeConversationMemory(
            java.io.File(memDir, "mem")
        )
        memory.bindWorkspace("ws")
        memory.save(
            listOf(
                LlmMessage.User("q"),
                LlmMessage.Assistant("a", listOf(ToolCall("c9", "code_read", "{}")))
                // 缺 ToolResult → 恢复时补占位
            )
        )
        // 记忆落盘是后台单线程异步写——等文件出现再重建引擎（模拟进程重启）
        val wsFile = java.io.File(java.io.File(memDir, "mem"), "ws_ws.json")
        val deadline = System.currentTimeMillis() + 5_000
        while (!wsFile.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("memory file should persist", wsFile.exists())
        val wired = StandardModeEngine(
            runtime = SingleClientModelRuntime(llm),
            toolRegistry = DefaultToolRegistry().apply { register(FakeTool("code_read")) },
            toolExecutor = FakeExecutor(),
            memory = memory
        )
        wired.setActiveWorkspace("ws", "ws", java.io.File(createTempDir(), "ws"))
        wired.execute("继续").toList()
        // 第二轮请求包含补齐的占位 ToolResult（不悬挂 → 协议合法）
        val request = llm.chatRequests.single()
        assertTrue(request.filterIsInstance<LlmMessage.ToolResult>().isNotEmpty())
    }

    // ═══ v1.6 增强：env 块 / 档位硬门 / read-before-edit / 未知工具 / 模型窗口 ═══

    @Test
    fun `system prompt embeds env block and conduct sections`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueue(LlmStreamChunk(content = "ok", isFinish = true))
        val engine = StandardModeEngine(
            runtime = SingleClientModelRuntime(llm),
            toolRegistry = DefaultToolRegistry().apply {
                register(FakeTool("code_read"))
                register(FakeTool("shell_execute"))
            },
            toolExecutor = FakeExecutor(),
            modelInfoProvider = { StandardModeEngine.ModelInfo(modelId = "test-model-x", contextWindow = 200_000) }
        )
        engine.execute("q").toList()
        val system = llm.chatRequests.single().first() as LlmMessage.System
        // env 块：模型 ID + 日期 + 平台
        assertTrue(system.content.contains("You are powered by the model named test-model-x"))
        assertTrue(system.content.contains("Today's date"))
        assertTrue(system.content.contains("Android"))
        // 核心行为段 + 工具纪律段
        assertTrue(system.content.contains("## Core Conduct"))
        assertTrue(system.content.contains("## Tool Discipline"))
        // 模型感知窗口
        assertEquals(200_000, engine.maxContextTokens())
    }

    @Test
    fun `plan mode injects turn-level reminder and hard-gates writes`() = runTest {
        val llm = ScriptedLlm()
        // PLAN 档：写尝试（被档位硬门拒绝，无问答弹窗）→ 纯文本收官
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "w", name = "code_write", arguments = """{"path":"x","content":"y"}"""))
            )
        )
        llm.enqueue(LlmStreamChunk(content = "已按只读约束给出计划", isFinish = true))
        val executor = FakeExecutor()
        val engine = engine(llm, executor)
        engine.updateMode(com.apex.agent.core.engine.AgentMode.PLAN)
        val events = engine.execute("规划一下").toList()

        // 请求级回合提醒注入（消息流末尾，不进会话）
        val request = llm.chatRequests.first()
        val lastMessage = request.last()
        assertTrue(lastMessage is LlmMessage.System)
        assertTrue((lastMessage as LlmMessage.System).content.contains("PLAN mode"))

        // 写调用被档位硬门拒绝：无 UserInputRequired，直接拒因
        assertTrue(events.none { it is AgentEvent.UserInputRequired })
        val toolComplete = events.filterIsInstance<AgentEvent.ToolCallComplete>().single()
        assertFalse(toolComplete.success)
        assertTrue(toolComplete.output.contains("PLAN 档只读硬门"))
        // 执行器从未收到写调用
        assertTrue(executor.executed.isEmpty())
    }

    @Test
    fun `edit without prior read is rejected then allowed after read`() = runTest {
        val llm = ScriptedLlm()
        // 第 1 轮：直接编辑未读文件（拒绝）；第 2 轮：读；第 3 轮：再编辑（放行）；第 4 轮：收官
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(
                    ToolCall(id = "e1", name = "code_edit", arguments = """{"path":"src/App.kt","old_string":"a","new_string":"b"}""")
                )
            )
        )
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "r1", name = "code_read", arguments = """{"path":"src/App.kt"}"""))
            )
        )
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(
                    ToolCall(id = "e2", name = "code_edit", arguments = """{"path":"src/App.kt","old_string":"a","new_string":"b"}""")
                )
            )
        )
        llm.enqueue(LlmStreamChunk(content = "编辑完成", isFinish = true))
        val executor = FakeExecutor()
        // 权限源接入测试：ALLOW 规则注入 code_edit（同时验证设置层规则接线）
        val registry = DefaultToolRegistry().apply {
            listOf("code_read", "code_edit").forEach { register(FakeTool(it)) }
        }
        val engine = StandardModeEngine(
            runtime = SingleClientModelRuntime(llm),
            toolRegistry = registry,
            toolExecutor = executor,
            permissionSource = object : StandardPermissionSource {
                override fun snapshot() = StandardPermissionSource.Snapshot(
                    rules = listOf(
                        StandardPermissionRule("code_edit", StandardPermissionEffect.ALLOW)
                    )
                )
            }
        )
        val events = engine.execute("改文件").toList()

        val edits = events.filterIsInstance<AgentEvent.ToolCallComplete>().filter { it.toolName == "code_edit" }
        assertEquals(2, edits.size)
        // 未读先编：拒绝并引导先读
        assertFalse(edits[0].success)
        assertTrue(edits[0].output.contains("has not been read"))
        // 读取成功登记后，编辑放行
        assertTrue(edits[1].success)
        // 执行器只收到放行的那次编辑
        assertEquals(1, executor.executed.count { it.first == "code_edit" })
        assertEquals(1, executor.executed.count { it.first == "code_read" })
    }

    @Test
    fun `write of unresolvable new file skips read guard`() = runTest {
        val llm = ScriptedLlm()
        // 新文件（路径不存在）写：免检放行（新建合法），但 DEFAULT 模式会 ASK
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "w", name = "code_write", arguments = """{"path":"brand_new_dir/Nuke.kt","content":"x"}"""))
            )
        )
        llm.enqueue(LlmStreamChunk(content = "新建完成", isFinish = true))
        val engine = engine(llm, FakeExecutor())

        val collected = mutableListOf<AgentEvent>()
        val job = async { engine.execute("q").collect { collected += it }; true }
        while (collected.none { it is AgentEvent.UserInputRequired }) delay(10)
        engine.submitUserInput("允许")
        job.await()

        // 走的是权限 ASK（不是 read 守卫拒绝）——新建免检
        val toolComplete = collected.filterIsInstance<AgentEvent.ToolCallComplete>().single()
        assertFalse(toolComplete.output.contains("has not been read"))
    }

    @Test
    fun `unknown tool returns correction suggestions`() = runTest {
        val llm = ScriptedLlm()
        // 模型幻觉调用 code_reads（多打一个 s）→ 修正建议指向 code_read
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "u1", name = "code_reads", arguments = """{"path":"x"}"""))
            )
        )
        llm.enqueue(LlmStreamChunk(content = "已改用正确工具名", isFinish = true))
        val engine = engine(llm, FakeExecutor())
        val events = engine.execute("q").toList()

        val toolComplete = events.filterIsInstance<AgentEvent.ToolCallComplete>().single()
        assertFalse(toolComplete.success)
        assertTrue(toolComplete.output.contains("Unknown tool"))
        assertTrue(toolComplete.output.contains("code_read"))
        // 第二轮请求里模型拿到修正建议（自纠闭环）
        val toolResult = llm.chatRequests[1].filterIsInstance<LlmMessage.ToolResult>().single()
        assertTrue(toolResult.content.contains("Did you mean"))
    }

    @Test
    fun `permission denial carries user feedback text`() = runTest {
        val llm = ScriptedLlm()
        llm.enqueue(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "w", name = "code_write", arguments = """{"path":"x","content":"y"}"""))
            )
        )
        llm.enqueue(LlmStreamChunk(content = "按用户指示换方式", isFinish = true))
        val engine = engine(llm, FakeExecutor())

        val collected = mutableListOf<AgentEvent>()
        val job = async { engine.execute("q").collect { collected += it }; true }
        while (collected.none { it is AgentEvent.UserInputRequired }) delay(10)
        engine.submitUserInput("别直接写，先展示你要改什么")
        job.await()

        val toolResult = llm.chatRequests[1].filterIsInstance<LlmMessage.ToolResult>().single()
        assertTrue(toolResult.content.contains("别直接写，先展示你要改什么"))
    }

    @Test
    fun `parseSubAgentRequest accepts general subagent type`() {
        val request = StandardModeEngine.parseSubAgentRequest(
            """{"description":"通用执行","prompt":"自主完成 X 模块重构","subagent_type":"general"}"""
        )
        assertEquals(StandardAgentKind.GENERAL, request!!.kind)
    }

    @Test
    fun `maxContextTokens falls back to static config without provider`() {
        val llm = ScriptedLlm()
        val engine = engine(llm, FakeExecutor())
        assertEquals(128_000, engine.maxContextTokens())
    }

    private fun createTempDir(): java.io.File =
        kotlin.io.path.createTempDirectory("std-test").toFile()

    // 静态工具（未用 flowOf 的引用防 import 警告）
    @Suppress("unused")
    private fun unusedFlow(): Flow<ToolStreamEvent> = flowOf()
}
