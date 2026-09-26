package com.apex.agent.core.engine.thinking

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 八档思考画像（[ThinkingProfile.forLevel]）表驱动全字段矩阵测试（v1.2）。
 *
 * 与 [ThinkingProfileTest] 的分工：那边按主题分组做语义断言，本文件用
 * **本地期望表**把 8 档 × 每档 10+ 字段逐格锁死——任何一档任何一字段的
 * 漂移都能精确定位到「哪一档的哪一项」：
 *
 *  - 矩阵主体：promptInstruction（NONE/AUTO 空，其余非空）、thinkingBudget
 *    （0/256/1024/4096/16384/32768/65536/null）、reasoningEffortName
 *    （null/LOW/MEDIUM/HIGH/MAX/MAX/MAX/null）、maxIterationsScale
 *    （0.8/0.9/1.0/1.2/1.5/2.0/3.0/1.0）、postToolVerification（DEEP 起开）、
 *    finalSelfCheck（MAXIMUM 起开）、compressionAggressiveness
 *    （NONE 1.2、APEXCODE 0.9、其余 1.0）、toolOutputBudget 阶梯；
 *  - 单调性：budget / 迭代倍率 / 输出预算沿深度档严格递增；
 *  - 清单契约：三问自检（Goal/Side effects/Omissions）与 APEX 五问
 *    （追加 Invariants/Regression）、工具失败与高风险模板的格式化可用性；
 *  - [ThinkingModeController] 的三个 resolve 纯函数在新两档上的数值语义
 *    （40×2.0=80、0.8÷0.9≈0.889 晚压缩、max(8000,12000)=12000 等）。
 *
 * 纯 JVM（JUnit4）——forLevel 是纯静态表，无协程无状态。
 */
class ThinkingProfileTableTest {

    /**
     * 期望表的一行：八档画像的完整期望快照（字段与 ThinkingProfile 一一对应）。
     *
     * @property promptBlank promptInstruction 是否应为空白（NONE/AUTO 不注入指令）。
     */
    private data class Expected(
        val level: ThinkingLevel,
        val promptBlank: Boolean,
        val thinkingBudget: Int?,
        val reasoningEffortName: String?,
        val maxIterationsScale: Float,
        val postToolVerification: Boolean,
        val finalSelfCheck: Boolean,
        val compressionAggressiveness: Float,
        val toolOutputBudget: Int
    )

    /** 八档全字段期望矩阵（值逐项核对 ThinkingProfile 源码静态表）。 */
    private val table = listOf(
        Expected(
            level = ThinkingLevel.NONE,
            promptBlank = true,
            thinkingBudget = 0,
            reasoningEffortName = null,
            maxIterationsScale = 0.8f,
            postToolVerification = false,
            finalSelfCheck = false,
            compressionAggressiveness = 1.2f,
            toolOutputBudget = 6000
        ),
        Expected(
            level = ThinkingLevel.LIGHT,
            promptBlank = false,
            thinkingBudget = 256,
            reasoningEffortName = "LOW",
            maxIterationsScale = 0.9f,
            postToolVerification = false,
            finalSelfCheck = false,
            compressionAggressiveness = 1.0f,
            toolOutputBudget = 7000
        ),
        Expected(
            level = ThinkingLevel.STANDARD,
            promptBlank = false,
            thinkingBudget = 1024,
            reasoningEffortName = "MEDIUM",
            maxIterationsScale = 1.0f,
            postToolVerification = false,
            finalSelfCheck = false,
            compressionAggressiveness = 1.0f,
            toolOutputBudget = 8000
        ),
        Expected(
            level = ThinkingLevel.DEEP,
            promptBlank = false,
            thinkingBudget = 4096,
            reasoningEffortName = "HIGH",
            maxIterationsScale = 1.2f,
            postToolVerification = true,
            finalSelfCheck = false,
            compressionAggressiveness = 1.0f,
            toolOutputBudget = 9000
        ),
        Expected(
            level = ThinkingLevel.MAXIMUM,
            promptBlank = false,
            thinkingBudget = 16384,
            reasoningEffortName = "MAX",
            maxIterationsScale = 1.5f,
            postToolVerification = true,
            finalSelfCheck = true,
            compressionAggressiveness = 1.0f,
            toolOutputBudget = 10000
        ),
        Expected(
            level = ThinkingLevel.ULTRACODE,
            promptBlank = false,
            thinkingBudget = 32768,
            reasoningEffortName = "MAX",
            maxIterationsScale = 2.0f,
            postToolVerification = true,
            finalSelfCheck = true,
            compressionAggressiveness = 1.0f,
            toolOutputBudget = 12000
        ),
        Expected(
            level = ThinkingLevel.APEXCODE,
            promptBlank = false,
            thinkingBudget = 65536,
            reasoningEffortName = "MAX",
            maxIterationsScale = 3.0f,
            postToolVerification = true,
            finalSelfCheck = true,
            compressionAggressiveness = 0.9f,
            toolOutputBudget = 16000
        ),
        Expected(
            level = ThinkingLevel.AUTO,
            promptBlank = true,
            thinkingBudget = null,
            reasoningEffortName = null,
            maxIterationsScale = 1.0f,
            postToolVerification = false,
            finalSelfCheck = false,
            compressionAggressiveness = 1.0f,
            toolOutputBudget = 8000
        )
    )

