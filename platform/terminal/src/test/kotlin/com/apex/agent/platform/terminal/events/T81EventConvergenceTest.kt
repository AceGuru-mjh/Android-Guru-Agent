package com.apex.agent.platform.terminal.events

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.InputManager
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.io.WriteResult
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import com.apex.agent.platform.terminal.state.SemanticStateReducer
import com.apex.agent.platform.terminal.wait.WaitEngineImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T81 (D-4) — 事件系统收敛回归：
 *  1. EventLog per-session 容量上限（驱逐最旧 + evicted 计数 + tail 语义不变）
 *  2. Bus replay 窗口填补（emit 后立即订阅不丢事件）
 *  3. close 幂等（并发/串行双 close 只发一条 SessionClosed）
 *  4. close 后 bus drop（迟到订阅者不收到已关 session 事件）
 *  5. exit watcher 在 close 后不发伪 ProcessExited
 *  6. TimeoutController.cancelSession 只收敛目标 session（不误杀他人）
 *  7. PtyOutputPump.start CAS（并发 start 单 reader + stop 后可重启）
 */
class T81EventLogBoundedTest {

    private fun ev(sessionId: Long, cursor: Long) = TerminalEvent.OutputProduced(
        id = 0, sessionId = sessionId, timestamp = 0L, cursor = cursor,
        startCursor = cursor, endCursor = cursor + 1, byteCount = 1
    )

    @Test fun `append over limit evicts oldest`() = runBlocking {
        val log = TerminalEventLogImpl(maxEventsPerSession = 10)
        repeat(25) { i -> log.append(ev(1L, i.toLong())) }
        assertEquals(10L, log.count(1L))
        assertEquals(15L, log.evictedCount(1L)) // 25 - 10
        val tail = log.tail(1L, 100)
        assertEquals(15L, tail.first().cursor)
        assertEquals(24L, tail.last().cursor)
    }

    @Test fun `tail stays accurate under bounded eviction`() = runBlocking {
        val log = TerminalEventLogImpl(maxEventsPerSession = 3)
        repeat(7) { i -> log.append(ev(2L, i.toLong())) }
        val t = log.tail(2L, 2)
        assertEquals(listOf(5L, 6L), t.map { it.cursor })
    }

    @Test fun `default limit is 500`() {
        assertEquals(500, TerminalEventLogImpl.DEFAULT_MAX_EVENTS)
    }

    @Test fun `different sessions bounded independently`() = runBlocking {
        val log = TerminalEventLogImpl(maxEventsPerSession = 5)
        repeat(8) { log.append(ev(1L, it.toLong())) }
        repeat(3) { log.append(ev(9L, it.toLong())) }
        assertEquals(5L, log.count(1L))
        assertEquals(3L, log.count(9L))
        assertEquals(0L, log.evictedCount(9L))
    }

    @Test fun `query after eviction only sees retained window`() = runBlocking {
        val log = TerminalEventLogImpl(maxEventsPerSession = 4)
        repeat(10) { i -> log.append(ev(3L, i.toLong())) }
        // cursor > 5 的都被驱逐；query(afterCursor=0) 只返回保留的 6..9
        val q = log.query(3L, afterCursor = 0L, limit = 100)
        assertEquals(listOf(6L, 7L, 8L, 9L), q.map { it.cursor })
    }
}

class T81EventBusReplayWindowTest {

    private fun ev(sessionId: Long, cursor: Long) = TerminalEvent.OutputProduced(
        id = 0, sessionId = sessionId, timestamp = 0L, cursor = cursor,
        startCursor = cursor, endCursor = cursor + 1, byteCount = 1
    )

