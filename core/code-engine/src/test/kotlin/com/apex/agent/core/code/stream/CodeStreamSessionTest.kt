package com.apex.agent.core.code.stream

import com.apex.agent.core.engine.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodeStreamSession] 事件状态机测试。
 *
 * 覆盖：工具胶囊生命周期（含幂等重放）/ 思考与正文流式 / 终端脉冲与
 * 尾窗 / 验证轮次聚合 / 错误·停止·完成收尾 / tick 攒批语义 /
 * 受影响文件追踪。
 */
class CodeStreamSessionTest {

    private fun editStart(callId: String, path: String) = AgentEvent.ToolCallStart(
        callId, "code_edit", """{"path":"$path","diff":"x"}"""
    )

    private fun bashStart(callId: String, command: String) = AgentEvent.ToolCallStart(
        callId, "terminal.exec", """{"command":"$command"}"""
    )

    @Test
    fun `tool capsule goes through full lifecycle`() {
        val s = CodeStreamSession()
        s.beginRun("修一下 bug")
        s.onEvent(editStart("c1", "src/App.kt"))
        s.onEvent(
            AgentEvent.ToolCallComplete(
                callId = "c1", toolName = "code_edit", arguments = """{"path":"src/App.kt"}""",
                output = "(no textual change)", fullOutput = "--- a/src/App.kt\n+++ b/src/App.kt\n@@ -1,2 +1,2 @@\n-a\n+b\n(1 added, 1 removed)",
                success = true, durationMs = 120
            )
        )
        val snap = s.snapshot()
        val capsule = snap.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals(ToolKind.EDIT_FILE, capsule.call.kind)
        assertEquals("src/App.kt", capsule.call.target)
        assertEquals("编辑类成功 → APPLIED", ToolCallStatus.APPLIED, capsule.call.status)
        assertEquals(1, capsule.call.hunksTotal)
        assertEquals(1, capsule.call.hunksApplied)
        assertEquals("+1 −1 · 1 hunk", capsule.call.summary)
        assertEquals(listOf("src/App.kt"), snap.affectedFiles)
    }

    @Test
    fun `bash capsule tracks terminal pulses and exit code`() {
        val s = CodeStreamSession()
        s.beginRun("跑构建")
        s.onEvent(bashStart("b1", "./gradlew assemble"))
        repeat(8) { s.onEvent(AgentEvent.ToolOutputChunk("b1", "compile ok\n")) }
        // 行数条件（8 行）命中脉冲
        val snap = s.tick(0)
        assertNotNull("脉冲后应产出快照", snap)
        assertEquals("b1", snap!!.activeTerminalCallId)
        assertTrue(snap.terminalContent.contains("compile ok"))
        s.onEvent(
            AgentEvent.ToolCallComplete(
                callId = "b1", toolName = "terminal.exec", arguments = "",
                output = "build finished\nexit code 0", fullOutput = "",
                success = true, durationMs = 9_000
            )
        )
        val final = s.snapshot()
        val capsule = final.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals(ToolCallStatus.SUCCESS, capsule.call.status)
        assertEquals("exit 0", capsule.call.summary)
        assertEquals(0, capsule.call.exitCode)
    }

    @Test
    fun `idempotent replay of same call events does not duplicate`() {
        val s = CodeStreamSession()
        s.beginRun("task")
        val start = editStart("c1", "A.kt")
        s.onEvent(start)
        s.onEvent(start) // 重放
        val complete = AgentEvent.ToolCallComplete(
            "c1", "code_edit", """{"path":"A.kt"}""", "done", "--- a/A.kt\n+++ b/A.kt\n@@ -1,1 +1,2 @@\n x\n+y\n(1 added, 0 removed)",
            true, 50
        )
        s.onEvent(complete)
        s.onEvent(complete) // 重放
        val snap = s.snapshot()
        assertEquals("幂等：重放不产生重复胶囊", 1, snap.toolCallCount)
        assertEquals(1, snap.entries.count { it is StreamEntry.ToolCapsuleEntry })
    }

