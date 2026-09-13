package com.apex.agent.browser.chrome.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.apex.agent.browser.chrome.BrowserEngineGateway
import com.apex.agent.browser.chrome.FindState
import com.apex.agent.browser.chrome.HistoryEntry
import com.apex.agent.browser.chrome.JsDialogChoice
import com.apex.agent.browser.chrome.JsDialogRequest
import com.apex.agent.browser.chrome.LivePageState
import com.apex.agent.browser.chrome.SnapshotComposer
import com.apex.agent.browser.chrome.TabIdentity
import com.apex.agent.browser.chrome.TabSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/*
 * NeonChromeWiring —— 引擎侧即插即用接线器（本交付的核心增量）。
 *
 * 之前的接入清单：实现 EngineCommands、手动挂两个 client、自建快照 Flow、
 * 手动 publishSnapshot、下载监听…… 本类把这五件事全部收进「一次构造 + 一行 bind」：
 *
 *   val wiring = NeonChromeWiring(context, tabOps = EngineTabOps(engine))
 *   wiring.bindActiveWebView(engine.activeWebView())   // 弹窗/权限/下载/进度/标题/查找全自动
 *   BrowserChrome(gateway = wiring.gateway, pageSlot = { ... })
 *
 * 频率分层（快照自动合成的关键）：
 * - 低频：引擎只在标签结构变化（增删/切换）后调 resync() 或 publishTabList(...)；
 * - 高频：进度/标题/URL/加载中/前进后退 —— 由链式客户端回调驱动，引擎零代码。
 *
 * 引擎不实现 TabOperations 也能用：自动退化为单标签模式（清单空 → SnapshotComposer
 * 合成 solo 卡）。多标签时实现 6 个一行方法即可。
 */

/** 引擎对标签操作的最小契约——每个方法在你们 BrowserEngine 上应是一行直通。 */
interface TabOperations {
    /** 新建标签，返回标签 id；url 为 null 打开默认页。 */
    fun newTab(url: String?): String
    fun switchTab(tabId: String)
    fun closeTab(tabId: String)
    fun closeAllTabs()
    /** 引擎当前标签结构（低频快照源）。 */
    fun currentTabs(): TabSet
    /** 当前活动标签对应的 WebView（引擎每标签独立 WebView 时随切换而变）。 */
    fun activeWebView(): WebView?
}

