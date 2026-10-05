package com.apex.agent.plugin.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [WorkflowStore] 纯 JVM 单测（#256 防回退）。
 *
 * 背景：workflow 插件曾有「save 返回 OK 但什么都不写 / list 永远返回
 * 空数组 / execute 无条件成功」的假成功桩，存活一个多月无任何测试报警。
 * 本套测试锁死诚实契约：
 * 1. save 成功 ⇔ 文件真实落盘（list 能读回、内容可解析）；
 * 2. 失败一律以 "Error" 开头（宿主 SafeAgentTool 按前缀判失败）；
 * 3. 原子写无 .tmp 残留；
 * 4. 模型输出的恶意/畸形输入（目录穿越、超长名、纯符号名）不越界不崩。
 */
class WorkflowStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 固定时钟：savedAt 可断言。 */
    private fun store(clock: () -> Long = { 1_700_000_000_000L }): WorkflowStore =
        WorkflowStore(tmp.newFolder("workflows"), clock)

    private val steps = """[{"tool":"browser_navigate","args":{"url":"https://example.com"}}]"""

    // ═══════════════ save → list 往返（假成功回归锁）═══════════════

    @Test
    fun `save 成功后文件真实落盘且 list 能读回`() {
        val s = store()

        val out = s.saveWorkflow("""{"name":"daily-report","steps":$steps}""")

        assertTrue("应成功：$out", out.startsWith("OK:"))
        assertTrue(out.contains("daily-report"))
        assertTrue(out.contains("1 steps"))
        // 落盘验证：目录里恰好一个 json，且无 tmp 残留（原子写）。
        val files = tmp.root.resolve("workflows").listFiles()!!.map { it.name }
        assertEquals(listOf("daily-report.json"), files)
        // list 读回：名称 + 步数 + savedAt（假时钟注入）。
        val list = s.listWorkflows()
        assertTrue(list.contains("\"name\":\"daily-report\""))
        assertTrue(list.contains("\"steps\":1"))
        assertTrue(list.contains("\"savedAt\":1700000000000"))
    }

    @Test
    fun `save 重名覆写旧文件不产生副本`() {
        val s = store()
        s.saveWorkflow("""{"name":"dup","steps":$steps}""")
        val twoSteps = "[$steps,$steps]"
        s.saveWorkflow("""{"name":"dup","steps":$twoSteps}""")

        val jsonFiles = tmp.root.resolve("workflows").listFiles()!!.filter { it.name.endsWith(".json") }
        assertEquals(1, jsonFiles.size)
        assertTrue(s.listWorkflows().contains("\"steps\":2"))
    }

    // ═══════════════ 参数校验分支 ═══════════════

    @Test
    fun `save 非 JSON 参数报错`() {
        val out = store().saveWorkflow("not-json-at-all")
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("JSON object"))
    }

    @Test
    fun `save 缺 name 或 name 为空报错`() {
        val s = store()
        assertTrue(s.saveWorkflow("""{"steps":$steps}""").startsWith("Error:"))
        assertTrue(s.saveWorkflow("""{"name":"  ","steps":$steps}""").startsWith("Error:"))
        assertTrue(s.saveWorkflow("""{"name":"","steps":$steps}""").startsWith("Error:"))
    }

    @Test
    fun `save 缺 steps 或非数组或空数组报错`() {
        val s = store()
        assertTrue(s.saveWorkflow("""{"name":"x"}""").contains("'steps'"))
        assertTrue(s.saveWorkflow("""{"name":"x","steps":"not-array"}""").contains("'steps'"))
        assertTrue(s.saveWorkflow("""{"name":"x","steps":[]}""").contains("at least one step"))
    }

    @Test
    fun `save 步数超限报错`() {
        val many = (1..WorkflowStore.MAX_STEPS + 1).joinToString(",", "[", "]") { steps }
        val out = store().saveWorkflow("""{"name":"big","steps":$many}""")
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("too many steps"))
    }

    // ═══════════════ 目录穿越 / 恶意名折叠 ═══════════════

    @Test
    fun `目录穿越名折叠为安全文件名不越界`() {
        val out = store().saveWorkflow("""{"name":"../../etc/passwd","steps":$steps}""")

        assertTrue(out.startsWith("OK:"))
        // `../` 的斜杠折叠为 `-` 且首尾符号被剥离，落在 workflows 目录内。
        val names = tmp.root.resolve("workflows").listFiles()!!.map { it.name }
        assertTrue("实际文件名：$names", names.all { it == "etc-passwd.json" })
        // 根目录（workflows 之上）没有逃逸文件。
        assertEquals(0, tmp.root.resolve("workflows").parentFile!!
            .listFiles { f -> f.name.contains("passwd") && f.name.endsWith(".json") }?.size ?: 0)
    }

    @Test
    fun `纯符号名无可用水字符报错`() {
        val out = store().saveWorkflow("""{"name":"///***","steps":$steps}""")
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("no usable characters"))
    }

    @Test
    fun `超长名截断到上限不报错`() {
        val longName = "a".repeat(WorkflowStore.MAX_ID_CHARS + 50)
        val out = store().saveWorkflow("""{"name":"$longName","steps":$steps}""")
        assertTrue(out.startsWith("OK:"))
        val names = tmp.root.resolve("workflows").listFiles()!!.map { it.name }
        assertTrue(names.single().length == WorkflowStore.MAX_ID_CHARS + WorkflowStore.JSON_SUFFIX.length)
    }

    // ═══════════════ list 容错 ═══════════════

    @Test
    fun `list 跳过损坏 JSON 不拖垮整个清单`() {
        val dir = tmp.newFolder("workflows2")
        val s = WorkflowStore(dir)
        s.saveWorkflow("""{"name":"good","steps":$steps}""")
        dir.resolve("broken.json").writeText("{not valid json", Charsets.UTF_8)

        val list = s.listWorkflows()

        assertTrue(list.contains("\"name\":\"good\""))
        assertFalse(list.contains("broken"))
    }

    @Test
    fun `list 空目录返回空数组`() {
        assertEquals("[]", store().listWorkflows())
    }

    // ═══════════════ load（execute 的读取半段）═══════════════

    @Test
    fun `load 未保存的工作流返回 NotFound 带自修复指引`() {
        val r = store().loadWorkflow("ghost")
        assertTrue(r is WorkflowStore.LoadResult.NotFound)
        assertTrue((r as WorkflowStore.LoadResult.NotFound).message.contains("workflow/list"))
    }

    @Test
    fun `load 损坏文件返回 Corrupted 带路径`() {
        val dir = tmp.newFolder("workflows3")
        val s = WorkflowStore(dir)
        dir.resolve("bad.json").writeText("]]]garbage[[[", Charsets.UTF_8)

        val r = s.loadWorkflow("bad")

        assertTrue(r is WorkflowStore.LoadResult.Corrupted)
        assertTrue((r as WorkflowStore.LoadResult.Corrupted).message.contains("corrupted"))
        assertTrue(r.message.contains("bad.json"))
    }

    @Test
    fun `load 成功返回原样 steps`() {
        val s = store()
        s.saveWorkflow("""{"name":"ok","steps":$steps}""")

        val r = s.loadWorkflow("ok")

        assertTrue(r is WorkflowStore.LoadResult.Loaded)
        val loaded = r as WorkflowStore.LoadResult.Loaded
        assertEquals("ok", loaded.name)
        assertEquals(1, loaded.steps.size)
        assertEquals("browser_navigate", loaded.steps.first().let {
            (it as? kotlinx.serialization.json.JsonObject)?.get("tool")?.let { p ->
                (p as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
        })
    }

    // ═══════════════ 原子写纪律 ═══════════════

    @Test
    fun `save 成功后无 tmp 残留文件`() {
        val s = store()
        s.saveWorkflow("""{"name":"clean","steps":$steps}""")
        val leftovers = tmp.root.resolve("workflows").listFiles()!!
            .filter { it.name.endsWith(WorkflowStore.TMP_SUFFIX) }
        assertEquals("原子写不应残留 tmp 文件", 0, leftovers.size)
    }

    @Test
    fun `写入内容为合法 JSON 且含全部字段`() {
        val dir = tmp.newFolder("workflows4")
        val s = WorkflowStore(dir) { 42L }
        s.saveWorkflow("""{"name":"fields","steps":$steps}""")

        val text = dir.resolve("fields.json").readText(Charsets.UTF_8)
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(text)
            as kotlinx.serialization.json.JsonObject

        assertEquals("fields", obj["name"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content })
        assertEquals(42L, obj["savedAt"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLong() })
        assertNotNull(obj["steps"])
        assertEquals(1, (obj["steps"] as kotlinx.serialization.json.JsonArray).size)
    }
}
