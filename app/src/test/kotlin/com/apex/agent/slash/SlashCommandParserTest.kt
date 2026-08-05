package com.apex.agent.slash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SlashCommandParser].
 *
 * These cover the grammar `/<type>:<id> [key=value ...] [positional user
 * text ...]` plus the documented degradation paths (missing slash, missing
 * colon, empty id, unknown type, malformed kv tokens). The parser is pure
 * JVM so the tests run without an Android or Compose runtime.
 */
class SlashCommandParserTest {

    // ═══════════════════════════════════════════════════════════
    // Happy paths — one per known command type
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `bare skill command parses to Skill with empty args`() {
        val parsed = SlashCommandParser.parse("/skill:code_interpreter")

        assertTrue(parsed is SlashCommand.Skill)
        val skill = parsed as SlashCommand.Skill
        assertEquals("code_interpreter", skill.id)
        assertEquals(emptyMap<String, String>(), skill.args)
        assertEquals("", skill.userExtra)
    }

    @Test
    fun `bare mcp command parses to Mcp`() {
        val parsed = SlashCommandParser.parse("/mcp:github")

        assertTrue(parsed is SlashCommand.Mcp)
        assertEquals("github", (parsed as SlashCommand.Mcp).id)
    }

    @Test
    fun `bare connector command parses to Connector`() {
        val parsed = SlashCommandParser.parse("/connector:ssh")

        assertTrue(parsed is SlashCommand.Connector)
        assertEquals("ssh", (parsed as SlashCommand.Connector).id)
    }

    @Test
    fun `bare plugin command parses to Plugin`() {
        val parsed = SlashCommandParser.parse("/plugin:pdf_reader")

        assertTrue(parsed is SlashCommand.Plugin)
        assertEquals("pdf_reader", (parsed as SlashCommand.Plugin).id)
    }

    // ═══════════════════════════════════════════════════════════
    // Args + positional user text
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `kv args and positional user text are split correctly`() {
        val parsed = SlashCommandParser.parse("/skill:web_search query=Android latest news")

        assertTrue(parsed is SlashCommand.Skill)
        val skill = parsed as SlashCommand.Skill
        assertEquals("web_search", skill.id)
        assertEquals(mapOf("query" to "Android"), skill.args)
        assertEquals("latest news", skill.userExtra)
    }

    @Test
    fun `multiple kv args are collected in insertion order`() {
        val parsed = SlashCommandParser.parse("/mcp:github repo=owner/name state=open")

        val mcp = parsed as SlashCommand.Mcp
        assertEquals("github", mcp.id)
        assertEquals(mapOf("repo" to "owner/name", "state" to "open"), mcp.args)
        assertEquals("", mcp.userExtra)
    }

    @Test
    fun `kv value may contain equals signs`() {
        // Regex captures key=([A-Za-z_][\w.\-]*) and value=(.*) ; the value
        // side is greedy, so "a=b=c" → key=a, value="b=c".
        val parsed = SlashCommandParser.parse("/skill:foo filter=a=b=c")

        val skill = parsed as SlashCommand.Skill
        assertEquals(mapOf("filter" to "a=b=c"), skill.args)
    }

    @Test
    fun `kv parsing stops once positional text begins`() {
        // After the first positional token, subsequent key=value-shaped tokens
        // are treated as user text (CLI-style) rather than dropped into args.
        val parsed = SlashCommandParser.parse("/skill:foo keep=this drop=that")

        val skill = parsed as SlashCommand.Skill
        assertEquals(mapOf("keep" to "this"), skill.args)
        assertEquals("drop=that", skill.userExtra)
    }

    @Test
    fun `kv key must start with letter or underscore`() {
        // "1key=val" doesn't match the key regex → positional user text.
        val parsed = SlashCommandParser.parse("/skill:foo 1key=val real=v")

        val skill = parsed as SlashCommand.Skill
        assertEquals(mapOf("real" to "v"), skill.args)
        assertEquals("1key=val", skill.userExtra)
    }

    @Test
    fun `user extra text is preserved verbatim including internal whitespace`() {
        val parsed = SlashCommandParser.parse("/skill:foo   multiple    spaces   here")

        val skill = parsed as SlashCommand.Skill
        assertEquals("multiple    spaces   here", skill.userExtra)
    }

    // ═══════════════════════════════════════════════════════════
    // Whitespace tolerance
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `leading whitespace is tolerated`() {
        val parsed = SlashCommandParser.parse("   /skill:code_interpreter")

        assertTrue(parsed is SlashCommand.Skill)
        assertEquals("code_interpreter", (parsed as SlashCommand.Skill).id)
    }

    @Test
    fun `trailing whitespace is trimmed from id`() {
        val parsed = SlashCommandParser.parse("/skill:code_interpreter   ")

        assertTrue(parsed is SlashCommand.Skill)
        assertEquals("code_interpreter", (parsed as SlashCommand.Skill).id)
    }

    // ═══════════════════════════════════════════════════════════
    // Degradation to Unknown
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `input without leading slash is Unknown`() {
        val parsed = SlashCommandParser.parse("skill:missing_slash")

        assertTrue(parsed is SlashCommand.Unknown)
        assertEquals("skill:missing_slash", (parsed as SlashCommand.Unknown).raw)
    }

    @Test
    fun `input with missing colon is Unknown`() {
        val parsed = SlashCommandParser.parse("/skill")

        assertTrue(parsed is SlashCommand.Unknown)
    }

    @Test
    fun `input with empty id is Unknown`() {
        val parsed = SlashCommandParser.parse("/skill:")

        assertTrue(parsed is SlashCommand.Unknown)
    }

    @Test
    fun `input with empty type is Unknown`() {
        // "/:foo" → colon at index 1 → type substring is empty.
        val parsed = SlashCommandParser.parse("/:foo")

        assertTrue(parsed is SlashCommand.Unknown)
    }

    @Test
    fun `input with unknown type is Unknown`() {
        val parsed = SlashCommandParser.parse("/unknown:foo")

        assertTrue(parsed is SlashCommand.Unknown)
    }

    @Test
    fun `unknown command forwards raw text verbatim`() {
        val parsed = SlashCommandParser.parse("/help")

        assertTrue(parsed is SlashCommand.Unknown)
        assertEquals("/help", (parsed as SlashCommand.Unknown).raw)
    }

    // ═══════════════════════════════════════════════════════════
    // Helper
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `looksLikeSlash detects leading slash after whitespace`() {
        assertTrue(SlashCommand.looksLikeSlash("/skill:foo"))
        assertTrue(SlashCommand.looksLikeSlash("   /skill:foo"))
        assertTrue(!SlashCommand.looksLikeSlash("skill:foo"))
        assertTrue(!SlashCommand.looksLikeSlash(""))
    }

    @Test
    fun `supported types list is stable and ordered`() {
        // Order matters for slash-menu rendering; assert it explicitly so a
        // future reorder is a conscious decision rather than an accident.
        assertEquals(
            listOf("skill", "mcp", "connector", "plugin"),
            SlashCommand.SUPPORTED_TYPES
        )
    }
}
