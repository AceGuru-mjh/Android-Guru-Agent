package com.apex.agent.core.tools.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HubSource 纯解析单测（apex-skill-hub / apex-mcp-hub 双目录）。
 *
 * 喂真实仓库形态的 index.json 文本夹具（不联网）：
 * - 技能目录：字段映射 / 缺省值 / 坏条目跳过 / 工位可见性；
 * - MCP 目录：transport/env/args/scope/requiresRootfs 映射 / 沙箱开关；
 * - 整体损坏（非 JSON）→ Result.failure 不抛异常。
 *
 * 网络路径（listSkills/listMcpServers/downloadSkillManifest）由
 * GitHub Actions 的集成链路覆盖（仓库真实存在且可匿名拉取）。
 */
class HubSourceTest {

    // ═══════════════════════════════════════════════════════════
    // 技能目录 index.json 解析
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `skill index parses full entries with all fields`() {
        val body = """
        {
          "schema": "apex-skill-hub-v1",
          "count": 2,
          "skills": [
            {
              "id": "cooking-master",
              "name": "家常菜大师",
              "version": "1.2.0",
              "description": "家常菜四维法",
              "category": "AGENT",
              "tags": ["cooking", "food"],
              "scope": "agent",
              "author": "Apex",
              "file": "skills/cooking-master.json"
            },
            {
              "id": "travel-planner",
              "name": "旅行规划师",
              "description": "行程规划",
              "file": "skills/travel-planner.json"
            }
          ]
        }
        """.trimIndent()

        val result = HubSource.parseSkillIndex(body)

        assertTrue(result.isSuccess)
        val entries = result.getOrThrow()
        assertEquals(2, entries.size)

        val first = entries[0]
        assertEquals("cooking-master", first.id)
        assertEquals("家常菜大师", first.name)
        assertEquals("1.2.0", first.version)
        assertEquals(listOf("cooking", "food"), first.tags)
        assertEquals("agent", first.scope)
        assertEquals("skills/cooking-master.json", first.file)
        assertEquals("hub/cooking-master", first.key)

        // 缺省字段：version→1.0.0、scope→agent（仓库默认 agent 工位）
        val second = entries[1]
        assertEquals("1.0.0", second.version)
        assertEquals("agent", second.scope)
        assertEquals("旅行规划师", second.name)
    }

    @Test
    fun `skill index skips broken entries and parses rest`() {
        val body = """
        {
          "skills": [
            { "id": "ok-one", "file": "skills/ok-one.json" },
            { "id": "", "file": "skills/bad.json" },
            { "file": "skills/no-id.json" },
            { "id": "no-file" },
            "not-an-object"
          ]
        }
        """.trimIndent()

        val result = HubSource.parseSkillIndex(body)

        assertTrue(result.isSuccess)
        val entries = result.getOrThrow()
        assertEquals(1, entries.size)
        assertEquals("ok-one", entries[0].id)
    }

    @Test
    fun `skill index empty or missing skills array yields empty list`() {
        assertTrue(HubSource.parseSkillIndex("""{"count": 0}""").getOrThrow().isEmpty())
        assertTrue(HubSource.parseSkillIndex("""{}""").getOrThrow().isEmpty())
    }

    @Test
    fun `skill index corrupted body returns failure without throwing`() {
        val result = HubSource.parseSkillIndex("not-json-at-all {{{")
        assertTrue(result.isFailure)
    }

    @Test
    fun `duplicate entries are deduped to protect lazy column keys`() {
        // P2-1：上游 index.json 出现重复条目时，不去重会让消费点 LazyColumn 的
        // key = it.key 抛 IllegalArgumentException 直接崩市场页 —— 按 key
        // 去重且保留首见条目
        val skillBody = """
        {"skills": [
            {"id": "dup", "name": "第一份", "file": "skills/dup.json"},
            {"id": "dup", "name": "第二份", "file": "skills/dup.json"},
            {"id": "unique", "file": "skills/unique.json"}
        ]}
        """.trimIndent()

        val skills = HubSource.parseSkillIndex(skillBody).getOrThrow()
        assertEquals(2, skills.size)
        // 保留首见条目（distinctBy 语义），键唯一
        assertEquals("第一份", skills[0].name)
        assertEquals(skills.size, skills.map { it.key }.toSet().size)
    }

