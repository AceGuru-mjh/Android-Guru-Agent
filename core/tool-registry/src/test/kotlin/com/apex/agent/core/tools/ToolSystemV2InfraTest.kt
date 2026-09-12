package com.apex.agent.core.tools

import com.apex.agent.core.tools.builtin.BaseTool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool System v2 基础设施测试：schema DSL / 校验、注册表（重复策略、
 * 类别查询、模糊搜索、版本号、事件）、执行管线（门控→校验→统计）、
 * 权限状态机、使用统计、相近建议。
 *
 * 与 [DefaultToolExecutorStreamingTest]（v1 流式行为回归）互补：这里
 * 覆盖 v2 新增的横切能力，且全部走真实执行路径（不 mock 注册表/工具）。
 */
class ToolSystemV2InfraTest {

    // ═════════════════════════════════════════════════════════════════
    // ToolSchema：DSL 渲染 + 运行时校验
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `schema renders valid JSON and round-trips through fromRendered`() {
        val schema = toolSchema {
            string("path", required = true, description = "File path")
            integer("limit", defaultValue = 50, minimum = 1.0, maximum = 1000.0)
            boolean("all", defaultValue = false)
            string("mode", enumValues = listOf("fast", "deep"))
        }
        val rendered = schema.render()

        val imported = ToolSchema.fromRendered(rendered)
        assertNotNull(imported)
        assertEquals(setOf("path", "limit", "all", "mode"), imported!!.params.map { it.name }.toSet())
        assertTrue(imported.params.first { it.name == "path" }.required)
        assertFalse(imported.params.first { it.name == "limit" }.required)
        assertEquals(
            listOf("fast", "deep"),
            imported.params.first { it.name == "mode" }.enumValues
        )
    }

    @Test
    fun `schema validation flags missing required and type mismatches`() {
        val schema = toolSchema {
            string("name", required = true)
            integer("count", required = true)
            boolean("verbose")
        }

        val missing = schema.validateArguments("""{"count": 3}""")
        assertFalse(missing.isValid)
        assertTrue(missing.summary().contains("'name'"))

        val typeMismatch = schema.validateArguments("""{"name": "x", "count": "three"}""")
        assertFalse(typeMismatch.isValid)
        assertTrue(typeMismatch.summary().contains("expected integer"))

        val boolMismatch = schema.validateArguments("""{"name": "x", "count": 1, "verbose": "yes"}""")
        assertFalse(boolMismatch.isValid)
        assertTrue(boolMismatch.summary().contains("expected boolean"))
    }

    @Test
    fun `schema validation tolerates unknown extra keys and null optionals`() {
        val schema = toolSchema {
            string("name", required = true)
            integer("count")
        }
        // 模型爱塞垃圾字段 —— 校验只管声明过的约束，多余键不拒绝。
        val ok = schema.validateArguments("""{"name": "x", "junk": [1, 2], "meta": {"a": 1}}""")
        assertTrue(ok.isValid)
        val nullOptional = schema.validateArguments("""{"name": "x", "count": null}""")
        assertTrue(nullOptional.isValid)
    }

    @Test
    fun `schema validation enforces enums and numeric bounds`() {
        val schema = toolSchema {
            string("mode", enumValues = listOf("fast", "deep"))
            integer("limit", minimum = 1.0, maximum = 100.0)
        }
        val badEnum = schema.validateArguments("""{"mode": "turbo"}""")
        assertFalse(badEnum.isValid)
        assertTrue(badEnum.summary().contains("fast/deep"))

        val below = schema.validateArguments("""{"limit": 0}""")
        assertFalse(below.isValid)
        val above = schema.validateArguments("""{"limit": 101}""")
        assertFalse(above.isValid)
    }

    @Test
    fun `schema validation reports invalid json and non-object input`() {
        val schema = toolSchema { string("a") }
        val badJson = schema.validateArguments("not json }{")
        assertFalse(badJson.isValid)
        assertTrue(badJson.summary().contains("not valid JSON"))
        val nonObject = schema.validateArguments("""[1, 2]""")
        assertFalse(nonObject.isValid)
    }

