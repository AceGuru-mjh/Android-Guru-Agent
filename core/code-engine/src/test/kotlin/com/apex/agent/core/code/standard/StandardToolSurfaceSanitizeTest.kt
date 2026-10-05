package com.apex.agent.core.code.standard

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardToolSurface 工具名 provider 清洗测试（P0 修复：严格端点 400）。
 *
 * 用户报错（Coding 模式发「你好」即失败）：
 * `Invalid 'tools[14].function.name': string does not match pattern
 * '^[a-zA-Z0-9_-]+$'` —— 根因是注册表原始 id（terminal.* 点号名 /
 * 含中文的 mcp 名）未经清洗直发请求体，严格 OpenAI 兼容端点拒绝整个
 * 请求。本组测试锁定 [StandardToolSurface.buildToolPlan] 的清洗契约：
 *
 * 1. 所有 definitions.name 匹配 `^[a-zA-Z0-9_-]{1,64}$`；
 * 2. 反查表（providerNameToId）双向可解析（执行路由不丢工具）；
 * 3. synthetic task 工具保留且自映射；
 * 4. allowlist 前缀过滤语义不变（过滤发生在清洗前的 registry id 上）；
 * 5. MAX_TOOLS 截断仍生效（优先序在截断前评估）；
 * 6. 清洗后冲突的名被确定性消解（双工具不互踩）；
 * 7. forced 路径同样清洗。
 */
class StandardToolSurfaceSanitizeTest {

    /** OpenAI function-name 语法（长度 1..64）。 */
    private val providerLegal = Regex("^[a-zA-Z0-9_-]{1,64}$")

    private class FakeTool(override val id: String) : AgentTool {
        override val name: String get() = id
        override val description: String get() = "fake $id"
        override val parametersSchema: String =
            """{"type":"object","properties":{},"required":[]}"""
        override suspend fun execute(arguments: String): String = "ok"
    }

    private fun registry(vararg ids: String): DefaultToolRegistry =
        DefaultToolRegistry().apply { ids.forEach { register(FakeTool(it)) } }

    /** 脏名动物园：触发 400 的全部形态（点号 / 中文 / 空格 / 超长 / 冲突对）。 */
    private fun dirtyRegistry(): DefaultToolRegistry = registry(
        "code_read", "code_write",
        "terminal.exec", "terminal.linux.bootstrap",
        "mcp__天气服务__查天气",
        "space tool",
        "x".repeat(80),
        "a.b", "a_b"
    )

    // ═══ 基本契约：全部名字合法 ═══

