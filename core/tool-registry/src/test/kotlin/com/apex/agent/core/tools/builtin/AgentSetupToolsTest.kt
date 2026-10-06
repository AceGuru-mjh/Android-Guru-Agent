package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.AgentSettingGetTool
import com.apex.agent.core.tools.builtin.AgentSettingSetTool
import com.apex.agent.core.tools.builtin.AgentSettingsPatch
import com.apex.agent.core.tools.builtin.AgentSettingsSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent 自主设置工具的纯 JVM 测试。
 *
 * 测试范围：
 * - agent_setting_get：读取快照（无参数 → 返回 JSON）
 * - agent_setting_set：白名单字段校验、空 patch 拒绝、枚举值校验、
 *   apply 失败回滚、apply 成功返回 OK
 *
 * 不测的（需 Android）：
 * - 实际持久化（SettingsRepository），由 [AgentSetupHost] 在 app 模块接
 *   入；本测试用 [FakeAgentSettingsHost] 替代。
 */
class AgentSetupToolsTest {

    // ── agent_setting_get ────────────────────────────────────────────────

    @Test
    fun `setting_get returns current snapshot as JSON`() = runTest {
        val host = FakeAgentSettingsHost(initial = AgentSettingsSnapshot(maxIterations = 25))
        val tool = AgentSettingGetTool(host)
        val result = tool.execute("{}")
        assertTrue("should succeed: $result", result.startsWith("OK") || !result.startsWith("Error"))
        assertTrue("should contain max_iterations: 25", result.contains("\"maxIterations\": 25"))
    }

    @Test
    fun `setting_get returns default snapshot when host empty`() = runTest {
        val host = FakeAgentSettingsHost(initial = AgentSettingsSnapshot.EMPTY)
        val tool = AgentSettingGetTool(host)
        val result = tool.execute("{}")
        assertTrue("should contain default_mode", result.contains("defaultMode"))
        assertTrue("should contain think_level", result.contains("thinkLevel"))
    }

    // ── agent_setting_set — happy paths ──────────────────────────────────

    @Test
    fun `setting_set applies a single int field`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"max_iterations": 30}""")
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertEquals(30, host.appliedPatch?.maxIterations)
        assertEquals(1, host.applyCallCount)
    }

    @Test
    fun `setting_set applies multiple fields of mixed types`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute(
            """{"force_deep_thinking": true, "default_mode": "build", "language": "zh", "compression_threshold": 0.85}"""
        )
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertEquals(true, host.appliedPatch?.forceDeepThinking)
        assertEquals("build", host.appliedPatch?.defaultMode)
        assertEquals("zh", host.appliedPatch?.language)
        assertEquals(0.85f, host.appliedPatch?.compressionThreshold)
    }

    @Test
    fun `setting_set result mentions applied field count`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"max_iterations": 10, "language": "en"}""")
        assertTrue("should mention count: $result", result.contains("2 field"))
    }

    // ── agent_setting_set — validation failures ─────────────────────────

    @Test
    fun `setting_set rejects an empty patch`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("{}")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention field name: $result", result.contains("patch"))
        assertEquals(0, host.applyCallCount)
    }

    @Test
    fun `setting_set rejects invalid default_mode enum`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"default_mode": "super"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention default_mode: $result", result.contains("default_mode"))
    }

    @Test
    fun `setting_set rejects out-of-range max_iterations`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"max_iterations": 100}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention max_iterations: $result", result.contains("max_iterations"))
    }

    @Test
    fun `setting_set rejects invalid theme_mode enum`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"theme_mode": "purple"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `setting_set rejects unknown accent_palette`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"accent_palette": "rainbow"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `setting_set rejects negative max_retry_per_action`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"max_retry_per_action": -1}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `setting_set rejects compression_threshold out of bounds`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"compression_threshold": 0.3}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `setting_set rejects bad JSON`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("not json at all")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `setting_set handles host apply failure gracefully`() = runTest {
        val host = FakeAgentSettingsHost(applyReturns = false)
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"max_iterations": 5}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention execution: $result", result.contains("EXECUTION_FAILED") || result.contains("host"))
    }

    // ── AgentSettingsPatch helpers ───────────────────────────────────────

    @Test
    fun `empty patch reports isEmpty true and count 0`() {
        val p = AgentSettingsPatch()
        assertTrue(p.isEmpty())
        assertEquals(0, p.changedFieldCount())
    }

    @Test
    fun `patch with one field reports count 1`() {
        val p = AgentSettingsPatch(maxIterations = 10)
        assertEquals(false, p.isEmpty())
        assertEquals(1, p.changedFieldCount())
    }

    @Test
    fun `patch with multiple fields reports correct count`() {
        val p = AgentSettingsPatch(
            maxIterations = 10,
            language = "en",
            forceDeepThinking = true,
            themeMode = "dark"
        )
        assertEquals(4, p.changedFieldCount())
    }

    // ── Fake host ─────────────────────────────────────────────────────────

    private class FakeAgentSettingsHost(
        initial: AgentSettingsSnapshot = AgentSettingsSnapshot(),
        private val applyReturns: Boolean = true
    ) : AgentSettingsHost {
        @Volatile private var current: AgentSettingsSnapshot = initial
        @Volatile var appliedPatch: AgentSettingsPatch? = null
            private set
        @Volatile var applyCallCount: Int = 0
            private set

        override suspend fun snapshot(): AgentSettingsSnapshot = current

        override suspend fun apply(patch: AgentSettingsPatch): Boolean {
            appliedPatch = patch
            applyCallCount++
            if (applyReturns) {
                // mutate current for next read (best-effort)
                current = current.copy(
                    maxIterations = patch.maxIterations ?: current.maxIterations,
                    defaultMode = patch.defaultMode ?: current.defaultMode,
                    forceDeepThinking = patch.forceDeepThinking ?: current.forceDeepThinking,
                    language = patch.language ?: current.language,
                    themeMode = patch.themeMode ?: current.themeMode,
                    compressionThreshold = patch.compressionThreshold ?: current.compressionThreshold
                )
            }
            return applyReturns
        }
    }
}