    /** 深度档链（NONE 到 APEXCODE 的 7 个真实深度层，AUTO 元档不参与单调性）。 */
    private val depthChain = listOf(
        ThinkingLevel.LIGHT, ThinkingLevel.STANDARD, ThinkingLevel.DEEP,
        ThinkingLevel.MAXIMUM, ThinkingLevel.ULTRACODE, ThinkingLevel.APEXCODE
    )

    // ═════════════════ 全字段矩阵 ═════════════════

    /**
     * 表驱动主体：8 档逐档断言 10+ 字段——promptInstruction 空白性、
     * budget、effort、迭代倍率、工具自检、终检、压缩倍率、输出预算、
     * level 自洽、双语 UI 描述非空、决策理由默认为空。
     */
    @Test
    fun `表驱动全字段矩阵_八档画像与期望表逐格一致`() {
        // 期望表必须覆盖全部档位（枚举加档时这里先红，逼着补表）
        assertEquals(
            "期望表必须穷举 ThinkingLevel 全部档位",
            ThinkingLevel.entries.toSet(),
            table.map { it.level }.toSet()
        )
        table.forEach { expected ->
            val profile = ThinkingProfile.forLevel(expected.level)
            val name = expected.level.name
            // 档位自洽：画像的 level 就是查询的档位
            assertEquals(expected.level, profile.level)
            // promptInstruction：NONE/AUTO 空（不注入指令），其余非空
            if (expected.promptBlank) {
                assertTrue("$name 的 promptInstruction 应为空白", profile.promptInstruction.isBlank())
            } else {
                assertTrue("$name 的 promptInstruction 不应为空白", profile.promptInstruction.isNotBlank())
            }
            // 模型参数两件套
            assertEquals("$name budget 漂移", expected.thinkingBudget, profile.thinkingBudget)
            assertEquals("$name effort 漂移", expected.reasoningEffortName, profile.reasoningEffortName)
            // 执行策略五件套
            assertEquals(
                "$name 迭代倍率漂移",
                expected.maxIterationsScale,
                profile.maxIterationsScale,
                0.0001f
            )
            assertEquals("$name 工具自检开关漂移", expected.postToolVerification, profile.postToolVerification)
            assertEquals("$name 终检开关漂移", expected.finalSelfCheck, profile.finalSelfCheck)
            assertEquals(
                "$name 压缩倍率漂移",
                expected.compressionAggressiveness,
                profile.compressionAggressiveness,
                0.0001f
            )
            assertEquals("$name 输出预算漂移", expected.toolOutputBudget, profile.toolOutputBudget)
            // 双语 UI 描述非空
            assertTrue("$name 中文描述不应为空", profile.uiDescriptionZh.isNotBlank())
            assertTrue("$name 英文描述不应为空", profile.uiDescriptionEn.isNotBlank())
            // 静态表不携带决策理由（仅 AUTO 解析路径由控制器填充）
            assertNull("$name 的 decisionReason 默认应为空", profile.decisionReason)
        }
    }

