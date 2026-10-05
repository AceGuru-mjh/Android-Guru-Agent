package com.apex.agent.platform.terminal.screen

import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T94（引擎生命周期收口）：会话关闭/收敛必须释放 VT 引擎。
 *
 * 背景：设备端引擎 NativeVtCore 持有 C++ Engine（屏幕环 + 样式表 + 链接表，
 * 单会话数百 KB native 堆），此前 close/recover/shutdown 全链路无人调用
 * NativeVtCore.close() —— 每关一个会话泄漏一个引擎，VtFeedTrail 的
 * live-engine 计数永不归零（每次退出都被误报「疑似 native 崩溃」）。
 *
 * 本测试用记录型 fake 锁定 [VirtualTerminal.release] 的调用契约：
 *  - close(session) 恰好释放一次；
 *  - 幂等 close 不重复释放（assembly 移除后不再触及）；
 *  - shutdown 对全部存活会话逐一释放；
 *  - 纯 Kotlin 引擎（TerminalCore）上 release 是安全 no-op。
 */
class T94EngineReleaseTest {

    /** 记录型 VT —— 只实现装配链（ObservationEngine/pump）触及的最小面。 */
    private class RecordingVt : VirtualTerminal {
        val releaseCount = java.util.concurrent.atomic.AtomicInteger()
        override fun feed(bytes: ByteArray) {}
        override fun resize(rows: Int, cols: Int) {}
        override fun reset() {}
        override fun release() {
            releaseCount.incrementAndGet()
        }

        override fun snapshot() = TerminalScreenState(
            rows = 24, cols = 80, cursorRow = 0, cursorCol = 0,
            alternateScreen = false, cursorVisible = true,
            title = null, renderedText = "", changedRows = null
        )

        override val cursorRow: Int get() = snapshot().cursorRow
        override val cursorCol: Int get() = snapshot().cursorCol
        override val alternateScreen: Boolean get() = snapshot().alternateScreen
        override val rows: Int get() = snapshot().rows
        override val cols: Int get() = snapshot().cols
    }

    private fun newRuntime(created: MutableList<RecordingVt>): TerminalRuntimeImpl =
        TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { _, _ -> RecordingVt().also(created::add) }
        )

    @Test
    fun `close releases the VT engine exactly once`() = kotlinx.coroutines.runBlocking {
        val created = mutableListOf<RecordingVt>()
        val rt = newRuntime(created)
        val s = rt.create().getOrThrow()
        assertEquals(0, created[0].releaseCount.get())

        rt.close(s.sessionId, force = true).getOrThrow()
        assertEquals(1, created[0].releaseCount.get())

        // 幂等 close：assembly 已移除，不再重复释放
        rt.close(s.sessionId, force = true).getOrThrow()
        assertEquals(1, created[0].releaseCount.get())

        rt.shutdown()
        Unit
    }

    @Test
    fun `shutdown releases engines of all live sessions`() = kotlinx.coroutines.runBlocking {
        val created = mutableListOf<RecordingVt>()
        val rt = newRuntime(created)
        rt.create().getOrThrow()
        rt.create().getOrThrow()

        rt.shutdown().getOrThrow()
        assertEquals(listOf(1, 1), created.map { it.releaseCount.get() })
    }

    @Test
    fun `release on pure-Kotlin engine is a safe idempotent no-op`() {
        // JVM 上 VtEngineFactory 回退 TerminalCore（非 AutoCloseable）→ release
        // 必须静默跳过、不抛异常、可重复调用。真实设备路径（NativeVtCore）由
        // 终点清理链 + 幂等 close 覆盖，无法在 JVM 单测中实例化。
        val vt = RealVirtualTerminal(24, 80)
        vt.feed("hello".toByteArray())
        vt.release()
        vt.release()
        assertEquals(24, vt.rows)
        assertEquals(80, vt.cols)
    }
}