    @Test
    fun `thinking and assistant stream incrementally then finalize`() {
        val s = CodeStreamSession()
        s.beginRun("问个问题")
        s.onEvent(AgentEvent.ThinkingChunk("想一"))
        s.onEvent(AgentEvent.ThinkingChunk("想二"))
        s.onEvent(AgentEvent.ThinkingComplete("想一想想二"))
        s.onEvent(AgentEvent.ResponseChunk("结论"))
        s.onEvent(AgentEvent.ResponseChunk("是……"))
        s.onEvent(AgentEvent.ResponseComplete("结论是……"))
        val snap = s.snapshot()
        val thinking = snap.entries.filterIsInstance<StreamEntry.ThinkingEntry>().single()
        assertEquals("想一想想二", thinking.text)
        assertTrue(!thinking.isStreaming)
        val assistant = snap.entries.filterIsInstance<StreamEntry.AssistantEntry>().single()
        assertEquals("结论是……", assistant.text)
        assertTrue(!assistant.isStreaming)
    }

    @Test
    fun `verify cycle collapses edit then lint into round card`() {
        val s = CodeStreamSession()
        s.beginRun("改完自检")
        s.onEvent(editStart("e1", "A.kt"))
        s.onEvent(AgentEvent.ToolCallComplete("e1", "code_edit", "{}", "ok", "", true, 30))
        s.onEvent(AgentEvent.ToolCallStart("v1", "lint", """{"path":"A.kt"}"""))
        s.onEvent(AgentEvent.ToolCallComplete("v1", "lint", "{}", "no issues", "", true, 40))
        // 收尾触发轮次收口
        s.onEvent(AgentEvent.Complete("done", 2, 2, 500))
        val snap = s.snapshot()
        val cycle = snap.entries.filterIsInstance<StreamEntry.VerifyCycleEntry>().single()
        assertEquals(1, cycle.cycle.round)
        assertEquals(listOf("e1"), cycle.cycle.editCallIds)
        assertEquals(listOf("v1"), cycle.cycle.verifyCallIds)
        assertEquals("验证通过 → passed=true", true, cycle.cycle.passed)
        // 轮内胶囊已折叠（时间轴不再有散装胶囊）
        assertEquals(0, snap.entries.count { it is StreamEntry.ToolCapsuleEntry })
    }

    @Test
    fun `failed verify marks cycle red`() {
        val s = CodeStreamSession()
        s.beginRun("改完自检")
        s.onEvent(editStart("e1", "A.kt"))
        s.onEvent(AgentEvent.ToolCallComplete("e1", "code_edit", "{}", "ok", "", true, 30))
        s.onEvent(AgentEvent.ToolCallStart("t1", "test", """{"path":"A.kt"}"""))
        s.onEvent(
            AgentEvent.ToolCallComplete(
                "t1", "test", "{}", "FAILED: ATest.b\nexit code 1", "", false, 60
            )
        )
        s.onEvent(editStart("e2", "A.kt")) // 第二轮 edit 开新轮次 → 上一轮收口
        val snap = s.snapshot()
        val cycles = snap.entries.filterIsInstance<StreamEntry.VerifyCycleEntry>()
        assertEquals(1, cycles.size)
        assertEquals(false, cycles[0].cycle.passed)
    }

    @Test
    fun `error event produces structured entry and lastError`() {
        val s = CodeStreamSession()
        s.beginRun("会出错的任务")
        s.onEvent(bashStart("b1", "make"))
        s.onEvent(AgentEvent.ToolOutputChunk("b1", "cc: error\n"))
        s.onEvent(AgentEvent.Error("工具执行失败：命令返回非零", recoverable = true))
        val snap = s.snapshot()
        val err = snap.entries.filterIsInstance<StreamEntry.ErrorEntry>().single()
        assertTrue(err.recoverable)
        assertNotNull(err.hint)
        assertEquals("b1", err.relatedCallId)
        assertEquals("工具执行失败：命令返回非零", snap.lastError?.message)
    }