    /** 8 档的双语 UI 描述各自互不相同（档位辨识度）。 */
    @Test
    fun `双语UI描述八档各自互不相同`() {
        assertEquals(8, table.map { ThinkingProfile.forLevel(it.level).uiDescriptionZh }.toSet().size)
        assertEquals(8, table.map { ThinkingProfile.forLevel(it.level).uiDescriptionEn }.toSet().size)
    }

    /**
     * 深度档 budget 沿阶梯严格递增，且 ULTRACODE = 2×MAXIMUM、
     * APEXCODE = 4×MAXIMUM = 2×ULTRACODE（编码双档的翻倍设计）。
     */
    @Test
    fun `深度档预算严格递增_编码双档翻倍阶梯`() {
        val budgets = depthChain.map { ThinkingProfile.forLevel(it).thinkingBudget!! }
        assertTrue(
            "budget 沿深度档必须严格递增: $budgets",
            budgets.zipWithNext().all { (a, b) -> a < b }
        )
        assertEquals(256, budgets[0])
        assertEquals(1024, budgets[1])
        assertEquals(4096, budgets[2])
        assertEquals(16384, budgets[3])
        assertEquals(32768, budgets[4])
        assertEquals(65536, budgets[5])
        assertEquals(2 * 16384, 32768) // ULTRACODE = 2 × MAXIMUM
        assertEquals(4 * 16384, 65536) // APEXCODE = 4 × MAXIMUM
        assertEquals(2 * 32768, 65536) // APEXCODE = 2 × ULTRACODE
        // NONE 显式 0（不是 null——关闭推理是明确的参数），AUTO 显式 null（不直接映射）
        assertEquals(0, ThinkingProfile.forLevel(ThinkingLevel.NONE).thinkingBudget)
        assertNull(ThinkingProfile.forLevel(ThinkingLevel.AUTO).thinkingBudget)
    }

    /** 迭代倍率与工具输出预算沿深度档严格递增（快档止损、深档放量）。 */
    @Test
    fun `迭代倍率与输出预算沿深度档单调递增`() {
        val scales = depthChain.map { ThinkingProfile.forLevel(it).maxIterationsScale }
        assertTrue(
            "迭代倍率沿深度档必须严格递增: $scales",
            scales.zipWithNext().all { (a, b) -> a < b }
        )
        val budgets = depthChain.map { ThinkingProfile.forLevel(it).toolOutputBudget }
        assertTrue(
            "工具输出预算沿深度档必须严格递增: $budgets",
            budgets.zipWithNext().all { (a, b) -> a < b }
        )
        assertEquals(listOf(7000, 8000, 9000, 10000, 12000, 16000), budgets)
    }

    /**
     * effort 名映射全表：null/LOW/MEDIUM/HIGH/MAX/MAX/MAX/null——
     * 三个深档同为 MAX（Provider 侧天花板），差异靠提示词与执行策略。
     */
    @Test
    fun `effort映射表_null到MAX五档`() {
        val efforts = table.map { ThinkingProfile.forLevel(it.level).reasoningEffortName }
        assertEquals(
            listOf(null, "LOW", "MEDIUM", "HIGH", "MAX", "MAX", "MAX", null),
            efforts
        )
        // 全表只出现这五个值
        assertEquals(setOf(null, "LOW", "MEDIUM", "HIGH", "MAX"), efforts.toSet())
    }

    /**
     * 压缩倍率全表：NONE 1.2（更早压缩省 token）、APEXCODE 0.9（更晚压缩
     * 保留上下文）、其余（含 AUTO 占位）1.0——0.9 是全梯度唯一小于 1 的档。
     */
    @Test
    fun `压缩倍率全表_仅两档偏离一`() {
        table.forEach { expected ->
            val profile = ThinkingProfile.forLevel(expected.level)
            when (expected.level) {
                ThinkingLevel.NONE -> assertEquals(1.2f, profile.compressionAggressiveness, 0.0001f)
                ThinkingLevel.APEXCODE -> assertEquals(0.9f, profile.compressionAggressiveness, 0.0001f)
                else -> assertEquals(1.0f, profile.compressionAggressiveness, 0.0001f)
            }
        }
    }

    // ═════════════════ promptInstruction 内容锚点 ═════════════════

