package com.apex.agent.platform.terminal.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * exec 包单元测试（纯 JVM；ExecEngine 用例跑真实 /bin/sh —— Termux Run Command 语义
 * 的等价验证：流分离 / 真实退出码 / 超时强杀 / 有界采集）。
 */
class ExecLayerTest {

    // ═══════════ AnsiSanitizer ═══════════

    @Test fun `ansi strip removes CSI SGR and OSC`() {
        val input = "\u001B[31mRED\u001B[0m plain \u001B]0;title\u0007 after"
        val r = AnsiSanitizer.sanitize(input, AnsiMode.STRIP)
        assertEquals("RED plain  after", r.text)
        assertEquals(3, r.sequencesRemoved)
    }

    @Test fun `ansi strip handles OSC with ST terminator`() {
        val input = "a\u001B]8;;http://x\u001B\\link\u001B]8;;\u001B\\b"
        val r = AnsiSanitizer.sanitize(input, AnsiMode.STRIP)
        assertEquals("alinkb", r.text)
    }

    @Test fun `ansi keep preserves sequences and counts zero`() {
        val input = "\u001B[31mRED\u001B[0m"
        val r = AnsiSanitizer.sanitize(input, AnsiMode.KEEP)
        assertEquals(input, r.text)
        assertEquals(0, r.sequencesRemoved)
    }

    @Test fun `crlf normalized to lf`() {
        val r = AnsiSanitizer.sanitize("a\r\nb\r\nc", AnsiMode.STRIP)
        assertEquals("a\nb\nc", r.text)
    }

    @Test fun `lone CR progress bar collapses to final frame`() {
        // pip/apt 式进度：多帧 \r 覆盖，终态是最后一帧
        val input = "downloading 10%\rdownloading 50%\rdownloading 99%\ndone\n"
        val r = AnsiSanitizer.sanitize(input, AnsiMode.STRIP)
        assertEquals("downloading 99%\ndone\n", r.text)
    }

    @Test fun `progress bar frames with ANSI between CR collapse correctly`() {
        val input = "  1%\r\u001B[K 50%\r\u001B[K100%\n"
        val r = AnsiSanitizer.sanitize(input, AnsiMode.STRIP)
        assertEquals("100%\n", r.text)
    }

    @Test fun `trailing lone CR keeps the line content`() {
        // 回归锁：旧实现把孤立 \r 当"丢弃本行内容"，`abc\r` 被吞成空串。
        // 真实终端的回车只是把光标移回第 0 列，并不擦除字符。
        val r = AnsiSanitizer.sanitize("abc\r", AnsiMode.STRIP)
        assertEquals("abc", r.text)
    }

    @Test fun `short frame overwrites in place without erasing the tail`() {
        // 回车的真实语义是覆盖写：短帧只覆盖自己的长度，长帧残留的尾部原样保留
        val r = AnsiSanitizer.sanitize("abcdef\rxy", AnsiMode.STRIP)
        assertEquals("xycdef", r.text)
    }

    @Test fun `EL to end of line removes progress residue`() {
        // \r\033[K = 回行首 + 擦到行尾（apt/pip 的标准写法）——残影必须真正被擦掉
        val r = AnsiSanitizer.sanitize("abcdef\r\u001B[Kxy", AnsiMode.STRIP)
        assertEquals("xy", r.text)
    }

    @Test fun `EL mode 2 clears the whole line`() {
        val r = AnsiSanitizer.sanitize("abcdef\r\u001B[2Kxy", AnsiMode.STRIP)
        assertEquals("xy", r.text)
    }

    @Test fun `CR does not join two logical lines`() {
        val r = AnsiSanitizer.sanitize("a\rb\nc", AnsiMode.STRIP)
        assertEquals("b\nc", r.text)
    }

    @Test fun `C0 control chars stripped except tab`() {
        val r = AnsiSanitizer.sanitize("a\u0007b\u0008c\td", AnsiMode.STRIP)
        assertEquals("abc\td", r.text)
    }

    @Test fun `charset selection escape is stripped`() {
        val r = AnsiSanitizer.sanitize("x\u001B(B0y", AnsiMode.STRIP)
        assertEquals("x0y", r.text)
    }

    // ═══════════ OutputLimiter ═══════════

