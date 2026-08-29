package com.apex.agent.platform.terminal.runtime

import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.session.SessionState
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * T73 — TerminalRuntime ↔ ExecutionBackend 正式接线的核心契约测试。
 *
 * 证明（JVM/FakeNativePty 层）：
 *   1. create() 统一经 ExecutionBackendRegistry 路由：
 *      local 默认路径与 golden 行为一致（argv/env 逐字节）；
 *      linux-ubuntu 路径产出 proot argv 并携带后端元数据。
 *   2. 失败模式可行动：BackendNotFound / RootfsNotReady（引导 Agent 先装 rootfs）/
 *      BackendFailed。
 *   3. backends() 能力发现：READY / NEEDS_ROOTFS / FAILED 三态如实上报。
 *   4. 后端元数据进 TerminalSession.backend（持久化契约在
 *      SessionMetadataStoreBackendTest）。
 *
 * 真实 Ubuntu rootfs + 真实 proot 的链路验证：UbuntuTerminalRuntimeWiringTest（CI）
 * + UbuntuTerminalRuntimeInstrumentationTest（真机 forkpty 全链）。
 */
class TerminalRuntimeBackendTest {

    private class StubLinuxBackend(
        private val availability: BackendAvailability = BackendAvailability.Ready
    ) : ExecutionBackend {
        override val id: String = "linux-ubuntu"
        override val runtimeType: BackendRuntimeType = BackendRuntimeType.LINUX
        var lastRequest: SessionSpawnRequest? = null

        override suspend fun availability(): BackendAvailability = availability

        override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> {
            lastRequest = request
            return Result.success(
                SpawnSpec(
                    argv = listOf("/lib/arm64/libproot.so", "-r", "/data/rootfs/v1", "--", "/bin/bash", "-i"),
                    env = mapOf("PROOT_TMP_DIR" to "/cache/proot-tmp", "PATH" to "/system/bin"),
                    cwd = "/data/rootfs/v1",
                    cwdIsGuestPath = true,
                    shellDisplay = "/bin/bash",
                    cwdDisplay = "/workspace",
                    metadata = BackendSessionMetadata(
                        backendId = id,
                        rootfsId = "ubuntu-24.04.4-arm64",
                        workspaceDir = "/data/files/linux/workspace",
                        binds = listOf("/data/files/linux/workspace:/workspace"),
                        guestCwd = "/workspace"
                    )
                )
            )
        }
    }

    private fun newRuntime(
        pty: FakeNativePty = FakeNativePty(),
        linux: ExecutionBackend = StubLinuxBackend()
    ): Pair<TerminalRuntimeImpl, FakeNativePty> {
        val rt = TerminalRuntimeImpl(
            native = pty,
            policy = TerminalPolicyImpl(),
            backendRegistry = ExecutionBackendRegistry.of(LocalShellBackend(), linux)
        )
        return rt to pty
    }

    // ─── 1. local 默认路由（golden 行为不回归） ───

    @Test
    fun `default create routes through local backend with golden argv`() = runBlocking {
        val (rt, pty) = newRuntime()
        val r = rt.create(shell = "/system/bin/sh", cwd = "/sdcard").getOrThrow()
        assertEquals("local", r.backendId)
        assertEquals("ANDROID_LOCAL", r.runtimeType)
        assertNull(r.rootfsId)
        assertEquals("/system/bin/sh", r.shell)
        assertEquals("/sdcard", r.cwd)

        val nativeId = rt.sessionManager.assembly(r.sessionId)!!.nativeSessionId
        assertEquals(listOf("/system/bin/sh", "-i"), pty.argvOf(nativeId))
        assertEquals("/system/bin/sh", pty.spawnEnvOf(nativeId)["SHELL"])
    }

    @Test
    fun `local create passes explicit env through backend merge`() = runBlocking {
        val (rt, pty) = newRuntime()
        val r = rt.create(env = mapOf("FOO" to "bar", "TERM" to "vt100")).getOrThrow()
        val nativeId = rt.sessionManager.assembly(r.sessionId)!!.nativeSessionId
        val env = pty.spawnEnvOf(nativeId)
        // 显式变量生效（T73 修复：此前 LocalShellBackend 丢弃 request.env）
        assertEquals("bar", env["FOO"])
        assertEquals("vt100", env["TERM"])   // 显式覆盖默认
        assertEquals("shell", env["USER"])   // 默认仍在
    }

    // ─── 2. linux-ubuntu 路由 ───

