package com.apex.agent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 内置浏览器浮窗（对标 Operit 的浮窗 + 显式握手控制条）。
 *
 * 设计要点（与 BrowserEngine 共享同一 WebView 实例，而非镜像截图）：
 * - 浮窗与引擎复用 activeTab 的 WebView：接管期间把该 WebView 从后台（无父）挂到浮窗容器，
 *   人类直接在网页上真实触摸；交还后 detach 回引擎继续后台驱动。避免"截图镜像"导致的
 *   交互失真与状态不一致（这正是 Operit 选择共享 WebView 的原因）。
 * - 显式握手：状态机 [BrowserSessionState.WAITING_HUMAN] 时浮窗展开且 WebView 可交互，
 *   Agent 自动化工具被锁；人类点「我已完成操作」→ [BrowserEngine.completeHandoff] → 浮窗收起。
 * - 通过 [BrowserEngine.addUiCallback] 订阅状态：WAITING_HUMAN 自动展开，其余状态自动收起，
 *   工具层（browser_show/browser_hide）无需感知 UI 细节，保持逻辑/UI 解耦。
 *
 * 仅在用户授予 SYSTEM_ALERT_WINDOW（已声明权限）时可用；addView 失败则静默降级为「无浮窗」，
 * 引擎功能不受影响。
 */
@Singleton
class BrowserOverlay @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val engine: BrowserEngine,
) : BrowserEngine.BrowserUiCallback {

    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var rootView: FrameLayout? = null
    @Volatile private var webViewHost: FrameLayout? = null
    @Volatile private var composeView: ComposeView? = null
    // 每次展示周期新建：LifecycleRegistry 一旦 ON_DESTROY 无法重置，
    // 复用同一实例会在二次显示时抛 IllegalStateException
    @Volatile private var lifecycleOwner: OverlayLifecycleOwner? = null

    // 控制条/浮窗状态（Compose 观察）
    private var uiState by mutableStateOf(OverlayUiState())
    private var attachedWebView: WebView? = null

    /** 是否已尝试注册回调（避免重复注册） */
    @Volatile private var registered = false

    init {
        engine.addUiCallback(this)
        registered = true
    }

    // ───────── BrowserUiCallback ─────────
    override fun onStateChanged(
        state: BrowserEngine.BrowserSessionState,
        url: String?,
        title: String?,
    ) {
        // 仅主线程触碰 WindowManager
        mainHandler.post {
            uiState = uiState.copy(state = state, url = url ?: "", title = title ?: "")
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
            // 已显示：仅刷新 WebView 绑定（如切换了 activeTab）
            rebindWebView()
            return
        }
        val params = buildLayoutParams()
        val root = FrameLayout(appContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // WebView 承载容器
        val host = FrameLayout(appContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // Compose 控制条
        // v2 修复：必须把 LifecycleOwner + SavedStateRegistryOwner 挂到 View 树上，
        // 否则 ComposeView.onAttachedToWindow 找不到 owner 直接抛
        // IllegalStateException，被下方 catch 吞掉 → 浮窗静默永远无法显示
        //（人工接管 UI 即 WAITING_HUMAN 面板完全不可用）。
        val owner = OverlayLifecycleOwner()
        owner.performRestore()
        val compose = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                OverlayContent(
                    state = uiState,
                    onCompleteHandoff = { mainHandler.post { CoroutineScope(Dispatchers.Main).launch { engine.completeHandoff() } } },
                    onCollapseToggle = { mainHandler.post { toggleCollapse() } },
                    onClose = { mainHandler.post { doHide() } },
                )
            }
        }

        root.addView(host)
        root.addView(compose)

        // 拖拽：在根布局拦截控制条区域外的拖动——改为在 Compose 控制条内处理拖动，
        // 这里只把根挂上去。拖拽通过 Compose 的 pointerInput 更新 window params。
        rootView = root
        webViewHost = host
        composeView = compose
        lifecycleOwner = owner

        try {
            windowManager.addView(root, params)
            owner.onCreate()
            owner.onStart()
            owner.onResume()
            rebindWebView()
        } catch (e: Exception) {
            // 无悬浮窗权限或系统拒绝：静默降级，引擎照常后台工作
            rootView = null
            webViewHost = null
            composeView = null
            lifecycleOwner = null
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
    }

    /** 把引擎 active WebView 挂到浮窗容器（接管期间人类真实交互） */
    private fun rebindWebView() {
        val host = webViewHost ?: return
        val wv = engine.activeWebView() ?: return
        if (wv.parent === host) return
        // 先从其旧父（不应有）detach，再挂到浮窗
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        host.addView(wv, lp)
        attachedWebView = wv
        // 折叠态：隐藏网页只留控制条
        host.visibility = if (uiState.collapsed) View.GONE else View.VISIBLE
    }

    /** 交还时把 WebView 从浮窗移除，回到后台无父状态 */
    private fun detachWebView() {
        val wv = attachedWebView ?: return
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        attachedWebView = null
    }

    private fun toggleCollapse() {
        uiState = uiState.copy(collapsed = !uiState.collapsed)
        webViewHost?.visibility = if (uiState.collapsed) View.GONE else View.VISIBLE
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
        }
    }

    /** 浮窗 Compose 可视状态 */
    private data class OverlayUiState(
        val state: BrowserEngine.BrowserSessionState = BrowserEngine.BrowserSessionState.HIDDEN,
        val url: String = "",
        val title: String = "",
        val collapsed: Boolean = false,
    )

    @Composable
    private fun OverlayContent(
        state: OverlayUiState,
        onCompleteHandoff: () -> Unit,
        onCollapseToggle: () -> Unit,
        onClose: () -> Unit,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 顶部控制条（覆盖在 WebView 之上）
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(8.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xE61B1B1F),
                tonalElevation = 4.dp,
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // 状态徽章
                        val (label, color) = when (state.state) {
                            BrowserEngine.BrowserSessionState.WAITING_HUMAN ->
                                "人工接管中" to Color(0xFF4CAF50)
                            BrowserEngine.BrowserSessionState.AGENT_DRIVING ->
                                "Agent 驾驶中" to Color(0xFF2196F3)
                            BrowserEngine.BrowserSessionState.RECOVERING ->
                                "恢复中…" to Color(0xFFFF9800)
                            else -> "浏览器" to Color.Gray
                        }
                        Surface(
                            color = color,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                label,
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                state.title.ifBlank { "内置浏览器" },
                                color = Color.White,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.url.isNotBlank()) {
                                Text(
                                    state.url,
                                    color = Color(0xFFBBBBBB),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // 折叠
                        TextButton(onClick = onCollapseToggle) {
                            Text(if (state.collapsed) "展开" else "折叠", color = Color.White, fontSize = 12.sp)
                        }
                        // 关闭
                        TextButton(onClick = onClose) {
                            Text("收起", color = Color(0xFFFF8A80), fontSize = 12.sp)
                        }
                    }
                    // 仅在人工接管时显示「我已完成操作」
                    if (state.state == BrowserEngine.BrowserSessionState.WAITING_HUMAN) {
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = onCompleteHandoff,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        ) {
                            Text("我已完成操作，交还 Agent", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