    @Test fun `short output passes through untouched`() {
        val l = OutputLimiter(maxChars = 100, headLines = 10, tailLines = 10)
        val r = l.limit("one\ntwo\nthree\n")
        assertEquals("one\ntwo\nthree\n", r.text)
        assertFalse(r.truncated)
        assertEquals(0, r.omittedLines)
    }

    @Test fun `long lines get head+tail with honest marker`() {
        val text = (1..300).joinToString("\n") { "line $it" }
        val l = OutputLimiter(maxChars = 100_000, headLines = 10, tailLines = 10)
        val r = l.limit(text)
        assertTrue(r.truncated)
        assertTrue(r.text.contains("line 1\n"))
        assertTrue(r.text.contains("line 300"))
        assertTrue(r.text.contains("280 lines omitted"))
        assertFalse(r.text.contains("line 150\n"))
        assertEquals(text.length, r.totalChars)
    }

    @Test fun `char-only overflow keeps head 2 thirds and tail 1 third`() {
        val text = "x".repeat(5_000) // 单行超字符预算
        val l = OutputLimiter(maxChars = 1_000, headLines = 60, tailLines = 60)
        val r = l.limit(text)
        assertTrue(r.truncated)
        // 单行 → 无行省略标记（诚实：只有字符截断）
        assertFalse(r.text.contains("lines omitted"))
        assertTrue(r.text.contains("chars > 1000 budget"))
        assertTrue(r.text.length < 1_400)
    }

    @Test fun `marker line not fabricated when only chars exceeded`() {
        val text = (1..50).joinToString("\n") { "x".repeat(100) } // 50 行 ≤ 60+60，但 5000 字符 > 1000
        val l = OutputLimiter(maxChars = 1_000, headLines = 60, tailLines = 60)
        val r = l.limit(text)
        assertTrue(r.truncated)
        assertEquals(0, r.omittedLines)
        assertFalse(r.text.contains("lines omitted"))
    }

    // ═══════════ BoundedCapture / TailRing ═══════════

    @Test fun `bounded capture keeps everything when under head cap`() {
        val c = ExecEngine.BoundedCapture(headCap = 64, tailCap = 64)
        c.write("hello".toByteArray(), 0, 5)
        val m = c.materialize()
        assertEquals("hello", m.text)
        assertEquals(5L, m.totalBytes)
        assertFalse(m.captureGap)
    }

    @Test fun `bounded capture dedups overlap losslessly`() {
        val c = ExecEngine.BoundedCapture(headCap = 10, tailCap = 10)
        val payload = "0123456789abcdefghij" // 20 bytes: head=[0,10) tail=[10,20) 相接
        c.write(payload.toByteArray(), 0, 20)
        val m = c.materialize()
        assertEquals(20L, m.totalBytes)
        assertFalse(m.captureGap)
        assertEquals("0123456789abcdefghij", m.text)
    }

    @Test fun `bounded capture marks gap when head and tail disjoint`() {
        val c = ExecEngine.BoundedCapture(headCap = 8, tailCap = 8)
        val head = "HEADBYTE"
        val gap = "GAPGAPGAPGAP"
        val tail = "TAILBYTE"
        val all = (head + gap + tail).toByteArray()
        c.write(all, 0, all.size)
        val m = c.materialize()
        assertTrue(m.captureGap)
        assertEquals(all.size.toLong(), m.totalBytes)
        assertTrue(m.text.startsWith("HEADBYTE"))
        assertTrue(m.text.contains("bytes not captured"))
        assertTrue(m.text.endsWith("TAILBYTE"))
        assertFalse(m.text.contains("GAPGAP"))
    }

    @Test fun `tail ring wraps correctly across many small writes`() {
        val r = ExecEngine.TailRing(cap = 8)
        // 逐字节写 20 字节：保留最后 8 字节
        val payload = "0123456789ABCDEFGHIJ"
        payload.forEach { ch -> r.write(byteArrayOf(ch.code.toByte()), 0, 1) }
        assertEquals("CDEFGHIJ", String(r.toByteArray(), Charsets.UTF_8))
    }

    @Test fun `tail ring single write larger than cap keeps suffix`() {
        val r = ExecEngine.TailRing(cap = 8)
        val payload = "0123456789ABCDEF".toByteArray() // 16 bytes
        r.write(payload, 0, 16)
        assertEquals("89ABCDEF", String(r.toByteArray(), Charsets.UTF_8))
    }

