package com.apex.agent.platform.terminal.exec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min

/**
 * 一次性结构化命令执行引擎（Termux Run Command / OperIt bridge 的执行语义）。
 *
 * 执行路径：spawn → **并发排空** stdout/stderr（各自有界采集：头部缓冲 + 尾部环形
 * 缓冲，`yes` 级洪流下内存恒定 ≤ 2×captureCap/流）→ waitFor(timeout)（可中断等待）
 * →（超时则 destroy 强杀 + 限时宽限收尾采集）→ ANSI 净化 → 限长 → [CommandResult]。
 *
 * 诚实性契约：
 *  - exitCode 只来自真实 waitpid；超时强杀 = -1 + timed_out/killed 如实上报；
 *  - spawn 失败 = exitCode=null 的结构化失败（绝不伪装 exit 0）；
 *  - 采集/预算两层截断都进 truncated / bytes_total 统计；
 *  - 调用方协程取消 → runInterruptible 打断 waitFor → finally destroy（不留孤儿进程）。
 *
 * （PrivilegeDetector.executeWithTimeout 此前修复过的教训在本引擎同样适用：
 * 「先阻塞读后 waitFor」的顺序会让超时永不生效 —— 这里读/等并发。）
 */
class ExecEngine(
    private val spawner: CommandSpawner,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val capture: CaptureConfig = CaptureConfig()
) {

    /** 执行通道标识（透明转发 spawner —— 供工具层在无结果路径上报）。 */
    val channel: String get() = spawner.channel


    /**
     * 采集层预算（内存安全上限，与 [ExecRequest] 的字符预算是两层不同的防线）：
     * 即便进程输出 GB 级，单流内存占用恒定 head+tail。
     */
    data class CaptureConfig(
        val headBytes: Int = 64 * 1024,
        val tailBytes: Int = 64 * 1024,
        /** 强杀后等待排空协程收尾的宽限（pipes 关闭即完成）。 */
        val postKillDrainGraceMs: Long = 800
    )

    suspend fun execute(request: ExecRequest): CommandResult = withContext(dispatcher) {
        val startedAt = System.nanoTime()

        val spawned: SpawnedCommand = try {
            spawner.spawn(SpawnRequest(request.command, request.cwd, request.env))
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            return@withContext CommandResult.spawnFailure(
                request = request,
                channel = spawner.channel,
                stderrSeparated = true,
                envApplied = spawner.supportsEnv,
                error = "spawn failed (${spawner.channel}): ${e.message ?: e.javaClass.simpleName}"
            )
        }

        val stdoutCapture = BoundedCapture(capture.headBytes, capture.tailBytes)
        val stderrCapture = BoundedCapture(capture.headBytes, capture.tailBytes)
        val stderrStream = spawned.stderr
        val stderrSeparated = stderrStream != null

        val drainScope = CoroutineScope(SupervisorJob() + dispatcher)
        val drainJobs = mutableListOf<Job>()
        var exited = false
        var timedOut = false
        var exitCode: Int? = null
        try {
            // ── 并发排空（阻塞读跑在 IO 线程；waitFor 与之并发，超时才真正生效）──
            drainJobs += drainScope.launch { drainStream(spawned.stdout, stdoutCapture) }
            if (stderrStream != null) {
                drainJobs += drainScope.launch { drainStream(stderrStream, stderrCapture) }
            }

            // ── 等待退出或超时（runInterruptible：协程取消 → 线程中断 → finally 杀进程）──
            val waitTimeout = if (request.timeoutMs <= 0) Long.MAX_VALUE / 2 else request.timeoutMs
            exited = runInterruptible { spawned.waitFor(waitTimeout) }
            if (!exited) {
                timedOut = true
                spawned.destroy()
                // 强杀后 pipes 关闭 → 排空协程自然收尾；限时宽限防个别驱动不关 pipe
                withTimeoutOrNull(capture.postKillDrainGraceMs) { drainJobs.joinAll() }
                exitCode = -1
            } else {
                exitCode = runCatching { spawned.exitValue() }.getOrDefault(-1)
                // 正常退出也等排空（EOF 已到，join 立即返回）
                withTimeoutOrNull(capture.postKillDrainGraceMs) { drainJobs.joinAll() }
            }
        } finally {
            if (!exited) runCatching { spawned.destroy() }
            drainJobs.forEach { it.cancel() }
            drainScope.cancel()
        }

        val durationMs = (System.nanoTime() - startedAt) / 1_000_000

        // ── 文本化（UTF-8 宽容解码：切割边界可能落在多字节字符中间 → 替换符，已文档化）──
        val rawStdout = stdoutCapture.materialize()
        val rawStderr = if (stderrSeparated) stderrCapture.materialize()
        else CapturedText("", 0L, false)

        // ── ANSI 净化 + 限长（两层防线）──
        val stdoutOut = postProcess(rawStdout.text, request.ansi, request.maxOutputChars, request.headLines, request.tailLines)
        val stderrOut = postProcess(rawStderr.text, request.ansi, request.maxErrorChars, request.headLines, request.tailLines)

        CommandResult(
            stdout = stdoutOut.text,
            stderr = stderrOut.text,
            exitCode = exitCode,
            durationMs = durationMs,
            truncated = stdoutOut.truncated || stderrOut.truncated ||
                rawStdout.captureGap || rawStderr.captureGap,
            timedOut = timedOut,
            killed = timedOut,
            stdoutBytesTotal = rawStdout.totalBytes,
            stderrBytesTotal = rawStderr.totalBytes,
            stdoutTruncated = stdoutOut.truncated || rawStdout.captureGap,
            stderrTruncated = stderrOut.truncated || rawStderr.captureGap,
            ansiMode = request.ansi,
            ansiSequencesRemoved = stdoutOut.sequencesRemoved + stderrOut.sequencesRemoved,
            channel = spawner.channel,
            stderrSeparated = stderrSeparated,
            envApplied = spawner.supportsEnv,
            cwd = request.cwd
        )
    }

    // ── 内部 ──

    private suspend fun CoroutineScope.drainStream(stream: InputStream, target: BoundedCapture) {
        try {
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (n > 0) target.write(buf, 0, n)
            }
        } catch (_: java.io.IOException) {
            // 进程被杀/管道破裂：已采集内容即为终态 —— 诚实保留
        } finally {
            runCatching { stream.close() }
        }
    }

    private data class Processed(
        val text: String,
        val truncated: Boolean,
        val sequencesRemoved: Int
    )

    private fun postProcess(
        text: String,
        ansi: AnsiMode,
        maxChars: Int,
        headLines: Int,
        tailLines: Int
    ): Processed {
        val sanitized = AnsiSanitizer.sanitize(text, ansi)
        val limited = OutputLimiter(maxChars, headLines, tailLines).limit(sanitized.text)
        return Processed(limited.text, limited.truncated, sanitized.sequencesRemoved)
    }

    /** 单流采集快照：拼接文本 + 真实总字节数 + 采集是否发生丢中间。 */
    internal data class CapturedText(
        val text: String,
        val totalBytes: Long,
        val captureGap: Boolean
    )

    /**
     * 有界采集：头部缓冲 + 尾部环形缓冲。
     *
     * 拼接规则（head 覆盖 [0,headLen)，tail 覆盖 [total-tailLen, total)）：
     *  - total ≤ headCap → 全量（head 即全部）；
     *  - 尾部起点 ≤ headLen（有重叠）→ head + tail 去重重叠段，无损；
     *  - 否则 → head + 显式 gap 标记 + tail（丢失的只是中间段，且被如实标记）。
     */
    internal class BoundedCapture(private val headCap: Int, private val tailCap: Int) {
        private val head = ByteArrayOutputStream(min(headCap, 8 * 1024))
        private val tail = TailRing(tailCap)
        private var total: Long = 0

        fun write(src: ByteArray, off: Int, len: Int) {
            total += len
            if (head.size() < headCap) {
                val room = headCap - head.size()
                val take = min(len, room)
                head.write(src, off, take)
            }
            tail.write(src, off, len)
        }

        fun materialize(): CapturedText {
            val headBytes = head.toByteArray()
            val tailBytes = tail.toByteArray()

            val text: String = when {
                total <= headCap -> String(headBytes, Charsets.UTF_8)
                else -> {
                    val tailStartAbs = total - tailBytes.size
                    val headLen = headBytes.size
                    if (tailStartAbs <= headLen) {
                        // 覆盖/相接：无损拼接（丢弃与 head 重叠的尾段前缀）
                        val overlap = (headLen - tailStartAbs).toInt().coerceAtLeast(0)
                        val unique = if (overlap >= tailBytes.size) ByteArray(0)
                        else tailBytes.copyOfRange(overlap, tailBytes.size)
                        String(headBytes, Charsets.UTF_8) + String(unique, Charsets.UTF_8)
                    } else {
                        // 有 gap：显式标记（诚实告知中间段未采集）
                        val gapBytes = tailStartAbs - headLen
                        String(headBytes, Charsets.UTF_8) +
                            "\n[... $gapBytes bytes not captured (output exceeded head+tail buffer) ...]\n" +
                            String(tailBytes, Charsets.UTF_8)
                    }
                }
            }
            val gap = total > headCap && (total - tailBytes.size) > headBytes.size
            return CapturedText(text, total, gap)
        }
    }

    /** 定长尾部环形缓冲（单写者；满环覆盖最老数据 —— Termux 滚动缓冲区语义）。 */
    internal class TailRing(private val cap: Int) {
        private var buf = ByteArray(if (cap > 0) cap else 1)
        private var start = 0
        private var len = 0

        fun write(src: ByteArray, off: Int, count: Int) {
            if (cap <= 0 || count <= 0) return
            if (count >= cap) {
                // 一次写超过容量：只保留最后 cap 字节
                System.arraycopy(src, off + count - cap, buf, 0, cap)
                start = 0
                len = cap
                return
            }
            // 追加写入（写入点 = (start+len)%cap；环满时 == start，即覆盖最老数据）
            val endPos = (start + len) % cap
            copyWrapped(src, off, endPos, count)
            val newLen = len + count
            len = min(newLen, cap)
            // 覆盖发生（写入超过剩余空间）→ 起点前移被淘汰的字节数
            if (newLen > cap) {
                start = (start + (newLen - cap)) % cap
            }
        }

        /** 环形复制（destPos 可能回绕，分两段拷贝）。 */
        private fun copyWrapped(src: ByteArray, srcOff: Int, destPos: Int, count: Int) {
            var remaining = count
            var s = srcOff
            var d = destPos
            while (remaining > 0) {
                val chunk = min(remaining, cap - d)
                System.arraycopy(src, s, buf, d, chunk)
                s += chunk
                d = (d + chunk) % cap
                remaining -= chunk
            }
        }

        fun toByteArray(): ByteArray {
            if (len == 0) return ByteArray(0)
            val out = ByteArray(len)
            if (start + len <= cap) {
                System.arraycopy(buf, start, out, 0, len)
            } else {
                val first = cap - start
                System.arraycopy(buf, start, out, 0, first)
                System.arraycopy(buf, 0, out, first, len - first)
            }
            return out
        }
    }
}