/** 桌面/移动 UA 策略。mobile 传 null 表示用 WebView 缺省 UA（首次 bind 时自动捕获）。 */
class NeonUserAgent(
    val mobile: String? = null,
    val desktop: String = DEFAULT_DESKTOP_UA,
) {
    companion object {
        const val DEFAULT_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

class NeonChromeWiring(
    appContext: Context,
    private val tabOps: TabOperations? = null,
    searchTemplate: String = "https://www.bing.com/search?q=%s",
    /** 供下载轮询等使用的协程域；不传则自建，release() 时释放。 */
    scope: CoroutineScope? = null,
    private val userAgent: NeonUserAgent = NeonUserAgent(),
    /** 历史联想数据源（可选，接你们的浏览历史存储）。 */
    private val historySource: (suspend (query: String, limit: Int) -> List<HistoryEntry>)? = null,
    /** P1：权限「总是允许」持久化；传 SharedPreferencesGrantStore(context) 即跨会话生效。 */
    grantStore: PermissionGrantStore? = null,
) : PageStateSink {

    private val ownsScope = scope == null
    private val engineScope: CoroutineScope =
        scope ?: CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    val router = DialogRequestRouter(grantStore)
    val downloads = ChromeDownloadManager(appContext, engineScope)

    val adapter = BrowserEngineGatewayAdapter(
        commands = DirectCommands(),
        appContext = appContext,
        router = router,
        downloadBridge = downloads,
        searchTemplate = searchTemplate,
        snapshotSource = null,
        historySource = historySource,
    )

    /** Chrome 层唯一入口。 */
    val gateway: BrowserEngineGateway get() = adapter

    /** 引擎侧 Agent 裁决钩子（JS 弹窗「交给 Agent 决定」）。P1 起可直接赋默认策略：
     *  `wiring.agentDecider = AgentDialogPolicy(scope)`。 */
    var agentDecider: ((JsDialogRequest, suspend (JsDialogChoice, String?) -> Unit) -> Boolean)?
        get() = router.agentDecider
        set(value) { router.agentDecider = value }

    /** P1：撤销某 origin 的全部「总是允许」记忆（站点权限管理界面用）。 */
    fun revokeRememberedGrants(originHost: String) = router.revokeRememberedGrants(originHost)

    /* ---------------- 内部状态（主线程受限） ---------------- */

    private val mainHandler = Handler(Looper.getMainLooper())

    private var tabSet = TabSet()
    private var live = LivePageState()
    private var findState: FindState? = null
    private var desktopMode = false

    private var boundView: WebView? = null
    private var originalUa: String? = null
    private var lastFindQuery: String? = null

    /* ---------------- 公开 API ---------------- */

    /**
     * 活动 WebView 被重新绑定（首次 bind / 标签切换 / resync）：
     * 浮窗宿主（BrowserOverlay）用它把新 WebView 挂进自己的容器。
     */
    var onWebViewRebound: ((WebView) -> Unit)? = null

    /**
     * 绑定当前活动标签的 WebView：自动链式保留引擎已挂的 client，并接管
     * webChromeClient / downloadListener / findListener 三个挂点。
     * 多标签换 view 时对新的 view 再次调用即可；同 view 重复调用幂等。
     */
    fun bindActiveWebView(
        view: WebView,
        existingWebViewClient: WebViewClient? = null,
        existingWebChromeClient: WebChromeClient? = null,
    ) {
        onMain {
            val prevClient = existingWebViewClient ?: view.attachedClientOrNull()
            val prevChrome = existingWebChromeClient ?: view.attachedChromeClientOrNull()
            view.webViewClient = (prevClient as? ChainWebViewClient) ?: ChainWebViewClient(this, prevClient)
            view.webChromeClient = (prevChrome as? ChainWebChromeClient) ?: ChainWebChromeClient(router, this, prevChrome)
            view.setDownloadListener(downloads)
            view.setFindListener(ChainFindListener(view, this))
            val viewChanged = boundView !== view
            boundView = view
            bootstrapLive(view)
            publish()
            if (viewChanged) onWebViewRebound?.invoke(view)
        }
    }

    /** 引擎主动发布标签结构（Agent 开新标签等引擎自发变化时调用；命令路径会自动 resync）。 */
    fun publishTabList(tabs: List<TabIdentity>, activeTabId: String?) {
        onMain {
            tabSet = TabSet(tabs, activeTabId)
            publish()
        }
    }

    /** 拉取引擎当前标签结构并重新绑定活动 WebView（任何 tab 命令后自动调用）。 */
    fun resync() {
        onMain {
            val ops = tabOps
            if (ops != null) {
                tabSet = ops.currentTabs()
                ops.activeWebView()?.let { bindActiveWebView(it) }
            }
            publish()
        }
    }

    fun release() {
        adapter.release()
        if (ownsScope) engineScope.cancel()
    }

    /* ---------------- PageStateSink：高频采集（主线程） ---------------- */

    override fun onPageStarted(view: WebView, url: String?) {
        if (view !== boundView) return
        live = live.copy(
            url = url ?: live.url,
            isLoading = true,
            progress = 0.05f,
            canGoBack = view.canGoBack(),
            canGoForward = view.canGoForward(),
        )
        lastFindQuery = null
        findState = null
        publish()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (view !== boundView) return
        live = live.copy(
            url = url ?: live.url,
            isLoading = false,
            progress = 1f,
            title = view.title?.takeIf { it.isNotBlank() } ?: live.title,
            canGoBack = view.canGoBack(),
            canGoForward = view.canGoForward(),
        )
        publish()
    }

    override fun onUrlChanged(view: WebView, url: String?) {
        if (view !== boundView) return
        live = live.copy(
            url = url ?: live.url,
            canGoBack = view.canGoBack(),
            canGoForward = view.canGoForward(),
        )
        publish()
    }

    override fun onProgress(view: WebView, newProgress: Int) {
        if (view !== boundView) return
        live = live.copy(
            progress = newProgress.coerceIn(0, 100) / 100f,
            canGoBack = view.canGoBack(),
            canGoForward = view.canGoForward(),
        )
        publish()
    }

    override fun onTitle(view: WebView, title: String?) {
        if (view !== boundView) return
        if (!title.isNullOrBlank()) live = live.copy(title = title)
        publish()
    }

    override fun onFindResult(view: WebView, activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean) {
        if (view !== boundView) return
        findState = FindState(
            query = lastFindQuery.orEmpty(),
            activeMatch = activeMatchOrdinal,
            totalMatches = numberOfMatches,
        )
        publish()
    }

    /* ---------------- 命令实现：WebView 直达 + TabOperations ---------------- */

    private inner class DirectCommands : EngineCommands {
        override fun goBack() { view()?.goBack() }
        override fun goForward() { view()?.goForward() }
        override fun reload() { view()?.reload() }
        override fun stopLoading() { view()?.stopLoading() }
        override fun loadUrl(url: String) { view()?.loadUrl(url) }

        override fun newTab(url: String?): String {
            val ops = tabOps
            if (ops == null) {
                view()?.loadUrl(url ?: "about:blank")
                publish()
                return SnapshotComposer.SOLO_TAB_ID
            }
            val id = ops.newTab(url)
            resync()
            return id
        }

        override fun switchTab(tabId: String) {
            if (tabOps == null) return
            tabOps.switchTab(tabId)
            resync()
        }

        override fun closeTab(tabId: String) {
            if (tabOps == null) {
                view()?.loadUrl("about:blank")
                publish()
                return
            }
            tabOps.closeTab(tabId)
            resync()
        }

        override fun closeAllTabs() {
            if (tabOps == null) {
                view()?.loadUrl("about:blank")
                publish()
                return
            }
            tabOps.closeAllTabs()
            resync()
        }

        override fun findInPage(query: String, forward: Boolean) = findInPageInternal(query, forward)

        override fun clearFindMatches() {
            view()?.clearMatches()
            lastFindQuery = null
            findState = null
            publish()
        }

        override fun setDesktopMode(enabled: Boolean) = setDesktopModeInternal(enabled)
    }

    /* ---------------- 私有实现 ---------------- */

    private fun view(): WebView? = boundView

    private fun publish() {
        adapter.publishSnapshot(SnapshotComposer.compose(tabSet, live, findState, desktopMode))
    }

    private fun findInPageInternal(query: String, forward: Boolean) {
        val v = view() ?: return
        if (query != lastFindQuery) {
            lastFindQuery = query
            findState = FindState(query = query, activeMatch = 0, totalMatches = 0)
            v.findAllAsync(query) // 计数经 ChainFindListener 回填
            publish()
        } else {
            // 匹配间跳转：WebView 无公开 findNext API，Chromium 支持非标准 window.find。
            val quoted = org.json.JSONObject.quote(query) ?: "\"$query\""
            val backwards = if (forward) "false" else "true"
            v.evaluateJavascript("window.find($quoted, false, $backwards, true)") { found ->
                if (found == "true") advanceFindOrdinal(forward)
            }
        }
    }

    /** window.find 不触发 FindListener，序号按环形推进（近似值，UI 立即反馈）。 */
    private fun advanceFindOrdinal(forward: Boolean) {
        val fs = findState ?: return
        val total = fs.totalMatches
        if (total <= 0) return
        val delta = if (forward) 1 else -1
        val next = (((fs.activeMatch + delta) % total) + total) % total
        findState = fs.copy(activeMatch = next)
        publish()
    }

    private fun setDesktopModeInternal(enabled: Boolean) {
        desktopMode = enabled
        val v = view()
        if (v != null) {
            v.settings.userAgentString = when {
                enabled -> userAgent.desktop
                else -> originalUa ?: userAgent.mobile
            }
            v.reload()
        }
        publish()
    }

    private fun bootstrapLive(view: WebView) {
        live = LivePageState(
            url = view.url?.takeIf { it.isNotBlank() } ?: live.url,
            title = view.title?.takeIf { it.isNotBlank() } ?: live.title,
            isLoading = view.progress < 100,
            progress = view.progress.coerceIn(0, 100) / 100f,
            canGoBack = view.canGoBack(),
            canGoForward = view.canGoForward(),
        )
        if (originalUa == null) originalUa = view.settings.userAgentString
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}

/* ---------------- WebView 挂点读取（API 26+，旧设备静默跳过） ---------------- */

private fun WebView.attachedClientOrNull(): WebViewClient? =
    runCatching { webViewClient }.getOrNull()

private fun WebView.attachedChromeClientOrNull(): WebChromeClient? =
    runCatching { webChromeClient }.getOrNull()
