package com.apex.agent.terminalview

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * T88（2-a）：触摸手势分类状态机（纯 JVM —— 无 MotionEvent 依赖）。
 *
 * ## 为什么抽成纯状态机
 *
 * View 把 `MotionEvent` 清洗成 [TouchSample] 喂进来，本机输出 [GestureEvent]
 * 决策 —— 判定规则（slop/双击窗/长按超时/速度估计/捏合旋转拒绝）因此可以
 * 脱离 Android 逐条单测（CI 只跑纯 JVM）。View 只做「事件 → 样本」和
 * 「决策 → 终端动作」两件机械事。
 *
 * ## 分类语义（Termux 对齐）
 *
 *  - **Tap / DoubleTap**：单击延迟 doubleTapTimeout 派发（给双击让路 —— 与
 *    Compose `detectTapGestures` 同款延迟策略，双击选词不会先弹键盘）；
 *  - **Scroll**：单指位移超 slop 后连续派发（携带**手指位移增量**，正 = 手指向下
 *    = 看更老内容；View 负责换算成行数并取反喂 `TerminalScrollModel.scrollBy`）；
 *  - **LongPress → Drag***：长按起选（不先动一下再选 —— slop 期间按住即计时），
 *    其后 MOVE 全部转拖选；
 *  - **Fling**：SCROLLING 态抬指且速度过阈值（最近样本**按新旧加权**估计，
 *    老样本权重指数衰减 —— 模拟 VelocityTracker 的 recency 偏置）；
 *  - **Pinch**：双指距离比率（增量派发，View 累计过 1.25/0.8 阈值）；旋转角超
 *    拒绝阈 → 判定为转屏/双指乱转，冻结捏合输出；
 *  - **TapSecondFinger**：主指按住期间第二指快击 —— 鼠标模式下的右键。
 *
 * 时间由调用方喂（[feed] 带 tMs；[tick] 用于长按/单击确认的**无事件定时转换**），
 * 状态机自身零线程/零 Handler。
 */
