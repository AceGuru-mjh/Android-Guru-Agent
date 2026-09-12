package com.apex.agent.platform.terminal.protocol

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * T82 — Shell Marker Protocol（真实退出码）回归矩阵。
 *
 * 覆盖：wrapCommand 文本契约（printf 八进制在 bash/mksh 可移植、readline 安全）、
 * parser 的 chunk 边界（marker 拆 3 段）、tracker 查询、JobManager 的 marker
 * 完成路径（EXITED + 真实退出码），以及**全链路**（FakeNativePty 模拟 bash 的
 * printf 行为 → pump → runtime listener 解析 → JobManager 终态）。
 */
class ShellMarkerProtocolTest {

    // ─── wrapCommand 文本契约 ───

    @Test fun `wrapCommand produces portable printf with jobId and exit placeholder`() {
        val wrapped = ShellMarkers.wrapCommand(7L, "echo hi")
        // 语义：`; printf '\033]633;APEX;j=7;e=%d\007' 7 $?`
        assertEquals("echo hi; printf '\\033]633;APEX;j=7;e=%d\\007' 7 \$?", wrapped)
        // 行内只有可打印 ASCII（readline 不破坏）—— ESC 只以 printf 八进制文本出现
        assertFalse(wrapped.contains('\u001B'))
        assertFalse(wrapped.contains('\u0007'))
        assertFalse(wrapped.contains('\n'))
    }

    @Test fun `wrapCommand keeps blank command unchanged`() {
        assertEquals("", ShellMarkers.wrapCommand(1L, ""))
        assertEquals("   ", ShellMarkers.wrapCommand(1L, "   "))
    }

    // ─── parser ───

    @Test fun `parser extracts marker in one chunk`() {
        val out = mutableListOf<ShellMarker>()
        val p = ShellMarkerParser()
        val frame = "\u001B]633;APEX;j=3;e=42\u0007"
        out += p.feed(frame.toByteArray(), 100L)
        assertEquals(1, out.size)
        assertEquals(3L, out[0].jobId)
        assertEquals(42, out[0].exitCode)
        assertEquals(100L + frame.length, out[0].atCursor)
    }

    @Test fun `parser survives marker split across three chunks`() {
        val out = mutableListOf<ShellMarker>()
        val p = ShellMarkerParser()
        val frame = "\u001B]633;APEX;j=12;e=137\u0007"
        val t = frame.toByteArray()
        val third = t.size / 3
        out += p.feed(t.copyOfRange(0, third), 0L)
        assertTrue(out.isEmpty())
        out += p.feed(t.copyOfRange(third, 2 * third), third.toLong())
        assertTrue(out.isEmpty())
        out += p.feed(t.copyOfRange(2 * third, t.size), (2 * third).toLong())
        assertEquals(1, out.size)
        assertEquals(137, out[0].exitCode)
    }

    @Test fun `parser accepts ST terminator and ignores unrelated OSC`() {
        val out = mutableListOf<ShellMarker>()
        val p = ShellMarkerParser()
        out += p.feed("\u001B]0;title\u0007noise\u001B]633;APEX;j=1;e=0\u001B\\".toByteArray(), 0L)
        assertEquals(1, out.size)
        assertEquals(0, out[0].exitCode)
    }

    @Test fun `parser carry stays bounded under garbage flood`() {
        val p = ShellMarkerParser()
        val garbage = "x".repeat(2000).toByteArray()
        repeat(50) { assertTrue(p.feed(garbage, 0L).isEmpty()) }
    }

    // ─── tracker ───

    @Test fun `tracker resolves only markers after job start cursor`() {
        val t = ShellMarkerTracker()
        t.record(ShellMarker(5L, 0, 10L))
        t.record(ShellMarker(5L, 99, 200L))
        assertNull(t.resolve(5L, 250L))                       // after both
        assertEquals(99, t.resolve(5L, 100L)?.exitCode)       // newest after start
        assertNull(t.resolve(6L, 0L))                          // other job
    }

    // ─── JobManager marker completion（单元级）───

    @Test fun `onShellMarker emits ProcessExited with real exit code`() = runBlocking<Unit> {
        val (jm, sid) = newJobManager()
        jm.startJob(sid, "echo ok", InputOwner.AGENT, false, 0L)
        val jobId = jm.foregroundJobId(sid)!!
        jm.onShellMarker(sid, ShellMarker(jobId, 17, 1000L))
        val job = jm.get(jobId)!!
        assertEquals("EXITED", job.state.name)
        assertEquals(17, job.exitCode)
    }