    @Test
    fun `fromRendered returns null for unparseable or property-less schemas`() {
        assertNull(ToolSchema.fromRendered("not json"))
        assertNull(ToolSchema.fromRendered("""{"type": "object"}"""))
        assertNull(ToolSchema.fromRendered("""{"properties": []}"""))
    }

    // ═════════════════════════════════════════════════════════════════
    // ToolRegistry：重复策略 / 类别 / 搜索 / 版本 / 事件
    // ═════════════════════════════════════════════════════════════════

    private fun tool(id: String, name: String = id, schema: String = "{}"): AgentTool =
        object : AgentTool {
            override val id = id
            override val name = name
            override val description = "test tool $id"
            override val parametersSchema = schema
            override suspend fun execute(arguments: String): String = "ok:$id"
        }

    @Test
    fun `register REPLACE returns the displaced tool and REJECT keeps the incumbent`() {
        val registry = DefaultToolRegistry()
        val first = tool("download_file")
        val second = tool("download_file")

        assertNull(registry.register(first, DuplicateToolIdPolicy.REPLACE))
        assertEquals(1, registry.toolCount)

        val displaced = registry.register(second, DuplicateToolIdPolicy.REPLACE)
        assertEquals(first, displaced)
        assertEquals(1, registry.toolCount)
        assertEquals("ok:download_file", kotlinx.coroutines.runBlocking { registry.getTool("download_file")!!.execute("{}") })

        val third = tool("download_file")
        assertNull(registry.register(third, DuplicateToolIdPolicy.REJECT))
        // REJECT 冲突：注册表不变，getTool 仍返回 second。
        assertEquals(second, registry.getTool("download_file"))
        assertEquals(1, registry.toolCount)
    }

    @Test
    fun `registryVersion increments on register and unregister`() {
        val registry = DefaultToolRegistry()
        val v0 = registry.registryVersion
        registry.register(tool("a"))
        val v1 = registry.registryVersion
        assertTrue(v1 > v0)
        registry.register(tool("b"))
        val v2 = registry.registryVersion
        assertTrue(v2 > v1)
        registry.unregister("a")
        assertTrue(registry.registryVersion > v2)
    }

    @Test
    fun `metadata inference drives category queries for legacy tools`() {
        val registry = DefaultToolRegistry()
        registry.register(tool("shell_execute"))
        registry.register(tool("read_file"))
        registry.register(tool("json_path"))
        registry.register(tool("web_search"))

        assertEquals(listOf("read_file"), registry.toolsByCategory(ToolCategory.FILE).map { it.id })
        assertEquals(listOf("json_path"), registry.toolsByCategory(ToolCategory.UTILITY).map { it.id })
        assertEquals(1, registry.toolsByCategory(ToolCategory.WEB).size)

        val grouped = registry.toolsGroupedByCategory()
        // 展示顺序按 ToolCategory.order：SHELL(10) < FILE(20) < WEB(40) < UTILITY(160)
        val order = grouped.keys.map { it.order }
        assertEquals(order.sorted(), order)
        assertTrue(grouped.keys.contains(ToolCategory.SHELL))
        assertEquals(4, grouped.values.sumOf { it.size })
    }

    @Test
    fun `searchTools ranks id prefix over contains and matches tags`() {
        val registry = DefaultToolRegistry()
        registry.register(tool("regex_extract"))
        registry.register(tool("regex_replace"))
        registry.register(tool("web_search", name = "Web Search"))

        assertEquals("regex_extract", registry.searchTools("regex_e")[0].id)
        val regexHits = registry.searchTools("regex").map { it.id }
        assertEquals(setOf("regex_extract", "regex_replace"), regexHits.toSet())
        assertTrue(registry.searchTools("").isEmpty())
        // 名称匹配
        assertEquals(1, registry.searchTools("Web Search".lowercase()).size)
    }

