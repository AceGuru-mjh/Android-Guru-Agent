package com.apex.agent.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool System v4 — per-request tool choice (forced function calling) +
 * request-side hardening (description clamp / Gemini schema strip).
 *
 * Covers the v4 additions to [StreamingOpenAiClient.buildRequestBody]:
 * - toolChoiceOverride = required / specific function / none;
 * - no override → profile-level ToolChoiceMode fallback (byte-identical);
 * - Gemini-compat endpoints strip unsupported schema keywords;
 * - non-Gemini endpoints pass schemas through untouched;
 * - oversized descriptions are clamped.
 */
class ToolChoiceSpecRequestTest {

    private val http = OkHttpClient()

    private fun client(
        baseUrl: String = "https://api.example.com/v1",
        toolChoice: ToolChoiceMode = ToolChoiceMode.AUTO
    ): StreamingOpenAiClient = StreamingOpenAiClient(
        LlmConfig(
            baseUrl = baseUrl, apiKey = "k", model = "m",
            enableTools = true, toolChoice = toolChoice
        ),
        http
    )

    private val oneTool = listOf(
        ToolDefinition(
            name = "read_file",
            description = "Read a file",
            parameters = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
        )
    )

    private fun body(
        toolChoice: ToolChoiceSpec?,
        baseUrl: String = "https://api.example.com/v1",
        tools: List<ToolDefinition> = oneTool
    ): JsonObject = client(baseUrl).buildRequestBody(
        messages = listOf(LlmMessage.User("hi")),
        tools = tools,
        temperature = -1f,
        maxTokens = -1,
        stream = false,
        toolChoiceOverride = toolChoice
    )

    private fun JsonObject.toolChoicePrimitive(): String? =
        this["tool_choice"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.toolChoiceFunctionName(): String? =
        this["tool_choice"]?.jsonObject?.get("function")?.jsonObject?.get("name")
            ?.jsonPrimitive?.content

    // ── v4 forced tool choice ─────────────────────────────────────

    @Test
    fun `required override serializes as required`() {
        val body = body(ToolChoiceSpec.Required)
        assertEquals("required", body.toolChoicePrimitive())
    }

    @Test
    fun `none override serializes as none`() {
        val body = body(ToolChoiceSpec.None)
        assertEquals("none", body.toolChoicePrimitive())
    }

    @Test
    fun `specific function override serializes as function object`() {
        val body = body(ToolChoiceSpec.Function("read_file"))
        assertEquals("function", body["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("read_file", body.toolChoiceFunctionName())
    }

    @Test
    fun `auto override serializes as auto`() {
        val body = body(ToolChoiceSpec.Auto)
        assertEquals("auto", body.toolChoicePrimitive())
    }

    @Test
    fun `null override falls back to profile toolChoice`() {
        val bodyWithProfile = client(toolChoice = ToolChoiceMode.REQUIRED).buildRequestBody(
            messages = listOf(LlmMessage.User("hi")),
            tools = oneTool,
            temperature = -1f,
            maxTokens = -1,
            stream = false,
            toolChoiceOverride = null
        )
        assertEquals("required", bodyWithProfile.toolChoicePrimitive())
    }

    @Test
    fun `tool_choice absent when no function tools present`() {
        val body = body(ToolChoiceSpec.Required, tools = emptyList())
        assertNull(body["tool_choice"])
        assertNull(body["tools"])
    }

    // ── v4 request-side hardening ──────────────────────────────────

    @Test
    fun `gemini endpoint strips unsupported schema keywords`() {
        val tools = listOf(
            ToolDefinition(
                name = "pick",
                description = "Pick a color",
                parameters = """{"type":"object","properties":{"c":{"type":"string","enum":["red","blue"],"format":"color"}},"additionalProperties":false}"""
            )
        )
        val body = body(ToolChoiceSpec.Auto, baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai", tools = tools)
        val params = body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        val prop = params["properties"]!!.jsonObject["c"]!!.jsonObject
        assertNull(prop["enum"])
        assertNull(prop["format"])
        assertNull(params["additionalProperties"])
        assertEquals("string", prop["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `non-gemini endpoint keeps enum and format`() {
        val tools = listOf(
            ToolDefinition(
                name = "pick",
                description = "Pick a color",
                parameters = """{"type":"object","properties":{"c":{"type":"string","enum":["red"]}}}"""
            )
        )
        val body = body(ToolChoiceSpec.Auto, tools = tools)
        val prop = body["tools"]!!.jsonArray[0].jsonObject["function"]!!
            .jsonObject["parameters"]!!.jsonObject["properties"]!!
            .jsonObject["c"]!!.jsonObject
        assertNotNull(prop["enum"])
    }

    @Test
    fun `oversized description is clamped in request`() {
        val big = "A".repeat(3000)
        val tools = listOf(ToolDefinition("t1", big, """{"type":"object","properties":{}}"""))
        val body = body(ToolChoiceSpec.Auto, tools = tools)
        val desc = body["tools"]!!.jsonArray[0].jsonObject["function"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(desc.length < 1100)
        assertTrue(desc.contains("[description truncated"))
    }

    @Test
    fun `invalid schema falls back to object skeleton`() {
        val tools = listOf(ToolDefinition("t1", "d", "not-json{{"))
        val body = body(ToolChoiceSpec.Auto, tools = tools)
        val fn = body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        val params = fn["parameters"]!!.jsonObject
        assertEquals("object", params["type"]?.jsonPrimitive?.content)
        val parsed = runCatching { Json.parseToJsonElement(params.toString()) }
        assertTrue(parsed.isSuccess)
        assertFalse(params.toString().contains("not-json"))
    }
}
