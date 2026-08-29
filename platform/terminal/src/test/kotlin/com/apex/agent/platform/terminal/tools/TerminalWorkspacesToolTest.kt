package com.apex.agent.platform.terminal.tools

import com.apex.agent.platform.terminal.tools.v2.TerminalWorkspacesTool
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T75: terminal.workspaces 工具测试 —— JSON 契约（list/create/inspect/delete）+
 * 错误分支（Busy/NotFound/InvalidId/InvalidInput）。
 */
class TerminalWorkspacesToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun tool() = TerminalWorkspacesTool(
        LinuxWorkspaceManager(rootDir = File(tmp.root, "workspaces"))
    )

    /** suspend invoke 的同步包装（测试断言全在 JVM 线程内完成）。 */
    private fun call(json: String): String = runBlocking { tool().invoke(json) }

    private fun call(t: TerminalWorkspacesTool, json: String): String = runBlocking { t.invoke(json) }

    private fun parse(s: String) = json.parseToJsonElement(s).jsonObject

    // ─── list ───

    @Test
    fun `list on empty root returns empty array`() {
        val out = parse(call("""{"action":"list"}"""))
        assertEquals(0, out["workspaces"]!!.jsonArray.size)
    }

    @Test
    fun `list returns created workspaces with active session counts`() {
        val t = tool()
        call("""{"action":"create","id":"alpha","name":"Alpha"}""")
        call("""{"action":"create","id":"beta"}""")

        val out = parse(call("""{"action":"list"}"""))
        val ws = out["workspaces"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, ws.size)
        val alpha = ws.first { it["id"]!!.jsonPrimitive.content == "alpha" }
        assertEquals("Alpha", alpha["name"]!!.jsonPrimitive.content)
        assertEquals("READY", alpha["state"]!!.jsonPrimitive.content)
        assertEquals(0, alpha["activeSessions"]!!.jsonPrimitive.content.toInt())
        // list 不含 sizeBytes（廉价路径）
        assertNull(alpha["sizeBytes"])
    }

    // ─── create ───

    @Test
    fun `create with id returns snapshot and is idempotent`() {
        val t = tool()
        val first = parse(call("""{"action":"create","id":"task-1"}"""))
        assertTrue(first["created"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("task-1", first["workspace"]!!.jsonObject["id"]!!.jsonPrimitive.content)

        val second = parse(call("""{"action":"create","id":"task-1"}"""))
        assertEquals(
            first["workspace"]!!.jsonObject["createdAt"],
            second["workspace"]!!.jsonObject["createdAt"]
        )
    }

    @Test
    fun `create with name slugifies`() {
        val out = parse(call("""{"action":"create","name":"Data Crunch v2"}"""))
        assertEquals("data-crunch-v2", out["workspace"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    // ─── inspect ───

    @Test
    fun `inspect includes sizeBytes`() {
        val t = tool()
        call("""{"action":"create","id":"data"}""")
        // 直接写文件到 workspace 目录
        val dir = File(File(tmp.root, "workspaces"), "data")
        File(dir, "blob.bin").writeText("0123456789")

        val out = parse(call("""{"action":"inspect","id":"data"}"""))
        assertEquals(10, out["workspace"]!!.jsonObject["sizeBytes"]!!.jsonPrimitive.content.toLong())
    }

    // ─── delete ───

    @Test
    fun `delete removes workspace and confirms`() {
        val t = tool()
        call("""{"action":"create","id":"temp"}""")
        val out = parse(call("""{"action":"delete","id":"temp"}"""))
        assertTrue(out["deleted"]!!.jsonPrimitive.content.toBoolean())
        // list 不再有
        assertEquals(0, parse(call("""{"action":"list"}"""))["workspaces"]!!.jsonArray.size)
    }

    // ─── 错误分支 ───

    @Test
    fun `delete with active session fails as Busy`() {
        // 工具与其 bind 的 manager 必须是同一实例（活跃计数在内存）
        val m = LinuxWorkspaceManager(File(tmp.root, "workspaces"))
        val t = TerminalWorkspacesTool(m)
        m.resolve("busy-ws").getOrThrow()
        m.bind(42, "busy-ws")

        try {
            call(t, """{"action":"delete","id":"busy-ws"}""")
            fail("expected WorkspaceError:Busy")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("WorkspaceError:Busy"))
        }
    }

    @Test
    fun `unknown action and invalid id are rejected`() {
        val t = tool()
        try {
            call("""{"action":"explode"}""")
            fail("expected InvalidInput")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("TerminalError:InvalidInput"))
        }
        try {
            call("""{"action":"inspect","id":"BAD ID"}""")
            fail("expected InvalidId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("WorkspaceError:InvalidId"))
        }
    }

    @Test
    fun `inspect and delete require id`() {
        val t = tool()
        try {
            call("""{"action":"inspect"}""")
            fail("expected InvalidInput")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("WorkspaceError:InvalidInput"))
        }
    }
}
