package com.apex.agent.platform.terminal.buffer

import org.junit.Assert.*
import org.junit.Test

/**
 * T81 (D-6) — UTF-8 窗口边界解码回归。
 *
 * 问题：按字节窗口（recentOutput / observe RAW）直接 toString(UTF_8)，
 * 窗口起点落在多字节序列中间 → 每个窗口都产生 U+FFFD 乱码。
 */
class T81Utf8BoundaryTest {

    @Test fun `window starting mid-sequence skips incomplete prefix`() {
        val full = "你好".toByteArray(Charsets.UTF_8)   // E4 BD A0 E5 A5 BD
        val window = full.copyOfRange(1, full.size)     // BD A0 E5 A5 BD（残缺 + 完整"好"）
        val decoded = Utf8Boundary.decodeWindow(window)
        assertEquals("好", decoded)
        assertFalse(decoded.contains("￿"))
    }

    @Test fun `aligned window decodes identically to plain toString`() {
        val text = "hello 世界 ünïcødé ✅"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, Utf8Boundary.decodeWindow(bytes))
    }

    @Test fun `empty window returns empty string`() {
        assertEquals("", Utf8Boundary.decodeWindow(ByteArray(0)))
    }

    @Test fun `pure binary window with leading continuation bytes is handled (no crash)`() {
        // 头部 80 81 是「后继字节」形态（残缺前缀语义）→ 跳过；其余 ASCII 原样保留
        val bin = byteArrayOf(-0x80, -0x7F, 0x00, 0x01, 0x7F)
        val out = Utf8Boundary.decodeWindow(bin)
        val expected = String(byteArrayOf(0x00, 0x01, 0x7F), Charsets.UTF_8)
        assertEquals(expected, out)
    }

    @Test fun `binary window with clean head decodes fully (no silent drop)`() {
        // 头部是合法 ASCII —— 不跳过任何字节；中间的非法字节按解码器默认替换
        val bin = byteArrayOf(0x00, 0x01, -0x80, -0x7F, 0x7F)
        val out = Utf8Boundary.decodeWindow(bin)
        assertEquals(5, out.length)   // 5 个码位（含 2 个 U+FFFD —— 与旧行为一致的容错）
    }

    @Test fun `slice overload respects offset and length`() {
        val bytes = "abcdef你".toByteArray(Charsets.UTF_8)
        assertEquals("abc", Utf8Boundary.decodeWindow(bytes, 0, 3))
        assertEquals("", Utf8Boundary.decodeWindow(bytes, 0, 0))
        assertEquals("", Utf8Boundary.decodeWindow(bytes, -1, 2))
    }

    @Test fun `multi-byte char spanning two reads produces no replacement chars in second window`() {
        val all = "a你b".toByteArray(Charsets.UTF_8)   // 61 E4 BD A0 62
        val second = all.copyOfRange(3, all.size)      // A0 62（"你"的尾字节 + "b"）
        val d2 = Utf8Boundary.decodeWindow(second)
        assertEquals("b", d2)
        assertFalse(d2.contains("￿"))
    }
}