    @Test
    fun `metadataOf distinguishes registered from unknown ids`() {
        val registry = DefaultToolRegistry()
        registry.register(tool("shell_execute"))
        assertNotNull(registry.metadataOf("shell_execute"))
        assertEquals(ToolRisk.HIGH, registry.metadataOf("shell_execute")!!.risk)
        assertNull(registry.metadataOf("no_such_tool"))
    }

    @Test
    fun `registration events fire for register replace and unregister`() {
        val registry = DefaultToolRegistry()
        val events = mutableListOf<ToolRegistrationEvent>()
        val listener = ToolRegistrationListener { events += it }
        registry.addRegistrationListener(listener)

        registry.register(tool("x"))
        registry.register(tool("x")) // replace
        registry.unregister("x")
        registry.unregister("x") // no-op — 不产生事件

        assertEquals(3, events.size)
        val first = events[0] as ToolRegistrationEvent.Registered
        assertFalse(first.replaced)
        val second = events[1] as ToolRegistrationEvent.Registered
        assertTrue(second.replaced)
        assertEquals("x", (events[2] as ToolRegistrationEvent.Unregistered).toolId)

        registry.removeRegistrationListener(listener)
        registry.register(tool("y"))
        assertEquals(3, events.size)
    }

    // ═════════════════════════════════════════════════════════════════
    // ToolSuggester
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `suggester ranks close typos first`() {
        val candidates = listOf("regex_extract", "regex_replace", "web_search", "calculate")
        val suggestions = ToolSuggester.suggest("regex_extact", candidates)
        assertTrue(suggestions.isNotEmpty())
        assertEquals("regex_extract", suggestions[0])
    }

    @Test
    fun `suggester handles prefixes and returns empty for hopeless input`() {
        val candidates = listOf("regex_extract", "regex_replace")
        assertEquals(listOf("regex_extract", "regex_replace"), ToolSuggester.suggest("regex", candidates))
        assertTrue(ToolSuggester.suggest("zzzzzz", listOf("regex_extract")).isEmpty())
        assertTrue(ToolSuggester.suggest("", candidates).isEmpty())
    }

    @Test
    fun `suggestionLine renders a model-facing hint`() {
        val line = ToolSuggester.suggestionLine("regex_extact", listOf("regex_extract"))
        assertNotNull(line)
        assertTrue(line!!.startsWith("Did you mean:"))
        assertTrue(line.contains("regex_extract"))
    }

    @Test
    fun `boundedLevenshtein computes classic distances and prunes far pairs`() {
        assertEquals(3, ToolSuggester.boundedLevenshtein("kitten", "sitting", 5))
        assertEquals(0, ToolSuggester.boundedLevenshtein("same", "same", 3))
        assertEquals(2, ToolSuggester.boundedLevenshtein("abc", "cab", 2))
        // 超出上限 → null（剪枝信号）
        assertNull(ToolSuggester.boundedLevenshtein("kitten", "sitting", 2))
        assertNull(ToolSuggester.boundedLevenshtein("a", "bbbbbbbb", 3))
    }

    // ═════════════════════════════════════════════════════════════════
    // ToolUsageTracker
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `tracker aggregates invocations successes failures and last error`() {
        val tracker = ToolUsageTracker()
        tracker.success(tracker.begin("t"))
        tracker.failure(tracker.begin("t"), "boom")
        tracker.success(tracker.begin("other"))

        val stat = tracker.statFor("t")!!
        assertEquals(2, stat.invocations)
        assertEquals(1, stat.successes)
        assertEquals(1, stat.failures)
        assertEquals(0.5, stat.successRate, 0.001)
        assertTrue(stat.lastError!!.contains("boom"))
        assertNull(tracker.statFor("never-used"))

        assertEquals(3, tracker.totalInvocations())
        assertEquals(2, tracker.stats().size)
        assertTrue(tracker.report().contains("t: 2 calls, 1 ok, 1 failed"))

        tracker.reset()
        assertEquals(0, tracker.totalInvocations())
    }

