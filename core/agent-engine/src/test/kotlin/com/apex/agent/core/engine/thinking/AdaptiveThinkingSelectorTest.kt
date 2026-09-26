package com.apex.agent.core.engine.thinking

import com.apex.agent.core.engine.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #168 AUTO 档自适应选档器场景测试（≥10 个场景）+ v1.2 新阶梯用例。
 *
 * 覆盖：短平快 / 多步指示 / 代码任务 / 长文本 / 风险词保底 / 错误恢复升档 /
 * 规划期首轮保底 / 长任务深水区 / 组合 MAXIMUM / 理由字符串可解释性 /
 * 纯函数确定性；v1.2 新增：超高分(>11)→ULTRACODE、深水区连环失败→
 * ULTRACODE 保底、APEXCODE 永不被 select 返回（穷举各类输入）、错误恢复
 * 升档阶梯到 ULTRACODE 封顶、档位序数比较。
 */
class AdaptiveThinkingSelectorTest {

    private val selector = AdaptiveThinkingSelector()

    private fun select(
        text: String,
        toolCalls: Int = 0,
        recentErrors: Int = 0,
        iteration: Int = 0,
        planMode: Boolean = false
    ): AdaptiveDecision = selector.select(
        AdaptiveInput(
            userText = text,
            historyToolCalls = toolCalls,
            recentErrors = recentErrors,
            iterationIndex = iteration,
            planMode = planMode
        )
    )

    // ── 1. 短平快：短输入且无任何信号 → LIGHT ──────────────────

    @Test
    fun `short plain question picks LIGHT with quick label`() {
        val d = select("现在几点了？")
        assertEquals(ThinkingLevel.LIGHT, d.level)
        assertTrue("理由应标注短平快：${d.reason}", d.reason.contains("短平快"))
    }

    @Test
    fun `short english greeting picks LIGHT`() {
        val d = select("hello there")
        assertEquals(ThinkingLevel.LIGHT, d.level)
    }

    // ── 2. 代码任务：多步指示 + 命令词 → STANDARD+ ──────────────

    @Test
    fun `code task with steps picks STANDARD`() {
        val d = select("先 git pull 拉最新代码，然后跑 npm install 装依赖，接着 npm test 跑测试")
        // 多步指示(+2) + 代码/命令(+2) = 4 → STANDARD
        assertEquals(ThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("多步指示"))
        assertTrue(d.reason.contains("代码/命令"))
    }

    @Test
    fun `code block request with steps and length picks DEEP`() {
        val d = select(
            "帮我重构这段代码并解释每一步：" +
                "```kotlin\nfun main() { println(1) }\n```" +
                "。要求：先分析问题，然后给出方案，接着改代码，最后跑 gradle 验证，" +
                "并详细说明每一步的取舍与风险。" + "补充背景与约束说明。".repeat(40)
        )
        // 代码块(+2) + 多步(+2) + 长文本(+2) = 6 → DEEP
        assertEquals(ThinkingLevel.DEEP, d.level)
        assertTrue(d.reason.contains("```"))
    }

    // ── 3. 风险词：无论其他信号 → 至少 DEEP ────────────────────

    @Test
    fun `rm -rf command floors at DEEP`() {
        val d = select("rm -rf /data/test")
        // 风险词(+3) = 3 → STANDARD → 风险词保底 DEEP
        assertEquals(ThinkingLevel.DEEP, d.level)
        assertTrue(d.reason.contains("rm -rf"))
        assertTrue(d.reason.contains("风险词保底DEEP"))
    }

    @Test
    fun `chinese delete keyword floors at DEEP even when score is minimal`() {
        val d = select("删除这个文件")
        assertEquals(ThinkingLevel.DEEP, d.level)
        assertTrue(d.reason.contains("删除"))
    }

    @Test
    fun `uninstall keyword in english floors at DEEP`() {
        val d = select("please uninstall this app for me")
        assertEquals(ThinkingLevel.DEEP, d.level)
    }

    // ── 4. 组合高分 → MAXIMUM ───────────────────────────────────

    @Test
    fun `very long multi step risky code task reaches MAXIMUM`() {
        val d = select(
            "先备份再操作：需要卸载旧版本，然后 rm -rf 清理残留目录，" +
                "接着重装并配置权限，用 git 管理改动，每一步都要验证。" +
                "以下是详细需求与边界情况说明。".repeat(110)
        )
        // 超长(+3) + 多步(+2) + 代码(+2) + 风险(+3) = 10 > 8 → MAXIMUM
        assertEquals(ThinkingLevel.MAXIMUM, d.level)
        assertTrue(d.reason.contains("评分 10"))
    }

