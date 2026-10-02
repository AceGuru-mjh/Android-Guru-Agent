package com.apex.agent.vtnative

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ═══ #F-⑰ Native VT 引擎黑匣子（feed trail）═══
 *
 * 问题背景：`libvt_native.so`（vendored C++ 引擎）若发生 SIGSEGV/SIGBUS，
 * 进程直接被杀 —— 系统会产出 tombstone（debuggerd），但**最近喂给引擎的
 * 终端字节流**（哪条 ANSI 序列触发崩溃）无处可查，无法归因到具体序列。
 *
 * 设计（黑匣子模式，而非 sigaction）：
 *  - 不在 vendored C++ 里加信号处理器 —— VENDOR.md 规定 vendored 源必须与
 *    上游字节一致（信号处理需上游先行）；且 Android 的 debuggerd 本就为
 *    任何 native 崩溃产出系统级 tombstone（含 backtrace），进程内再装一个
 *    handler 只能补充「崩溃前现场」；
 *  - 本类在 **JVM 侧**维护最近 4KB feed 字节环（[note]）并周期性（2s 节流）
 *    落盘 —— 崩溃后文件天然持有「最后 ~1KB+ 的终端 buffer」（正是审计文档
 *    要求的 crash 前 dump），且全程 async-signal-safe（根本没有信号处理器）；
 *  - 下次进程启动（[install]）时：上一份 trail 若未以 CLEAN 标记收尾 →
 *    判定为 native 崩溃/硬杀现场，打印到 logcat 并另存 `.crash` 留档；
 *  - 成本：每 PTY read 一次 ≤4KB 的 synchronized arraycopy + 2s 一次的小文件
 *    覆写 —— 相对 JNI feed 本身可忽略。
 *
 * 接线：app 的 TerminalModule.provideTerminalRuntime 调 [install]（filesDir/logs）；
 * [NativeVtCore] 的 feed/close 调 [note]/[onEngineClosed]。
 */
object VtFeedTrail {

    private const val TAG = "VtFeedTrail"
    private const val RING_BYTES = 4 * 1024
    private const val FLUSH_INTERVAL_MS = 2_000L
    private const val TRAIL_FILE = "vt-feed-trail.log"
    private const val CLEAN_MARK = "===CLEAN_SHUTDOWN==="

    private val ring = ByteArray(RING_BYTES)
    private var ringPos = 0
    private val installed = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)
    private val liveEngines = AtomicInteger(0)
    private var trailFile: File? = null

    @Volatile
    private var lastFlushMs = 0L

    /** 启动接线（幂等）：落盘目录 + 上一次崩溃现场的事后归因。 */
    fun install(dir: File) {
        if (!installed.compareAndSet(false, true)) return
        trailFile = File(dir, TRAIL_FILE).also { f ->
            runCatching { f.parentFile?.mkdirs() }
            runCatching {
                if (f.exists()) {
                    val text = f.readText()
                    if (text.endsWith(CLEAN_MARK + "\n")) {
                        f.delete()
                    } else {
                        // 未干净收尾 → 视为 native 崩溃/硬杀现场：logcat 归因 + 留档。
                        Log.w(
                            TAG,
                            "previous VT feed trail ended WITHOUT clean shutdown " +
                                "(possible native crash / hard kill); tail follows:\n" +
                                text.takeLast(2048)
                        )
                        f.renameTo(File(dir, "$TRAIL_FILE.crash"))
                    }
                }
            }.onFailure { Log.w(TAG, "trail install failed: ${it.message}") }
        }
    }

    /** feed 热路径：字节进环（synchronized 的 ≤4KB arraycopy），节流落盘。 */
    fun note(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || !installed.get()) return
        synchronized(this) {
            var src = offset
            var remaining = length.coerceAtMost(bytes.size - offset)
            while (remaining > 0) {
                val n = minOf(remaining, RING_BYTES - ringPos)
                System.arraycopy(bytes, src, ring, ringPos, n)
                ringPos = (ringPos + n) % RING_BYTES
                src += n
                remaining -= n
            }
            dirty.set(true)
            val now = System.currentTimeMillis()
            if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                lastFlushMs = now
                flushLocked()
            }
        }
    }

    /** 引擎实例计数：归零 = 全部会话已干净关闭 → 落 CLEAN 标记。 */
    fun onEngineCreated() {
        liveEngines.incrementAndGet()
    }

    fun onEngineClosed() {
        if (liveEngines.decrementAndGet() <= 0) {
            synchronized(this) {
                dirty.set(true)
                appendCleanMarkAndFlush()
            }
        }
    }

    private fun appendCleanMarkAndFlush() {
        val f = trailFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(CLEAN_MARK + "\n")
            dirty.set(false)
        }
    }

    private fun flushLocked() {
        if (!dirty.get()) return
        val f = trailFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(renderRing())
            dirty.set(false)
        }.onFailure { Log.w(TAG, "trail flush failed: ${it.message}") }
    }

    /** 环 → 可读文本（不可见字节替换为 '.'，保留 \n；ANSI 结构可辨）。 */
    private fun renderRing(): String {
        val sb = StringBuilder(RING_BYTES + 32)
        sb.append("--- vt feed trail (last ").append(RING_BYTES).append(" bytes) ---\n")
        for (i in 0 until RING_BYTES) {
            val b = ring[(ringPos + i) % RING_BYTES]
            val c = b.toInt() and 0xFF
            sb.append(
                when {
                    c == 0x0A -> '\n'
                    c in 0x20..0x7E -> c.toChar()
                    else -> '.'
                }
            )
        }
        return sb.toString()
    }
}
