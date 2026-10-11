package com.apex.agent.core.tools.catalog

import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolEnvironmentState
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.marketplace.HubSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability Introspection 元工具单测（capability_report / market_search）。
 *
 * 覆盖：
 * - capability_report：权限（与 PrivilegeLadder 同源）/ 环境快照（与执行侧
 *   环境门同源）/ 工具核心-目录二分（legacy 剔除）/ 技能-MCP 计数注入与
 *   未知省略 / 扩展梯度完整；
 * - market_search：双目录打分检索、安装指令完整可执行（skill_install 带
 *   raw URL / mcp_connect 带 url 或 command+args+沙箱）、kind 过滤、
 *   limit、无命中与市场不可达的可操作错误。
 */
class CapabilityToolsTest {

    // ── fakes ─────────────────────────────────────────────────

    private class FakeTool(
        override val id: String,
        override val description: String = "Test tool $id"
    ) : AgentTool {
        override val name: String = "Fake $id"
        override val parametersSchema: String = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String): String = "ok"
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

    private fun hubSkill(
        id: String,
        name: String,
        description: String,
        file: String,
        tags: List<String> = emptyList()
    ) = HubSource.HubSkillEntry(
        id = id, name = name, description = description, tags = tags, file = file
    )

    private fun hubMcp(
        name: String,
        description: String,
        transport: String = "STDIO",
        url: String = "",
        command: String? = null,
        args: List<String> = emptyList(),
        runInSandbox: Boolean = false
    ) = HubSource.HubMcpEntry(
        name = name, description = description, transport = transport,
        url = url, command = command, args = args, runInSandbox = runInSandbox
    )

    // ── capability_report ─────────────────────────────────────

    @Test
    fun `report covers privilege environment tools skills mcp and ladder`() = runBlocking {
        val registry = FakeRegistry(
            listOf(
                FakeTool("read_file"),          // CORE
                FakeTool("github_create_issue"), // catalog
                FakeTool("terminal_exec")        // legacy alias → 剔除
            )
        )
        val env = ToolEnvironmentState().apply {
            set(ToolEnvironmentState.Flags.NETWORK_AVAILABLE, true)
            set(ToolEnvironmentState.Flags.UBUNTU_READY, false)
        }
        val tool = CapabilityReportTool(
            registry = registry,
            environmentState = env,
            privilegeLevel = { "SHIZUKU" },
            installedSkillCount = { 13 },
            configuredMcpCount = { 3 },
            connectedMcpCount = { 2 }
        )

        val out = tool.execute("{}")

        // 权限段：与 PrivilegeLadder 同源
        assertTrue(out.contains("Privilege: SHIZUKU"))
        assertTrue(out.contains("- CAN: "))
        assertTrue(out.contains("- CANNOT: "))
        assertTrue(out.contains("- Upgrade: "))
        // 环境段：与执行侧环境门同源（flag 名 = ToolEnvironmentState.Flags 常量）
        assertTrue(out.contains("network_available=on"))
        assertTrue(out.contains("ubuntu_ready=off"))
        // 工具段：legacy 剔除 → 2 注册（1 默认加载 + 1 目录）
        assertTrue(out.contains("Tools: 2 registered — 1 loaded by default, 1 more"))
        // 技能与 MCP 计数
        assertTrue(out.contains("Skills: 13 installed"))
        assertTrue(out.contains("MCP servers: 3 configured, 2 connected"))
        // 扩展梯度完整
        assertTrue(out.contains("tool_search(query)"))
        assertTrue(out.contains("market_search(kind=\"mcp\")"))
        assertTrue(out.contains("terminal.ubuntu.ensure"))
        assertTrue(out.contains("Never claim a task is impossible"))
    }

    @Test
    fun `report omits unknown skill and mcp counts`() = runBlocking {
        val tool = CapabilityReportTool(
            registry = FakeRegistry(emptyList()),
            environmentState = ToolEnvironmentState()
            // 计数 lambda 全部走默认 -1（未知）
        )
        val out = tool.execute("{}")
        assertFalse(out.contains("Skills:"))
        assertFalse(out.contains("MCP servers:"))
        // 工具段与环境段仍在（0 注册是事实，不省略）
        assertTrue(out.contains("Tools: 0 registered"))
        assertTrue(out.contains("Environment:"))
    }

    @Test
    fun `report privilege flows from provider lambda`() = runBlocking {
        val tool = CapabilityReportTool(
            registry = FakeRegistry(emptyList()),
            environmentState = ToolEnvironmentState(),
            privilegeLevel = { "ROOT" }
        )
        val out = tool.execute("{}")
        assertTrue(out.contains("Privilege: ROOT"))
        // ROOT 无 CANNOT 行
        assertFalse(out.contains("- CANNOT:"))
    }

    // ── market_search：技能 ───────────────────────────────────

