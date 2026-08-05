package com.apex.agent.slash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SlashCommandRouter].
 *
 * Covers:
 * - The four generic command types (Skill / Mcp-other / Connector / Plugin)
 *   produce the expected system-message prefix and an agent prompt that
 *   embeds args + user extra.
 * - `/mcp:github` is the special case: with a connected context it emits a
 *   prompt that explicitly enumerates the `github_*` tool IDs; with a
 *   disconnected context it short-circuits to `requestGithubConnect = true`
 *   and an empty agent prompt.
 * - Unknown commands forward the raw text verbatim.
 */
class SlashCommandRouterTest {

    // ═══════════════════════════════════════════════════════════
    // Generic command types
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `Skill route has skill emoji and forwards args + user extra to prompt`() {
        val cmd = SlashCommand.Skill(
            id = "web_search",
            args = mapOf("query" to "Android"),
            userExtra = "latest news"
        )
        val route = SlashCommandRouter.route(cmd)

        assertEquals("🧩 激活 Skill: web_search", route.systemMessage)
        assertFalse(route.requestGithubConnect)
        // Prompt must surface the command, args, and user extra.
        assertTrue(route.agentPrompt.contains("/skill:web_search"))
        assertTrue(route.agentPrompt.contains("query=Android"))
        assertTrue(route.agentPrompt.contains("用户附加要求: latest news"))
    }

    @Test
    fun `Connector route has connector emoji and generic prompt`() {
        val cmd = SlashCommand.Connector(id = "ssh", args = mapOf("host" to "1.2.3.4"))
        val route = SlashCommandRouter.route(cmd)

        assertEquals("🔗 使用连接器: ssh", route.systemMessage)
        assertFalse(route.requestGithubConnect)
        assertTrue(route.agentPrompt.contains("/connector:ssh"))
        assertTrue(route.agentPrompt.contains("host=1.2.3.4"))
    }

    @Test
    fun `Plugin route has plugin emoji and generic prompt`() {
        val cmd = SlashCommand.Plugin(id = "pdf_reader")
        val route = SlashCommandRouter.route(cmd)

        assertEquals("📦 调用插件: pdf_reader", route.systemMessage)
        assertFalse(route.requestGithubConnect)
        assertTrue(route.agentPrompt.contains("/plugin:pdf_reader"))
    }

    @Test
    fun `Unknown route forwards raw text verbatim to agent`() {
        val cmd = SlashCommand.Unknown(raw = "/help")
        val route = SlashCommandRouter.route(cmd)

        assertEquals("⚡ 指令: /help", route.systemMessage)
        assertEquals("/help", route.agentPrompt)
        assertFalse(route.requestGithubConnect)
    }

    // ═══════════════════════════════════════════════════════════
    // /mcp:github — the real binding (P0)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `mcp github when disconnected requests connect flow and emits no agent prompt`() {
        val cmd = SlashCommand.Mcp(id = "github")
        val route = SlashCommandRouter.route(cmd, SlashRouteContext.Empty)

        assertTrue(route.requestGithubConnect)
        assertEquals("", route.agentPrompt)
        // System message must tell the user *what* to do.
        assertTrue(route.systemMessage.contains("GitHub 未连接"))
    }

    @Test
    fun `mcp github when disconnected ignores any args or user extra`() {
        // Even if the user typed /mcp:github repo=owner/name list issues,
        // we cannot execute — so no prompt is built. Args are simply dropped
        // (acceptable: the user will re-issue after connecting).
        val cmd = SlashCommand.Mcp(
            id = "github",
            args = mapOf("repo" to "owner/name"),
            userExtra = "list issues"
        )
        val route = SlashCommandRouter.route(cmd, SlashRouteContext.Empty)

        assertTrue(route.requestGithubConnect)
        assertEquals("", route.agentPrompt)
    }

    @Test
    fun `mcp github when connected lists github tool ids in agent prompt`() {
        val cmd = SlashCommand.Mcp(id = "github")
        val ctx = SlashRouteContext(githubConnected = true, githubUsername = "octocat")
        val route = SlashCommandRouter.route(cmd, ctx)

        assertFalse(route.requestGithubConnect)
        // System message must surface the bound username.
        assertTrue(route.systemMessage.contains("octocat"))
        // Agent prompt must enumerate the github_* tools so the LLM prefers them.
        listOf(
            "github_get_user", "github_list_repos", "github_read_file",
            "github_write_file", "github_create_issue", "github_list_issues",
            "github_search_code"
        ).forEach { toolId ->
            assertTrue("prompt should mention $toolId", route.agentPrompt.contains(toolId))
        }
    }

    @Test
    fun `mcp github when connected forwards args and user extra into prompt`() {
        val cmd = SlashCommand.Mcp(
            id = "github",
            args = mapOf("repo" to "owner/name"),
            userExtra = "list open issues"
        )
        val ctx = SlashRouteContext(githubConnected = true, githubUsername = "octocat")
        val route = SlashCommandRouter.route(cmd, ctx)

        assertFalse(route.requestGithubConnect)
        assertTrue(route.agentPrompt.contains("repo=owner/name"))
        assertTrue(route.agentPrompt.contains("用户附加要求: list open issues"))
    }

    @Test
    fun `mcp github connected with null username still routes as connected`() {
        // A token may be saved before username resolution completes. The
        // router must treat isConnected=true as the source of truth, not the
        // username — otherwise the user would be bounced back to connect.
        val cmd = SlashCommand.Mcp(id = "github")
        val ctx = SlashRouteContext(githubConnected = true, githubUsername = null)
        val route = SlashCommandRouter.route(cmd, ctx)

        assertFalse(route.requestGithubConnect)
        assertTrue(route.systemMessage.contains("GitHub"))
    }

    // ═══════════════════════════════════════════════════════════
    // /mcp:<other> — generic fallback (no regression)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `mcp with non-github id uses generic mcp routing regardless of context`() {
        val cmd = SlashCommand.Mcp(id = "postgres")
        val route = SlashCommandRouter.route(cmd, SlashRouteContext(githubConnected = true))

        assertFalse(route.requestGithubConnect)
        assertEquals("🔌 连接 MCP: postgres", route.systemMessage)
        assertTrue(route.agentPrompt.contains("/mcp:postgres"))
        // Must NOT leak github tool ids into a postgres prompt.
        assertFalse(route.agentPrompt.contains("github_"))
    }

    // ═══════════════════════════════════════════════════════════
    // Default context backward-compat
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `route without explicit context defaults to empty and treats github as disconnected`() {
        // Ensures existing call sites that omit context (e.g. future tooling)
        // get safe "not connected" behavior rather than a hollow prompt.
        val cmd = SlashCommand.Mcp(id = "github")
        val route = SlashCommandRouter.route(cmd)

        assertTrue(route.requestGithubConnect)
        assertEquals("", route.agentPrompt)
    }
}
