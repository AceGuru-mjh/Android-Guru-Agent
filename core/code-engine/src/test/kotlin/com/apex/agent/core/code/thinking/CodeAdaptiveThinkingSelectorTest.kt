package com.apex.agent.core.code.thinking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodeAdaptiveThinkingSelector] AUTO 档自治选档测试。
 *
 * 断言四件事：
 * 1. 阈值表：评分 → 档位（LIGHT/STANDARD/DEEP/MAXIMUM/ULTRACODE）；
 * 2. coding 专有维度：编码意图 +1（@file 引用 / code 工具语义词 / 修改意图形态）；
 * 3. 保底与升档：风险词 DEEP / 错误史升一档 / 上轮深水区规则；
 * 4. 深水区升级观察器：30+ 调用 & 滑窗 ≥2 失败 → ULTRACODE；
 *    APEXCODE 任何路径不可达（成本防线）。
 */
class CodeAdaptiveThinkingSelectorTest {

    private val selector = CodeAdaptiveThinkingSelector()

    // ═══ 阈值表 ═══

    @Test
    fun `short simple goal picks light`() {
        val d = selector.select("改下 README 拼写", 0, 0)
        assertEquals(CodeThinkingLevel.LIGHT, d.level)
        assertTrue("短平快标注应出现在理由里", d.reason.contains("短平快"))
    }

    @Test
    fun `long text alone reaches standard`() {
        val goal = "x".repeat(600) // > 500 → +2 → 仍 < 3? 不：+2 = 2 分 < 3 → LIGHT
        val d = selector.select(goal, 0, 0)
        // 600 字符只有长度 +2 → 评分 2 → LIGHT（短平快不标：有信号因子）
        assertEquals(CodeThinkingLevel.LIGHT, d.level)
        assertTrue(d.reason.contains("长文本+2"))
    }

    @Test
    fun `long text plus multi step reaches standard`() {
        val goal = "y".repeat(600) + " 先做A然后做B"
        val d = selector.select(goal, 0, 0)
        // +2 长文本 +2 多步 = 4 → STANDARD
        assertEquals(CodeThinkingLevel.STANDARD, d.level)
    }

    @Test
    fun `code signal plus long text reaches deep`() {
        val goal = "z".repeat(600) + " 跑一下 git status 然后看看"
        val d = selector.select(goal, 0, 0)
        // +2 长文本 +2 多步(然后) +2 命令词(git) = 6 → DEEP
        assertEquals(CodeThinkingLevel.DEEP, d.level)
    }

    @Test
    fun `full stack signals reach maximum`() {
        val goal = "w".repeat(1600) + " 先A然后B，跑 gradle assemble，注意 rm -rf 风险"
        val d = selector.select(goal, 0, 0)
        // +3 超长 +2 多步 +2 命令 +3 风险 = 10 → MAXIMUM（>8 且 ≤11）
        assertEquals(CodeThinkingLevel.MAXIMUM, d.level)
        assertTrue(d.reason.contains("风险词保底DEEP").not() || d.level >= CodeThinkingLevel.DEEP)
    }

    @Test
    fun `score above eleven reaches ultracode`() {
        val goal = ("v".repeat(1600) + " 先A然后B step by step，git/npm/gradle 全跑一遍，")
        val d = selector.select(goal, 0, 2)
        // +3 超长 +2 多步 +2 命令 +3 风险(可选) +2 错误史 —— 用错误史顶过 11
        assertTrue("评分 > 11 应达 ULTRACODE（实际 ${d.level}）", d.level >= CodeThinkingLevel.ULTRACODE)
    }

    // ═══ coding 专有：编码意图 +1 ═══

    @Test
    fun `file ref counts as coding intent`() {
        // 无其他信号 + 编码意图 +1 = 1 分 → 仍 LIGHT，但理由含「编码意图」
        val d = selector.select("看下 @src/main/Foo.kt:42 这里", 0, 0)
        assertTrue(d.reason.contains("编码意图+1"))
    }

    @Test
    fun `code tool verb counts as coding intent`() {
        val d = selector.select("用 code_grep 找调用点", 0, 0)
        assertTrue(d.reason.contains("编码意图+1"))
    }

    @Test
    fun `edit intent pattern counts as coding intent`() {
        val d = selector.select("实现一个新的解析函数", 0, 0)
        assertTrue("「实现+函数」形态应命中编码意图", d.reason.contains("编码意图+1"))
    }

    @Test
    fun `plain question has no coding intent`() {
        val d = selector.select("今天天气怎么样", 0, 0)
        assertTrue("无编码信号不应加编码意图分", d.reason.contains("编码意图").not())
    }

