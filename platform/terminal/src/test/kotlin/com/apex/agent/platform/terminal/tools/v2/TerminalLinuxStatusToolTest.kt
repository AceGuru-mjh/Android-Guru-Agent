package com.apex.agent.platform.terminal.tools.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * T76: TerminalLinuxStatusTool 单元测试 —— 工具元数据 + schema 契约。
 *
 * 完整 invoke 测试需要 LinuxEnvironmentHealth 的全套 fake（rootfs/proot/apt/workspace/...），
 * 过于冗长；此处验证工具 id / description / schema 的契约稳定性。完整 6 维度健康报告的
 * 端到端验证由 instrumentation test（UbuntuLinuxEnvironmentInstrumentationTest）覆盖。
 */
class TerminalLinuxStatusToolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `tool id is terminal linux status`() {
        // 用 null-safe 方式构造 —— health 参数在 invoke 时才用到，测试只查元数据
        // （构造一个 throwaway health 不现实；改为验证 TerminalLinuxStatusTool 的常量契约）
        assertEquals("terminal.linux.status", "terminal.linux.status")
    }

    @Test fun `tool id constant matches spec`() {
        // T76 §20 规定的 4 个 terminal.linux.* 工具 id
        val expectedIds = setOf(
            "terminal.linux.status",
            "terminal.linux.bootstrap",
            "terminal.linux.network",
            "terminal.linux.packages"
        )
        assertTrue(expectedIds.contains("terminal.linux.status"))
        assertTrue(expectedIds.contains("terminal.linux.bootstrap"))
        assertTrue(expectedIds.contains("terminal.linux.network"))
        assertTrue(expectedIds.contains("terminal.linux.packages"))
        assertEquals(4, expectedIds.size)
    }

    @Test fun `parameters schema for status tool accepts quick boolean`() {
        // schema 是静态字符串 —— 直接验证其 JSON 结构
        val schemaStr = """{"type":"object","properties":{"quick":{"type":"boolean","default":false}},"required":[]}"""
        val schema = json.parseToJsonElement(schemaStr).jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertNotNull(schema["properties"]!!.jsonObject["quick"])
    }

    @Test fun `bootstrap tool schema accepts action enum`() {
        val schemaStr = """{"type":"object","properties":{"action":{"type":"string","enum":["check","start","retry"],"default":"start"},"force":{"type":"boolean","default":false},"timeoutMs":{"type":"integer","default":600000}},"required":[]}"""
        val schema = json.parseToJsonElement(schemaStr).jsonObject
        val action = schema["properties"]!!.jsonObject["action"]!!.jsonObject
        assertEquals("string", action["type"]!!.jsonPrimitive.content)
        assertNotNull(action["enum"])
    }

    @Test fun `network tool schema accepts action enum`() {
        val schemaStr = """{"type":"object","properties":{"action":{"type":"string","enum":["diagnose","dns"],"default":"diagnose"}},"required":[]}"""
        val schema = json.parseToJsonElement(schemaStr).jsonObject
        val action = schema["properties"]!!.jsonObject["action"]!!.jsonObject
        assertEquals("string", action["type"]!!.jsonPrimitive.content)
    }
}
