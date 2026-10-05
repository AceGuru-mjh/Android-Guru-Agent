package com.apex.agent.core.tools

import com.apex.agent.core.tools.catalog.McpAgentTool
import com.apex.agent.core.tools.skill.SkillImplementation
import com.apex.agent.core.tools.skill.SkillStep
import com.apex.agent.core.tools.skill.SkillToolAdapter
import com.apex.agent.core.tools.skill.SkillToolDef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * # 工具韧性 v6 —— 用户规格收口的单元锁定
 *
 * 用户规格三条：
 * 1. 工具出错任务**不停止**；网络类瞬时错误按阶梯重试：
 *    第一次 2s、第二次 5s、第三次 10s …… 直到 3 分钟封顶自动停止；
 * 2. MCP 工具失败 → 错误进 "Error:" 协议（此前服务端 isError 被字符串级
 *    成败判定当成功）+ 换路指引（任务继续，换方式完成）；
 * 3. Skills 步骤失败 → 断点上下文 + 换路指引（不静默短路）。
 *
 * 纯决策/纯渲染逻辑，全部无 IO 无时钟；执行器路径用 runTest 虚拟时间
 * （delay(2s…160s) 瞬时完成，仅断言重试次数与最终结果）。
 */
class ToolResilienceLadderTest {

    // ═══ 1. 阶梯表本体 ═══════════════════════════════════════════

    @Test
    fun `agent ladder follows the user spec exactly`() {
        assertEquals(
            listOf(2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L),
            ToolRetrySchedules.AGENT_LADDER_MS
        )
        assertEquals(180_000L, ToolRetrySchedules.LADDER_CAP_MS)
    }

    @Test
    fun `ladder caps and auto-stops at the 3 minute ceiling`() {
        // 阶梯内：逐级精确返回
        assertEquals(2_000L, ToolRetrySchedules.ladderDelayMs(0))
        assertEquals(160_000L, ToolRetrySchedules.ladderDelayMs(6))
        // 越界：收敛到封顶，且判定已穷尽（下一级 320s ≥ 180s → 自动停止）
        assertEquals(180_000L, ToolRetrySchedules.ladderDelayMs(7))
        assertTrue(ToolRetrySchedules.ladderExhausted(6))
        assertTrue(ToolRetrySchedules.ladderExhausted(7))
        // 末级之前未穷尽
        assertFalse(ToolRetrySchedules.ladderExhausted(5))
        assertFalse(ToolRetrySchedules.ladderExhausted(0))
    }

    @Test
    fun `run policy ladder mode is deterministic - no jitter`() {
        val policy = ToolRunPolicy.agentLadder()
        // 同一 attemptIndex 多次求值完全一致（用户可感知的规格值，禁抖动）
        assertEquals(policy.retryDelayMs(1), policy.retryDelayMs(1))
        assertEquals(5_000L, policy.retryDelayMs(1))
        assertEquals(160_000L, policy.retryDelayMs(6))
        // baseRetryDelayMs 被忽略（ladder 优先）
        val mixed = ToolRunPolicy(
            maxRetries = 7,
            baseRetryDelayMs = 800L,
            ladderDelays = ToolRetrySchedules.AGENT_LADDER_MS
        )
        assertEquals(2_000L, mixed.retryDelayMs(0))
        // 总尝试数 = 1 + 阶梯级数
        assertEquals(1 + ToolRetrySchedules.AGENT_LADDER_MS.size, policy.totalAttempts)
    }

    @Test
    fun `exponential jitter mode still works when no ladder is set`() {
        val policy = ToolRunPolicy(maxRetries = 2, baseRetryDelayMs = 800L)
        val r = kotlin.random.Random(42)
        val d = policy.retryDelayMs(0, r)
        assertTrue("delay in 0..800*2^0", d in 0L..800L)
    }

    // ═══ 2. 执行器路径：阶梯重试把网络类失败顶到底 ═══════════════

    /** 脚本化 retrySafe 工具：预设结果序列（耗尽后重复末位）。 */
    private class ScriptedTool(
        override val id: String,
        private val results: List<String>,
        private val annotations: ToolAnnotations = ToolAnnotations.readOnly()
    ) : AgentTool {
        override val name = id
        override val description = "scripted"
        override val parametersSchema = "{}"
        override val metadata = ToolMetadata(
            id = id, category = ToolCategory.UTILITY, risk = ToolRisk.LOW,
            tags = emptyList(), annotations = annotations
        )
        val calls = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun execute(arguments: String): String {
            val index = calls.getAndIncrement()
            return if (index < results.size) results[index] else results.last()
        }
    }

    private class StaticPolicyResolver(private val policy: ToolRunPolicy) : ToolRunPolicyResolver {
        override fun resolve(tool: AgentTool): ToolRunPolicy = policy
    }

    @Test
    fun `executor retries a flaky network tool through the whole ladder then succeeds`() = runTest {
        // 前六次全失败（网络类瞬时错误），第七次成功 —— 阶梯全程顶住。
        val tool = ScriptedTool(
            "web_fetch",
            listOf(
                "Error: execution failed: socket reset",
                "Error: execution failed: socket reset",
                "Error: execution failed: socket reset",
                "Error: execution failed: socket reset",
                "Error: execution failed: socket reset",
                "Error: execution failed: socket reset",
                "final payload"
            )
        )
        val executor = EnhancedToolExecutor(
            registry = DefaultToolRegistry().apply { register(tool) },
            policyResolver = StaticPolicyResolver(ToolRunPolicy.agentLadder())
        )
        val result = executor.execute("web_fetch", "{}")
        assertEquals("final payload", result)
        // 1 次初始 + 6 次阶梯重试 = 7 次尝试
        assertEquals(7, tool.calls.get())
    }

