package com.apex.agent.platform.terminal.runtime

import com.apex.agent.platform.terminal.pty.FakeNativePty
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * P71 golden tests — LocalShellBackend 的 argv/env 必须与 P70 前
 * pty_session.cpp 硬编码的 spawn 行为逐字节一致。
 *
 * 这是"不破坏成熟本地路径"的结构性保证（PR #75 计划 §3.1/§3.2）：
 * P73 把 SessionManager.create() 切到 backend 路径时，本快照即回归契约。
 */
class ExecutionBackendGoldenTest {

    private val backend = LocalShellBackend()

    // ─── golden argv ───

    @Test
    fun `default spawn argv is system sh interactive`() = runBlocking {
        val spec = backend.prepare(SessionSpawnRequest(cwd = "/sdcard", rows = 24, cols = 80)).getOrThrow()
        assertEquals(listOf("/system/bin/sh", "-i"), spec.argv)
    }

    @Test
    fun `shell hint overrides argv0`() = runBlocking {
        val spec = backend.prepare(
            SessionSpawnRequest(shellHint = "/system/bin/mksh", cwd = "/data", rows = 24, cols = 80)
        ).getOrThrow()
        assertEquals(listOf("/system/bin/mksh", "-i"), spec.argv)
    }

    // ─── golden env（== pty_session.cpp 旧硬编码，逐键断言防漂移） ───

    @Test
    fun `default env matches legacy C++ hardcoded env`() = runBlocking {
        val spec = backend.prepare(SessionSpawnRequest(cwd = "/sdcard", rows = 24, cols = 80)).getOrThrow()
        val expected = mapOf(
            "TERM" to "xterm-256color",
            "HOME" to "/data/local/tmp",
            "USER" to "shell",
            "SHELL" to "/system/bin/sh",
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "en_US.UTF-8",
            "PATH" to "/system/bin:/system/xbin:/vendor/bin:/data/local/tmp/bin:/product/bin"
        )
        assertEquals(expected, spec.env)
    }

    @Test
    fun `empty shell hint falls back to default shell`() = runBlocking {
        val spec = backend.prepare(
            SessionSpawnRequest(shellHint = "  ", cwd = "/x", rows = 24, cols = 80)
        ).getOrThrow()
        assertEquals(listOf("/system/bin/sh", "-i"), spec.argv)
        assertEquals("/system/bin/sh", spec.env["SHELL"])
    }

    // ─── spec 基础属性 ───

    @Test
    fun `cwd passthrough and never guest`() = runBlocking {
        val spec = backend.prepare(SessionSpawnRequest(cwd = "/sdcard/workspace", rows = 30, cols = 100)).getOrThrow()
        assertEquals("/sdcard/workspace", spec.cwd)
        assertFalse(spec.cwdIsGuestPath)
        assertNull(spec.metadata.guestCwd)
        assertEquals(LocalShellBackend.ID, spec.metadata.backendId)
    }

    @Test
    fun `blank cwd falls back to default`() = runBlocking {
        val spec = backend.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80)).getOrThrow()
        assertEquals(LocalShellBackend.DEFAULT_CWD, spec.cwd)
    }

    @Test
    fun `local backend is always ready`() = runBlocking {
        assertTrue(backend.availability() is BackendAvailability.Ready)
    }

    // ─── LocalShellBackend → FakeNativePty(argv) 全链（P73 切换前的行为锁定） ───

    @Test
    fun `spec feeds nativeCreateSessionArgv unchanged`() = runBlocking {
        val pty = FakeNativePty()
        val spec = backend.prepare(
            SessionSpawnRequest(shellHint = "/system/bin/sh", cwd = "/sdcard", rows = 24, cols = 80)
        ).getOrThrow()

        val nativeId = pty.nativeCreateSessionArgv(spec.argv, spec.cwd, 24, 80, spec.env)

        assertTrue(nativeId > 0)
        assertEquals(spec.argv, pty.argvOf(nativeId))
        assertEquals(spec.env, pty.spawnEnvOf(nativeId))
    }

    // ─── Registry ───

    @Test
    fun `registry resolves by id and defaults to local`() {
        val registry = ExecutionBackendRegistry.of(backend, StubBackend("linux-ubuntu"))
        assertEquals(LocalShellBackend.ID, registry.default.id)
        assertEquals("linux-ubuntu", registry.get("linux-ubuntu")?.id)
        assertNull(registry.get("missing"))
        assertEquals(2, registry.list().size)
    }

    private class StubBackend(override val id: String) : ExecutionBackend {
        override val runtimeType = BackendRuntimeType.LINUX
        override suspend fun availability(): BackendAvailability = BackendAvailability.Ready
        override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> =
            Result.failure(RuntimeException("stub"))
    }
}
