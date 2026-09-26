package com.apex.agent.core.engine.thinking

import com.apex.agent.core.engine.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #168 六档 + v1.2 新两档思考画像完整性测试（八档全覆盖）。
 *
 * 锁定 [ThinkingProfile.forLevel] 的静态表：
 *  1. 八档画像全部存在且互相**真实差异**（迭代倍率 / 预算 / 验证策略 /
 *     压缩倍率 / 输出预算单调或分档成立），绝非只有数值不同的空壳；
 *  2. forLevel 幂等（同档多次调用等值）；
 *  3. 提示词迁移正确（NONE/AUTO 空、LIGHT..APEXCODE 非空且含档位特有指令）；
 *  4. 自评清单 / 工具自检模板可用（引擎注入路径的内容源）；
 *  5. v1.2 新档：ULTRACODE 编码闭环画像 / APEXCODE 架构穷举画像全字段断言，
 *     含 compressionAggressiveness < 1 的「更晚压缩」语义验证。
 */
class ThinkingProfileTest {

    private val allLevels = listOf(
        ThinkingLevel.NONE, ThinkingLevel.LIGHT, ThinkingLevel.STANDARD,
        ThinkingLevel.DEEP, ThinkingLevel.MAXIMUM, ThinkingLevel.ULTRACODE,
        ThinkingLevel.APEXCODE, ThinkingLevel.AUTO
    )

    @Test
    fun `all eight levels have profiles and non-blank ui descriptions`() {
        allLevels.forEach { level ->
            val p = ThinkingProfile.forLevel(level)
            assertEquals(level, p.level)
            assertTrue("zh desc blank for $level", p.uiDescriptionZh.isNotBlank())
            assertTrue("en desc blank for $level", p.uiDescriptionEn.isNotBlank())
        }
    }

    @Test
    fun `forLevel is idempotent`() {
        allLevels.forEach { level ->
            assertEquals(
                "forLevel($level) must be stable",
                ThinkingProfile.forLevel(level),
                ThinkingProfile.forLevel(level)
            )
        }
    }

    @Test
    fun `iteration scale strictly increases with depth for the seven fixed levels`() {
        val scales = listOf(
            ThinkingLevel.NONE to 0.8f,
            ThinkingLevel.LIGHT to 0.9f,
            ThinkingLevel.STANDARD to 1.0f,
            ThinkingLevel.DEEP to 1.2f,
            ThinkingLevel.MAXIMUM to 1.5f,
            ThinkingLevel.ULTRACODE to 2.0f,
            ThinkingLevel.APEXCODE to 3.0f
        )
        scales.forEach { (level, expected) ->
            assertEquals("scale mismatch for $level", expected, ThinkingProfile.forLevel(level).maxIterationsScale, 0.0001f)
        }
        // 单调：快档提前止损，深档允许更多 ReAct 轮次（7 深度档全阶梯）
        val ordered = scales.map { ThinkingProfile.forLevel(it.first).maxIterationsScale }
        assertTrue("scales must be ascending", ordered.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `thinking budgets and reasoning efforts are real per-level parameters`() {
        assertEquals(0, ThinkingProfile.forLevel(ThinkingLevel.NONE).thinkingBudget)
        assertEquals(256, ThinkingProfile.forLevel(ThinkingLevel.LIGHT).thinkingBudget)
        assertEquals(1024, ThinkingProfile.forLevel(ThinkingLevel.STANDARD).thinkingBudget)
        assertEquals(4096, ThinkingProfile.forLevel(ThinkingLevel.DEEP).thinkingBudget)
        assertEquals(16384, ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM).thinkingBudget)
        assertEquals(32768, ThinkingProfile.forLevel(ThinkingLevel.ULTRACODE).thinkingBudget)
        assertEquals(65536, ThinkingProfile.forLevel(ThinkingLevel.APEXCODE).thinkingBudget)
        // AUTO 不直接映射（由选档结果决定），占位画像必须显式 null
        assertEquals(null, ThinkingProfile.forLevel(ThinkingLevel.AUTO).thinkingBudget)
        assertEquals(null, ThinkingProfile.forLevel(ThinkingLevel.AUTO).reasoningEffortName)
        assertEquals(null, ThinkingProfile.forLevel(ThinkingLevel.NONE).reasoningEffortName)
        assertEquals("MAX", ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM).reasoningEffortName)
    }

