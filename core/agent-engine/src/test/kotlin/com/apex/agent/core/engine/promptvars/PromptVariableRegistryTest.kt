package com.apex.agent.core.engine.promptvars

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 4-b — 提示词变量注册表测试。
 *
 * 确定性策略：Locale.US 注册表 + 假钟固定 epoch + 上下文 UTC 时区，
 * 时间类变量可断言精确字符串（不依赖宿主机时区/语言）。
 *
 * 固定 epoch：1735689600000 = 2025-01-01T00:00:00Z（周三）；
 * 偏移 epoch：1735745696000 = 同日 15:34:56Z。
 */
class PromptVariableRegistryTest {

    private val epoch = 1735689600000L
    private val epochLater = 1735745696000L

    private val registry = PromptVariableRegistry(Locale.US)

    private val ctx: PromptVariableContext = PromptVariableContext.DEFAULT.copy(
        nowMs = { epoch }
    )

    // ═══ 时间类内置变量（假钟 + UTC + Locale.US 精确断言）═══

    @Test
    fun `time date datetime resolve exactly with fixed clock`() {
        assertEquals("00:00", registry.resolve("time", ctx))
        assertEquals("2025-01-01", registry.resolve("date", ctx))
        assertEquals("2025-01-01 00:00", registry.resolve("datetime", ctx))
    }

    @Test
    fun `weekday resolves to localized short name`() {
        assertEquals("Wed", registry.resolve("weekday", ctx))
    }

    @Test
    fun `time formatting honors context timezone`() {
        val shanghai = ctx.copy(timeZoneId = "Asia/Shanghai")
        assertEquals("08:00", registry.resolve("time", shanghai))
        assertEquals("2025-01-01", registry.resolve("date", shanghai))
        // 与 UTC 相差 8 小时，证明时区真实生效
        assertEquals("00:00", registry.resolve("time", ctx))
    }

    @Test
    fun `different clock value yields different time strings`() {
        val later = ctx.copy(nowMs = { epochLater })
        assertEquals("15:34", registry.resolve("time", later))
        assertEquals("2025-01-01 15:34", registry.resolve("datetime", later))
        assertEquals("Wed", registry.resolve("weekday", later))
    }

    @Test
    fun `weekday localization differs per registry locale`() {
        val french = PromptVariableRegistry(Locale.FRENCH)
        val frenchWeekday = french.resolve("weekday", ctx)
        assertEquals("Wed", registry.resolve("weekday", ctx))
        assertTrue("french weekday should be localized: '$frenchWeekday'", frenchWeekday.isNotBlank())
        assertFalse(frenchWeekday == "Wed")
    }

    // ═══ 会话级内置变量 ═══

    @Test
    fun `session variables resolve from context`() {
        assertEquals("gemini-2.0-flash", registry.resolve("model_id", ctx))
        assertEquals("Gemini 2.0 Flash", registry.resolve("model_name", ctx))
        assertEquals("BUILD", registry.resolve("mode", ctx))
        assertEquals("STANDARD", registry.resolve("thinking_level", ctx))
        assertEquals("12", registry.resolve("tool_count", ctx))
        assertEquals("session-test", registry.resolve("session_id", ctx))
    }

    @Test
    fun `tool count zero is preserved`() {
        assertEquals("0", registry.resolve("tool_count", ctx.copy(toolCount = 0)))
    }

    // ═══ 用户级人设变量（含回退）═══

    @Test
    fun `persona variables resolve with fallbacks`() {
        assertEquals("Apex Agent", registry.resolve("agent_name", ctx))
        assertEquals("Boss", registry.resolve("user_name", ctx))
        val blank = ctx.copy(agentName = "", userTitle = "   ")
        assertEquals("Apex Agent", registry.resolve("agent_name", blank))
        assertEquals("user", registry.resolve("user_name", blank))
    }

    @Test
    fun `persona variables honor context overrides`() {
        val persona = ctx.copy(agentName = "小助手", userTitle = "老板")
        assertEquals("小助手", registry.resolve("agent_name", persona))
        assertEquals("老板", registry.resolve("user_name", persona))
    }

    // ═══ 环境快照变量 ═══

    @Test
    fun `environment variables resolve from context`() {
        assertEquals("zh-CN", registry.resolve("locale", ctx))
        assertEquals("UTC", registry.resolve("timezone", ctx))
        assertEquals("Android", registry.resolve("platform", ctx))
        assertEquals("Pixel 9 Pro", registry.resolve("device", ctx))
        assertEquals("Wi-Fi", registry.resolve("network", ctx))
        assertEquals("85%", registry.resolve("battery", ctx))
    }

    @Test
    fun `os composes platform and device summary`() {
        assertEquals("Android Pixel 9 Pro", registry.resolve("os", ctx))
        assertEquals("Android", registry.resolve("os", ctx.copy(deviceSummary = "")))
        assertEquals("Pixel 9 Pro", registry.resolve("os", ctx.copy(platform = "")))
        assertEquals("unknown", registry.resolve("os", ctx.copy(platform = "", deviceSummary = " ")))
    }

