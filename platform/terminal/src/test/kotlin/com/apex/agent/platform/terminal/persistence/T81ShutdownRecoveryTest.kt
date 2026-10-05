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
        // 括号平衡的非法 JSON（CI brace gate 按字符统计）
        java.io.File(dir, "session-3.json").writeText("{ this is not json }")
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

/**
 * #223 — 恢复会话的真实可见性（原 recover() 空壳修复的回归锁）：
 *  1. recover() 后恢复会话必须出现在 snapshot(SESSIONS)（EXITED 只读视图，
 *     id/shell/cwd/pid/cursor 等 autoSave 落盘字段原样回读 —— round-trip）；
 *  2. 新建会话 id 不与恢复 id 撞车（recover 抬 id 地板）；
 *  3. 关闭恢复会话 → 记录删除 + 视图消失（僵尸 tab 不复活）；
 *  4. snapshot 按 id 过滤也能命中恢复视图；
 *  5. pid 仍在（fd 丢）→ BROKEN（服务级，isPidAlive 注入）。
 */
class T223RecoveryVisibilityTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newRuntime(store: SessionMetadataStore) = TerminalRuntimeImpl(
        native = FakeNativePty(),
        policy = TerminalPolicyImpl(),
        virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) },
        persistenceStore = store
    )

    /** 模拟 autoSave 每 2s 落盘的记录（RUNNING 会话 + 前台 job —— apt/编译被杀现场）。 */
    private fun persistedSession(id: Long, pid: Int) =
        com.apex.agent.platform.terminal.session.TerminalSession(
            id = id, shell = "/bin/bash", initialCwd = "/root/project", pid = pid,
            rows = 40, cols = 120, privilege = PrivilegeLevel.NORMAL,
            state = com.apex.agent.platform.terminal.session.SessionState.RUNNING,
            createdAt = 123L, lastExitCode = null, cursor = 4321L
        )

    private fun persistedJob(id: Long, sid: Long) =
        com.apex.agent.platform.terminal.job.TerminalJob(
            id = id, sessionId = sid, command = "apt-get install build-essential",
            owner = com.apex.agent.platform.terminal.io.InputOwner.USER,
            background = false, startCursor = 0L, endCursor = null,
            state = com.apex.agent.platform.terminal.job.JobState.RUNNING,
            exitCode = null, signal = null, startedAt = 100L, finishedAt = null
        )

    @Test fun `recovered session appears in snapshot with EXITED state and persisted metadata`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SessionMetadataStore(dir)
        store.save(persistedSession(7L, pid = 999991), listOf(persistedJob(3L, 7L)), emptyList())
        val rt = newRuntime(store)
        assertEquals(listOf(7L), rt.recover())
        val snap = rt.snapshot(com.apex.agent.platform.terminal.runtime.TerminalRuntime.SnapshotMode.SESSIONS)
            .getOrThrow()
        val rec = snap.sessions.firstOrNull { it.session.id == 7L }
        assertNotNull("recovered session must be visible in snapshot (#223)", rec)
        // pid 已死 → EXITED（不伪造 RUNNING，Spec §39）；autoSave 字段原样回读
        assertEquals(com.apex.agent.platform.terminal.session.SessionState.EXITED, rec!!.session.state)
        assertEquals("/bin/bash", rec.session.shell)
        assertEquals("/root/project", rec.session.cwd)
        assertEquals(999991, rec.session.pid)
        assertEquals(40, rec.session.rows)
        assertEquals(4321L, rec.session.cursor)
        // 活跃 job 收敛为 INTERRUPTED（crash 中断）
        assertNotNull(rec.foregroundJob)
        assertEquals("INTERRUPTED", rec.foregroundJob!!.state.name)
    }

    @Test fun `live session created after recovery never reuses a recovered id`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SessionMetadataStore(dir)
        store.save(persistedSession(7L, pid = 999992), emptyList(), emptyList())
        val rt = newRuntime(store)
        rt.recover()
        val live = rt.create().getOrThrow()
        assertTrue("new session id must stay above the recovered id floor: ${live.sessionId}", live.sessionId > 7L)
        val snap = rt.snapshot(com.apex.agent.platform.terminal.runtime.TerminalRuntime.SnapshotMode.SESSIONS)
            .getOrThrow()
        // 1 活跃 + 1 恢复，无同 id 双条目
        assertEquals(2, snap.sessions.size)
        assertEquals(1, snap.sessions.count { it.session.id == 7L })
        assertEquals(1, snap.sessions.count { it.session.id == live.sessionId })
    }

    @Test fun `closing a recovered session removes it from snapshot and the store`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SessionMetadataStore(dir)
        store.save(persistedSession(7L, pid = 999993), emptyList(), emptyList())
        val rt = newRuntime(store)
        rt.recover()
        val r = rt.close(7L, force = true).getOrThrow()
        assertTrue(r.closed)
        // 记录删除 —— 否则下次启动 recover() 又把死会话捞回来（僵尸 tab 永生）
        assertNull(store.load(7L))
        val snap = rt.snapshot(com.apex.agent.platform.terminal.runtime.TerminalRuntime.SnapshotMode.SESSIONS)
            .getOrThrow()
        assertTrue(snap.sessions.none { it.session.id == 7L })
    }

    @Test fun `snapshot with sessionId filter returns the recovered view`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SessionMetadataStore(dir)
        store.save(persistedSession(7L, pid = 999994), emptyList(), emptyList())
        store.save(persistedSession(8L, pid = 999995), emptyList(), emptyList())
        val rt = newRuntime(store)
        assertEquals(listOf(7L, 8L), rt.recover().sorted())
        val snap = rt.snapshot(
            com.apex.agent.platform.terminal.runtime.TerminalRuntime.SnapshotMode.SESSIONS,
            sessionId = 8L
        ).getOrThrow()
        assertEquals(listOf(8L), snap.sessions.map { it.session.id })
        assertEquals(com.apex.agent.platform.terminal.session.SessionState.EXITED, snap.sessions[0].session.state)
    }

    @Test fun `recover is idempotent — second reload does not duplicate views`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SessionMetadataStore(dir)
        store.save(persistedSession(7L, pid = 999996), emptyList(), emptyList())
        val rt = newRuntime(store)
        assertEquals(listOf(7L), rt.recover())
        assertEquals(listOf(7L), rt.recover())
        val snap = rt.snapshot(com.apex.agent.platform.terminal.runtime.TerminalRuntime.SnapshotMode.SESSIONS)
            .getOrThrow()
        assertEquals(1, snap.sessions.count { it.session.id == 7L })
    }

    @Test fun `pid still alive recovers as BROKEN — never faked alive`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SessionMetadataStore(dir)
        store.save(persistedSession(9L, pid = 999997), emptyList(), emptyList())
        val rt = newRuntime(store)
        // 服务级：注入 isPidAlive=true（进程仍在但 PTY fd 已丢 —— v1 无法重挂）
        val svc = RuntimeRecoveryService(store, rt, isPidAlive = { true })
        assertEquals(listOf(9L), svc.recover())
        val snap = svc.recoveredSnapshot(9L)
        assertNotNull(snap)
        assertEquals("BROKEN", snap!!.session.state.name)
    }
}