    @Test
    fun `linux create routes through backend and records proot argv`() = runBlocking {
        val linux = StubLinuxBackend()
        val (rt, pty) = newRuntime(linux = linux)
        val r = rt.create(cwd = "/workspace", backendId = "linux-ubuntu").getOrThrow()

        assertEquals("linux-ubuntu", r.backendId)
        assertEquals("LINUX", r.runtimeType)
        assertEquals("ubuntu-24.04.4-arm64", r.rootfsId)
        assertEquals("/workspace", r.guestCwd)
        // 展示语义：shell 是 guest bash，不是 libproot.so
        assertEquals("/bin/bash", r.shell)
        assertEquals("/workspace", r.cwd)

        val nativeId = rt.sessionManager.assembly(r.sessionId)!!.nativeSessionId
        val argv = pty.argvOf(nativeId)
        assertEquals("/lib/arm64/libproot.so", argv[0])
        assertTrue(argv.contains("--"))
        assertEquals(listOf("/bin/bash", "-i"), argv.takeLast(2))
        assertEquals("/cache/proot-tmp", pty.spawnEnvOf(nativeId)["PROOT_TMP_DIR"])

        // 请求语义传递：cwd 是 guest 路径、shell hint 不进 linux 后端
        assertEquals("/workspace", linux.lastRequest!!.cwd)
        assertNull(linux.lastRequest!!.shellHint)
    }

    @Test
    fun `linux session carries backend metadata in TerminalSession`() = runBlocking {
        val (rt, _) = newRuntime()
        val r = rt.create(backendId = "linux-ubuntu").getOrThrow()
        val session = rt.sessionManager.get(r.sessionId) ?: error("session not found")
        assertEquals("linux-ubuntu", session.backend?.backendId)
        assertEquals("ubuntu-24.04.4-arm64", session.backend?.rootfsId)
        assertEquals("/workspace", session.backend?.guestCwd)
        assertEquals("/bin/bash", session.shell)
        assertEquals("/workspace", session.initialCwd)
    }

    @Test
    fun `local session carries local backend metadata`() = runBlocking {
        val (rt, _) = newRuntime()
        val r = rt.create().getOrThrow()
        val session = rt.sessionManager.get(r.sessionId) ?: error("session not found")
        // 统一路由后本地会话也携带 backend 元数据（backendId=local）
        assertEquals("local", session.backend?.backendId)
        assertNull(session.backend?.rootfsId)
    }

    // ─── 3. 失败模式（可行动错误） ───

    @Test
    fun `unknown backend fails with BackendNotFound listing available`() = runBlocking {
        val (rt, _) = newRuntime()
        val err = rt.create(backendId = "linux-debian").exceptionOrNull()
        assertNotNull(err)
        val msg = err!!.message ?: ""
        assertTrue("BackendNotFound: $msg", msg.contains("BackendNotFound"))
        assertTrue("lists available backends: $msg", msg.contains("local") && msg.contains("linux-ubuntu"))
    }

    @Test
    fun `needs-rootfs fails fast with install guidance`() = runBlocking {
        val linux = StubLinuxBackend(availability = BackendAvailability.NeedsRootfs("not_provisioned"))
        val (rt, _) = newRuntime(linux = linux)
        val err = rt.create(backendId = "linux-ubuntu").exceptionOrNull()
        assertNotNull(err)
        val msg = err!!.message ?: ""
        assertTrue("RootfsNotReady: $msg", msg.contains("RootfsNotReady"))
        assertTrue("guidance mentions install: $msg", msg.contains("terminal.ubuntu.install"))
    }

