package com.apex.agent.core.engine.terminal

import com.apex.agent.core.engine.terminal.TerminalProactivityAdvisor.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #170 TerminalProactivityAdvisor 单测 —— 连发一次性 shell 触发 SESSION_FLOW /
 * terminal.create 清零 / 工具链关键词触发 ENSURE_UBUNTU 一次性 / 失败连击提示 /
 * 无关工具不触发。
 */
class TerminalProactivityAdvisorTest {

    // ═══════════════════════ SESSION_FLOW ═══════════════════════

    @Test
    fun `three consecutive one-shot shells trigger SESSION_FLOW`() {
        val advisor = TerminalProactivityAdvisor()
        // 未达阈值前不触发
        repeat(2) { advisor.onToolCallCompleted("terminal.exec", succeeded = true) }
        assertNull(advisor.onIterationStart("hello"))
        // 第 3 次后触发
        advisor.onToolCallCompleted("shell_execute", succeeded = true)
        val advice = advisor.onIterationStart("hello")
        assertNotNull(advice)
        assertEquals(Kind.SESSION_FLOW, advice!!.kind)
        assertTrue("建议应提到 terminal.create：${advice.systemNote}", advice.systemNote.contains("terminal.create"))
    }

    @Test
    fun `session flow tool resets the one-shot streak`() {
        val advisor = TerminalProactivityAdvisor()
        repeat(2) { advisor.onToolCallCompleted("shell_execute", succeeded = true) }
        // 模型切换会话流 → streak 清零
        advisor.onToolCallCompleted("terminal.create", succeeded = true)
        advisor.onToolCallCompleted("terminal.exec", succeeded = true)
        assertNull("create 后仅 1 次一次性命令不应触发", advisor.onIterationStart("hello"))
        // 重新攒满阈值才再次触发
        repeat(TerminalProactivityAdvisor.SESSION_FLOW_THRESHOLD - 1) {
            advisor.onToolCallCompleted("terminal.exec", succeeded = true)
        }
        assertEquals(Kind.SESSION_FLOW, advisor.onIterationStart("hello")!!.kind)
    }

    @Test
    fun `session flow advice does not repeat every iteration when streak stalls`() {
        val advisor = TerminalProactivityAdvisor()
        repeat(3) { advisor.onToolCallCompleted("terminal.exec", succeeded = true) }
        assertNotNull(advisor.onIterationStart("x"))
        // streak 停在 3（模型停止调用工具）→ 不重复唠叨
        assertNull(advisor.onIterationStart("x"))
        assertNull(advisor.onIterationStart("x"))
        // 攒到 6 才再次提醒
        repeat(3) { advisor.onToolCallCompleted("shell_execute", succeeded = true) }
        assertEquals(Kind.SESSION_FLOW, advisor.onIterationStart("x")!!.kind)
    }

    // ═══════════════════════ ENSURE_UBUNTU ═══════════════════════

    @Test
    fun `toolchain keyword in user prompt triggers ENSURE_UBUNTU once per session`() {
        val advisor = TerminalProactivityAdvisor()
        val advice = advisor.onIterationStart("帮我在项目里 npm install 并构建")
        assertNotNull(advice)
        assertEquals(Kind.ENSURE_UBUNTU, advice!!.kind)
        assertTrue(advice.systemNote.contains("terminal.ubuntu.ensure"))
        // 每会话最多一次
        assertNull(advisor.onIterationStart("继续 cargo build"))
    }

    @Test
    fun `toolchain keyword in recent one-shot args triggers ENSURE_UBUNTU`() {
        val advisor = TerminalProactivityAdvisor()
        advisor.onToolCallCompleted("shell_execute", succeeded = true, args = """{"command":"git clone https://example.com/repo"}""")
        val advice = advisor.onIterationStart("继续")
        assertNotNull("近期命令参数含 git clone 应触发", advice)
        assertEquals(Kind.ENSURE_UBUNTU, advice!!.kind)
    }

    @Test
    fun `ubuntu probe seen suppresses ENSURE_UBUNTU`() {
        val advisor = TerminalProactivityAdvisor()
        advisor.onToolCallCompleted("terminal.backends", succeeded = true)
        assertNull("模型已探测过后端，不再给预备建议", advisor.onIterationStart("pip install requests"))
        advisor.onToolCallCompleted("terminal.ubuntu.status", succeeded = true)
        assertNull(advisor.onIterationStart("apt install curl"))
    }

    @Test
    fun `non toolchain prompt does not trigger ENSURE_UBUNTU`() {
        val advisor = TerminalProactivityAdvisor()
        assertNull(advisor.onIterationStart("讲个笑话"))
        assertNull(advisor.onIterationStart("帮我读一下 /sdcard/notes.txt"))
    }

    // ═══════════════════════ 失败连击 ═══════════════════════

    @Test
    fun `two consecutive one-shot failures trigger failure recovery advice`() {
        val advisor = TerminalProactivityAdvisor()
        advisor.onToolCallCompleted("terminal.exec", succeeded = false)
        assertNull("单次失败不触发", advisor.onIterationStart("x"))
        advisor.onToolCallCompleted("terminal.exec", succeeded = false)
        val advice = advisor.onIterationStart("x")
        assertNotNull(advice)
        assertEquals(Kind.FAILURE_RECOVERY, advice!!.kind)
        assertTrue("应提示检查输出：${advice.systemNote}", advice.systemNote.contains("inspect"))
        assertTrue("应提示问用户：${advice.systemNote}", advice.systemNote.contains("ask the user"))
    }

    @Test
    fun `successful one-shot resets the failure streak`() {
        val advisor = TerminalProactivityAdvisor()
        advisor.onToolCallCompleted("terminal.exec", succeeded = false)
        advisor.onToolCallCompleted("terminal.exec", succeeded = true)
        advisor.onToolCallCompleted("shell_execute", succeeded = false)
        // streak=3 触发 SESSION_FLOW（一次性计数正常工作），但失败不连续 →
        // 不是 FAILURE_RECOVERY，且无失败附注。
        val advice = advisor.onIterationStart("x")
        assertEquals(Kind.SESSION_FLOW, advice!!.kind)
        assertTrue(!advice.systemNote.contains("also failed"))
    }

    @Test
    fun `failure note attached when failures continue alongside session flow`() {
        val advisor = TerminalProactivityAdvisor()
        repeat(3) { advisor.onToolCallCompleted("shell_execute", succeeded = false) }
        val advice = advisor.onIterationStart("x")
        assertEquals(Kind.SESSION_FLOW, advice!!.kind)
        assertTrue("连败附注应拼进建议：${advice.systemNote}", advice.systemNote.contains("also failed"))
    }

    // ═══════════════════════ 无关工具 ═══════════════════════

    @Test
    fun `unrelated tools never trigger any advice`() {
        val advisor = TerminalProactivityAdvisor()
        repeat(10) { advisor.onToolCallCompleted("read_file", succeeded = true) }
        advisor.onToolCallCompleted("web_search", succeeded = false)
        assertNull(advisor.onIterationStart("随便聊聊"))
    }
}
