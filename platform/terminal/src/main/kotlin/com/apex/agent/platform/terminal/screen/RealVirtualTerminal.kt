package com.apex.agent.platform.terminal.screen

import com.apex.agent.terminalemulator.ScreenMutation
import com.apex.agent.terminalemulator.TerminalEngine
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import com.apex.agent.vtnative.VtEngineFactory
import java.util.concurrent.locks.ReentrantLock

/**
 * RealVirtualTerminal — backed by a [TerminalEngine] (Spec PR #53).
 *
 * Adapter implementing the [VirtualTerminal] interface. Runtime/UI contract unchanged;
 * internals upgraded from the removed VT100Emulator fallback to TerminalCore
 * (incremental parser, UTF-8 decoder, wide chars, scroll region, alternate screen,
 * modes, dirty mutations).
 *
 * Engine backend (terminal-native):
 *  - 设备端：libvt_native.so（C++17 零分配引擎，:terminal-native 模块），
 *    由 [VtEngineFactory] 运行时选择；
 *  - JVM 单测 / CI / 加载失败：无缝回退纯 Kotlin [com.apex.agent.terminalemulator.TerminalCore]，
 *    两者为语义等价的姊妹实现（上游 129 项奇偶校验测试保证）。
 *
 * P83 hardening:
 *  - **Snapshot caching** — every property getter used to trigger a full
 *    O(rows×cols) `core.snapshot()` render (5 getters × every observation). The
 *    snapshot is now cached and invalidated on feed/resize/reset.
 *  - **changedRows wiring** — TerminalCore's dirty-region mutations are drained on
 *    every feed (previously nobody drained them in production, so they folded into a
 *    single FULL and `TerminalScreenState.changedRows` stayed null forever). The
 *    union of row ranges since the last snapshot is now surfaced as `changedRows`;
 *    FULL/RESIZE mutations degrade to null (= full-screen, the old semantics).
 *  - **styledSnapshot** — full-fidelity render state for the UI grid renderer.
 *
 * Thread safety（VT 线程模型收敛）:
 *  [core] 被三类线程并发直达：pump 线程（feed + styledSnapshot，每 8KB chunk）、
 *  Agent 工具线程（observe(SCREEN) → snapshot/scrollbackText）、Main/IO 线程
 *  （resize）。两个引擎实现都不自卫（TerminalCore 纯可变状态零锁；
 *  NativeVtCore 的 JNI 面无内部锁 —— vendored C++ 不可改），此前无任何串行化层
 *  —— native 侧数据竞争 = 未定义行为（#F-⑰ VtFeedTrail 黑匣子记录的真实崩溃
 *  归因面）。收敛点：[VtEngineFactory.create] 的唯一生产调用方就是本类，故在
 *  这里用一把 per-instance [ReentrantLock] 串行化**全部**引擎访问，两个引擎
 *  实现一并受保护。选型依据：
 *   - ReentrantLock 而非 kotlinx Mutex：本类入口大多在非 suspend 上下文被调
 *     （pump 循环体 / observe 直通 / resize），Mutex 无法覆盖；
 *   - 可重入性是防御位：feed → respond() → responseSink → nativeWrite 当前
 *     不回跳 VT，但若未来应答回写链路回跳到本类方法（如读模式），可重入锁不会
 *     自锁死；
 *   - 锁序：engineLock 为叶子锁 —— 持锁期间不再获取其它锁（responseSink 回写
 *     只做 PTY write），与 SessionManager 的 mutex/transitionLocks 无环。
 *
 * Spec ref: ATR 2.1 PR #53 — VT/ANSI/Unicode/Screen Core 2.0 / P83 Terminal Finalization.
 */
