package com.apex.agent.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ToolCallArgumentRepairTest {

    @Test
    fun `valid json object passes through unchanged`() {
        val raw = """{"path": "main.py", "edits": [{"search": "a", "replace": "b"}]}"""
        assertEquals(raw, repairToolCallArguments(raw))
    }

    @Test
    fun `json with surrounding whitespace is kept trimmed`() {
        val raw = """  {"path": "x"}  """
        assertEquals("""{"path": "x"}""", repairToolCallArguments(raw))
    }

    @Test
    fun `markdown fenced json is extracted`() {
        val raw = "```json\n{\"path\": \"x\", \"content\": \"hi\"}\n```"
        assertEquals("""{"path": "x", "content": "hi"}""", repairToolCallArguments(raw))
    }

    @Test
    fun `plain markdown fence without json tag is extracted`() {
        val raw = "```\n{\"a\": 1}\n```"
        assertEquals("""{"a": 1}""", repairToolCallArguments(raw))
    }

    @Test
    fun `json embedded in prose is extracted`() {
        val raw = "Here is the call:\n{\"path\": \"/tmp/a.txt\", \"content\": \"x\"}\nLet me know."
        assertEquals("""{"path": "/tmp/a.txt", "content": "x"}""", repairToolCallArguments(raw))
    }

    @Test
    fun `invalid text without braces returns null`() {
        assertNull(repairToolCallArguments("hello world, no json here"))
    }

    @Test
    fun `empty or blank input returns null`() {
        assertNull(repairToolCallArguments(""))
        assertNull(repairToolCallArguments("   "))
    }

    @Test
    fun `unbalanced braces returns null`() {
        assertNull(repairToolCallArguments("\u007b\"path\": \"x\""))
    }

    @Test
    fun `nested braces still extract whole object`() {
        val raw = "prefix {\"a\": {\"b\": [1, 2]}, \"c\": \"\u007dd\"} suffix"
        val repaired = repairToolCallArguments(raw)
        assertNotNull(repaired)
        // 应从第一个 { 到最后一个 } 完整截取
        assertEquals("{\"a\": {\"b\": [1, 2]}, \"c\": \"\u007dd\"}", repaired)
    }
}