    @Test
    fun `every definition name matches the provider grammar`() {
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.BUILD, dirtyRegistry())
        assertTrue(plan.definitions.isNotEmpty())
        plan.definitions.forEach { def ->
            assertTrue(
                "illegal provider name in tools array: '${def.name}'",
                providerLegal.matches(def.name)
            )
        }
    }

    @Test
    fun `dotted spaced and overlong ids sanitize deterministically`() {
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.BUILD, dirtyRegistry())
        val names = plan.definitions.map { it.name }
        // 点号 → 下划线（terminal.* 是用户报错里的第 14 号工具族）
        assertTrue(names.contains("terminal_exec"))
        assertTrue(names.contains("terminal_linux_bootstrap"))
        // 空格 → 下划线
        assertTrue(names.contains("space_tool"))
        // 中文全替换为下划线且尾下划线被裁 → 合法短名（合法即可过端点校验）
        val chineseProviderName = plan.providerNameToId.entries
            .single { it.value == "mcp__天气服务__查天气" }.key
        assertTrue(providerLegal.matches(chineseProviderName))
        assertTrue(names.contains(chineseProviderName))
        // 超长 → 64 封顶，且反查表还原原始 id
        val capped = plan.definitions.single { it.name.length == 64 }
        assertEquals("x".repeat(64), capped.name)
        assertEquals("x".repeat(80), plan.providerNameToId[capped.name])
    }

    // ═══ 反查路由表：双向可解析 ═══

    @Test
    fun `reverse map round-trips every definition to its registry id`() {
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.BUILD, dirtyRegistry())
        assertEquals(
            "definitions 与反查表一一对应",
            plan.definitions.size,
            plan.providerNameToId.size
        )
        plan.definitions.forEach { def ->
            assertTrue(
                "provider name '${def.name}' 缺反查表项",
                plan.providerNameToId.containsKey(def.name)
            )
        }
        // 全部原始 id（含脏名）都能从反查表找回——执行路由零丢失
        //（synthetic task 自映射也在表内）
        val expectedIds = setOf(
            "code_read", "code_write", "terminal.exec", "terminal.linux.bootstrap",
            "mcp__天气服务__查天气", "space tool", "x".repeat(80), "a.b", "a_b",
            "task"
        )
        assertEquals(expectedIds, plan.providerNameToId.values.toSet())
    }

    @Test
    fun `synthetic task tool stays legal and self-mapped`() {
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.BUILD, dirtyRegistry())
        val names = plan.definitions.map { it.name }
        assertTrue("合成 task 恒在场", names.contains("task"))
        assertEquals("task", plan.providerNameToId["task"])
    }

    // ═══ 冲突消解：清洗后撞名的两个工具都在 ═══

    @Test
    fun `collision pair disambiguated without losing either tool`() {
        // a.b 与 a_b 都清洗成 a_b → 确定性后缀消解，路由不互踩
        val plan = StandardToolSurface.buildToolPlan(
            StandardAgentCatalog.BUILD,
            registry("a.b", "a_b", "code_read")
        )
        val names = plan.definitions.map { it.name }
        assertTrue(names.contains("a_b"))
        assertTrue(names.contains("a_b_2"))
        // a.b 更短字典序在前拿到干净名（确定性：非 legacy 优先 → 等长 → 字典序）
        assertEquals("a.b", plan.providerNameToId["a_b"])
        assertEquals("a_b", plan.providerNameToId["a_b_2"])
    }

    @Test
    fun `definitions are sorted by provider name for prompt-cache stability`() {
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.BUILD, dirtyRegistry())
        val names = plan.definitions.map { it.name }
        assertEquals(names.sorted(), names)
    }

    // ═══ 既有语义保持：过滤 / 截断发生在清洗之前 ═══

    @Test
    fun `allowlist filters on registry ids before sanitization`() {
        // 探针：registry id `code.git_status` 清洗后恰为 `code_git_status`——
        // 若过滤发生在清洗后它会混进 PLAN 面并撞名；正确语义（先过滤后
        // 清洗）下它应被白名单挡住（id 不以 code_git_status 开头）。
        val r = registry(
            "code_read", "code_grep", "code_git_status",
            "code.git_status", "terminal.exec", "code_write",
            "mcp__天气服务__查天气"
        )
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.PLAN, r)
        val names = plan.definitions.map { it.name }
        // PLAN 是主代理画像 → 合成 task 照常在场（既有语义）
        assertEquals(listOf("code_git_status", "code_grep", "code_read", "task"), names)
        // 写工具 / 终端点号名 / 中文 mcp 名 / 探针脏名都不在 PLAN 只读面
        assertFalse(names.contains("code_write"))
        assertFalse(names.contains("terminal_exec"))
        assertFalse(names.contains("mcp"))
    }

    @Test
    fun `max tools truncation still effective and priority keeps dotted terminal`() {
        // 60 个无前缀填充 + 点号终端名：截断按优先序（registry id 评估），
        // terminal.exec 必存活；task 合成不占预算恒在场。
        val ids = buildList {
            add("terminal.exec")
            repeat(60) { add("zz_filler_%02d".format(it + 1)) }
        }
        val plan = StandardToolSurface.buildToolPlan(
            StandardAgentCatalog.BUILD, registry(*ids.toTypedArray())
        )
        assertEquals(
            "MAX_TOOLS 截断 + task 合成（不占预算）",
            StandardToolSurface.MAX_TOOLS + 1,
            plan.definitions.size
        )
        val names = plan.definitions.map { it.name }
        assertTrue("优先序评估在清洗前（registry id terminal. 前缀）", names.contains("terminal_exec"))
        assertTrue(names.contains("task"))
        assertTrue("填充名被截到 47 个", names.count { it.startsWith("zz_filler_") } == 47)
        names.forEach { assertTrue(providerLegal.matches(it)) }
    }

    // ═══ forced 路径同样清洗 ═══

    @Test
    fun `forced path is sanitized too`() {
        val plan = StandardToolSurface.buildToolPlan(
            StandardAgentCatalog.BUILD, dirtyRegistry(),
            forcedToolIds = setOf("terminal.exec")
        )
        assertEquals(listOf("terminal_exec"), plan.definitions.map { it.name })
        assertEquals("terminal.exec", plan.providerNameToId["terminal_exec"])
    }
}
