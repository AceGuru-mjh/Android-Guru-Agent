package com.apex.agent.core.code.stream

import com.apex.agent.core.engine.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [CodeStreamCheckpoint] 扁平持久化测试。
 *
 * 覆盖：往返一致性 / 未完成胶囊恢复为 PARTIAL / diff 原文保留 /
 * 坏档隔离 / 原子写 / 条目封顶。
 */
class CodeStreamCheckpointTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun feedSession(): CodeStreamSession {
        val s = CodeStreamSession()
        s.beginRun("持久化测试")
        s.injectSystem("🧠 自适应预检：无信号 → 评分 0 → LIGHT")
        s.onEvent(AgentEvent.ThinkingChunk("思考中"))
        s.onEvent(AgentEvent.ThinkingComplete("思考中"))
        s.onEvent(
            AgentEvent.ToolCallStart(
                "c1", "code_edit",
                """{"path":"src/App.kt","diff":"@@ -1 +1 @@"}"""
            )
        )
        s.onEvent(
            AgentEvent.ToolCallComplete(
                "c1", "code_edit", """{"path":"src/App.kt"}""",
                "ok",
                "--- a/src/App.kt\n+++ b/src/App.kt\n@@ -1,1 +1,2 @@\n ctx\n+new\n(1 added, 0 removed)",
                true, 42
            )
        )
        s.onEvent(AgentEvent.ToolCallStart("b1", "terminal.exec", """{"command":"make"}"""))
        // b1 未完成（模拟中断）
        return s
    }

    @Test
    fun `round trip preserves timeline and pending calls`() {
        val s = feedSession()
        val dir = tmp.newFolder("ckpt")
        val checkpoint = CodeStreamCheckpoint.toCheckpoint(s, "ws_1", committedFiles = listOf("src/App.kt"))
        CodeStreamCheckpoint.save(dir, checkpoint)

        val loaded = CodeStreamCheckpoint.load(dir, "ws_1")
        assertTrue(loaded != null)
        val entries = CodeStreamCheckpoint.toEntries(loaded!!)
        // 用户/系统/思考/胶囊 全部在轴
        assertTrue(entries.any { it is StreamEntry.UserEntry && it.text == "持久化测试" })
        assertTrue(entries.any { it is StreamEntry.SystemEntry && it.text.contains("自适应预检") })
        assertTrue(entries.any { it is StreamEntry.ThinkingEntry && it.text == "思考中" })
        val editCapsule = entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>()
            .single { it.call.id == "c1" }
        assertEquals(ToolCallStatus.APPLIED, editCapsule.call.status)
        // diff 原文保留（恢复后现场重解析）
        assertTrue(editCapsule.call.diffText!!.contains("@@ -1,1 +1,2 @@"))
        // 未完成 BASH 恢复为 PARTIAL
        val bashCapsule = entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>()
            .single { it.call.id == "b1" }
        assertEquals("中断胶囊恢复为 PARTIAL", ToolCallStatus.PARTIAL, bashCapsule.call.status)
        // 检查点元数据
        assertEquals(listOf("src/App.kt"), loaded.committedFiles)
        assertEquals(listOf("b1"), loaded.pendingToolCallIds)
    }

    @Test
    fun `verify cycle entry survives round trip with red green state`() {
        val s = CodeStreamSession()
        s.beginRun("轮次测试")
        s.onEvent(AgentEvent.ToolCallStart("e1", "code_edit", """{"path":"A.kt"}"""))
        s.onEvent(AgentEvent.ToolCallComplete("e1", "code_edit", "{}", "ok", "", true, 5))
        s.onEvent(AgentEvent.ToolCallStart("t1", "test", """{"path":"A.kt"}"""))
        s.onEvent(AgentEvent.ToolCallComplete("t1", "test", "{}", "pass", "", true, 5))
        s.onEvent(AgentEvent.Complete("done", 1, 2, 10))

        val dir = tmp.newFolder("ckpt2")
        CodeStreamCheckpoint.save(dir, CodeStreamCheckpoint.fromSession(s, "ws_2"))
        val loaded = CodeStreamCheckpoint.load(dir, "ws_2")!!
        val cycle = CodeStreamCheckpoint.toEntries(loaded)
            .filterIsInstance<StreamEntry.VerifyCycleEntry>().single()
        assertEquals(1, cycle.cycle.round)
        assertEquals(true, cycle.cycle.passed)
        assertEquals(listOf("e1"), cycle.cycle.editCallIds)
    }

    @Test
    fun `corrupt file is quarantined and load returns null`() {
        val dir = tmp.newFolder("bad")
        dir.resolve("stream_ws_3.json").writeText("{ not valid json !!")
        assertNull(CodeStreamCheckpoint.load(dir, "ws_3"))
        assertTrue("坏档被隔离为 .corrupt", dir.resolve("stream_ws_3.json.corrupt").exists())
        // 再次 load（corrupt 已挪走）不崩溃
        assertNull(CodeStreamCheckpoint.load(dir, "ws_3"))
    }

    @Test
    fun `workspace id mismatch is rejected`() {
        val dir = tmp.newFolder("mismatch")
        val s = CodeStreamSession()
        s.beginRun("x")
        CodeStreamCheckpoint.save(dir, CodeStreamCheckpoint.fromSession(s, "ws_a"))
        assertNull(CodeStreamCheckpoint.load(dir, "ws_b"))
    }

    @Test
    fun `entries are capped to recent window`() {
        val s = CodeStreamSession()
        s.beginRun("大量条目")
        repeat(500) {
            s.onEvent(AgentEvent.ToolCallStart("c$it", "code_read", """{"path":"f$it.kt"}"""))
            s.onEvent(
                AgentEvent.ToolCallComplete(
                    "c$it", "code_read", "{}", "ok", "", true, 1
                )
            )
        }
        val cp = CodeStreamCheckpoint.fromSession(s, "ws_cap", maxEntries = 100)
        assertEquals("封顶裁剪：只保留最近 100 条", 100, cp.entries.size)
        assertTrue("最新的在前窗内", cp.entries.last().call?.target == "f499.kt")
    }

    @Test
    fun `clear removes checkpoint file`() {
        val dir = tmp.newFolder("clear")
        val s = CodeStreamSession()
        s.beginRun("x")
        CodeStreamCheckpoint.save(dir, CodeStreamCheckpoint.fromSession(s, "ws_clear"))
        assertTrue(dir.resolve("stream_ws_clear.json").exists())
        CodeStreamCheckpoint.clear(dir, "ws_clear")
        assertNull(CodeStreamCheckpoint.load(dir, "ws_clear"))
    }

    @Test
    fun `atomic write leaves no tmp residue on success`() {
        val dir = tmp.newFolder("atomic")
        val s = CodeStreamSession()
        s.beginRun("x")
        CodeStreamCheckpoint.save(dir, CodeStreamCheckpoint.fromSession(s, "ws_atomic"))
        assertEquals(
            "成功路径无 tmp 残留",
            1,
            dir.listFiles { f -> f.name.endsWith(".json") }!!.size
        )
        assertEquals(0, dir.listFiles { f -> f.name.endsWith(".tmp") }!!.size)
    }
}
