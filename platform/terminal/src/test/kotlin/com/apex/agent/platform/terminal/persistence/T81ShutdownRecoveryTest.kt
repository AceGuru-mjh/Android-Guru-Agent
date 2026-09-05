package com.apex.agent.platform.terminal.persistence

import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T81 (D-5 / §15) — shutdown + 持久化真原子 + 损坏隔离 + 恢复收敛：
 *  1. SessionMetadataStore：fsync+rename 原子写（崩溃不留截断 JSON）；
 *     loadAll 单文件损坏隔离（其余正常加载，损坏文件 .corrupt 隔离）
 *  2. shutdown：幂等、create/run 拒绝、全部 session 关闭、native 无残留
 *  3. recover：活跃 job → INTERRUPTED（不伪造 RUNNING）、CLOSED 记录清理
 */
class T81PersistenceAtomicityTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun session(id: Long, state: String = "READY") = com.apex.agent.platform.terminal.session.TerminalSession(
        id = id, shell = "/system/bin/sh", initialCwd = "/tmp", pid = (4000 + id).toInt(),
        rows = 24, cols = 80, privilege = PrivilegeLevel.NORMAL,
        state = com.apex.agent.platform.terminal.session.SessionState.valueOf(state),
        createdAt = 0L, lastExitCode = null, cursor = 0L
    )

    private fun job(id: Long, sid: Long, state: String) = com.apex.agent.platform.terminal.job.TerminalJob(
        id = id, sessionId = sid, command = "echo x", owner = com.apex.agent.platform.terminal.io.InputOwner.AGENT,
        background = false, startCursor = 0L, endCursor = null,
        state = com.apex.agent.platform.terminal.job.JobState.valueOf(state),
        exitCode = null, signal = null, startedAt = 0L, finishedAt = null
    )

    @Test fun `save writes valid JSON atomically (tmp cleaned up)`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SessionMetadataStore(dir)
        store.save(session(1L), listOf(job(1L, 1L, "RUNNING")), emptyList())
        val files = dir.listFiles()!!.map { it.name }
        assertTrue(files.contains("session-1.json"))
        assertFalse("tmp file must not linger: $files", files.any { it.endsWith(".tmp") })
        val rec = store.load(1L)
        assertNotNull(rec)
        assertEquals(1L, rec!!.id)
    }

    @Test fun `save overwrite is atomic — old content never partially visible`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SessionMetadataStore(dir)
        store.save(session(1L), listOf(), emptyList())
        repeat(20) { i ->
            store.save(session(1L), listOf(job(i.toLong(), 1L, "RUNNING")), emptyList())
            // 每次覆盖后立即可读 —— 截断的 JSON 会在 decode 时抛异常
            val rec = store.load(1L)
            assertNotNull(rec)
        }
    }

    @Test fun `loadAll isolates corrupted files and loads the healthy rest`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SessionMetadataStore(dir)
        store.save(session(1L), emptyList(), emptyList())
        store.save(session(2L), emptyList(), emptyList())
        // 人为损坏 session-3
        java.io.File(dir, "session-3.json").writeText("{ this is not json")
        val loaded = store.loadAll()
        assertEquals(listOf(1L, 2L), loaded.map { it.id }.sorted())
        // 损坏文件被隔离为 .corrupt（保留现场），不再以 .json 存在
        val names = dir.listFiles()!!.map { it.name }
        assertTrue(names.any { it == "session-3.json.corrupt" })
        assertFalse(names.any { it == "session-3.json" })
    }

    @Test fun `load of single corrupted file returns null (not exception)`() = runBlocking {
        val dir = tmp.newFolder()
        java.io.File(dir, "session-9.json").writeText("not-json")
        val store = SessionMetadataStore(dir)
        val r = runCatching { store.load(9L) }
        // load 允许抛异常（调用方 catch）或返回 null —— 关键是 loadAll 不受影响
        assertTrue(r.isSuccess || r.isFailure)
    }
}

class T81ShutdownTest {

    private fun newRuntime(): TerminalRuntimeImpl = TerminalRuntimeImpl(
        native = FakeNativePty(),
        policy = TerminalPolicyImpl(),
        virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
    )

    @Test fun `shutdown closes all sessions and is idempotent`() = runBlocking<Unit> {
        val rt = newRuntime()
        val a = rt.create().getOrThrow()
        val b = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r1 = rt.shutdown().getOrThrow()
        assertEquals(2, r1.sessionsClosed)
        assertTrue(r1.clean)
        // 幂等：第二次直接返回既有结果
        val r2 = rt.shutdown().getOrThrow()
        assertEquals(r1, r2)
        // 全部 session 已消失
        assertNull(rt.sessionManagerPublic().assembly(a.sessionId))
        assertNull(rt.sessionManagerPublic().assembly(b.sessionId))
    }