    @Test
    fun `abort produces stop entry`() {
        val s = CodeStreamSession()
        s.beginRun("被中止")
        s.onEvent(AgentEvent.Aborted)
        val snap = s.snapshot()
        assertEquals(1, snap.entries.count { it is StreamEntry.StopEntry })
    }

    @Test
    fun `complete appends summary and file chips`() {
        val s = CodeStreamSession()
        s.beginRun("改两个文件")
        s.onEvent(editStart("e1", "A.kt"))
        s.onEvent(AgentEvent.ToolCallComplete("e1", "code_edit", "{}", "ok", "", true, 10))
        s.onEvent(editStart("e2", "B.kt"))
        s.onEvent(AgentEvent.ToolCallComplete("e2", "code_edit", "{}", "ok", "", true, 10))
        s.onEvent(AgentEvent.Complete("done", 3, 2, 1_000))
        val snap = s.snapshot()
        val chips = snap.entries.filterIsInstance<StreamEntry.FileChipsEntry>().single()
        assertEquals(listOf("A.kt", "B.kt"), chips.files)
        assertTrue(snap.entries.any { it is StreamEntry.StatusEntry })
    }

    @Test
    fun `tick returns null when nothing changed`() {
        val s = CodeStreamSession()
        s.beginRun("安静的任务")
        s.tick(1_000)
        assertNull("无变更无脉冲 → null（VM 跳过更新）", s.tick(1_001))
    }

    @Test
    fun `tick batches storm of chunks into coalesced snapshots`() {
        val s = CodeStreamSession()
        s.beginRun("流式风暴")
        s.onEvent(bashStart("b1", "cat big.log"))
        // 100 个小 chunk 事件
        repeat(100) { s.onEvent(AgentEvent.ToolOutputChunk("b1", "data\n")) }
        // 中间不 tick——全部攒住
        val snap = s.tick(50_000)
        assertNotNull(snap)
        assertEquals("终态只有一条胶囊", 1, snap!!.toolCallCount)
        assertTrue(snap.terminalContent.contains("data"))
    }

    @Test
    fun `inject system entry is visible in timeline`() {
        val s = CodeStreamSession()
        s.injectSystem("🧠 自适应预检：长文本+2 → 评分 2 → LIGHT")
        val snap = s.snapshot()
        val sys = snap.entries.filterIsInstance<StreamEntry.SystemEntry>().single()
        assertTrue(sys.text.contains("自适应预检"))
    }

    @Test
    fun `non bash log tail is windowed`() {
        val s = CodeStreamSession()
        s.beginRun("长输出")
        s.onEvent(AgentEvent.ToolCallStart("g1", "code_grep", """{"pattern":"foo"}"""))
        repeat(50) { s.onEvent(AgentEvent.ToolOutputChunk("g1", "match-$it\n")) }
        s.onEvent(AgentEvent.ToolCallComplete("g1", "code_grep", "{}", "done", "", true, 10))
        val snap = s.snapshot()
        val capsule = snap.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals(ToolKind.GREP_SEARCH, capsule.call.kind)
        assertTrue(
            "logTail 只保留尾窗（≤2000 字符）",
            capsule.call.logTail.length <= 2_000 && capsule.call.logTail.contains("match-49")
        )
    }

    @Test
    fun `failed capsule increments failure count`() {
        val s = CodeStreamSession()
        s.beginRun("失败案例")
        s.onEvent(bashStart("b1", "false"))
        s.onEvent(
            AgentEvent.ToolCallComplete("b1", "terminal.exec", "{}", "exit code 1", "", false, 5)
        )
        val snap = s.snapshot()
        assertEquals(1, snap.failedToolCallCount)
        val capsule = snap.entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().single()
        assertEquals(ToolCallStatus.FAILED, capsule.call.status)
        assertEquals(1, capsule.call.exitCode)
    }
}
