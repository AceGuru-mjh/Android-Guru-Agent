package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.ShortcutDefinition
import com.apex.agent.core.tools.ShortcutRegistry
import com.apex.agent.core.tools.ShortcutSuggester
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolTraceRecorder
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool System v3 — Shortcut 组合动作系统与新内置工具测试。
 *
 * Shortcut：定义解析（合法/非法形态逐一拒绝）、参数占位符校验、
 * JSON 感知转义替换、注册表 upsert/移除/导出、编译后风险继承（联合
 * 取最坏）、执行链路（经真实 ToolExecutor 的顺序步骤 + 首错即停）、
 * 挖掘建议（成功相邻 bigram）。
 *
 * 新工具：wait（边界/成功）、json_transform（七操作管线）、
 * version_compare（SemVer 阶梯）、tool_batch_run / shortcut_define /
 * shortcut_list / shortcut_run（模型入口行为）。
 */
class ShortcutSystemTest {

    // ═══ fixtures ═══════════════════════════════════════════════════

    /** 透传执行器：返回调用日志（模型侧可验证每次调用与参数）。 */
    private class RecordingExecutor : ToolExecutor {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun execute(toolId: String, arguments: String): String {
            calls += toolId to arguments
            return "OK[$toolId]"
        }

        override fun executeStream(toolId: String, arguments: String) = flow {
            emit(com.apex.agent.core.tools.ToolStreamEvent.Output(execute(toolId, arguments)))
        }
    }

    private fun registryWith(vararg tools: AgentTool): ToolRegistry {
        val registry = DefaultToolRegistry()
        tools.forEach { registry.register(it) }
        return registry
    }

    /** 极简工具（供快捷方式步骤引用）。 */
    private class StubTool(override val id: String, private val risk: ToolRisk = ToolRisk.LOW) :
        AgentTool {
        override val name = id
        override val description = "stub"
        override val parametersSchema = "{}"
        override val metadata = com.apex.agent.core.tools.ToolMetadata(
            id, ToolCategory.UTILITY, risk
        )

        override suspend fun execute(arguments: String): String = "OK[$id]"
    }

    private val knownIds = setOf("web_search", "write_file", "read_file", "app_uninstall")

    private val validDefinition = """
        {
          "id": "shortcut.save_search",
          "name": "Search and save",
          "description": "Run a search, save the result",
          "precondition": "network available",
          "params": [
            {"name": "query", "type": "string", "required": true},
            {"name": "path", "type": "string", "required": true}
          ],
          "steps": [
            {"tool": "web_search", "arguments": "{\"query\": \"{query}\"}"},
            {"tool": "write_file", "arguments": "{\"path\": \"{path}\", \"content\": \"{0}\"}"}
          ]
        }
    """.trimIndent()

    // ═══ 定义解析与校验 ═════════════════════════════════════════════

    @Test
    fun `valid definition parses with all fields intact`() {
        val definition = ShortcutDefinition.parse(validDefinition, knownIds)
        assertEquals("shortcut.save_search", definition.id)
        assertEquals("Search and save", definition.name)
        assertEquals("network available", definition.precondition)
        assertEquals(2, definition.params.size)
        assertEquals(2, definition.steps.size)
        assertEquals("web_search", definition.steps[0].toolId)
        assertTrue(definition.params[0].required)
    }