    @Test
    fun `long plain text above 1500 chars reaches STANDARD`() {
        val d = select("这是一段单纯的需求描述，不含任何指示词。".repeat(80)) // ~1600 字
        assertEquals(ThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("超长文本+3"))
    }

    // ── 5. 错误恢复：近期出错 → 升一档 ─────────────────────────

    @Test
    fun `recent errors bump one tier from LIGHT to STANDARD`() {
        val d = select("现在几点了？", recentErrors = 2)
        // 零信号 + 错误恢复 → LIGHT → 显式升一档 STANDARD
        assertEquals(ThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("错误恢复升一档"))
        assertTrue(d.reason.contains("错误恢复(2次)"))
    }

    @Test
    fun `recent errors cap at MAXIMUM`() {
        val d = select("rm -rf /data 并按步骤详细规划每个风险", recentErrors = 3)
        // 风险(+3)+多步(+2) = 5 → DEEP（风险保底）→ 错误升档 MAXIMUM（封顶）
        assertEquals(ThinkingLevel.MAXIMUM, d.level)
    }

    // ── 6. 规划期首轮保底 ──────────────────────────────────────

    @Test
    fun `plan mode first iteration floors at STANDARD`() {
        val d = select("现在几点了？", iteration = 0, planMode = true)
        assertEquals(ThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("规划期首轮保底STANDARD"))
    }

    @Test
    fun `plan mode floor does not apply after first iteration`() {
        val d = select("现在几点了？", iteration = 1, planMode = true)
        assertEquals(ThinkingLevel.LIGHT, d.level)
    }

    // ── 7. 长任务深水区 ────────────────────────────────────────

    @Test
    fun `deep water task with many tool calls floors at STANDARD`() {
        val d = select("继续", toolCalls = 16)
        assertEquals(ThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("长任务深水区"))
    }

    @Test
    fun `few tool calls do not trigger deep water floor`() {
        val d = select("继续", toolCalls = 5)
        assertEquals(ThinkingLevel.LIGHT, d.level)
    }

    // ── 8. 理由字符串可解释性 / 确定性 ──────────────────────────

    @Test
    fun `reason contains score and final level`() {
        val d = select("先 git pull 然后 npm install")
        assertTrue("理由应含评分：${d.reason}", d.reason.contains("评分"))
        assertTrue("理由应以档位结尾结构呈现：${d.reason}", d.reason.contains(d.level.name))
    }

    @Test
    fun `selector is deterministic for identical input`() {
        val input = AdaptiveInput("先分析然后执行 git 操作", 3, 1, 2, false)
        assertEquals(selector.select(input), selector.select(input))
    }

    // ── 9. v1.2 超高分：> 11 → ULTRACODE ──────────────────────

    @Test
    fun `score above eleven picks ULTRACODE directly`() {
        // 超长(+3) + 多步(+2) + 代码(+2) + 风险(+3) + 错误恢复(+2) = 12 > 11
        val d = select(
            "先备份再操作：需要卸载旧版本，然后 rm -rf 清理残留目录，" +
                "接着重装并配置权限，用 git 管理改动，每一步都要验证。" +
                "以下是详细需求与边界情况说明。".repeat(110),
            recentErrors = 1
        )
        assertEquals(ThinkingLevel.ULTRACODE, d.level)
        assertTrue("理由应含评分 12：${d.reason}", d.reason.contains("评分 12"))
    }

    @Test
    fun `score ten without errors still caps at MAXIMUM not ULTRACODE`() {
        // 同文本零错误：10 分 → MAXIMUM（9..11 区间），v1.2 阈值不冲击既有行为
        val d = select(
            "先备份再操作：需要卸载旧版本，然后 rm -rf 清理残留目录，" +
                "接着重装并配置权限，用 git 管理改动，每一步都要验证。" +
                "以下是详细需求与边界情况说明。".repeat(110)
        )
        assertEquals(ThinkingLevel.MAXIMUM, d.level)
        assertTrue(d.reason.contains("评分 10"))
    }

    // ── 10. v1.2 深水区升级：>30 调用 & ≥2 错误 → 至少 ULTRACODE ──

