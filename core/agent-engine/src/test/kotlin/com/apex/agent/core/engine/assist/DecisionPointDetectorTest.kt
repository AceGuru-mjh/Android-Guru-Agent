package com.apex.agent.core.engine.assist

import com.apex.agent.core.engine.assist.DecisionPointDetector.DecisionOption
import com.apex.agent.core.engine.assist.DecisionPointDetector.DecisionPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #168 决策点检测器单测 —— 覆盖三类识别规则（编号方案 / 疑问选择 /
 * 显式请求降级）与全部排除规则（代码块 / 单方案 / 空文本）。
 */
class DecisionPointDetectorTest {

    // ═══ 规则 1：编号方案模式 ═══

    @Test
    fun `chinese scheme A and B pair is detected with two options`() {
        val text = """
            这个任务有两种做法：
            方案A：直接用 shell 脚本批量重命名，速度快但不可逆。
            方案B：逐个文件确认后重命名，慢但安全。
            你倾向哪种？
        """.trimIndent()
        val point = DecisionPointDetector.detect(text)
        assertNotNull("方案A/B 对比应检出决策点", point)
        assertEquals(2, point!!.options.size)
        assertTrue(point.options[0].label.contains("shell 脚本"))
        assertTrue(point.options[1].label.contains("逐个文件"))
        assertTrue(point.rationale.startsWith("numbered-schemes"))
    }

    @Test
    fun `chinese numeral schemes yi and er are detected`() {
        val text = "方案一：先备份再操作。方案二：直接操作，出了问题再说。你选哪个？"
        val point = DecisionPointDetector.detect(text)
        assertNotNull(point)
        assertEquals(2, point!!.options.size)
        assertEquals("一", point.options[0].key)
        assertEquals("二", point.options[1].key)
    }

    @Test
    fun `english option A and B pair is detected`() {
        val text = """
            There are two ways forward.
            Option A: use the batch rename script — fast but irreversible.
            Option B: confirm each file one by one — slower but safer.
            Which would you like?
        """.trimIndent()
        val point = DecisionPointDetector.detect(text)
        assertNotNull(point)
        assertEquals(2, point!!.options.size)
        assertEquals("A", point.options[0].key)
        assertEquals("B", point.options[1].key)
    }

    @Test
    fun `lettered standalone lines A and B are detected`() {
        val text = "Pick one:\nA. Install via apt\nB. Build from source"
        val point = DecisionPointDetector.detect(text)
        assertNotNull(point)
        assertEquals(2, point!!.options.size)
        assertTrue(point.options[0].label.contains("apt"))
        assertTrue(point.options[1].label.contains("source"))
    }

    @Test
    fun `circled numerals are detected and normalized to digits`() {
        val text = "① 先读文件确认结构\n② 直接正则替换全部\n你选哪个？"
        val point = DecisionPointDetector.detect(text)
        assertNotNull(point)
        assertEquals(2, point!!.options.size)
        assertEquals("1", point.options[0].key)
        assertEquals("2", point.options[1].key)
    }

    @Test
    fun `keycap emoji numbers are detected`() {
        val text = "两个路线：\n1️⃣ 走 GitHub API 拉取\n2️⃣ 直接 git clone 镜像\n选一个？"
        val point = DecisionPointDetector.detect(text)
        assertNotNull("keycap 1️⃣/2️⃣ 编号应检出", point)
        assertEquals(2, point!!.options.size)
        assertTrue(point.options[0].label.contains("GitHub API"))
        assertTrue(point.options[1].label.contains("git clone"))
    }

    @Test
    fun `single scheme statement without contrast is not a decision`() {
        val text = "方案A：我打算先读取配置文件，然后按配置执行重命名。"
        assertNull("单个方案陈述（无第二方案）不算决策点", DecisionPointDetector.detect(text))
    }

    @Test
    fun `repeated mention of same scheme key counts once and does not trigger`() {
        val text = "方案A 是首选。方案A 也最省事。就按方案A 来吧。"
        assertNull("同一编号重复提及只计一次 → 不足 2 个方案", DecisionPointDetector.detect(text))
    }

    @Test
    fun `long detail is truncated to 80 chars`() {
        val longDetail = "x".repeat(300)
        val text = "方案A：短标签。$longDetail\n方案B：另一个。"
        val point = DecisionPointDetector.detect(text)
        assertNotNull(point)
        val detailA = point!!.options[0].detail
        assertTrue(
            "detail 应截断到 ≤80 字符（实际 ${detailA.length}）",
            detailA.length <= 80 && detailA.isNotEmpty()
        )
    }

