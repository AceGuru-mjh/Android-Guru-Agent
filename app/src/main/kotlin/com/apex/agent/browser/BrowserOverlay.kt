package com.apex.agent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.apex.agent.browser.chrome.BrowserChrome
import com.apex.agent.browser.chrome.ChromeConfig
import com.apex.agent.browser.chrome.chromePalette
import com.apex.agent.browser.chrome.bridge.ApexChromeWiring
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 内置浏览器浮窗 —— 完整「给人用的浏览器界面」层（chrome）。
 *
 * 架构（三层解耦）：
 * - BrowserEngine：无头自动化引擎（Agent 工具层直用，零改动）；
 * - ApexChromeWiring：引擎↔chrome 接线器（链式 client 接管弹窗/权限/下载，
 *   快照自动合成，人机双通道裁决路由）；
 * - BrowserChrome：引擎无关的浏览器外观（地址胶囊即进度条 / 标签条 / 页内查找 /
 *   下载面板 / 弹窗权限浮层）——本类只负责把它挂进 overlay 窗口。
 *
 * 与引擎共享同一 WebView 实例（接管期间人类直接真实触摸，交还后 detach 回引擎）。
 * 显式握手：WAITING_HUMAN 时浮窗展开 + chrome 顶部接管横幅（「我已完成操作」），
 * Agent 自动化工具被锁；HIDDEN/AGENT_DRIVING 状态浮窗自动收起（霓虹球仍在，
 * 点球即再次进入接管）。WebView 容器的 topMargin 由 chrome 头部实际高度回调驱动，
 * 页面内容永不遮挡地址栏。
 */