    @Test
    fun `invalid ids are rejected with the naming rule in the message`() {
        val cases = listOf(
            "save_search" to "missing shortcut. prefix",
            "shortcut.Save_Search" to "uppercase rejected",
            "shortcut.a" to "too short",
            "shortcut.has-dash" to "dash rejected"
        )
        cases.forEach { (badId, label) ->
            val json = validDefinition.replace("shortcut.save_search", badId)
            try {
                ShortcutDefinition.parse(json, knownIds)
                org.junit.Assert.fail("$label: expected rejection for id '$badId'")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("shortcut."))
            }
        }
    }

    @Test
    fun `unknown tool references and dead params are rejected`() {
        // 步骤引用未注册工具。
        val unknownTool = validDefinition.replace("\"web_search\"", "\"not_a_tool\"")
        try {
            ShortcutDefinition.parse(unknownTool, knownIds)
            org.junit.Assert.fail("expected rejection for unknown tool")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("not_a_tool"))
        }

        // 声明但从未使用的参数（误导模型的 schema 视图）：在合法定义上
        // 追加一个从未被模板引用的额外参数。
        val deadParam = validDefinition.replace(
            "\"params\": [",
            "\"params\": [" +
                "{\"name\": \"never_used\", \"type\": \"string\", \"required\": false},"
        )
        try {
            ShortcutDefinition.parse(deadParam, knownIds)
            org.junit.Assert.fail("expected rejection for dead param")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("never_used"))
        }
    }

    @Test
    fun `placeholder referencing an undeclared param is rejected`() {
        val badPlaceholder = validDefinition.replace("{query}", "{typo_query}")
        try {
            ShortcutDefinition.parse(badPlaceholder, knownIds)
            org.junit.Assert.fail("expected rejection for unknown placeholder")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("typo_query"))
        }
    }

    @Test
    fun `step count is capped at eight`() {
        val nineSteps = (1..9).joinToString(",") {
            """{"tool": "read_file", "arguments": "{\"i\": \"$it\"}"}"""
        }
        val definition = """
            {
              "id": "shortcut.nine",
              "name": "Nine",
              "description": "",
              "precondition": "",
              "params": [{"name": "i", "type": "string", "required": true}],
              "steps": [$nineSteps]
            }
        """.trimIndent()
        try {
            ShortcutDefinition.parse(definition, knownIds)
            org.junit.Assert.fail("expected rejection for 9 steps")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("max 8"))
        }
    }

    // ═══ 注册表与编译 ═══════════════════════════════════════════════

    @Test
    fun `registry upserts replaces and exports definitions`() {
        val registry = ShortcutRegistry()
        val definition = ShortcutDefinition.parse(validDefinition, knownIds)
        assertFalse(registry.upsert(definition))
        assertTrue(registry.find("shortcut.save_search") != null)

        // 重定义（改名）→ 替换。
        val redefined = ShortcutDefinition.parse(
            validDefinition.replace("Search and save", "Search and save v2"),
            knownIds
        )
        assertTrue(registry.upsert(redefined))
        assertEquals(1, registry.size())
        assertEquals("Search and save v2", registry.find("shortcut.save_search")!!.name)

        // 导出 JSON 包含全部字段。
        val exported = registry.exportJson().toString()
        assertTrue(exported.contains("shortcut.save_search"))
        assertTrue(exported.contains("web_search"))

        assertTrue(registry.remove("shortcut.save_search"))
        assertNull(registry.find("shortcut.save_search"))
        assertEquals(0, registry.size())
    }

    @Test
    fun `compiled shortcut inherits the worst risk among its steps`() {
        val registry = ShortcutRegistry()
        val toolRegistry = registryWith(
            StubTool("web_search"),
            StubTool("app_uninstall", ToolRisk.HIGH)
        )
        val definition = ShortcutDefinition.parse(
            """
            {
              "id": "shortcut.risky_chain",
              "name": "Risky",
              "description": "",
              "precondition": "",
              "params": [],
              "steps": [
                {"tool": "web_search", "arguments": "{}"},
                {"tool": "app_uninstall", "arguments": "{}"}
              ]
            }
            """.trimIndent(),
            toolRegistry.getAllTools().map { it.id }.toSet()
        )
        val compiled = registry.compile(definition, RecordingExecutor(), toolRegistry)
        assertEquals("shortcut.risky_chain", compiled.id)
        assertEquals(ToolRisk.HIGH, compiled.metadata.risk)
        assertEquals(ToolCategory.SKILL, compiled.metadata.category)
        assertTrue(compiled.description.contains("Precondition"))
        assertTrue(compiled.description.contains("app_uninstall"))
    }

    // ═══ 执行链路 ═══════════════════════════════════════════════════

    @Test
    fun `compiled shortcut substitutes params JSON-safely and pipes step outputs`() = runTest {
        val executor = RecordingExecutor()
        val registry = ShortcutRegistry()
        val toolRegistry = registryWith(StubTool("web_search"), StubTool("write_file"))
        val definition = ShortcutDefinition.parse(validDefinition, knownIds)
        val compiled = registry.compile(definition, executor, toolRegistry)

        val result = compiled.execute(
            """{"query": "kotlin release \"notes\"", "path": "notes.md"}"""
        )
        assertTrue(result.contains("batch result"))
        assertEquals(2, executor.calls.size)
        // 字符串参数被 JSON 转义（引号存活）。
        assertEquals(
            """{"query": "kotlin release \"notes\""}""",
            executor.calls[0].second
        )
        // {path} 替换 + {0} 引用第 0 步输出。
        assertEquals(
            """{"path": "notes.md", "content": "OK[web_search]"}""",
            executor.calls[1].second
        )
    }

    @Test
    fun `numeric and boolean params substitute raw`() = runTest {
        val executor = RecordingExecutor()
        val registry = ShortcutRegistry()
        val toolRegistry = registryWith(StubTool("read_file"))
        val definition = ShortcutDefinition.parse(
            """
            {
              "id": "shortcut.typed_params",
              "name": "Typed",
              "description": "",
              "precondition": "",
              "params": [
                {"name": "limit", "type": "integer", "required": true},
                {"name": "verbose", "type": "boolean", "required": false}
              ],
              "steps": [
                {"tool": "read_file", "arguments": "{\"limit\": {limit}, \"verbose\": {verbose}}"}
              ]
            }
            """.trimIndent(),
            toolRegistry.getAllTools().map { it.id }.toSet()
        )
        val compiled = registry.compile(definition, executor, toolRegistry)
        compiled.execute("""{"limit": 50, "verbose": true}""")
        assertEquals("""{"limit": 50, "verbose": true}""", executor.calls[0].second)
    }

    @Test
    fun `missing required param yields a field-precise error`() = runTest {
        val executor = RecordingExecutor()
        val registry = ShortcutRegistry()
        val toolRegistry = registryWith(StubTool("web_search"), StubTool("write_file"))
        val compiled = registry.compile(
            ShortcutDefinition.parse(validDefinition, knownIds), executor, toolRegistry
        )
        val result = compiled.execute("""{"query": "only query"}""")
        assertTrue(result.startsWith("Error: invalid argument"))
        assertTrue(result.contains("path"))
        assertEquals(0, executor.calls.size)
    }

    @Test
    fun `failing step halts the shortcut like a batch`() = runTest {
        val failing = object : ToolExecutor {
            override suspend fun execute(toolId: String, arguments: String): String =
                if (toolId == "write_file") "Error: not found: no such tool" else "OK[$toolId]"

            override fun executeStream(toolId: String, arguments: String) = flow {
                emit(com.apex.agent.core.tools.ToolStreamEvent.Output(execute(toolId, arguments)))
            }
        }
        val registry = ShortcutRegistry()
        val toolRegistry = registryWith(StubTool("web_search"), StubTool("write_file"))
        val compiled = registry.compile(
            ShortcutDefinition.parse(validDefinition, knownIds), failing, toolRegistry
        )
        val result = compiled.execute("""{"query": "q", "path": "p"}""")
        assertTrue(result.contains("halted"))
        assertTrue(result.contains("Error: not found"))
    }

    // ═══ 挖掘建议 ═══════════════════════════════════════════════════

    @Test
    fun `suggester mines recurring successful adjacent pairs only`() {
        val recorder = ToolTraceRecorder()
        // 时间序：read↔write 三轮完整循环（有序对 (read→write) 恰 3 次，
        // (write→read) 2 次）+ 失败打断 + 一次孤立调用。
        val sequence = listOf(
            "read_file" to true,
            "write_file" to true,
            "read_file" to true,
            "write_file" to true,
            "read_file" to true,
            "write_file" to true,
            "shell_execute" to false, // 失败：邻接被打断
            "write_file" to true,
            "web_search" to true // 只出现一次
        )
        // 时间正序灌入：先发生的先 begin/complete（deque 头 = 最新）。
        sequence.forEach { (tool, ok) ->
            val handle = recorder.begin(tool, "{}")
            if (ok) recorder.complete(handle) else recorder.completeFailure(handle, "boom")
        }

        val suggestions = ShortcutSuggester(minCount = 3).suggest(recorder)
        // read→write 3 次成为建议；失败打断的 shell→write 不够格。
        assertEquals(1, suggestions.size)
        assertEquals("read_file", suggestions[0].firstToolId)
        assertEquals("write_file", suggestions[0].secondToolId)
        assertEquals(3, suggestions[0].count)

        // 骨架可被 parse 接受（补注册表后）。
        val skeleton = suggestions[0].toDefinitionSkeleton()
        val parsed = ShortcutDefinition.parse(
            skeleton,
            setOf("read_file", "write_file")
        )
        assertEquals("shortcut.read_file_then_write_file", parsed.id)
        assertEquals(2, parsed.steps.size)
    }
}

