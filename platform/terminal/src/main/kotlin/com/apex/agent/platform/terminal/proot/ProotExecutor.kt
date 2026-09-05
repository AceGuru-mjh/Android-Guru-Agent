package com.apex.agent.platform.terminal.proot

/**
 * P71: ProotExecutor —— 无 PTY 的 proot 短命令执行器（ProcessBuilder + pipes）。
 *
 * 定位（PR #75 计划 §3.3）：P68 的 ProcessBuilder 路线从"运行时"降级为本执行器，
 * 专用于 rootfs 探针、apt（P76）、环境检测等非交互批处理，以及 CI/JVM 集成测试
 * （JVM 无 JNI .so，必须走 ProcessBuilder）。生产交互会话一律走 forkpty 路径
 * （LinuxPRootBackend → nativeCreateSessionArgv）。
 *
 * 对 P68 (PRootProcessProvider) 的两个修复：
 *  - G1 假 PID：`Process.pid()`（Java 9+）返回真实宿主 pid，替代 10000 起步的计数器 ——
 *    信号/快照/日志才能对上真实进程。
 *  - G4 host/guest env 分离：[hostEnv] 只含 proot 自身需要的变量
 *    （PROOT_TMP_DIR/PROOT_LOADER/LD_LIBRARY_PATH/PATH）；guest 环境变量只经
 *    argv 的 -E 传入，两套 env 永不混合。
 */
class ProotExecutor(
    /** proot 进程的宿主 env（来自 PRootHostEnvironment.hostEnv()；测试可注入任意 map）。 */
    private val hostEnv: () -> Map<String, String> = { emptyMap() }
) {

    /** 一次执行的完整结果。 */
    data class Execution(
        val pid: Long,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean = false
    )

    /**
     * 同步执行一个已构建好的 [PRootCommand]（含 proot 本体在内的完整 argv）。
     * stdout/stderr 分离捕获（redirectErrorStream=false）。
     *
     * @param timeoutMs 超时强杀（destroyForcibly）并以 timedOut=true 返回。
     */
    fun execute(command: PRootCommand, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Execution {
        // T81 (U-9)：默认有界 —— 原实现 Long.MAX_VALUE 把 BoundedOutputCapture 的
        // halfBudget 压到 Int.MAX_VALUE，head StringBuilder 可积 ~1GB（OOM 风险）。
        // 需要更大预算的调用方显式传 executeBounded(maxOutputBytes=…)。
        return executeBounded(command, timeoutMs, maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES).let {
            Execution(
                pid = it.pid,
                exitCode = it.exitCode,
                stdout = it.stdout,
                stderr = it.stderr,
                durationMs = it.durationMs,
                timedOut = it.timedOut
            )
        }
    }

    /**
     * T76: 有界输出执行 —— apt/bootstrap 的输出可能达数 MB（apt update 的 InRelease
     * 列表、dpkg 配置日志）。无界 StringBuilder 会撑爆 Agent context + 占用内存。
     *
     * 策略（T76 §35）：超过 [maxOutputBytes] 后保留 **首 N + 尾 M**（各占预算一半），
     * 中间丢弃并打标 `truncated=true`。这样 Agent 既能看到开头的错误信息，也能看到
     * 末尾的 SUMMARY 行，不被海量包列表淹没。
     *
     * 流式实现：reader 线程边读边写 ring，到达预算上限后切换到"只保留尾部"模式 ——
     * 不一次性缓存全文再截断（那仍会 OOM）。
     */
    fun executeBounded(
        command: PRootCommand,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Long = DEFAULT_MAX_OUTPUT_BYTES
    ): BoundedExecution {
        val argv = listOf(command.executable.value) + command.arguments
        val pb = ProcessBuilder(argv)
        pb.redirectErrorStream(false)
        val pbEnv = pb.environment()
        pbEnv.clear()
        pbEnv.putAll(hostEnv())

        val start = System.currentTimeMillis()
        val proc = pb.start()

        // 有界捕获器：超预算后首部固化、尾部滚动。
        val stdoutCapture = BoundedOutputCapture(maxOutputBytes)
        val stderrCapture = BoundedOutputCapture(maxOutputBytes)
        val stdoutThread = Thread {
            runCatching {
                proc.inputStream.bufferedReader().use { reader ->
                    val buf = CharArray(8 * 1024)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (n > 0) stdoutCapture.append(buf, n)
                    }
                }
            }
        }
        val stderrThread = Thread {
            runCatching {
                proc.errorStream.bufferedReader().use { reader ->
                    val buf = CharArray(8 * 1024)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (n > 0) stderrCapture.append(buf, n)
                    }
                }
            }
        }
        stdoutThread.isDaemon = true
        stderrThread.isDaemon = true
        stdoutThread.start()
        stderrThread.start()

        var timedOut = false
        val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            timedOut = true
        }
        stdoutThread.join(2000)
        stderrThread.join(2000)
        val exit = if (timedOut) -1 else proc.exitValue()

        return BoundedExecution(
            pid = ProcessPidAccessor.pidOf(proc),
            exitCode = exit,
            stdout = stdoutCapture.snapshot(),
            stderr = stderrCapture.snapshot(),
            stdoutTruncated = stdoutCapture.truncated,
            stderrTruncated = stderrCapture.truncated,
            stdoutBytesCaptured = stdoutCapture.bytesSeen,
            stderrBytesCaptured = stderrCapture.bytesSeen,
            durationMs = System.currentTimeMillis() - start,
            timedOut = timedOut
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
        /** T76: apt/bootstrap 输出默认上限 1 MB（首 512KB + 尾 512KB）。 */
        const val DEFAULT_MAX_OUTPUT_BYTES: Long = 1_048_576L
    }
}