    @Test
    fun `tracker tolerates null invocation handles`() {
        val tracker = ToolUsageTracker()
        tracker.success(null)
        tracker.failure(null, "ignored")
        assertEquals(0, tracker.totalInvocations())
    }

    // ═════════════════════════════════════════════════════════════════
    // ToolPermissionManager：会话状态机
    // ═════════════════════════════════════════════════════════════════

    private fun highRiskTool(): AgentTool = object : AgentTool {
        override val id = "app_uninstall"
        override val name = "Uninstall"
        override val description = "uninstall an app"
        override val parametersSchema = "{}"
        override val metadata = ToolMetadata.meta(id) { risk(ToolRisk.HIGH) }
        override suspend fun execute(arguments: String): String = "uninstalled"
    }

    private fun lowRiskTool(): AgentTool = tool("json_path")

    @Test
    fun `permission manager prompts once for high risk then allows for session`() = runTest {
        var prompts = 0
        val gate = ToolPermissionManager(confirm = { _, _ -> prompts++; true })

        assertEquals(GateDecision.Allow, gate.check(highRiskTool(), "{}"))
        assertEquals(1, prompts)
        // 会话内第二次：不再询问
        assertEquals(GateDecision.Allow, gate.check(highRiskTool(), "{}"))
        assertEquals(1, prompts)
        assertEquals(SessionToolDecision.ALLOWED_SESSION, gate.decisionFor("app_uninstall"))
        assertTrue(gate.hasPrompted("app_uninstall"))
    }

    @Test
    fun `permission manager denies silently after a session denial`() = runTest {
        val gate = ToolPermissionManager(confirm = { _, _ -> false })
        val high = highRiskTool()

        val first = gate.check(high, "{}")
        assertTrue(first is GateDecision.Deny)
        assertEquals(SessionToolDecision.DENIED_SESSION, gate.decisionFor("app_uninstall"))

        val second = gate.check(high, "{}")
        assertTrue(second is GateDecision.Deny)
        assertTrue((second as GateDecision.Deny).reason.contains("previously denied"))
    }

    @Test
    fun `low risk tools never prompt and selfGated tools bypass the gate`() = runTest {
        var prompts = 0
        val gate = ToolPermissionManager(confirm = { _, _ -> prompts++; true })

        assertEquals(GateDecision.Allow, gate.check(lowRiskTool(), "{}"))
        assertEquals(0, prompts)

        // shell_execute 预置为 selfGated（自带命令级确认，避免双重弹窗）
        val shell = object : AgentTool {
            override val id = "shell_execute"
            override val name = "Shell"
            override val description = "shell"
            override val parametersSchema = "{}"
            override val metadata = ToolMetadata.meta(id) { risk(ToolRisk.HIGH) }
            override suspend fun execute(arguments: String): String = "ok"
        }
        assertEquals(GateDecision.Allow, gate.check(shell, "rm -rf /"))
        assertEquals(0, prompts)
    }

    @Test
    fun `medium risk prompts only when defaultRiskAllowed is false`() = runTest {
        var prompts = 0
        val medium = object : AgentTool {
            override val id = "write_file"
            override val name = "Write"
            override val description = "write"
            override val parametersSchema = "{}"
            override val metadata = ToolMetadata.meta(id) { risk(ToolRisk.MEDIUM) }
            override suspend fun execute(arguments: String): String = "ok"
        }
        val permissive = ToolPermissionManager(confirm = { _, _ -> prompts++; true })
        assertEquals(GateDecision.Allow, permissive.check(medium, "{}"))
        assertEquals(0, prompts)

        val strict = ToolPermissionManager(confirm = { _, _ -> prompts++; true }, defaultRiskAllowed = false)
        assertEquals(GateDecision.Allow, strict.check(medium, "{}"))
        assertEquals(1, prompts)
    }