@Singleton
class BrowserOverlay @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val engine: BrowserEngine,
    private val chromeWiring: ApexChromeWiring,
) : BrowserEngine.BrowserUiCallback {

    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // 单例常驻 mainScope：Main.immediate + 异常记录不崩溃（与引擎/球一致的生命周期纪律）
    private val mainScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            android.util.Log.w("BrowserOverlay", "handoff failed", e)
        }
    )

    @Volatile private var rootView: FrameLayout? = null
    @Volatile private var webViewHost: FrameLayout? = null
    @Volatile private var composeView: ComposeView? = null
    // 每次展示周期新建：LifecycleRegistry 一旦 ON_DESTROY 无法重置
    @Volatile private var lifecycleOwner: OverlayLifecycleOwner? = null
    @Volatile private var windowParams: WindowManager.LayoutParams? = null

    @Volatile private var attachedWebView: WebView? = null

    /** chrome 头部（地址栏+横幅+标签条）实测高度，px；驱动 WebView 容器留白。 */
    @Volatile private var chromeHeaderPx: Int = 0

    init {
        engine.addUiCallback(this)
        // 标签切换/新建/崩溃重建 → 换挂 WebView（wiring 已把链式 client 挂好）
        chromeWiring.onActiveWebViewChanged = { view ->
            mainHandler.post { rebindWebView(view) }
        }
    }

    // ───────── BrowserUiCallback ─────────
    override fun onStateChanged(
        state: BrowserEngine.BrowserSessionState,
        url: String?,
        title: String?,
    ) {
        mainHandler.post {
            when (state) {
                BrowserEngine.BrowserSessionState.WAITING_HUMAN -> show()
                else -> hide()
            }
        }
    }

    // ───────── 显式展开/收起（供工具或外部调用） ─────────
    fun show() = mainHandler.post { doShow() }
    fun hide() = mainHandler.post { doHide() }

    @SuppressLint("ClickableViewAccessibility")
    private fun doShow() {
        if (rootView != null) {
            // 已显示：对齐标签结构并刷新 WebView 绑定（如 Agent 换了 activeTab）
            chromeWiring.resync()
            rebindWebView(engine.activeWebView())
            return
        }
        val params = buildLayoutParams()
        val root = FrameLayout(appContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // WebView 承载容器：topMargin 由 chrome 头部高度回调实时驱动（初始估计值，
        // 首帧 onGloballyPositioned 回调后即校正），页面内容永不遮挡地址栏。
        val host = FrameLayout(appContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { topMargin = statusBarHeightPx() + dpPx(64) }
        }

        // Compose 完整浏览器 chrome（自带暗色 MaterialTheme，宿主无需包装）
        val owner = OverlayLifecycleOwner()
        owner.performRestore()
        val compose = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                BrowserChrome(
                    gateway = chromeWiring.gateway,
                    config = ChromeConfig(),
                    modifier = Modifier.statusBarsPadding(),
                    handoffBanner = { HandoffBanner() },
                    onChromeHeightChanged = { px -> applyChromeHeaderHeight(px) },
                    pageSlot = {}, // WebView 由 host(FrameLayout) 承载，页面区不重复渲染
                )
            }
        }

        root.addView(host)
        root.addView(compose)

        rootView = root
        webViewHost = host
        composeView = compose
        lifecycleOwner = owner

        try {
            windowManager.addView(root, params)
            windowParams = params
            owner.onCreate()
            owner.onStart()
            owner.onResume()
            // 展开即对齐：标签结构 + 链式接管 + 快照
            chromeWiring.resync()
            rebindWebView(engine.activeWebView())
        } catch (e: Exception) {
            // 无悬浮窗权限或系统拒绝：静默降级，引擎照常后台工作
            rootView = null
            webViewHost = null
            composeView = null
            lifecycleOwner = null
            windowParams = null
        }
    }

    private fun doHide() {
        val root = rootView ?: return
        // 从浮窗 detach WebView，交还引擎后台驱动
        detachWebView()
        lifecycleOwner?.let { owner ->
            runCatching {
                owner.onPause()
                owner.onStop()
                owner.onDestroy()
            }
        }
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {
            // ignore
        }
        rootView = null
        webViewHost = null
        composeView = null
        lifecycleOwner = null
        windowParams = null
        chromeHeaderPx = 0
    }

    /** 把引擎 active WebView 挂到浮窗容器（接管期间人类真实交互）。 */
    private fun rebindWebView(view: WebView?) {
        val host = webViewHost ?: return
        if (view == null) {
            host.visibility = View.INVISIBLE
            return
        }
        if (view.parent === host) return
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        host.addView(view, lp)
        host.visibility = View.VISIBLE
        attachedWebView = view
    }

    /** 交还时把 WebView 从浮窗移除，回到后台无父状态。 */
    private fun detachWebView() {
        val wv = attachedWebView ?: return
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        attachedWebView = null
    }

    /** chrome 头部高度变化（地址栏/横幅/标签条增减）→ WebView 容器同步留白。 */
    private fun applyChromeHeaderHeight(px: Int) {
        if (px == chromeHeaderPx) return
        chromeHeaderPx = px
        val host = webViewHost ?: return
        val lp = host.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.topMargin = statusBarHeightPx() + px
        host.requestLayout()
    }

    // ───────── 接管横幅：显式握手的人机界面 ─────────

    @Composable
    private fun HandoffBanner() {
        val session by chromeWiring.engineSessionState.collectAsState()
        if (session != BrowserEngine.BrowserSessionState.WAITING_HUMAN) return
        val p = chromePalette()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.ok.copy(alpha = 0.14f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = p.ok,
                modifier = Modifier.height(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("人工接管中 · Agent 工具已锁定", fontSize = 11.sp, color = p.textSecondary)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { mainScope.launch { engine.completeHandoff() } },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 10.dp, vertical = 2.dp,
                ),
            ) { Text("收起", fontSize = 11.sp) }
        }
        Button(
            onClick = { mainScope.launch { engine.completeHandoff() } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.ok),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("我已完成操作，交还 Agent", color = androidx.compose.ui.graphics.Color(0xFF06230F))
        }
    }

    // ───────── 窗口参数 ─────────

    private fun dpPx(dp: Int): Int =
        (dp * appContext.resources.displayMetrics.density).toInt()

    private fun statusBarHeightPx(): Int {
        val id = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) appContext.resources.getDimensionPixelSize(id) else dpPx(24)
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // 浮窗内可交互，浮窗外触摸透传（不抢外部事件）
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 地址栏编辑时软键盘随窗口 resize（overlay 窗口默认不调整，输入框会被键盘挡住）
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }
}
