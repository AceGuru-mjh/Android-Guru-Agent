package com.apex.agent.platform.terminal.tools

import com.apex.agent.platform.terminal.runtime.BackendAvailability
import com.apex.agent.platform.terminal.runtime.BackendRuntimeType
import com.apex.agent.platform.terminal.runtime.ExecutionBackend
import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import com.apex.agent.platform.terminal.runtime.SpawnSpec
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.v2.TerminalBackendsTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * T73 — terminal.backends 工具：后端能力发现的 Agent 出口。
 * 输出契约：{ backends: [ { id, runtimeType, available, state, detail? } ] }
 */
class TerminalBackendsToolTest {

    private class FakeBackend(
        override val id: String,
        override val runtimeType: BackendRuntimeType,
        private val av: BackendAvailability
    ) : ExecutionBackend {
        override suspend fun availability(): BackendAvailability = av
        override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> =
            Result.failure(RuntimeException("unused"))
    }

    private class FakeRuntime(private val statuses: List<TerminalRuntime.BackendStatus>) : TerminalRuntime {
        override suspend fun shutdown(): Result<com.apex.agent.platform.terminal.runtime.TerminalRuntime.ShutdownResult> =
            Result.success(com.apex.agent.platform.terminal.runtime.TerminalRuntime.ShutdownResult(0, 0, true))

        override suspend fun backends(): List<TerminalRuntime.BackendStatus> = statuses
        // 其余门面操作在工具测试中不可达 —— 工具只调 backends()。
        override suspend fun create(shell: String, cwd: String, rows: Int, cols: Int, env: Map<String, String>, privilege: com.apex.agent.platform.terminal.policy.PrivilegeLevel, backendId: String, workspaceId: String?): Result<TerminalRuntime.CreateResult> = throw UnsupportedOperationException()
        override suspend fun run(sessionId: Long, command: String, owner: com.apex.agent.platform.terminal.io.InputOwner, background: Boolean, timeoutMs: Long): Result<TerminalRuntime.RunResult> = Result.failure(UnsupportedOperationException())
        override suspend fun observe(sessionId: Long, mode: TerminalRuntime.ObserveMode, afterCursor: Long, maxBytes: Int, maxEvents: Int): Result<TerminalRuntime.ObserveResult> = Result.failure(UnsupportedOperationException())
        override suspend fun wait(sessionId: Long, condition: com.apex.agent.platform.terminal.wait.WaitCondition, timeoutMs: Long): Result<com.apex.agent.platform.terminal.wait.WaitResult> = Result.failure(UnsupportedOperationException())
        override suspend fun write(sessionId: Long, owner: com.apex.agent.platform.terminal.io.InputOwner, kind: TerminalRuntime.WriteKind, text: String?, key: com.apex.agent.platform.terminal.io.TerminalKey?): Result<TerminalRuntime.WriteResult> = Result.failure(UnsupportedOperationException())
        override suspend fun signal(sessionId: Long, signal: com.apex.agent.platform.terminal.io.UnixSignal, owner: com.apex.agent.platform.terminal.io.InputOwner, jobId: Long?): Result<TerminalRuntime.SignalResult> = Result.failure(UnsupportedOperationException())
        override suspend fun cancel(sessionId: Long, jobId: Long): Result<TerminalRuntime.CancelResult> = Result.failure(UnsupportedOperationException())
        override suspend fun resize(sessionId: Long, rows: Int, cols: Int): Result<TerminalRuntime.ResizeResult> = Result.failure(UnsupportedOperationException())
        override suspend fun snapshot(mode: TerminalRuntime.SnapshotMode, sessionId: Long?, recentEvents: Int, recentOutputBytes: Int): Result<TerminalRuntime.SnapshotResult> = Result.failure(UnsupportedOperationException())
        override suspend fun stop(sessionId: Long): Result<TerminalRuntime.StopResult> = Result.failure(UnsupportedOperationException())
        override suspend fun close(sessionId: Long, force: Boolean): Result<TerminalRuntime.CloseResult> = Result.failure(UnsupportedOperationException())
        override fun screenStateFlow(sessionId: Long): kotlinx.coroutines.flow.Flow<com.apex.agent.platform.terminal.screen.TerminalScreenState>? = null
        override fun semanticStateFlow(sessionId: Long): kotlinx.coroutines.flow.Flow<com.apex.agent.platform.terminal.state.TerminalSemanticState>? = null
        override suspend fun recover(): List<Long> = emptyList()
        override suspend fun recoveredSnapshot(sessionId: Long): com.apex.agent.platform.terminal.state.TerminalSemanticState? = null
    }

    private fun parse(json: String) =
        kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject["backends"]!!.jsonArray

    @Test
    fun `ready and needs-rootfs backends serialize honestly`() = runBlocking {
        val tool = TerminalBackendsTool(
            FakeRuntime(
                listOf(
                    TerminalRuntime.BackendStatus("local", "ANDROID_LOCAL", true, "READY"),
                    TerminalRuntime.BackendStatus("linux-ubuntu", "LINUX", false, "NEEDS_ROOTFS:not_provisioned", "先调用 terminal.ubuntu.install")
                )
            )
        )
        val arr = parse(tool.invoke("{}"))
        assertEquals(2, arr.size)

        val local = arr[0].jsonObject
        assertEquals("local", local["id"]!!.jsonPrimitive.content)
        assertEquals("true", local["available"]!!.jsonPrimitive.content)
        assertEquals("READY", local["state"]!!.jsonPrimitive.content)
        assertNull(local["detail"])

        val ubuntu = arr[1].jsonObject
        assertEquals("linux-ubuntu", ubuntu["id"]!!.jsonPrimitive.content)
        assertEquals("false", ubuntu["available"]!!.jsonPrimitive.content)
        assertEquals("NEEDS_ROOTFS:not_provisioned", ubuntu["state"]!!.jsonPrimitive.content)
        assertEquals("先调用 terminal.ubuntu.install", ubuntu["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty arguments tolerated`() = runBlocking {
        val tool = TerminalBackendsTool(FakeRuntime(emptyList()))
        val arr = parse(tool.invoke(""))
        assertEquals(0, arr.size)
    }

    @Test
    fun `failed backend carries reason in detail`() = runBlocking {
        val tool = TerminalBackendsTool(
            FakeRuntime(listOf(TerminalRuntime.BackendStatus("linux-ubuntu", "LINUX", false, "FAILED", "PRootError:BINARY_NOT_FOUND")))
        )
        val ubuntu = parse(tool.invoke("{}"))[0].jsonObject
        assertEquals("FAILED", ubuntu["state"]!!.jsonPrimitive.content)
        assertEquals("PRootError:BINARY_NOT_FOUND", ubuntu["detail"]!!.jsonPrimitive.content)
    }
}