    @Test
    fun `coding intent tips short goal from light to standard`() {
        // 长度 ~300（无长度分）+ 编码意图 +1 = 1 → LIGHT；
        // 再叠多步词 +2 = 3 → STANDARD（编码意图把跨档推上去）
        val goal = "先看 " + "@app/Foo.kt" + " 然后改"
        val d = selector.select(goal, 0, 0)
        // +2 多步(先/然后) +1 编码意图 = 3 → STANDARD
        assertEquals(CodeThinkingLevel.STANDARD, d.level)
    }

    // ═══ 保底与升档 ═══

    @Test
    fun `risk word floors at deep`() {
        val d = selector.select("帮我 rm -rf 清理", 0, 0)
        // +3 风险 = 3 → STANDARD 基础档，但风险词保底 DEEP
        assertEquals(CodeThinkingLevel.DEEP, d.level)
        assertTrue(d.reason.contains("风险词保底DEEP"))
    }

    @Test
    fun `error history escalates one tier`() {
        // 零信号 goal + 1 错误：评分 0+2=2 → LIGHT，显式升一档 → STANDARD
        val d = selector.select("改下 README 拼写", 0, 1)
        assertEquals(CodeThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("错误史升一档"))
    }

    @Test
    fun `error history escalation caps at ultracode`() {
        // 评分已顶 ULTRACODE + 错误史：不再升（APEXCODE 不可达）
        val goal = ("v".repeat(1600) + " 先A然后B，git/npm/gradle 全跑，rm -rf 检查")
        val d = selector.select(goal, 0, 3)
        assertEquals(CodeThinkingLevel.ULTRACODE, d.level)
    }

    @Test
    fun `last run deep water floors at standard`() {
        // 零信号 + 上轮 16 次调用：保底 STANDARD
        val d = selector.select("改下 README 拼写", 16, 0)
        assertEquals(CodeThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("保底STANDARD"))
    }

    @Test
    fun `last run deep water with errors floors at ultracode`() {
        // 上轮 31 调用 & 2 错误：开局即 ULTRACODE（上轮系统性跑偏）
        val d = selector.select("改下 README 拼写", 31, 2)
        assertEquals(CodeThinkingLevel.ULTRACODE, d.level)
        assertTrue(d.reason.contains("保底ULTRACODE"))
    }

    @Test
    fun `apexcode is never auto selected`() {
        // 组合各种强信号与错误史，任何路径都不应产出 APEXCODE
        val goals = listOf(
            "v".repeat(2000) + " 先然后step plan 全套 git npm gradle rm -rf",
            "重构整个架构 " + "@a.kt " + "code_edit code_write"
        )
        goals.forEach { goal ->
            listOf(0 to 0, 30 to 2, 50 to 5, 100 to 9).forEach { (calls, errors) ->
                val d = selector.select(goal, calls, errors)
                assertTrue(
                    "APEXCODE 不可自动选（goal=${goal.take(12)}…, calls=$calls, errors=$errors）",
                    d.level != CodeThinkingLevel.APEXCODE
                )
            }
        }
    }

    @Test
    fun `reason always carries score and level`() {
        val d = selector.select("随便看看", 0, 0)
        assertTrue(d.reason.contains("评分"))
        assertTrue(d.reason.contains("LIGHT"))
    }

    // ═══ 深水区升级观察器 ═══

    @Test
    fun `escalate triggers on deep water with consecutive failures`() {
        val d = selector.escalateOnDeepWater(
            runToolCalls = 31,
            recentWindowErrors = 2,
            current = CodeThinkingLevel.STANDARD
        ) ?: error("31 次调用 + 2 连败应触发深水区升级")
        assertEquals(CodeThinkingLevel.ULTRACODE, d.level)
        assertTrue(d.reason.contains("深水区升级"))
    }

    @Test
    fun `escalate does not trigger below call threshold`() {
        assertNull(
            selector.escalateOnDeepWater(30, 2, CodeThinkingLevel.STANDARD)
        )
    }

    @Test
    fun `escalate does not trigger with single failure`() {
        assertNull(
            selector.escalateOnDeepWater(50, 1, CodeThinkingLevel.STANDARD)
        )
    }

    @Test
    fun `escalate short circuits at or above ultracode`() {
        assertNull(
            "ULTRACODE 已是自动档顶点",
            selector.escalateOnDeepWater(50, 3, CodeThinkingLevel.ULTRACODE)
        )
        assertNull(
            "APEXCODE 不可被观察器触碰",
            selector.escalateOnDeepWater(50, 3, CodeThinkingLevel.APEXCODE)
        )
    }

    @Test
    fun `escalate never returns apexcode`() {
        // 观察器目标恒为 ULTRACODE
        val d = selector.escalateOnDeepWater(99, 3, CodeThinkingLevel.LIGHT)
            ?: error("99 次调用 + 3 连败应触发深水区升级")
        assertEquals(CodeThinkingLevel.ULTRACODE, d.level)
    }
}