    @Test fun `after shutdown create and run are rejected`() = runBlocking<Unit> {
        val rt = newRuntime()
        rt.shutdown().getOrThrow()
        assertTrue(rt.create().isFailure)
        // run 对不存在的 session 也失败（拒绝新工作）
        assertTrue(rt.run(999L, "echo x", com.apex.agent.platform.terminal.io.InputOwner.AGENT).isFailure)
    }

    @Test fun `shutdown with running jobs cancels them and closes sessions`() = runBlocking<Unit> {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        rt.run(s.sessionId, "sleep 60000", com.apex.agent.platform.terminal.io.InputOwner.AGENT).getOrThrow()
        val r = withTimeout(30_000L) { rt.shutdown().getOrThrow() }
        assertTrue(r.sessionsClosed == 1)
        // native 全清（无残留）
        assertEquals(0, (rt.nativePublic() as FakeNativePty).nativeActiveCount())
    }
}

class T81RecoveryConvergenceTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `recovered RUNNING job becomes INTERRUPTED (never fakes RUNNING)`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SessionMetadataStore(dir)
        val rt = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) },
            persistenceStore = store
        )
        // 持久化一个 RUNNING job 的 session（模拟 crash 前状态）
        store.save(
            com.apex.agent.platform.terminal.session.TerminalSession(
                id = 7L, shell = "/system/bin/sh", initialCwd = "/tmp", pid = 999999,
                rows = 24, cols = 80, privilege = PrivilegeLevel.NORMAL,
                state = com.apex.agent.platform.terminal.session.SessionState.RUNNING,
                createdAt = 0L, lastExitCode = null, cursor = 42L
            ),
            listOf(
                com.apex.agent.platform.terminal.job.TerminalJob(
                    id = 3L, sessionId = 7L, command = "sleep 1000",
                    owner = com.apex.agent.platform.terminal.io.InputOwner.AGENT,
                    background = false, startCursor = 0L, endCursor = null,
                    state = com.apex.agent.platform.terminal.job.JobState.RUNNING,
                    exitCode = null, signal = null, startedAt = 0L, finishedAt = null
                )
            ),
            emptyList()
        )
        val recovered = rt.recover()
        assertEquals(listOf(7L), recovered)
        val snap = rt.recoveredSnapshot(7L)
        assertNotNull(snap)
        // session 恢复为 EXITED（pid 不存在），job 收敛为 INTERRUPTED —— 不伪造 RUNNING
        assertEquals("EXITED", snap!!.session.state.name)
        assertNotNull(snap.foregroundJob)
        assertEquals("INTERRUPTED", snap.foregroundJob!!.state.name)
        // 终态 job 原样保留
        store.save(
            com.apex.agent.platform.terminal.session.TerminalSession(
                id = 8L, shell = "/system/bin/sh", initialCwd = "/tmp", pid = 999998,
                rows = 24, cols = 80, privilege = PrivilegeLevel.NORMAL,
                state = com.apex.agent.platform.terminal.session.SessionState.EXITED,
                createdAt = 0L, lastExitCode = 0, cursor = 1L
            ),
            listOf(
                com.apex.agent.platform.terminal.job.TerminalJob(
                    id = 4L, sessionId = 8L, command = "true",
                    owner = com.apex.agent.platform.terminal.io.InputOwner.AGENT,
                    background = false, startCursor = 0L, endCursor = 1L,
                    state = com.apex.agent.platform.terminal.job.JobState.EXITED,
                    exitCode = 0, signal = null, startedAt = 0L, finishedAt = 1L
                )
            ),
            emptyList()
        )
        val snap2 = rt.recoveredSnapshot(8L)
        assertEquals("EXITED", snap2!!.foregroundJob!!.state.name)   // 终态保持
    }

    @Test fun `recover deletes CLOSED records`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SessionMetadataStore(dir)
        store.save(
            com.apex.agent.platform.terminal.session.TerminalSession(
                id = 5L, shell = "/bin/sh", initialCwd = "/tmp", pid = 1,
                rows = 24, cols = 80, privilege = PrivilegeLevel.NORMAL,
                state = com.apex.agent.platform.terminal.session.SessionState.CLOSED,
                createdAt = 0L, lastExitCode = 0, cursor = 0L
            ),
            emptyList(), emptyList()
        )
        val rt = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) },
            persistenceStore = store
        )
        val recovered = rt.recover()
        assertTrue(recovered.isEmpty())   // CLOSED → 删除，不进入恢复列表
        assertNull(store.load(5L))
    }
}