class NewToolsV3Test {

    // ═══ wait ═══════════════════════════════════════════════════════

    @Test
    fun `wait completes with an elapsed report`() = runTest {
        val tool = WaitTool()
        val result = tool.executeStructured("""{"duration_ms": 100}""")
        assertTrue(result.isSuccess)
        assertTrue(result.output!!.contains("waited"))
    }

    @Test
    fun `wait bounds are enforced with suggestions`() = runTest {
        val tool = WaitTool()
        listOf(0, -1, 300_001).forEach { bad ->
            val result = tool.executeStructured("""{"duration_ms": $bad}""")
            assertFalse(result.isSuccess)
            assertEquals(
                com.apex.agent.core.tools.ToolErrorCode.INVALID_ARGUMENT,
                result.error!!.code
            )
            assertTrue(result.error.suggestion!!.contains("split"))
        }
        // 300_000 是合法上限。
        assertTrue(tool.executeStructured("""{"duration_ms": 300000}""").isSuccess)
    }

    @Test
    fun `wait metadata is read-only and retry-safe`() {
        val metadata = WaitTool().metadata
        assertEquals(ToolRisk.LOW, metadata.risk)
        assertTrue(metadata.annotations.retrySafe)
    }

    // ═══ json_transform ═══════════════════════════════════════════════