    @Test
    fun `force decisions and reset work`() = runTest {
        val gate = ToolPermissionManager(confirm = { _, _ -> false })
        gate.allowForSession("app_uninstall")
        assertEquals(GateDecision.Allow, gate.check(highRiskTool(), "{}"))
        gate.denyForSession("app_uninstall")
        assertTrue(gate.check(highRiskTool(), "{}") is GateDecision.Deny)
        gate.reset()
        assertEquals(SessionToolDecision.UNDECIDED, gate.decisionFor("app_uninstall"))
    }

    // ═════════════════════════════════════════════════════════════════
    // DefaultToolExecutor：门控 → 校验 → 统计 管线
    // ═════════════════════════════════════════════════════════════════

    /** 手写 v1 风格 schema 的工具 —— 校验走 fromRendered 宽松导入路径。 */
    private fun legacyTool(id: String, schema: String = "{}", result: String = "legacy-ok"): AgentTool =
        object : AgentTool {
            override val id = id
            override val name = id
            override val description = "legacy"
            override val parametersSchema = schema
            override suspend fun execute(arguments: String): String = result
        }

    @Test
    fun `executor intercepts schema violations before the tool runs`() = runTest {
        val schema = """
            {"type": "object",
             "properties": {"path": {"type": "string"}, "limit": {"type": "integer"}},
             "required": ["path"]}
        """.trimIndent()
        val registry = DefaultToolRegistry()
        registry.register(legacyTool("legacy_read", schema))
        val executor = DefaultToolExecutor(registry)

        val missing = executor.execute("legacy_read", """{"limit": 5}""")
        assertTrue(missing.startsWith("Error:"))
        assertTrue(missing.contains("required but missing"))

        val typeBad = executor.execute("legacy_read", """{"path": 42}""")
        assertTrue(typeBad.contains("expected string"))

        val ok = executor.execute("legacy_read", """{"path": "a/b.txt"}""")
        assertEquals("legacy-ok", ok)
    }

    @Test
    fun `executor leaves unparsable legacy schemas alone`() = runTest {
        val registry = DefaultToolRegistry()
        registry.register(legacyTool("weird", "not even json"))
        val executor = DefaultToolExecutor(registry)

        // 解析不了的 schema → 不校验（绝不误伤 v1 工具）
        assertEquals("legacy-ok", executor.execute("weird", "{}"))
    }

    @Test
    fun `executor unknown tool error carries suggestions not the full id list`() = runTest {
        val registry = DefaultToolRegistry()
        registry.register(legacyTool("regex_extract"))
        registry.register(legacyTool("regex_replace"))
        val executor = DefaultToolExecutor(registry)

        val err = executor.execute("regex_extact", "{}")
        assertTrue(err.startsWith("Error:"))
        assertTrue(err.contains("Did you mean:"))
        assertTrue(err.contains("regex_extract"))
        // 不再倾倒全量 id 清单
        assertFalse(err.contains("regex_replace"))
    }

    @Test
    fun `executor consults the gate and denies gated tools`() = runTest {
        val registry = DefaultToolRegistry()
        registry.register(highRiskTool())
        val gate = ToolPermissionManager(confirm = { _, _ -> false })
        val executor = DefaultToolExecutor(registry, gate = gate)

        val denied = executor.execute("app_uninstall", "{}")
        assertTrue(denied.startsWith("Error:"))
        assertTrue(denied.contains("permission denied"))
        // 门拒绝后工具本体不应被执行
    }

    @Test
    fun `executor records usage for success failure and unknown tools`() = runTest {
        val registry = DefaultToolRegistry()
        registry.register(legacyTool("good", "{}"))
        registry.register(legacyTool("bad", "{}", result = "Error: boom"))
        val tracker = ToolUsageTracker()
        val executor = DefaultToolExecutor(registry, usageTracker = tracker)

        executor.execute("good", "{}")
        executor.execute("bad", "{}")
        executor.execute("missing_tool", "{}")

        assertEquals(1, tracker.statFor("good")!!.successes)
        assertEquals(1, tracker.statFor("bad")!!.failures)
        assertTrue(tracker.statFor("bad")!!.lastError!!.contains("boom"))
        // 未找到工具：工具本体从未运行，不产生统计（查找失败发生在 begin 之前）。
        assertNull(tracker.statFor("missing_tool"))
        assertEquals(2, tracker.totalInvocations())
    }