    @Test
    fun `failed backend reports reason`() = runBlocking {
        val linux = StubLinuxBackend(availability = BackendAvailability.Failed("PRootError:BINARY_NOT_FOUND"))
        val (rt, _) = newRuntime(linux = linux)
        val err = rt.create(backendId = "linux-ubuntu").exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("BackendFailed"))
        assertTrue(err.message!!.contains("BINARY_NOT_FOUND"))
    }

    // ─── 4. backends() 能力发现 ───

    @Test
    fun `backends reports local READY and linux NEEDS_ROOTFS`() = runBlocking {
        val linux = StubLinuxBackend(availability = BackendAvailability.NeedsRootfs("not_provisioned"))
        val (rt, _) = newRuntime(linux = linux)
        val list = rt.backends()
        assertEquals(2, list.size)

        val local = list.first { it.id == "local" }
        assertTrue(local.available)
        assertEquals("READY", local.state)
        assertEquals("ANDROID_LOCAL", local.runtimeType)

        val ubuntu = list.first { it.id == "linux-ubuntu" }
        assertFalse(ubuntu.available)
        assertEquals("NEEDS_ROOTFS:not_provisioned", ubuntu.state)
        assertTrue("guidance in detail", ubuntu.detail!!.contains("terminal.ubuntu.install"))
    }

    @Test
    fun `backends reports linux READY when rootfs installed`() = runBlocking {
        val (rt, _) = newRuntime()
        val ubuntu = rt.backends().first { it.id == "linux-ubuntu" }
        assertTrue(ubuntu.available)
        assertEquals("READY", ubuntu.state)
        assertEquals("LINUX", ubuntu.runtimeType)
    }

    // ─── 5. 后端会话上的常规操作不受影响 ───

    @Test
    fun `run and observe work on a linux backend session`() = runBlocking {
        val (rt, _) = newRuntime()
        val r = rt.create(backendId = "linux-ubuntu").getOrThrow()
        assertEquals(SessionState.READY.name, r.state)

        // run 走同一条 JobManager 路径（backend 无关）
        val job = rt.run(r.sessionId, "cat /etc/os-release", com.apex.agent.platform.terminal.io.InputOwner.AGENT)
        assertTrue(job.isSuccess)
        assertTrue(job.getOrThrow().jobId > 0)

        val obs = rt.observe(r.sessionId, com.apex.agent.platform.terminal.runtime.TerminalRuntime.ObserveMode.RAW, 0, 65536)
        assertTrue(obs.isSuccess)

        val closed = rt.close(r.sessionId)
        assertTrue(closed.isSuccess)
    }

    // ─── 6. T75: workspace 路由 + 会话绑定生命周期 ───

    @Test
    fun `local create with workspaceId is rejected as InvalidInput`() = runBlocking {
        val (rt, _) = newRuntime()
        val err = rt.create(backendId = "local", workspaceId = "alpha").exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("TerminalError:InvalidInput"))
        assertTrue(err.message!!.contains("workspaceId"))
    }

    @Test
    fun `linux create forwards workspaceId to backend request`() = runBlocking {
        val linux = StubLinuxBackend()
        val (rt, _) = newRuntime(linux = linux)
        rt.create(backendId = "linux-ubuntu", workspaceId = "task-42").getOrThrow()

        assertEquals("task-42", linux.lastRequest!!.workspaceId)
    }

    @Test
    fun `linux create result carries backend workspaceId`() = runBlocking {
        val linux = object : ExecutionBackend {
            override val id: String = "linux-ubuntu"
            override val runtimeType: BackendRuntimeType = BackendRuntimeType.LINUX
            override suspend fun availability(): BackendAvailability = BackendAvailability.Ready
            override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> =
                Result.success(
                    SpawnSpec(
                        argv = listOf("/libproot.so", "--", "/bin/bash", "-i"),
                        env = emptyMap(),
                        cwd = "/rootfs",
                        cwdIsGuestPath = true,
                        metadata = BackendSessionMetadata(
                            backendId = id, workspaceId = "task-42",
                            workspaceDir = "/ws/task-42", guestCwd = "/workspace"
                        )
                    )
                )
        }
        val (rt, _) = newRuntime(linux = linux)
        val r = rt.create(backendId = "linux-ubuntu", workspaceId = "task-42").getOrThrow()
        assertEquals("task-42", r.workspaceId)
    }

    @Test
    fun `binder binds on linux create and unbinds on close`() = runBlocking {
        val binder = RecordingBinder()
        // backend metadata 带 workspaceId → create 成功后 bind
        val linux = object : ExecutionBackend {
            override val id: String = "linux-ubuntu"
            override val runtimeType: BackendRuntimeType = BackendRuntimeType.LINUX
            override suspend fun availability(): BackendAvailability = BackendAvailability.Ready
            override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> =
                Result.success(
                    SpawnSpec(
                        argv = listOf("/libproot.so", "--", "/bin/bash", "-i"),
                        env = emptyMap(),
                        cwd = "/rootfs",
                        cwdIsGuestPath = true,
                        metadata = BackendSessionMetadata(
                            backendId = id, workspaceId = "ws-a", guestCwd = "/workspace"
                        )
                    )
                )
        }
        val rt = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            backendRegistry = ExecutionBackendRegistry.of(LocalShellBackend(), linux),
            workspaceBinder = binder
        )

        val r = rt.create(backendId = "linux-ubuntu", workspaceId = "ws-a").getOrThrow()
        assertEquals(listOf(r.sessionId to "ws-a"), binder.bound)

        // close 成功 → unbind（delete 门禁解除）
        rt.close(r.sessionId).getOrThrow()
        assertEquals(listOf(r.sessionId), binder.unbound)

        // LOCAL 会话不 bind；close 时 unbind 是无害 no-op（会调用但不影响计数）
        val local = rt.create(backendId = "local").getOrThrow()
        rt.close(local.sessionId).getOrThrow()
        assertEquals(1, binder.bound.size)
        assertEquals(listOf(r.sessionId, local.sessionId), binder.unbound)
    }

    private class RecordingBinder : com.apex.agent.platform.terminal.workspace.SessionWorkspaceBinder {
        val bound = mutableListOf<Pair<Long, String>>()
        val unbound = mutableListOf<Long>()
        override fun bind(sessionId: Long, workspaceId: String) { bound += sessionId to workspaceId }
        override fun unbind(sessionId: Long) { unbound += sessionId }
    }
}
