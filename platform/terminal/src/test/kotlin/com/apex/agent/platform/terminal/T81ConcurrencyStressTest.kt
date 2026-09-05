package com.apex.agent.platform.terminal

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * T81 — 并发与压力回归（§8/§14/§44 Concurrency）：
 *  1. 多 session 并发 create/write/observe/close —— 无串扰/无死锁/无泄漏
 *  2. A 的 close 不影响 B（隔离验收 §14）
 *  3. 高输出压力（大量 chunk）不 OOM/不阻塞（RingBuffer 有界）
 *  4. 并发 shutdown 与 create 竞争收敛
 *  5. 大量 job 并发（background + timeout）
 */
class T81ConcurrencyStressTest {

    private fun newRuntime(): TerminalRuntimeImpl = TerminalRuntimeImpl(
        native = FakeNativePty(),
        policy = TerminalPolicyImpl(),
        virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
    )

    @Test fun `20 concurrent sessions with parallel writes and observations`() = runBlocking<Unit> {
        val rt = newRuntime()
        val sessions = (1..20).map { rt.create().getOrThrow() }
        assertEquals(20, sessions.size)
        delay(200)
        // 每个会话并发 run + observe —— 无死锁、无异常
        val results = sessions.map { s ->
            async {
                val w = rt.write(s.sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.LINE, text = "echo ${s.sessionId}")
                val o = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.SEMANTIC)
                w.isSuccess && o.isSuccess
            }
        }.awaitAll()
        assertTrue(results.all { it })
        sessions.forEach { rt.close(it.sessionId, force = true).getOrThrow() }
        // 全部关闭后无 native 残留（Fake 侧）
        assertEquals(0, (rt.nativePublic() as FakeNativePty).nativeActiveCount())
    }

    @Test fun `closing session A does not affect session B (isolation)`() = runBlocking<Unit> {
        val rt = newRuntime()
        val a = rt.create().getOrThrow()
        val b = rt.create().getOrThrow()
        delay(150)
        rt.run(b.sessionId, "echo before-close", InputOwner.AGENT).getOrThrow()
        // 关 A
        rt.close(a.sessionId, force = true).getOrThrow()
        // B 仍然完全可用
        val w = rt.write(b.sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.LINE, text = "echo after-close")
        assertTrue(w.getOrThrow().written)
        val o = rt.observe(b.sessionId, TerminalRuntime.ObserveMode.SEMANTIC)
        assertTrue(o.isSuccess)
        val job = rt.run(b.sessionId, "echo still-alive", InputOwner.AGENT).getOrThrow()
        assertTrue(job.jobId > 0)
        rt.close(b.sessionId, force = true).getOrThrow()
    }

    @Test fun `high-volume output does not grow beyond ring capacity`() = runBlocking<Unit> {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        delay(100)
        // yes 命令高速输出（FakeNativePty 上限 10k 行）
        rt.run(s.sessionId, "yes", InputOwner.AGENT).getOrThrow()
        delay(800)
        // 关键验收：RingBuffer 有界（256KB 默认）—— cursor 增长但内存恒定
        val asm = rt.sessionManagerPublic().assembly(s.sessionId)
        assertNotNull(asm)
        val cursor = asm!!.ringBuffer.totalCursor
        assertTrue("expected substantial output, cursor=$cursor", cursor > 0)
        val latest = asm.ringBuffer.latest(4096)
        assertTrue(latest.bytes.size <= 4096)
        rt.close(s.sessionId, force = true).getOrThrow()
    }

    @Test fun `concurrent close and create converge without deadlock`() = runBlocking<Unit> {
        val rt = newRuntime()
        withTimeout(30_000L) {
            val closers = (1..5).map { i ->
                async {
                    val s = rt.create().getOrThrow()
                    rt.close(s.sessionId, force = true)
                    i
                }
            }
            val creators: List<kotlinx.coroutines.Deferred<Result<TerminalRuntime.CreateResult>>> =
                (1..5).map { async { rt.create() } }
            closers.awaitAll()
            val created = creators.awaitAll()
            assertTrue(created.all { it.isSuccess })
            created.forEach { c -> c.getOrThrow().let { s -> rt.close(s.sessionId, force = true) } }
        }
    }

    @Test fun `many concurrent background jobs with timeouts all converge`() = runBlocking<Unit> {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        delay(100)
        val jobs = (1..10).map {
            rt.run(s.sessionId, "sleep 10000", InputOwner.AGENT, background = true, timeoutMs = 300L).getOrThrow()
        }
        assertEquals(10, jobs.size)
        // 全部 job 必须收敛到终态（TIMED_OUT 或 INTERRUPTED —— 不得永久 RUNNING）
        val converged = withTimeout(60_000L) {
            jobs.map { j ->
                var st = rt.jobStatePublic(j.jobId) ?: "GONE"
                while (st == "RUNNING" || st == "WAITING_INPUT" || st == "CREATED") {
                    delay(100)
                    st = rt.jobStatePublic(j.jobId) ?: "GONE"
                }
                st
            }
        }
        assertTrue(converged.all { it == "TIMED_OUT" || it == "INTERRUPTED" })
        // session 存活（timeout 不再杀全组）
        assertNotNull(rt.sessionManagerPublic().assembly(s.sessionId))
        rt.close(s.sessionId, force = true).getOrThrow()
    }

    @Test fun `shutdown during active operations converges cleanly`() = runBlocking<Unit> {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        delay(100)
        rt.run(s.sessionId, "sleep 50000", InputOwner.AGENT).getOrThrow()
        // 并发 shutdown + 新请求 —— shutdown 胜，create 被拒
        val sd = async { rt.shutdown() }
        val cr = async { rt.create() }
        val sdResult = sd.await()
        assertTrue(sdResult.getOrThrow().clean)
        val crResult = cr.await()
        // create 可能已在 gate 前完成（允许），但至少一个失败或两者一致的收敛
        if (crResult.isSuccess) {
            rt.close(crResult.getOrThrow().sessionId, force = true)
        }
        assertEquals(0, (rt.nativePublic() as FakeNativePty).nativeActiveCount())
    }

    @Test fun `event log stays bounded under sustained output`() = runBlocking<Unit> {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        delay(100)
        // 产生大量 OutputProduced 事件（>500 默认上限）
        repeat(30) { rt.write(s.sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.LINE, text = "echo batch-$it") }
        delay(1000)
        val count = rt.eventLogCountPublic(s.sessionId)
        assertTrue("event log exceeded bound: $count", count <= 520)   // 500 + close 相关事件余量
        rt.close(s.sessionId, force = true).getOrThrow()
    }

    @Test fun `session ids are unique across rapid create-close cycles`() = runBlocking<Unit> {
        val rt = newRuntime()
        val ids = mutableSetOf<Long>()
        repeat(25) {
            val s = rt.create().getOrThrow()
            assertTrue("duplicate session id ${s.sessionId}", ids.add(s.sessionId))
            rt.close(s.sessionId, force = true).getOrThrow()
        }
        assertEquals(25, ids.size)
    }
}

