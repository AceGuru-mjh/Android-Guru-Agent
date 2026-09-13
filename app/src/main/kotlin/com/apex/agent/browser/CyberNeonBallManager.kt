package com.apex.agent.browser

import com.apex.agent.R
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
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 赛博极客·霓虹环流球（按需出现的浮窗枢纽）。
 *
 * 设计语言：高光黑曜石核心 + 动态等离子霓虹光环 + 物理弹力触压感。对标用户方案，
 * 但用代码绘制光环（[NeonRingView]）替代 Lottie 二进制资源，避免引入不可控资产与额外包体。
 *
 * 【按需出现，不再常驻】球的生命周期完全由 [BrowserEngine] 状态驱动：
 * - Agent 首次 navigate / newTab(url)（网页搜索、自动化浏览）→ 状态离开 HIDDEN → 球出现；
 * - HIDDEN（会话结束，见 [BrowserEngine.releaseBrowser]）→ 球收起；
 * - App 启动不再无条件 show —— 服务里不拉起，靠状态回调自然驱动。
 * - 球颜色随引擎状态切换（RUNNING 电光蓝 / NEED_HUMAN 琥珀金 / ERROR 赛博红）。
 * - 订阅 [BrowserEngine] 状态：WAITING_HUMAN 时球切 NEED_HUMAN（脉冲+抖动+震动+badge）。
 * - 点击球 toggle 显式握手：AGENT_DRIVING → enterHandoffMode（展开接管面板）；
 *   WAITING_HUMAN → completeHandoff（交还 Agent）。与 BrowserOverlay 接管面板状态一致。
 * - 长按球 = 结束本次浏览器会话（releaseBrowser → HIDDEN），球随之收起。
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

    // P1 fix（生命周期竞态，两轮混沌审查同题合并）：旧实现每次点击都 new 一个裸
    // CoroutineScope(Dispatchers.Main) —— 无 SupervisorJob / CoroutineExceptionHandler，
    // completeHandoff/enterHandoffMode 内任何未捕获异常直接杀进程，且孤儿协程
    // 排队抢 stateMutex 永不取消。单例持有唯一作用域（进程级生命周期与 @Singleton
    // 一致）+ Main.immediate（点击响应免额外调度延迟）+ 异常记录不崩溃。
    private val mainScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            android.util.Log.w(tag, "handoff action failed", e)
        }
    )

    // P1 fix（内存泄漏）：无限循环 ObjectAnimator 必须持有引用才能 cancel；
    // 旧实现 clearAnimation() 只能取消 View tween，取消不了属性动画，
    // 每次 NEED_HUMAN → RUNNING 往返都泄漏一个持有整棵浮窗视图树的无限动画。
    private var pulseAnimator: ObjectAnimator? = null

    @Volatile private var ballView: View? = null
    @Volatile private var currentState = CyberState.RUNNING

    init {
        engine.addUiCallback(this)
    }

    fun show() = mainHandler.post { doShow() }

    /**
     * v2 修复：旧实现 dismiss 后不清 ballView 引用，而 doShow 以 `ballView != null`
     * 判断"已显示"直接 return——首次 dismiss 后球**永远无法再唤起**，且 @Singleton
     * 长期持有已 detach 的 View 树（内存泄漏）。现在 dismiss 时同步置空引用。
     */
    fun dismiss() = mainHandler.post {
        ballView = null
        runCatching { EasyFloat.dismiss(tag) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun doShow() {
        if (ballView != null) return
        EasyFloat.with(appContext)
            .setTag(tag)
            .setLayout(R.layout.view_cyber_neon_ball) { view ->
                ballView = view
                setupSqueeze(view)
                view.setOnClickListener { onBallClick() }
                view.setOnLongClickListener { onBallLongPress(); true }
                applyState(currentState) // 初始化颜色
            }
            .setShowPattern(ShowPattern.ALL_TIME)
            .setSidePattern(SidePattern.RESULT_HORIZONTAL)
            .setDragEnable(true)
            .show()
    }

    /** 点击球 toggle 显式握手 */
    private fun onBallClick() {
        mainScope.launch {
            when (engine.currentState) {
                BrowserEngine.BrowserSessionState.WAITING_HUMAN -> engine.completeHandoff()
                else -> engine.enterHandoffMode()
            }
        }
    }

    /** 长按球 = 结束浏览器会话：状态回落 HIDDEN，球随之收起（见 [onStateChanged]）。 */
    private fun onBallLongPress() {
        mainScope.launch {
            engine.releaseBrowser()
            android.widget.Toast.makeText(
                appContext, "已结束浏览器会话，悬浮球已收起", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ───────── BrowserUiCallback：引擎状态 → 球的按需出现/收起 ─────────
    override fun onStateChanged(
        state: BrowserEngine.BrowserSessionState,
        url: String?,
        title: String?,
    ) {
        // 状态驱动：浏览器被使用（离开 HIDDEN）→ 出现；会话结束（HIDDEN）→ 收起。
        // 旧实现由 ApexCoreService 启动时无条件 show，球从 App 启动起常驻，
        // 用户只能拖着它到处放 —— 与"被调用时才出现"的预期相悖。
        if (state == BrowserEngine.BrowserSessionState.HIDDEN) {
            dismiss()
            return
        }
        show()
        val next = when (state) {
            BrowserEngine.BrowserSessionState.WAITING_HUMAN -> CyberState.NEED_HUMAN
            BrowserEngine.BrowserSessionState.RECOVERING -> CyberState.ERROR
            BrowserEngine.BrowserSessionState.AGENT_DRIVING -> CyberState.RUNNING
            BrowserEngine.BrowserSessionState.HIDDEN -> CyberState.RUNNING // 不可达：上面已 return
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
            // P1 fix：先 cancel 属性动画再隐藏（clearAnimation 对 ObjectAnimator 无效）
            pulseAnimator?.cancel()
            pulseAnimator = null
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
        // P1 fix：先取消旧动画再启动，避免多次 NEED_HUMAN 往返叠加多个无限动画实例
        pulseAnimator?.cancel()
        val pX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.4f)
        val pY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.4f)
        val pA = PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 0.0f)
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(pulseView, pX, pY, pA).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun triggerVibration() {
        // P0 fix 防御层（两轮混沌审查同题合并）：VIBRATE 属普通权限，正常打包清单已声明，
        // 但清单合并被裁剪 / OEM 定制 ROM 等边缘场景下 vibrate() 会抛 SecurityException
        // （主线程 → 杀进程）。双层防御：① 运行时无权限直接跳过；② runCatching 兜底记录。
        if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.VIBRATE)
            != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(150)
            }
        }.onFailure { e ->
            android.util.Log.w(tag, "vibrate failed: ${e.message}")
        }
    }
}