class RealVirtualTerminal(
    initialRows: Int,
    initialCols: Int
) : VirtualTerminal {

    private val core: TerminalEngine = VtEngineFactory.create(initialRows, initialCols)

    /** 串行化全部 [core] 访问（见类 KDoc「Thread safety」）。 */
    private val engineLock = ReentrantLock()

    private inline fun <T> withEngine(block: () -> T): T {
        engineLock.lock()
        try {
            return block()
        } finally {
            engineLock.unlock()
        }
    }

    /** Cached plain-text snapshot — invalidated by feed/resize/reset. */
    @Volatile
    private var cachedScreen: TerminalScreenState? = null

    /** Dirty rows accumulated since the last snapshot() (from drained mutations). */
    private var pendingChangedRows: Set<Int>? = null
    private var pendingIsFull = false

    override fun feed(bytes: ByteArray) = withEngine {
        core.feed(bytes)
        cachedScreen = null
        collectMutations()
    }

    fun flush() = withEngine {
        core.flush()
        cachedScreen = null
        collectMutations()
    }

    override fun resize(rows: Int, cols: Int) = withEngine {
        core.resize(rows, cols)
        cachedScreen = null
        pendingIsFull = true
        pendingChangedRows = null
    }

    override fun reset() = withEngine {
        core.reset()
        cachedScreen = null
        pendingIsFull = true
        pendingChangedRows = null
    }

    /**
     * Drain TerminalCore's bounded mutation list so it never folds to FULL under
     * production load, and fold it into an incremental [TerminalScreenState.changedRows].
     */
    private fun collectMutations() {
        val drained = core.drainMutations()
        if (drained.isEmpty()) return
        if (drained.any {
                it.type == ScreenMutation.MutationType.FULL ||
                    it.type == ScreenMutation.MutationType.RESIZE
            }
        ) {
            pendingIsFull = true
            pendingChangedRows = null
            return
        }
        if (pendingIsFull) return  // already full-screen — nothing finer to accumulate
        val union = (pendingChangedRows as? MutableSet<Int>)
            ?: mutableSetOf<Int>().also { pendingChangedRows = it }
        for (m in drained) {
            val range = m.affectedRows
            if (range.last < 0 || range.first > core.rows - 1) continue
            union.addAll(range.first.coerceAtLeast(0)..range.last.coerceAtMost(core.rows - 1))
        }
    }

    override fun snapshot(): TerminalScreenState = withEngine {
        cachedScreen?.let { return it }
        val s = core.snapshot()
        val changed = if (pendingIsFull) null else pendingChangedRows
        val state = TerminalScreenState(
            rows = s.rows, cols = s.cols,
            cursorRow = s.cursorRow, cursorCol = s.cursorCol,
            alternateScreen = s.alternateScreen,
            cursorVisible = s.cursorVisible,
            title = s.title,
            renderedText = s.renderedText,
            changedRows = changed
        )
        pendingChangedRows = null
        pendingIsFull = false
        cachedScreen = state
        state
    }

    override fun styledSnapshot(maxScrollbackLines: Int): TerminalRenderSnapshot =
        withEngine { core.renderSnapshot(maxScrollbackLines) }

    /** Drain pending screen mutations (for event-driven UI / observation delta). */
    fun drainMutations(): List<ScreenMutation> = withEngine { core.drainMutations() }

    // Property getters reuse the cached snapshot — each used to trigger a full
    // O(rows×cols) core.snapshot() render (5 renders per chained observation).
    override val cursorRow: Int get() = snapshot().cursorRow
    override val cursorCol: Int get() = snapshot().cursorCol
    override val alternateScreen: Boolean get() = snapshot().alternateScreen
    override val rows: Int get() = snapshot().rows
    override val cols: Int get() = snapshot().cols

    // ─── T82: input-translation + scrollback/clipboard capability exposure ───

    /** DECCKM: when true the input layer must send SS3 (ESC O x) arrows/home/end. */
    fun applicationCursorKeys(): Boolean = withEngine { core.applicationCursorKeys() }

    /** Bracketed paste mode 2004: paste writes must wrap ESC[200~ … ESC[201~. */
    fun bracketedPasteMode(): Boolean = withEngine { core.bracketedPasteMode() }

    /** Last [maxLines] scrollback rows, oldest first (main screen only). */
    fun scrollbackLines(maxLines: Int): List<String> =
        withEngine { core.scrollbackText(maxLines) }

    /** Scrollback depth (main screen only). */
    fun scrollbackLineCount(): Int = withEngine { core.scrollbackLineCount() }

    /** Drain OSC 52 clipboard-write requests emitted by guest programs (vim/tmux). */
    fun drainClipboardRequests(): List<String> =
        withEngine { core.drainClipboardRequests() }

    /**
     * T85：宿主应答回写通道（DA1/DA2/DSR-CPR）—— 透传给 TerminalCore。
     * 写入经 [engineLock] 串行化（NativeVtCore 的 responseSink 字段非 volatile，
     * 装配线程写 / pump 线程读的可见性由此保证）。
     * SessionManagerImpl 装配时接线为 nativeWrite；应答为终端自生字节，
     * 非用户/Agent 输入，不过策略门禁。
     */
    var responseSink: ((ByteArray) -> Unit)?
        get() = withEngine { core.responseSink }
        set(value) {
            withEngine { core.responseSink = value }
        }

    /**
     * Last visible (cursor) line as plain text — for InputWaiting heuristic (Spec §29).
     * The cursor row of the rendered screen, trimmed.
     */
    fun lastVisibleLine(): String = withEngine {
        val s = core.snapshot()
        val lines = s.renderedText.split('\n')
        lines.getOrElse(s.cursorRow) { "" }.trimEnd()
    }

    /**
     * T94（引擎生命周期收口）：释放底层引擎持有的 native 资源。
     *
     * 设备端引擎是 [com.apex.agent.vtnative.NativeVtCore]（JNI 句柄 → C++
     * Engine，含屏幕环/样式表/链接表，单会话数百 KB native 堆）。此前
     * close/recover/shutdown 全链路无人调 NativeVtCore.close() —— 每关一个
     * 会话泄漏一个引擎，且 VtFeedTrail 的 live-engine 计数永不归零，每次
     * 退出都被误报「疑似 native 崩溃」。纯 Kotlin 引擎（TerminalCore）无
     * native 资源，release 为 no-op —— 以 AutoCloseable 探测，不引入对
     * terminal-native 模块的硬依赖（保持本模块纯 JVM 可测）。
     *
     * 在 [engineLock] 内执行（串行化，与 pump/observe/resize 的并发访问
     * 互斥）；重复调用幂等（NativeVtCore.close 自身幂等）。
     */
    override fun release() {
        withEngine {
            runCatching { (core as? AutoCloseable)?.close() }
                .onFailure {
                    // 引擎销毁失败不再可恢复（句柄生命周期已终结）—— 记录后吞掉，
                    // 不让会话清理链中断（nativeCloseSession 仍在调用方序列中）。
                    System.err.println("RealVirtualTerminal: engine close failed: ${it.message}")
                }
        }
    }
}
