package com.apex.agent.core.tools.skill

import com.apex.agent.core.tools.builtin.McpRemoveServerTool
import com.apex.agent.core.tools.builtin.McpToggleServerTool
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tool System v4.1 — skill suggestions + MCP lifecycle tools.
 */
class ToolSystemV4Wave2Test {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── SkillRegistry.suggestSkills（纯函数）─────────────────────

    private fun skill(id: String, name: String, description: String, enabled: Boolean = false) =
        SkillRegistry.InstalledSkill(
            manifest = SkillManifest(
                id = id, name = name, version = "1.0.0", description = description
            ),
            enabled = enabled
        )

    @Test
    fun `suggests matching disabled skills by name and description`() {
        val installed = listOf(
            skill("pdf", "PDF Reader", "Extract text and tables from PDF documents"),
            skill("weather", "Weather Query", "Query real-time weather by city"),
            skill("cron", "Cron Scheduler", "Schedule periodic tasks"),
            skill("on-skill", "Active One", "weather related but already enabled", enabled = true)
        )
        val suggestions = SkillRegistry.suggestSkills("帮我查一下北京今天的天气 weather", installed)
        assertTrue(suggestions.any { it.startsWith("weather —") })
        // enabled skills never suggested (their promptInjection is already active)
        assertTrue(suggestions.none { it.startsWith("on-skill") })
        // non-matching skills not suggested
        assertTrue(suggestions.none { it.startsWith("cron") })
    }

    @Test
    fun `no suggestions without meaningful terms`() {
        val installed = listOf(skill("a", "A", "desc"))
        assertTrue(SkillRegistry.suggestSkills("你好", installed).isEmpty())
        assertTrue(SkillRegistry.suggestSkills("", installed).isEmpty())
    }

    @Test
    fun `suggestions are capped at three`() {
        val installed = (1..6).map { skill("s$it", "Skill $it", "search keyword matcher $it") }
        val suggestions = SkillRegistry.suggestSkills("search keyword", installed)
        assertEquals(3, suggestions.size)
    }

    // ── MCP lifecycle tools（真实 McpManager + 临时目录）─────────

    private fun newManager(): McpManager = McpManager(tmp.newFolder())

    @Test
    fun `mcp_remove_server deletes config and unknown name errors`() = runBlocking {
        val manager = newManager()
        manager.addServer(
            McpServerConfig(name = "github", url = "http://localhost:9/mcp", transport = McpTransport.HTTP)
        )
        val remove = McpRemoveServerTool(manager)

        val unknown = remove.execute("""{"name":"nope"}""")
        assertTrue(unknown.contains("Error"))

        val ok = remove.execute("""{"name":"github"}""")
        assertTrue(ok.contains("Removed MCP server 'github'"))
        assertTrue(manager.getConfigs().none { it.name == "github" })
    }

    @Test
    fun `mcp_toggle_server flips enabled flag`() = runBlocking {
        val manager = newManager()
        manager.addServer(
            McpServerConfig(name = "github", url = "http://localhost:9/mcp", transport = McpTransport.HTTP)
        )
        val toggle = McpToggleServerTool(manager)

        val off = toggle.execute("""{"name":"github","enabled":false}""")
        assertTrue(off.contains("disabled"))
        assertEquals(false, manager.getConfigs().first { it.name == "github" }.enabled)

        val on = toggle.execute("""{"name":"github","enabled":true}""")
        assertTrue(on.contains("enabled"))
        assertEquals(true, manager.getConfigs().first { it.name == "github" }.enabled)

        val badArgs = toggle.execute("""{"name":"github"}""")
        assertTrue(badArgs.contains("Error"))
    }
}