    @Test
    fun `skill entry scope visibility follows market tier semantics`() {
        val agentOnly = HubSource.HubSkillEntry(id = "a", name = "A", file = "skills/a.json", scope = "agent")
        val codingOnly = HubSource.HubSkillEntry(id = "c", name = "C", file = "skills/c.json", scope = "coding")
        val both = HubSource.HubSkillEntry(id = "b", name = "B", file = "skills/b.json", scope = "all")

        assertTrue(agentOnly.visibleToScope("agent"))
        assertFalse(agentOnly.visibleToScope("coding"))
        assertTrue(codingOnly.visibleToScope("coding"))
        assertFalse(codingOnly.visibleToScope("agent"))
        assertTrue(both.visibleToScope("agent"))
        assertTrue(both.visibleToScope("coding"))
    }

    // ═══════════════════════════════════════════════════════════
    // MCP 目录 index.json 解析
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `mcp index parses sandbox stdio and remote http entries`() {
        val body = """
        {
          "schema": "apex-mcp-hub-v1",
          "count": 2,
          "servers": [
            {
              "name": "fs-sandbox",
              "description": "Official filesystem server",
              "transport": "STDIO",
              "command": "npx",
              "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"],
              "env": {"FOO": "bar"},
              "runInSandbox": true,
              "enabled": false,
              "scope": "coding",
              "requiresRootfs": true,
              "vendor": "modelcontextprotocol",
              "tags": ["files"]
            },
            {
              "name": "deepwiki",
              "description": "DeepWiki remote",
              "transport": "HTTP",
              "url": "https://mcp.deepwiki.com/mcp"
            }
          ]
        }
        """.trimIndent()

        val result = HubSource.parseMcpIndex(body)

        assertTrue(result.isSuccess)
        val servers = result.getOrThrow()
        assertEquals(2, servers.size)

        val sandbox = servers[0]
        assertEquals("fs-sandbox", sandbox.name)
        assertEquals("STDIO", sandbox.transport)
        assertEquals("npx", sandbox.command)
        assertEquals(3, sandbox.args.size)
        assertEquals(mapOf("FOO" to "bar"), sandbox.env)
        assertTrue(sandbox.runInSandbox)
        assertFalse(sandbox.enabled)
        assertEquals("coding", sandbox.scope)
        assertTrue(sandbox.requiresRootfs)
        // STDIO 端点摘要 = 命令行拼接
        assertEquals("npx -y @modelcontextprotocol/server-filesystem /workspace", sandbox.endpointSummary())

        // 远端条目缺省：enabled=false、scope=all、非沙箱、不需 rootfs
        val remote = servers[1]
        assertEquals("HTTP", remote.transport)
        assertEquals("https://mcp.deepwiki.com/mcp", remote.url)
        assertFalse(remote.runInSandbox)
        assertFalse(remote.requiresRootfs)
        assertEquals("all", remote.scope)
        assertNull(remote.command)
        assertEquals("https://mcp.deepwiki.com/mcp", remote.endpointSummary())
    }

    @Test
    fun `mcp index skips entries without name and returns failure on garbage`() {
        val body = """{"servers": [{"name": "ok"}, {"description": "no name"}, 42]}"""
        val result = HubSource.parseMcpIndex(body)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)

        assertTrue(HubSource.parseMcpIndex("garbage").isFailure)
    }

    @Test
    fun `mcp index dedupes repeated server names for lazy column keys`() {
        // P2-1：消费点（MarketBrowseMcpTab）列表 key 为 "hub-" + name ——
        // 上游重复 name 的条目不去重会撞重复 key 崩溃，按 name 去重
        val body = """{"servers": [{"name": "fs"}, {"name": "fs"}, {"name": "wiki"}]}"""
        val servers = HubSource.parseMcpIndex(body).getOrThrow()
        assertEquals(2, servers.size)
        assertEquals(listOf("fs", "wiki"), servers.map { it.name })
    }

    @Test
    fun `mcp entry scope visibility mirrors skill semantics`() {
        val body = """
        {"servers": [
            {"name": "a", "scope": "agent"},
            {"name": "c", "scope": "coding"},
            {"name": "b", "scope": "all"}
        ]}
        """.trimIndent()
        val servers = HubSource.parseMcpIndex(body).getOrThrow().associateBy { it.name }

        assertTrue(servers.getValue("a").visibleToScope("agent"))
        assertFalse(servers.getValue("a").visibleToScope("coding"))
        assertTrue(servers.getValue("c").visibleToScope("coding"))
        assertTrue(servers.getValue("b").visibleToScope("agent"))
        assertTrue(servers.getValue("b").visibleToScope("coding"))
    }
}