    private val document = """
        {"title": "Release 1.2", "url": "https://example.com", "secret": "abc",
         "items": [{"name": "a", "v": 1}, {"name": "b", "v": 2}]}
    """.trimIndent()

    private fun transform(ops: String): ToolResult = kotlinx.coroutines.runBlocking {
        // executeSafe：把 ToolArgumentException（含 shapeError）转成结构化结果，
        // 与 executor 真实路径一致。
        JsonTransformTool().executeSafe(
            """{"json": "${jsonEscape(document)}", "operations": $ops}"""
        )
    }

    /** 把任意文本安全嵌入 JSON 字符串字面量（与 ShortcutTool 的替换规则一致）。 */
    private fun jsonEscape(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    @Test
    fun `pick projects a key subset`() {
        val result = transform(
            """[{"op": "pick", "keys": ["title", "url"]}]"""
        )
        assertTrue(result.isSuccess)
        val output = result.output!!
        assertTrue(output.contains("title"))
        assertFalse(output.contains("secret"))
    }

    @Test
    fun `omit drops keys and rename maps them`() {
        val omitted = transform("""[{"op": "omit", "keys": ["secret"]}]""")
        assertTrue(omitted.isSuccess)
        assertFalse(omitted.output!!.contains("secret"))

        val renamed = transform("""[{"op": "rename", "from": "url", "to": "link"}]""")
        assertTrue(renamed.isSuccess)
        assertTrue(renamed.output!!.contains("link"))
        assertFalse(renamed.output!!.contains("url"))
    }

    @Test
    fun `map_pick projects across arrays of objects`() {
        // 根是 object：pick 取 items → values 展开 → flatten 压平 → map_pick 投影。
        val result = transform(
            """[
                {"op": "pick", "keys": ["items"]},
                {"op": "values"},
                {"op": "flatten"},
                {"op": "map_pick", "keys": ["name"]}
            ]"""
        )
        assertTrue(result.isSuccess)
        val output = result.output!!
        assertTrue(output.contains("\"name\":\"a\""))
        assertFalse(output.contains("\"v\""))
    }

    @Test
    fun `wrap and values reshape the document`() {
        val wrapped = transform("""[{"op": "wrap", "key": "envelope"}]""")
        assertTrue(wrapped.isSuccess)
        assertTrue(wrapped.output!!.contains("\"envelope\":"))

        val values = transform("""[{"op": "values"}]""")
        assertTrue(values.isSuccess)
        assertTrue(values.output!!.startsWith("["))
    }

    @Test
    fun `operations pipeline in declared order`() {
        // pick → values → flatten → map_pick：声明序即执行序，
        // 最终产物只含 items 的 name 字段。
        val result = transform(
            """[
                {"op": "pick", "keys": ["items"]},
                {"op": "values"},
                {"op": "flatten"},
                {"op": "map_pick", "keys": ["name"]}
            ]"""
        )
        assertTrue(result.isSuccess)
        val output = result.output!!
        assertTrue(output.contains("a"))
        assertFalse(output.contains("secret"))
        assertFalse(output.contains("title"))
    }

    @Test
    fun `shape mismatch names the actual document type`() {
        // 对 object 施 flatten → 报错指明期望 array。
        val result = transform("""[{"op": "flatten"}]""")
        assertFalse(result.isSuccess)
        val error = result.error!!
        assertEquals(
            com.apex.agent.core.tools.ToolErrorCode.INVALID_ARGUMENT,
            error.code
        )
        assertTrue(error.message!!.contains("expects a array"))
        assertTrue(error.message!!.contains("got object"))
    }

    @Test
    fun `unknown op and invalid json are rejected`() {
        val badOp = transform("""[{"op": "explode"}]""")
        assertFalse(badOp.isSuccess)
        assertTrue(badOp.error!!.suggestion!!.contains("pick, omit, rename, flatten, map_pick, wrap, values"))

        val badJson = kotlinx.coroutines.runBlocking {
            JsonTransformTool().executeStructured(
                """{"json": "not json at all", "operations": "[]"}"""
            )
        }
        assertFalse(badJson.isSuccess)
        assertEquals(
            com.apex.agent.core.tools.ToolErrorCode.INVALID_JSON,
            badJson.error!!.code
        )
    }

    // ═══ version_compare ═════════════════════════════════════════════

    private fun compare(a: String, b: String): String = kotlinx.coroutines.runBlocking {
        val result = VersionCompareTool().executeStructured("""{"a": "$a", "b": "$b"}""")
        assertTrue(result.isSuccess)
        result.output!!
    }

    @Test
    fun `numeric core segments compare numerically not lexically`() {
        assertTrue(compare("1.10.0", "1.9.0").contains("NEWER"))
        assertTrue(compare("2.0", "2.0.1").contains("OLDER"))
        assertTrue(compare("1.2", "1.2.0").contains("EQUAL"))
    }

    @Test
    fun `pre-release ladder follows semver section 11`() {
        assertTrue(compare("2.0.0-alpha.1", "2.0.0-alpha.2").contains("OLDER"))
        assertTrue(compare("2.0.0-alpha.2", "2.0.0-beta").contains("OLDER"))
        assertTrue(compare("2.0.0-rc.1", "2.0.0").contains("OLDER"))
        assertTrue(compare("1.0.0", "1.0.0-rc.1").contains("NEWER"))
    }

    @Test
    fun `prefixes and build metadata are tolerated`() {
        assertTrue(compare("v1.2.3", "1.2.3").contains("EQUAL"))
        assertTrue(compare("v1.2.3+build.7", "1.2.3").contains("EQUAL"))
        assertTrue(compare("1.2", "1.2.3").contains("OLDER"))
    }

    @Test
    fun `parsed tuples are echoed for model self-checking`() {
        val output = compare("1.10.0", "1.9.0")
        assertTrue(output.contains("core="))
        assertTrue(output.contains("pre="))
    }

    // ═══ tool_batch_run（模型入口）════════════════════════════════════

    @Test
    fun `tool_batch_run executes steps and reports the batch shape`() = runTest {
        val inner = object : ToolExecutor {
            override suspend fun execute(toolId: String, arguments: String): String =
                "OK[$toolId:$arguments]"

            override fun executeStream(toolId: String, arguments: String) = flow {
                emit(com.apex.agent.core.tools.ToolStreamEvent.Output(execute(toolId, arguments)))
            }
        }
        val tool = ToolBatchRunTool(inner)
        val result = tool.executeStructured(
            """
            {"steps": [
              {"tool": "read_file", "arguments": "{\"path\": \"a.txt\"}"},
              {"tool": "write_file", "arguments": "{\"path\": \"b.txt\", \"content\": \"{0}\"}"}
            ]}
            """.trimIndent()
        )
        assertTrue(result.isSuccess)
        val output = result.output!!
        assertTrue(output.contains("batch result (2 steps, complete)"))
        assertTrue(output.contains("[0] read_file → ok"))
        assertTrue(output.contains("OK[read_file:{\"path\": \"a.txt\"}]"))
    }

    @Test
    fun `tool_batch_run rejects empty and oversized step arrays`() = runTest {
        val tool = ToolBatchRunTool(RecordingExecutor())
        val empty = tool.executeStructured("""{"steps": []}""")
        assertFalse(empty.isSuccess)
        assertTrue(empty.error!!.suggestion!!.contains("directly"))

        val big = (1..17).joinToString(",") {
            """{"tool": "t", "arguments": "{}"}"""
        }
        val oversized = tool.executeStructured("""{"steps": [$big]}""")
        assertFalse(oversized.isSuccess)
        assertTrue(oversized.error!!.suggestion!!.contains("shortcut"))
    }

    // ═══ shortcut_*（模型入口）════════════════════════════════════════

    @Test
    fun `shortcut_define validates registers and hot-registers the compiled tool`() = runTest {
        val shortcutRegistry = ShortcutRegistry()
        val executor = RecordingExecutor()
        val toolRegistry = registryWith(StubTool("web_search"), StubTool("write_file"))

        val define = ShortcutDefineTool(shortcutRegistry, toolRegistry, executor)
        val definitionJson = """
            {
              "id": "shortcut.save_search",
              "name": "Search and save",
              "description": "test composite",
              "precondition": "",
              "params": [
                {"name": "query", "type": "string", "required": true}
              ],
              "steps": [
                {"tool": "web_search", "arguments": "{\"query\": \"{query}\"}"}
              ]
            }
        """.trimIndent()

        // 坏 JSON（非对象）→ 结构化失败。
        val bad = define.executeStructured("""{"definition": "not json at all"}""")
        assertFalse(bad.isSuccess)

        // 合法定义 → 注册 + 热注册编译工具。
        val ok = define.executeStructured(
            """{"definition": "${jsonEscape(definitionJson)}"}"""
        )
        assertTrue(ok.isSuccess)
        assertTrue(ok.output!!.contains("defined"))
        // 热注册：注册表里出现编译后的工具。
        assertTrue(toolRegistry.getTool("shortcut.save_search") != null)
        assertEquals("Search and save", toolRegistry.getTool("shortcut.save_search")!!.name)

        // 重定义 → replaced。
        val again = define.executeStructured(
            """{"definition": "${jsonEscape(definitionJson)}"}"""
        )
        assertTrue(again.isSuccess)
        assertTrue(again.output!!.contains("redefined"))
    }

    @Test
    fun `shortcut_define rejects definitions referencing unknown tools`() = runTest {
        val shortcutRegistry = ShortcutRegistry()
        val toolRegistry = registryWith(StubTool("read_file"))
        val define = ShortcutDefineTool(shortcutRegistry, toolRegistry, RecordingExecutor())
        val bad = define.executeStructured(
            """{"definition": {"id": "shortcut.probe", "name": "X", "steps": [{"tool": "ghost", "arguments": "{}"}]}}"""
        )
        assertFalse(bad.isSuccess)
        assertTrue(bad.error!!.message!!.contains("ghost"))
        assertEquals(0, shortcutRegistry.size())
    }

    @Test
    fun `shortcut_list renders installed and suggestions views`() = runTest {
        val shortcutRegistry = ShortcutRegistry()
        val recorder = ToolTraceRecorder()
        // 灌入 3 次 read→write 成功对，制造一条挖掘建议。
        repeat(3) {
            val a = recorder.begin("read_file", "{}")
            recorder.complete(a)
            val b = recorder.begin("write_file", "{}")
            recorder.complete(b)
        }
        val list = ShortcutListTool(shortcutRegistry, recorder)
        val suggestionsOnly = list.executeStructured("""{"view": "suggestions"}""")
        assertTrue(suggestionsOnly.isSuccess)
        assertTrue(suggestionsOnly.output!!.contains("read_file"))
        assertTrue(suggestionsOnly.output!!.contains("skeleton"))

        val installedOnly = list.executeStructured("""{"view": "installed"}""")
        assertTrue(installedOnly.isSuccess)
        assertTrue(installedOnly.output!!.contains("none"))

        val both = list.executeStructured("{}")
        assertTrue(both.isSuccess)
        assertTrue(both.output!!.contains("installed shortcuts"))
        assertTrue(both.output!!.contains("mined suggestions"))
    }

    @Test
    fun `shortcut_run executes stored definitions and reports unknown ids`() = runTest {
        val shortcutRegistry = ShortcutRegistry()
        val executor = RecordingExecutor()
        val toolRegistry = registryWith(StubTool("web_search"))
        shortcutRegistry.upsert(
            ShortcutDefinition.parse(
                """
                {
                  "id": "shortcut.pure_search",
                  "name": "Search",
                  "description": "",
                  "precondition": "",
                  "params": [{"name": "query", "type": "string", "required": true}],
                  "steps": [
                    {"tool": "web_search", "arguments": "{\"query\": \"{query}\"}"}
                  ]
                }
                """.trimIndent(),
                setOf("web_search")
            )
        )
        val run = ShortcutRunTool(shortcutRegistry, executor, toolRegistry)
        val ok = run.executeStructured(
            """{"id": "shortcut.pure_search", "args": {"query": "hello world"}}"""
        )
        assertTrue(ok.isSuccess)
        assertEquals("""{"query": "hello world"}""", executor.calls[0].second)

        val unknown = run.executeStructured("""{"id": "shortcut.missing"}""")
        assertFalse(unknown.isSuccess)
        assertEquals(
            com.apex.agent.core.tools.ToolErrorCode.NOT_FOUND,
            unknown.error!!.code
        )
    }

    // ═══ fixtures（本文件复用）══════════════════════════════════════

    private class RecordingExecutor : ToolExecutor {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun execute(toolId: String, arguments: String): String {
            calls += toolId to arguments
            return "OK[$toolId]"
        }

        override fun executeStream(toolId: String, arguments: String) = flow {
            emit(com.apex.agent.core.tools.ToolStreamEvent.Output(execute(toolId, arguments)))
        }
    }

    private class StubTool(override val id: String) : AgentTool {
        override val name = id
        override val description = "stub"
        override val parametersSchema = "{}"

        override suspend fun execute(arguments: String): String = "OK[$id]"
    }

    private fun registryWith(vararg tools: AgentTool): ToolRegistry {
        val registry = DefaultToolRegistry()
        tools.forEach { registry.register(it) }
        return registry
    }
}