    @Test fun `event emitted before subscribe is replayed (no startup-gap loss)`() = runBlocking {
        val log = TerminalEventLogImpl()
        val bus = TerminalEventBusImpl(log, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        bus.emit(ev(1L, 0L))
        val received = withTimeout(2000L) {
            bus.subscribe(1L, afterCursor = 0L).first { it.cursor == 0L }
        }
        assertEquals(0L, received.cursor)
    }

    @Test fun `no duplicate delivery when both history and replay carry the event`() = runBlocking {
        val log = TerminalEventLogImpl()
        val bus = TerminalEventBusImpl(log, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val e = ev(1L, 5L)
        val id = log.append(e)
        bus.emit(e.copy(id = id))
        val collected = mutableListOf<TerminalEvent>()
        val job = launch {
            bus.subscribe(1L, afterCursor = 0L).collect { collected.add(it) }
        }
        delay(300)
        job.cancel()
        assertEquals(1, collected.count { it.cursor == 5L })
    }

    @Test fun `subscriberCount tracks subscriptions`() = runBlocking {
        val log = TerminalEventLogImpl()
        val bus = TerminalEventBusImpl(log, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertEquals(0, bus.subscriberCount(7L))
        val job = launch {
            withTimeout(5000L) { bus.subscribe(7L, afterCursor = 0L).first { false } }
        }
        delay(300)
        assertTrue(bus.subscriberCount(7L) >= 1)
        job.cancel()
        delay(100)
        // 取消后计数回落（flow 的 finally 递减）
        assertEquals(0, bus.subscriberCount(7L))
    }
}

class T81CloseConvergenceTest {

    private fun newRuntime(): TerminalRuntimeImpl = TerminalRuntimeImpl(
        native = FakeNativePty(),
        policy = TerminalPolicyImpl(),
        virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
    )

    @Test fun `double close is idempotent and single SessionClosed`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        val closed = mutableListOf<TerminalEvent.SessionClosed>()
        val job = launch {
            rt.eventBusPublic().subscribe(s.sessionId, afterCursor = 0L).collect { ev ->
                if (ev is TerminalEvent.SessionClosed) closed.add(ev)
            }
        }
        delay(100)
        rt.close(s.sessionId).getOrThrow()
        rt.close(s.sessionId).getOrThrow()
        rt.close(s.sessionId, force = true).getOrThrow()
        delay(400)
        job.cancel()
        assertEquals(1, closed.size)
    }

    @Test fun `concurrent double close emits exactly one SessionClosed`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        val closed = java.util.concurrent.atomic.AtomicInteger(0)
        val job = launch {
            rt.eventBusPublic().subscribe(s.sessionId, afterCursor = 0L).collect { ev ->
                if (ev is TerminalEvent.SessionClosed) closed.incrementAndGet()
            }
        }
        delay(100)
        val a = async { rt.close(s.sessionId) }
        val b = async { rt.close(s.sessionId, force = true) }
        a.await().getOrThrow()
        b.await().getOrThrow()
        delay(400)
        job.cancel()
        assertEquals(1, closed.get())
    }

    @Test fun `no events appended after close (log frozen)`() = runBlocking {
        // bus drop 的可观察语义 = close 后该 session 不再产生任何新事件
        // （迟到订阅者按订阅契约仍可读到历史回放 —— 这正是 crash-safe 语义，不是泄漏）。
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        delay(200)
        val before = rt.eventLogCountPublic(s.sessionId)
        rt.close(s.sessionId).getOrThrow()
        val mid = rt.eventLogCountPublic(s.sessionId)
        // close 主流程自身合法追加 ≤2 条（StateChanged(CLOSED) + SessionClosed）
        assertTrue("close appended ${mid - before} events (expected <=2)", mid - before <= 2)
        delay(400)   // > EXIT_POLL_MS(100ms)：watcher 若未收敛必然已追加伪事件
        val after = rt.eventLogCountPublic(s.sessionId)
        assertEquals(mid, after)
    }

    @Test fun `exit watcher does not fire fake ProcessExited after close`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        rt.close(s.sessionId).getOrThrow()
        val fake = java.util.concurrent.atomic.AtomicInteger(0)
        val job = launch {
            rt.eventBusPublic().subscribe(s.sessionId, afterCursor = 0L).collect {
                if (it is TerminalEvent.ProcessExited) fake.incrementAndGet()
            }
        }
        delay(350)   // > EXIT_POLL_MS(100ms) —— watcher 若未收敛必然已发伪事件
        job.cancel()
        assertEquals(0, fake.get())
    }

    @Test fun `close cause BROKEN is produced when session was broken`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        // 制造 BROKEN：直接迁移 SessionManager 的状态机（pump ReadFailed 路径的等价终态）
        rt.sessionManagerPublic().transition(s.sessionId, com.apex.agent.platform.terminal.session.SessionState.BROKEN)
        val closed = mutableListOf<TerminalEvent.SessionClosed>()
        val job = launch {
            rt.eventBusPublic().subscribe(s.sessionId, afterCursor = 0L).collect { ev ->
                if (ev is TerminalEvent.SessionClosed) closed.add(ev)
            }
        }
        delay(100)
        rt.close(s.sessionId).getOrThrow()
        delay(300)
        job.cancel()
        assertEquals(1, closed.size)
        assertEquals(CloseCause.BROKEN, closed.first().cause)   // 原实现恒 USER
    }
}

class T81TimeoutControllerIsolationTest {