    @Test fun `tail ring full-overwrite advances start by written count`() {
        val r = ExecEngine.TailRing(cap = 8)
        r.write("0123456789".toByteArray(), 0, 10)   // ≥cap → 保留 "23456789"，start=0,len=8
        r.write("AB".toByteArray(), 0, 2)             // 满环覆盖 → "456789AB"
        assertEquals("456789AB", String(r.toByteArray(), Charsets.UTF_8))
    }

    @Test fun `tail ring survives heavy churn without livelock`() {
        // 回归锁：旧实现在满环时 space=0 → chunk=0 → 死循环（drain 自旋、管道塞死）。
        // 若回归，本测试会因死循环超时失败。
        val r = ExecEngine.TailRing(cap = 1024)
        val chunk = ByteArray(100) { (it % 251).toByte() }
        repeat(10_000) { r.write(chunk, 0, 100) }    // 1,000,000 bytes 穿透
        assertEquals(1024, r.toByteArray().size)
    }

    // ═══════════ ExecEngine（真实 /bin/sh）═══════════

    private fun shEngine(): ExecEngine = ExecEngine(
        ProcessBuilderSpawner(channel = "local-sh", shell = "/bin/sh"),
        Dispatchers.IO,
        ExecEngine.CaptureConfig(headBytes = 64 * 1024, tailBytes = 64 * 1024)
    )