    /**
     * 提示词内容锚点：六档非空提示词各有可辨识的档位特有指令；
     * 深四档（DEEP 起）都带与工具自检策略呼应的句子。
     */
    @Test
    fun `提示词内容锚点_六档各有档位特有指令`() {
        assertTrue(
            ThinkingProfile.forLevel(ThinkingLevel.LIGHT).promptInstruction.contains("1-2 sentences")
        )
        assertTrue(
            ThinkingProfile.forLevel(ThinkingLevel.STANDARD).promptInstruction.contains("Chain-of-Thought")
        )
        assertTrue(
            ThinkingProfile.forLevel(ThinkingLevel.DEEP).promptInstruction.contains("multi-path")
        )
        assertTrue(
            ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM).promptInstruction.contains("Tree-of-Thoughts")
        )
        val ultra = ThinkingProfile.forLevel(ThinkingLevel.ULTRACODE).promptInstruction
        assertTrue(ultra.contains("invariants"))
        assertTrue(ultra.contains("smallest change"))
        val apex = ThinkingProfile.forLevel(ThinkingLevel.APEXCODE).promptInstruction
        assertTrue(apex.contains("Architecture-first"))
        assertTrue(apex.contains("Adversarial self-review"))
        assertTrue(apex.contains("verification matrix"))
        // 深四档与执行策略互相咬合：工具失败/高风险后重审
        listOf(ThinkingLevel.DEEP, ThinkingLevel.MAXIMUM, ThinkingLevel.ULTRACODE, ThinkingLevel.APEXCODE)
            .forEach { level ->
                assertTrue(
                    "$level 提示词应含工具自检呼应句",
                    ThinkingProfile.forLevel(level).promptInstruction
                        .contains("After any failed or high-risk tool call")
                )
            }
        // MAXIMUM 与 ULTRACODE 提示词都点名三问预检；APEXCODE 点名五问预检
        assertTrue(
            ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM).promptInstruction.contains("self-check")
        )
        assertTrue(
            ThinkingProfile.forLevel(ThinkingLevel.ULTRACODE).promptInstruction.contains("self-check")
        )
        assertTrue(apex.contains("apex-grade adversarial self-check"))
    }

    // ═════════════════ 自检清单契约 ═════════════════

    /** 三问清单：目标 / 副作用 / 遗漏，编号 1-3，不含不变量与回归。 */
    @Test
    fun `三问自检清单_目标副作用遗漏`() {
        val checklist = ThinkingProfile.SELF_CHECK_CHECKLIST
        assertTrue(checklist.contains("1. Goal:"))
        assertTrue(checklist.contains("2. Side effects:"))
        assertTrue(checklist.contains("3. Omissions:"))
        assertTrue(checklist.contains("self-check"))
        assertFalse("三问清单不应包含不变量问", checklist.contains("Invariants"))
        assertFalse("三问清单不应包含回归问", checklist.contains("Regression"))
    }

    /**
     * APEX 五问清单：在三问之上追加不变量与回归（对应其提示词的
     * invariants 与 verification matrix 闭环），且与三问清单是不同文本。
     */
    @Test
    fun `APEX五问清单_在三问之上追加不变量与回归`() {
        val apex = ThinkingProfile.APEX_SELF_CHECK_CHECKLIST
        // 三问基底
        assertTrue(apex.contains("1. Goal:"))
        assertTrue(apex.contains("2. Side effects:"))
        assertTrue(apex.contains("3. Omissions:"))
        // 两问追加
        assertTrue(apex.contains("4. Invariants:"))
        assertTrue(apex.contains("5. Regression:"))
        // 与三问清单是不同文本，且都以「先修复再交付」收尾
        assertFalse(apex == ThinkingProfile.SELF_CHECK_CHECKLIST)
        assertTrue(apex.contains("If any check fails"))
        assertTrue(ThinkingProfile.SELF_CHECK_CHECKLIST.contains("If any check fails"))
    }

    /** 工具失败与高风险两个注入模板：原始模板含百分之 s，格式化后含工具名且无残留占位。 */
    @Test
    fun `工具失败与高风险模板的格式化可用性`() {
        assertTrue(ThinkingProfile.POST_TOOL_FAILURE_CHECK.contains("%s"))
        val failure = ThinkingProfile.POST_TOOL_FAILURE_CHECK.format("code_edit")
        assertTrue(failure.contains("code_edit"))
        assertTrue(failure.contains("FAILED"))
        assertFalse("格式化后不应残留占位符", failure.contains("%s"))

        assertTrue(ThinkingProfile.POST_TOOL_HIGH_RISK_CHECK.contains("%s"))
        val highRisk = ThinkingProfile.POST_TOOL_HIGH_RISK_CHECK.format("shell_execute")
        assertTrue(highRisk.contains("shell_execute"))
        assertTrue(highRisk.contains("HIGH-RISK"))
        assertFalse(highRisk.contains("%s"))
    }

    // ═════════════════ forLevel 幂等与自洽 ═════════════════

    /** forLevel 幂等：同档两次调用返回等值画像（纯静态表、无副作用）。 */
    @Test
    fun `forLevel幂等_两次调用等值`() {
        table.forEach { expected ->
            assertEquals(
                "forLevel(${expected.level}) 必须幂等",
                ThinkingProfile.forLevel(expected.level),
                ThinkingProfile.forLevel(expected.level)
            )
        }
    }

    /**
     * AUTO 占位画像不带执行策略：空指令、null 参数、全部开关关闭——
     * 真实执行策略由选档结果档位的画像承载。
     */
    @Test
    fun `AUTO占位画像不带执行策略`() {
        val auto = ThinkingProfile.forLevel(ThinkingLevel.AUTO)
        assertTrue(auto.promptInstruction.isBlank())
        assertNull(auto.thinkingBudget)
        assertNull(auto.reasoningEffortName)
        assertFalse(auto.postToolVerification)
        assertFalse(auto.finalSelfCheck)
        assertEquals(1.0f, auto.compressionAggressiveness, 0.0001f)
        assertEquals(8000, auto.toolOutputBudget)
    }

    // ═════════════════ 控制器 resolve 三件套（新两档数值语义）═════════════════

    /**
     * resolveMaxIterations：base × 迭代倍率四舍五入且下限 1——
     * ULTRACODE 40×2.0=80、APEXCODE 40×3.0=120、NONE 10×0.8=8、
     * 零基数钳到 1、LIGHT 3×0.9=2.7 进位 3、MAXIMUM 25×1.5=37.5 进位 38。
     */
    @Test
    fun `控制器迭代上限换算_新档倍率与下限钳制`() {
        val controller = ThinkingModeController()
        assertEquals(
            80,
            controller.resolveMaxIterations(40, ThinkingProfile.forLevel(ThinkingLevel.ULTRACODE))
        )
        assertEquals(
            120,
            controller.resolveMaxIterations(40, ThinkingProfile.forLevel(ThinkingLevel.APEXCODE))
        )
        assertEquals(
            75,
            controller.resolveMaxIterations(25, ThinkingProfile.forLevel(ThinkingLevel.APEXCODE))
        )
        assertEquals(
            8,
            controller.resolveMaxIterations(10, ThinkingProfile.forLevel(ThinkingLevel.NONE))
        )
        assertEquals(
            3,
            controller.resolveMaxIterations(3, ThinkingProfile.forLevel(ThinkingLevel.LIGHT))
        )
        assertEquals(
            38,
            controller.resolveMaxIterations(25, ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM))
        )
        // 下限钳制：任何档位 × 0 都不低于 1（防止迭代上限归零死循环）
        assertEquals(
            1,
            controller.resolveMaxIterations(0, ThinkingProfile.forLevel(ThinkingLevel.APEXCODE))
        )
    }

    /**
     * resolveCompressionThreshold：base ÷ 压缩倍率并钳制在 0.1..0.98——
     * APEXCODE 0.8÷0.9≈0.889 大于 0.8（更晚压缩保留上下文）、
     * NONE 0.8÷1.2≈0.667 小于 0.8（更早压缩省 token）、
     * 上界 1.0÷0.9 钳到 0.98、下界 0.05÷1.2 钳到 0.1。
     */
    @Test
    fun `控制器压缩阈值换算_晚压缩语义与上下钳制`() {
        val controller = ThinkingModeController()
        val apexThreshold = controller.resolveCompressionThreshold(
            0.8f, ThinkingProfile.forLevel(ThinkingLevel.APEXCODE)
        )
        assertEquals(0.8f / 0.9f, apexThreshold, 0.0001f)
        assertTrue("APEXCODE 阈值应大于基准（更晚压缩）", apexThreshold > 0.8f)

        val noneThreshold = controller.resolveCompressionThreshold(
            0.8f, ThinkingProfile.forLevel(ThinkingLevel.NONE)
        )
        assertEquals(0.8f / 1.2f, noneThreshold, 0.0001f)
        assertTrue("NONE 阈值应小于基准（更早压缩）", noneThreshold < 0.8f)

        // 钳制上界：1.0 ÷ 0.9 ≈ 1.11 → 0.98
        assertEquals(
            0.98f,
            controller.resolveCompressionThreshold(
                1.0f, ThinkingProfile.forLevel(ThinkingLevel.APEXCODE)
            ),
            0.0001f
        )
        // 钳制下界：0.05 ÷ 1.2 ≈ 0.042 → 0.1
        assertEquals(
            0.1f,
            controller.resolveCompressionThreshold(
                0.05f, ThinkingProfile.forLevel(ThinkingLevel.NONE)
            ),
            0.0001f
        )
        // 无画像回退：新控制器尚未解析任何画像时 base 原样返回
        assertEquals(0.7f, controller.resolveCompressionThreshold(0.7f), 0.0001f)
    }

    /**
     * resolveToolOutputBudget：max(用户默认, 档位预算)——深档按深度上调、
     * 用户设置不被档位缩小；无画像时 default 原样返回。
     */
    @Test
    fun `控制器工具输出预算换算_深档上调且用户设置不被缩小`() {
        val controller = ThinkingModeController()
        assertEquals(
            12000,
            controller.resolveToolOutputBudget(8000, ThinkingProfile.forLevel(ThinkingLevel.ULTRACODE))
        )
        assertEquals(
            16000,
            controller.resolveToolOutputBudget(8000, ThinkingProfile.forLevel(ThinkingLevel.APEXCODE))
        )
        // 用户默认更大时保持用户值（不被档位缩小）
        assertEquals(
            20000,
            controller.resolveToolOutputBudget(20000, ThinkingProfile.forLevel(ThinkingLevel.APEXCODE))
        )
        // 浅档也按档位预算兜底抬高
        assertEquals(
            6000,
            controller.resolveToolOutputBudget(5000, ThinkingProfile.forLevel(ThinkingLevel.NONE))
        )
        assertEquals(
            8000,
            controller.resolveToolOutputBudget(8000, ThinkingProfile.forLevel(ThinkingLevel.STANDARD))
        )
        // 无画像回退：default 原样
        assertEquals(1234, controller.resolveToolOutputBudget(1234))
    }

    /**
     * 终检清单按档切换（控制器视角）：APEXCODE 独占五问清单，
     * MAXIMUM 与 ULTRACODE 用三问清单，LIGHT 无终检。
     */
    @Test
    fun `控制器终检清单按档切换_APEX独占五问`() {
        val controller = ThinkingModeController()
        controller.onIterationStart(AgentConfig(thinkingLevel = ThinkingLevel.APEXCODE), 1)
        assertEquals(ThinkingProfile.APEX_SELF_CHECK_CHECKLIST, controller.finalSelfCheckPrompt())

        controller.onIterationStart(AgentConfig(thinkingLevel = ThinkingLevel.ULTRACODE), 1)
        assertEquals(ThinkingProfile.SELF_CHECK_CHECKLIST, controller.finalSelfCheckPrompt())

        controller.onIterationStart(AgentConfig(thinkingLevel = ThinkingLevel.MAXIMUM), 1)
        assertEquals(ThinkingProfile.SELF_CHECK_CHECKLIST, controller.finalSelfCheckPrompt())

        controller.onIterationStart(AgentConfig(thinkingLevel = ThinkingLevel.LIGHT), 1)
        assertNull(controller.finalSelfCheckPrompt())

        // 返回的画像档位与配置一致（level 字段自洽的另一面）
        val profile = controller.onIterationStart(
            AgentConfig(thinkingLevel = ThinkingLevel.ULTRACODE), 1
        )
        assertEquals(ThinkingLevel.ULTRACODE, profile.level)
        assertNotNull(profile.promptInstruction)
    }
}
