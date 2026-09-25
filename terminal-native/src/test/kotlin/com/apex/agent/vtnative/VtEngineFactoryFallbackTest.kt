package com.apex.agent.vtnative

import com.apex.agent.terminalemulator.TerminalCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM 回退契约：单测环境没有 libvt_native.so，工厂必须无缝退回纯 Kotlin
 * TerminalCore —— RealVirtualTerminal 的全部既有测试都依赖这一行为。
 */
class VtEngineFactoryFallbackTest {

    @Test
    fun `falls back to TerminalCore when the native library is unavailable`() {
        VtEngineFactory.forceKotlinFallback = false
        val engine = VtEngineFactory.create(5, 10)
        assertNotNull(engine)
        // JVM: no .so → Kotlin backend (sticky after first probe)
        assertTrue(engine is TerminalCore)
    }

    @Test
    fun `forced fallback produces a working engine`() {
        VtEngineFactory.forceKotlinFallback = true
        try {
            val engine = VtEngineFactory.create(3, 8)
            engine.feed("hello".toByteArray())
            engine.drainMutations()
            val snap = engine.snapshot()
            assertEquals(3, snap.rows)
            assertEquals(8, snap.cols)
            assertTrue(snap.renderedText!!.contains("hello"))
        } finally {
            VtEngineFactory.forceKotlinFallback = false
        }
    }
}