    @Test
    fun `market search skill hit carries executable install url`() = runBlocking {
        val tool = MarketSearchTool(
            fetchSkills = {
                Result.success(
                    listOf(
                        hubSkill(
                            "youtube-dl", "YouTube 下载",
                            "Download YouTube videos to /sdcard",
                            "skills/youtube-dl.json"
                        ),
                        hubSkill("cooking", "家常菜大师", "做菜方法论", "skills/cooking.json")
                    )
                )
            },
            fetchMcpServers = { Result.success(emptyList()) }
        )

        val out = tool.execute("""{"query":"youtube"}""")

        assertTrue(out.contains("YouTube 下载"))
        // 安装指令：完整 raw URL（模型可直接复制执行）
        assertTrue(
            out.contains(
                "skill_install({\"source\":\"url\",\"url\":\"" +
                    "https://raw.githubusercontent.com/Ultra-Guru/apex-skill-hub/main/skills/youtube-dl.json\"})"
            )
        )
        // 未命中条目不出现
        assertFalse(out.contains("家常菜大师"))
        assertTrue(out.contains("skill_activate"))
    }

    // ── market_search：MCP ────────────────────────────────────

    @Test
    fun `market search mcp stdio hit carries command args and sandbox`() = runBlocking {
        val tool = MarketSearchTool(
            fetchSkills = { Result.success(emptyList()) },
            fetchMcpServers = {
                Result.success(
                    listOf(
                        hubMcp(
                            "filesystem", "Filesystem access",
                            command = "npx", args = listOf("-y", "@modelcontextprotocol/server-filesystem"),
                            runInSandbox = true
                        )
                    )
                )
            }
        )

        val out = tool.execute("""{"query":"filesystem","kind":"mcp"}""")

        assertTrue(out.contains("[STDIO]"))
        assertTrue(out.contains("mcp_connect"))
        assertTrue(out.contains("\"command\":\"npx\""))
        assertTrue(out.contains("\"run_in_sandbox\": true"))
        assertTrue(out.contains("mcp_list()"))
    }

    @Test
    fun `market search mcp http hit carries url`() = runBlocking {
        val tool = MarketSearchTool(
            fetchSkills = { Result.success(emptyList()) },
            fetchMcpServers = {
                Result.success(
                    listOf(
                        hubMcp("search", "Web search MCP", transport = "HTTP", url = "https://mcp.example.com/sse")
                    )
                )
            }
        )

        val out = tool.execute("""{"query":"search","kind":"mcp"}""")

        assertTrue(out.contains("[HTTP]"))
        assertTrue(out.contains("\"url\":\"https://mcp.example.com/sse\""))
    }

    // ── market_search：过滤 / 限额 / 错误 ─────────────────────

    @Test
    fun `kind filter and limit are respected`() = runBlocking {
        val tool = MarketSearchTool(
            fetchSkills = {
                Result.success(
                    (1..8).map { hubSkill("sk$it", "Skill $it", "match query", "s$it.json") }
                )
            },
            fetchMcpServers = {
                Result.success(listOf(hubMcp("srv", "match query mcp")))
            }
        )

        val skillOnly = tool.execute("""{"query":"match","kind":"skill","limit":3}""")
        assertTrue(skillOnly.contains("Skill 3"))
        assertFalse(skillOnly.contains("Skill 4"))
        assertFalse(skillOnly.contains("== MCP servers"))

        val mcpOnly = tool.execute("""{"query":"match","kind":"mcp"}""")
        assertTrue(mcpOnly.contains("== MCP servers"))
        assertFalse(mcpOnly.contains("== Skills"))
    }

    @Test
    fun `no match suggests broader keywords or self creation`() = runBlocking {
        val tool = MarketSearchTool(
            fetchSkills = { Result.success(listOf(hubSkill("a", "A", "cooking", "a.json"))) },
            fetchMcpServers = { Result.success(emptyList()) }
        )
        val out = tool.execute("""{"query":"quantum chess"}""")
        assertTrue(out.contains("No marketplace entries matched"))
        assertTrue(out.contains("broader keywords"))
    }

    @Test
    fun `unreachable marketplace returns actionable error`() = runBlocking {
        val tool = MarketSearchTool(
            fetchSkills = { Result.failure(Exception("network down")) },
            fetchMcpServers = { Result.failure(Exception("network down")) }
        )
        val out = tool.execute("""{"query":"anything"}""")
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("Marketplace unreachable"))
        // 可操作建议：web_search 兜底 / skill_install(url) / mcp_connect
        assertTrue(out.contains("web_search"))
    }

    @Test
    fun `empty query is an argument error`() = runBlocking {
        val tool = MarketSearchTool(
            fetchSkills = { Result.success(emptyList()) },
            fetchMcpServers = { Result.success(emptyList()) }
        )
        val out = tool.execute("""{"query":"  "}""")
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("query"))
    }

    @Test
    fun `bad kind is rejected with suggestion`() = runBlocking {
        val tool = MarketSearchTool(
            fetchSkills = { Result.success(emptyList()) },
            fetchMcpServers = { Result.success(emptyList()) }
        )
        val out = tool.execute("""{"query":"x","kind":"plugins"}""")
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("'skill', 'mcp' or 'all'"))
    }

    @Test
    fun `tool ids and core tier are wired`() {
        // CORE 集登记（EngineToolPlanner 会按此进请求）
        assertTrue(ToolTierPolicy.isCore("capability_report"))
        assertTrue(ToolTierPolicy.isCore("market_search"))
        assertEquals(2, 2)
    }
}