    @Test fun `onShellMarker is a no-op for terminal jobs (timeout keeps its state)`() = runBlocking<Unit> {
        val (jm, sid) = newJobManager()
        jm.startJob(sid, "sleep 60", InputOwner.AGENT, false, 0L)
        val jobId = jm.foregroundJobId(sid)!!
        // Simulate timeout finalization first
        jm.onEvent(
            com.apex.agent.platform.terminal.events.TerminalEvent.ProcessExited(
                id = 1, sessionId = sid, timestamp = 0, cursor = -1, jobId = jobId, pid = 0,
                exitCode = 137, signal = null, cause = com.apex.agent.platform.terminal.events.ExitCause.TIMEOUT
            )
        )
        jm.onShellMarker(sid, ShellMarker(jobId, 0, 1000L))
        val job = jm.get(jobId)!!
        assertEquals("TIMED_OUT", job.state.name)   // first terminal state wins
    }

    // ─── 全链路（fake PTY 模拟 bash printf marker）───

    @Test fun `runtime end-to-end marker completion carries real exit code`() = runBlocking<Unit> {
        val rt = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) },
            enableShellMarkers = true
        )
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(150)   // fake shell prompt ready
        val run = rt.run(s.sessionId, "false", InputOwner.AGENT, background = false, timeoutMs = 10_000).getOrThrow()
        // fake: `false` → exit 1 → marker e=1 → job EXITED(1)（marker 路径，非 prompt 合成）
        val final = withTimeout(20_000) {
            var st = "RUNNING"
            while (st == "RUNNING" || st == "WAITING_INPUT" || st == "CREATED") {
                kotlinx.coroutines.delay(50)
                st = rt.jobStatePublic(run.jobId) ?: "GONE"
            }
            st
        }
        assertEquals("EXITED", final)
        assertEquals(1, rt.jobPublic(run.jobId)?.exitCode)
        rt.close(s.sessionId, force = true).getOrThrow()
        rt.shutdown()
    }

    @Test fun `markers disabled keeps legacy prompt-heuristic zero exit code`() = runBlocking<Unit> {
        val rt = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) },
            enableShellMarkers = false
        )
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(150)
        val run = rt.run(s.sessionId, "false", InputOwner.AGENT, background = false, timeoutMs = 10_000).getOrThrow()
        val final = withTimeout(20_000) {
            var st = "RUNNING"
            while (st == "RUNNING" || st == "WAITING_INPUT" || st == "CREATED") {
                kotlinx.coroutines.delay(50)
                st = rt.jobStatePublic(run.jobId) ?: "GONE"
            }
            st
        }
        assertEquals("EXITED", final)
        // 历史行为：合成路径的 exitCode=0（正是 T82 要修的假 0 —— 这里锁定行为边界）
        assertEquals(0, rt.jobPublic(run.jobId)?.exitCode)
        rt.close(s.sessionId, force = true).getOrThrow()
        rt.shutdown()
    }

    // ─── helpers ───

    /** 真实 SessionManagerImpl + LocalShellBackend spawn 的会话（JobManager 单元级路径）。 */
    private fun newJobManager(): Pair<com.apex.agent.platform.terminal.job.JobManagerImpl, Long> {
        val fake = FakeNativePty()
        val eventLog = com.apex.agent.platform.terminal.events.TerminalEventLogImpl()
        val eventBus = com.apex.agent.platform.terminal.events.TerminalEventBusImpl(
            eventLog, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )
        val waitEngine = com.apex.agent.platform.terminal.wait.WaitEngineImpl(
            eventBus, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )
        val input = com.apex.agent.platform.terminal.io.InputManagerImpl(
            com.apex.agent.platform.terminal.policy.TerminalPolicyImpl(), fake, eventLog, eventBus
        )
        val sm = com.apex.agent.platform.terminal.session.SessionManagerImpl(
            fake, eventLog, eventBus, waitEngine, input,
            { r, c -> RealVirtualTerminal(r, c) },
            com.apex.agent.platform.terminal.policy.TerminalPolicyImpl()
        )
        input.nativeIdResolver = { sid -> sm.assembly(sid)?.nativeSessionId }
        val session: com.apex.agent.platform.terminal.session.TerminalSession = runBlocking {
            val spec = com.apex.agent.platform.terminal.runtime.LocalShellBackend()
                .prepare(com.apex.agent.platform.terminal.runtime.SessionSpawnRequest(cwd = "/sdcard", rows = 24, cols = 80))
                .getOrThrow()
            sm.createFromSpec(spec, 24, 80, com.apex.agent.platform.terminal.policy.PrivilegeLevel.NORMAL)
                .getOrThrow()
        }
        val jm = com.apex.agent.platform.terminal.job.JobManagerImpl(
            sessionManager = sm,
            inputManager = input,
            eventLog = eventLog,
            eventBus = eventBus,
            enableShellMarkers = true
        )
        // 模拟 runtime 的 session listener：事件 → JobManager.onEvent
        //（生产路径由 TerminalRuntimeImpl.startSessionListener 提供该分发）。
        val listenerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        listenerScope.launch {
            eventBus.subscribe(session.id, afterCursor = 0L)
                .takeWhile { ev -> ev !is com.apex.agent.platform.terminal.events.TerminalEvent.SessionClosed }
                .collect { ev -> jm.onEvent(ev) }
        }
        return jm to session.id
    }
}