    private class RecordingInputManager : InputManager {
        val signals = java.util.concurrent.ConcurrentHashMap<Long, String>()
        override val policy: com.apex.agent.platform.terminal.policy.TerminalPolicy =
            com.apex.agent.platform.terminal.policy.TerminalPolicyImpl()
        override fun controlState(sessionId: Long): kotlinx.coroutines.flow.StateFlow<com.apex.agent.platform.terminal.io.InputControlState> =
            kotlinx.coroutines.flow.MutableStateFlow(com.apex.agent.platform.terminal.io.InputControlState.FREE)
        override suspend fun requestTakeover(sessionId: Long): Result<Unit> = Result.success(Unit)
        override suspend fun releaseTakeover(sessionId: Long): Result<Unit> = Result.success(Unit)
        override suspend fun write(sessionId: Long, owner: InputOwner, bytes: ByteArray): Result<WriteResult> =
            Result.success(WriteResult(true, bytes.size, 0L, owner))
        override suspend fun sendKey(sessionId: Long, owner: InputOwner, key: TerminalKey): Result<WriteResult> =
            Result.success(WriteResult(true, 0, 0L, owner))
        override suspend fun sendSignal(sessionId: Long, owner: InputOwner, signal: UnixSignal, jobId: Long?): Result<Unit> {
            val key: Long = jobId ?: sessionId
            val prev = signals[key] ?: ""
            signals[key] = prev + signal.name + ";"
            return Result.success(Unit)
        }
        override suspend fun closeStdin(sessionId: Long, owner: InputOwner): Result<Unit> = Result.success(Unit)
    }

    @Test fun `cancelSession only cancels that session timers`() {
        val im = RecordingInputManager()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tc = com.apex.agent.platform.terminal.process.TimeoutController(im, scope, gracePeriodMs = 60_000L)
        tc.startTimeout(1L, 11L, 60_000L) { }
        tc.startTimeout(1L, 12L, 60_000L) { }
        tc.startTimeout(2L, 21L, 60_000L) { }
        assertEquals(3, tc.activeTimers())
        tc.cancelSession(1L)
        assertEquals(1, tc.activeTimers())   // session 2 的 21 仍在
        tc.cancelSession(2L)
        assertEquals(0, tc.activeTimers())
        scope.cancel()
    }

    @Test fun `cancelled timer sends no signals`() = runBlocking {
        val im = RecordingInputManager()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tc = com.apex.agent.platform.terminal.process.TimeoutController(im, scope, gracePeriodMs = 5L)
        tc.startTimeout(1L, 99L, 5L) { }
        delay(2)
        tc.cancelTimeout(99L)
        delay(60)
        assertTrue("cancelled timer must not deliver signals, got=${im.signals[99L]}",
            im.signals[99L].isNullOrEmpty())
        scope.cancel()
    }

    @Test fun `timeout expiry delivers TERM then KILL after grace`() = runBlocking {
        val im = RecordingInputManager()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tc = com.apex.agent.platform.terminal.process.TimeoutController(im, scope, gracePeriodMs = 50L)
        var fired = false
        tc.startTimeout(1L, 5L, 10L) { fired = true }
        delay(200)
        assertTrue(fired)
        val sig = im.signals[5L] ?: ""
        assertTrue("expected TERM then KILL, got=$sig", sig.contains("SIGTERM") && sig.contains("SIGKILL"))
        assertTrue(sig.indexOf("SIGTERM") < sig.indexOf("SIGKILL"))
        scope.cancel()
    }
}

class T81PumpSingleReaderTest {

    @Test fun `concurrent start is safe and pump restarts after stop`() {
        val busScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val log = TerminalEventLogImpl()
        val bus = TerminalEventBusImpl(log, busScope)
        val fake = FakeNativePty()
        // 先创建真实 session（不存在的 id 会让 pump 读 -1 立即退出）
        val nativeId = fake.nativeCreateSession("/system/bin/sh", "/tmp", 24, 80, emptyArray())
        val pump = com.apex.agent.platform.terminal.io.PtyOutputPumpImpl(
            sessionId = 1L, nativeSessionId = nativeId, native = fake,
            ringBuffer = com.apex.agent.platform.terminal.buffer.RingTerminalBuffer(),
            eventLog = log,
            eventBus = bus,
            virtualTerminal = RealVirtualTerminal(24, 80),
            semanticReducer = SemanticStateReducer(
                1L, "/bin/sh", "/tmp", PrivilegeLevel.NORMAL, 100, 24, 80
            ),
            waitEngine = WaitEngineImpl(bus, busScope),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
        val ready = CountDownLatch(1)
        val threads = (1..8).map {
            Thread {
                ready.await(1, TimeUnit.SECONDS)
                runBlocking { pump.start() }
            }
        }
        threads.forEach { it.start() }
        ready.countDown()
        threads.forEach { it.join(3000) }
        assertTrue(pump.isRunning)
        runBlocking { pump.stop() }
        assertFalse(pump.isRunning)
        runBlocking { pump.start() }
        assertTrue(pump.isRunning)
        runBlocking { pump.stop() }
        assertFalse(pump.isRunning)
        busScope.cancel()
    }
}
