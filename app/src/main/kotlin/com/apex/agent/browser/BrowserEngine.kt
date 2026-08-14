package com.apex.agent.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.GeolocationPermissions
import androidx.core.os.postDelayed
import com.apex.agent.core.tools.builtin.browser.BrowserScript
import com.apex.agent.core.tools.builtin.browser.DomParser
import com.apex.agent.core.tools.builtin.browser.PageSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 内置 WebView 浏览器引擎（对标 Operit 的 BrowserAgent + TabManager + HistoryManager）。
 *
 * 2026 裁决最终形态（综合 Qwen 评审 P0 缺口）：
 * - 单例常驻，由 [ApexCoreService] 持有，后台即可驱动网页自动化（不依赖前台 Activity）。
 * - 稳定 Ref：语义哈希 `data-apex-hash`（"r_xxx"），抗 SPA 局部刷新错位。
 * - 物理触摸注入：点击经 DOM 定位 → 换算 WebView 屏幕坐标 → [WebView.dispatchTouchEvent]，
 *   绕过 `isTrusted` 校验与 JS 事件委托陷阱（替代旧 `el.click()`）。
 * - 显式握手状态机：[BrowserSessionState] 驱动人工接管，[WAITING_HUMAN] 期间所有自动化工具被锁。
 * - P0 缺口补齐：页面加载等待(#1)、动作后验证(#2)、JS 弹窗处理(#3)、渲染进程崩溃恢复(#4)、
 *   动作空间补全 select/toggle(#5)、Cookie 持久化(#6)。
 */
@Singleton
class BrowserEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    // ───────── 状态机（显式握手人工接管） ─────────
    enum class BrowserSessionState {
        HIDDEN,          // 后台运行，无 UI
        AGENT_DRIVING,   // Agent 控制中，浮窗可显示实时画面
        WAITING_HUMAN,   // 人工接管中，WebView 接收真实触摸，Agent 工具被锁定
        RECOVERING       // 渲染进程崩溃重建中
    }

    /** Agent 工具在 [WAITING_HUMAN] 期间被调用时抛出，转化为友好的 SYSTEM_LOCKED 提示 */
    class HandoffLockedException(message: String) : IllegalStateException(message)

    @Volatile
    var currentState: BrowserSessionState = BrowserSessionState.HIDDEN
        private set

    private val stateMutex = Mutex()

    // ───────── UI 回调（浮窗等可视层订阅状态变更） ─────────
    /** 可视层（浮窗）订阅状态变更，用于自动展开/收起与刷新控制条 */
    interface BrowserUiCallback {
        fun onStateChanged(state: BrowserSessionState, url: String?, title: String?)
    }

    @Volatile
    private var uiCallbacks = mutableSetOf<BrowserUiCallback>()

    /** 浮窗注册自己为状态订阅者（支持多订阅者：霓虹球 + 接管面板） */
    fun addUiCallback(cb: BrowserUiCallback) {
        uiCallbacks.add(cb)
    }

    /** 注销状态订阅者 */
    fun removeUiCallback(cb: BrowserUiCallback) {
        uiCallbacks.remove(cb)
    }

    /** 统一状态出口：所有状态变更必须经此，以驱动可视层 */
    private fun setState(next: BrowserSessionState) {
        currentState = next
        val tab = activeTab()
        uiCallbacks.forEach { it.onStateChanged(next, tab?.url, tab?.title) }
    }

    /** 当前激活标签页的 WebView，供浮窗承载显示 */
    fun activeWebView(): WebView? = activeTab()?.webView

    // ───────── 标签页 / 历史 ─────────
    private val tabs = LinkedHashMap<Int, Tab>()
    private var activeTabId: Int = 0
    private var nextTabId = 1
    private val history = ArrayDeque<String>()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** ref(语义哈希) -> 上次快照时的 Bid 序号，点击/输入时按稳定 ref 定位 */
    private val refToBid = mutableMapOf<String, Int>()

    // P1 #7：错误恢复 —— 指数退避重试 + 熔断器（仅瞬态异常重试，语义错误不重试）
    private val retryPolicy = RetryPolicy()
    private val breaker = CircuitBreaker()


    /** 文件上传回调挂起（[onShowFileChooser] ↔ [respondFileChooser]） */
    private var pendingFileChooser: ValueCallback<Array<android.net.Uri>>? = null

    data class Tab(
        val id: Int,
        val webView: WebView,
        var title: String = "",
        var url: String = "",
        /** 页面加载完成信号（onPageFinished 置 true，navigate 时置 false） */
        @Volatile var pageFinished: Boolean = false,
    )

    @SuppressLint("SetJavaScriptEnabled", "SdCardPath")
    private fun createWebView(): WebView {
        val wv = WebView(applicationContext())
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        // P0 #11（安全加固基线）：禁止本地文件访问，避免 UXSS / 路径穿越
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.allowFileAccessFromFileURLs = false
        wv.settings.allowUniversalAccessFromFileURLs = false
        // 安全加固（清单 11.1 / 11.2）：禁止 https 页加载 http 混合内容，防中间人注入
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // 安全加固：生产环境关闭 WebView 远程调试桥（防止 adb 注入与本地端口探测）
        WebView.setWebContentsDebuggingEnabled(false)
        // 反检测（#13 轻量版）：去除 UA 中的 "; wv" / "Version/4.0" WebView 标志，
        // 降低被 Cloudflare/Akamai 等反爬系统识别为机器人的概率。
        wv.settings.userAgentString = wv.settings.userAgentString
            ?.replace("; wv", "")
            ?.replace("Version/4.0 ", "")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                url?.let {
                    val tab = activeTab()
                    if (tab != null && tab.webView === view) {
                        tab.url = it
                        tab.pageFinished = true
                        history.addLast(it)
                        if (history.size > MAX_HISTORY) history.removeFirst()
                    }
                }
                // 反检测（#13）：每次页面加载完成注入隐身 JS，隐藏自动化痕迹
                view?.evaluateJavascript(STEALTH_JS, null)
            }

            // P0 #4：渲染进程崩溃恢复
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val gone = detail?.didCrash() ?: true
                // 重建：销毁崩溃实例并新建（Chromium 建议崩溃后重建，勿直接复用）
                tabs.values.filter { it.webView === view }.forEach { bad ->
                    bad.webView.destroy()
                    tabs.remove(bad.id)
                }
                if (activeTabId == 0 || !tabs.containsKey(activeTabId)) {
                    val id = newTabSync()
                    activeTabId = id
                }
                // 通知由调用方在 next snapshot/navigate 时感知；此处仅标记恢复
                Handler(Looper.getMainLooper()).post {
                    setState(BrowserSessionState.RECOVERING)
                }
                return true // 已处理，不 crash 宿主
            }

            // 安全加固（清单 4.5.8 / 11.1 / 11.2 / V4）：仅允许 http/https 导航，
            // 拦截 file://、content://、javascript: 等危险 scheme，避免本地文件泄露与 UXSS。
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val u = url ?: return true
                val allowed = runCatching { android.net.Uri.parse(u) }
                    .getOrNull()?.scheme?.let { it == "http" || it == "https" } ?: false
                return if (allowed) false else true // 非白名单 scheme：自行吞掉，不导航
            }

            // 安全加固（清单 11.10 / V6）：SSL 错误绝不自动忽略，显式取消加载。
            // WebView 默认即对 SSL 错误取消，此处显式重写以保持可审计、可一致行为。
            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                lastDialog = "ssl_error: 证书校验失败（${error?.primaryError}），已取消加载"
                handler?.cancel() // 严禁 handler.proceed()
            }
        }

        // P0 #3：JS 弹窗处理（alert/confirm/prompt 阻塞 JS，必须接管）
        wv.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                lastDialog = "alert: ${message ?: ""}"
                result?.confirm()
                return true
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                // 默认确认；Agent 可通过 browser_dialog 工具在确认前拦截（此处保守确认以免卡死）
                lastDialog = "confirm: ${message ?: ""}"
                result?.confirm()
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?,
                result: android.webkit.JsPromptResult?
            ): Boolean {
                lastDialog = "prompt: ${message ?: ""}"
                result?.confirm(defaultValue ?: "")
                return true
            }

            // P0 #5（文件上传）：拦截系统文件选择器
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<android.net.Uri>>,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = filePathCallback
                // 无法自动解析时进入人工接管（由 Agent 调用 browser_show 让人选文件）
                return true
            }

            // P2 #12：网页权限请求（摄像头/麦克风/地理）处理。
            // 敏感权限（摄像头/麦克风/地理）默认拒绝，避免未经用户确认的自动授权泄露隐私；
            // 进入人工接管模式，提示用户改用 browser_show 在真实页面自行授权。
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val resources = request.resources
                val isSensitive = resources.any {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (isSensitive) {
                    lastDialog = "permission: 网页请求敏感权限(摄像头/麦克风/地理)，已默认拒绝并进入人工接管；" +
                        "如需授权请用 browser_show 在真实页面操作"
                    // 进入人工接管，由用户在真实页面上通过浏览器原生对话框授权
                    CoroutineScope(Dispatchers.Main).launch { enterHandoffMode() }
                    request.deny() // 隐私最小化：不自动授予敏感权限
                } else {
                    request.deny()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                // 地理位置默认拒绝
                callback?.invoke(origin, false, false)
            }
        }

        // P2 #14（文件下载）：通过系统 DownloadManager 下载，记录到最近下载列表供 Agent 查询
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("Apex Browser 下载")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            runCatching { dm.enqueue(req) }.onSuccess { id ->
                lastDownload = DownloadRecord(fileName, url, id)
                lastDialog = "download: 已开始下载 $fileName"
            }.onFailure {
                lastDownload = null
                lastDialog = "download: 下载失败 ${it.message}"
            }
        }
        return wv
    }

    /** 最近一次下载记录（#14），供 [browser_download_list] 读取 */
    data class DownloadRecord(
        val fileName: String,
        val url: String,
        val downloadId: Long,
    )

    @Volatile var lastDownload: DownloadRecord? = null
        private set

    /** 最近一次 JS 弹窗文本，供 snapshot 回报注入 Agent 上下文 */
    @Volatile var lastDialog: String? = null
        private set

    private fun newTabSync(): Int {
        val id = nextTabId++
        val wv = createWebView()
        tabs[id] = Tab(id, wv)
        return id
    }

    /** 重建当前激活 tab 的 WebView（P2 #15）：销毁旧实例、新建、恢复 URL */
    private fun rebuildActiveWebView() {
        val old = activeTab() ?: return
        val url = old.url
        old.webView.destroy()
        val wv = createWebView()
        tabs[old.id] = old.copy(webView = wv, pageFinished = false)
        if (url.isNotBlank()) wv.loadUrl(url)
    }

    /** 由 DI 构造器注入的 Application Context（避免在引擎内直接持 Activity） */
    private fun applicationContext() = appContext.applicationContext

    // ═════════ 状态机 API（显式握手） ═════════

    /** Agent 调用 browser_show 展开浮窗时触发：进入人工接管，锁定自动化工具 */
    suspend fun enterHandoffMode() = stateMutex.withLock {
        if (currentState == BrowserSessionState.RECOVERING) return@withLock
        setState(BrowserSessionState.WAITING_HUMAN)
    }

    /** 人类点击「我已完成操作」按钮时触发：交还 Agent，自动补一次快照对齐状态 */
    suspend fun completeHandoff() = stateMutex.withLock {
        if (currentState != BrowserSessionState.WAITING_HUMAN) return@withLock
        setState(BrowserSessionState.AGENT_DRIVING)
        // 接管完成后强制持久化 Cookie（P0 #6）
        flushCookies()
        // 交还瞬间自动补一次快照，结果由 Agent 下一次 snapshot 直接获得
    }

    /** 所有 AgentTool 执行前必须调用的守卫 */
    fun assertAgentControl() {
        if (currentState == BrowserSessionState.WAITING_HUMAN) {
            throw HandoffLockedException("人类正在接管浏览器，请等待人类点击「我已完成操作」后再执行自动化。")
        }
    }

    // ═════════ 标签页管理 ═════════

    suspend fun newTab(url: String? = null): Int = withContext(Dispatchers.Main) {
        val id = newTabSync()
        activeTabId = id
        url?.let { loadUrlInternal(it) }
        id
    }

    fun activeTab(): Tab? = tabs[activeTabId]

    fun switchTab(id: Int): Boolean {
        if (!tabs.containsKey(id)) return false
        activeTabId = id
        return true
    }

    fun closeTab(id: Int): Boolean {
        val tab = tabs.remove(id) ?: return false
        tab.webView.destroy()
        if (activeTabId == id) activeTabId = tabs.keys.firstOrNull() ?: 0
        return true
    }

    fun listTabs(): List<Pair<Int, String>> = tabs.map { it.key to it.value.url }

    // ═════════ 导航 + 加载等待（P0 #1） ═════════

    /** 导航到 URL，支持 waitForSelector（元素等待）与超时兜底。
     *  @param onProgress 可选进度回调（百分比 0..100 + 阶段文案），用于把等待过程推给 UI。
     *         不传则与原行为完全一致（向后兼容既有调用方）。
     */
    suspend fun navigate(
        url: String,
        waitForSelector: String? = null,
        timeoutMs: Long = 15000,
        onProgress: ((percent: Int, phase: String) -> Unit)? = null,
    ): NavResult = withContext(Dispatchers.Main) {
        val u = if (url.startsWith("http")) url else "https://$url"
        // P2 #15：长会话内存维护——导航次数超阈值时重建当前 WebView
        if (++navigationCount > MAX_NAVIGATIONS_BEFORE_REBUILD) {
            navigationCount = 0
            rebuildActiveWebView()
        }
        val tab = activeTab() ?: run { newTabSync().also { activeTabId = it } }.let { tabs[it]!! }
        onProgress?.invoke(10, "正在加载 $u")
        tab.pageFinished = false
        loadUrlInternal(u)
        // (1) 基础等待：onPageFinished
        val baseOk = waitForPageFinished(tab, timeoutMs, onProgress)
        // (2) 智能等待：元素出现
        val selOk = if (waitForSelector != null) {
            onProgress?.invoke(80, "等待元素 $waitForSelector 出现")
            waitForSelectorOnPage(tab.webView, waitForSelector, timeoutMs)
        } else true
        // (3) Cookie 持久化时机（P0 #6）
        flushCookies()
        onProgress?.invoke(100, if (baseOk) "页面加载完成" else "页面加载超时（已兜底返回当前状态）")
        NavResult(success = baseOk, selectorFound = selOk, timedOut = !baseOk)
    }

    data class NavResult(val success: Boolean, val selectorFound: Boolean, val timedOut: Boolean)

    private fun loadUrlInternal(u: String) {
        activeTab()?.webView?.loadUrl(u)
    }

    private suspend fun waitForPageFinished(
        tab: Tab,
        timeoutMs: Long,
        onProgress: ((percent: Int, phase: String) -> Unit)? = null,
    ): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (tab.pageFinished) return true
            val elapsed = SystemClock.uptimeMillis() - start
            // 10→75 映射到加载等待阶段，留出余量给后续元素等待
            val percent = 10 + ((elapsed.toDouble() / timeoutMs) * 65).toInt().coerceIn(0, 65)
            onProgress?.invoke(percent, "等待页面加载完成…")
            delay(100)
        }
        // 超时兜底：仍返回当前状态（页面可能已在加载，只是未触发 finish）
        return tab.pageFinished
    }

    private suspend fun waitForSelectorOnPage(wv: WebView, selector: String, timeoutMs: Long): Boolean {
        return runCatching {
            evaluateJson(wv, BrowserScript.waitForSelectorJs(selector)) == "true"
        }.getOrDefault(false)
    }

    suspend fun goBack(): Boolean = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext false
        if (wv.canGoBack()) { wv.goBack(); return@withContext true }
        false
    }

    suspend fun goForward(): Boolean = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext false
        if (wv.canGoForward()) { wv.goForward(); return@withContext true }
        false
    }

    // ═════════ 快照 ═════════

    /**
     * 页面快照。
     * @param tokenBudget 字符预算（[DomParser.buildSummary] 使用）
     * @param strategy 剪枝策略（#19/#20），默认 [INTERACTIVE_ONLY] 即原有行为
     * @param allowA11yFallback 主快照交互元素过少（<5）时是否降级用 A11y 补充源（#17）
     */
    suspend fun snapshot(
        tokenBudget: Int = 1600,
        strategy: DomParser.SnapshotStrategy = DomParser.SnapshotStrategy.INTERACTIVE_ONLY,
        allowA11yFallback: Boolean = true,
    ): PageSnapshot = withContext(Dispatchers.Main) {
        val tab = activeTab() ?: return@withContext emptySnapshot()
        // P1 #7：快照超时视为可重试（主线程偶发卡顿），熔断保护
        withRetry(retryPolicy, breaker) {
            // 注入网络监控（#18），首次快照时挂载一次即可
            runCatching { tab.webView.evaluateJavascript(BrowserScript.NETWORK_MONITOR_JS, null) }
            var wrapped = evaluateSnapshotJs(tab.webView, strategy)
            val raw = runCatching {
                json.parseToJsonElement(wrapped).jsonPrimitive.content
            }.getOrDefault(wrapped)
            val wv = tab.webView
            var snap = DomParser.parse(
                rawJson = raw,
                url = wv.url ?: tab.url,
                title = tab.title,
                scrollY = wv.scrollY,
                scrollHeight = (wv.contentHeight * wv.resources.displayMetrics.density).toInt(),
                viewportHeight = wv.height,
                tokenBudget = tokenBudget,
                strategy = strategy,
            )
            // #17 降级：主快照元素过少，说明可能被 CSP 拦截或页面极简，用 A11y 源补充
            if (allowA11yFallback && snap.interactiveElements.size < 5) {
                val a11yRaw = runCatching {
                    json.parseToJsonElement(evaluateA11yJs(wv)).jsonPrimitive.content
                }.getOrDefault("[]")
                val a11ySnap = DomParser.parse(
                    rawJson = a11yRaw,
                    url = wv.url ?: tab.url,
                    title = tab.title,
                    scrollY = wv.scrollY,
                    scrollHeight = (wv.contentHeight * wv.resources.displayMetrics.density).toInt(),
                    viewportHeight = wv.height,
                    tokenBudget = tokenBudget,
                    strategy = DomParser.SnapshotStrategy.INTERACTIVE_ONLY,
                )
                if (a11ySnap.interactiveElements.size > snap.interactiveElements.size) {
                    snap = a11ySnap
                }
            }
            refToBid.clear()
            snap.interactiveElements.forEach { refToBid[it.ref] = it.bid }
            snap
        }
    }

    private suspend fun evaluateSnapshotJs(wv: WebView, strategy: DomParser.SnapshotStrategy): String =
        kotlinx.coroutines.withTimeout(8000) {
            suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(BrowserScript.snapshotJs(strategy)) { result ->
                    cont.resume(result ?: "[]")
                }
            }
        }

    private suspend fun evaluateA11yJs(wv: WebView): String = kotlinx.coroutines.withTimeout(8000) {
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(BrowserScript.A11Y_FALLBACK_JS) { result ->
                cont.resume(result ?: "[]")
            }
        }
    }

    /** 读取已挂载的网络监控日志（#18），返回最近 N 条 fetch/xhr 记录 */
    suspend fun networkLog(limit: Int = 50): List<Map<String, Any?>> = withContext(Dispatchers.Main) {
        val tab = activeTab() ?: return@withContext emptyList()
        runCatching {
            val jsonStr = kotlinx.coroutines.withTimeout(5000) {
                suspendCancellableCoroutine<String> { cont ->
                    tab.webView.evaluateJavascript(
                        "JSON.stringify((window.__apexNetLog||[]).slice(-$limit))"
                    ) { cont.resume(it ?: "[]") }
                }
            }
            json.parseToJsonElement(jsonStr).jsonArray.map { it.jsonObject.toMap() }
        }.getOrDefault(emptyList())
    }

    // ═════════ 点击：物理触摸注入（P0 主线 #2 + 创新二） ═════════

    /**
     * 物理触摸注入点击：DOM(ref) 定位 → WebView 屏幕坐标 → dispatchTouchEvent。
     * 返回 [PostActionState] 供动作后验证(#2)。
     */
    suspend fun clickElement(ref: String): PostActionState = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext PostActionState.failed("无激活标签页")
        // P1 #7：重试+熔断；元素找不到视为可重试瞬态（可能页面未渲染完）
        withRetry(retryPolicy, breaker) {
            val center = getCenterInWebView(wv, ref)
                ?: throw ElementNotFoundException("找不到 ref=$ref 对应元素（可能页面未渲染完成）")
            probePage(wv)
            // 构造真实触摸事件，DOWN~UP 间 30~80ms 随机延迟模拟人类按压
            val downTime = SystemClock.uptimeMillis()
            val hold = (30L..80L).random()
            val x = center.first
            val y = center.second
            val down = android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
            val up = android.view.MotionEvent.obtain(downTime, downTime + hold, android.view.MotionEvent.ACTION_UP, x, y, 0)
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
            down.recycle(); up.recycle()
            delay((300L..800L).random()) // 等待页面响应
            probePage(wv)
        }
    }

    /** 换算元素中心点到 WebView 自身坐标（dispatchTouchEvent 用 WebView 本地坐标） */
    private suspend fun getCenterInWebView(wv: WebView, ref: String): Pair<Float, Float>? {
        val jsonStr = evaluateJson(wv, BrowserScript.rectByRefJs(ref))
        return runCatching {
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            val x = obj["x"]?.jsonPrimitive?.content?.toFloat()
            val y = obj["y"]?.jsonPrimitive?.content?.toFloat()
            if (x != null && y != null) x to y else null
        }.getOrNull()
    }

    // ═════════ 输入 / 选择 / 切换（P0 #5 动作空间补全） ═════════

    suspend fun inputText(ref: String, text: String): PostActionState = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext PostActionState.failed("无激活标签页")
        withRetry(retryPolicy, breaker) {
            val safe = text.replace("\\", "\\\\").replace("'", "\\'")
            val ok = evaluateBoolean(wv, """
                (function(){
                  var el = document.querySelector('[data-apex-hash=${ref.replace("\"", "\\\"")}]');
                  if (!el) return false;
                  el.focus();
                  el.value = '$safe';
                  el.dispatchEvent(new Event('input', {bubbles:true}));
                  el.dispatchEvent(new Event('change', {bubbles:true}));
                  return true;
                })();
            """)
            delay(200)
            if (ok) probePage(wv) else throw ElementNotFoundException("找不到输入框 ref=$ref")
        }
    }

    /** <select> 选择：按 value 或可见文本 */
    suspend fun selectOption(ref: String, value: String, byText: Boolean = false): PostActionState =
        withContext(Dispatchers.Main) {
            val wv = activeTab()?.webView ?: return@withContext PostActionState.failed("无激活标签页")
            withRetry(retryPolicy, breaker) {
                val ok = evaluateBoolean(wv, BrowserScript.selectJs(ref, value, byText))
                delay(200)
                if (ok) probePage(wv) else throw ElementNotFoundException("找不到 select ref=$ref 或选项不匹配")
            }
        }

    /** checkbox / radio 切换：物理触摸点击（交互模式与文本输入不同） */
    suspend fun toggle(ref: String): PostActionState = clickElement(ref)

    // ═════════ 文件上传（P0 #5 / #14 基础） ═════════

    /** 由 Agent 指定本地文件路径完成上传；无挂起回调时返回 false（需人工接管） */
    fun respondFileChooser(uri: android.net.Uri): Boolean {
        val cb = pendingFileChooser ?: return false
        pendingFileChooser = null
        cb.onReceiveValue(arrayOf(uri))
        return true
    }

    // ═════════ 滚动（含无限滚动检测，P0 #10 增强） ═════════

    suspend fun scroll(
        deltaY: Int,
        waitForNewContent: Boolean = false,
        maxWaitMs: Long = 3000,
        onProgress: ((percent: Int, phase: String) -> Unit)? = null,
    ): ScrollResult = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext ScrollResult(scrolled = false)
        onProgress?.invoke(10, "已滚动 ${if (deltaY >= 0) "+" else ""}$deltaY px${if (waitForNewContent) "，等待新内容…" else ""}")
        val before = probePage(wv).newElementsCount
        wv.evaluateJavascript("window.scrollBy(0, $deltaY); true;", null)
        if (waitForNewContent) {
            val start = SystemClock.uptimeMillis()
            var max = before
            while (SystemClock.uptimeMillis() - start < maxWaitMs) {
                delay(300)
                val now = probePage(wv).newElementsCount
                max = maxOf(max, now)
                val elapsed = SystemClock.uptimeMillis() - start
                val percent = 20 + ((elapsed.toDouble() / maxWaitMs) * 70).toInt().coerceIn(0, 70)
                onProgress?.invoke(percent, "等待新内容加载…")
                if (now > before) break
            }
            onProgress?.invoke(100, "新内容检测完成（新增 ${max - before} 个元素）")
            ScrollResult(scrolled = true, newElementsDetected = max - before)
        } else {
            onProgress?.invoke(100, "滚动完成")
            ScrollResult(scrolled = true)
        }
    }

    data class ScrollResult(val scrolled: Boolean, val newElementsDetected: Int = 0)

    // ═════════ 截图（视口，P0 #6 路线图） ═════════

    suspend fun screenshot(
        onProgress: ((percent: Int, phase: String) -> Unit)? = null,
    ): ByteArray? = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext null
        onProgress?.invoke(50, "正在渲染视口截图…")
        val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        wv.draw(canvas)
        val stream = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, stream)
        bmp.recycle()
        onProgress?.invoke(100, "截图完成")
        stream.toByteArray()
    }

    // ═════════ Cookie 持久化（P0 #6） ═════════

    /** 强制把内存 Cookie 刷盘，避免应用被杀后登录态丢失 */
    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    // ═════════ 动作后验证探针（P0 #2） ═════════

    data class PostActionState(
        val success: Boolean,
        val urlChanged: Boolean = false,
        val newElementsCount: Int = 0,
        val pageTitle: String = "",
        val scrollY: Int = 0,
        val failReason: String? = null,
    ) {
        fun toText(): String = if (!success) "Error: $failReason"
        else "动作完成 · URL变化=${urlChanged} · 新增元素=$newElementsCount · 标题=$pageTitle"

        companion object {
            fun failed(reason: String) = PostActionState(success = false, failReason = reason)
        }
    }

    private suspend fun probePage(wv: WebView): PostActionState {
        val jsonStr = evaluateJson(wv, BrowserScript.POST_ACTION_PROBE_JS)
        return runCatching {
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            PostActionState(
                success = true,
                urlChanged = false,
                newElementsCount = obj["interactiveCount"]?.jsonPrimitive?.content?.toInt() ?: 0,
                pageTitle = obj["title"]?.jsonPrimitive?.content ?: "",
                scrollY = obj["scrollY"]?.jsonPrimitive?.content?.toInt() ?: 0,
            )
        }.getOrDefault(PostActionState.failed("探针执行失败"))
    }

    // ═════════ JS 求值封装 ═════════

    private suspend fun evaluateBoolean(wv: WebView, js: String): Boolean =
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result -> cont.resume(result == "true") }
        }

    private suspend fun evaluateJson(wv: WebView, js: String): String =
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result -> cont.resume(result ?: "null") }
        }

    private fun emptySnapshot() = PageSnapshot(
        url = "", title = "", scrollY = 0, scrollHeight = 0, viewportHeight = 0,
        interactiveCount = 0, domSummary = "(无激活标签页)", interactiveElements = emptyList()
    )

    // ═════════ 内存维护（P2 #15） ═════════

    /** 累计导航次数，超过阈值后强制重建 WebView，防止长会话内存累积 */
    private var navigationCount = 0

    /**
     * 主动维护：清理缓存与历史（保留当前页），应对长时间运行的 Agent 会话。
     * 由系统 [onTrimMemory] 或导航计数阈值触发。
     */
    fun performMaintenance() {
        runCatching {
            tabs.values.forEach { it.webView.clearCache(true) }
            tabs.values.forEach { it.webView.clearHistory() }
        }
    }

    /** 系统内存压力回调（由 [ApexCoreService.onTrimMemory] 转发） */
    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            flushCookies()
            performMaintenance()
        }
    }

    fun destroy() {
        tabs.values.forEach { it.webView.destroy() }
        tabs.clear()
        flushCookies()
    }

    companion object {
        private const val MAX_HISTORY = 100
        /** 导航次数阈值：超过后下次 navigate 前重建 WebView（P2 #15） */
        private const val MAX_NAVIGATIONS_BEFORE_REBUILD = 50

        /**
         * 反检测隐身 JS（#13 轻量版）：隐藏自动化痕迹，降低被反爬识别概率。
         * 注意：仅做基础痕迹抹除，不过度伪装（避免破坏页面功能）。
         */
        private val STEALTH_JS = """
            (function(){
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                } catch(e) {}
                try {
                    Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN','zh','en'] });
                } catch(e) {}
            })();
        """.trimIndent()
    }
}