    // ═══ 规则 2：疑问选择模式 ═══

    @Test
    fun `zh either-or question extracts both candidates`() {
        val text = "请问你是想用微信发送这条消息还是用短信发送这条消息？"
        val point = DecisionPointDetector.detect(text)
        assertNotNull(point)
        assertEquals(2, point!!.options.size)
        assertTrue(point.options[0].label.contains("微信"))
        assertTrue(point.options[1].label.contains("短信"))
        assertTrue(point.question.endsWith("？"))
    }

    @Test
    fun `en should-i-or question extracts both candidates`() {
        val text = "Should I delete the whole directory or only the temp files?"
        val point = DecisionPointDetector.detect(text)
        assertNotNull(point)
        assertEquals(2, point!!.options.size)
        assertTrue(point.options[0].label.contains("directory"))
        assertTrue(point.options[1].label.contains("temp files"))
    }

    @Test
    fun `non-question either-or statement is not a decision`() {
        // 陈述句（无问号收尾）+ 单方案 → 不触发疑问选择
        val text = "我认为还是先做数据库迁移比较好。"
        assertNull(DecisionPointDetector.detect(text))
    }

    // ═══ 规则 3：显式请求降级 ═══

    @Test
    fun `explicit zh request without options degrades to continue-stop pair`() {
        val text = "已准备好执行，需要你确认后我再继续。"
        val point = DecisionPointDetector.detect(text)
        assertNotNull("显式请求应降级触发", point)
        assertEquals(2, point!!.options.size)
        assertEquals("continue", point.options[0].key)
        assertEquals("stop", point.options[1].key)
        assertTrue(point.rationale.startsWith("explicit-request-fallback"))
        assertTrue(point.question.contains("确认"))
    }

    @Test
    fun `explicit en request degrades with original sentence as question`() {
        val text = "The refactor touches 12 files. Which do you prefer before I start?"
        val point = DecisionPointDetector.detect(text)
        assertNotNull(point)
        assertEquals(2, point!!.options.size)
        assertTrue(point.question.contains("12 files"))
    }

    // ═══ 排除规则 ═══

    @Test
    fun `fenced code block content is excluded from detection`() {
        val text = """
            我看了代码：
            ```
            val option1 = parse(a)
            val option2 = parse(b)
            // Option A: use option1
            // Option B: use option2
            ```
            代码没有问题，可以直接跑。
        """.trimIndent()
        assertNull("代码块内的 option A/B 不应触发", DecisionPointDetector.detect(text))
    }

    @Test
    fun `inline code is excluded from detection`() {
        val text = "检查了 `option a or option b` 的分支逻辑，一切正常，无需改动。"
        assertNull(DecisionPointDetector.detect(text))
    }

    @Test
    fun `blank text returns null`() {
        assertNull(DecisionPointDetector.detect(""))
        assertNull(DecisionPointDetector.detect("   \n  "))
    }

    // ═══ question 合成 ═══

    @Test
    fun `question sentence from trailing question is preferred over synthetic`() {
        val text = "方案A：快但糙。方案B：慢但稳。你更倾向哪个方案？"
        val point = DecisionPointDetector.detect(text)
        assertNotNull(point)
        assertTrue(
            "question 应取尾部问句而非合成句",
            point!!.question.contains("倾向")
        )
    }

    @Test
    fun `option list without question gets synthesized question`() {
        val text = "方案A：路线一描述。方案B：路线二描述。"
        val point = DecisionPointDetector.detect(text)
        assertNotNull(point)
        assertTrue(point!!.question.isNotBlank())
    }

    // ═══ 结构化数据完整性 ═══

    @Test
    fun `max options cap keeps at most six`() {
        val text = buildString {
            append("很多方案：\n")
            ('A'..'H').forEach { append("方案$it：方案 $it 的描述。\n") }
        }
        val point = DecisionPointDetector.detect(text)
        assertNotNull(point)
        assertTrue("超过 6 个方案应截断（实际 ${point!!.options.size}）", point.options.size <= 6)
    }

    @Test
    fun `decision point data class exposes rationale for observability`() {
        val point = DecisionPoint(
            question = "q",
            options = listOf(DecisionOption("A", "a"), DecisionOption("B", "b")),
            rationale = "test"
        )
        assertEquals(2, point.options.size)
        assertEquals("test", point.rationale)
    }
}