class TerminalGestureModel(
    /** tap/滚动 slop（px）。 */
    private val tapSlopPx: Float = 24f,
    /** 长按超时（ms）。 */
    private val longPressTimeoutMs: Long = 400L,
    /** 双击窗（ms）。 */
    private val doubleTapTimeoutMs: Long = 300L,
    /** 单击抬起后允许的最大滞留（tap 判定窗，ms）。 */
    private val tapTimeoutMs: Long = 180L,
    /** fling 触发速度阈值（px/s）。 */
    private val flingVelocityThreshold: Float = 350f,
    /** 速度估计窗口（ms）。 */
    private val velocityWindowMs: Long = 120L,
    /** 捏合旋转拒绝角（度）。 */
    private val rotationRejectDeg: Float = 32f,
    /** 捏合起手最小指距（px）：两指几乎同时落下但初始距离过近（如并指误触）时
     * 距离比率噪声极大 —— 直接冻结捏合输出（仍保留第二指快击语义），
     * 避免起手阶段的 scale 抖动误触发 ±1sp 步进。 */
    private val minPinchStartDistPx: Float = 96f
) {
    /** 清洗后的触摸样本（View 由 MotionEvent 构造）。
     *
     * 坐标约定：[DOWN]/[UP] 是**主指**（首根按下的手指）；[MOVE] 单指时是主指、
     * 多指时是**第二指**（捏合距离跟踪）；[POINTER_DOWN] 的坐标是**新按下**的
     * 第二指；[POINTER_UP] 的坐标是**正在抬起**的那根指（[pointerId] 指明是哪根
     * —— View 从 MotionEvent.getPointerId(actionIndex) 取）。 */
    data class TouchSample(
        val x: Float,
        val y: Float,
        val tMs: Long,
        val action: TouchAction,
        /** 当前总手指数（含刚按下/刚抬起的那根）。 */
        val pointerCount: Int = 1,
        /** 事件所属指（POINTER_DOWN 的新指 / POINTER_UP 的抬指）。 */
        val pointerId: Int = 0
    )

    /** 触摸动作（MotionEvent 的去 Android 化投影）。 */
    enum class TouchAction { DOWN, MOVE, UP, CANCEL, POINTER_DOWN, POINTER_UP }

    /** 手势决策（View 据此驱动选区/滚动/菜单/鼠标报告）。 */
    sealed class GestureEvent {
        /** 单击（已过双击窗确认）。 */
        data class Tap(val x: Float, val y: Float) : GestureEvent()

        /** 双击。 */
        data class DoubleTap(val x: Float, val y: Float) : GestureEvent()

        /** 长按（选区起点 —— View 随后进入拖选）。 */
        data class LongPress(val x: Float, val y: Float) : GestureEvent()

        /** 拖选开始（LongPress 之后的首次语义 MOVE 之前由 View 自行补齐）。 */
        data class DragStart(val x: Float, val y: Float) : GestureEvent()

        /** 拖选移动。 */
        data class DragMove(val x: Float, val y: Float) : GestureEvent()

        /** 拖选结束。 */
        data class DragEnd(val x: Float, val y: Float) : GestureEvent()

        /** 滚动增量（px；**手指位移**语义：正 = 手指向下 = 看更老内容）。 */
        data class Scroll(val deltaYPx: Float) : GestureEvent()

        /** 惯性甩动（px/s，符号同上：正 = 手指原运动方向向下）。 */
        data class Fling(val velocityYPx: Float) : GestureEvent()

        /** 双指捏合增量（scale = 本次距离/上次距离；焦点为双指中点）。 */
        data class Pinch(val scale: Float, val focusX: Float, val focusY: Float) : GestureEvent()

        /** 第二指快击（主指仍在）—— 鼠标模式右键。 */
        data class TapSecondFinger(val x: Float, val y: Float) : GestureEvent()
    }

    private enum class State {
        IDLE, DOWN, SCROLLING, DRAGGING, MULTI, MULTI_TRAIL, TAP_PENDING
    }

    private var state = State.IDLE

    // DOWN/TAP_PENDING 公共锚
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var doubleTapCandidate = false
    private var dragStarted = false

    // TAP_PENDING
    private var pendingTapX = 0f
    private var pendingTapY = 0f
    private var pendingTapT = 0L

    // MULTI（捏合 + 第二指快击）
    private var multiFirstX = 0f
    private var multiFirstY = 0f
    private var multiSecondX = 0f
    private var multiSecondY = 0f
    private var multiBaseDist = 0f
    private var multiBaseAngle = 0f
    private var multiSecondDownT = 0L
    private var multiSecondMoved = false
    private var multiSecondPointerId = 0
    private var pinchRejected = false

    // 速度估计环
    private val velT = LongArray(VEL_BUFFER)
    private val velY = FloatArray(VEL_BUFFER)
    private var velCount = 0
    private var velHead = 0

    /** 是否处于拖选（View 判断长按后 MOVE 是否扩选）。 */
    val dragging: Boolean get() = state == State.DRAGGING

    /** 是否在等长按判定（View 据此排长按 tick）。 */
    val awaitingLongPress: Boolean get() = state == State.DOWN

    /** 是否在等单击确认/双击窗口（View 据此排确认 tick）。 */
    val awaitingTapConfirm: Boolean get() = state == State.TAP_PENDING

    /** 复位（CANCEL/宿主主动取消）。 */
    fun reset() {
        state = State.IDLE
        doubleTapCandidate = false
        pinchRejected = false
        dragStarted = false
        velCount = 0
    }

    /**
     * 喂一个样本；返回 0..n 个决策（一个样本可能触发多个，如 UP → Fling）。
     */
    fun feed(sample: TouchSample): List<GestureEvent> {
        val out = ArrayList<GestureEvent>(2)
        when (sample.action) {
            TouchAction.DOWN -> onDown(sample, out)
            TouchAction.MOVE -> onMove(sample, out)
            TouchAction.UP -> onUp(sample, out)
            TouchAction.CANCEL -> reset()
            TouchAction.POINTER_DOWN -> onPointerDown(sample, out)
            TouchAction.POINTER_UP -> onPointerUp(sample, out)
        }
        return out
    }

    /**
     * 时间推进（View 的长按 Runnable / 单击确认 Runnable 调用）。
     * 触发：长按超时（DOWN 态）、单击确认超时（TAP_PENDING 态）。
     */
    fun tick(nowMs: Long): List<GestureEvent> {
        val out = ArrayList<GestureEvent>(1)
        if (state == State.DOWN && nowMs - downT >= longPressTimeoutMs) {
            state = State.DRAGGING
            dragStarted = false
            out.add(GestureEvent.LongPress(lastX, lastY))
        } else if (state == State.TAP_PENDING && nowMs - pendingTapT >= doubleTapTimeoutMs) {
            state = State.IDLE
            out.add(GestureEvent.Tap(pendingTapX, pendingTapY))
        }
        return out
    }

    // ─── 状态转换 ───

    private fun onDown(s: TouchSample, out: MutableList<GestureEvent>) {
        if (state == State.TAP_PENDING) {
            // 双击候选：在窗内且落点贴近首击
            val near = hypot(s.x - pendingTapX, s.y - pendingTapY) <= tapSlopPx * 2
            if (near && s.tMs - pendingTapT <= doubleTapTimeoutMs) {
                doubleTapCandidate = true
            } else {
                // 首击已成单击（不会再有双击）
                out.add(GestureEvent.Tap(pendingTapX, pendingTapY))
                doubleTapCandidate = false
            }
            state = State.DOWN
        } else {
            state = State.DOWN
        }
        downX = s.x; downY = s.y; downT = s.tMs
        lastX = s.x; lastY = s.y
        velCount = 0
    }

    private fun onMove(s: TouchSample, out: MutableList<GestureEvent>) {
        when (state) {
            State.DOWN -> {
                val moved = hypot(s.x - downX, s.y - downY) > tapSlopPx
                if (moved) {
                    if (doubleTapCandidate) doubleTapCandidate = false // 移动即废双击
                    state = State.SCROLLING
                    out.add(GestureEvent.Scroll(s.y - downY)) // 首段携带锚点以来的位移
                    pushVel(s.tMs, s.y)
                } else {
                    lastX = s.x; lastY = s.y // 长按位置跟随（微移不影响长按判定）
                }
            }
            State.SCROLLING -> {
                out.add(GestureEvent.Scroll(s.y - lastY))
                pushVel(s.tMs, s.y)
            }
            State.DRAGGING -> {
                if (!dragStarted) {
                    dragStarted = true
                    out.add(GestureEvent.DragStart(s.x, s.y))
                }
                out.add(GestureEvent.DragMove(s.x, s.y))
            }
            State.MULTI -> {
                updateMulti(s.x, s.y, out)
            }
            State.MULTI_TRAIL, State.TAP_PENDING, State.IDLE -> {
                lastX = s.x; lastY = s.y
            }
        }
        lastX = s.x; lastY = s.y
    }

    private fun onUp(s: TouchSample, out: MutableList<GestureEvent>) {
        dragStarted = false
        when (state) {
            State.DOWN -> {
                if (doubleTapCandidate && s.tMs - downT <= tapTimeoutMs + longPressTimeoutMs / 2) {
                    state = State.IDLE
                    doubleTapCandidate = false
                    out.add(GestureEvent.DoubleTap(s.x, s.y))
                } else {
                    state = State.TAP_PENDING
                    doubleTapCandidate = false
                    pendingTapX = s.x; pendingTapY = s.y; pendingTapT = s.tMs
                }
            }
            State.SCROLLING -> {
                state = State.IDLE
                val v = estimateVelocityY(s.tMs)
                if (abs(v) >= flingVelocityThreshold) out.add(GestureEvent.Fling(v))
            }
            State.DRAGGING -> {
                state = State.IDLE
                out.add(GestureEvent.DragEnd(s.x, s.y))
            }
            State.MULTI_TRAIL -> {
                // 捏合后余指抬起 → 手势整体结束
                state = State.IDLE
            }
            State.MULTI -> {
                state = State.IDLE
            }
            else -> state = State.IDLE
        }
    }

    private fun onPointerDown(s: TouchSample, out: MutableList<GestureEvent>) {
        if (s.pointerCount < 2) return
        when (state) {
            State.DOWN, State.SCROLLING, State.DRAGGING -> {
                // 保留主指位置（当前 lastX/lastY），记录第二指
                multiFirstX = lastX; multiFirstY = lastY
                multiSecondX = s.x; multiSecondY = s.y
                multiSecondDownT = s.tMs
                multiSecondMoved = false
                multiSecondPointerId = s.pointerId
                pinchRejected = false
                multiBaseDist = hypot(s.x - multiFirstX, s.y - multiFirstY).coerceAtLeast(1f)
                // 起手指距过近 → 冻结捏合（距离比率在极小基线下噪声被放大；
                // Termux 同款“观察态”思想的入口门控）。
                if (multiBaseDist < minPinchStartDistPx) pinchRejected = true
                multiBaseAngle = atan2(s.y - multiFirstY, s.x - multiFirstX)
                state = State.MULTI
            }
            else -> Unit
        }
    }

    private fun onPointerUp(s: TouchSample, out: MutableList<GestureEvent>) {
        if (state != State.MULTI) {
            if (state == State.MULTI_TRAIL) state = State.IDLE
            return
        }
        val quick = s.tMs - multiSecondDownT <= tapTimeoutMs
        val still = hypot(s.x - multiSecondX, s.y - multiSecondY) <= tapSlopPx && !multiSecondMoved
        val secondLifted = s.pointerId == multiSecondPointerId
        if (quick && still && secondLifted) {
            out.add(GestureEvent.TapSecondFinger(s.x, s.y))
        }
        state = State.MULTI_TRAIL
    }

    private fun updateMulti(x: Float, y: Float, out: MutableList<GestureEvent>) {
        multiSecondMoved = multiSecondMoved || hypot(x - multiSecondX, y - multiSecondY) > tapSlopPx
        multiSecondX = x; multiSecondY = y
        val dist = hypot(x - multiFirstX, y - multiFirstY)
        if (dist <= 1f) return
        val angle = atan2(y - multiFirstY, x - multiFirstX)
        val angleDelta = abs(angle - multiBaseAngle)
        if (angleDelta > rotationRejectDeg * (Math.PI.toFloat() / 180f)) {
            pinchRejected = true // 旋转手势 —— 冻结捏合
        }
        if (!pinchRejected) {
            val scale = dist / multiBaseDist
            if (abs(scale - 1f) > 0.005f) {
                out.add(
                    GestureEvent.Pinch(
                        scale = scale,
                        focusX = (multiFirstX + x) / 2f,
                        focusY = (multiFirstY + y) / 2f
                    )
                )
                multiBaseDist = dist // 增量派发
            }
        }
        multiBaseAngle = angle
    }

    // ─── 速度估计（recency 加权）───

    private fun pushVel(t: Long, y: Float) {
        velT[velHead] = t
        velY[velHead] = y
        velHead = (velHead + 1) % VEL_BUFFER
        if (velCount < VEL_BUFFER) velCount++
    }

    /** 最近窗口内相邻样本差分，按样本年龄衰减加权（老样本弱化）。 */
    fun estimateVelocityY(nowMs: Long): Float {
        if (velCount < 2) return 0f
        var wSum = 0f
        var vSum = 0f
        val newest = (velHead + VEL_BUFFER - 1) % VEL_BUFFER
        for (k in 0 until velCount - 1) {
            val i = (newest + VEL_BUFFER - k) % VEL_BUFFER
            val j = (i + VEL_BUFFER - 1) % VEL_BUFFER
            val dt = velT[i] - velT[j]
            if (dt <= 0) continue
            val dy = velY[i] - velY[j]
            val age = nowMs - velT[i]
            if (age > velocityWindowMs) continue
            val v = dy / dt * 1000f // px/s
            val w = 1f / (1f + age / 40f)
            vSum += v * w
            wSum += w
        }
        return if (wSum <= 0f) 0f else vSum / wSum
    }

    private companion object {
        const val VEL_BUFFER = 16
    }
}