    @Test
    fun `executor auto-stops after the ladder is exhausted - task error is structured`() = runTest {
        // 永久失败的 retrySafe 工具：阶梯 7 级重试后自动停止（不会无限重试）
        val tool = ScriptedTool("web_fetch", listOf("Error: execution failed: down"))
        val executor = EnhancedToolExecutor(
            registry = DefaultToolRegistry().apply { register(tool) },
            policyResolver = StaticPolicyResolver(ToolRunPolicy.agentLadder())
        )
        val result = executor.execute("web_fetch", "{}")
        assertTrue(result.startsWith("Error"))
        // 1 + 7 级阶梯 = 8 次尝试后封顶自停
        assertEquals(1 + ToolRetrySchedules.AGENT_LADDER_MS.size, tool.calls.get())
    }

    @Test
    fun `permission denials are never retried even under the ladder policy`() = runTest {
        val tool = ScriptedTool("web_fetch", listOf("Error: permission denied: user said no"))
        val executor = EnhancedToolExecutor(
            registry = DefaultToolRegistry().apply { register(tool) },
            policyResolver = StaticPolicyResolver(ToolRunPolicy.agentLadder())
        )
        val result = executor.execute("web_fetch", "{}")
        assertTrue(result.startsWith("Error: permission denied"))
        // 终态分类生效：一次即停，不消耗阶梯
        assertEquals(1, tool.calls.get())
    }

    // ═══ 3. MCP 失败渲染：Error 协议 + 换路指引 ═══════════════════

    @Test
    fun `mcp server-reported failure now carries the error prefix and fallback guidance`() {
        val rendered = McpAgentTool.renderServerError("github", "create_issue", "repo not found")
        assertTrue(rendered.startsWith("Error:"))
        assertTrue(rendered.contains("'github/create_issue'"))
        assertTrue(rendered.contains("NOT aborted"))
        assertTrue(rendered.contains("different approach"))
    }

    @Test
    fun `mcp transport failure carries reconnect hint and fallback guidance`() {
        val rendered = McpAgentTool.renderTransportFailure("github", "create_issue", "connection reset")
        assertTrue(rendered.startsWith("Error:"))
        assertTrue(rendered.contains("mcp_connect('github')"))
        assertTrue(rendered.contains("NOT aborted"))
    }

    // ═══ 4. Skill 步骤失败：断点上下文 + 换路指引 ══════════════════

    /** 脚本化执行器：按工具名返回预设结果（流式通道不被 Skill 适配层使用）。 */
    private class FakeExecutor(private val script: Map<String, String>) : ToolExecutor {
        val executed = mutableListOf<String>()
        override suspend fun execute(toolId: String, arguments: String): String {
            executed += toolId
            return script[toolId] ?: "OK"
        }
        override fun executeStream(toolId: String, arguments: String): kotlinx.coroutines.flow.Flow<ToolStreamEvent> =
            kotlinx.coroutines.flow.flow { emit(ToolStreamEvent.Error("not implemented in fake")) }
    }

    private fun compositeSkill(steps: List<SkillStep>) = SkillToolDef(
        id = "skill_two_step",
        name = "Two Step",
        description = "test skill",
        parameters = "{}",
        implementation = SkillImplementation(type = "composite", steps = steps)
    )

    @Test
    fun `skill step failure reports breakpoint context and continues guidance`() = runTest {
        val executor = FakeExecutor(
            mapOf(
                "fs_read" to "step-1 content",
                "fs_write" to "Error: permission denied: read-only mount"
            )
        )
        val skill = compositeSkill(
            listOf(
                SkillStep(tool = "fs_read", args = mapOf("p" to "a.txt")),
                SkillStep(tool = "fs_write", args = mapOf("p" to "b.txt"))
            )
        )
        val adapter = SkillToolAdapter(skill, executor)
        val result = adapter.execute("{}")

        assertTrue(result, result.startsWith("Error: skill 'skill_two_step' stopped at step 2/2"))
        assertTrue(result, result.contains("'fs_write'"))
        // 断点上下文：最后一段成功输出被带回，模型可从断点换路续跑
        assertTrue(result, result.contains("step-1 content"))
        assertTrue(result, result.contains("NOT aborted"))
        // 失败发生在第 2 步：第 1 步已执行
        assertEquals(listOf("fs_read", "fs_write"), executor.executed)
    }

    @Test
    fun `skill all-steps-success path is unchanged`() = runTest {
        val executor = FakeExecutor(mapOf("fs_read" to "A", "fs_write" to "B"))
        val skill = compositeSkill(
            listOf(
                SkillStep(tool = "fs_read", args = mapOf("p" to "a.txt")),
                SkillStep(tool = "fs_write", args = mapOf("p" to "b.txt"))
            )
        )
        val adapter = SkillToolAdapter(skill, executor)
        assertEquals("B", adapter.execute("{}"))
    }
}
