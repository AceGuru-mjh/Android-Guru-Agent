package com.apex.agent.core.code.thinking

import com.apex.agent.core.code.thinking.CodeThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodeThinkingPrompts] 编码特化思考指令测试（coding 专属七档）。
 *
 * 断言三件事：
 * 1. NONE 档不注入（空串——与通用画像的「不思考」语义对齐）；
 * 2. 每档指令携带自己的档位标识（标题行可区分），且内容非空；
 * 3. 幂等（同档多次调用等值）+ ULTRACODE/APEXCODE 的编码特有方法论关键词。
 */
class CodeThinkingPromptsTest {

    @Test
    fun `none level produces empty directive`() {
        assertEquals("", CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.NONE))
    }

    @Test
    fun `every non-none level produces non-empty directive with own marker`() {
        CodeThinkingLevel.entries
            .filter { it != CodeThinkingLevel.NONE }
            .forEach { level ->
                val directive = CodeThinkingPrompts.thinkingDirective(level)
                assertTrue("档位 $level 指令不应为空", directive.isNotBlank())
                assertTrue(
                    "档位 $level 指令应含段落标题",
                    directive.startsWith("### 编码思考")
                )
            }
    }

    @Test
    fun `each level marker is distinct`() {
        val markers = CodeThinkingLevel.entries
            .filter { it != CodeThinkingLevel.NONE }
            .associateWith { level ->
                CodeThinkingPrompts.thinkingDirective(level).lineSequence().first()
            }
        val distinct = markers.values.distinct()
        assertEquals("各档标题行应互不相同", markers.size, distinct.size)
    }

    @Test
    fun `ultracode carries coding closed-loop methodology`() {
        val directive = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.ULTRACODE)
        assertTrue(directive.contains("依赖地图"))
        assertTrue(directive.contains("候选改法"))
        assertTrue(directive.contains("风险排序"))
        assertTrue(directive.contains("最小修改"))
        assertTrue(directive.contains("即时验证"))
        assertTrue(directive.contains("回归扫描"))
    }

    @Test
    fun `apexcode carries architecture-grade methodology`() {
        val directive = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.APEXCODE)
        assertTrue(directive.contains("架构定位"))
        assertTrue(directive.contains("影响半径"))
        assertTrue(directive.contains("对比矩阵"))
        assertTrue(directive.contains("对抗性自审"))
        assertTrue(directive.contains("全量验证矩阵"))
        assertTrue(directive.contains("证据链汇报"))
    }

    @Test
    fun `auto directive explains adaptivity and carries deep baseline`() {
        val directive = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.AUTO)
        assertTrue(directive.contains("自适应"))
        // AUTO 兜底 = DEEP 纪律（去标题后的缩进体）
        assertTrue(directive.contains("改动集清单"))
        assertTrue(directive.contains("code_task"))
    }

    @Test
    fun `directives are idempotent per level`() {
        CodeThinkingLevel.entries.forEach { level ->
            assertEquals(
                "档位 $level 指令应幂等",
                CodeThinkingPrompts.thinkingDirective(level),
                CodeThinkingPrompts.thinkingDirective(level)
            )
        }
    }

    @Test
    fun `auto fallback level is deep`() {
        assertEquals(CodeThinkingLevel.DEEP, CodeThinkingPrompts.AUTO_FALLBACK_DIRECTIVE_LEVEL)
    }

    @Test
    fun `no nested comment hazard sequences in directives`() {
        // KDoc/字符串里出现斜杠+星号序列会在 Kotlin 块注释里开启嵌套层级
        // （历史地雷）；指令文本本身也该避免（可能被拼进注释性上下文）。
        CodeThinkingLevel.entries.forEach { level ->
            val directive = CodeThinkingPrompts.thinkingDirective(level)
            assertFalse(
                "档位 $level 指令不应含斜杠+星号序列",
                directive.contains("/*") || directive.contains("*/")
            )
        }
    }

    // ═══ 每档方法论关键词矩阵（内容质量锁定）═══

    @Test
    fun `light directive covers glance-edit discipline`() {
        val d = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.LIGHT)
        assertTrue(d.contains("code_read"))
        assertTrue(d.contains("diff"))
        assertTrue(d.contains("STANDARD")) // 退路指引
    }

    @Test
    fun `standard directive covers full edit loop`() {
        val d = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.STANDARD)
        assertTrue(d.contains("code_read"))
        assertTrue(d.contains("code_edit"))
        assertTrue(d.contains("诊断"))
        assertTrue(d.contains("最小 diff"))
    }

    @Test
    fun `deep directive covers changeset mindset and subagent delegation`() {
        val d = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.DEEP)
        assertTrue(d.contains("改动集清单"))
        assertTrue(d.contains("验证"))
        assertTrue(d.contains("code_task"))
        assertTrue(d.contains("一致性"))
    }

    @Test
    fun `maximum directive covers invariants and ripple effects`() {
        val d = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.MAXIMUM)
        assertTrue(d.contains("不变量"))
        assertTrue(d.contains("调用方"))
        assertTrue(d.contains("全链路"))
        assertTrue(d.contains("重试"))
    }

    @Test
    fun `ultracode directive carries six-step closed loop`() {
        val d = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.ULTRACODE)
        // 六步闭环逐一在场
        assertTrue(d.contains("1. **依赖地图**"))
        assertTrue(d.contains("2. **候选改法**"))
        assertTrue(d.contains("3. **风险排序**"))
        assertTrue(d.contains("4. **最小修改**"))
        assertTrue(d.contains("5. **即时验证**"))
        assertTrue(d.contains("6. **回归扫描**"))
        // 委派纪律与方向记录
        assertTrue(d.contains("并行"))
        assertTrue(d.contains("放弃当前路线"))
    }

    @Test
    fun `apexcode directive carries six-phase review protocol`() {
        val d = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.APEXCODE)
        assertTrue(d.contains("1. **架构定位**"))
        assertTrue(d.contains("2. **影响半径测绘**"))
        assertTrue(d.contains("3. **多方案对比矩阵**"))
        assertTrue(d.contains("4. **对抗性自审**"))
        assertTrue(d.contains("5. **全量验证矩阵**"))
        assertTrue(d.contains("6. **证据链汇报**"))
        // 证据与检查点纪律
        assertTrue(d.contains("path:line"))
        assertTrue(d.contains("code_todo"))
    }

    @Test
    fun `directives grow more prescriptive with depth`() {
        // 深度档指令应比浅档更长（方法论更具体）：阶梯单调性粗粒度锁定
        val lightLen = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.LIGHT).length
        val standardLen = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.STANDARD).length
        val deepLen = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.DEEP).length
        val maximumLen = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.MAXIMUM).length
        val ultracodeLen = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.ULTRACODE).length
        val apexLen = CodeThinkingPrompts.thinkingDirective(CodeThinkingLevel.APEXCODE).length
        assertTrue(lightLen < standardLen)
        assertTrue(standardLen < deepLen)
        assertTrue(deepLen < maximumLen)
        assertTrue(maximumLen < ultracodeLen)
        assertTrue(ultracodeLen < apexLen)
    }
}
