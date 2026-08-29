package com.apex.agent.platform.terminal.persistence

import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.runtime.BackendSessionMetadata
import com.apex.agent.platform.terminal.session.SessionState
import com.apex.agent.platform.terminal.session.TerminalSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T73 — SessionRecord schema v2：后端元数据持久化 + v1 向后兼容。
 *
 * 崩溃恢复后必须能区分"这是 Ubuntu 会话还是本地会话"—— 否则恢复逻辑
 * 与 Agent 上下文都会把 PRoot 会话误当本地 shell（T73-audit G4）。
 */
class SessionMetadataStoreBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun linuxSession(): TerminalSession = TerminalSession(
        id = 42L, shell = "/bin/bash", initialCwd = "/workspace", pid = 1234,
        rows = 24, cols = 80, privilege = PrivilegeLevel.NORMAL,
        state = SessionState.READY, createdAt = System.currentTimeMillis(),
        lastExitCode = null, cursor = 100L,
        backend = BackendSessionMetadata(
            backendId = "linux-ubuntu",
            rootfsId = "ubuntu-24.04.4-arm64",
            workspaceDir = "/data/user/0/app/files/linux/workspace",
            binds = listOf("/data/user/0/app/files/linux/workspace:/workspace"),
            guestCwd = "/workspace"
        )
    )

    @Test
    fun `backend metadata round-trips through v2 record`() = runBlocking {
        val store = SessionMetadataStore(tmp.newFolder())
        store.save(linuxSession(), emptyList(), emptyList())

        val loaded = store.load(42L)!!
        assertEquals(2, loaded.schemaVersion)
        assertEquals("linux-ubuntu", loaded.backendId)
        assertEquals("ubuntu-24.04.4-arm64", loaded.rootfsId)
        assertEquals("/data/user/0/app/files/linux/workspace", loaded.workspaceDir)
        assertEquals("/workspace", loaded.guestCwd)
        assertEquals(listOf("/data/user/0/app/files/linux/workspace:/workspace"), loaded.binds)
    }

    @Test
    fun `local session persists with null backend fields`() = runBlocking {
        val store = SessionMetadataStore(tmp.newFolder())
        val local = TerminalSession(
            id = 7L, shell = "/system/bin/sh", initialCwd = "/sdcard", pid = 99,
            rows = 24, cols = 80, privilege = PrivilegeLevel.NORMAL,
            state = SessionState.READY, createdAt = System.currentTimeMillis(),
            lastExitCode = null, cursor = 0L,
            backend = BackendSessionMetadata(backendId = "local")
        )
        store.save(local, emptyList(), emptyList())

        val loaded = store.load(7L)!!
        assertEquals("local", loaded.backendId)
        assertNull(loaded.rootfsId)
        assertTrue(loaded.binds.isEmpty())
    }

    @Test
    fun `v1 record without backend fields still loads (schema migration)`() = runBlocking {
        // 模拟 T73 之前写出的 v1 文件（无 backend 字段）—— ignoreUnknownKeys/默认值
        // 保证升级后旧记录可读，backendId=null 语义上等同 "local"。
        val dir = tmp.newFolder()
        val v1Json = """
            {"schemaVersion":1,"id":5,"shell":"/system/bin/sh","initialCwd":"/sdcard",
             "pid":11,"rows":24,"cols":80,"privilege":"NORMAL","state":"EXITED",
             "createdAt":1700000000000,"lastActivityAt":1700000000000,
             "lastExitCode":0,"cursor":55,"jobs":[],"recentEvents":[]}
        """.trimIndent()
        java.io.File(dir, "session-5.json").writeText(v1Json)

        val loaded = SessionMetadataStore(dir).load(5L)!!
        assertEquals(1, loaded.schemaVersion)
        assertNull(loaded.backendId)
        assertNull(loaded.rootfsId)
        assertEquals("/system/bin/sh", loaded.shell)
        assertEquals(55L, loaded.cursor)
    }
}