    @Test
    fun `deep water with repeated failures floors at ULTRACODE`() {
        // 零信号短文本 + 31 次调用 + 2 错误：评分 2 → LIGHT → 升档 STANDARD →
        // 深水区连环失败保底 ULTRACODE
        val d = select("继续", toolCalls = 31, recentErrors = 2)
        assertEquals(ThinkingLevel.ULTRACODE, d.level)
        assertTrue("理由应含保底说明：${d.reason}", d.reason.contains("保底ULTRACODE"))
        assertTrue(d.reason.contains("错误恢复升一档"))
    }

    @Test
    fun `deep water escalation requires strictly more than thirty calls`() {
        // 30 次调用 = 不触发（>30 严格门槛）：仍停在 STANDARD（深水区旧保底）
        val boundary = select("继续", toolCalls = 30, recentErrors = 2)
        assertEquals(ThinkingLevel.STANDARD, boundary.level)
    }

    @Test
    fun `deep water escalation requires at least two errors`() {
        // 31 次调用但只有 1 个错误：连环失败不成立 → 仍 STANDARD
        val single = select("继续", toolCalls = 31, recentErrors = 1)
        assertEquals(ThinkingLevel.STANDARD, single.level)
    }

    // ── 11. v1.2 成本防线：APEXCODE 永不被自动选择 ────────────

    @Test
    fun `apexcode is never selected across exhaustive input combinations`() {
        // 穷举代表性输入空间：文本信号 × 错误数 × 调用数 × 迭代期 × 模式，
        // 任何路径（阈值/保底/升档）都不允许返回 APEXCODE（成本失控防线）
        val texts = listOf(
            "现在几点了",
            "先分析然后执行 git 操作",
            "rm -rf /data 并按步骤详细规划每个风险",
            "这是很长的需求描述。".repeat(120)
        )
        val toolCallCounts = listOf(0, 5, 16, 31, 40)
        val errorCounts = listOf(0, 1, 2, 3)
        var combinations = 0
        for (text in texts) {
            for (calls in toolCallCounts) {
                for (errors in errorCounts) {
                    for (iteration in 0..1) {
                        for (planMode in listOf(false, true)) {
                            val d = select(
                                text,
                                toolCalls = calls,
                                recentErrors = errors,
                                iteration = iteration,
                                planMode = planMode
                            )
                            assertTrue(
                                "APEXCODE 不可被自动选择（text=${text.take(8)}、calls=$calls、errors=$errors）",
                                d.level != ThinkingLevel.APEXCODE
                            )
                            combinations++
                        }
                    }
                }
            }
        }
        assertTrue("穷举组合数异常：$combinations", combinations >= 300)
    }

    // ── 12. v1.2 错误恢复升档阶梯：…→MAXIMUM→ULTRACODE 封顶 ──

    @Test
    fun `error recovery escalates maximum base to ultracode`() {
        // 长文本(+2) + 多步(+2) + 代码(+2) + 风险(+3) + 错误(+2) = 11 →
        // 基础 MAXIMUM（9..11）→ 错误恢复升一档 → ULTRACODE
        val d = select(
            "先备份，然后 rm -rf 清理，再用 git 提交。" + "背景说明。".repeat(100),
            recentErrors = 1
        )
        assertEquals(ThinkingLevel.ULTRACODE, d.level)
        assertTrue(d.reason.contains("评分 11"))
        assertTrue(d.reason.contains("错误恢复升一档"))
    }

    @Test
    fun `error recovery caps at ultracode even from ultracode base`() {
        // 基础档已是 ULTRACODE（评分 12）再出错：封顶不再升，绝不进 APEXCODE
        val d = select(
            "先备份再操作：需要卸载旧版本，然后 rm -rf 清理残留目录，" +
                "接着重装并配置权限，用 git 管理改动，每一步都要验证。" +
                "以下是详细需求与边界情况说明。".repeat(110),
            recentErrors = 3
        )
        assertEquals(ThinkingLevel.ULTRACODE, d.level)
    }

    // ── 13. v1.2 档位序数：阶梯顶端可比 ──────────────────────

    @Test
    fun `ordinal ladder puts new tiers above maximum and auto last`() {
        assertTrue(ThinkingLevel.ULTRACODE > ThinkingLevel.MAXIMUM)
        assertTrue(ThinkingLevel.APEXCODE > ThinkingLevel.ULTRACODE)
        assertTrue(ThinkingLevel.AUTO > ThinkingLevel.APEXCODE)
    }
}
