package com.apex.agent.core.code.stream

/**
 * # Terminal Pulse Buffer — 终端输出脉冲缓冲（规格书：脉冲式输出）
 *
 * ## 为什么不逐字直通
 *
 * 工具的流式输出（ToolOutputChunk）可能每秒几十次、每次几个字符。
 * 逐字打字机式直通终端面板 = 每字符一次重组，低端机上肉眼可见地拖垮
 * 时间轴滚动。终端输出按**脉冲**整块 flush——一屏内容一次到位。
 *
 * ## 四条件脉冲（任一满足即 flush）
 *
 * | # | 条件 | 阈值 | 动机 |
 * |---|------|------|------|
 * | ① | 累积体量 | [sizeThresholdBytes]（默认 4KB） | 大输出快速排空，防积压 |
 * | ② | 完整行数 | [flushLineCount]（默认 8 行，含换行符计数） | 命令输出天然按行产生，攒几行一屏最自然 |
 * | ③ | 时间窗 | [windowMs]（默认 120ms） | 稀疏小输出也要及时可见（交互不死感） |
 * | ④ | 显式收尾 | [close] / [flush] | 工具 Complete 时必须清空尾巴 |
 *
 * ## 环形窗口
 *
 * 面板展示的不是全部历史（万行构建日志会把内存和渲染同时打爆），而是
 * 最新 [windowChars]（默认 16KB）尾窗——够看上下文，又常数级内存。
 *
 * 线程模型：与引擎 Flow 收集协程同线程串行调用，无锁。
 */
class TerminalPulseBuffer(
    private val sizeThresholdBytes: Int = DEFAULT_SIZE_THRESHOLD,
    private val flushLineCount: Int = DEFAULT_FLUSH_LINES,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val windowChars: Int = DEFAULT_WINDOW_CHARS
) {
    private val pending = StringBuilder()
    private var lastFlushAt = 0L
    private val window = StringBuilder()
    private var closed = false

    /** 追加一段流式输出（不立即返回内容——[tick] 统一取脉冲）。 */
    fun append(chunk: String) {
        if (closed || chunk.isEmpty()) return
        pending.append(chunk)
    }

    /**
     * 取一次脉冲：四条件判定，满足则返回整块内容（并滑入环形窗口），
     * 否则 null。VM 的 25ms 渲染 ticker 每拍调用。
     *
     * @param nowMs 当前时刻（注入便于测试；0 = 不做时间窗判定）
     */
    fun tick(nowMs: Long = System.currentTimeMillis()): String? {
        if (pending.isEmpty()) return null
        if (lastFlushAt == 0L) lastFlushAt = nowMs

        val sizeHit = pending.length >= sizeThresholdBytes
        val linesHit = pending.count { it == '\n' } >= flushLineCount
        val timeHit = nowMs > 0 && nowMs - lastFlushAt >= windowMs
        if (!sizeHit && !linesHit && !timeHit) return null
        return doFlush(nowMs)
    }

    /** 显式 flush（条件④：工具 Complete / 会话收尾）。 */
    fun flush(nowMs: Long = System.currentTimeMillis()): String? {
        if (pending.isEmpty()) return null
        return doFlush(nowMs)
    }

    /** 当前尾窗内容（面板渲染源；含已 flush 的历史，截到 [windowChars]）。 */
    fun content(): String = window.toString()

    /** 是否有待 flush 的内容（测试与观测）。 */
    fun pendingSize(): Int = pending.length

    /** 关闭缓冲：此后 append 无效，残留内容可被最后一次 [flush] 取走。 */
    fun close() {
        closed = true
    }

    /** 重置（新 run 复用缓冲实例时）。 */
    fun reset() {
        pending.clear()
        window.clear()
        lastFlushAt = 0L
        closed = false
    }

    private fun doFlush(nowMs: Long): String {
        val out = pending.toString()
        pending.clear()
        lastFlushAt = nowMs
        window.append(out)
        if (window.length > windowChars) {
            window.delete(0, window.length - windowChars)
        }
        return out
    }

    private companion object {
        const val DEFAULT_SIZE_THRESHOLD = 4 * 1024
        const val DEFAULT_FLUSH_LINES = 8
        const val DEFAULT_WINDOW_MS = 120L
        const val DEFAULT_WINDOW_CHARS = 16 * 1024
    }
}