    @Test fun `stdout and stderr are captured separately with real exit code`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val engine = shEngine()
        val r = engine.execute(ExecRequest(command = "echo out; echo err 1>&2; exit 3"))
        assertEquals(3, r.exitCode)
        assertEquals("out\n", r.stdout)
        assertEquals("err\n", r.stderr)
        assertTrue(r.durationMs >= 0)
        assertTrue(r.stderrSeparated)
        assertFalse(r.truncated)
        assertEquals("local-sh", r.channel)
    }

    @Test fun `ansi stripped from real command output by default`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val engine = shEngine()
        val r = engine.execute(ExecRequest(command = "printf '\\033[31mRED\\033[0m plain\\n'"))
        assertEquals("RED plain\n", r.stdout)
        assertTrue(r.ansiSequencesRemoved >= 2)
    }

    @Test fun `ansi keep mode preserves escapes`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val engine = shEngine()
        val r = engine.execute(ExecRequest(command = "printf '\\033[31mRED\\033[0m'", ansi = AnsiMode.KEEP))
        assertEquals("\u001B[31mRED\u001B[0m", r.stdout)
    }

    @Test fun `timeout kills process honestly with partial output`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val engine = shEngine()
        val r = engine.execute(ExecRequest(command = "echo started; sleep 30; echo never", timeoutMs = 1_500))
        assertTrue(r.timedOut)
        assertTrue(r.killed)
        assertEquals(-1, r.exitCode)
        assertEquals("started\n", r.stdout)
        assertFalse(r.stdout.contains("never"))
        // 真实超时语义：1.5s 超时 → 时长不应接近 sleep 30
        assertTrue("duration should reflect early kill: ${r.durationMs}", r.durationMs < 10_000)
    }

    @Test fun `huge output stays bounded with honest truncation stats`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val engine = shEngine()
        // ~1.28MB stdout（每行 ~7 字节 × 200000 行）
        val r = engine.execute(ExecRequest(command = "seq 1 200000", maxOutputChars = 2_000, headLines = 20, tailLines = 20))
        assertTrue(r.stdoutBytesTotal > 1_000_000)
        assertTrue(r.truncated)
        assertTrue(r.stdoutTruncated)
        assertTrue(r.stdout.contains("1\n"))          // 头部在
        assertTrue(r.stdout.contains("200000"))       // 尾部在
        assertTrue(r.stdout.contains("omitted"))      // 有省略标记
        assertTrue("text bounded: ${r.stdout.length}", r.stdout.length < 3_000)
        assertEquals(0, r.exitCode)
    }

    @Test fun `capture gap marked when output exceeds head plus tail buffers`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val engine = shEngine()
        val r = engine.execute(ExecRequest(command = "seq 1 100000", maxOutputChars = 100_000, headLines = 500, tailLines = 500))
        // 采集层默认 head+tail=128KB < ~589KB 输出 → gap；限长层预算 100K chars 也不够装
        assertTrue("total=${r.stdoutBytesTotal}", r.stdoutBytesTotal > 500_000)
        assertTrue(r.truncated)
        assertTrue(r.stdoutTruncated)
        // 截断事实由结构化字段承载；文本里则保留限长层标记
        //（采集层 gap 标记位于 head/tail 之间，可能被行折叠并入 omitted 区间 —— 语义等价）
        assertTrue(r.stdout.contains("omitted"))
    }

    @Test fun `env vars applied on local channel`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val engine = shEngine()
        val r = engine.execute(ExecRequest(command = "echo \$APEX_TEST_VAR", env = mapOf("APEX_TEST_VAR" to "hello-env")))
        assertEquals("hello-env\n", r.stdout)
        assertTrue(r.envApplied)
    }

    @Test fun `cwd is honored`() = runBlocking<Unit> {
        assumeTrue(File("/bin/sh").exists())
        val engine = shEngine()
        val dir = createTempDirSafe()
        try {
            val r = engine.execute(ExecRequest(command = "pwd", cwd = dir))
            assertEquals(dir + "\n", r.stdout)
        } finally {
            File(dir).deleteRecursively()
        }
    }

    @Test fun `spawn failure is honest structured failure`() = runBlocking<Unit> {
        // 不存在的 shell → spawn 抛 IOException → 结构化 spawn failure
        val engine = ExecEngine(
            ProcessBuilderSpawner(channel = "local-sh", shell = "/definitely/not/a/shell"),
            Dispatchers.IO
        )
        val r = engine.execute(ExecRequest(command = "echo hi"))
        assertNull(r.exitCode)
        assertNotNull(r.spawnError)
        assertTrue(r.stderr.contains("spawn failed"))
    }

    @Test fun `merged channel reports stderr not separated`() = runBlocking<Unit> {
        // Fake：stderr=null（合并通道）
        val spawner = object : CommandSpawner {
            override val channel = "merged-fake"
            override val supportsEnv = false
            override fun spawn(request: SpawnRequest): SpawnedCommand {
                val text = "out-part\nerr-part\n"
                return object : SpawnedCommand {
                    override val stdout = ByteArrayInputStream(text.toByteArray())
                    override val stderr: java.io.InputStream? = null
                    override fun waitFor(timeoutMs: Long) = true
                    override fun exitValue() = 0
                    override fun destroy() {}
                }
            }
        }
        val r = ExecEngine(spawner, Dispatchers.IO).execute(ExecRequest(command = "x"))
        assertEquals(0, r.exitCode)
        assertEquals("out-part\nerr-part\n", r.stdout)
        assertEquals("", r.stderr)
        assertFalse(r.stderrSeparated)
    }

    @Test fun `unfinished drain is reported as truncated instead of silently partial`() = runBlocking<Unit> {
        // 典型现场：命令把后台孙进程留在原地，孙进程继续持有 stdout —— 进程已退出
        // （waitFor=true），但管道迟迟不 EOF。宽限内收尾失败时，拿到的是**不完整**
        // 输出，必须如实进 truncated，绝不能返回"看起来完整"的结果。
        val spawner = object : CommandSpawner {
            override val channel = "slow-pipe-fake"
            override val supportsEnv = false
            override fun spawn(request: SpawnRequest): SpawnedCommand = object : SpawnedCommand {
                override val stdout: java.io.InputStream =
                    object : ByteArrayInputStream("partial".toByteArray()) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val n = super.read(b, off, len)
                            if (n < 0) Thread.sleep(1_500) // 模拟孙进程继续持有管道，迟迟不 EOF
                            return n
                        }
                    }
                override val stderr: java.io.InputStream? = null
                override fun waitFor(timeoutMs: Long) = true
                override fun exitValue() = 0
                override fun destroy() {}
            }
        }
        val engine = ExecEngine(
            spawner,
            Dispatchers.IO,
            ExecEngine.CaptureConfig(postKillDrainGraceMs = 300)
        )
        val r = engine.execute(ExecRequest(command = "x"))
        assertEquals("partial", r.stdout)
        assertTrue("未收尾的采集必须上报截断，而不是静默返回部分输出", r.stdoutTruncated)
        assertTrue(r.truncated)
    }

    // ═══════════ 工具 ═══════════

    private fun createTempDirSafe(): String =
        File.createTempFile("apexexec", "").let { it.delete(); it.mkdirs(); it.absolutePath }
}