/**
 * T76: apt 执行结果（有界输出版）。
 *
 * `stdout`/`stderr` 已应用首-N + 尾-M 截断；`truncated=true` 表示原始输出超过预算
 * 被压缩。`bytesSeen` 是原始字节数（诊断用 —— Agent 据此判断是否需要再查日志）。
 */
data class BoundedExecution(
    val pid: Long,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val stdoutBytesCaptured: Long,
    val stderrBytesCaptured: Long,
    val durationMs: Long,
    val timedOut: Boolean = false
) {
    /** 是否成功（exit 0 且未超时）。 */
    val ok: Boolean get() = !timedOut && exitCode == 0
}

/**
 * T76: 有界输出捕获器。
 *
 * 两阶段策略：
 *  1. 累积阶段：未达 [budget] 时全文累积（StringBuilder）。
 *  2. 滚动阶段：达 [budget] 后切换为固定容量的尾部 ring（仅保留最近 budget/2 字符），
 *     首部 budget/2 字符固化。最终 snapshot = 首部 + "\n...[truncated N bytes]...\n" + 尾部。
 *
 * 这样首部的错误上下文（apt E: 行）与尾部的 SUMMARY 都保留，中间包列表丢弃。
 */
internal class BoundedOutputCapture(private val budget: Long) {
    /** 首部（固化，达 headCapacity 后停止增长）。 */
    private val head = StringBuilder()
    /** 尾部 ring（容量 headCapacity）。 */
    private val tail = StringBuilder()
    /** 首/尾各占预算一半，但防 Long→Int 溢出（budget=Long.MAX_VALUE 时退化为 Int.MAX_VALUE）。 */
    private val halfBudget: Int = (budget / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    /** 是否已进入滚动模式。 */
    var truncated: Boolean = false
        private set
    /** 原始流总字节数（含被丢弃部分）。 */
    var bytesSeen: Long = 0
        private set

    private val headCapacity: Int get() = halfBudget
    private val tailCapacity: Int get() = halfBudget

    fun append(buf: CharArray, len: Int) {
        if (len <= 0) return
        bytesSeen += len
        if (!truncated) {
            val remainingHead = headCapacity - head.length
            if (len <= remainingHead) {
                head.append(buf, 0, len)
            } else {
                head.append(buf, 0, remainingHead)
                val rest = len - remainingHead
                truncated = true
                appendToTail(buf, remainingHead, rest)
            }
        } else {
            appendToTail(buf, 0, len)
        }
    }

    private fun appendToTail(buf: CharArray, offset: Int, len: Int) {
        tail.append(buf, offset, len)
        if (tail.length > tailCapacity) {
            tail.delete(0, tail.length - tailCapacity)
        }
    }

    fun snapshot(): String {
        if (!truncated) return head.toString()
        val dropped = bytesSeen - head.length - tail.length
        return buildString(head.length + tail.length + 64) {
            append(head)
            append("\n...[truncated ")
            append(dropped)
            append(" bytes]...\n")
            append(tail)
        }
    }
}
