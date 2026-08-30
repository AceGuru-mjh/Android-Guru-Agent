package com.apex.agent.platform.terminal.proot

import org.junit.Assert.*
import org.junit.Test

/**
 * T76: BoundedOutputCapture 单元测试 —— 有界输出截断的正确性。
 */
class BoundedOutputCaptureTest {

    @Test fun `under-budget output is kept verbatim`() {
        val cap = BoundedOutputCapture(budget = 1024)
        cap.append("hello world".toCharArray(), 11)
        assertFalse(cap.truncated)
        assertEquals(11, cap.bytesSeen)
        assertEquals("hello world", cap.snapshot())
    }

    @Test fun `over-budget output triggers truncation`() {
        val cap = BoundedOutputCapture(budget = 100)  // head=50, tail=50
        val big = "A".repeat(200)
        cap.append(big.toCharArray(), 200)
        assertTrue(cap.truncated)
        assertEquals(200, cap.bytesSeen)
        val snap = cap.snapshot()
        // 首部 50 个 A + 截断标记 + 尾部 50 个 A
        assertTrue(snap.startsWith("A".repeat(50)))
        assertTrue(snap.contains("[truncated"))
        assertTrue(snap.endsWith("A".repeat(50)))
    }

    @Test fun `head is fixed after truncation`() {
        val cap = BoundedOutputCapture(budget = 100)  // head=50
        cap.append("HEAD".toCharArray(), 4)
        // 溢出
        val overflow = "B".repeat(200)
        cap.append(overflow.toCharArray(), 200)
        val snap = cap.snapshot()
        assertTrue("head preserved", snap.startsWith("HEAD"))
    }

    @Test fun `tail rolls to keep last N bytes`() {
        val cap = BoundedOutputCapture(budget = 100)  // tail=50
        // 先填满 head（50）触发 truncation
        cap.append("A".repeat(60).toCharArray(), 60)
        assertTrue(cap.truncated)
        // 再写 "XYZ" —— 应出现在尾部
        cap.append("XYZ".toCharArray(), 3)
        val snap = cap.snapshot()
        assertTrue("tail contains last written", snap.endsWith("XYZ"))
    }

    @Test fun `truncation marker includes dropped byte count`() {
        val cap = BoundedOutputCapture(budget = 100)  // head=50, tail=50
        cap.append("A".repeat(300).toCharArray(), 300)
        val snap = cap.snapshot()
        // dropped = 300 - 50(head) - 50(tail) = 200
        assertTrue(snap.contains("[truncated 200 bytes]"))
    }

    @Test fun `empty input is no-op`() {
        val cap = BoundedOutputCapture(budget = 100)
        cap.append(CharArray(0), 0)
        assertFalse(cap.truncated)
        assertEquals(0, cap.bytesSeen)
        assertEquals("", cap.snapshot())
    }

    @Test fun `multiple small appends accumulate`() {
        val cap = BoundedOutputCapture(budget = 1000)
        cap.append("foo".toCharArray(), 3)
        cap.append("bar".toCharArray(), 3)
        cap.append("baz".toCharArray(), 3)
        assertEquals(9, cap.bytesSeen)
        assertEquals("foobarbaz", cap.snapshot())
    }
}

/**
 * T76: BoundedExecution 数据类测试。
 */
class BoundedExecutionTest {
    @Test fun `ok is true when exit 0 and not timed out`() {
        val e = BoundedExecution(
            pid = 1, exitCode = 0, stdout = "", stderr = "",
            stdoutTruncated = false, stderrTruncated = false,
            stdoutBytesCaptured = 0, stderrBytesCaptured = 0,
            durationMs = 100, timedOut = false
        )
        assertTrue(e.ok)
    }

    @Test fun `ok is false when non-zero exit`() {
        val e = BoundedExecution(
            pid = 1, exitCode = 100, stdout = "", stderr = "E: error",
            stdoutTruncated = false, stderrTruncated = false,
            stdoutBytesCaptured = 0, stderrBytesCaptured = 5,
            durationMs = 100, timedOut = false
        )
        assertFalse(e.ok)
    }

    @Test fun `ok is false when timed out`() {
        val e = BoundedExecution(
            pid = 1, exitCode = -1, stdout = "", stderr = "",
            stdoutTruncated = false, stderrTruncated = false,
            stdoutBytesCaptured = 0, stderrBytesCaptured = 0,
            durationMs = 30000, timedOut = true
        )
        assertFalse(e.ok)
    }
}
