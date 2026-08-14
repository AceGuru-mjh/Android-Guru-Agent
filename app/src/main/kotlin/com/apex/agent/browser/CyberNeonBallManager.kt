package com.apex.agent.browser

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.animation.CycleInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 赛博极客·霓虹环流球（常驻收缩态浮窗枢纽）。
 *
 * 设计语言：高光黑曜石核心 + 动态等离子霓虹光环 + 物理弹力触压感。对标用户方案，
 * 但用代码绘制光环（[NeonRingView]）替代 Lottie 二进制资源，避免引入不可控资产与额外包体。
 * - 球常驻显示，颜色随引擎状态切换（RUNNING 电光蓝 / NEED_HUMAN 琥珀金 / ERROR 赛博红 / SUCCESS 流光绿）。
 * - 订阅 [BrowserEngine] 状态：WAITING_HUMAN 时球切 NEED_HUMAN（脉冲+抖动+震动+badge）。
 * - 点击球 toggle 显式握手：AGENT_DRIVING/HIDDEN → enterHandoffMode（展开接管面板）；
 *   WAITING_HUMAN → completeHandoff（交还 Agent）。与 BrowserOverlay 接管面板状态一致。
 * - 按下时 Spring 物理挤压（果冻感），松开弹回。
 *
 * 依赖 EasyFloat 全局 WindowManager 管理（低侵入），无 SYSTEM_ALERT_WINDOW 权限时 show 静默失败。
 */
@Singleton
class CyberNeonBallManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val engine: BrowserEngine,
) : BrowserEngine.BrowserUiCallback {

    enum class CyberState { RUNNING, NEED_HUMAN, ERROR, SUCCESS }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = "cyber_neon_ball"

    @Volatile private var ballView: View? = null
    @Volatile private var currentState = CyberState.RUNNING

    init {
        engine.addUiCallback(this)
    }

    fun show() = mainHandler.post { doShow() }
    fun dismiss() = mainHandler.post { EasyFloat.dismiss(tag) }

    @SuppressLint("ClickableViewAccessibility")
    private fun doShow() {
        if (ballView != null) return
        EasyFloat.with(appContext)
            .setTag(tag)
            .setLayout(R.layout.view_cyber_neon_ball) { view ->
                ballView = view
                setupSqueeze(view)
                view.setOnClickListener { onBallClick() }
                applyState(currentState) // 初始化颜色
            }
            .setShowPattern(ShowPattern.ALL_TIME)
            .setSidePattern(SidePattern.RESULT_HORIZONTAL)
            .setDragEnable(true)
            .show()
    }

    /** 点击球 toggle 显式握手 */
    private fun onBallClick() {
        CoroutineScope(Dispatchers.Main).launch {
            when (engine.currentState) {
                BrowserEngine.BrowserSessionState.WAITING_HUMAN -> engine.completeHandoff()
                else -> engine.enterHandoffMode()
            }
        }
    }

    // ───────── BrowserUiCallback：引擎状态 → 球状态 ─────────
    override fun onStateChanged(
        state: BrowserEngine.BrowserSessionState,
        url: String?,
        title: String?,
    ) {
        val next = when (state) {
            BrowserEngine.BrowserSessionState.WAITING_HUMAN -> CyberState.NEED_HUMAN
            BrowserEngine.BrowserSessionState.RECOVERING -> CyberState.ERROR
            BrowserEngine.BrowserSessionState.AGENT_DRIVING -> CyberState.RUNNING
            BrowserEngine.BrowserSessionState.HIDDEN -> CyberState.RUNNING
        }
        mainHandler.post { applyState(next) }
    }

    private fun applyState(next: CyberState) {
        if (currentState == next && ballView != null) return
        currentState = next
        val view = ballView ?: return
        val neonRing = view.findViewById<NeonRingView>(R.id.neonRing)
        val imgCoreIcon = view.findViewById<ImageView>(R.id.imgCoreIcon)
        val pulseRing = view.findViewById<View>(R.id.pulseRing)
        val badgeAlert = view.findViewById<TextView>(R.id.badgeAlert)

        val color: Int
        val showBadge: Boolean
        val showPulse: Boolean
        when (next) {
            CyberState.RUNNING -> {
                color = 0xFF00F0FF.toInt(); showBadge = false; showPulse = false
            }
            CyberState.NEED_HUMAN -> {
                color = 0xFFFFB800.toInt(); showBadge = true; showPulse = true
            }
            CyberState.ERROR -> {
                color = 0xFFFF2A55.toInt(); showBadge = false; showPulse = false
            }
            CyberState.SUCCESS -> {
                color = 0xFF00FF87.toInt(); showBadge = false; showPulse = false
            }
        }
        imgCoreIcon.setColorFilter(color)
        badgeAlert.visibility = if (showBadge) View.VISIBLE else View.GONE

        // 霓虹环始终由代码绘制（NeonRingView），不依赖 Lottie 二进制资源，零额外包体
        neonRing.visibility = View.VISIBLE
        neonRing.setStateColor(color)

        if (showPulse) {
            pulseRing.visibility = View.VISIBLE
            startPulse(pulseRing)
            startShake(view)
            triggerVibration()
        } else {
            pulseRing.visibility = View.INVISIBLE
            pulseRing.clearAnimation()
        }
    }

    // ───────── 物理挤压（果冻感） ─────────
    @SuppressLint("ClickableViewAccessibility")
    private fun setupSqueeze(view: View) {
        val sx = SpringAnimation(view, DynamicAnimation.SCALE_X).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                stiffness = SpringForce.STIFFNESS_LOW
            }
        }
        val sy = SpringAnimation(view, DynamicAnimation.SCALE_Y).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                stiffness = SpringForce.STIFFNESS_LOW
            }
        }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx.cancel(); sy.cancel()
                    view.scaleX = 0.88f; view.scaleY = 0.88f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sx.animateToFinalPosition(1f)
                    sy.animateToFinalPosition(1f)
                }
            }
            false // 不消费，保留点击/拖拽
        }
    }

    private fun startShake(view: View) {
        val anim = ObjectAnimator.ofFloat(view, "translationX", 0f, 12f, -12f, 8f, -8f, 0f)
        anim.duration = 400
        anim.interpolator = CycleInterpolator(2f)
        anim.start()
    }

    private fun startPulse(pulseView: View) {
        pulseView.clearAnimation()
        val pX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.4f)
        val pY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.4f)
        val pA = PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 0.0f)
        ObjectAnimator.ofPropertyValuesHolder(pulseView, pX, pY, pA).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun triggerVibration() {
        val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }
}
