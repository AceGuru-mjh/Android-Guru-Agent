package com.apex.agent.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 霓虹等离子光环（代码绘制，替代 Lottie 二进制资源）。
 *
 * 多层旋转渐变弧 + 呼吸透明度，营造"环流"流光观感；颜色随 [NeonRingView.setStateColor]
 * 切换（RUNNING 电光蓝 / NEED_HUMAN 琥珀金 / ERROR 赛博红 / SUCCESS 流光绿）。
 * 不用 Lottie 是为了避免引入不可控的二进制 JSON 资源，同时零额外包体。
 */
class NeonRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val rings = listOf(
        RingSpec(width = 4f, radiusFactor = 0.92f, speed = 1.4f, offset = 0f, alpha = 0.9f),
        RingSpec(width = 3f, radiusFactor = 0.80f, speed = -1.0f, offset = 120f, alpha = 0.7f),
        RingSpec(width = 2f, radiusFactor = 0.68f, speed = 0.7f, offset = 240f, alpha = 0.5f),
    )

    private var baseColor = 0xFF00F0FF.toInt()
    private var sweep = 90f
    private var breath = 0f
    private var angle = 0f
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    data class RingSpec(
        val width: Float,
        val radiusFactor: Float,
        val speed: Float,
        val offset: Float,
        val alpha: Float,
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun start() {
        if (running) return
        running = true
        loop()
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(animRunnable)
    }

    fun setStateColor(color: Int) {
        baseColor = color
    }

    private val animRunnable = Runnable { loop() }

    private fun loop() {
        if (!running) return
        angle = (angle + 3.2f) % 360f
        breath = (Math.sin(System.currentTimeMillis() / 420.0) * 0.18f + 0.82f).toFloat()
        invalidate()
        mainHandler.postDelayed(animRunnable, 16)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val baseR = min(w, h) / 2f * 0.96f

        for (ring in rings) {
            val r = baseR * ring.radiusFactor
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            val start = angle * ring.speed + ring.offset
            val grad = SweepGradient(
                cx, cy,
                intArrayOf(
                    adjustAlpha(baseColor, 0f),
                    adjustAlpha(baseColor, ring.alpha),
                    adjustAlpha(baseColor, 0f),
                    adjustAlpha(baseColor, ring.alpha * 0.6f),
                ),
                floatArrayOf(0f, 0.30f, 0.55f, 1f),
            )
            paint.shader = grad
            paint.strokeWidth = ring.width * breath
            paint.alpha = (255 * breath).toInt().coerceIn(40, 255)
            canvas.drawArc(rect, start, sweep, false, paint)
        }
        paint.shader = null
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (android.graphics.Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(
            a,
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color),
        )
    }
}
