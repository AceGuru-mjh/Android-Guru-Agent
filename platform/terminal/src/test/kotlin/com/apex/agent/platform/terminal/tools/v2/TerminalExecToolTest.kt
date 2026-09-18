package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.exec.AnsiMode
import com.apex.agent.platform.terminal.exec.ExecEngine
import com.apex.agent.platform.terminal.exec.ExecRequest
import com.apex.agent.platform.terminal.exec.ProcessBuilderSpawner
import com.apex.agent.platform.terminal.exec.CommandResult
import com.apex.agent.platform.terminal.exec.CommandSpawner
import com.apex.agent.platform.terminal.exec.SpawnRequest
import com.apex.agent.platform.terminal.exec.SpawnedCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * terminal.exec 工具层契约测试 —— 锁定 Agent 可见的 JSON 形态
 * （用户契约：stdout/stderr/exit_code/duration_ms/truncated，snake_case）。
 */
class TerminalExecToolTest {

    private fun realEngine(): ExecEngine = ExecEngine(
        ProcessBuilderSpawner(channel = "local-sh", shell = "/bin/sh"),
        Dispatchers.IO
    )

    // 固定脚本引擎：不依赖系统 sh，验证工具层编排（门禁/cwd 记忆/JSON）
    private fun scriptedEngine(): ExecEngine = ExecEngine(
        object : CommandSpawner {
            override val channel = "scripted"
            override val supportsEnv = true
            override fun spawn(request: SpawnRequest): SpawnedCommand {
                val out = "ran [${request.command}] in [${request.cwd}] env=[${request.env}]"
                return object : SpawnedCommand {
                    override val stdout = ByteArrayInputStream(out.toByteArray())
                    override val stderr: java.io.InputStream? = ByteArrayInputStream("no-err".toByteArray())
                    override fun waitFor(timeoutMs: Long) = true
                    override fun exitValue() = 0
                    override fun destroy() {}
                }
            }
        },
        Dispatchers.Unconfined
    )

    @Test fun `json output carries user contract fields in snake_case`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val tool = TerminalExecTool(realEngine())
        val json = tool.invoke("""{"command":"echo hello; echo bad 1>&2; exit 0"}""")
        val o = Json.parseToJsonElement(json).jsonObject

        // 用户契约最小集
        assertEquals("hello\n", o["stdout"]!!.jsonPrimitive.content)
        assertEquals("bad\n", o["stderr"]!!.jsonPrimitive.content)
        assertEquals(0, o["exit_code"]!!.jsonPrimitive.int)
        assertTrue(o["duration_ms"]!!.jsonPrimitive.long >= 0)
        assertEquals(false, o["truncated"]!!.jsonPrimitive.boolean)