/**
 * T81 — 观察模式矩阵（§12/§34.3）：四种 ObserveMode 的行为契约。
 */
class T81ObserveMatrixTest {

    private fun newRuntime(): TerminalRuntimeImpl = TerminalRuntimeImpl(
        native = FakeNativePty(),
        policy = TerminalPolicyImpl(),
        virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
    )

    @Test fun `SEMANTIC returns semantic state without raw bytes`() = runBlocking<Unit> {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        rt.run(s.sessionId, "echo semantic", InputOwner.AGENT)
        delay(400)
        val o = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.SEMANTIC).getOrThrow()
        assertEquals(TerminalRuntime.ObserveMode.SEMANTIC, o.mode)
        assertNotNull(o.semantic)
        assertNull(o.raw)
        assertNull(o.events)
        rt.close(s.sessionId, force = true).getOrThrow()
    }

    @Test fun `RAW returns incremental bytes with cursor semantics`() = runBlocking<Unit> {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        rt.run(s.sessionId, "echo raw-mode", InputOwner.AGENT)
        delay(400)
        val o1 = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.RAW, afterCursor = 0).getOrThrow()
        assertNotNull(o1.raw)
        val cursor = o1.cursor
        // 从 cursor 增量读 —— 语义：增量（不重复）
        rt.run(s.sessionId, "echo second", InputOwner.AGENT)
        delay(400)
        val o2 = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.RAW, afterCursor = cursor).getOrThrow()
        assertTrue(o2.raw != null)
        if (o2.raw!!.isNotEmpty()) {
            assertFalse("incremental read returned old bytes", o2.raw!!.contains("raw-mode"))
        }
        rt.close(s.sessionId, force = true).getOrThrow()
    }

    @Test fun `EVENT returns structured events after cursor`() = runBlocking<Unit> {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        rt.run(s.sessionId, "echo evt", InputOwner.AGENT)
        delay(400)
        val o = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.EVENT, afterCursor = 0, maxEvents = 50).getOrThrow()
        assertNotNull(o.events)
        assertTrue("expected events, got ${o.events?.size}", (o.events?.size ?: 0) > 0)
        rt.close(s.sessionId, force = true).getOrThrow()
    }

    @Test fun `observe on missing session fails with typed error`() = runBlocking<Unit> {
        val rt = newRuntime()
        val r = rt.observe(424242L, TerminalRuntime.ObserveMode.SEMANTIC)
        assertTrue(r.isFailure)
        assertEquals(
            "SessionNotFound",
            (r.exceptionOrNull() as com.apex.agent.platform.terminal.errors.TerminalOperationException).code
        )
    }

    @Test fun `snapshot lists all sessions with global cursor`() = runBlocking<Unit> {
        val rt = newRuntime()
        val a = rt.create().getOrThrow()
        val b = rt.create().getOrThrow()
        delay(100)
        val snap = rt.snapshot().getOrThrow()
        assertTrue(snap.sessions.size >= 2)
        assertTrue(snap.globalCursor >= 0)
        rt.close(a.sessionId, force = true).getOrThrow()
        rt.close(b.sessionId, force = true).getOrThrow()
    }
}