    @Test
    fun `blank environment fields fall back to unknown`() {
        val blank = ctx.copy(
            platform = "", deviceSummary = "", networkSummary = "", batterySummary = "",
            modelId = "", modelName = "", sessionId = ""
        )
        assertEquals("unknown", registry.resolve("platform", blank))
        assertEquals("unknown", registry.resolve("device", blank))
        assertEquals("unknown", registry.resolve("network", blank))
        assertEquals("unknown", registry.resolve("battery", blank))
        assertEquals("unknown", registry.resolve("model_id", blank))
        assertEquals("unknown", registry.resolve("model_name", blank))
        assertEquals("none", registry.resolve("session_id", blank))
    }

    // ═══ 自定义变量与优先级 ═══

    @Test
    fun `custom variable registers and resolves`() {
        registry.registerCustom("project", "Apex Agent 仓库")
        assertEquals("Apex Agent 仓库", registry.resolve("project", ctx))
        assertEquals(mapOf("project" to "Apex Agent 仓库"), registry.customVariables())
    }

    @Test
    fun `custom variable overrides built-in and unregister restores it`() {
        assertEquals("00:00", registry.resolve("time", ctx))
        registry.registerCustom("time", "演示时间")
        assertEquals("演示时间", registry.resolve("time", ctx))
        assertTrue(registry.unregisterCustom("time"))
        assertEquals("00:00", registry.resolve("time", ctx))
        assertFalse(registry.unregisterCustom("time"))
    }

    @Test
    fun `context custom has highest precedence`() {
        registry.registerCustom("device", "注册表设备")
        assertEquals("注册表设备", registry.resolve("device", ctx))
        val withCustom = ctx.copy(custom = mapOf("device" to "上下文设备"))
        assertEquals("上下文设备", registry.resolve("device", withCustom))
        // 内置名同样可被 context.custom 覆盖
        val overrideTime = ctx.copy(custom = mapOf("time" to "09:99"))
        assertEquals("09:99", registry.resolve("time", overrideTime))
    }

    @Test
    fun `re-register overwrites previous custom value`() {
        registry.registerCustom("city", "北京")
        registry.registerCustom("city", "上海")
        assertEquals("上海", registry.resolve("city", ctx))
    }

    // ═══ 命名规范与大小写 ═══

    @Test
    fun `lookup is case-insensitive`() {
        assertEquals("gemini-2.0-flash", registry.resolve("MODEL_ID", ctx))
        assertEquals("Gemini 2.0 Flash", registry.resolve("Model_Name", ctx))
        assertEquals("Android", registry.resolve(" PLATFORM ", ctx))
    }

    @Test
    fun `mixed case custom name is canonicalized to lowercase`() {
        registry.registerCustom("My_Project", "值")
        assertEquals("值", registry.resolve("my_project", ctx))
        assertEquals("值", registry.resolve("MY_PROJECT", ctx))
        assertTrue(registry.customVariables().containsKey("my_project"))
    }

    @Test
    fun `illegal custom names are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { registry.registerCustom("2fast", "x") }
        assertThrows(IllegalArgumentException::class.java) { registry.registerCustom("has-dash", "x") }
        assertThrows(IllegalArgumentException::class.java) { registry.registerCustom("has space", "x") }
        assertThrows(IllegalArgumentException::class.java) { registry.registerCustom("", "x") }
        assertThrows(IllegalArgumentException::class.java) { registry.registerCustom("中文", "x") }
    }

    // ═══ 元数据 ═══

    @Test
    fun `definitions contain all built-ins in order plus customs`() {
        val before = registry.definitions()
        assertEquals(19, before.size)
        assertTrue(before.all { it.isBuiltIn })
        assertEquals(
            listOf(
                "model_id", "model_name", "mode", "thinking_level", "tool_count", "session_id",
                "agent_name", "user_name",
                "time", "date", "datetime", "weekday",
                "locale", "timezone", "platform", "device", "network", "battery", "os"
            ),
            before.map { it.name }
        )
        // 作用域抽查
        assertEquals(VariableScope.SESSION, before.first { it.name == "session_id" }.scope)
        assertEquals(VariableScope.SYSTEM, before.first { it.name == "time" }.scope)
        assertEquals(VariableScope.USER, before.first { it.name == "user_name" }.scope)
        // 示例值齐全（设置页提示用）
        assertTrue(before.all { it.example.isNotBlank() })
        assertTrue(before.all { it.description.isNotBlank() })

        registry.registerCustom("extra", "x")
        val after = registry.definitions()
        assertEquals(20, after.size)
        val extra = after.last()
        assertEquals("extra", extra.name)
        assertFalse(extra.isBuiltIn)
        assertEquals(VariableScope.USER, extra.scope)
    }

    @Test
    fun `isBuiltIn reflects known built-in names case-insensitively`() {
        assertTrue(registry.isBuiltIn("model_id"))
        assertTrue(registry.isBuiltIn("OS"))
        assertFalse(registry.isBuiltIn("nope"))
        assertFalse(registry.isBuiltIn("illegal-name"))
        registry.registerCustom("custom_one", "x")
        assertFalse(registry.isBuiltIn("custom_one"))
    }

    // ═══ 未知变量 ═══

    @Test
    fun `unknown or illegal names resolve to null and empty string`() {
        assertNull(registry.resolveOrNull("does_not_exist", ctx))
        assertNull(registry.resolveOrNull("1illegal", ctx))
        assertNull(registry.resolveOrNull("", ctx))
        assertEquals("", registry.resolve("does_not_exist", ctx))
    }
}