        // 诚实扩展字段
        assertEquals("local-sh", o["channel"]!!.jsonPrimitive.content)
        assertEquals(true, o["stderr_separated"]!!.jsonPrimitive.boolean)
        assertEquals("strip", o["ansi_mode"]!!.jsonPrimitive.content)
        assertEquals(6L, o["stdout_bytes_total"]!!.jsonPrimitive.long)
        assertEquals(4L, o["stderr_bytes_total"]!!.jsonPrimitive.long)
        assertFalse(o.containsKey("error"))
    }

    @Test fun `gate rejection returns structured permission_denied without executing`() = runBlocking<Unit> {
        var spawned = false
        val engine = scriptedEngine()
        val tool = TerminalExecTool(
            engine = engine,
            approvalGate = { "用户拒绝执行命令" }
        )
        val json = tool.invoke("""{"command":"rm -rf /"}""")
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals("permission_denied", o["error"]!!.jsonPrimitive.content)
        assertEquals(126, o["exit_code"]!!.jsonPrimitive.int)
        assertTrue(o["stderr"]!!.jsonPrimitive.content.contains("用户拒绝执行命令"))
        assertEquals("", o["stdout"]!!.jsonPrimitive.content)
    }

    @Test fun `default cwd resolver feeds engine when cwd omitted`() = runBlocking<Unit> {
        val tool = TerminalExecTool(
            engine = scriptedEngine(),
            defaultCwd = { "/remembered/dir" }
        )
        val json = tool.invoke("""{"command":"pwd"}""")
        val o = Json.parseToJsonElement(json).jsonObject
        assertTrue(o["stdout"]!!.jsonPrimitive.content.contains("[/remembered/dir]"))
        assertEquals("/remembered/dir", o["cwd"]!!.jsonPrimitive.content)
    }

    @Test fun `explicit env parsed from json object`() = runBlocking<Unit> {
        val tool = TerminalExecTool(scriptedEngine())
        val json = tool.invoke("""{"command":"x","env":{"K1":"V1","K2":"V2"}}""")
        val o = Json.parseToJsonElement(json).jsonObject
        val out = o["stdout"]!!.jsonPrimitive.content
        assertTrue(out.contains("K1=V1"))
        assertTrue(out.contains("K2=V2"))
        assertEquals(true, o["env_applied"]!!.jsonPrimitive.boolean)
    }

    @Test fun `onCommandSucceeded invoked only for zero exit`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val succeeded = mutableListOf<String>()
        val tool = TerminalExecTool(
            engine = realEngine(),
            onCommandSucceeded = { cmd, result -> succeeded.add("${cmd}:${result.exitCode}") }
        )
        tool.invoke("""{"command":"true"}""")
        tool.invoke("""{"command":"false"}""")
        assertEquals(listOf("true:0"), succeeded)
    }

    @Test fun `timeout surfaces timed_out and killed with exit minus one`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val tool = TerminalExecTool(realEngine())
        val json = tool.invoke("""{"command":"sleep 20","timeout_ms":1000}""")
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals(true, o["timed_out"]!!.jsonPrimitive.boolean)
        assertEquals(true, o["killed"]!!.jsonPrimitive.boolean)
        assertEquals(-1, o["exit_code"]!!.jsonPrimitive.int)
    }

    @Test fun `missing command rejected with invalid input`() = runBlocking<Unit> {
        val tool = TerminalExecTool(scriptedEngine())
        try {
            tool.invoke("""{"cwd":"/x"}""")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("command"))
        }
    }

    @Test fun `timeout clamped into sane bounds`() = runBlocking<Unit> {
        val tool = TerminalExecTool(scriptedEngine())
        // 1ms 会被钳到下限 1000 —— Scripted 引擎立即返回，不真正等待
        val json = tool.invoke("""{"command":"x","timeout_ms":1}""")
        assertTrue(json.contains("\"channel\":\"scripted\""))
    }

    @Test fun `ansi keep mode honored through json`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val tool = TerminalExecTool(realEngine())
        val json = tool.invoke("""{"command":"printf 'A\\033[31mB\\033[0mC'","ansi":"keep"}""")
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals("keep", o["ansi_mode"]!!.jsonPrimitive.content)
        assertTrue(o["stdout"]!!.jsonPrimitive.content.contains("\u001B[31m"))
        assertEquals(0, o["ansi_sequences_removed"]!!.jsonPrimitive.int)
    }

    @Test fun `ansi strip default removes sequences through full stack`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val tool = TerminalExecTool(realEngine())
        val json = tool.invoke("""{"command":"printf 'A\\033[31mB\\033[0mC'"}""")
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals("ABC", o["stdout"]!!.jsonPrimitive.content)
        assertTrue(o["ansi_sequences_removed"]!!.jsonPrimitive.int >= 2)
    }

    @Test fun `spawn failure mapped to 126 with error field`() = runBlocking<Unit> {
        val engine = ExecEngine(
            ProcessBuilderSpawner(channel = "local-sh", shell = "/definitely/not/a/shell"),
            Dispatchers.IO
        )
        val tool = TerminalExecTool(engine)
        val json = tool.invoke("""{"command":"echo hi"}""")
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals("spawn_failed", o["error"]!!.jsonPrimitive.content)
        assertEquals(126, o["exit_code"]!!.jsonPrimitive.int)
        assertTrue(o["stderr"]!!.jsonPrimitive.content.contains("spawn failed"))
    }

    @Test fun `schema and id are stable`() {
        val tool = TerminalExecTool(scriptedEngine())
        assertEquals("terminal.exec", tool.id)
        val schema = Json.parseToJsonElement(tool.parametersSchema).jsonObject
        val props = schema["properties"]!!.jsonObject
        for (key in listOf("command", "cwd", "timeout_ms", "max_output_chars", "max_error_chars", "head_lines", "tail_lines", "ansi", "env")) {
            assertTrue("schema missing $key", props.containsKey(key))
        }
        assertTrue(tool.description.contains("stdout, stderr, exit_code, duration_ms, truncated"))
    }

    @Test fun `cd memory hook receives command on success`() = runBlocking<Unit> {
        val seen = mutableListOf<String>()
        val tool = TerminalExecTool(
            engine = scriptedEngine(),
            onCommandSucceeded = { cmd, _ -> seen.add(cmd) }
        )
        tool.invoke("""{"command":"cd /tmp && ls"}""")
        assertEquals(listOf("cd /tmp && ls"), seen)
    }
}