    @Test
    fun `executor streams gate rejections as error events`() = runTest {
        val registry = DefaultToolRegistry()
        registry.register(highRiskTool())
        val gate = ToolPermissionManager(confirm = { _, _ -> false })
        val executor = DefaultToolExecutor(registry, gate = gate)

        val events = executor.executeStream("app_uninstall", "{}").toList()
        assertEquals(1, events.size)
        assertTrue(events[0] is ToolStreamEvent.Error)
    }

    // ═════════════════════════════════════════════════════════════════
    // StructuredAgentTool / BaseTool 契约
    // ═════════════════════════════════════════════════════════════════

    /** 最小 BaseTool 实现：缺参 / 类型错 / 成功 三条路径。 */
    private class EchoTool : BaseTool(
        id = "echo_v2",
        name = "Echo",
        description = "echo the text argument",
        declaredSchema = toolSchema {
            string("text", required = true, description = "text to echo")
            integer("repeat", defaultValue = 1, minimum = 1.0, maximum = 3.0)
        }
    ) {
        override suspend fun executeStructured(arguments: String): ToolResult {
            val args = when (val parsed = ToolArguments.of(arguments)) {
                is ToolArguments.ParseOutcome.Ok -> parsed.args
                is ToolArguments.ParseOutcome.Bad -> return parsed.result
            }
            val text = args.requireString("text")
            val repeat = args.intWithDefault("repeat", 1)
            return ToolResult.ok(text.repeat(repeat))
        }
    }

    @Test
    fun `structured tool renders the v1 string protocol via execute`() = runTest {
        val echo = EchoTool()
        assertEquals("hihi", echo.execute("""{"text": "hi", "repeat": 2}"""))
    }

    @Test
    fun `base tool converts argument failures into field-precise structured errors`() = runTest {
        val echo = EchoTool()

        val badJson = echo.executeSafe("not json")
        assertFalse(badJson.isSuccess)
        assertEquals(ToolErrorCode.INVALID_JSON, badJson.error!!.code)

        val missing = echo.executeSafe("""{}""")
        assertFalse(missing.isSuccess)
        assertEquals(ToolErrorCode.MISSING_ARGUMENT, missing.error!!.code)
        assertEquals("text", missing.error!!.field)

        val rendered = echo.execute("""{}""")
        assertTrue(rendered.startsWith("Error:"))
        assertTrue(rendered.contains("text"))
    }

    @Test
    fun `base tool contains crashes as execution failures`() = runTest {
        val crashy = object : BaseTool(
            id = "crashy_v2",
            name = "Crashy",
            description = "always throws",
            declaredSchema = toolSchema { string("ignored") }
        ) {
            override suspend fun executeStructured(arguments: String): ToolResult =
                throw IllegalStateException("kaboom")
        }
        val result = crashy.executeSafe("{}")
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.EXECUTION_FAILED, result.error!!.code)
        assertTrue(result.error!!.message.contains("kaboom"))
        // execute() 也绝不抛
        val rendered = kotlinx.coroutines.runBlocking { crashy.execute("{}") }
        assertTrue(rendered.startsWith("Error:"))
    }

    @Test
    fun `executor validates structured tools through their rendered schema`() = runTest {
        val registry = DefaultToolRegistry()
        registry.register(EchoTool())
        val executor = DefaultToolExecutor(registry)

        val violation = executor.execute("echo_v2", "{}")
        assertTrue(violation.startsWith("Error:"))
        assertTrue(violation.contains("required but missing"))

        val boundViolation = executor.execute("echo_v2", """{"text": "x", "repeat": 99}""")
        assertTrue(boundViolation.startsWith("Error:"))

        assertEquals("xx", executor.execute("echo_v2", """{"text": "x", "repeat": 2}"""))
    }
}
