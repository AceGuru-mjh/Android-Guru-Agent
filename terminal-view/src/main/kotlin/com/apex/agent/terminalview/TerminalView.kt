package com.apex.agent.terminalview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import com.apex.agent.terminalemulator.KeyModifiers
import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.TerminalKey
import com.apex.agent.terminalemulator.TerminalMouseEventType
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * T88（2-a）：Termux 级终端 View —— Canvas 直绘（替代 LazyColumn+BasicText 渲染链）。
 *
 * ## 数据流（快照推送）
 *
 * 宿主把 `TerminalRenderSnapshot` 喂给 [submit]（UI 线程或任意线程 —— 内部 hop）。
 * View 只做三件事：合并行（scrollback+屏）、滚动模型代际推进（follow-bottom /
 * 锚定不偷位）、`postInvalidateOnAnimation`。绘制交给 [TerminalCanvasRenderer]
 * （只画可视行 + run 缓存 + textScaleX 列对齐校正 —— 详见其 KDoc）。
 *
 * ## 交互语义（Termux 对齐）
 *
 *  - 单击：OSC 8 链接/URL 自动识别 → 宿主打开；鼠标模式 → PRESS/RELEASE 报告；
 *    否则聚焦拉起 IME；
 *  - 双击：选词（路径/URL 友好词边界）；
 *  - 长按：起选 → 拖选扩选 → 抬手弹上下文菜单（复制/粘贴/全选）；
 *  - 滚动：单指位移 → 行滚动；甩动 → OverScroller 惯性（`verticalScrollBounce`
 *    开时边界有回弹衰减）；键入自动跳底（Termux `scrollForNewInput`）；
 *  - 捏合：字号 ±（1.25/0.8 阈值防抖）—— **T91 起默认关闭**
 *    （[TerminalViewSettings.pinchZoomEnabled]=false；捏合事件在 View 层被短路，
 *    字号调节走宿主设置页 Slider）；
 *  - 双指快击：鼠标模式右键；
 *  - 硬件键：Ctrl+字母 → 控制字节；Alt+键 → ESC 前缀；方向/F 键 → TerminalKey。
 *
 * ## 电池纪律
 *
 * 光标闪烁只在 `cursorVisible && 窗口聚焦` 时排帧（2 帧/秒 —— Handler 换相，
 * 不空转 Choreographer）；失焦/后台即停。
 *
 * 异常安全：所有宿主数据入口（submit/touch/key）都有防御 clamp —— malformed
 * 快照不允许炸绘制/手势。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ─── 宿主桥 / 配置 ───
    private var client: TerminalViewClient? = null
    private var settings = TerminalViewSettings()

    /** v0.3 OSC 10/11/12 动态色生效后的实际调色板（null 动态色 = settings.palette）。 */
    private var effectivePalette: TerminalPalette = settings.palette

    // ─── 数据（快照代际）───
    @Volatile
    private var pendingSnapshot: TerminalRenderSnapshot? = null

    /** submit 携带的调色板（跨线程 → 主线程应用后清空）。 */
    @Volatile
    private var pendingPalette: TerminalPalette? = null
    private var snapshot: TerminalRenderSnapshot? = null
    private var mergedRows: List<List<RenderCell>> = emptyList()
    private var rowIdBase: Long = 0L

    /** 渲染代际 id：快照换代 / 换肤 / 换字号都递增（run 缓存的失效键）。 */
    private var frameId = 0L
    private var lastBellSeq = 0L
    private var lastTitle: String? = null
    private var lastNotifiedTopRow = Int.MIN_VALUE
    private var lastNotifiedAtBottom = false

    // ─── 子模型（全部纯逻辑，可 JVM 测试）───
    private val scrollModel = TerminalScrollModel(2)
    private val selectionModel = TerminalSelectionModel()
    private val renderer = TerminalCanvasRenderer()
    private val keyModel = TerminalKeyInputModel
    private val gestureModel: TerminalGestureModel

    // ─── 滚动 / 手势基建 ───
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scroller = OverScroller(context)
    private var lastScrollerY = 0
    private var bounceOffsetPx = 0f
    private var pinchScaleAccum = 1f

    // ─── 度量 ───
    private var grid: TerminalTextGrid = TerminalTextGrid.compute(1, 1, 8f, 16f)
    private var cellHeightPx = 16f
    private var density = 1f

    // ─── 手势时序参数（与 gestureModel 同源）───
    private val longPressTimeoutMs: Long
    private val doubleTapTimeoutMs: Long

    // ─── 光标闪烁（[TerminalCursorBlink] —— T92 抽出）───
    private val cursorBlink = TerminalCursorBlink(
        handler = mainHandler,
        canBlink = { settings.cursorBlinks && snapshot?.cursorVisible == true &&
            hasWindowFocus() && isAttachedToWindow },
        onInvalidate = { invalidate() },
        intervalMs = { settings.cursorBlinkMs.toLong() }
    )

    // ─── resize 防抖 ───
    private var resizeRunnable: Runnable? = null

    // ─── 触摸位置跟踪（上下文菜单定位 / 鼠标 motion 坐标 / 滚轮节流）───
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var wheelAccumPx = 0f

    // ─── T92：拖动滚动余量累加器（Termux mScrollRemainder）───
    // 每个 MOVE 事件只携带本帧增量（典型 5~15px < 行高 ~48px），逐事件取整会
    // 丢弃余量 → 慢速拖动「纹丝不动」只有 fling 能滚。跨事件累加：满一行滚
    // 一行，余量留给下一帧。
    private var scrollRemainderPx = 0f

    // ─── T92：硬件死键组合重音（欧式键盘 acute/grave/tilde…）───
    private var pendingCombiningAccent = 0

    // ─── 系统手势排除（#B-④）───
    // Android 10+ 手势导航在屏幕左/右边缘保留了 quick-switch/返回滑区；
    // 终端滚回历史/拖选时贴边滑动会被系统抢走。仅在本 View 触摸会话
    // 进行中动态申请排除（DOWN 时申请、UP/CANCEL 撤销）—— 不做常驻全屏
    // 排除（Play 对滥用 systemGestureExclusionRects 有审核红线）。
    private val gestureExclusionRect = Rect()
    private var gestureExclusionActive = false

    private fun applyGestureExclusion(enable: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (enable == gestureExclusionActive) return
        gestureExclusionActive = enable
        if (enable) {
            gestureExclusionRect.set(0, 0, width, height)
            systemGestureExclusionRects = listOf(gestureExclusionRect)
        } else {
            systemGestureExclusionRects = emptyList()
        }
    }

    // ─── 无障碍（[TerminalViewA11y] —— T92 抽出）───
    private val a11y = TerminalViewA11y(this)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        density = resources.displayMetrics.density
        val vc = ViewConfiguration.get(context)
        val slop = vc.scaledTouchSlop.toFloat().coerceAtLeast(density * 8f)
        gestureModel = TerminalGestureModel(
            tapSlopPx = slop,
            longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong(),
            doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
            tapTimeoutMs = ViewConfiguration.getTapTimeout().toLong(),
            flingVelocityThreshold = density * 120f,
            // #B-⑥：捏合起手最小指距按密度换算（≈48dp）—— 并指/贴边误触
            // 起手阶段的距离比率噪声直接冻结捏合输出。
            minPinchStartDistPx = density * 48f
        )
        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
        doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Terminal"
    }

    // ═════════════════════ 宿主 API ═════════════════════

    /** 绑定宿主桥与初始配置（构造后必须先调它才会有 IO）。 */
    fun attach(client: TerminalViewClient, settings: TerminalViewSettings = TerminalViewSettings()) {
        this.client = client
        updateSettings(settings)
    }

    /** 换配置（换肤/调字号/改行为 —— 全量原子替换；换肤立即失效 run 缓存）。 */
    fun updateSettings(newSettings: TerminalViewSettings) {
        val fontChanged = newSettings.fontSizeSp != settings.fontSizeSp ||
            newSettings.typefaceStyle != settings.typefaceStyle ||
            newSettings.palette != settings.palette
        val styleChanged = fontChanged || newSettings.monochrome != settings.monochrome
        settings = newSettings
        effectivePalette = currentSnapshotPalette(newSettings.palette)
        if (styleChanged) frameId++
        if (width > 0 && height > 0) rebuildMetrics(fontChanged)
        invalidate()
    }

    /** 基于当前快照解析实际调色板（含 OSC 10/11/12 动态色覆写）。 */
    private fun currentSnapshotPalette(base: TerminalPalette): TerminalPalette =
        snapshot?.let { deriveDynamicPalette(it, base) } ?: base

    /**
     * 快照推送（宿主在状态流 collect 时调用；任意线程安全 —— 非主线程会 hop 到
     * 主线程执行代际推进）。同对象引用去重（宿主重发同一快照零成本）。
     *
     * @param palette 可选换肤（随本帧生效；动态色仍由快照优先覆写）
     */
    fun submit(snapshot: TerminalRenderSnapshot?, palette: TerminalPalette? = null) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applySubmit(snapshot, palette)
        } else {
            pendingSnapshot = snapshot
            if (palette != null) pendingPalette = palette
            mainHandler.post { applySubmit(pendingSnapshot, pendingPalette) }
        }
    }

    /** submit 的主线程落点（调色板应用 → 快照代际推进）。 */
    private fun applySubmit(s: TerminalRenderSnapshot?, palette: TerminalPalette?) {
        if (palette != null) {
            pendingPalette = null
            settings = settings.withPalette(palette)
            effectivePalette = currentSnapshotPalette(palette)
            frameId++
        }
        applySnapshot(s)
    }

    /** 只换调色板（换肤通道 —— 不动滚动/选区；run 缓存失效，本帧即生效）。 */
    fun setPalette(palette: TerminalPalette) {
        settings = settings.withPalette(palette)
        effectivePalette = currentSnapshotPalette(palette)
        frameId++
        invalidate()
    }

    /** 跳到底部（「跳到最新」浮标的 View 侧实现）。 */
    fun scrollToBottom() {
        stopScrollAnimation()
        if (scrollModel.snapToBottom()) {
            notifyScrollChanged()
            invalidate()
        }
    }

    /** 是否有选区。 */
    fun hasSelection(): Boolean = selectionModel.active

    /** 复制选区（→ [TerminalViewClient.onTerminalClipboardCopy]），随后清空。 */
    fun copySelection(): Boolean {
        val text = currentSelectedText() ?: return false
        client?.onTerminalClipboardCopy(text)
        clearSelection()
        return true
    }

    /** 清空选区。 */
    fun clearSelection() {
        if (selectionModel.active || selectionModel.pending) {
            selectionModel.clear()
            notifySelectionChanged(null)
            invalidate()
        }
    }

    /** 全选（可见网格全行 —— 从首行到末行）。 */
    fun selectAll() {
        if (mergedRows.isEmpty()) return
        selectionModel.start(rowIdBase, 0)
        selectionModel.extend(rowIdBase + mergedRows.size - 1, Int.MAX_VALUE / 4)
        notifySelectionChanged(currentSelectedText())
        invalidate()
    }

    /** 粘贴（宿主已读剪贴板 —— View 只归一换行并转发；括号策略宿主自理）。 */
    fun pasteFromClipboard(text: String) {
        if (text.isEmpty()) return
        client?.onTerminalWrite(text.replace('\n', '\r'))
        onUserTypedSomething()
    }

    /** 字号设置（clamp + 重探测 + 网格重建）。 */
    fun setFontSize(sp: Float) {
        val clamped = settings.clampFontSize(sp)
        if (clamped == settings.fontSizeSp) return
        settings = settings.withFontSize(clamped)
        rebuildMetrics(true)
        client?.onTerminalFontSizeChanged(clamped)
        invalidate()
    }

    /** 聚焦并拉起输入法（进入终端即敲 —— 部分设备 requestFocus 不弹 IME 的补招）。
     *
     * ★ 修复（键盘拉不起来）：旧实现用 SHOW_IMPLICIT —— 该标志语义是
     * 「隐式请求」（窗口焦点变化等被动场景），部分 ROM/输入法（尤其中文 IME）
     * 会直接忽略。改用 flags=0（显式用户请求），绝大多数 IME 都必须响应；
     * 首次失败后再用 SHOW_FORCED 兜一次（极端 ROM）。
     */
    fun requestFocusAndShowKeyboard() {
        runCatching { requestFocus() }
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imm == null) return
        runCatching {
            val shown = imm.showSoftInput(this, 0)
            if (!shown) imm.showSoftInput(this, InputMethodManager.SHOW_FORCED)
        }
    }

    /** ★ 修复（点按不弹键盘）：系统把「可编辑文本视图」识别为 tap-to-type 的
     * 通道就是 onCheckIsTextEditor —— 不覆写时点击 View 不会自动聚焦拉 IME
     *（旧实现全靠 handleTap 显式调 requestFocusAndShowKeyboard，链路一旦
     * 被 peek/scroll 手势拦截就断）。覆写后 tap-to-type 是系统级保障。 */
    override fun onCheckIsTextEditor(): Boolean = true

    /** 隐藏输入法（Back 键被 onKeyPreIme 拦截时用）。 */
    fun hideKeyboard() {
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    /** 当前 topRow（宿主「跳到最新」affordance 联动）。 */
    fun currentTopRow(): Int = scrollModel.topRow

    /** 是否贴底。 */
    fun isAtBottom(): Boolean = scrollModel.isAtBottom

    // ═════════════════════ 快照代际 ═════════════════════

    /** v0.3 OSC 10/11/12：guest 动态色覆写（仅非 null 槽位覆盖 —— vim
     *  `hi Normal guifg=…` 换肤即刻生效，无需宿主干预）。 */
    private fun deriveDynamicPalette(
        s: TerminalRenderSnapshot,
        base: TerminalPalette
    ): TerminalPalette {
        val dynFg = s.dynamicForeground
        val dynBg = s.dynamicBackground
        val dynCur = s.dynamicCursorColor
        if (dynFg == null && dynBg == null && dynCur == null) return base
        return base.copy(
            foreground = dynFg?.toInt() ?: base.foreground,
            background = dynBg?.toInt() ?: base.background,
            cursor = dynCur?.toInt() ?: base.cursor
        )
    }

    private fun applySnapshot(s: TerminalRenderSnapshot?) {
        if (s === null) {
            if (snapshot === null) return
            snapshot = null
            mergedRows = emptyList()
            invalidate()
            return
        }
        if (s === snapshot) return // 引用去重
        // 防御：malformed 尺寸不允许进入几何（崩溃红线）
        if (s.rows <= 0 || s.cols <= 0) return
        val wasAtBottom = scrollModel.isAtBottom
        val oldGridRows = scrollModel.gridRows
        val previousBase = snapshot?.scrollbackBase ?: s.scrollbackBase

        snapshot = s
        frameId++
        mergedRows = s.scrollback + s.lines
        rowIdBase = s.scrollbackBase - s.scrollback.size
        effectivePalette = deriveDynamicPalette(s, settings.palette)

        // 选区淘汰收缩
        if (selectionModel.onSnapshotScrolled(s.scrollbackBase, s.scrollback.size)) {
            notifySelectionChanged(null)
        }

        scrollModel.updateGridRows(mergedRows.size)
        if (s.scrollbackBase < previousBase) {
            // 引擎 reset（RIS/新会话）→ 快照代际重置：贴底重来
            scrollModel.snapToBottom()
            // T92：选区残留清理 —— 新会话的 rowId 与旧选区不相交时
            // onSnapshotScrolled 不会清（端点未被淘汰），选区高亮常驻但不可
            // 复制（首次点按被「清选区」吃掉）。代际重置 = 选区必清。
            if (selectionModel.active || selectionModel.pending) {
                selectionModel.clear()
                notifySelectionChanged(null)
            }
        } else {
            scrollModel.onContentGrew(mergedRows.size - oldGridRows, wasAtBottom)
        }
        notifyScrollChanged()

        // 响铃（bellSeq 单调增；宿主做振动/提示）
        if (s.bellSeq > lastBellSeq && s.bellSeq > 0L) {
            lastBellSeq = s.bellSeq
            client?.onTerminalBell()
        }
        // 标题（去重）
        val t = s.title
        if (t != null && t != lastTitle) {
            lastTitle = t
            client?.onTerminalTitle(t)
        }
        a11y.updateContent(s)
        // T92：鼠标模式动态跟随（vim 开关鼠标报告即生效，无需重新触摸）；
        // 首个带光标的快照到达时排闪烁（旧行为要等一次窗口焦点变化才闪）。
        gestureModel.immediateTapEnabled = s.mouseMode.enabled
        cursorBlink.scheduleIfNeeded()
        invalidate()
    }

    // ═════════════════════ 度量 / resize ═════════════════════

    private fun rebuildMetrics(fontChanged: Boolean) {
        density = resources.displayMetrics.density
        if (fontChanged) {
            val advance = renderer.onFontChanged(settings, density)
            cellHeightPx = settings.fontSizeSp * density * settings.lineHeightFactor
            grid = TerminalTextGrid.compute(
                width, height, advance, cellHeightPx, settings.wideSafetyFactor
            )
        }
        scheduleResizeNotify()
    }

    /** 网格几何推送（含光标锚定，语义见 TerminalScrollModel.onGridResized）+ PTY resize 防抖通知。 */
    private fun scheduleResizeNotify() {
        if (scrollModel.onGridResized(mergedRows.size.coerceAtLeast(grid.viewRows), grid.viewRows, cursorAnchorRow())) {
            notifyScrollChanged()
        }
        resizeRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            resizeRunnable = null
            client?.onTerminalViewSizeChanged(
                grid.viewRows, grid.viewCols, width.coerceAtLeast(0), height.coerceAtLeast(0)
            )
        }
        resizeRunnable = r
        mainHandler.postDelayed(r, settings.resizeDebounceMs.toLong().coerceAtLeast(50L))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        renderer.onFontChanged(settings, density)
        cellHeightPx = settings.fontSizeSp * density * settings.lineHeightFactor
        grid = TerminalTextGrid.compute(
            w, h, renderer.charAdvancePx, cellHeightPx, settings.wideSafetyFactor
        )
        scheduleResizeNotify()
        invalidate()
    }

    /** 光标合并网格行（无快照/空网格 → null）—— resize 锚定用。 */
    private fun cursorAnchorRow(): Int? = snapshot?.let { s ->
        mergedRows.takeIf { it.isNotEmpty() }
            ?.let { (s.scrollback.size + s.cursorRow).coerceIn(0, it.size - 1) }
    }

    // ═════════════════════ 绘制 ═════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snap = snapshot ?: run {
            renderer.draw(canvas, placeholderFrame())
            return
        }
        // 回弹衰减（fling 撞边界后的 12% 减幅弹回）
        if (abs(bounceOffsetPx) > 0.5f) {
            canvas.save()
            canvas.translate(0f, bounceOffsetPx)
            drawFrame(canvas, snap)
            canvas.restore()
            bounceOffsetPx *= 0.88f
            postInvalidateOnAnimation()
        } else {
            bounceOffsetPx = 0f
            drawFrame(canvas, snap)
        }
    }

    private fun drawFrame(canvas: Canvas, snap: TerminalRenderSnapshot) {
        val frame = TerminalCanvasRenderer.RenderFrame(
            rows = mergedRows,
            snapshot = snap,
            grid = grid,
            scroll = scrollModel,
            selection = selectionModel,
            settings = settings,
            palette = effectivePalette,
            focused = hasWindowFocus(),
            blinkOn = cursorBlink.on,
            selectionActive = selectionModel.active,
            viewWidthPx = width,
            viewHeightPx = height,
            density = density,
            frameId = frameId,
            rowIdBase = rowIdBase
        )
        renderer.draw(canvas, frame)
    }

    private fun placeholderFrame(): TerminalCanvasRenderer.RenderFrame =
        TerminalCanvasRenderer.RenderFrame(
            rows = emptyList(),
            snapshot = TerminalRenderSnapshot(
                rows = 0, cols = 0, cursorRow = 0, cursorCol = 0, cursorVisible = false,
                alternateScreen = false, applicationCursor = false, bracketedPaste = false,
                reverseVideo = false, title = null, lines = emptyList(), scrollback = emptyList(),
                scrollbackTotal = 0
            ),
            grid = grid,
            scroll = scrollModel,
            selection = selectionModel,
            settings = settings,
            palette = effectivePalette,
            focused = false,
            blinkOn = cursorBlink.on,
            selectionActive = false,
            viewWidthPx = width,
            viewHeightPx = height,
            density = density,
            frameId = frameId,
            rowIdBase = rowIdBase
        )

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        cursorBlink.scheduleIfNeeded()
        client?.onTerminalFocus(hasWindowFocus)
        // ★ T89 输入修复：窗口焦点恢复且本 View 持焦点时重拉 IME（Activity
        // 切回/弹层关闭后输入法被系统收走的经典场景；IME 仅在窗口有焦点时
        // 响应 show 请求）。仅在 IME 之前处于激活态时恢复 —— 不抢用户主动
        // 收起的键盘。
        if (hasWindowFocus && isFocused) {
            runCatching {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                if (imm?.isAcceptingText == true) imm.showSoftInput(this, 0)
            }
        }
        invalidate()
    }

    // ═════════════════════ 触摸 → 手势 ═════════════════════

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastTouchX = event.x
        lastTouchY = event.y
        val sample = event.toTouchSample() ?: return super.onTouchEvent(event)
        // #B-④：触摸会话进行中标记系统手势排除区（UP/CANCEL 撤销）。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> applyGestureExclusion(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> applyGestureExclusion(false)
        }
        // T90：捏合累子手势结束复位 —— 旧行为残留 1.05..1.24 的累积量带到下一次
        // 小捏合，凭空触发 ±1sp 步进（「缩放一坨」的直接根源之一）。任何手势
        // 结束/降指（UP/CANCEL/POINTER_UP）都视为捏合会话终结。
        // T92：滚动余量/滚轮累子同步清零 —— 跨手势残留会让下个手势首滚误发
        // 一行（wheelAccum）或带入旧余量（scrollRemainder）。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pinchScaleAccum = 1f
                scrollRemainderPx = 0f
                wheelAccumPx = 0f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                pinchScaleAccum = 1f
                scrollRemainderPx = 0f
                wheelAccumPx = 0f
            }
        }
        val decisions = gestureModel.feed(sample)
        for (d in decisions) handleGesture(d)
        scheduleGestureTimers()
        return true
    }

    /**
     * T92：外接鼠标滚轮（DeX/蓝牙鼠标/桌面模式）—— 旧行为完全无响应。
     * 每档 3 行（Termux doScroll(±3) 同量级）；鼠标模式下改发 WHEEL 报告
     *（vim/tmux 里滚轮翻它们的内部缓冲，而非本地 scrollback）。
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // M1：记录鼠标「指点位置」—— sendWheelEvents 用 lastTouch* 做 tmux 分屏
        // 路由，旧版只在 onTouchEvent 更新：外接鼠标滚轮恒路由到上一次手指触点
        // 或 (0,0)（分屏下必错 pane）。悬停/滚轮事件自带坐标，这里补上。
        lastTouchX = event.x
        lastTouchY = event.y
        if (event.actionMasked == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_MOUSE)
        ) {
            val vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vscroll != 0f && cellHeightPx > 0f) {
                val snap = snapshot
                if (snap != null && snap.mouseMode.enabled && !settings.mousePassthrough) {
                    // 滚轮向上（vscroll>0）= WHEEL_UP：不受触摸滚动的符号约定影响
                    val up = vscroll > 0f
                    val notches = (abs(vscroll) * 3f).toInt().coerceIn(1, 6)
                    repeat(notches) { sendWheelEvents(up) }
                } else {
                    // 本地滚动：滚轮向上看更老 = 手指下拖等价（正 delta）
                    handleScrollDelta(vscroll * 3f * cellHeightPx)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** 手势模型的时间转换需要宿主排程（长按/单击确认 tick）。 */
    private fun scheduleGestureTimers() {
        mainHandler.removeCallbacks(gestureTick)
        val delay = when {
            gestureModel.awaitingLongPress -> longPressTimeoutMs
            gestureModel.awaitingTapConfirm -> doubleTapTimeoutMs
            else -> return
        }
        mainHandler.postDelayed(gestureTick, delay + 1L)
    }

    private val gestureTick = Runnable {
        val decisions = gestureModel.tick(SystemClock.uptimeMillis())
        for (d in decisions) handleGesture(d)
    }

    private fun handleGesture(event: TerminalGestureModel.GestureEvent) {
        when (event) {
            is TerminalGestureModel.GestureEvent.Tap -> {
                performClick()
                handleTap(event.x, event.y)
            }
            is TerminalGestureModel.GestureEvent.DoubleTap -> {
                if (settings.doubleTapSelectsWord) handleDoubleTap(event.x, event.y)
                else handleTap(event.x, event.y)
            }
            is TerminalGestureModel.GestureEvent.LongPress -> {
                // T92：长按触觉反馈（Termux performHapticFeedback(LONG_PRESS)）——
                // 选词起选的物理确认；无振动器设备静默。
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                beginSelectionAt(event.x, event.y)
            }
            is TerminalGestureModel.GestureEvent.DragStart -> Unit // 起点已由 LongPress 落位
            is TerminalGestureModel.GestureEvent.DragMove ->
                extendSelectionAt(event.x, event.y)
            is TerminalGestureModel.GestureEvent.DragEnd ->
                finishSelectionWithMenu()
            is TerminalGestureModel.GestureEvent.Scroll ->
                handleScrollDelta(event.deltaYPx)
            is TerminalGestureModel.GestureEvent.Fling ->
                startScrollAnimation(event.velocityYPx)
            is TerminalGestureModel.GestureEvent.Pinch ->
                // T91（D1）：设置未显式开启时在 View 层短路 —— 捏合手势完全沉寂
                //（不进累子、不驱动 resizeTerminal），避免任何残留路径驱动字号。
                if (settings.pinchZoomEnabled) handlePinch(event.scale)
            is TerminalGestureModel.GestureEvent.TapSecondFinger ->
                handleSecondFingerTap(event.x, event.y)
        }
    }

    private fun handleScrollDelta(deltaYPx: Float) {
        stopScrollAnimation()
        val snap = snapshot
        // 鼠标模式：触摸滚 → 滚轮编码（vim 翻屏），按行节流（一 MOVE 样本一事件
        // 会风暴 —— 攒满一行才发一次，与物理滚轮档位密度一致）
        if (snap != null && snap.mouseMode.enabled) {
            if (settings.mousePassthrough) {
                // 透传模式：拖动即 MOTION（button 0 按下）
                cellAt(lastTouchX, lastTouchY)?.let { (row, col) ->
                    dispatchMouse(row, col, TerminalMouseEventType.MOTION, button = 0, released = false)
                }
            } else {
                wheelAccumPx += deltaYPx
                if (abs(wheelAccumPx) >= cellHeightPx) {
                    // H2：正 delta（手指下拖 = 看更老内容）→ WHEEL_UP —— 与本地
                    // 滚动（scrollBy(-rows) 看更老）和物理滚轮（vscroll>0 = UP）
                    // 同向。旧版取 `accum < 0` 把方向发反了。
                    sendWheelEvents(wheelAccumPx > 0f)
                    wheelAccumPx = 0f
                }
            }
            return
        }
        // 备用屏无 scrollback：滚动手势 → 方向键（Termux 1007 altScroll 近似，
        // 每标准 3 行一档 —— 用户在 less/vim 里滚即翻页）。
        // H3 修复：① 方向取反 —— 正 delta（手指下拖/滚轮上，等价「看更老」）
        //    应发 UP，旧版发 DOWN，与本地滚动、物理滚轮方向相反；
        // ② 余量累计满 3 行才发一键 —— 旧版 `+1` 下限让每个 MOVE 事件都发
        //    ≥1 键，慢拖 60~120Hz 直接轰炸 PTY（本地路径 T92 已修累计，
        //    此分支漏修）。
        if (snap != null && snap.alternateScreen && scrollModel.maxScrollUp == 0) {
            val total = deltaYPx + scrollRemainderPx
            val rows = TerminalScrollModel.rowsForDelta(total, cellHeightPx)
            val notches = (abs(rows) / 3).coerceAtMost(6)
            val consumedRows = notches * 3 * (if (rows > 0) 1 else -1)
            scrollRemainderPx = total - consumedRows * cellHeightPx
            if (notches > 0) {
                val key = if (rows > 0) TerminalKey.UP else TerminalKey.DOWN
                repeat(notches) { client?.onTerminalKey(key, 0) }
            }
            return
        }
        // T92：余量累加（Termux mScrollRemainder）—— 单帧 MOVE 增量几乎总小于
        // 一行高，旧行为逐事件取整丢余量 → 慢/中速拖动不滚动（只能靠 fling）。
        // 跨事件累加：总位移满一行滚一行，余量滚到下一帧。
        val total = deltaYPx + scrollRemainderPx
        val rows = TerminalScrollModel.rowsForDelta(total, cellHeightPx)
        scrollRemainderPx = total - rows * cellHeightPx
        if (rows != 0 && scrollModel.scrollBy(-rows)) {
            notifyScrollChanged()
            invalidate()
        }
    }

    private fun startScrollAnimation(velocityYPx: Float) {
        // T92：鼠标模式 fling = 滚轮连发（Termux mouseTrackingAtStartOfFling）。
        // 旧行为 fling 一律滚本地 scrollback/回弹 —— vim 开鼠标模式时拖动能翻
        // 但一甩就滚本地历史，与刚发生的滚轮语义打架。
        val snap = snapshot
        if (snap != null && snap.mouseMode.enabled && !settings.mousePassthrough) {
            // 行数与速度成比例、限 ±半屏（避免一次甩动轰炸几百行滚轮事件）
            val rowsBySpeed = (abs(velocityYPx) / cellHeightPx * 0.25f).toInt()
            val rows = rowsBySpeed.coerceIn(1, snap.rows / 2).coerceAtLeast(1)
            // H2：手指下甩（velocity > 0，本地滚动看更老）→ WHEEL_UP ——
            // 旧版 `velocity < 0` 方向发反。
            val up = velocityYPx > 0f
            repeat(rows) {
                sendWheelEvents(up)
                // 每行一次可短暂停顿 —— 直接连发，事件量 ≤ 半屏可控
            }
            return
        }
        if (!scrollModel.canScroll()) {
            // 无滚动量 + 开回弹 → 视觉反馈（fling 撞底）
            if (settings.verticalScrollBounce) {
                bounceOffsetPx = (velocityYPx / 40f).coerceIn(-density * 28f, density * 28f)
                postInvalidateOnAnimation()
            }
            return
        }
        lastScrollerY = 0
        scroller.fling(0, 0, 0, velocityYPx.toInt(), 0, 0, Int.MIN_VALUE / 8, Int.MAX_VALUE / 8)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (!scroller.isFinished) {
            scroller.computeScrollOffset()
            val dy = scroller.currY - lastScrollerY
            lastScrollerY = scroller.currY
            val rows = TerminalScrollModel.rowsForDelta(dy.toFloat(), cellHeightPx)
            val moved = rows != 0 && scrollModel.scrollBy(-rows)
            if (moved) {
                notifyScrollChanged()
                invalidate()
                postInvalidateOnAnimation()
            } else {
                // 撞边界：弹性反馈（可配）+ 停动画
                if (settings.verticalScrollBounce && abs(dy) > 2) {
                    bounceOffsetPx = (dy / 6f).coerceIn(-density * 28f, density * 28f)
                }
                scroller.abortAnimation()
                invalidate()
            }
        } else if (abs(bounceOffsetPx) > 0.5f) {
            postInvalidateOnAnimation()
        }
    }

    private fun stopScrollAnimation() {
        if (!scroller.isFinished) scroller.abortAnimation()
        bounceOffsetPx = 0f
    }

    private fun handlePinch(scale: Float) {
        if (!settings.pinchZoomEnabled) return
        // T92：选区激活时豁免 —— 选区中双指误触会触发 ±1sp 字号步进 →
        // resize + rewrap + 选区跳位（Termux 直接 return 同款）。
        if (selectionModel.active || selectionModel.pending) return
        pinchScaleAccum *= scale.coerceIn(0.5f, 2f)
        when {
            pinchScaleAccum >= 1.25f -> {
                pinchScaleAccum = 1f
                stepFontSize(+1)
            }
            pinchScaleAccum <= 0.8f -> {
                pinchScaleAccum = 1f
                stepFontSize(-1)
            }
            else -> Unit
        }
    }

    private fun stepFontSize(delta: Int) {
        val next = settings.stepFontSize(delta)
        if (next == settings.fontSizeSp) return
        settings = settings.withFontSize(next)
        rebuildMetrics(true)
        client?.onTerminalFontSizeChanged(next)
        invalidate()
    }

    /** 当前网格尺寸（rows×cols；未真实布局时 null —— 宿主据此避免把 2×4 的
     * 占位网格误报给 PTY）。会话切换重握手用（T90：后台创建的会话从未收到
     * resize，一直以 24×80 悬空 → 80 列行在窄屏右裁 + 短网格浮在长视口里）。 */
    fun currentGridSize(): TerminalGridSize? {
        if (width <= 0 || height <= 0 || !isLaidOut) return null
        return TerminalGridSize(grid.viewRows, grid.viewCols)
    }

    /** 网格尺寸快照（[currentGridSize] 返回值）。 */
    data class TerminalGridSize(val rows: Int, val cols: Int)

    private fun handleSecondFingerTap(x: Float, y: Float) {
        val snap = snapshot ?: return
        if (!snap.mouseMode.enabled) return
        val cell = cellAt(x, y) ?: return
        // 右键（button=2）：PRESS + RELEASE
        dispatchMouse(cell.first, cell.second, TerminalMouseEventType.PRESS, button = 2, released = false)
        dispatchMouse(cell.first, cell.second, TerminalMouseEventType.RELEASE, button = 2, released = true)
    }

    // ═════════════════════ 点击语义（链接/URL/鼠标/键盘）═════════════════════

    private fun handleTap(x: Float, y: Float) {
        val snap = snapshot
        // 1) 选区激活 → 点选区外 = 清空（不再拉键盘 —— 两段式防误触）
        if (selectionModel.active) {
            clearSelection()
            return
        }
        if (snap == null) {
            requestFocusAndShowKeyboard()
            return
        }
        // 2) OSC 8 链接优先（点击即打开，不拉键盘）
        cellAt(x, y)?.let { (row, col) ->
            val cells = mergedRows.getOrNull(row)
            val cell = cells?.getOrNull(col)
            if (cell != null && cell.link != 0) {
                snap.linkTable[cell.link]?.let { uri ->
                    client?.onTerminalLinkOpen(uri)
                    return
                }
            }
            // 3) URL 自动识别：命中词以 http(s):// 开头 → 菜单（打开/复制链接）
            if (settings.doubleTapSelectsWord) {
                val word = wordTextAt(row, col)
                if (word != null && URL_PATTERN.matcher(word).find()) {
                    showContextMenuFor(word, x, y)
                    return
                }
            }
            // 4) 鼠标模式：触摸点击 → PRESS/RELEASE 报告（旧渲染器同款语义）
            if (snap.mouseMode.enabled) {
                val screenRow = row - snap.scrollback.size
                if (screenRow >= 0) {
                    dispatchMouse(row, col, TerminalMouseEventType.PRESS, button = 0, released = false)
                    dispatchMouse(row, col, TerminalMouseEventType.RELEASE, button = 0, released = true)
                    return
                }
            }
        }
        // 5) 常规：聚焦拉输入法
        requestFocusAndShowKeyboard()
    }

    private fun handleDoubleTap(x: Float, y: Float) {
        val bounds = wordSelectionAt(x, y) ?: return
        selectionModel.start(rowIdBase + bounds.first.first, bounds.first.second)
        selectionModel.extend(rowIdBase + bounds.second.first, bounds.second.second)
        notifySelectionChanged(currentSelectedText())
        invalidate()
    }

    /**
     * T92：词选公共路径（几何在 [TerminalWordGeometry]；双击与长按共用 ——
     * 长按起选即整词）。返回 ((起始行, 起始列), (结束行, 结束列))（合并网格行 + VT 列）。
     */
    private fun wordSelectionAt(x: Float, y: Float): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val at = cellAt(x, y) ?: return null
        val (row, col) = at
        val cells = mergedRows.getOrNull(row) ?: return null
        val span = TerminalWordGeometry.wordSpanAt(cells, col) { idx, text ->
            selectionModel.expandToWord(rowIdBase + row, idx, text)
        } ?: return null
        return (row to span.first) to (row to span.second)
    }

    private fun beginSelectionAt(x: Float, y: Float) {
        // T92：长按 = 词选起手（Termux TextSelectionCursorController）：长按落点
        // 立即扩词两侧扫到非词字符 —— 旧行为只落单 cell（首帧无高亮、复制不到
        // 整词）。
        val word = wordSelectionAt(x, y)
        if (word != null) {
            selectionModel.start(rowIdBase + word.first.first, word.first.second)
            selectionModel.extend(rowIdBase + word.second.first, word.second.second)
        } else {
            val at = cellAt(x, y) ?: return
            selectionModel.start(rowIdBase + at.first, at.second)
        }
        notifySelectionChanged(currentSelectedText())
        invalidate()
    }

    private fun extendSelectionAt(x: Float, y: Float) {
        if (!selectionModel.pending && !selectionModel.active) return
        // T92：拖选边缘自动翻屏（Termux updatePosition 边缘滚动）—— 手指拖出
        // 视口上/下沿时逐 MOVE 滚一行，选区端点跟随新几何延伸（旧行为端点被
        // cellAt 钉死在视口边，选不到视口外内容）。
        var ey = y
        val edge = cellHeightPx
        if (y < edge && scrollModel.topRow > -scrollModel.maxScrollUp) {
            if (scrollModel.scrollBy(-1)) {
                notifyScrollChanged(); invalidate()
            }
            ey = edge
        } else if (y > height - edge && !scrollModel.isAtBottom) {
            if (scrollModel.scrollBy(1)) {
                notifyScrollChanged(); invalidate()
            }
            ey = height - edge
        }
        val at = cellAt(x, ey) ?: return
        selectionModel.extend(rowIdBase + at.first, at.second)
        invalidate()
    }

    private fun finishSelectionWithMenu() {
        if (!selectionModel.active) return
        val text = currentSelectedText()
        notifySelectionChanged(text)
        client?.onTerminalContextMenu(
            defaultSelectionMenuItems(!text.isNullOrEmpty()), lastTouchX, lastTouchY
        )
    }

    private fun showContextMenuFor(url: String, x: Float, y: Float) {
        lastDetectedUrl = url
        client?.onTerminalContextMenu(urlMenuItems(), x, y)
    }

    /** 最近一次 URL 自动识别的命中（宿主菜单点击 ID_COPY_LINK/OPEN_LINK 后调
     *  [openDetectedUrl]/[copyDetectedUrl]）。 */
    @Volatile
    private var lastDetectedUrl: String? = null

    /** 宿主菜单回调：打开最近识别的链接。 */
    fun openDetectedUrl() {
        lastDetectedUrl?.let { client?.onTerminalLinkOpen(it) }
        lastDetectedUrl = null
    }

    /** 宿主菜单回调：复制最近识别的链接。 */
    fun copyDetectedUrl() {
        lastDetectedUrl?.let { client?.onTerminalClipboardCopy(it) }
        lastDetectedUrl = null
    }

    /** 宿主上下文菜单点击路由（Copy/Paste/SelectAll/Clear 四类内置 id）。 */
    fun onContextMenuAction(itemId: String): Boolean {
        when (itemId) {
            TerminalContextMenuItem.ID_COPY -> return copySelection()
            TerminalContextMenuItem.ID_PASTE -> {
                client?.onTerminalPasteRequest()
                return true
            }
            TerminalContextMenuItem.ID_SELECT_ALL -> {
                selectAll()
                return true
            }
            TerminalContextMenuItem.ID_CLEAR_SELECTION -> {
                clearSelection()
                return true
            }
            TerminalContextMenuItem.ID_OPEN_LINK -> {
                openDetectedUrl()
                return true
            }
            TerminalContextMenuItem.ID_COPY_LINK -> {
                copyDetectedUrl()
                return true
            }
            else -> return false
        }
    }

    // ═════════════════════ 鼠标报告 ═════════════════════
    // 编码出口缝拆在 TerminalMouseReport（守 1200 行预算）—— 这里只取快照/触点。

    private fun dispatchMouse(
        mergedRow: Int,
        col: Int,
        type: TerminalMouseEventType,
        button: Int,
        released: Boolean
    ) {
        val snap = snapshot ?: return
        TerminalMouseReport.dispatch(snap, client, mergedRow, col, type, button, released)
    }

    private fun sendWheelEvents(up: Boolean) {
        val snap = snapshot ?: return
        // 滚轮报告位置 = 最近触点/指点位置（tmux 分屏路由正确 pane；M1 已让
        // 外接鼠标的悬停/滚轮坐标也写进 lastTouch*）。
        TerminalMouseReport.wheel(snap, client, cellAt(lastTouchX, lastTouchY), up)
    }

    // ═════════════════════ 几何辅助 ═════════════════════

    /** 像素 → (合并网格行, VT 列)。 */
    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (mergedRows.isEmpty() || cellHeightPx <= 0f) return null
        val viewRow = grid.rowAt(y - bounceOffsetPx, mergedRows.size - 1)
        val mergedRow = scrollModel.firstVisibleRow + viewRow
        if (mergedRow < 0 || mergedRow >= mergedRows.size) return null
        val cells = mergedRows[mergedRow]
        return mergedRow to grid.columnAt(cells, x.coerceIn(0f, grid.widthPx))
    }

    /** 命中词文本（URL 自动识别；几何在 [TerminalWordGeometry]）。 */
    private fun wordTextAt(row: Int, col: Int): String? {
        val cells = mergedRows.getOrNull(row) ?: return null
        val text = TerminalWordGeometry.textAt(cells)
        if (text.isEmpty()) return null
        val charIdx = TerminalWordGeometry.charIndexOfCol(cells, col).coerceAtMost(text.length - 1)
        val (ws, we) = selectionModel.expandToWord(0, charIdx, text)
        if (we <= ws) return null
        return text.substring(ws.coerceIn(0, text.length), we.coerceIn(0, text.length))
    }

    private fun currentSelectedText(): String? {
        if (!selectionModel.active) return null
        return selectionModel.selectedText { rowId ->
            val idx = (rowId - rowIdBase).toInt()
            mergedRows.getOrNull(idx)
        }
    }

    private fun notifySelectionChanged(text: String?) {
        client?.onTerminalSelectionChanged(text)
    }

    private fun notifyScrollChanged() {
        if (scrollModel.topRow != lastNotifiedTopRow || scrollModel.isAtBottom != lastNotifiedAtBottom) {
            lastNotifiedTopRow = scrollModel.topRow
            lastNotifiedAtBottom = scrollModel.isAtBottom
            client?.onTerminalScrollChanged(scrollModel.topRow, scrollModel.isAtBottom)
        }
    }

    // ═════════════════════ 硬件键盘 ═════════════════════

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        event ?: return super.onKeyDown(keyCode, event)
        if (dispatchHardwareKeyEvent(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    /** IME 的 sendKeyEvent 与 onKeyDown 共用（返回 true = 消费）。纯决策在 [TerminalKeyEventRouter]。 */
    fun dispatchHardwareKeyEvent(event: KeyEvent): Boolean {
        val pre = TerminalKeyEventRouter.preMap(
            event, pendingCombiningAccent, scrollModel.maxScrollUp > 0
        )
        return when (pre) {
            is TerminalKeyEventRouter.PreMap.PageScroll -> {
                val dy = if (pre.up) -grid.viewRows else grid.viewRows
                if (scrollModel.scrollBy(dy)) {
                    notifyScrollChanged()
                    invalidate()
                }
                true
            }
            is TerminalKeyEventRouter.PreMap.DeadAccent -> {
                pendingCombiningAccent = pre.accent
                true
            }
            is TerminalKeyEventRouter.PreMap.ComposedAccent -> {
                pendingCombiningAccent = 0
                client?.onTerminalWrite(pre.text)
                onUserTypedSomething()
                true
            }
            is TerminalKeyEventRouter.PreMap.Hardware -> {
                if (pre.clearAccent) pendingCombiningAccent = 0
                val hw = TerminalKeyInputModel.HardwareKey(
                    keyCodeLabel = pre.keyCodeLabel,
                    mods = pre.mods,
                    unicodeChar = pre.unicodeChar
                )
                when (val mapped = keyModel.map(hw)) {
                    is TerminalKeyInputModel.MappedInput.TerminalKeyInput -> {
                        client?.onTerminalKey(mapped.key, mapped.mods)
                        onUserTypedSomething()
                        true
                    }
                    is TerminalKeyInputModel.MappedInput.ControlChar -> {
                        client?.onTerminalControlChar(mapped.code)
                        onUserTypedSomething()
                        true
                    }
                    is TerminalKeyInputModel.MappedInput.CharInput -> {
                        val payload = if (mapped.mods and KeyModifiers.ALT != 0) {
                            "\u001B${mapped.text}" // Alt → ESC 前缀（bash 词跳等）
                        } else mapped.text
                        client?.onTerminalWrite(payload)
                        onUserTypedSomething()
                        true
                    }
                    TerminalKeyInputModel.MappedInput.Unmapped -> false
                }
            }
        }
    }

    /** 键入即跳底（Termux scrollForNewInput）+ 闪烁相位复位。 */
    private fun onUserTypedSomething() {
        if (settings.scrollToBottomOnInput && !scrollModel.isAtBottom) {
            scrollModel.scrollForNewInput()
            notifyScrollChanged()
            invalidate()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // 修饰键抬键不拦截；字符已在 Down 处理
        return super.onKeyUp(keyCode, event)
    }

    /** IME 拦截（Back 收键盘而非退出终端页）。 */
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm?.isAcceptingText == true) {
                hideKeyboard()
                return true
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    // ═════════════════════ IME 桥 ═════════════════════

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        TerminalInputConnection.populateEditorInfo(outAttrs)
        return TerminalInputConnection(this)
    }

    /** IME 组合/提交文本（TerminalInputConnection 差分桥的落点；\n 已在桥侧
     *  归一为 \r —— 此处原样直通 RAW 写入）。 */
    fun handleImeCompose(text: String) {
        if (text.isEmpty()) return
        client?.onTerminalWrite(text)
        onUserTypedSomething()
    }

    /** IME 退格（deleteSurroundingText 的每字符一击）。 */
    fun handleImeBackspace() {
        client?.onTerminalControlChar(0x7F)
        onUserTypedSomething()
    }

    /** IME 前删（Delete 语义）。 */
    fun handleImeDeleteForward() {
        client?.onTerminalKey(TerminalKey.DELETE, 0)
        onUserTypedSomething()
    }

    // ═════════════════════ 无障碍 / 生命周期 ═════════════════════

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        applyGestureExclusion(false)
        mainHandler.removeCallbacksAndMessages(null)
        scroller.abortAnimation()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cursorBlink.scheduleIfNeeded()
    }

    companion object {
        /** URL 自动识别（命中词内 http(s):// 前缀）。 */
        private val URL_PATTERN: Pattern = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE)
    }
}