    @Test
    fun `post tool verification only on deep and above`() {
        assertFalse(ThinkingProfile.forLevel(ThinkingLevel.NONE).postToolVerification)
        assertFalse(ThinkingProfile.forLevel(ThinkingLevel.LIGHT).postToolVerification)
        assertFalse(ThinkingProfile.forLevel(ThinkingLevel.STANDARD).postToolVerification)
        assertTrue(ThinkingProfile.forLevel(ThinkingLevel.DEEP).postToolVerification)
        assertTrue(ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM).postToolVerification)
        assertTrue(ThinkingProfile.forLevel(ThinkingLevel.ULTRACODE).postToolVerification)
        assertTrue(ThinkingProfile.forLevel(ThinkingLevel.APEXCODE).postToolVerification)
    }

    @Test
    fun `final self check only on maximum and above`() {
        listOf(ThinkingLevel.NONE, ThinkingLevel.LIGHT, ThinkingLevel.STANDARD, ThinkingLevel.DEEP)
            .forEach { assertFalse("finalSelfCheck should be false for $it", ThinkingProfile.forLevel(it).finalSelfCheck) }
        assertTrue(ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM).finalSelfCheck)
        assertTrue(ThinkingProfile.forLevel(ThinkingLevel.ULTRACODE).finalSelfCheck)
        assertTrue(ThinkingProfile.forLevel(ThinkingLevel.APEXCODE).finalSelfCheck)
    }

    @Test
    fun `none compresses earlier others keep default threshold`() {
        assertEquals(1.2f, ThinkingProfile.forLevel(ThinkingLevel.NONE).compressionAggressiveness, 0.0001f)
        listOf(ThinkingLevel.LIGHT, ThinkingLevel.STANDARD, ThinkingLevel.DEEP, ThinkingLevel.MAXIMUM, ThinkingLevel.ULTRACODE)
            .forEach {
                assertEquals("compression default expected for $it", 1.0f, ThinkingProfile.forLevel(it).compressionAggressiveness, 0.0001f)
            }
        // APEXCODE < 1 = 更晚压缩（保留更多上下文），见下方专项语义测试
        assertEquals(0.9f, ThinkingProfile.forLevel(ThinkingLevel.APEXCODE).compressionAggressiveness, 0.0001f)
    }

    @Test
    fun `tool output budget grows with depth`() {
        val budgets = listOf(
            ThinkingLevel.NONE to 6000,
            ThinkingLevel.LIGHT to 7000,
            ThinkingLevel.STANDARD to 8000,
            ThinkingLevel.DEEP to 9000,
            ThinkingLevel.MAXIMUM to 10000,
            ThinkingLevel.ULTRACODE to 12000,
            ThinkingLevel.APEXCODE to 16000
        )
        budgets.forEach { (level, expected) ->
            assertEquals("budget mismatch for $level", expected, ThinkingProfile.forLevel(level).toolOutputBudget)
        }
        assertTrue(
            "budgets must be ascending with depth",
            budgets.map { ThinkingProfile.forLevel(it.first).toolOutputBudget }.zipWithNext().all { (a, b) -> a < b }
        )
    }

    @Test
    fun `prompt instructions migrated and enhanced`() {
        // NONE / AUTO：无推理指令（AUTO 由选档结果注入）
        assertEquals("", ThinkingProfile.forLevel(ThinkingLevel.NONE).promptInstruction)
        assertEquals("", ThinkingProfile.forLevel(ThinkingLevel.AUTO).promptInstruction)
        // LIGHT..MAXIMUM：非空且携带档位特有推理框架
        val light = ThinkingProfile.forLevel(ThinkingLevel.LIGHT).promptInstruction
        assertTrue(light.contains("1-2 sentences"))
        val standard = ThinkingProfile.forLevel(ThinkingLevel.STANDARD).promptInstruction
        assertTrue(standard.contains("Chain-of-Thought"))
        val deep = ThinkingProfile.forLevel(ThinkingLevel.DEEP).promptInstruction
        assertTrue(deep.contains("multi-path"))
        assertTrue("DEEP 指令需呼应工具自检策略", deep.contains("failed or high-risk tool call"))
        val maximum = ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM).promptInstruction
        assertTrue(maximum.contains("Tree-of-Thoughts"))
        assertTrue("MAXIMUM 指令需呼应终检清单策略", maximum.contains("self-check"))
        // v1.2 新档：编码闭环 / 架构穷举，各自携带档位特有推理框架
        val ultracode = ThinkingProfile.forLevel(ThinkingLevel.ULTRACODE).promptInstruction
        assertTrue(ultracode.contains("invariants"))
        assertTrue(ultracode.contains("smallest change"))
        assertTrue("ULTRACODE 指令需呼应工具自检策略", ultracode.contains("failed or high-risk tool call"))
        assertTrue("ULTRACODE 指令需呼应终检清单策略", ultracode.contains("self-check"))
        val apexcode = ThinkingProfile.forLevel(ThinkingLevel.APEXCODE).promptInstruction
        assertTrue(apexcode.contains("Architecture-first"))
        assertTrue(apexcode.contains("Adversarial self-review"))
        assertTrue(apexcode.contains("verification matrix"))
        assertTrue("APEXCODE 指令需呼应巅峰五问清单", apexcode.contains("invariants / regression"))
    }

    @Test
    fun `self check checklist and post tool templates are usable`() {
        val checklist = ThinkingProfile.SELF_CHECK_CHECKLIST
        assertTrue(checklist.contains("Goal"))
        assertTrue(checklist.contains("Side effects"))
        assertTrue(checklist.contains("Omissions"))
        // 工具自检模板含 %s 工具占位
        assertTrue(ThinkingProfile.POST_TOOL_FAILURE_CHECK.contains("%s"))
        assertTrue(ThinkingProfile.POST_TOOL_HIGH_RISK_CHECK.contains("%s"))
        assertTrue(ThinkingProfile.POST_TOOL_FAILURE_CHECK.contains("FAILED"))
        assertTrue(ThinkingProfile.POST_TOOL_HIGH_RISK_CHECK.contains("HIGH-RISK"))
    }

    // ── v1.2 新档画像全字段断言 ──────────────────────────────

    @Test
    fun `ultracode profile is a coding closed-loop not just a bigger maximum`() {
        val p = ThinkingProfile.forLevel(ThinkingLevel.ULTRACODE)
        assertEquals(ThinkingLevel.ULTRACODE, p.level)
        assertEquals(32768, p.thinkingBudget)
        assertEquals("MAX", p.reasoningEffortName)
        assertEquals(2.0f, p.maxIterationsScale, 0.0001f)
        assertTrue(p.postToolVerification)
        assertTrue(p.finalSelfCheck)
        assertEquals(1.0f, p.compressionAggressiveness, 0.0001f)
        assertEquals(12000, p.toolOutputBudget)
        // 编码闭环框架：read-map-plan / invariants / candidate edits / risk-rank /
        // smallest change / immediate verify / regression scan
        val prompt = p.promptInstruction
        listOf(
            "Read-map-plan", "invariants", "candidate edits", "rank them by risk",
            "smallest change", "immediately verify", "regressions"
        ).forEach { keyword ->
            assertTrue("ULTRACODE 提示词应含「$keyword」", prompt.contains(keyword))
        }
        // 与 MAXIMUM 真实差异：不是换个名字的同一画像（!! = 两档 budget 契约非空）
        val maximum = ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM)
        assertTrue(p.thinkingBudget!! > maximum.thinkingBudget!!)
        assertTrue(p.maxIterationsScale > maximum.maxIterationsScale)
        assertTrue(p.toolOutputBudget > maximum.toolOutputBudget)
        assertTrue(prompt != maximum.promptInstruction)
    }

    @Test
    fun `apexcode profile is architecture-grade with apex checklist`() {
        val p = ThinkingProfile.forLevel(ThinkingLevel.APEXCODE)
        assertEquals(ThinkingLevel.APEXCODE, p.level)
        assertEquals(65536, p.thinkingBudget)
        assertEquals("MAX", p.reasoningEffortName)
        assertEquals(3.0f, p.maxIterationsScale, 0.0001f)
        assertTrue(p.postToolVerification)
        assertTrue(p.finalSelfCheck)
        assertEquals(0.9f, p.compressionAggressiveness, 0.0001f)
        assertEquals(16000, p.toolOutputBudget)
        // 架构级框架：architecture-first / blast-radius / ToT + verification
        // matrix / adversarial self-review / checkpoints / exhaustive
        // post-verification / evidence-based summary
        val prompt = p.promptInstruction
        listOf(
            "Architecture-first decomposition", "Blast-radius mapping",
            "Tree-of-Thoughts", "verification matrix", "Adversarial self-review",
            "checkpoints", "Post-verification is exhaustive", "evidence-based summary"
        ).forEach { keyword ->
            assertTrue("APEXCODE 提示词应含「$keyword」", prompt.contains(keyword))
        }
    }

    @Test
    fun `apexcode compression below one means later compression`() {
        // compressionAggressiveness = 0.9 < 1：有效阈值 = base / 0.9 ≈ 0.889 > base
        // —— 巅峰档更晚压缩、保留更多上下文（与 NONE 的 1.2 早压缩方向相反）。
        // resolve 语义归 ThinkingModeController，此处经其纯函数验证倍率含义。
        val controller = ThinkingModeController()
        val apex = ThinkingProfile.forLevel(ThinkingLevel.APEXCODE)
        val effective = controller.resolveCompressionThreshold(0.8f, apex)
        assertEquals(0.8f / 0.9f, effective, 0.0001f)
        assertTrue("APEXCODE 有效阈值应高于 base（更晚压缩）", effective > 0.8f)
        assertTrue("约 0.889", effective > 0.888f && effective < 0.89f)
        // 对照：NONE 的 1.2 → 0.667（更早压缩），两方向都有语义
        val none = ThinkingProfile.forLevel(ThinkingLevel.NONE)
        assertTrue(controller.resolveCompressionThreshold(0.8f, none) < 0.8f)
    }

    @Test
    fun `apex self check checklist has five adversarial questions`() {
        val checklist = ThinkingProfile.APEX_SELF_CHECK_CHECKLIST
        // 五问：goal / side-effects / omissions / invariants / regression
        listOf("Goal", "Side effects", "Omissions", "Invariants", "Regression").forEach { q ->
            assertTrue("APEX 清单应含「$q」", checklist.contains(q))
        }
        assertTrue("应明示对抗性", checklist.contains("adversarial"))
        assertTrue("应保留失败先修口径", checklist.contains("fix the gap with tools first"))
        // 与三问清单真实不同（非同文重复）
        assertTrue(checklist != ThinkingProfile.SELF_CHECK_CHECKLIST)
        assertTrue(checklist.length > ThinkingProfile.SELF_CHECK_CHECKLIST.length)
    }

    @Test
    fun `forLevel covers all eight enum entries exhaustively`() {
        // entries 全量 ≠ 遗漏新档：枚举每项都必须能拿到画像（编译期穷举 when
        // 已保证，这里再运行期死磕一次，防静态表与枚举脱钩）
        assertEquals(8, ThinkingLevel.entries.size)
        ThinkingLevel.entries.forEach { level ->
            val p = ThinkingProfile.forLevel(level)
            assertEquals(level, p.level)
            // 幂等：同档多次调用等值
            assertEquals(p, ThinkingProfile.forLevel(level))
        }
    }
}
